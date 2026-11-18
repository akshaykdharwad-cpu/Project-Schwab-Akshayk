# Design Specification — Course Waitlist Service

## 1. Scope and service boundary

This service handles enrollment, drops and FIFO waitlist promotion for **one course offering**. No
starter project was supplied, so this follows the brief's fallback: an in-memory Java service with a
thin CLI adapter.

```
 CLI (Main) ──► EnrollmentService ──► PrerequisitePolicy ──► LearnerRegistry
  adapter only    owns seats + waitlist   eligibility rule       learner records (stand-in for
  no rules        + the per-offering lock                         an external records system)
```

- `EnrollmentService` is the only component that changes seat or waitlist state.
- `LearnerRegistry` stands in for an external student-records system. The service only reads
  from it. Tests write to it to simulate eligibility changing over time.
- Out of scope: catalog, multiple offerings or terms, identity, payments, persistence, frontend.

## 2. Data model

| Type | Fields | Notes |
|---|---|---|
| `CourseOffering` | `id`, `capacity`, `prerequisites: Set<String>` | Immutable. Rejects `capacity <= 0` and a blank id. |
| `LearnerRegistry` | `learnerId → Set<completed course codes>` | `ConcurrentHashMap`. |
| `EnrollmentService` state | `enrolled: LinkedHashSet<String>`, `waitlist: LinkedHashSet<String>` | Insertion order = arrival order. A set prevents duplicate records. |
| `OfferingSnapshot` | `offeringId`, `capacity`, `enrolled`, `waitlist`, `seatsAvailable()` | Immutable, consistent copy taken under the lock. |

## 3. API

| Operation | Result statuses |
|---|---|
| `EnrollResult enroll(learnerId)` | `ENROLLED`, `WAITLISTED(position)`, `ALREADY_ENROLLED`, `ALREADY_WAITLISTED(position)`, `INELIGIBLE`, `UNKNOWN_LEARNER`, `INVALID_REQUEST` |
| `DropResult drop(learnerId)` | `DROPPED(promoted?, removedIneligible[])`, `WITHDRAWN_FROM_WAITLIST`, `NOT_ENROLLED`, `UNKNOWN_LEARNER`, `INVALID_REQUEST` |
| `snapshot()`, `isEnrolled(id)`, `waitlistPosition(id)` | read-only |

Expected business outcomes are returned as typed results with a readable `message`. They are never
thrown as exceptions. Only programmer errors, such as an invalid `CourseOffering`, throw. Every
non-success status leaves state **unchanged**.

`enroll` checks run in this order. The first one that matches wins:
1. blank id → `INVALID_REQUEST`
2. unknown learner → `UNKNOWN_LEARNER`
3. already enrolled → `ALREADY_ENROLLED`
4. already waitlisted → `ALREADY_WAITLISTED`
5. prerequisites not met → `INELIGIBLE`
6. seat free → `ENROLLED`; otherwise → `WAITLISTED`

Duplicate checks (3, 4) come before the eligibility check (5). An enrolled learner whose eligibility
was later revoked therefore gets `ALREADY_ENROLLED`, not `INELIGIBLE`. This reports the learner's
true state.

`drop` on a **waitlisted** learner removes them from the queue (`WITHDRAWN_FROM_WAITLIST`). No seat
opens and no promotion runs.

## 4. Invariants

These hold after every public call. Tests check them with the package-private
`assertInvariants()`.

1. **Capacity:** `enrolled.size() <= capacity`.
2. **Uniqueness:** no learner appears twice, and no learner is both enrolled and waitlisted.
3. **No idle seat while someone waits:** if the waitlist is non-empty, `enrolled.size() == capacity`.
4. **Eligibility at admission:** a learner gains a seat or a waitlist entry only after passing the
   prerequisite check in the same atomic step.

## 5. Prerequisite and waitlist policy (assumptions)

- **Prerequisite rule:** a learner is eligible if they have completed **every** course code in
  `offering.prerequisites()`. An empty set means any known learner is eligible.
- **Enroll time:** an ineligible learner is rejected (`INELIGIBLE`) and is **never added to the
  waitlist**.
- **Promotion time: skip and remove.** When a seat opens, eligibility is checked again starting at
  the head of the queue. Eligibility can change while a learner waits, for example when a grade is
  revoked. An ineligible learner at the head is **removed from the waitlist** and listed in
  `DropResult.removedIneligible`, then the next learner is checked. The first eligible learner gets
  the seat. If nobody is eligible, the seat stays open and the waitlist ends up empty.
- A removed learner who becomes eligible again must call `enroll` again. They join the **back** of
  the queue and do not get their old position back.
- **FIFO** means the order in which the service accepted waitlist entries, which is the order in
  which the lock was acquired. This is not client-side request timestamps.
- The waitlist has no size limit, and a promoted learner gets the seat immediately. There is no
  offer that must be accepted.

**Why skip-and-remove rather than skip-and-retain:** keeping ineligible learners in the queue lets
it fill up with people who can never be promoted. Every promotion would rescan them, and the result
of a drop would be harder to explain. Removing them keeps the queue made of currently-eligible
learners and makes every removal visible in the drop result.

## 6. Concurrency approach

- One fair `ReentrantLock` per `EnrollmentService` (per offering) guards every read and write of
  `enrolled` and `waitlist`.
- Each operation runs as one critical section: check duplicates → check eligibility → check
  capacity → mutate. A drop and its promotion also run as one critical section. Another thread can
  never see a freed seat before promotion has run, so it can never take that seat ahead of the
  waitlist.
- Input validation and the `registry.exists` check run before the lock. They do not touch offering
  state.
- Reads (`snapshot`, etc.) take the lock, so they always see a consistent pair of lists.
- **Tradeoff:** a single coarse lock serializes all operations on one offering. At the contention
  level of a single course offering this is simple and obviously correct. A lock-free or
  finer-grained design would add risk and gain nothing measurable here.
- The concurrency tests use a `CountDownLatch` start gate. With 16 threads they cover these cases,
  each repeated 20 times:
  - 50 learners enroll concurrently into 5 seats
  - 20 duplicate enrolls
  - 20 duplicate drops
  - interleaved drops and enrolls, with conservation checks and FIFO checks

## 7. Persistence

Everything is in memory and is lost when the process exits. A production version would keep the
same service contract and move the critical section into the database:

- a transaction with `SELECT … FOR UPDATE` on the offering row
- a unique constraint on `(offering_id, learner_id)` for enrollments and for waitlist entries
- a monotonic sequence column for FIFO ordering

## 8. Key tradeoffs

| Decision | Alternative | Reason |
|---|---|---|
| Typed result records | Exceptions for business outcomes | Duplicates and invalid requests are expected. Callers branch on status. |
| `LinkedHashSet` for the waitlist | `ArrayDeque` + `HashSet` | One structure gives FIFO order and O(1) duplicate checks. Finding a position is O(n), which is fine at this size. |
| Coarse per-offering lock | Fine-grained / optimistic | Correctness is easy to see, and contention is low. |
| Check eligibility again at promotion | Trust the check made at enroll time | Prerequisites can change while a learner waits. AC4 requires that ineligible learners are never promoted. |
