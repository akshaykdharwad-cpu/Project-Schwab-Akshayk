package com.example.waitlist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused concurrency tests: many threads released at the same instant via a latch,
 * then the final state is checked against the service invariants.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConcurrencyTest {

    private static final String PREREQ = "CS101";
    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private static LearnerRegistry registryWith(int learners) {
        LearnerRegistry registry = new LearnerRegistry();
        for (int i = 0; i < learners; i++) {
            registry.register("L" + i, Set.of(PREREQ));
        }
        return registry;
    }

    /** Submits all tasks, releases them together, and returns their results in submission order. */
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        return results;
    }

    @RepeatedTest(20)
    void concurrentEnrollmentsNeverExceedCapacity() throws Exception {
        int learners = 50;
        int capacity = 5;
        EnrollmentService service = new EnrollmentService(
                new CourseOffering("CS201", capacity, Set.of(PREREQ)), registryWith(learners));

        List<Callable<EnrollResult>> tasks = new ArrayList<>();
        for (int i = 0; i < learners; i++) {
            String id = "L" + i;
            tasks.add(() -> service.enroll(id));
        }
        List<EnrollResult> results = runTogether(tasks);

        long enrolled = results.stream().filter(r -> r.status() == EnrollResult.Status.ENROLLED).count();
        long waitlisted = results.stream().filter(r -> r.status() == EnrollResult.Status.WAITLISTED).count();
        assertEquals(capacity, enrolled);
        assertEquals(learners - capacity, waitlisted);

        // Every waitlist position handed out is unique: 1..45.
        Set<Integer> positions = new HashSet<>();
        results.stream().filter(r -> r.status() == EnrollResult.Status.WAITLISTED)
                .forEach(r -> positions.add(r.waitlistPosition()));
        assertEquals(learners - capacity, positions.size());

        OfferingSnapshot s = service.snapshot();
        assertEquals(capacity, s.enrolled().size());
        assertEquals(learners - capacity, s.waitlist().size());
        service.assertInvariants();
    }

    @RepeatedTest(20)
    void concurrentDuplicateRequestsCreateExactlyOneRecord() throws Exception {
        EnrollmentService service = new EnrollmentService(
                new CourseOffering("CS201", 3, Set.of(PREREQ)), registryWith(1));

        List<Callable<EnrollResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> service.enroll("L0"));
        }
        List<EnrollResult> results = runTogether(tasks);

        assertEquals(1, results.stream().filter(r -> r.status() == EnrollResult.Status.ENROLLED).count());
        assertEquals(19, results.stream().filter(r -> r.status() == EnrollResult.Status.ALREADY_ENROLLED).count());
        assertEquals(List.of("L0"), service.snapshot().enrolled());
        service.assertInvariants();
    }

    @RepeatedTest(20)
    void concurrentDuplicateDropsFreeOnlyOneSeatAndPromoteOnce() throws Exception {
        // capacity 2: L0, L1 enrolled; L2..L5 waitlisted.
        EnrollmentService service = new EnrollmentService(
                new CourseOffering("CS201", 2, Set.of(PREREQ)), registryWith(6));
        for (int i = 0; i < 6; i++) {
            service.enroll("L" + i);
        }

        List<Callable<DropResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> service.drop("L0"));
        }
        List<DropResult> results = runTogether(tasks);

        assertEquals(1, results.stream().filter(r -> r.status() == DropResult.Status.DROPPED).count());
        assertEquals(19, results.stream().filter(r -> r.status() == DropResult.Status.NOT_ENROLLED).count());
        assertEquals(List.of("L1", "L2"), service.snapshot().enrolled());
        assertEquals(List.of("L3", "L4", "L5"), service.snapshot().waitlist());
        service.assertInvariants();
    }

    @RepeatedTest(20)
    void interleavedDropsAndEnrollmentsPreserveInvariantsAndLoseNoOne() throws Exception {
        int capacity = 10;
        int initial = 30;    // L0..L29 enroll first: 10 enrolled, 20 waitlisted
        int late = 20;       // L30..L49 enroll concurrently with drops
        EnrollmentService service = new EnrollmentService(
                new CourseOffering("CS201", capacity, Set.of(PREREQ)), registryWith(initial + late));
        for (int i = 0; i < initial; i++) {
            service.enroll("L" + i);
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {          // drop every originally enrolled learner
            String id = "L" + i;
            tasks.add(() -> service.drop(id));
        }
        for (int i = initial; i < initial + late; i++) {
            String id = "L" + i;
            tasks.add(() -> service.enroll(id));
        }
        runTogether(tasks);

        OfferingSnapshot s = service.snapshot();
        service.assertInvariants();
        assertEquals(capacity, s.enrolled().size());

        // Conservation: 50 learners - 10 dropped = 40 must be either enrolled or waitlisted, exactly once.
        Set<String> all = new HashSet<>(s.enrolled());
        all.addAll(s.waitlist());
        assertEquals(s.enrolled().size() + s.waitlist().size(), all.size(), "duplicate learner found");
        assertEquals(initial + late - capacity, all.size());
        for (int i = 0; i < capacity; i++) {
            assertTrue(!all.contains("L" + i), "dropped learner L" + i + " reappeared");
        }
        // The original waitlist (L10..L29) was ahead of all late arrivals, so FIFO says
        // the 10 freed seats go to L10..L19 regardless of how threads interleaved.
        for (int i = capacity; i < 2 * capacity; i++) {
            assertTrue(s.enrolled().contains("L" + i), "expected FIFO promotion of L" + i);
        }
    }
}
