## Purpose

Give the owner's desktop companion and AstrBot a shared, source-attributed daily AI bulletin that can be delivered automatically and referenced naturally during later conversations.

## ADDED Requirements

### Requirement: Daily feed polling and durable summaries
When enabled, the system SHALL poll Juya's public RSS every ten minutes after 08:00 Asia/Shanghai, summarize only the current day's bulletin, and persist one successful briefing per date across restarts. Failed reads or model calls SHALL retry without blocking chat or replacing a good briefing.

#### Scenario: Bulletin appears after the initial check
- **WHEN** the feed still contains yesterday's entry at 08:00 and today's entry appears later
- **THEN** a subsequent poll produces today's briefing without re-labeling yesterday's news as today

#### Scenario: Restart after successful summarization
- **WHEN** the service restarts with today's durable briefing
- **THEN** it reuses the briefing without another model call

#### Scenario: Unavailable or malformed input
- **WHEN** the RSS is unavailable, oversized, malformed, or contains unsafe XML declarations
- **THEN** the previous briefing remains available and a later poll can retry

### Requirement: Useful and attributed delivery
The bulletin SHALL contain 3–5 key news items, their implications, 1–2 conversation topics, the publication date, and a Juya source link. Delivery SHALL use the existing subscribed AstrBot destination and local desktop report channel, deduplicate by date and target, and retain failed deliveries for retry.

#### Scenario: New daily briefing reaches both clients
- **WHEN** today's briefing is ready and the owner has the clients running
- **THEN** AstrBot can send the bulletin to the configured owner destination and the desktop receives the same dated bulletin

### Requirement: News is available to conversations
Both supported Core model-request paths SHALL receive bounded recent AI news as reference data, with date and source attribution. News older than three days SHALL not be injected as current news. External articles SHALL not become instructions or personal experiences.

#### Scenario: Follow-up discussion
- **WHEN** the owner asks what is worth discussing in today's AI news
- **THEN** the model request contains the stored briefing and its date and source

#### Scenario: Old or absent briefing
- **WHEN** no recent briefing is available
- **THEN** chat proceeds without a fabricated news context

### Requirement: Explicit rollout configuration
The feature SHALL be disabled in default offline environments and enabled in the owner's local launcher and existing remote Core configuration using existing credentials. Configuration SHALL preserve unrelated features and existing subscribed destinations.

#### Scenario: Default offline tests
- **WHEN** the application starts with default configuration
- **THEN** no Juya feed or hosted summarization request is made
