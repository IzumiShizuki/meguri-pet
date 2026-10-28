## Why

The Bilibili daily card reports time, categories and a few editorial title keywords, but cannot explain the actual subjects watched. The owner wants concrete content and viewing directions, with relevant open-source projects evaluated before implementation.

## What Changes

- Add a bounded content-enrichment step to the existing daily-report runner: reuse explicitly supplied local transcripts when available, otherwise obtain public video descriptions and available subtitles, then summarize with the existing configured text model.
- Show specific per-video subjects, cross-video directions with supporting videos, and analysis coverage in the Markdown report and existing AstrBot card.
- Cache video evidence and summaries outside the repository; mark subtitle, description and title-only evidence separately and preserve report delivery on extraction/model failure.
- Retain the history reader's read-only MCP boundary and metadata-only standalone mode. This request authorizes the new daily content step; account cookies and audio/video downloads are unnecessary for the initial implementation.
- Save a first-party-source comparison of related projects and document configuration and limitations.

## Capabilities

### New Capabilities

- `bilibili-content-digest`: Evidence-based video subjects and daily viewing directions with bounded extraction, caching, honest fallback and card/report presentation.

### Modified Capabilities

None.

## Impact

Python report tooling, the PowerShell daily runner, the AstrBot card renderer and focused tests. Reuses the existing Python runtime and model key files, with no new package installation. Public Bilibili endpoints may restrict subtitle access; such cases must remain visibly description/title based. Existing metadata report clients and publishing contracts remain compatible.
