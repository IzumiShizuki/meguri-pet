from __future__ import annotations

import asyncio
import os
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest import mock

from fastapi.testclient import TestClient

from services.meguri_core.app import app
from services.meguri_core.skill_bridge import _download_bounded


class SkillBridgeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.values = {
            name: os.environ.get(name)
            for name in (
                "MEGURI_INTERNAL_BRIDGE_TOKEN",
                "MEGURI_INTERNAL_BRIDGE_ALLOW_TESTCLIENT",
                "MEGURI_SKILL_STAGING_ROOT",
                "MEGURI_MODELSCOPE_DOWNLOAD_TIMEOUT_SECONDS",
            )
        }
        os.environ["MEGURI_INTERNAL_BRIDGE_TOKEN"] = "bridge-test-token"
        os.environ["MEGURI_INTERNAL_BRIDGE_ALLOW_TESTCLIENT"] = "true"
        self.temporary = TemporaryDirectory()
        os.environ["MEGURI_SKILL_STAGING_ROOT"] = self.temporary.name
        self.client = TestClient(app)

    def tearDown(self) -> None:
        self.temporary.cleanup()
        for name, value in self.values.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value

    def test_fetch_requires_internal_token(self) -> None:
        response = self.client.post(
            "/internal/skills/modelscope/fetch",
            json={"skill_id": "@MiniMax-AI/minimax-pdf"},
        )
        self.assertEqual(response.status_code, 503)

    def test_downloader_uses_public_anonymous_skill_repo_fallback(self) -> None:
        api = mock.Mock()
        api.download_skill = None
        api.download_repo.return_value = Path(self.temporary.name)
        fake_module = SimpleNamespace(HubApi=mock.Mock(return_value=api))

        with mock.patch.dict("sys.modules", {"modelscope_hub": fake_module}):
            from services.meguri_core.skill_bridge import _download_public_skill

            result = _download_public_skill(
                "@MiniMax-AI/minimax-pdf", Path(self.temporary.name)
            )

        fake_module.HubApi.assert_called_once_with(
            endpoint="https://modelscope.cn", token=""
        )
        api.download_repo.assert_called_once_with(
            "@MiniMax-AI/minimax-pdf",
            repo_type="skill",
            local_dir=self.temporary.name,
            max_workers=4,
        )
        self.assertEqual(result, Path(self.temporary.name))

    @mock.patch("services.meguri_core.skill_bridge._download_public_skill")
    def test_fetch_returns_only_scoped_staging_metadata(self, download) -> None:
        def fixture(_skill_id: str, destination: Path) -> Path:
            (destination / "SKILL.md").write_text(
                "---\nname: PDF\n---\nbody", encoding="utf-8"
            )
            return destination

        download.side_effect = fixture

        response = self.client.post(
            "/internal/skills/modelscope/fetch",
            headers={"X-Meguri-Internal-Token": "bridge-test-token"},
            json={"skill_id": "@MiniMax-AI/minimax-pdf"},
        )

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["skill_id"], "@MiniMax-AI/minimax-pdf")
        self.assertTrue(
            Path(payload["staging_path"])
            .resolve()
            .is_relative_to(Path(self.temporary.name).resolve())
        )
        self.assertNotIn("bridge-test-token", response.text)

        released = self.client.post(
            "/internal/skills/modelscope/release",
            headers={"X-Meguri-Internal-Token": "bridge-test-token"},
            json={"fetch_id": payload["fetch_id"]},
        )
        self.assertEqual(released.status_code, 200)
        self.assertEqual(list(Path(self.temporary.name).iterdir()), [])

    def test_release_rejects_an_unscoped_fetch_identifier(self) -> None:
        response = self.client.post(
            "/internal/skills/modelscope/release",
            headers={"X-Meguri-Internal-Token": "bridge-test-token"},
            json={"fetch_id": "../outside"},
        )
        self.assertEqual(response.status_code, 422)

    def test_traversal_like_id_is_rejected(self) -> None:
        for skill_id in ("../private", "owner/../private", r"owner\..\private"):
            with self.subTest(skill_id=skill_id):
                response = self.client.post(
                    "/internal/skills/modelscope/fetch",
                    headers={"X-Meguri-Internal-Token": "bridge-test-token"},
                    json={"skill_id": skill_id},
                )
                self.assertEqual(response.status_code, 400)

    @mock.patch("services.meguri_core.skill_bridge._download_public_skill")
    def test_partial_download_is_removed_after_failure(self, download) -> None:
        def fixture(_skill_id: str, destination: Path) -> Path:
            (destination / "partial.txt").write_text("partial", encoding="utf-8")
            raise OSError("connection dropped")

        download.side_effect = fixture

        response = self.client.post(
            "/internal/skills/modelscope/fetch",
            headers={"X-Meguri-Internal-Token": "bridge-test-token"},
            json={"skill_id": "@MiniMax-AI/minimax-pdf"},
        )

        self.assertEqual(response.status_code, 502)
        self.assertEqual(list(Path(self.temporary.name).iterdir()), [])

    @mock.patch("services.meguri_core.skill_bridge._download_public_skill")
    def test_timed_out_download_is_isolated_then_removed_after_worker_finishes(
        self, download
    ) -> None:
        os.environ["MEGURI_MODELSCOPE_DOWNLOAD_TIMEOUT_SECONDS"] = "0.1"

        def fixture(_skill_id: str, destination: Path) -> Path:
            (destination / "partial.txt").write_text("partial", encoding="utf-8")
            time.sleep(0.2)
            return destination

        download.side_effect = fixture

        response = self.client.post(
            "/internal/skills/modelscope/fetch",
            headers={"X-Meguri-Internal-Token": "bridge-test-token"},
            json={"skill_id": "@MiniMax-AI/minimax-pdf"},
        )

        self.assertEqual(response.status_code, 504)
        deadline = time.monotonic() + 1
        while list(Path(self.temporary.name).iterdir()) and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual(list(Path(self.temporary.name).iterdir()), [])

    @mock.patch("services.meguri_core.skill_bridge._download_public_skill")
    def test_cancelled_download_is_removed_after_worker_finishes(
        self, download
    ) -> None:
        staging_root = Path(self.temporary.name).resolve()
        destination = staging_root / ("fetch-" + "a" * 32)

        def fixture(_skill_id: str, target: Path) -> Path:
            target.mkdir()
            (target / "partial.txt").write_text("partial", encoding="utf-8")
            time.sleep(0.2)
            return target

        download.side_effect = fixture

        async def cancel_download() -> None:
            task = asyncio.create_task(
                _download_bounded("@MiniMax-AI/minimax-pdf", destination, staging_root)
            )
            await asyncio.sleep(0.05)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            await asyncio.sleep(0.25)

        asyncio.run(cancel_download())
        self.assertEqual(list(staging_root.iterdir()), [])

    @mock.patch("services.meguri_core.skill_bridge._download_public_skill")
    def test_oversized_download_is_rejected_and_removed(self, download) -> None:
        def fixture(_skill_id: str, destination: Path) -> Path:
            (destination / "large.txt").write_bytes(b"x" * (1_048_576 + 1))
            return destination

        download.side_effect = fixture

        response = self.client.post(
            "/internal/skills/modelscope/fetch",
            headers={"X-Meguri-Internal-Token": "bridge-test-token"},
            json={"skill_id": "@MiniMax-AI/minimax-pdf"},
        )

        self.assertEqual(response.status_code, 413)
        self.assertEqual(list(Path(self.temporary.name).iterdir()), [])
