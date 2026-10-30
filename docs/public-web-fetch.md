# Public document fetch capability

The opt-in capability ID is **web_fetch**. It reads one public HTTP(S) document and returns data, including anchor text and URLs. It does not perform keyword search, execute HTML/JavaScript, crawl links, import a Skill, or create a Todo.

## Contract

Input: url (required), extractMode (only text, default text), maxChars (integer 1–12000, default 8000). The input schema is closed: headers, cookies and authentication cannot be supplied. Userinfo and common credential query keys are rejected. This is not a general-purpose detector for confidential information in arbitrary URL parameters; callers must use public URLs and public queries.

Output: final url, title, text, links [{text,url}], truncated, linksTruncated, redirects and trust=UNTRUSTED_EXTERNAL. The text limit counts Unicode code points; title and link text are bounded to 256, and at most 32 links are returned. Links are resolved against the fetched URL, not an HTML base element. They are not automatically fetched; a subsequent invocation revalidates its destination. Unknown/non-HTTP link schemes are omitted.

Text-only extraction uses the JDK HTML parser. It drops script/style and non-document container text. Visible instructions in the page remain ordinary untrusted text. No sanitizer can guarantee that a language model ignores all adversarial prose: authorization, frozen capability scope and the untrusted observation boundary remain authoritative. ReAct receives a maximum 4096-character observation, prioritizing source and selectable links; persisted observation data is a result digest rather than the raw full document.

## Network boundary

- Only http:80 and https:443 (including implicit default ports); no URL credentials, local hostnames, proxy, request cookies or authentication headers.
- Reuse the existing MCP public-address predicate unchanged, then conservatively exclude additional web-document-inappropriate ranges: documentation, IPv6 transition/reserved space and the Azure platform metadata address.
- Resolve all DNS answers and reject the entire answer set if any address is non-public. Give a per-hop resolver only the immutable approved address bytes. Even pre-resolved socket addresses cannot bypass that resolver. The original URI remains the Host/TLS hostname; TLS uses Reactor Netty's normal certificate/hostname checks.
- Use a fresh connection, disable built-in retries/redirects and compression. Follow at most three redirects manually, revalidating every hop including same-host redirects. Reject HTTPS-to-HTTP downgrade and loops.
- Total operation timeout 12 seconds; connect timeout 3 seconds; response timeout 5 seconds. The capability executor has a 13-second outer limit and concurrency 2. Cancellation stops the subscription and prevents late DNS completion from initiating a request.
- Bound response headers (8192), initial response line (4096), response bytes (2,000,000), URL input length (4096 UTF-16 code units) and returned data. Reject unsupported content types, charsets, compressed responses and oversized declared/streamed bodies.
- Accept text/plain, text/html and application/xhtml+xml only. No credentials, login flows, browser state, file access or subresource loads.

The native OS DNS operation may finish after cancellation; the canceled reactive chain cannot continue to transport. Malformed or JavaScript-dependent HTML can yield incomplete text. Compressed-only endpoints and nonstandard ports are intentionally unsupported. Address classification is conservative, including some special-purpose globally reachable prefixes; it is not an automatically updated IANA registry.

Relevant upstream references: [Reactor Netty HTTP client](https://projectreactor.io/docs/netty/release/reference/http-client.html) documents redirect control, response timeouts and SNI; [Netty address resolver](https://netty.io/4.1/api/io/netty/resolver/AbstractAddressResolver.html) describes pre-resolved address handling. The additional 3fff::/20 documentation range is listed in the [IANA IPv6 special-purpose registry](https://www.iana.org/assignments/iana-ipv6-special-registry).

## Integration and approval boundary

PublicWebFetchConfiguration registers the real implementation only with meguri.web-fetch.enabled=true. No configuration file is changed by this candidate; default remains disabled. Existing capability:read scope, allowed execution mode, authentic frozen plan and network permission are required. An ordinary Skill text cannot supply those grants.

An approved package must declare requires.tools: [web_fetch]. Merely importing a package does not enable this network capability, and capability registration does not install or activate any Skill. The audited DDG review package and MIT notice remain separate review artifacts.

This tool is distinct from the typed web retrieval provider. It does not reroute keyword search, change lore/relationship/learning authorization or weaken the Exa/MCP destination policy. Existing MCP transport behavior is unchanged; the only shared-policy edit exposes the same classifier within its Java package for reuse.

## Validation and remaining acceptance

Tests use synthetic DNS, pinned resolver calls, fake HTTP responses and the real frozen capability/ReAct boundaries; no unsafe destination is contacted. They cover private/link-local/loopback/shared/benchmark/metadata and IPv6 special ranges, mixed DNS, pre-resolved rebinding, redirects, body limits, encoding/type rejection, canceled DNS/response work, recovery, HTML containers, Unicode and scope isolation.

The one public RFC 9110 probe resolved www.rfc-editor.org to 198.18.0.82 and failed with WEB_FETCH_ADDRESS_REJECTED before transport (0 HTTP calls). No alternate route or retry was attempted. Thus live HTTPS handshake/certificate rejection, on-wire DNS pinning, real redirect traversal and DDG production use remain **unverified**. Unit/contract success does not close that evidence gap.

See openspec/changes/add-safe-web-fetch-capability/verification.md for exact results and handoff. Review before integration; no current Core, primary working tree, network configuration or third-party Skill was changed.
