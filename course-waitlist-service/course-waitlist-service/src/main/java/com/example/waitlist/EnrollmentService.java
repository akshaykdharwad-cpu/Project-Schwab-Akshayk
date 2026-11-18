package com.example.waitlist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Enrollment, drop and FIFO waitlist promotion for ONE course offering.
 *
 * <h2>Policy</h2>
 * <ul>
 *   <li>A learner is eligible if they completed every prerequisite ({@link PrerequisitePolicy}).</li>
 *   <li>Ineligible learners are rejected at enroll time; they never join the waitlist.</li>
 *   <li>When a seat opens, eligibility is re-checked from the head of the queue. An ineligible
 *       learner at the head is <b>skipped and removed</b> from the waitlist (reported in
 *       {@link DropResult#removedIneligible()}); the next eligible learner is promoted.</li>
 * </ul>
 *
 * <h2>Invariants (hold after every public call)</h2>
 * <ol>
 *   <li>{@code enrolled.size() <= capacity}</li>
 *   <li>No learner appears twice, and no learner is both enrolled and waitlisted.</li>
 *   <li>If the waitlist is non-empty, the offering is full (no idle seat while someone waits).</li>
 * </ol>
 *
 * <h2>Concurrency</h2>
 * Every read and write of {@code enrolled}/{@code waitlist} happens while holding a single
 * per-offering lock, so each operation (including drop + promotion) is atomic.
 */
public class EnrollmentService {

    private final CourseOffering offering;
    private final LearnerRegistry registry;
    private final PrerequisitePolicy prerequisites;

    /** Insertion-ordered sets: O(1) membership checks, deterministic ordering. */
    private final LinkedHashSet<String> enrolled = new LinkedHashSet<>();
    /** Insertion order == arrival order == FIFO promotion order. */
    private final LinkedHashSet<String> waitlist = new LinkedHashSet<>();

    private final ReentrantLock lock = new ReentrantLock(true);

    public EnrollmentService(CourseOffering offering, LearnerRegistry registry) {
        this.offering = Objects.requireNonNull(offering, "offering");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.prerequisites = new PrerequisitePolicy(registry);
    }

    public CourseOffering offering() {
        return offering;
    }

    // ------------------------------------------------------------------ enroll

    public EnrollResult enroll(String learnerId) {
        if (isBlank(learnerId)) {
            return new EnrollResult(EnrollResult.Status.INVALID_REQUEST, learnerId, null,
                    "Learner id is required");
        }
        if (!registry.exists(learnerId)) {
            return new EnrollResult(EnrollResult.Status.UNKNOWN_LEARNER, learnerId, null,
                    "Unknown learner " + learnerId);
        }

        lock.lock();
        try {
            if (enrolled.contains(learnerId)) {
                return new EnrollResult(EnrollResult.Status.ALREADY_ENROLLED, learnerId, null,
                        learnerId + " is already enrolled in " + offering.id());
            }
            if (waitlist.contains(learnerId)) {
                return new EnrollResult(EnrollResult.Status.ALREADY_WAITLISTED, learnerId,
                        positionOf(learnerId),
                        learnerId + " is already on the waitlist for " + offering.id());
            }
            // Eligibility is evaluated inside the lock so the decision and the state change
            // are part of the same atomic step.
            if (!prerequisites.isEligible(learnerId, offering)) {
                return new EnrollResult(EnrollResult.Status.INELIGIBLE, learnerId, null,
                        learnerId + " has not completed prerequisites " + offering.prerequisites());
            }
            if (enrolled.size() < offering.capacity()) {
                enrolled.add(learnerId);
                return new EnrollResult(EnrollResult.Status.ENROLLED, learnerId, null,
                        learnerId + " enrolled in " + offering.id());
            }
            waitlist.add(learnerId);
            int position = waitlist.size();
            return new EnrollResult(EnrollResult.Status.WAITLISTED, learnerId, position,
                    offering.id() + " is full; " + learnerId + " waitlisted at position " + position);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ drop

    public DropResult drop(String learnerId) {
        if (isBlank(learnerId)) {
            return DropResult.rejected(DropResult.Status.INVALID_REQUEST, learnerId,
                    "Learner id is required");
        }
        if (!registry.exists(learnerId)) {
            return DropResult.rejected(DropResult.Status.UNKNOWN_LEARNER, learnerId,
                    "Unknown learner " + learnerId);
        }

        lock.lock();
        try {
            if (enrolled.remove(learnerId)) {
                List<String> removedIneligible = new ArrayList<>();
                Optional<String> promoted = promoteNextEligible(removedIneligible);
                String message = learnerId + " dropped " + offering.id()
                        + promoted.map(p -> "; promoted " + p).orElse("; no eligible learner waiting")
                        + (removedIneligible.isEmpty() ? "" : "; removed ineligible " + removedIneligible);
                return new DropResult(DropResult.Status.DROPPED, learnerId, promoted, removedIneligible, message);
            }
            if (waitlist.remove(learnerId)) {
                return DropResult.rejected(DropResult.Status.WITHDRAWN_FROM_WAITLIST, learnerId,
                        learnerId + " left the waitlist for " + offering.id());
            }
            return DropResult.rejected(DropResult.Status.NOT_ENROLLED, learnerId,
                    learnerId + " is not enrolled in or waitlisted for " + offering.id());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Fills free seats from the head of the waitlist. Must be called while holding {@link #lock}.
     * Ineligible learners encountered are removed and appended to {@code removedIneligible}.
     */
    private Optional<String> promoteNextEligible(List<String> removedIneligible) {
        String promoted = null;
        Iterator<String> it = waitlist.iterator();
        while (enrolled.size() < offering.capacity() && it.hasNext()) {
            String candidate = it.next();
            it.remove();
            if (prerequisites.isEligible(candidate, offering)) {
                enrolled.add(candidate);
                promoted = candidate;
            } else {
                removedIneligible.add(candidate);
            }
        }
        return Optional.ofNullable(promoted);
    }

    // ------------------------------------------------------------------ reads

    public OfferingSnapshot snapshot() {
        lock.lock();
        try {
            return new OfferingSnapshot(offering.id(), offering.capacity(),
                    List.copyOf(enrolled), List.copyOf(waitlist));
        } finally {
            lock.unlock();
        }
    }

    public boolean isEnrolled(String learnerId) {
        lock.lock();
        try {
            return enrolled.contains(learnerId);
        } finally {
            lock.unlock();
        }
    }

    /** 1-based waitlist position, or empty if the learner is not waitlisted. */
    public Optional<Integer> waitlistPosition(String learnerId) {
        lock.lock();
        try {
            return Optional.ofNullable(positionOf(learnerId));
        } finally {
            lock.unlock();
        }
    }

    /** Throws {@link IllegalStateException} if any invariant is violated. Used by tests. */
    void assertInvariants() {
        lock.lock();
        try {
            if (enrolled.size() > offering.capacity()) {
                throw new IllegalStateException("Over capacity: " + enrolled.size() + " > " + offering.capacity());
            }
            Collection<String> overlap = new HashSet<>(enrolled);
            overlap.retainAll(waitlist);
            if (!overlap.isEmpty()) {
                throw new IllegalStateException("Learners both enrolled and waitlisted: " + overlap);
            }
            if (!waitlist.isEmpty() && enrolled.size() < offering.capacity()) {
                throw new IllegalStateException("Idle seat while waitlist is non-empty: enrolled="
                        + enrolled.size() + ", waitlist=" + waitlist.size());
            }
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Must be called while holding {@link #lock}. */
    private Integer positionOf(String learnerId) {
        int position = 1;
        for (String id : waitlist) {
            if (id.equals(learnerId)) {
                return position;
            }
            position++;
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
