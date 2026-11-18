# Course Waitlist Service

Enrollment, drop and FIFO waitlist promotion for a **single course offering**. It is an in-memory
Java service with a small CLI, built as the fallback the assessment allows when no starter project
is supplied.

- Design specification: [docs/DESIGN.md](docs/DESIGN.md)
- AI prompt and use record: [docs/AI_USAGE.md](docs/AI_USAGE.md)

## Requirements

- JDK 21+
- Maven 3.9+

There are no runtime dependencies. JUnit 5 is used for tests only.

## Build, test, run

```bash
# Run all tests (unit + concurrency)
mvn test

# Run one test class
mvn test -Dtest=ConcurrencyTest

# Build the jar and start the interactive CLI
mvn package
java -jar target/course-waitlist-service-1.0.0.jar
```

Expected test summary: `Tests run: 103, Failures: 0, Errors: 0, Skipped: 0`. This counts the 4
concurrency tests × 20 repetitions, plus parameterized cases.

### CLI

The CLI is seeded with offering **CS201** (capacity 2, prerequisite **CS101**):

- `alice`, `bob`, `carol` and `dave` have completed CS101.
- `eve` has not completed CS101.

Commands: `enroll <id>`, `drop <id>`, `status`, `help`, `quit`.

```
> enroll alice      ENROLLED: alice enrolled in CS201
> enroll bob        ENROLLED: bob enrolled in CS201
> enroll carol      WAITLISTED: CS201 is full; carol waitlisted at position 1
> enroll dave       WAITLISTED: CS201 is full; dave waitlisted at position 2
> enroll carol      ALREADY_WAITLISTED: carol is already on the waitlist for CS201
> enroll eve        INELIGIBLE: eve has not completed prerequisites [CS101]
> drop alice        DROPPED: alice dropped CS201; promoted carol
> drop alice        NOT_ENROLLED: alice is not enrolled in or waitlisted for CS201
> status            enrolled=[bob, carol] waitlist=[dave] seatsAvailable=0
```

## Assumptions (summary)

- **Prerequisite:** a learner must have completed *all* course codes listed on the offering.
- **Ineligible at enroll:** the request is rejected, and the learner is not enrolled or waitlisted.
- **Ineligible at promotion — skip and remove:** when a seat opens, the waitlist is checked again
  from the head. A learner who has become ineligible is removed from the queue and listed in the
  drop result, and the next eligible learner is promoted. If they become eligible later, they
  enroll again at the back of the queue.
- **Duplicates:** repeating an enroll or drop request returns `ALREADY_*` or `NOT_ENROLLED`, and
  state does not change.
- **Drop while waitlisted:** the learner is removed from the queue (`WITHDRAWN_FROM_WAITLIST`), and
  no seat changes.
- **FIFO:** order is the order in which the service accepted the waitlist entries.

See [docs/DESIGN.md](docs/DESIGN.md) for the full rationale.

## Project layout

```
src/main/java/com/example/waitlist/
  EnrollmentService.java   core logic, invariants, locking
  CourseOffering.java      offering config (id, capacity, prerequisites)
  LearnerRegistry.java     in-memory learner records (completed courses)
  PrerequisitePolicy.java  eligibility rule
  EnrollResult.java / DropResult.java / OfferingSnapshot.java
  Main.java                CLI adapter
src/test/java/com/example/waitlist/
  EnrollmentServiceTest.java   acceptance criteria 1-6, grouped with @Nested
  ConcurrencyTest.java         multi-threaded invariant tests
```

## Limitations and follow-up risks

1. **No persistence.** All state is lost on exit. The single JVM lock does not protect several
   service instances running against shared storage. A real deployment needs DB transactions,
   unique constraints and a sequence column (see DESIGN §7).
2. **Eligibility is checked only at enroll and at promotion.** If an *enrolled* learner loses a
   prerequisite, they keep their seat. Whether to revoke seats is a policy question that was left
   out of scope.
3. **Immediate promotion.** A promoted learner is enrolled straight away. There is no
   offer-and-accept window, no notification and no expiry.
4. **Removal of ineligible learners is reported but not persisted.** `removedIneligible` appears in
   the `DropResult`, but there is no audit log or notification for the removed learner.
5. **Capacity is fixed.** Changing capacity at runtime, which would need promotion or eviction
   rules, is not supported.
6. **Waitlist position lookup is O(n),** and the queue has no size limit. This is fine for
   one offering but worth revisiting at scale.
7. **The concurrency tests show the absence of races only for the scenarios tested.** They ran
   reliably here (20 repetitions each) and fail when the lock is removed (see AI_USAGE.md). They are
   not a formal proof.
8. The rubric file `Course-Registration-Rubric-Score.md` named in the brief was not supplied, so it
   could not be checked against.
