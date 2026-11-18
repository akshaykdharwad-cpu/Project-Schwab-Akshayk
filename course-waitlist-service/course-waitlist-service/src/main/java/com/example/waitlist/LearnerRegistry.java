package com.example.waitlist;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of known learners and the course codes each has completed.
 * Stands in for an external student-records system; thread-safe.
 */
public class LearnerRegistry {

    private final Map<String, Set<String>> completedByLearner = new ConcurrentHashMap<>();

    /** Registers a learner (or replaces their record) with the given completed courses. */
    public void register(String learnerId, Set<String> completedCourses) {
        Objects.requireNonNull(learnerId, "learnerId");
        Set<String> courses = ConcurrentHashMap.newKeySet();
        courses.addAll(completedCourses);
        completedByLearner.put(learnerId, courses);
    }

    public boolean exists(String learnerId) {
        return learnerId != null && completedByLearner.containsKey(learnerId);
    }

    /** Completed course codes for the learner, or empty if the learner is unknown. */
    public Optional<Set<String>> completedCourses(String learnerId) {
        if (learnerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(completedByLearner.get(learnerId)).map(Set::copyOf);
    }

    public void recordCompletion(String learnerId, String courseCode) {
        requireKnown(learnerId).add(courseCode);
    }

    /** Removes a completion, e.g. a grade was revoked. Used to model eligibility changing over time. */
    public void revokeCompletion(String learnerId, String courseCode) {
        requireKnown(learnerId).remove(courseCode);
    }

    private Set<String> requireKnown(String learnerId) {
        Set<String> courses = learnerId == null ? null : completedByLearner.get(learnerId);
        if (courses == null) {
            throw new IllegalArgumentException("Unknown learner: " + learnerId);
        }
        return courses;
    }
}
