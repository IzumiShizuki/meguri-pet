## Purpose

Explain concrete subjects in the owner's Bilibili viewing history and derive daily viewing directions from attributable video evidence, while keeping collection bounded and distinguishing available content from title-only inference.

## ADDED Requirements

### Requirement: Concrete subjects have attributable evidence
The daily enrichment SHALL attach a short subject summary and specific topics to each analyzed video, identify subtitle, description or title-only evidence, and retain its original BV link. Imported local transcripts SHALL only apply to matching videos in the report. The output MUST distinguish video content from a claim that the viewer watched, learned or endorsed it.

#### Scenario: Subtitle available
- **WHEN** an analyzed video has an available public subtitle or a matching imported transcript
- **THEN** its summary identifies subtitle evidence and the report links to that video

#### Scenario: Content unavailable
- **WHEN** subtitles and descriptions are unavailable or extraction fails
- **THEN** the report marks that video as title-only and does not present it as a subtitle-based summary

### Requirement: Viewing directions are supported by analyzed videos
The report SHALL group concrete topics across analyzed videos, show supporting BV links and report analyzed coverage. Topics MUST support any video genre, rather than only editorial/news videos. Sampling SHALL prioritize viewing weight and SHALL NOT imply full-day content coverage when the limit is exceeded.

#### Scenario: Mixed learning and entertainment
- **WHEN** the history contains programming, language-learning and entertainment videos
- **THEN** representative contents and directions can include all three genres and each direction references analyzed videos

#### Scenario: Analysis limit
- **WHEN** the history contains more videos than the configured analysis limit
- **THEN** the report states the analyzed count and total count, leaving remaining videos visibly unanalyzed

### Requirement: Enrichment is bounded and resilient
The system MUST retain metadata report generation, use bounded network requests, enforce a per-run budget and cache evidence and successful summaries outside the repository. It SHALL NOT read account cookies or download audio/video in this implementation. Model/extraction/cache failures SHALL preserve a publishable report and explicit fallback status. Untrusted content MUST be treated as data and model results MUST reference only videos supplied for analysis.

#### Scenario: Repeated video
- **WHEN** a valid cached summary matches current video evidence and model
- **THEN** the summary is reused without another model request

#### Scenario: Model failure
- **WHEN** the model times out or returns invalid/unknown video identifiers
- **THEN** available evidence is displayed with a clearly marked extractive/title fallback and metadata report delivery continues

### Requirement: Existing card and Markdown expose content
The daily Markdown SHALL include concrete video subjects, directions and evidence coverage. The compatible AstrBot card SHALL show representative video content and direction labels; the ordinary metadata-only reader SHALL continue to work without a configured model.

#### Scenario: Enriched card
- **WHEN** the enriched report is published through the existing daily-report contract
- **THEN** the card contains representative subject descriptions and its Markdown report contains the full analyzed list and supporting links

#### Scenario: Metadata-only compatibility
- **WHEN** the original history reader runs by itself
- **THEN** it performs no content/model requests and retains its existing contract

#### Scenario: Card text aligns with template regions
- **WHEN** the card renders single-line or wrapped direction labels, long subjects, statistics and the daily summary
- **THEN** the visible text bounds are aligned inside their template regions, subject lines remain above their guide rules, and summary text does not overlap the character or bottom decoration
