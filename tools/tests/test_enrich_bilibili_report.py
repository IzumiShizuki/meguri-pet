from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from datetime import date, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

from tools.enrich_bilibili_report import (
    ContentError, JsonHttp, PrivateCache, PublicVideoEvidence, TextModel,
    enrich_report, subtitle_url,
)
from tools.generate_bilibili_browser_report import build_account_report
from tools.publish_daily_report import build_envelope

ROOT = Path(__file__).resolve().parents[2]
IDS = ["BV1xx411c7mD", "BV1Q541167Qg", "BV1ab411c7mE"]


def fixture_report() -> dict:
    titles = ["Java 并发：虚拟线程与线程池", "日语 N3：授受动词的用法", "游戏实况：探索隐藏关卡"]
    records = [dict(bvid=bvid, title=title, cid=100 + index, author_name="演示UP",
                    view_at=int(datetime(2026, 10, 23, 19, index, tzinfo=ZoneInfo("Asia/Shanghai")).timestamp()),
                    progress=900 - index * 100, duration=1200, tag_name="知识", main_category="知识")
               for index, (bvid, title) in enumerate(zip(IDS, titles))]
    return build_account_report(date(2026, 10, 23), ZoneInfo("Asia/Shanghai"), records,
                                len(records), False, ROOT, "success", "fixture")


def transcript_fixture() -> dict:
    texts = ["演示资料：比较虚拟线程与固定线程池的阻塞任务吞吐，说明共享可变状态仍需同步。",
             "演示资料：用例句区分あげる、くれる、もらう的主语视角和授受关系。",
             "演示资料：记录探索路线、隐藏房间入口和机关解法。"]
    return {bvid: {"text": text} for bvid, text in zip(IDS, texts)}


class FixtureModel:
    identity = "fixture-v1"

    def __init__(self):
        self.calls = []
        self.fail = False

    def summarize(self, evidence):
        self.calls.append(copy.deepcopy(evidence))
        if self.fail:
            raise RuntimeError("fixture failure")
        topics = {IDS[0]: "Java并发", IDS[1]: "日语授受动词", IDS[2]: "隐藏关卡探索"}
        descriptions = {IDS[0]: "比较虚拟线程与线程池，解释阻塞任务调度及共享状态同步。",
                        IDS[1]: "通过例句区分あげる、くれる、もらう的授受视角。",
                        IDS[2]: "展示隐藏房间入口、探索路线与机关解法。"}
        return {"videos": [{"bvid": e["bvid"], "summary": descriptions[e["bvid"]],
                            "topics": [topics[e["bvid"]]]} for e in evidence]}


class FixtureHttp:
    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def request(self, url, **kwargs):
        self.calls.append((url, kwargs))
        value = self.responses[len(self.calls) - 1]
        if isinstance(value, Exception):
            raise value
        return value


def public_responses(*, tracks=None, pages=None):
    return [
        {"code": 0, "data": {"bvid": IDS[0], "aid": 42, "desc": "公开简介：虚拟线程的调度。",
                              "pages": pages or [{"cid": 100, "part": "并发原理"}]}},
        {"code": 0, "data": {"aid": 42, "cid": 100, "subtitle": {"subtitles": tracks or [
            {"lan": "zh-CN", "subtitle_url": "//aisubtitle.hdslb.com/test.json"}]}}},
        {"body": [{"from": 0, "to": 2, "content": "虚拟线程阻塞时可以释放载体线程。"}]},
    ]


class ContentDigestTests(unittest.TestCase):
    def test_mixed_genres_have_concrete_subjects_directions_and_matching_links(self):
        with tempfile.TemporaryDirectory() as directory:
            model = FixtureModel()
            imported = {**transcript_fixture(), "BV1ZZ411c7mF": {"text": "unrelated private text"}}
            original = fixture_report()
            result = enrich_report(original, cache=PrivateCache(Path(directory)), fetcher=None,
                                   model=model, imported=imported)
            analysis = result["content_analysis"]
            self.assertEqual(3, analysis["analyzed_videos"])
            self.assertEqual({"subtitle": 3}, analysis["evidence_counts"])
            self.assertEqual(["Java并发", "日语授受动词", "隐藏关卡探索"],
                             [g["topic"] for g in analysis["directions"]])
            self.assertEqual(IDS[1], analysis["directions"][1]["bvids"][0])
            self.assertIn("くれる", next(i for i in analysis["videos"] if i["bvid"] == IDS[1])["summary"])
            self.assertEqual("content_evidence", result["visual_payload"]["analysis_basis"])
            self.assertEqual(3, len(result["visual_payload"]["top_commentary"]))
            self.assertEqual("not_requested", original["videos"][0]["content_summary_status"])
            sent = json.dumps(model.calls)
            for private_field in ("view_at", "author_mid", "progress_seconds", "visit_count", "unrelated private text"):
                self.assertNotIn(private_field, sent)

    def test_sampling_prioritizes_weight_and_marks_remaining_unanalyzed(self):
        with tempfile.TemporaryDirectory() as directory:
            result = enrich_report(fixture_report(), cache=PrivateCache(Path(directory)), fetcher=None,
                                   model=FixtureModel(), imported=transcript_fixture(), limit=1)
            self.assertIn("分析1/3", result["content_analysis"]["coverage"])
            self.assertEqual([IDS[0]], [v["bvid"] for v in result["content_analysis"]["videos"]])
            self.assertEqual(2, sum(v["content_summary_status"] == "not_analyzed" for v in result["videos"]))

    def test_cache_reuses_model_and_changed_transcript_invalidates_only_that_video(self):
        with tempfile.TemporaryDirectory() as directory:
            cache, model, report, imported = PrivateCache(Path(directory)), FixtureModel(), fixture_report(), transcript_fixture()
            enrich_report(report, cache=cache, fetcher=None, model=model, imported=imported)
            enrich_report(report, cache=cache, fetcher=None, model=model, imported=imported)
            self.assertEqual(1, len(model.calls))
            imported[IDS[0]]["text"] += "新增一段内容。"
            enrich_report(report, cache=cache, fetcher=None, model=model, imported=imported)
            self.assertEqual(2, len(model.calls))
            self.assertEqual([IDS[0]], [e["bvid"] for e in model.calls[1]])

    def test_model_failure_preserves_extracts_and_does_not_poison_summary_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            cache, model = PrivateCache(Path(directory)), FixtureModel()
            model.fail = True
            failed = enrich_report(fixture_report(), cache=cache, fetcher=None, model=model, imported=transcript_fixture())
            self.assertEqual("model_failed", failed["content_analysis"]["model_status"])
            self.assertEqual("fallback", failed["videos"][0]["content_summary_status"])
            self.assertTrue(failed["content_analysis"]["videos"][0]["summary"].startswith("字幕摘录"))
            model.fail = False
            recovered = enrich_report(fixture_report(), cache=cache, fetcher=None, model=model, imported=transcript_fixture())
            self.assertEqual("ready", recovered["content_analysis"]["status"])
            self.assertEqual(2, len(model.calls))

    def test_unknown_or_duplicate_model_identifiers_are_rejected(self):
        for bad in ({"bvid": "BV1ZZ411c7mF", "summary": "fabricated", "topics": ["fiction"]},
                    {"bvid": IDS[0], "summary": "duplicate", "topics": ["duplicate"]}):
            with self.subTest(bad=bad), tempfile.TemporaryDirectory() as directory:
                model = FixtureModel()
                model.summarize = lambda evidence: {"videos": [bad, bad, bad]}
                result = enrich_report(fixture_report(), cache=PrivateCache(Path(directory)),
                                       fetcher=None, model=model, imported=transcript_fixture())
                self.assertEqual("model_failed", result["content_analysis"]["model_status"])
                self.assertEqual([], result["content_analysis"]["directions"])

    def test_no_evidence_is_explicitly_title_only_and_budget_exhaustion_skips_analysis(self):
        with tempfile.TemporaryDirectory() as directory:
            options = dict(cache=PrivateCache(Path(directory)), fetcher=None, model=None)
            result = enrich_report(fixture_report(), **options)
            self.assertEqual({"title_only": 3}, result["content_analysis"]["evidence_counts"])
            self.assertTrue(all(i["summary"].startswith("标题涉及") for i in result["content_analysis"]["videos"]))
            timed_out = enrich_report(fixture_report(), **options, deadline=time.monotonic() - 1)
            self.assertEqual(0, timed_out["content_analysis"]["analyzed_videos"])

    def test_public_subtitle_identity_and_description_fallback(self):
        responses = public_responses()
        http = FixtureHttp(responses)
        result = PublicVideoEvidence(http).fetch({"bvid": IDS[0], "title": "并发", "cid": 100})
        self.assertEqual("subtitle", result["basis"])
        self.assertIn("载体线程", result["text"])
        self.assertTrue(all("Cookie" not in opts.get("headers", {}) for _, opts in http.calls))
        responses[1]["data"]["cid"] = 999
        http = FixtureHttp(responses)
        result = PublicVideoEvidence(http).fetch({"bvid": IDS[0], "title": "并发", "cid": 100})
        self.assertEqual("description", result["basis"])
        self.assertEqual(2, len(http.calls))
        http = FixtureHttp([responses[0], RuntimeError("login needed")])
        result = PublicVideoEvidence(http).fetch({"bvid": IDS[0], "title": "并发", "cid": 100})
        self.assertIn("公开简介", result["text"])

    def test_multipart_never_guesses_first_part_and_imported_cid_must_match(self):
        http = FixtureHttp(public_responses(pages=[{"cid": 100}, {"cid": 101}]))
        result = PublicVideoEvidence(http).fetch({"bvid": IDS[0], "title": "课程"})
        self.assertEqual("description", result["basis"])
        self.assertEqual(1, len(http.calls))
        with tempfile.TemporaryDirectory() as directory:
            result = enrich_report(fixture_report(), cache=PrivateCache(Path(directory)), fetcher=None, model=None,
                                   imported={IDS[0]: {"text": "其他分P资料", "cid": 999}})
            self.assertTrue(all(i["basis"] == "title_only" for i in result["content_analysis"]["videos"]))

    def test_chinese_ai_subtitle_preferred_over_other_language_and_public_cache_is_reused(self):
        tracks = [{"lan": "en", "subtitle_url": "//aisubtitle.hdslb.com/english.json"},
                  {"lan": "ai-zh", "subtitle_url": "//aisubtitle.hdslb.com/chinese.json"}]
        http = FixtureHttp(public_responses(tracks=tracks))
        fetcher = PublicVideoEvidence(http)
        with tempfile.TemporaryDirectory() as directory:
            cache = PrivateCache(Path(directory))
            first = enrich_report(fixture_report(), cache=cache, fetcher=fetcher, model=None, limit=1)
            enrich_report(fixture_report(), cache=cache, fetcher=fetcher, model=None, limit=1)
            self.assertEqual(3, len(http.calls))
            self.assertIn("chinese.json", http.calls[2][0])
            self.assertEqual("subtitle", first["content_analysis"]["videos"][0]["basis"])

    def test_untrusted_subtitle_hosts_rejected(self):
        for url in ("http://aisubtitle.hdslb.com/a", "https://evil.example/a", "https://127.0.0.1/a",
                    "https://aisubtitle.hdslb.com.evil.example/a", "https://name:pass@aisubtitle.hdslb.com/a"):
            with self.subTest(url=url), self.assertRaises(ContentError):
                subtitle_url(url)


class ModelHandler(BaseHTTPRequestHandler):
    calls = []

    def log_message(self, *_):
        pass

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.__class__.calls.append(payload)
        evidence = json.loads(payload["messages"][1]["content"])
        value = {"choices": [{"message": {"content": json.dumps(FixtureModel().summarize(evidence), ensure_ascii=False)}}]}
        encoded = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        self.send_response(302)
        self.send_header("Location", "/redirect-target")
        self.send_header("Content-Length", "0")
        self.end_headers()


class ContentCliIntegrationTests(unittest.TestCase):
    def test_offline_cli_model_markdown_publisher_and_original_metadata_compatibility(self):
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            project = workspace / "project"
            project.mkdir()
            report_file, output_file = workspace / "metadata.json", workspace / "content.json"
            report_file.write_text(json.dumps(fixture_report(), ensure_ascii=False), encoding="utf-8")
            original_bytes = report_file.read_bytes()
            transcripts = workspace / "transcripts.json"
            transcripts.write_text(json.dumps(transcript_fixture(), ensure_ascii=False), encoding="utf-8")
            ModelHandler.calls = []
            server = ThreadingHTTPServer(("127.0.0.1", 0), ModelHandler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                command = [sys.executable, str(ROOT / "tools/enrich_bilibili_report.py"), "--project-root", str(project),
                           "--report-json", str(report_file), "--output-json", str(output_file),
                           "--cache-dir", str(workspace / "cache"), "--transcripts-json", str(transcripts), "--no-public-fetch",
                           "--llm-base-url", f"http://127.0.0.1:{server.server_port}/v1", "--llm-model", "fixture"]
                for _ in range(2):
                    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", check=True)
                    self.assertEqual("ready", json.loads(completed.stdout)["status"])
                self.assertEqual(1, len(ModelHandler.calls))
                self.assertEqual(original_bytes, report_file.read_bytes())
                report = json.loads(output_file.read_text(encoding="utf-8"))
                markdown = (project / "reports/daily/bilibili-2026-10-23.md").read_text(encoding="utf-8")
                self.assertIn("具体内容与观看方向", markdown)
                self.assertIn("日语授受动词", markdown)
                self.assertIn("https://www.bilibili.com/video/" + IDS[1], markdown)
                self.assertNotIn("本日报只做元数据分析", markdown)
                envelope = build_envelope("bilibili", report, markdown)
                self.assertIn("观看内容日报", envelope["title"])
                self.assertIn("視聴内容日報", envelope["delivery_text"])
                self.assertEqual("content_evidence", envelope["render_payload"]["analysis_basis"])
                # The transport never follows redirects, including credential-bearing model requests.
                with self.assertRaises(ContentError):
                    JsonHttp(time.monotonic() + 5).request(f"http://127.0.0.1:{server.server_port}/redirect")
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
