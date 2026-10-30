# Candidate verification — 2026-10-30

Independent branch: codex/safe-web-fetch-20261030. Base: d1365765754ed6ae3f0cb6f84f8addfd7b948387. Primary tree and running Core were not modified. Third-party Skill import/activation remains unperformed.

## Actual results

- Revised complete Java suite: **922 tests, 917 passed, 0 failures, 0 errors, 5 existing skips**.
- Compared testcase identities/outcomes against the saved d136576 full-suite evidence: all **831 baseline outcomes unchanged**; **91 new tests passed**. Against the previous candidate db75cd5, all **914 outcomes unchanged**, with **8 new projection tests passed**.
- Reproduced the review issue before editing production code: **8 tests failed, 0 errors**. The real fetch capability, frozen runtime facade, ReAct executor and observation normalizer were exercised, with synthetic HTTP responses only. Long source URL, two 2031-character links, 32 ordinary links with tail body marker, and body URL/emoji clipping each run with forward/reversed result and link field orders. After correction, the targeted 20 tests passed, followed by the complete suite.
- Before final IPv6 review, a targeted suite of 98 tests passed. The final full suite includes those tests plus the new address parameter; these counts are subsets and must not be added.
- Initial failures were retained and fixed: parser-generated whitespace consumed text quota; HTML5 end-tag markers were strings, leaving suppression open; expanded IPv6 literals failed pinned-host matching; 3fff::/20 documentation addresses needed explicit rejection. No assertion was weakened to hide these failures.
- OpenSpec strict validation and git diff --check passed.

## Test execution

Java 21.0.11, Maven 3.9.16, offline existing dependency cache. The existing isolated harness clears provider opt-ins, uses a separate test home and the previously verified JDK temporary socket directory fallback. No dependency installation.

From the parent workspace:

    .\run-isolated-retrieval-tests.ps1 -RepoName meguri-reply-constraints -SocketDirectory '<workspace>\meguri-retrieval-baseline\tmp\jdk-unix-sockets' -LogName web-fetch-projection-full.log

Revised raw evidence and testcase comparison: <workspace>/web-fetch-projection-review-20261030/verification-summary.json and web-fetch-projection-surefire.zip. Red, targeted and complete-suite logs are preserved there. The earlier web-fetch-candidate-20261030 directory retains the prior 914-test evidence and original public-probe result.

## Public probe and explicit gaps

One request was proposed to https://www.rfc-editor.org/rfc/rfc9110.html using the candidate policy and transport. DNS returned 198.18.0.82; the policy rejected it with WEB_FETCH_ADDRESS_REJECTED before transport: **0 HTTP calls**, 0 Core/model calls, no alternate route or retry. Exa was not contacted.

Thus actual TLS handshake/certificate rejection, on-wire DNS pinning, real redirect traversal and production DDG Skill use remain unverified. Unit tests exercise the real resolver and response handler with synthetic inputs; they do not replace wire-level acceptance. The candidate must remain disabled pending review and a route accepted by the unchanged network policy.

This review revision made **zero additional public probes**, changed no network policy and installed or activated no third-party Skill. The implementation change is confined to ReAct document projection; independent source/link/body budgets prevent source/link starvation while retaining the existing untrusted label, digest-only data and token accounting. Long documents still have bounded prefixes; omitted URLs and links are explicitly marked.

## Review focus and application

- Review the immutable per-hop DNS snapshot, numeric-address matching, TLS default preservation, manual redirects and cancellation.
- Review document extraction and the bounded untrusted ReAct projection, including link ordering and token accounting.
- Confirm the desired read-only deployment scope before opting in to meguri.web-fetch.enabled; no existing runtime setting was changed here.
- The revised bundle contains the original web_fetch implementation plus the projection correction. Its full prerequisite is **d1365765754ed6ae3f0cb6f84f8addfd7b948387**; prerequisite history is not included. Verify/apply only in a separately reviewed target containing that prerequisite, or review the patches for adaptation. The primary tree lacks this commit per the parent review: **bundle verification in this independent copy does not verify primary-tree applicability or integration**. No primary-tree verification or application was performed.
- Do not import/activate the DDG package automatically. Package notice/dependency fixes remain separate audited artifacts.

See docs/public-web-fetch.md for the exact contract, limits and known limitations.
