package com.example.waitlist;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Minimal command-line adapter over {@link EnrollmentService}. Contains no business rules.
 *
 * <pre>
 *   enroll &lt;learnerId&gt;   drop &lt;learnerId&gt;   status   help   quit
 * </pre>
 *
 * Seed data: offering CS201 (capacity 2, prerequisite CS101). Learners alice, bob, carol,
 * dave have completed CS101; eve has not.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        LearnerRegistry registry = new LearnerRegistry();
        registry.register("alice", Set.of("CS101"));
        registry.register("bob", Set.of("CS101"));
        registry.register("carol", Set.of("CS101"));
        registry.register("dave", Set.of("CS101"));
        registry.register("eve", Set.of());

        EnrollmentService service = new EnrollmentService(
                new CourseOffering("CS201", 2, Set.of("CS101")), registry);

        PrintStream out = System.out;
        out.println("Course waitlist CLI - offering CS201 (capacity 2, prerequisite CS101).");
        out.println("Learners: alice, bob, carol, dave (eligible), eve (missing CS101). Type 'help'.");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            // Strip a UTF-8 byte-order mark: PowerShell adds one when piping text to stdin.
            if (line.startsWith("﻿")) {
                line = line.substring(1);
            }
            String[] parts = line.trim().split("\\s+", 2);
            String command = parts[0].toLowerCase();
            String arg = parts.length > 1 ? parts[1].trim() : "";
            switch (command) {
                case "" -> { }
                case "enroll" -> {
                    EnrollResult r = service.enroll(arg);
                    out.println(r.status() + ": " + r.message());
                }
                case "drop" -> {
                    DropResult r = service.drop(arg);
                    out.println(r.status() + ": " + r.message());
                }
                case "status" -> {
                    OfferingSnapshot s = service.snapshot();
                    out.println("enrolled=" + s.enrolled() + " waitlist=" + s.waitlist()
                            + " seatsAvailable=" + s.seatsAvailable());
                }
                case "help" -> out.println("Commands: enroll <id> | drop <id> | status | quit");
                case "quit", "exit" -> {
                    return;
                }
                default -> out.println("Unknown command '" + command + "'. Type 'help'.");
            }
        }
    }
}
