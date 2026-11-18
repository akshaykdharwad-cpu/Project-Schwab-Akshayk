package com.example.waitlist;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of a drop request.
 *
 * @param promoted          learner moved from the waitlist into the freed seat, if any
 * @param removedIneligible waitlisted learners found ineligible at promotion time and
 *                          removed from the queue (skip-and-remove policy), in queue order
 */
public record DropResult(Status status,
                         String learnerId,
                         Optional<String> promoted,
                         List<String> removedIneligible,
                         String message) {

    public DropResult {
        removedIneligible = List.copyOf(removedIneligible);
    }

    public enum Status {
        /** Enrolled learner dropped; seat freed and promotion attempted. */
        DROPPED,
        /** Waitlisted learner left the queue; no seat change. */
        WITHDRAWN_FROM_WAITLIST,
        /** Learner is neither enrolled nor waitlisted (e.g. repeated drop). No state change. */
        NOT_ENROLLED,
        /** Learner id not found in the registry. */
        UNKNOWN_LEARNER,
        /** Null or blank learner id. */
        INVALID_REQUEST
    }

    static DropResult rejected(Status status, String learnerId, String message) {
        return new DropResult(status, learnerId, Optional.empty(), List.of(), message);
    }
}
