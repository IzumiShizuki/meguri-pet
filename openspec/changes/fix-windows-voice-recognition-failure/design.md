## Context

See `proposal.md` for the motivation. The desktop main process launches a fixed PowerShell helper. The helper currently collapses all script exceptions into one message, and the JavaScript wrapper maps every nonzero process exit to the same generic recognizer error. The Windows account has an installed zh-CN recognizer, microphone permission is allowed, and opening the default audio endpoint succeeded in a local initialization check; live recognition has not been run by the agent.

## Goals / Non-Goals

**Goals:**
- Preserve safe failure categories across the PowerShell/Node boundary.
- Give the user a next step that matches the failed recognition stage.
- Keep the local-only, bounded, no-audio-persistence design.

**Non-Goals:**
- Add a cloud speech service or change microphone privacy behavior.
- Select or persist a microphone device.
- Surface raw PowerShell/.NET exception text or transcripts in diagnostics.

## Decisions

- The PowerShell helper will track a small fixed set of stages and emit only a known stage code and a localized safe message when a stage fails. It will also write a fixed ASCII stage marker to stderr before each risky stage; this gives the Node wrapper a safe fallback if PowerShell exits before its catch block can produce JSON.
- Node will invoke the fixed script path through a short PowerShell command boundary and pass the path through a child-process environment variable. The boundary emits a launcher marker and catches script parse/dispatch errors into a fixed JSON failure. This covers failures that occur before the `.ps1` file can enter its own `try/catch`.
- Active status text in the compact chat dock will wrap onto a full-width line beneath its action buttons; the passive default hint can remain on one line. A title attribute also preserves the complete text for hover access.
- A Windows-only integration test will opt into a helper diagnostic mode that loads the speech assembly and grammar, then exits before selecting an audio input. Normal desktop launches always force this mode off.
- The `.ps1` helper will be stored as UTF-8 with a BOM. Windows PowerShell 5.1 otherwise decodes BOM-less UTF-8 using the active Windows code page, which corrupts localized strings and can cause a parse failure before the helper's error boundary runs.
- The Node wrapper will parse a valid bounded JSON result before interpreting the helper's process exit code. If no valid result exists, it will use only the last allowlisted stage marker and the numeric exit status. It will never retain or forward arbitrary stderr text.
- Console encoding initialization will run inside the helper's structured error boundary so startup failures can be classified and reported like later failures.
- The existing synchronous one-utterance capture remains unchanged. This avoids a new audio stack or changes to when Meguri accesses the microphone.

## Risks / Trade-offs

- A stage code may narrow the issue without identifying a device-driver cause → The user message will give the relevant Windows setting or retry action and avoid claiming a diagnosis more specific than the observed stage.
- PowerShell may fail before its command body starts → The Node wrapper will retain a helper-process category; once the body starts, the launcher marker distinguishes that from script and recognition stages.
- Actual recognition requires live microphone input → Agent-side validation will be limited to the safe initialization boundary unless the user performs a capture in Meguri.
