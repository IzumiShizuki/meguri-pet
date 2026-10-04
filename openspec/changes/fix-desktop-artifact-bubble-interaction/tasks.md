## 1. Renderer interaction seam

- [x] 1.1 Extract generated-file bubble DOM construction and activation behavior into a testable browser-side module.
- [x] 1.2 Render separate native open and dismiss buttons, preserving artifact icon/badge presentation and accessibility labels.
- [x] 1.3 Wire the page's existing artifact opener and icon lookup into the extracted bubble module without changing feed or allow-list behavior.

## 2. Regression coverage

- [x] 2.1 Add fast Node regression tests for pointer activation, keyboard activation, successful removal, failed-open retention, and independent dismissal.
- [x] 2.2 Run the regression tests against the current implementation before and after the fix, recording the exact symptom as the pass/fail signal.

## 3. Verification

- [x] 3.1 Validate the OpenSpec change and run the desktop-airi tests plus the new bubble regression test.
- [x] 3.2 Inspect the final diff to confirm unrelated user changes remain untouched and no debug instrumentation remains.
