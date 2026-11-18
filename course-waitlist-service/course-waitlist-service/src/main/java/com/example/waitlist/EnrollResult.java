package com.example.waitlist;

/**
 * Outcome of an enroll request. {@code waitlistPosition} is 1-based and only set for
 * {@link Status#WAITLISTED} and {@link Status#ALREADY_WAITLISTED}; otherwise null.
 */
public record EnrollResult(Status status, String learnerId, Integer waitlistPosition, String message) {

    public enum Status {
        /** Learner took a free seat. */
        ENROLLED,
        /** Course full; learner appended to the waitlist. */
        WAITLISTED,
        /** Duplicate request: learner already holds a seat. No state change. */
        ALREADY_ENROLLED,
        /** Duplicate request: learner already on the waitlist. No state change. */
        ALREADY_WAITLISTED,
        /** Learner fails the prerequisite rule. Not enrolled, not waitlisted. */
        INELIGIBLE,
        /** Learner id not found in the registry. */
        UNKNOWN_LEARNER,
        /** Null or blank learner id. */
        INVALID_REQUEST
    }

    /** True if the request changed state (a seat or waitlist entry was created). */
    public boolean accepted() {
        return status == Status.ENROLLED || status == Status.WAITLISTED;
    }
}
