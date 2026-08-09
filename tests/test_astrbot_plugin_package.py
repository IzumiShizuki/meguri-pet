import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from adapters.astrbot.package_plugin import PLUGIN_NAME, build_plugin_archive


class AstrBotPluginPackageTests(unittest.TestCase):
    def test_archive_has_a_loadable_plugin_root_and_no_local_state(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = build_plugin_archive(Path(directory) / "meguri.zip")
            with ZipFile(archive_path) as archive:
                names = set(archive.namelist())
                metadata = archive.read(
                    f"{PLUGIN_NAME}/metadata.yaml"
                ).decode("utf-8")
            prefix = f"{PLUGIN_NAME}/"
            self.assertIn(prefix + "main.py", names)
            self.assertIn(prefix + "metadata.yaml", names)
            self.assertIn(prefix + "_conf_schema.json", names)
            self.assertIn(prefix + "requirements.txt", names)
            self.assertIn(prefix + "bilibili_daily_card.py", names)
            self.assertIn(prefix + "assets/bilibili_daily_template.png", names)
            self.assertIn(prefix + "assets/NotoSansSC-Regular.otf", names)
            self.assertFalse(any("__pycache__" in name for name in names))
            self.assertFalse(any("token" in name.casefold() for name in names))
            self.assertIn('astrbot_version: ">=4.26,<5"', metadata)
            self.assertNotIn("repo:", metadata)
