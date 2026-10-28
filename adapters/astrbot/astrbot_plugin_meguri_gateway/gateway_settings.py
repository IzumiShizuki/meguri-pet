"""Advanced settings presentation with one-time legacy configuration migration."""

from copy import deepcopy
from typing import Any


ADVANCED_DEFAULTS = {
    "core_url": "http://127.0.0.1:18080",
    "tenant_id": "meguri-local",
    "timeout_seconds": 8.0,
    "async_turns_enabled": True,
    "turn_checkpoint_file": "data/plugin_data/astrbot_plugin_meguri_gateway/turn-checkpoints.json",
    "identity_salt_env": "MEGURI_IDENTITY_SALT",
    "identity_salt_file": "",
    "core_token_file_env": "MEGURI_ASTRBOT_SHARED_TOKEN_FILE",
    "core_token_file": "",
    "timeprism_todo_draft_file": "data/plugin_data/astrbot_plugin_meguri_gateway/timeprism-todo-drafts.json",
    "timeprism_reminder_poll_seconds": 15,
    "timeprism_reminder_max_age_minutes": 30,
    "timeprism_reminder_state_file": "data/plugin_data/astrbot_plugin_meguri_gateway/timeprism-reminder-state.json",
    "daily_report_poll_seconds": 60,
    "daily_report_max_age_hours": 48,
    "daily_report_state_file": "data/plugin_data/astrbot_plugin_meguri_gateway/daily-report-state.json",
    "daily_report_render_directory": "data/plugin_data/astrbot_plugin_meguri_gateway/rendered-reports",
    "relay_url": "http://127.0.0.1:18100",
    "relay_token_file_env": "MEGURI_RELAY_TOKEN_FILE",
}
GROUP = "advanced_settings"
MIGRATED = "_advanced_settings_migrated"


def effective_gateway_settings(config: Any) -> dict[str, Any]:
    """Keep persisted UI groups while existing consumers continue using flat keys."""
    advanced = config.get(GROUP, {})
    if not isinstance(advanced, dict):
        raise ValueError("advanced_settings must be an object")
    if config.get(MIGRATED) is not True:
        # AstrBot inserts grouped schema defaults before instantiating plugins.
        # Preserve legacy values on the first load, then honor grouped edits.
        advanced = {key: deepcopy(config.get(key, advanced.get(key, default)))
                    for key, default in ADVANCED_DEFAULTS.items()}
        config[GROUP] = advanced
        config[MIGRATED] = True
        save = getattr(config, "save_config", None)
        if callable(save):
            save()
    effective = dict(config)
    effective.update({key: advanced.get(key, default) for key, default in ADVANCED_DEFAULTS.items()})
    return effective


def group_gateway_schema(schema: dict[str, Any]) -> dict[str, Any]:
    """Keep invisible flat fields so AstrBot does not remove legacy saved values."""
    result = deepcopy(schema)
    items = {}
    for key in ADVANCED_DEFAULTS:
        if key not in result:
            continue
        items[key] = deepcopy(result[key])
        items[key].pop("invisible", None)
        result[key]["invisible"] = True
    result[GROUP] = {
        "description": "高级设置",
        "hint": "服务地址、凭据文件和轮询参数。已自动沿用现有配置，日常使用通常无需修改。",
        "type": "object",
        "collapsed": True,
        "items": items,
    }
    result[MIGRATED] = {"type": "bool", "default": False, "invisible": True}
    return result
