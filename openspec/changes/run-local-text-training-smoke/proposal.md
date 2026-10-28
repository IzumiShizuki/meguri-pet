## Why

The owner requests an actual training attempt after the P0 learning bridge was completed. Establish current reproducible GPU training evidence using the existing text-LLM pipeline and approved baseline before investing in a larger experiment.

## What Changes

- Run current environment/data checks and one bounded L-006 smoke experiment with the installed toolchain and cached pinned model.
- Preserve the dirty primary checkout by running the existing committed trainer in an attached clean worktree.
- Save successful or failed preflight/training evidence, produced adapters and practical next steps, and update the existing learning record.

## Capabilities

### New Capabilities

None. This is an operational experiment and documentation change; existing training and evaluation requirements are unchanged. `skip_specs: true` applies.

### Modified Capabilities

None.

## Impact

Local GPU resource use, ignored experiment/probe artifacts, attached worktree and experiment documentation. No dependency reinstall, locked-eval tuning, canonical dataset rewrite, automatic model promotion or desktop provider replacement. Do not fabricate human review for unapproved or withdrawn feedback. Any local failure stays visible rather than weakening the existing gate.
