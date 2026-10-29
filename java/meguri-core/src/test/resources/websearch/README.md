# Recorded wrong-topic search results

`wrong-topic-rss.json` retains the public title, URL and snippet fields from four
read-only Bing RSS captures on 2026-10-29: R01, R03, RECOVERY and CLEAN_CONTROL.
Each capture contains ten parsed items, including candidates beyond the production
top-five limit. The original query is retained for offline relevance replay.

Source evidence: `reports/companion-acceptance-20261029/overnight-independent-qa/search-diagnosis/*-captured-analysis.json`
in the original acceptance workspace. No new network request is made by this test.
All forty results were unrelated to the requested RFC. The regression asserts that
they remain rejected; it does not claim that the upstream search problem is fixed.

The separate R02 HTTP-200 HTML failure is covered by `BingRssDiagnosticsTest` using
minimal HTML fixtures, including absent or misleading content types. The captured
HTML homepage is not needed in the source tree.
