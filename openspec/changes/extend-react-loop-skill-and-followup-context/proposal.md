## Why

The current Limited ReAct runtime loops over observations, but its action gate rejects `PROMPT_SKILL` and its provider planner cannot see the frozen external-Skill candidate index. Skills are therefore either executed by the one-shot canonical preparation path or unavailable after an intermediate observation. Separately, short follow-ups such as “我问的是明天来着” fall through to the generic model path instead of inheriting the active weather query.

## What Changes

- Add a strict provider-backed ReAct planning route and give every round the current observations, frozen exposed capabilities, and planner-safe external-Skill L1 candidates.
- Allow an AGENT ReAct planner to select an exposed, side-effect-free prompt skill on any loop round, or progressively disclose a frozen external Skill with `meguri.skill.view` when its instructions are needed.
- Execute each selected skill through the frozen Capability Runtime, normalize its bounded output as an untrusted observation, and feed that observation into the next planning round.
- Hard-cap Limited ReAct v1 at six planning decisions and four actions, intersected with all smaller parent Turn limits.
- Keep the existing round, model-call, tool-call, token, cost, deadline, cancellation, duplicate-action, scope, schema, and snapshot protections.
- Emit sanitized skill lifecycle/round traces without exposing action arguments, external Skill bodies, or raw capability payloads.
- Detect weather/outing follow-ups from the active session history and preserve the explicit date expression from the preceding weather request.
- Keep ordinary non-weather messages and sessions without a prior weather turn on the existing path.

## Capabilities

### New Capabilities

- `react-loop-skill-dispatch`: bounded ReAct rounds may choose and execute authorized prompt skills as observations.
- `weather-followup-context`: short weather follow-ups inherit the active weather intent and date context from the current conversation branch.

### Modified Capabilities

<!-- No archived main capabilities exist in openspec/specs yet. -->

## Impact

- Java ReAct provider planner, action validation, frozen external-Skill disclosure, and Capability Runtime adapter.
- Java Turn orchestration, session-context lookup, and weather conversation classification.
- ReAct, weather, and turn integration tests.
- Feature flags remain unchanged; the behavior is available only on the existing AGENT/Limited ReAct path, while the normal default remains fail-closed.
