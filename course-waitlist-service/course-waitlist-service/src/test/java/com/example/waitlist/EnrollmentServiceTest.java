package com.example.waitlist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentServiceTest {

    private static final String PREREQ = "CS101";

    private LearnerRegistry registry;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        registry = new LearnerRegistry();
        for (String id : List.of("a", "b", "c", "d", "e")) {
            registry.register(id, Set.of(PREREQ));
        }
        registry.register("noPrereq", Set.of());
        service = new EnrollmentService(new CourseOffering("CS201", 2, Set.of(PREREQ)), registry);
    }

    @AfterEach
    void invariantsHold() {
        service.assertInvariants();
    }

    private void fill() {
        assertEquals(EnrollResult.Status.ENROLLED, service.enroll("a").status());
        assertEquals(EnrollResult.Status.ENROLLED, service.enroll("b").status());
    }

    @Nested
    @DisplayName("AC1: available capacity")
    class AvailableCapacity {

        @Test
        void eligibleLearnerEnrollsWhenSeatFree() {
            EnrollResult r = service.enroll("a");

            assertEquals(EnrollResult.Status.ENROLLED, r.status());
            assertTrue(r.accepted());
            assertTrue(service.isEnrolled("a"));
            assertEquals(1, service.snapshot().seatsAvailable());
        }

        @Test
        void offeringWithoutPrerequisitesAdmitsAnyKnownLearner() {
            EnrollmentService open = new EnrollmentService(new CourseOffering("ART100", 1, Set.of()), registry);

            assertEquals(EnrollResult.Status.ENROLLED, open.enroll("noPrereq").status());
            open.assertInvariants();
        }
    }

    @Nested
    @DisplayName("AC2: full capacity")
    class FullCapacity {

        @Test
        void eligibleLearnerIsWaitlistedInArrivalOrder() {
            fill();

            EnrollResult c = service.enroll("c");
            EnrollResult d = service.enroll("d");

            assertEquals(EnrollResult.Status.WAITLISTED, c.status());
            assertEquals(1, c.waitlistPosition());
            assertEquals(EnrollResult.Status.WAITLISTED, d.status());
            assertEquals(2, d.waitlistPosition());

            OfferingSnapshot s = service.snapshot();
            assertEquals(List.of("a", "b"), s.enrolled());
            assertEquals(List.of("c", "d"), s.waitlist());
            assertEquals(0, s.seatsAvailable());
        }
    }

    @Nested
    @DisplayName("AC2: duplicate requests")
    class Duplicates {

        @Test
        void reEnrollingAnEnrolledLearnerDoesNotDuplicate() {
            fill();
            OfferingSnapshot before = service.snapshot();

            EnrollResult r = service.enroll("a");

            assertEquals(EnrollResult.Status.ALREADY_ENROLLED, r.status());
            assertFalse(r.accepted());
            assertEquals(before, service.snapshot());
        }

        @Test
        void reEnrollingAWaitlistedLearnerKeepsOriginalPosition() {
            fill();
            service.enroll("c");
            service.enroll("d");
            OfferingSnapshot before = service.snapshot();

            EnrollResult r = service.enroll("c");

            assertEquals(EnrollResult.Status.ALREADY_WAITLISTED, r.status());
            assertEquals(1, r.waitlistPosition());
            assertEquals(before, service.snapshot());
        }
    }

    @Nested
    @DisplayName("AC3: FIFO promotion")
    class FifoPromotion {

        @Test
        void dropPromotesEarliestWaitlistedLearner() {
            fill();
            service.enroll("c");
            service.enroll("d");

            DropResult r = service.drop("a");

            assertEquals(DropResult.Status.DROPPED, r.status());
            assertEquals(Optional.of("c"), r.promoted());
            assertTrue(r.removedIneligible().isEmpty());
            assertEquals(List.of("b", "c"), service.snapshot().enrolled());
            assertEquals(List.of("d"), service.snapshot().waitlist());
        }

        @Test
        void successiveDropsPromoteInArrivalOrder() {
            fill();
            service.enroll("c");
            service.enroll("d");
            service.enroll("e");

            assertEquals(Optional.of("c"), service.drop("a").promoted());
            assertEquals(Optional.of("d"), service.drop("b").promoted());
            assertEquals(Optional.of("e"), service.drop("c").promoted());
            assertEquals(List.of("d", "e"), service.snapshot().enrolled());
            assertTrue(service.snapshot().waitlist().isEmpty());
        }

        @Test
        void dropWithEmptyWaitlistLeavesSeatOpen() {
            fill();

            DropResult r = service.drop("a");

            assertEquals(DropResult.Status.DROPPED, r.status());
            assertEquals(Optional.empty(), r.promoted());
            assertEquals(1, service.snapshot().seatsAvailable());
        }

        @Test
        void withdrawnLearnerIsNotPromotedLater() {
            fill();
            service.enroll("c");
            service.enroll("d");

            assertEquals(DropResult.Status.WITHDRAWN_FROM_WAITLIST, service.drop("c").status());
            assertEquals(Optional.of("d"), service.drop("a").promoted());
        }
    }

    @Nested
    @DisplayName("AC4: prerequisite failure (skip-and-remove policy)")
    class Prerequisites {

        @Test
        void ineligibleLearnerIsNotEnrolledWhenSeatFree() {
            EnrollResult r = service.enroll("noPrereq");

            assertEquals(EnrollResult.Status.INELIGIBLE, r.status());
            assertFalse(service.isEnrolled("noPrereq"));
            assertEquals(2, service.snapshot().seatsAvailable());
        }

        @Test
        void ineligibleLearnerDoesNotJoinWaitlistWhenFull() {
            fill();

            EnrollResult r = service.enroll("noPrereq");

            assertEquals(EnrollResult.Status.INELIGIBLE, r.status());
            assertTrue(service.snapshot().waitlist().isEmpty());
        }

        @Test
        void learnerWhoBecomesIneligibleWhileWaitingIsSkippedAndRemoved() {
            fill();
            service.enroll("c");
            service.enroll("d");
            registry.revokeCompletion("c", PREREQ);

            DropResult r = service.drop("a");

            assertEquals(Optional.of("d"), r.promoted());
            assertEquals(List.of("c"), r.removedIneligible());
            assertFalse(service.isEnrolled("c"));
            assertEquals(Optional.empty(), service.waitlistPosition("c"));
            assertEquals(List.of("b", "d"), service.snapshot().enrolled());
            assertTrue(service.snapshot().waitlist().isEmpty());
        }

        @Test
        void allWaitlistedIneligibleLeavesSeatOpenAndQueueEmpty() {
            fill();
            service.enroll("c");
            service.enroll("d");
            registry.revokeCompletion("c", PREREQ);
            registry.revokeCompletion("d", PREREQ);

            DropResult r = service.drop("a");

            assertEquals(Optional.empty(), r.promoted());
            assertEquals(List.of("c", "d"), r.removedIneligible());
            assertEquals(1, service.snapshot().seatsAvailable());
            assertTrue(service.snapshot().waitlist().isEmpty());
        }

        @Test
        void removedLearnerWhoRegainsEligibilityRejoinsAtBackOfQueue() {
            fill();
            service.enroll("c");
            service.enroll("d");
            registry.revokeCompletion("c", PREREQ);
            service.drop("a"); // c removed, d promoted -> enrolled [b, d]
            service.enroll("e"); // full -> e waitlisted at 1

            registry.recordCompletion("c", PREREQ);
            EnrollResult r = service.enroll("c");

            assertEquals(EnrollResult.Status.WAITLISTED, r.status());
            assertEquals(2, r.waitlistPosition());
            assertEquals(List.of("e", "c"), service.snapshot().waitlist());
        }

        @Test
        void ineligibleLearnerBecomingEligibleCanThenEnroll() {
            assertEquals(EnrollResult.Status.INELIGIBLE, service.enroll("noPrereq").status());

            registry.recordCompletion("noPrereq", PREREQ);

            assertEquals(EnrollResult.Status.ENROLLED, service.enroll("noPrereq").status());
        }
    }

    @Nested
    @DisplayName("AC5: invalid requests and repeated drops")
    class InvalidAndRepeatedDrops {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void blankIdIsRejectedWithoutStateChange(String id) {
            fill();
            OfferingSnapshot before = service.snapshot();

            assertEquals(EnrollResult.Status.INVALID_REQUEST, service.enroll(id).status());
            assertEquals(DropResult.Status.INVALID_REQUEST, service.drop(id).status());
            assertEquals(before, service.snapshot());
        }

        @Test
        void unknownLearnerIsRejectedWithoutStateChange() {
            fill();
            OfferingSnapshot before = service.snapshot();

            assertEquals(EnrollResult.Status.UNKNOWN_LEARNER, service.enroll("ghost").status());
            assertEquals(DropResult.Status.UNKNOWN_LEARNER, service.drop("ghost").status());
            assertEquals(before, service.snapshot());
        }

        @Test
        void droppingANeverEnrolledLearnerIsReportedAndHarmless() {
            fill();
            service.enroll("c");
            OfferingSnapshot before = service.snapshot();

            DropResult r = service.drop("d");

            assertEquals(DropResult.Status.NOT_ENROLLED, r.status());
            assertEquals(before, service.snapshot());
        }

        @Test
        void repeatedDropDoesNotFreeASecondSeatOrPromoteTwice() {
            fill();
            service.enroll("c");
            service.enroll("d");

            DropResult first = service.drop("a");
            OfferingSnapshot afterFirst = service.snapshot();
            DropResult second = service.drop("a");

            assertEquals(DropResult.Status.DROPPED, first.status());
            assertEquals(DropResult.Status.NOT_ENROLLED, second.status());
            assertEquals(Optional.empty(), second.promoted());
            assertEquals(afterFirst, service.snapshot());
            assertEquals(List.of("d"), service.snapshot().waitlist());
        }

        @Test
        void repeatedWaitlistWithdrawalIsReportedAndHarmless() {
            fill();
            service.enroll("c");

            assertEquals(DropResult.Status.WITHDRAWN_FROM_WAITLIST, service.drop("c").status());
            assertEquals(DropResult.Status.NOT_ENROLLED, service.drop("c").status());
            assertEquals(List.of("a", "b"), service.snapshot().enrolled());
        }

        @Test
        void invalidOfferingConfigurationIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new CourseOffering("X", 0, Set.of()));
            assertThrows(IllegalArgumentException.class, () -> new CourseOffering("X", -1, Set.of()));
            assertThrows(IllegalArgumentException.class, () -> new CourseOffering(" ", 1, Set.of()));
        }
    }
}
