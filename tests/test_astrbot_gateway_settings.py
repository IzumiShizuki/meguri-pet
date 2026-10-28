import json
import unittest
from pathlib import Path

from adapters.astrbot.astrbot_plugin_meguri_gateway.gateway_settings import (
    ADVANCED_DEFAULTS, GROUP, MIGRATED, effective_gateway_settings, group_gateway_schema,
)


class SavedConfig(dict):
    saves = 0

    def save_config(self):
        self.saves += 1


class GatewaySettingsTests(unittest.TestCase):
    def test_migrates_existing_values_before_inserted_defaults_and_saves_once(self):
        config = SavedConfig(core_url="http://127.0.0.1:19000", tenant_id="existing-tenant",
                             core_token_file="/run/secrets/existing-token", async_turns_enabled=False,
                             advanced_settings=dict(ADVANCED_DEFAULTS), timeprism_owner_user_id=42)
        settings = effective_gateway_settings(config)
        self.assertEqual(settings["core_url"], "http://127.0.0.1:19000")
        self.assertEqual(settings["core_token_file"], "/run/secrets/existing-token")
        self.assertFalse(settings["async_turns_enabled"])
        self.assertEqual(settings["timeprism_owner_user_id"], 42)
        self.assertTrue(config[MIGRATED])
        self.assertEqual(config.saves, 1)
        effective_gateway_settings(config)
        self.assertEqual(config.saves, 1)

    def test_grouped_edits_override_legacy_including_empty_false_and_zero(self):
        config = SavedConfig(core_url="legacy", core_token_file="old-token", async_turns_enabled=True)
        effective_gateway_settings(config)
        config[GROUP].update(core_url="http://127.0.0.1:19100", core_token_file="",
                             async_turns_enabled=False, timeprism_reminder_max_age_minutes=0)
        settings = effective_gateway_settings(config)
        self.assertEqual(settings["core_url"], "http://127.0.0.1:19100")
        self.assertEqual(settings["core_token_file"], "")
        self.assertFalse(settings["async_turns_enabled"])
        self.assertEqual(settings["timeprism_reminder_max_age_minutes"], 0)

    def test_new_nested_config_without_legacy_values_is_preserved(self):
        config = SavedConfig(advanced_settings={"tenant_id": "new-tenant"})
        self.assertEqual(effective_gateway_settings(config)["tenant_id"], "new-tenant")

    def test_invalid_group_fails_instead_of_silently_losing_configuration(self):
        with self.assertRaises(ValueError):
            effective_gateway_settings({GROUP: "invalid"})

    def test_schema_keeps_public_controls_and_hides_legacy_fields(self):
        path = Path(__file__).parents[1]/"adapters/astrbot/astrbot_plugin_meguri_gateway/_conf_schema.json"
        schema = group_gateway_schema(json.loads(path.read_text(encoding="utf-8-sig")))
        self.assertTrue(schema[GROUP]["collapsed"])
        self.assertEqual(set(schema[GROUP]["items"]), set(ADVANCED_DEFAULTS))
        for key in ADVANCED_DEFAULTS:
            self.assertTrue(schema[key]["invisible"])
            self.assertNotIn("invisible", schema[GROUP]["items"][key])
        for key in ["timeprism_todo_creation_enabled", "timeprism_owner_user_id", "timeprism_reminder_target"]:
            self.assertFalse(schema[key].get("invisible", False))


if __name__ == "__main__":
    unittest.main()
