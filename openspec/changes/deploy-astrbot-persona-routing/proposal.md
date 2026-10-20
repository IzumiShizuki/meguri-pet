## Why

The owner has explicitly requested deployment of the tested persona backend routing feature. The existing personal-server gateway must acquire the feature while retaining its enabled owner Todo creation and independent reminder/report settings.

## What Changes

- Inspect the live AstrBot 4.28.1 gateway, effective default persona and native persona API on 111.228.35.186.
- Back up the exact deployed files and configuration, rehearse restore in an isolated directory, and install only the required gateway additions.
- Enable persona routing for the existing default persona ID `default`, preserving the current Meguri conversation while other persona IDs use native AstrBot.
- Restart only the existing AstrBot container and verify live loading, preservation of unrelated settings and routing through isolated synthetic events.

## Capabilities

### New Capabilities

- `astrbot-persona-routing-deployment`: Recoverable activation and evidence-based validation of persona backend selection on the existing personal bot.

### Modified Capabilities

None.

## Impact

Only the personal server's existing AstrBot gateway plugin/configuration and local deployment documentation/evidence. Core, website, container image/network/mounts, other plugins, credentials, targets, databases and user messages remain outside deployment mutation scope. No Git push or real QQ test send is required.
