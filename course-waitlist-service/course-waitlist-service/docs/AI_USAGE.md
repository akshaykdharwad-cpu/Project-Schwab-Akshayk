# AI Prompt and Use Record

**Tool:** Claude Code (Anthropic), used in the VS Code extension.
**Activities AI was used for:** planning and breaking down the task, implementation, test design,
bug-injection checks of the tests, and drafting the documentation.
**No credentials, secrets or personal data** were included in the prompts. The only input was the
assessment brief (PDF) and the instructions below.

> **Candidate note:** sections 1–4 record what happened in the AI session. Section 5 is for your own
> independent review. Fill it in after you have read and run the code yourself. Reviewers
> specifically look for evidence that the candidate checked AI output and did not accept it blindly.

---

## 1. Prompts used

| # | Prompt (summary or verbatim) | Activity |
|---|---|---|
| P1 | *(Attached the assessment brief PDF)* "Here we want to implement this one for job assessment. Can you provide implementation code for it please." | Task intake |
| P2 | AI asked: *"The project folder is empty (no starter)… Which language should it be in?"* Answer: **Java + JUnit (Maven)** | Stack decision |
| P3 | AI asked: *"Which policy should we document and enforce for an INELIGIBLE learner at the front of the waitlist?"* Options were *Skip & remove* or *Skip but retain*. Answer: **Skip & remove** | Policy decision |
| P4 | Approved the AI's written implementation plan (layout, API, invariants, test matrix, verification steps) | Plan review |

## 2. AI responses (summaries)

**R1 – Exploring the project and breaking down the task.** The AI checked the working directory and
found it **empty**, so there was no starter data model or test convention to follow. It checked
which toolchains were installed (JDK 21, Maven 3.9.9, Python, Node). Following the brief's
fallback, it proposed an in-memory service with a thin CLI. It did not build a frontend, database
or policy engine.

**R2 – Plan.** The plan split the work into:
- a domain layer (`CourseOffering`, `LearnerRegistry`, `PrerequisitePolicy`)
- a single stateful `EnrollmentService` guarded by one per-offering `ReentrantLock`
- typed result records instead of exceptions
- an explicit list of invariants
- a test matrix mapped one-to-one to acceptance criteria 1–6, plus a separate concurrency test
  class

**R3 – Implementation.** The AI wrote all the files listed in the README. Key excerpt, the
promotion loop, which runs under the lock inside `drop`:

```java
while (enrolled.size() < offering.capacity() && it.hasNext()) {
    String candidate = it.next();
    it.remove();
    if (prerequisites.isEligible(candidate, offering)) {
        enrolled.add(candidate);
        promoted = candidate;
    } else {
        removedIneligible.add(candidate);   // skip-and-remove policy
    }
}
```

**R4 – Verification.** The AI ran the tests, ran bug-injection checks, and ran a scripted CLI
session. The results are in section 4.

## 3. Suggestions: accepted, edited, rejected

| Suggestion | Decision | Notes / files affected |
|---|---|---|
| Use the in-memory service + CLI fallback because no starter exists | **Accepted** | Whole project. Confirmed by checking that the directory was empty. |
| Python + pytest as the recommended stack | **Rejected** | Chose Java + JUnit so the concurrency test could use real threads. `pom.xml` |
| Skip-and-remove policy for ineligible learners | **Accepted** | `EnrollmentService.promoteNextEligible`, DESIGN §5 |
| Skip-but-retain policy | **Rejected** | It lets the queue fill with learners who cannot be promoted (DESIGN §5). |
| Planned invariant "every enrolled/waitlisted learner was eligible at admission" as a runtime assertion | **Edited** | Not put in `assertInvariants()`. It is a historical property: eligibility can change later, so it cannot be checked from current state. It is enforced by construction instead (the eligibility check and the state change happen under the same lock) and tested through the `Prerequisites` tests. `EnrollmentService.java`, DESIGN §4 |
| Check eligibility again at promotion, not only at enroll | **Accepted** | Required for AC4. Covered by the `Prerequisites` tests. |
| Order duplicate checks before the eligibility check in `enroll` | **Accepted** | A revoked-but-enrolled learner gets `ALREADY_ENROLLED`, which reports their true state. DESIGN §3 |
| `drop` on a waitlisted learner withdraws them from the queue | **Accepted** | Small and natural. Without it there would be no way to leave the queue. `DropResult.WITHDRAWN_FROM_WAITLIST` |
| CLI first version (no BOM handling) | **Edited after verification** | The scripted demo showed the first command read as `?enroll`, because PowerShell adds a UTF-8 BOM to piped stdin. A BOM-stripping fix was added in `Main.java`. |

## 4. Verification evidence

### 4.1 Automated tests: `mvn test`

```
[INFO] Tests run: 80, Failures: 0, Errors: 0 -- in com.example.waitlist.ConcurrencyTest
[INFO] Tests run: 8,  Failures: 0, Errors: 0 -- in EnrollmentServiceTest$InvalidAndRepeatedDrops
[INFO] Tests run: 6,  Failures: 0, Errors: 0 -- in EnrollmentServiceTest$Prerequisites
[INFO] Tests run: 4,  Failures: 0, Errors: 0 -- in EnrollmentServiceTest$FifoPromotion
[INFO] Tests run: 2,  Failures: 0, Errors: 0 -- in EnrollmentServiceTest$Duplicates
[INFO] Tests run: 1,  Failures: 0, Errors: 0 -- in EnrollmentServiceTest$FullCapacity
[INFO] Tests run: 2,  Failures: 0, Errors: 0 -- in EnrollmentServiceTest$AvailableCapacity
[INFO] Tests run: 103, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Every `EnrollmentServiceTest` test also runs `assertInvariants()` in `@AfterEach`.

### 4.2 Bug-injection check: do the tests catch real bugs?

Each bug was injected into a **throwaway copy** of the project, and the full suite was run against
it. The real source was not changed.

| Injected bug | Result |
|---|---|
| M1: capacity off-by-one (`<` changed to `<=` in `enroll`) | **Caught.** 10 failures + 4 errors, including `FullCapacity` and the concurrency tests. |
| M2: no eligibility check at promotion (`if (true)`) | **Caught.** 3 failures in `Prerequisites`. |
| M3: all locking removed | **Caught.** `concurrentEnrollmentsNeverExceedCapacity` and `concurrentDuplicateRequestsCreateExactlyOneRecord` failed. |

M3 shows that the concurrency tests really depend on the lock and are not passing by luck.

### 4.3 CLI run (after the BOM fix)

```
ENROLLED: alice enrolled in CS201
ENROLLED: bob enrolled in CS201
WAITLISTED: CS201 is full; carol waitlisted at position 1
WAITLISTED: CS201 is full; dave waitlisted at position 2
ALREADY_WAITLISTED: carol is already on the waitlist for CS201
INELIGIBLE: eve has not completed prerequisites [CS101]
UNKNOWN_LEARNER: Unknown learner ghost
INVALID_REQUEST: Learner id is required
enrolled=[alice, bob] waitlist=[carol, dave] seatsAvailable=0
DROPPED: alice dropped CS201; promoted carol
enrolled=[bob, carol] waitlist=[dave] seatsAvailable=0
NOT_ENROLLED: alice is not enrolled in or waitlisted for CS201
DROPPED: carol dropped CS201; promoted dave
NOT_ENROLLED: eve is not enrolled in or waitlisted for CS201
enrolled=[bob, dave] waitlist=[] seatsAvailable=0
```

## 5. Candidate's independent review *(to be completed by the candidate)*

- [ ] Read `EnrollmentService` end to end and confirmed that every state change happens inside
      `lock.lock() … finally unlock()`.
- [ ] Traced the policy by hand: the waitlist is [X (ineligible), Y (eligible)], a seat opens →
      X is removed, Y is promoted.
- [ ] Ran `mvn test` locally. Result: ______
- [ ] Ran the CLI and tried my own scenario: ______
- [ ] Changes I made to the AI output, and why: ______
- [ ] Anything I disagree with or would do differently: ______
