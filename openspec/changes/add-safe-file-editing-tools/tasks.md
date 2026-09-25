## 1. Approved document model

- [x] 1.1 Add a bounded safe resolver for selected text and DOCX documents, including digest and opaque reference validation.
- [x] 1.2 Add DOCX text extraction and exact text-replacement support without expanding runtime dependencies.
- [x] 1.3 Add focused parser, unsafe-path, encoding, size-limit, and DOCX regression tests.

## 2. Proposal and confirm workflow

- [x] 2.1 Parse valid model document-edit proposals and hold digest-bound, expiring preview tokens.
- [x] 2.2 Implement a user-confirmed local write endpoint with same-folder backup and atomic replacement.
- [x] 2.3 Extend the opt-in approved-file prompt instructions for document read and edit-proposal output.

## 3. Desktop conversation flow

- [x] 3.1 Allow supported document choices from `@` and mark them for document reading without changing image/PDF behavior.
- [x] 3.2 Forward valid document proposals through the AIRI adapter and render an explicit preview/confirm interaction.
- [x] 3.3 Add Electron IPC for the locally authenticated confirmation request and default-open changed files.

## 4. Verification and rollout

- [x] 4.1 Add Core, adapter, and desktop tests for read, proposal, decline, stale-digest, backup, and success cases.
- [x] 4.2 Validate the OpenSpec change strictly and build both projects.
- [x] 4.3 Redeploy the local Core, gateway, TTS, and Electron desktop without updating remote staging.
