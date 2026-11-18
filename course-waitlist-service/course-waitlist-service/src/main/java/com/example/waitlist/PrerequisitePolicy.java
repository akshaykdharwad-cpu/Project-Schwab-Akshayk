package com.example.waitlist;

import java.util.Objects;

/**
 * Prerequisite rule: a learner is eligible for an offering only if they are a known
 * learner AND have completed every course code listed in the offering's prerequisites.
 * An offering with no prerequisites admits any known learner.
 */
public class PrerequisitePolicy {

    private final LearnerRegistry registry;

    public PrerequisitePolicy(LearnerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public boolean isEligible(String learnerId, CourseOffering offering) {
        return registry.completedCourses(learnerId)
                .map(completed -> completed.containsAll(offering.prerequisites()))
                .orElse(false);
    }
}
