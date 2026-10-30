## Context

See proposal.md. The capability runtime already enforces authentic frozen exposure, scopes, network permission, deadline cancellation and untrusted result normalization. The existing MCP address classification rejects private/shared/benchmark ranges but its HTTP transport does not pin DNS. This new fetcher must not weaken that classifier or reuse a credential-bearing client.

## Goals / Non-Goals

Goals: one bounded public HTTP(S) GET, up to three validated redirects, readable text and links. Non-goals: browser execution, login, cookies, arbitrary ports, recursive crawling, installing DDG, changing MCP transport or running Core.

## Decisions

- Keep the original URI for Host/SNI/certificate verification; resolve and validate all answers, then provide only that immutable address snapshot to a per-hop Netty resolver. Use fresh connections, no proxy, no automatic retries or redirects. A redirect is a new validated hop, including same-host redirects. This prevents a second DNS resolution after validation.
- Reuse MCP's address classifier without altering its decisions; web fetching additionally denies reserved/documentation/IPv6 transition ranges and Azure platform metadata IP. Accept only standard HTTP/HTTPS ports; disallow userinfo and local hostnames. HTTPS-to-HTTP redirects fail closed.
- Bound total operation to 12 seconds, connect/response waits, three redirects, 2 MB response, 12,000 output code points, 32 links. Reject compressed/unsupported response content; never download subresources. Output includes actual URL, title, text, links and truncation flags.
- Use the JDK HTML parser rather than a new package or regular-expression HTML parsing. Drop scripts/styles/non-document regions; preserve safe HTTP(S) anchor targets as data. Visible page instructions remain untrusted text, not executable/system instructions. Use the capability normalizer and ReAct observation trust boundary.
- Register exact ID web_fetch only when meguri.web-fetch.enabled=true; default is disabled. Require existing capability:read scope plus network permission. No alias to keyword search, no credential settings or automatic Skill binding.
- ReAct reserves independent source/link/body budgets (768/1024/2048 UTF-16 characters plus fixed headings within 4096). Read fields by key; do not let source or links consume the body allocation. Include source/link URLs whole or with explicit omission notices, remove duplicate parenthesized anchor URLs from prose with a notice, and keep body clipping outside URL spans and surrogate pairs. The body is a bounded prefix, not an assertion that the entire fetched document is visible.

## Risks / Trade-offs

- OS DNS can be slow: run off the event loop under total timeout; canceled resolution cannot initiate a connection after cancellation.
- Parser is not a browser: malformed/HTML5-only/JavaScript pages may produce sparse text; return bounded data without claiming visual rendering or search relevance.
- Plain HTTP lacks confidentiality/authenticity: supported only for public data with no secrets; reject HTTPS downgrade redirects.
- Fake-IP resolvers fail public address checks: preserve failure; never add Exa/DDG exceptions.
- No live TLS acceptance until an allowed public route exists: include offline resolver/transport configuration tests and report live evidence separately.

## Migration Plan

Review candidate, merge if accepted, then explicitly opt in and bind through existing capability policy. Skill import/activation remains separate. Rollback by removing opt-in or disabling capability for subsequent turns; no data migration.
