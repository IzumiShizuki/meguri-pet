## 1. Owner and Desktop Delivery

- [x] 1.1 Add a durable main Shizuki account selection and visible QQ configuration/delivery status in desktop settings.
- [x] 1.2 Trigger existing local TTS for selected-account reminder events while preserving cards and system notifications.
- [x] 1.3 Add an atomic desktop reminder outbox, bounded retry policy, and authenticated remote Core publisher.

## 2. Remote Inbox

- [x] 2.1 Add a validated, idempotent and durable owner reminder inbox with upload and cursor feed routes to Meguri Core.
- [x] 2.2 Require the proper authenticated desktop and AstrBot clients plus the server's selected Shizuki user ID.

## 3. AstrBot Delivery

- [x] 3.1 Add owner ID and exact QQ private origin configuration, and an authenticated reminder feed client.
- [x] 3.2 Add a durable cursor poller with send retry and integrate it with plugin lifecycle.

## 4. Review and Rollout

- [x] 4.1 Review the three boundaries for token/target leakage, duplicate handling, and account isolation.
- [x] 4.2 Document deployment configuration, dependency on desktop uptime, and the remaining production rollout steps.
- [x] 4.3 Repair Windows startup Boolean arguments and defer first-time administrator setup to explicit settings save so the desktop can open for account binding.
