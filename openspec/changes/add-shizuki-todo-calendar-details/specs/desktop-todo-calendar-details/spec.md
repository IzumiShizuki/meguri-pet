## Purpose

Let the native companion inspect calendar dates and complete task deadlines using the same authenticated TimePrism Todo records as the personal site.

## ADDED Requirements

### Requirement: Connected Todo calendar
The companion SHALL show a month calendar in its connected Shizuki Todo panel using the existing Todo records, respecting calendar visibility, day/minute precision, Asia/Shanghai dates, and inclusive multi-day ranges.

#### Scenario: Timed and hidden Todos
- **WHEN** the connected panel contains deadline, range, hidden, and undated Todos
- **THEN** visible dated Todos are marked on their deadline or range dates and hidden/undated Todos do not appear

### Requirement: Complete Todo date details
Opening any calendar date SHALL show all matching Todos with full titles, full deadline dates/times, optional range starts and detail, and completion/overdue states. All calendar date and month controls MUST be keyboard accessible.

#### Scenario: Busy date details
- **WHEN** a user opens a date with more than four Todos including a completed item
- **THEN** every matching Todo and its current completion state is visible in details

#### Scenario: Empty date
- **WHEN** a date contains no calendar Todos
- **THEN** an empty message is displayed for that date

### Requirement: Current account snapshot
The calendar SHALL update when the panel creates, edits, completes, or refreshes a Todo. It MUST clear records on disconnect or account change, ignore superseded responses, and refresh on focus and while visible.

#### Scenario: Successful edit or completion
- **WHEN** the server confirms a deadline edit or completion
- **THEN** markers and open date details update from the confirmed record

#### Scenario: Account changes during refresh
- **WHEN** the bridge changes account or disconnects before a Todo refresh completes
- **THEN** the old records do not appear in the list or calendar
