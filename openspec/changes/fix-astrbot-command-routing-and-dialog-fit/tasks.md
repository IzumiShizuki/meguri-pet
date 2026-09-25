## 1. Bilingual pairing and fit

- [x] 1.1 Flatten all full-width bracket groups per line before pairing so packed and canonical layouts produce identical pairs
- [x] 1.2 Keep rejecting stray prose, empty groups, and odd group counts so ordinary text is never misread as bilingual
- [x] 1.3 Report a language-correct pair for the captured production text (2 pairs, not 1)
- [x] 1.4 Measure the wrapped pair block and shrink the bilingual fonts until it fits the dialogue box, bounded at the minimum size
- [x] 1.5 Open `.ttc` font collections through an index that carries the required glyphs
- [x] 1.6 Accept an unbracketed Chinese+Japanese reply by pairing on script, while still rejecting ordinary multi-line text
- [x] 1.7 Render a Meguri turn from a high-priority decorator that isolates the event, keeping the low-priority path for other turns

## 2. Command ownership

- [x] 2.1 Add a gateway ownership check that releases messages matching another plugin's registered command names and aliases
- [x] 2.2 Cache discovery, degrade to the configured release list when AstrBot internals are unavailable, and never release an explicit `/meguri` invocation
- [x] 2.3 Route the release decision through `MessageRoutePolicy` and document `passthrough_commands` in the plugin schema
- [x] 2.4 Give the wife plugin `jrlp`/`jrlb` as "today's wife", case-insensitive ASCII matching, private-chat scope, and default-LLM suppression for owned messages

## 3. Tests

- [x] 3.1 Add renderer regressions for packed bracket lines and layout equivalence
- [x] 3.2 Add gateway regressions for release matching, prefix safety, `/meguri` precedence, and discovery failure
- [x] 3.3 Add a wife-plugin regression module for alias resolution, scope, draw/read selection, case-insensitive dispatch, and untouched unrelated chat
- [x] 3.4 Run the full AstrBot plugin test suite
- [x] 3.5 Add decoration-isolation regressions for the registered priorities, the stop-after-render behaviour, and the untouched non-Meguri path

## 4. Verification on the live server

- [x] 4.1 Render the captured production text through the deployed plugin and confirm two complete, language-correct pages fitting the frame
- [x] 4.2 Drive the deployed wife plugin with a private `jrlp` event and confirm the draw path, scope, and LLM suppression
- [x] 4.3 Confirm the gateway release policy on the deployed server, including `/meguri` precedence
- [x] 4.4 Restart the AstrBot container and confirm all touched plugins load without error
- [x] 4.5 Confirm the reason `jrlp` needs a configured release entry (0 registry-discoverable commands on this host) and record it in `design.md`

## 5. Repository / server reconciliation

- [x] 5.1 Merge the server-only decoration isolation and unbracketed bilingual fallback into the repository files
- [x] 5.2 Deploy the reconciled files so the server matches the repository, and normalize line endings
- [x] 5.3 Re-verify on the server that both decorators register at the intended priorities and that packed, canonical, and unbracketed inputs all parse and render correctly
- [x] 5.4 Record the reconciliation table and rollback paths in `design.md`
