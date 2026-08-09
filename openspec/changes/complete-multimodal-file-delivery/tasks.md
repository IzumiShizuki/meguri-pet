## 1. Contract and configuration

- [x] 1.1 Add documented primary-multimodal and `dsv4p` fallback configuration with outer-provider credential inheritance.
- [x] 1.2 Add unit coverage for capability routing, missing fallback failures, and safe content limits.

## 2. Safe multimodal delivery

- [x] 2.1 Consolidate Everything and send-time local-file validation behind a shared policy.
- [x] 2.2 Resolve bounded inline-image and safe local image/PDF attachments into multimodal Core content.
- [x] 2.3 Preserve actual attachment content through the AIRI adapter and route compatible turns to the primary or fallback model.
- [x] 2.4 Route validated multimodal desktop sessions through the local Core while retaining existing remote text-only routing.

## 3. AIRI composition flow

- [x] 3.1 Enable drag/drop image selection and give unsupported file selection a visible error.
- [x] 3.2 Mark eligible `@` resource selections as multimodal reads using the canonical attachment field names.

## 4. Generated-file presentation

- [x] 4.1 Extract and validate model-returned generated artifact references in Core response events.
- [x] 4.2 Render verified artifacts as local-open blue chat links in the AIRI adapter.
- [x] 4.3 Mount and randomize the existing desktop-pet artifact bubble layer and wire its desktop default-open RPC.

## 5. Prompt capability and verification

- [x] 5.1 Register an opt-in prompt-only approved-file reference skill without changing ordinary planning or agent activation.
- [x] 5.2 Run focused Java, gateway, adapter, and AIRI verification and validate the OpenSpec change strictly.
- [x] 5.3 Build both projects and redeploy the local Java, gateway, TTS, and Electron stack without updating remote staging.
