package com.example.waitlist;

import java.util.Objects;
import java.util.Set;

/**
 * A single course offering: an identifier, a fixed seat capacity and the set of
 * course codes a learner must have completed before enrolling.
 */
public record CourseOffering(String id, int capacity, Set<String> prerequisites) {

    public CourseOffering {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Offering id must not be blank");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive, was " + capacity);
        }
        prerequisites = Set.copyOf(Objects.requireNonNull(prerequisites, "prerequisites"));
    }
}
