## Context

See `proposal.md` for motivation. The renderer already recognizes canonical `【中文译文】` / `【日语原文】` pairs, renders pages sequentially to unique temporary files, and sends each page through a fresh message chain. Its pair splitter currently ignores the configured character limit and treats every pair as atomic, so an oversized single pair cannot create real continuation slices.

## Goals / Non-Goals

**Goals:**

- Keep every generated page valid for the existing bilingual renderer.
- Produce deterministic, contiguous slices whose independent concatenation reconstructs both source lines.
- Keep short pairs as one image each and preserve the existing per-page text fallback.

**Non-Goals:**

- Pixel-measure glyphs or dynamically resize the card.
- Change the canonical bilingual response format or AstrBot gateway contract.
- Merge bilingual pages into a vertically enlarged image.

## Decisions

### Split each language into the same number of contiguous slices

For an oversized pair, derive the page count from its combined visible character count and the configured limit. Partition both language strings into that page count using balanced contiguous slices, then format corresponding slices as independent bilingual pairs. This keeps every page parseable and prevents either language from being separated into a different outbound position.

The page count is capped so both languages contribute non-empty text to each page. This can make a pathological pair with a one-character side exceed the nominal limit, but it preserves the renderer contract and avoids inventing or repeating content.

Alternatives considered:

- Splitting each language independently at the limit can produce unequal page counts and orphan one language.
- Repeating a short side on later pages preserves visual pairing but violates exact-once content and resembles the reported first-page repetition.
- Reducing the font until the pair fits harms readability and does not guarantee a bound.

### Keep delivery sequential and verify page identity at the rendering seam

The existing rendering path passes a page's text to the renderer, creates a unique temporary image, and sends a fresh message chain before advancing. Retain that behavior, but cover it with an integrated regression test that captures the text rendered for each outbound image and asserts ordered, distinct continuation.

Alternatives considered:

- Sending all images in one message chain changes platform-adapter behavior and fallback ordering.
- Reusing one output path risks cache aliasing and makes page identity harder to verify.

## Risks / Trade-offs

- [Character counts approximate rendered height because glyph widths and wrapping vary] → Retain the existing configurable limit and keep the algorithm deterministic.
- [Balanced hard boundaries can split at a visually awkward character] → Keep cuts deterministic and contiguous so no content is lost or repeated; punctuation-aware refinement can be added independently later.
- [One language can be much shorter than the other] → Cap page count at the shorter non-empty side and never duplicate content.
