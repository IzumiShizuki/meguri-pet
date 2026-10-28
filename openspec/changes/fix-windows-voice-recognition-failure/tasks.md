## 1. Preserve the Windows failure stage

- [x] 1.1 Track engine initialization, default audio-input setup, and recognition stages in the PowerShell helper; emit bounded stage codes and safe localized guidance.
- [x] 1.2 Parse a valid helper JSON result before handling its exit status, and report a distinct helper-process failure when no valid result was produced.
- [x] 1.3 Keep raw exception text, transcripts, and microphone audio out of the user-facing diagnostics.
- [x] 1.4 Emit and consume allowlisted stage markers for helper exits that occur before a structured result is written.
- [x] 1.5 Move console encoding initialization inside the helper's structured error boundary.
- [x] 1.6 Wrap PowerShell script invocation so parse/dispatch failures return a fixed safe JSON result and launcher stage marker.

## 2. Document and validate the recovery path

- [x] 2.1 Update the desktop voice-input documentation with the stage-specific messages and Windows actions.
- [x] 2.2 Validate the OpenSpec change and inspect the final diff for unrelated edits.
- [x] 2.3 Wrap active chat-dock status messages and expose their complete text on hover.
- [x] 2.4 Add and run a Windows PowerShell helper smoke test that does not access the microphone.
- [x] 2.5 Save the localized helper with UTF-8 BOM encoding for Windows PowerShell 5.1.
