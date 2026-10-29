# Skills / MCP / ModelScope acceptance boundaries

This record separates implementation checks from live deployment acceptance. The
coordinator's deployment report owns the running build identity and live results.
No unknown Skill package, model or MCP server was installed for this change.

## Verified code defects and repairs

- `SkillSelectionService` selected candidates by word/CJK token overlap, but
  `SkillDisclosureService.search` required the complete follow-up query to occur
  verbatim in the metadata. A natural query such as `extract pdf tables` could
  therefore discard an already relevant frozen PDF candidate. Disclosure now
  reuses selection relevance, exclusively inside the existing frozen snapshot.
  It does not requery the live catalog, activate a Skill or consume view budgets.
- `HttpMcpAdapter` returned the complete `tools/call` result envelope to the
  capability output-schema validator. A valid `{structuredContent: {value: ...}}`
  response failed a schema requiring `value`. The adapter now projects an object
  `structuredContent` before validation, rejects malformed structured results,
  checks `isError` first, and preserves text-only result envelopes. Content still
  receives `UNTRUSTED_EXTERNAL` normalization, redaction and audit treatment.
- Read-only tool proposals may omit `operationId`; optional MCP metadata now
  handles that case instead of throwing before the HTTP call.
- MCP results with an empty display string previously reached the ReAct planner
  as an empty summary despite successful execution. A bounded projection now
  carries redacted structured/text values with an untrusted label (4096
  characters, maximum depth six, 32 fields / 16 items per collection). Binary
  content is omitted; normalized observations retain a digest and bounded text.
- ModelScope public discovery is now separate from the default-disabled
  `meguri.skills.modelscope.imports-enabled` gate. The environment mapping is
  `MEGURI_MODELSCOPE_SKILL_IMPORTS_ENABLED`; a disabled fetch fails before bridge
  transport with `MODELSCOPE_IMPORTS_DISABLED`. Explicit opt-in still passes
  through ID validation and the existing quarantine/activation lifecycle.

The MCP structured-result rule is specified in the
[official MCP tools contract](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#structured-content).

## Reproducible isolated checks

Run the following with the repository's Java 21 / Maven toolchain; the coordinator
serializes Maven runs because agents share `target/`:

```powershell
mvn -B '-Dtest=SkillDisclosureServiceTest,SkillManagementServiceTest,SkillPackageValidatorTest,SkillRuntimeConfigurationTest,ModelScopeSkillSourcePluginTest,HttpMcpAdapterTest,McpSourceManagerTest,CapabilityRuntimeFacadeTest,CapabilityRuntime20_6CompletionTest' test
```

New coverage in `SkillDisclosureServiceTest` verifies Chinese and English natural
queries through selection, frozen search and actual `SKILL.md` disclosure, plus
unrelated-query rejection and absence of new catalog candidates. Existing tests
cover disabled/new-turn separation, immutable revision disclosure, traversal
rejection and token/reference budgets.

New coverage in `HttpMcpAdapterTest` uses a temporary loopback HTTP fixture through
`McpSourceManager` and `CapabilityRuntimeFacade`, not a fake success return from
the adapter. It checks discovery, frozen exposure, pending/declined approval with
zero HTTP tool calls, approved call, structured schema validation, audit receipt,
remote `isError`, a later successful request and text-only compatibility. This is
real transport execution of a controlled fixture; it does not prove production
MCP registration or natural-language planner selection. Test pass/fail totals
must come from the coordinator's run, not from this coverage description.

## Live acceptance matrix

| Feature | Existing implementation | Enable / select requirements | Required evidence |
| --- | --- | --- | --- |
| External Skills | validated immutable package, activation ledger, bounded search/view | installed and explicitly activated digest; `skill:read`; server-authorized AGENT and exposed search/view | candidate digest, actual view result, untrusted label, tool result/turn receipt, next-turn disable behavior |
| MCP tools | source negotiation, list refresh, schema normalization, approval, invocation and audit | existing source and authorization; `mcp:<source>`; frozen exposure; bounded planner/action; approval if required | actual `tools/call`, result and operation/trace receipt; denial must produce zero calls; failure followed by independent successful call |
| ModelScope discovery | public search/detail OpenAPI plugin | opt-in source flag and existing bridge configuration | successful list/detail response; an empty local catalog is not discovery evidence |
| ModelScope import | staging, quarantine, package validation, digest revisions, explicit activation | specific package review/authorization; required supported tools/environment; license acknowledgment where needed | disabled validated revision, digest, activation audit, selected view on a later turn |
| Automatic self-improvement | no unrestricted installer or self-edit loop | no authorization inferred from community text | propose a specific extension with source, license, dependencies, permissions and rollback; obtain separate approval before unfamiliar installation |

Read-only deployment probes:

- `GET /internal/v1/skills`: identity/`skill:read`; `[]` is an empty catalog, not a
  successful Skill execution.
- `GET /internal/v1/skill-sources/modelscope/skills?q=pdf&page=1&page_size=2`:
  `SKILL_SOURCE_DISABLED` is a configuration blocker, not a network or parser bug.
- `GET /internal/v1/capabilities`, `/internal/v1/capabilities/mcp/sources` and
  `/internal/v1/capabilities/audit?trace_id=<isolated-test-trace>` require the
  opt-in administrative surface and its existing `X-Meguri-Admin-Token`. A 404 can
  mean administration is disabled; do not invent a credential or expand policy.
- Record execution mode and `react_eligible` from the isolated Turn. Ordinary
  FAST chat does not establish external Skill planner execution. The execution
  and limited-ReAct feature gates must be checked independently of catalog data.

Default configuration disables ModelScope and uses an in-memory Skill catalog.
Selecting the PostgreSQL application profile alone does not set
`MEGURI_SKILL_STORE_MODE=postgres`; installation persistence must be verified from
the actual configured mode and restart evidence before claiming it.

Workspace inventory during this investigation found no tracked `SKILL.md`, no
repository `.agents/skills`, `.agent/skills` or `skills` directory, and no local
`%USERPROFILE%/.meguri/skills/packages` directory. Core's bundled prompt callbacks
(`meguri.prompt.default`, `meguri.prompt.approved-file-reference`) are distinct
from installable external Skill packages and must not be counted as such.
The latter requires `artifact:reference` and remains opt-in.

MCP source configuration comes from `meguri.capability.mcp.sources-json` and,
when configured, the `capability_mcp_source` store. The administrative API accepts
`meguri.capability.admin.token` or the new optional
`meguri.capability.admin.token-file`. The latter requires an absolute regular
non-symlink file, rejects missing/short/oversized data without leaking its path
or contents, and never falls back to the inline credential after a file error.
Every endpoint still requires its credential; the administrative controller
remains opt-in. This does not enable a public listener or create a token. No
admin/MCP-named secret file was found in the project's configured secret directory
during a name-only check. Existing database source rows and process environment
values were not inspected by this module audit.

The pre-existing community API was a management surface. The pre-existing
`meguri.skill.search` tool searches already installed, frozen candidates; it is
not a ModelScope public search. This change adds `meguri.skill.discover(query)`
and `meguri.skill.inspect(external_id)` on the existing frozen capability path:
AGENT/DEEP, `skill:read`, a public-modelscope network policy, inherited turn
budget/deadline and bounded untrusted metadata. Neither capability imports or
activates packages. Discovery returns at most five items and detail reports
requirements as names, not secret values. A frozen turn is required before any
source request. The deployment report must still verify actual natural-language
planner selection; registration by itself is not a successful call.

## Deliberately unexecuted operations

`ModelScopeNetworkAcceptanceTest` downloads `@MiniMax-AI/minimax-pdf`; it is not a
read-only browse test and must not be enabled merely to increase test counts.
The existing bridge downloads public package files, never auto-installs package
dependencies, executes their scripts, downloads model weights or creates
credentials. See [the local ModelScope lifecycle](modelscope-skills.md).

The upstream [ModelScope Skill cookbook](https://github.com/modelscope/ms-cookbook/blob/main/content/chapters/chapter-31.md)
describes community download and progressive loading. Upstream support is not
evidence that the local source is enabled or that a package is safe to activate.
Desktop UI save/rendering and production tool selection remain separate live
checks; passing the isolated API tests must not be substituted for either.

Additional targeted regression: `CapabilityRuntimeReactActionExecutorTest`
checks that MCP values reach normalized planner observations while secret keys,
image/audio bytes and over-budget trailing text do not.

## Live selection follow-up

The isolated `DISCOVER` turn in `reports/companion-acceptance-20261029/capability-live.jsonl`
(`turn_48255b4d3f2f428d`, tested source `6a9a91109516b064fc18e8948e36f66437436476`)
exposed all four Skill capabilities, but selected `meguri.skill.search` twice.
The empty installed catalog was then incorrectly described as a community search.
This is a planner selection/reporting failure, not evidence that public discovery
was absent or that its API failed. No discovery call occurred in that turn.

The planner's capability projection contained IDs and input schemas but no
purpose descriptions. The follow-up adds usage descriptions only for actually
exposed Skill capabilities, separating public discovery/detail from frozen
installed search/view. Policy retains untrusted metadata and the prohibition on
inferring installation/execution authority; empty local results now explicitly
say that no public community search occurred. There is no automatic installation
fallback or hard-coded query. `LangChain4jLlmProviderTest` captures the actual
planner request to verify these scopes and that unexposed tools are not advertised;
`CapabilityRuntimeReactActionExecutorTest` executes an empty inventory callback
and verifies the observation's scope. These offline checks establish prompt and
receipt wiring; a new isolated live turn must establish improved model selection.

The next deployed probe (`d21d125bedf51a545522b2bfe6006792b38a3149`) selected
`discover`, but the capability audit recorded three `NETWORK_DENIED` rejections
before any callback execution. The exposure policy had authorized the network
capabilities; `limitedReactContext` incorrectly created every invocation scope
with `networkAllowed=false`. The repair retains the server's network decision
inside the authenticated frozen capability state and passes that decision into
ReAct execution. Empty/default freezes remain denied, descriptor host constraints
remain unchanged, and explicit offline requests deny network exposure. Neither
model arguments nor a visible tool ID can grant network authority.

`ModelScopeCapabilityTurnIntegrationTest` covers the actual orchestrator,
frozen facade, disclosure callback, production ModelScope request/parser and a
loopback HTTP transport fixture. It checks discover plus detail metadata in the
planner and final provider context, receipts, offline/missing-scope zero requests,
transport failure followed by a successful independent turn, and rejection of
forged/released freeze tokens. No packages are installed and no external HTTP or
model calls occur in these tests. Test results and a renewed live probe are still
required before claiming deployment acceptance.

## Natural public-skill entry

The successful public capability probes explicitly requested AGENT mode. A later
ordinary client request without `execution_mode` selected `SERVER_DEFAULT_FAST`
with zero tool calls (`natural-skill-entry-before.json`), so those earlier probes
did not establish the normal language entry. `PublicSkillReadIntent` now proposes
the existing `tool_required` signal only for a direct public discovery/detail
read request naming ModelScope/魔搭 and a Skill or concrete external ID, and only
when the request already has the server-authorized `skill:read` scope. Explicit
mode choices, availability, budgets, frozen exposure and network policy remain
authoritative. This does not add installation, activation or execution rights.

The intent rejects offline instructions, quoted/code examples, explanation or
implementation questions and non-read commands. Its unit tests cover Chinese and
English requests and negative controls. The turn/HTTP integration test now also
omits the execution-mode override, verifies discovery and direct-ID inspection,
and checks zero planner/network calls for missing `skill:read`, negation,
discussion, quotation, installation requests and an explicit FAST override.
These deterministic tests do not prove live model selection; the coordinator
must repeat ordinary client probes after deployment.
