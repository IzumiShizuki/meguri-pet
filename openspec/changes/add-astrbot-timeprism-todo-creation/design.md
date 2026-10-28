## Context

See proposal.md for motivation. AstrBot currently polls Core's reminder inbox for delivery, while the Desktop client owns the existing TimePrism login and Todo creation flow. Core already authenticates AstrBot calls with a shared bearer and adapter identity headers, and stores reminder state under `MEGURI_REMINDER_DIR`.

The feature crosses the AstrBot Python plugin and the Java Core service. Shizuki credentials must remain server-side; the existing reminder delivery path must continue to work unchanged.

## Goals / Non-Goals

**Goals:**
- Keep parsing, private sender authorization, preview, edit, cancel, and explicit confirmation in AstrBot.
- Keep Shizuki credentials and all TimePrism network writes in Java Core.
- Reuse the existing owner binding and Core identity model, and make confirmed writes safe to retry.
- Use Asia/Shanghai wall time and infer the nearest future occurrence when the request omits its date or AM/PM period.

**Non-Goals:**
- Reuse Desktop login tokens or expose Shizuki credentials to AstrBot.
- Support group chats, recurring reminders, arbitrary scheduled actions, or offline delivery guarantees.
- Install or reconfigure a live AstrBot/Core deployment as part of this code change.

## Decisions

1. **AstrBot owns an owner-only draft state machine.** Add a feature flag defaulting off and an exact private sender allowlist using `platform:bot_account:sender_id`. Parse only direct messages from those senders; bind persisted drafts to the opaque actor identity produced by `IdentityBindingStore`, and bound their count and lifetime. Natural requests create a draft and preview. `/meguri todo edit <draft-id> <reminder text>`, `/meguri todo confirm <draft-id>`, and `/meguri todo cancel <draft-id>` operate on that draft. Editing reparses a complete reminder request and assigns a new request identifier so a prior uncertain submission cannot be changed and accidentally duplicated.

   A small Python parser will follow the already established Desktop reminder grammar: one reminder, one time, one-time requests, Asia/Shanghai, relative day words and explicit calendar dates, Chinese and Arabic clock numerals, and nearest-future AM/PM selection for bare 1–12 hours. Unsupported recurrence, remote timezones, mixed actions, and unresolved time expressions return a clarification or unsupported response; none are submitted.

2. **Core is the only TimePrism writer.** Add a dedicated authenticated Core endpoint under `/v1/reminders/timeprism/todos`. It accepts a request ID, title, and Shanghai local due time, and requires the configured owner identity, AstrBot client identity, and a dedicated `timeprism-todo-writer` session. The endpoint validates owner binding and input before calling a Core service that logs in and writes the same reminder fields as the Desktop client.

   Core reads the owner email and password from separate absolute external file paths (environment properties `MEGURI_TIMEPRISM_OWNER_EMAIL_FILE` and `MEGURI_TIMEPRISM_OWNER_PASSWORD_FILE`) and never returns or logs their values. The TimePrism site origin uses the existing Shizuki site default, with HTTPS required except for loopback development. These credentials are distinct from the Core shared bearer used by AstrBot.

3. **Use a durable Core write journal and marker reconciliation.** Persist request ID, normalized payload fingerprint, configured account identity, state, and confirmed Todo identity in a bounded journal under `MEGURI_REMINDER_DIR`. Record an uncertain state before sending the remote POST and place a stable request marker in the Todo detail. On repeated or timed-out requests, inspect the configured account for that marker and verify the Todo's title, due time, and reminder fields before returning success. A changed payload conflicts; an uncertain result never triggers a blind second POST. Reuse the Desktop client's expected TimePrism payload and response checks.

4. **Fail closed when the feature is not configured.** AstrBot's writer flag is off by default. Core refuses writes if owner ID, valid Core authentication, external credential files, or an available TimePrism login are missing. Existing reminder polling remains independent of the writer flag.

## Risks / Trade-offs

- [AstrBot may restart between preview and confirmation] → Persist bounded draft state atomically in plugin data, including expiry and actor binding.
- [TimePrism may commit a POST before a timeout] → Journal before dispatch and reconcile by a stable request marker; never blindly repeat an uncertain create.
- [Credential file paths may be missing or unreadable] → Fail closed with a non-sensitive configuration error; do not log file contents.
- [Natural-language parsing may encounter an unsupported phrase] → Limit parsing to a bounded grammar and ask for clarification rather than guessing or writing.

## Migration Plan

No data migration is needed. The AstrBot feature remains disabled until an operator adds the exact private sender allowlist and enables it. Core remains read-only for Todo creation until its external credential file paths and existing owner ID are configured. Rollback consists of disabling the AstrBot feature and reverting the code; existing reminder delivery and its inbox are unchanged. This implementation does not modify deployment files or restart services.
