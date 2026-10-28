## 1. Audit and catalogue

- [x] 1.1 Inspect installed plugin source and AstrBot dispatch rules without exposing secrets or modifying server data
- [x] 1.2 Publish Chinese private-support matrix, common commands and full installed command/alias appendix

## 2. Gateway routing

- [x] 2.1 Honor per-event matched commands, command groups and regex handlers, preserving host filtering
- [x] 2.2 Add bounded ownership checks for media links/cards, image-search state and loaded meme keywords
- [x] 2.3 Preserve explicit Meguri commands after wake-prefix normalization and suppress default LLM for plugin-owned messages
- [x] 2.4 Document alias boundaries and runtime ownership behaviour in the schema/adapter README

## 3. Verification and delivery

- [x] 3.1 Add and pass regressions for plugin priority, explicit commands, ordinary chat, configuration/scoping and automatic triggers
- [x] 3.2 Build and inspect the standalone gateway archive and check the diff/OpenSpec validation
- [x] 3.3 Record read-only live routing verification and concrete deployment/rollback preparation
- [x] 3.4 Verify completeness, correctness and coherence against the change specification

## 4. Authorized server installation

- [x] 4.1 Recheck installed hashes, prepared archive and target service; preserve a timestamped rollback backup
- [x] 4.2 Install only the five reviewed gateway files and reload the gateway using the existing authenticated management path when available
- [x] 4.3 Verify loaded gateway version, runtime/plugin health and deployed routing; update delivery evidence and command catalogue status
