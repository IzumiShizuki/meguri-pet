## Purpose

Provide a reproducible offline evaluator for the memory, desktop GUI, and cross-client targets in the graduation task sheet. Its reports distinguish observed results, synthetic demonstrations, missing evidence, and insufficient coverage so subsequent strengthening can be compared honestly.

## ADDED Requirements

### Requirement: Explicit evidence and strict input
The evaluator SHALL accept documented JSON observations with an explicit evidence kind and run metadata, validate finite numbers, strict booleans, required identities, unique case identifiers and version identifiers, and reject malformed records. It MUST run without network access or application mutation. The report SHALL include numerator, denominator, excluded records and coverage for each metric; an empty denominator SHALL yield a null value and `not_measured`.

#### Scenario: Invalid observation
- **WHEN** a record contains a missing required version, duplicate case ID, negative duration, non-finite number or a string instead of a required boolean
- **THEN** the evaluator rejects it with a useful error and the CLI returns a nonzero exit code without presenting a passing report.

#### Scenario: Missing evidence
- **WHEN** any of the three experiment sections is absent or has no records
- **THEN** that section is reported as not measured with null metrics, and overall acceptance is false.

### Requirement: Version-aware memory evaluation
The evaluator SHALL calculate macro Recall@5 over queries having at least one annotated relevant current `memory_id + version_id` pair. Only the first five ranked results count; duplicates MUST NOT increase recall. It SHALL separately report the fraction of all executed queries whose top five contain an explicitly annotated obsolete version, and the number of returned hits whose tenant or canonical user differs from the querying identity. Queries annotated as unanswerable SHALL be excluded from recall and reported separately with an abstention success rate. Credential ingestion probes SHALL separately record whether a forbidden credential was admitted, and an absent ingestion-probe denominator MUST NOT be interpreted as zero violations.

#### Scenario: Correct fact and obsolete fact both returned
- **WHEN** a query returns the current version in its top five and also returns an annotated obsolete version
- **THEN** the current pair contributes to Recall@5 and the query independently counts toward the old-version error numerator.

#### Scenario: Multiple relevant memories
- **WHEN** a query has two relevant current pairs and only one appears in its top five
- **THEN** that query contributes 0.5 to macro Recall@5 rather than a binary success.

#### Scenario: Unanswerable query
- **WHEN** the gold relevant set is empty
- **THEN** the query is evaluated for abstention and leakage but does not create a divide-by-zero or a perfect recall score.

### Requirement: GUI localization and task evaluation
The evaluator SHALL report element localization accuracy independently from complete multi-step task success. Localization SHALL use annotated bounding boxes and predicted points in the same coordinate system, treating a missing prediction as a failure and rejecting invalid geometry. Coverage SHALL count distinct scenarios and application classes. Unauthorized successful sensitive-operation probes SHALL be reported separately; an absent security-probe set SHALL be not measured.

#### Scenario: Element localized but task incomplete
- **WHEN** a predicted point is inside the gold element bounding box and the complete multi-step task failed
- **THEN** the observation contributes a localization success and a task failure.

### Requirement: Cross-client consistency and synchronization
The evaluator SHALL compare an observed receiving-client version and canonical state digest with the corresponding authoritative committed version and digest per case, and calculate P95 synchronization duration for stable-network cases using linearly interpolated percentiles. State digests MUST include the experiment's relevant authorization, active/deleted status and version pointers without plaintext credentials. Cases SHALL identify distinct source and receiving clients; memory cases SHALL identify distinct source and query sessions so same-end or same-session examples cannot satisfy cross-end or cross-session coverage. Distinct case IDs MAY reuse the same qualifying clients and sessions, and unanswerable memory cases with cross-session history SHALL count toward memory coverage. A stable-network missing delivery or delivered-but-inconsistent state SHALL count as a synchronization failure and prevent the latency gate from passing, even if the synchronized subset has a low P95. Only consistent stable cases SHALL contribute synchronization durations; the report SHALL separately count missing deliveries, inconsistent deliveries, unstable-network exclusions and the full excluded denominator. Cross-user and cross-session leakage counts SHALL be reported independently and MUST block acceptance when nonzero.

#### Scenario: Missing synchronization delivery
- **WHEN** a stable-network case has no receiving-client version or delivery time
- **THEN** the case counts as inconsistent, appears in the missing-delivery count, and the latency acceptance flag is false.

### Requirement: Coverage and acceptance gates
The evaluator SHALL declare graduation-target acceptance only for non-synthetic observed evidence with all required measurements and coverage. Memory requires at least 150 distinct cross-session query cases, macro Recall@5 at least 0.85, old-version query error rate at most 0.02, zero admitted credentials and zero identity leakage. GUI requires at least 60 distinct scenarios across at least three application classes, localization accuracy at least 0.85, complete-task success at least 0.75 and zero unauthorized sensitive successes. Cross-client requires at least 50 distinct cases, consistency at least 0.99, stable-network synchronization P95 at most 3000 milliseconds, no failed stable synchronizations and zero cross-user or cross-session leaks. Security gates require nonempty explicit credential probes and at least one unauthorized sensitive-operation probe; a set containing only authorized operations SHALL NOT establish unauthorized-operation safety. One successful violation SHALL fail its gate regardless of average accuracy.

#### Scenario: Perfect synthetic example
- **WHEN** every metric in a small synthetic fixture is perfect
- **THEN** the report retains the measured fixture values but acceptance remains false due to evidence kind and insufficient coverage.

#### Scenario: Threshold equality
- **WHEN** sufficient observed evidence exactly meets all inclusive accuracy and latency thresholds and contains zero violations
- **THEN** the associated gates pass.
