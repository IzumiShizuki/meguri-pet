import json
import tempfile
import unittest
from pathlib import Path

from services.meguri_core.config import BUILD_ID
from services.meguri_core.providers import MockRagProvider
from services.meguri_core.schemas import RuntimeState


def runtime_state(relationship: str = "lover") -> RuntimeState:
    return RuntimeState(
        client_id="airi",
        mode="private",
        relationship_profile=relationship,
        outfit_code="03",
        local_time="2026-07-22T20:00:00+08:00",
        is_holiday=False,
        voice_enabled=False,
        screen_context_enabled=False,
        allowed_expression_tags=["neutral"],
    )


class CanonicalRagProviderTest(unittest.TestCase):
    def make_provider(self, rows: list[dict], concepts: list[dict] | None = None) -> MockRagProvider:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        rag = root / "exports" / "rag"
        rag.mkdir(parents=True)
        (rag / "chunks_train.jsonl").write_text(
            "\n".join(json.dumps({"build_id": BUILD_ID, **row}, ensure_ascii=False) for row in rows) + "\n",
            encoding="utf-8",
        )
        aliases = root / "aliases.json"
        aliases.write_text(
            json.dumps({"version": "test", "concepts": concepts or []}, ensure_ascii=False),
            encoding="utf-8",
        )
        return MockRagProvider(root, aliases)

    def test_relationship_is_a_hard_filter_and_unrelated_queries_return_no_hit(self) -> None:
        provider = self.make_provider([
            {
                "chunk_id": "lover-blue",
                "scene_id": "lover-scene",
                "relationship_stage": "lover",
                "text_zh": "恋人蓝色约定",
                "text_jp": "恋人の青い約束",
            },
            {
                "chunk_id": "sibling-blue",
                "scene_id": "sibling-scene",
                "relationship_stage": "sibling",
                "text_zh": "兄妹蓝色约定",
                "text_jp": "兄妹の青い約束",
            },
        ])

        self.assertEqual(provider.search("蓝色", runtime_state()), ["恋人蓝色约定"])
        self.assertEqual(provider.search("蓝色", runtime_state("sibling")), ["兄妹蓝色约定"])
        self.assertEqual(provider.search("量子电动力学", runtime_state()), [])
        self.assertEqual(provider.search("喜欢什么颜色", runtime_state()), [])

    def test_query_language_selects_the_matching_text(self) -> None:
        provider = self.make_provider([{
            "chunk_id": "bilingual",
            "scene_id": "bilingual-scene",
            "relationship_stage": "lover",
            "text_zh": "爱莉: 蓝色的约定",
            "text_jp": "メグリ: 青い約束",
        }])

        self.assertEqual(provider.search("蓝色约定", runtime_state(), 1), ["爱莉: 蓝色的约定"])
        self.assertEqual(provider.search("青い約束", runtime_state(), 1), ["メグリ: 青い約束"])

    def test_reviewed_aliases_bridge_daily_paraphrases(self) -> None:
        provider = self.make_provider(
            [{
                "chunk_id": "fatigue",
                "scene_id": "fatigue-scene",
                "relationship_stage": "lover",
                "text_zh": "爱莉: 今天也辛苦了\n爱莉: 我刚刚买了新的发卡",
                "text_jp": "メグリ: 今日もお疲れ様でした\nメグリ: 新しい髪飾りを買いました",
            }],
            [{"id": "fatigue", "zh": ["累", "辛苦"], "ja": ["疲れ", "お疲れ"]}],
        )

        self.assertEqual(provider.search("今天工作好累", runtime_state(), 1), ["爱莉: 今天也辛苦了"])
        self.assertEqual(provider.search("今日は疲れた", runtime_state(), 1), ["メグリ: 今日もお疲れ様でした"])
        self.assertEqual(provider.search("帮我写一段代码", runtime_state()), [])

    def test_results_are_diversified_by_scene(self) -> None:
        provider = self.make_provider([
            {"chunk_id": "a", "scene_id": "same", "relationship_stage": "lover", "text_zh": "蓝色约定之一", "text_jp": "青い約束その一"},
            {"chunk_id": "b", "scene_id": "same", "relationship_stage": "lover", "text_zh": "蓝色约定之二", "text_jp": "青い約束その二"},
            {"chunk_id": "c", "scene_id": "other", "relationship_stage": "lover", "text_zh": "蓝色信物", "text_jp": "青い記念品"},
        ])

        self.assertEqual(len(provider.search("蓝色", runtime_state(), 3)), 2)


if __name__ == "__main__":
    unittest.main()
