## Purpose

Ensure AstrBot conversation-card replies remain readable when a bilingual sentence exceeds one image, with ordered continuation pages that never restart from the first page.

## ADDED Requirements

### Requirement: Oversized bilingual pairs paginate
The system SHALL split a bilingual translation/original pair into multiple bounded pages when the pair exceeds the configured rendering character limit. Each page SHALL retain the canonical translation-first, original-second structure.

#### Scenario: A single pair exceeds one card
- **WHEN** a bilingual pair contains more visible characters than the configured page limit
- **THEN** the system produces more than one renderable page without placing the complete oversized pair on any single page

#### Scenario: A pair fits one card
- **WHEN** a bilingual pair does not exceed the configured page limit
- **THEN** the system produces exactly one page for that pair

### Requirement: Pagination preserves content and order
The system MUST preserve every character of both the translation and original exactly once and in source order across the generated pages.

#### Scenario: Reconstruct a paginated pair
- **WHEN** the translation slices and original slices are concatenated independently in page order
- **THEN** they equal the original translation and original-language text respectively

### Requirement: Consecutive pages are delivered distinctly
The system SHALL render each page from that page's own text slice and SHALL deliver generated images in page order without substituting the first page for a later page.

#### Scenario: Multiple image pages render successfully
- **WHEN** one reply produces two or more successfully rendered image pages
- **THEN** each outbound image corresponds to its same-position text slice and later pages continue after earlier pages

### Requirement: Page failure retains readable fallback
The system SHALL send the page's text slice in its original position when that page cannot be rendered as an image.

#### Scenario: One continuation page fails to render
- **WHEN** an image render fails for one page within a multi-page reply
- **THEN** the system sends that page's text fallback without reordering the surrounding successful pages
