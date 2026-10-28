from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

from tools.thesis_evaluation import (
    INPUT_SCHEMA_VERSION,
    EvaluationInputError,
    evaluate_document,
    load_input,
)


ROOT = Path(__file__).resolve().parents[2]
DIGEST = "a" * 64


def tiny_document() -> dict:
    return {
        "schema_version": INPUT_SCHEMA_VERSION,
        "evidence_kind": "synthetic",
        "metadata": {"run": "unit-test"},
        "memory": {
            "queries": [
                {
                    "case_id": "q1", "tenant_id": "t", "user_id": "u",
                    "query_client_id": "client-query", "query_session_id": "session-query",
                    "available_history_contexts": [],
                    "relevant_current": [
                        {"memory_id": "m", "version_id": "v2", "source_session_id": "session-source", "source_client_id": "client-source"}
                    ],
                    "obsolete_versions": [
                        {"memory_id": "m", "version_id": "v1", "source_session_id": "session-old", "source_client_id": "client-source"}
                    ],
                    "hits": [
                        {"memory_id": "m", "version_id": "v2", "source_session_id": "session-source", "source_client_id": "client-source", "tenant_id": "t", "user_id": "u"},
                        {"memory_id": "m", "version_id": "v2", "source_session_id": "session-source", "source_client_id": "client-source", "tenant_id": "t", "user_id": "u"},
                        {"memory_id": "m", "version_id": "v1", "source_session_id": "session-old", "source_client_id": "client-source", "tenant_id": "t", "user_id": "u"}
                    ]
                },
                {
                    "case_id": "q2", "tenant_id": "t", "user_id": "u",
                    "query_client_id": "client-query-2", "query_session_id": "session-query-2",
                    "available_history_contexts": [{"session_id": "session-history", "client_id": "client-source"}],
                    "relevant_current": [], "obsolete_versions": [], "hits": []
                }
            ],
            "credential_probes": [{"probe_id": "p", "admitted": False}],
        },
        "gui": {
            "observations": [
                {
                    "case_id": "g1", "scenario_id": "s1", "application_class": "browser",
                    "coordinate_system": "logical-px", "gold_bbox": {"x": 10, "y": 20, "width": 10, "height": 10},
                    "predicted_point": {"x": 15, "y": 25}, "task_succeeded": False,
                },
                {
                    "case_id": "g2", "scenario_id": "s2", "application_class": "desktop",
                    "coordinate_system": "logical-px", "gold_bbox": {"x": 10, "y": 20, "width": 10, "height": 10},
                    "predicted_point": None, "task_succeeded": True,
                },
            ],
            "sensitive_probes": [{"probe_id": "sensitive", "authorized": False, "operation_succeeded": False}],
        },
        "cross_client": {
            "cases": [
                {
                    "case_id": "c1", "source_client_id": "desktop", "source_session_id": "source-session",
                    "receiving_client_id": "mobile", "receiving_session_id": "receive-session", "stable_network": True,
                    "authoritative": {"tenant_id": "t", "user_id": "u", "memory_id": "m", "version_id": "v1", "state_digest": DIGEST, "committed_at_ms": 1000},
                    "received": {"tenant_id": "t", "user_id": "u", "memory_id": "m", "version_id": "v1", "state_digest": DIGEST, "session_id": "receive-session", "delivered_at_ms": 1300},
                },
                {
                    "case_id": "c2", "source_client_id": "desktop", "source_session_id": "source-session-2",
                    "receiving_client_id": "browser", "receiving_session_id": "receive-session-2", "stable_network": True,
                    "authoritative": {"tenant_id": "t", "user_id": "u", "memory_id": "m2", "version_id": "v1", "state_digest": "b" * 64, "committed_at_ms": 2000},
                    "received": None,
                },
            ]
        },
    }


def threshold_sized_synthetic() -> dict:
    doc = tiny_document()
    queries = []
    for index in range(150):
        positives = [
            {"memory_id": f"m{index}-{part}", "version_id": "v1", "source_session_id": f"source-{index}-{part}", "source_client_id": "client-source"}
            for part in range(2)
        ]
        hits = [
            {**positive, "tenant_id": "t", "user_id": "u"}
            for positive in positives[: (2 if index < 105 else 1)]
        ]
        queries.append({
            "case_id": f"q{index}", "tenant_id": "t", "user_id": "u",
            "query_client_id": "client-query", "query_session_id": f"query-{index}",
            "available_history_contexts": [], "relevant_current": positives,
            "obsolete_versions": [], "hits": hits,
        })
    doc["memory"]["queries"] = queries
    doc["gui"]["observations"] = [
        {
            "case_id": f"g{i}", "scenario_id": f"scenario-{i}",
            "application_class": ("browser", "native", "office")[i % 3],
            "coordinate_system": "logical-px", "gold_bbox": {"x": 0, "y": 0, "width": 10, "height": 10},
            "predicted_point": {"x": 5, "y": 5} if i < 51 else None,
            "task_succeeded": i < 45,
        }
        for i in range(60)
    ]
    doc["cross_client"]["cases"] = [
        {
            "case_id": f"c{i}", "source_client_id": "desktop",
            "source_session_id": f"source-{i}", "receiving_client_id": "mobile",
            "receiving_session_id": f"receive-{i}", "stable_network": True,
            "authoritative": {"tenant_id": "t", "user_id": "u", "memory_id": f"m{i}", "version_id": "v1", "state_digest": DIGEST, "committed_at_ms": 0},
            "received": {"tenant_id": "t", "user_id": "u", "memory_id": f"m{i}", "version_id": "v1", "state_digest": DIGEST, "session_id": f"receive-{i}", "delivered_at_ms": 3000},
        }
        for i in range(50)
    ]
    doc["memory"]["credential_probes"] = [{"probe_id": "credential", "admitted": False}]
    doc["gui"]["sensitive_probes"] = [{"probe_id": "sensitive", "authorized": False, "operation_succeeded": False}]
    doc["evidence_kind"] = "synthetic"
    doc["metadata"] = {"dataset_id": "generated-threshold-test", "reviewed_blind_set": False}
    return doc


def reused_session_memory_queries(old_version_cases: int = 3) -> list[dict]:
    queries = []
    for index in range(150):
        current = {
            "memory_id": f"memory-{index}", "version_id": "v2",
            "source_session_id": "source-session-shared", "source_client_id": "same-client",
        }
        obsolete = {
            "memory_id": f"memory-{index}", "version_id": "v1",
            "source_session_id": "source-session-shared", "source_client_id": "same-client",
        }
        answerable = index < 125
        hits = [{**current, "tenant_id": "t", "user_id": "u"}] if answerable else []
        obsolete_labels = [obsolete] if index < old_version_cases else []
        if index < old_version_cases:
            hits.append({**obsolete, "tenant_id": "t", "user_id": "u"})
        queries.append({
            "case_id": f"shared-session-query-{index}", "tenant_id": "t", "user_id": "u",
            "query_client_id": "same-client", "query_session_id": "query-session-shared",
            "available_history_contexts": ([{"session_id": "source-session-shared", "client_id": "same-client"}] if not answerable else []),
            "relevant_current": [current] if answerable else [],
            "obsolete_versions": obsolete_labels,
            "hits": hits,
        })
    return queries


def test_macro_recall_old_version_and_duplicate_hit_are_independent():
    report = evaluate_document(tiny_document())
    memory = report["memory"]
    assert memory["metrics"]["macro_recall_at_5"]["value"] == 1.0
    assert memory["metrics"]["macro_recall_at_5"]["denominator"] == 1
    assert memory["metrics"]["old_version_query_error_rate"]["value"] == 0.5
    assert memory["metrics"]["abstention_success_rate"]["value"] == 1.0
    assert memory["duplicate_hits_ignored"] == 1
    assert memory["case_results"][0]["returned_top_5"] == 2


def test_identity_leakage_checks_foreign_duplicate_pair_after_valid_owner():
    doc = tiny_document()
    doc["memory"]["queries"][0]["hits"].insert(1, {
        "memory_id": "m", "version_id": "v2", "source_session_id": "session-source",
        "source_client_id": "client-source", "tenant_id": "other-tenant", "user_id": "other-user",
    })
    report = evaluate_document(doc)
    metric = report["memory"]["metrics"]["identity_leakage_hits"]
    assert metric["numerator"] == 1
    assert metric["denominator"] == 3
    checks = {item["name"]: item["passed"] for item in report["acceptance"]["targets"]["memory"]["checks"]}
    assert checks["identity_leakage_hits"] is False


def test_identity_leakage_checks_sixth_and_later_ranked_results():
    doc = tiny_document()
    query = doc["memory"]["queries"][0]
    query["hits"] = [
        {"memory_id": f"valid-{i}", "version_id": "v1", "source_session_id": "s", "source_client_id": "c", "tenant_id": "t", "user_id": "u"}
        for i in range(5)
    ]
    query["hits"].append({
        "memory_id": "foreign", "version_id": "v1", "source_session_id": "s", "source_client_id": "c", "tenant_id": "other", "user_id": "u"
    })
    report = evaluate_document(doc)
    assert report["memory"]["case_results"][0]["returned_top_5"] == 5
    assert report["memory"]["metrics"]["identity_leakage_hits"]["numerator"] == 1


def test_gui_localization_is_separate_from_full_task_success():
    report = evaluate_document(tiny_document())
    gui = report["gui"]
    assert gui["metrics"]["element_localization_accuracy"]["value"] == 0.5
    assert gui["metrics"]["complete_task_success_rate"]["value"] == 0.5
    assert gui["case_results"][0]["localized"] is True
    assert gui["case_results"][0]["task_succeeded"] is False


def test_cross_client_compares_digest_and_missing_stable_delivery_blocks_latency():
    doc = tiny_document()
    doc["cross_client"]["cases"][0]["received"]["state_digest"] = "c" * 64
    report = evaluate_document(doc)
    cross = report["cross_client"]
    assert cross["metrics"]["state_consistency_rate"]["value"] == 0.0
    assert cross["coverage"]["stable_network_missing_delivery_count"] == 1
    gates = {item["name"]: item["passed"] for item in report["acceptance"]["targets"]["cross_client"]["checks"]}
    assert gates["stable_network_sync_p95_ms"] is False
    assert gates["stable_network_missing_deliveries"] is False


def test_cross_session_same_client_and_cross_client_same_session_are_valid_provenance():
    doc = tiny_document()
    doc["memory"]["queries"][0]["relevant_current"][0]["source_client_id"] = "client-query"
    doc["cross_client"]["cases"][0]["receiving_session_id"] = "source-session"
    doc["cross_client"]["cases"][0]["received"]["session_id"] = "source-session"
    report = evaluate_document(doc)
    assert report["memory"]["coverage"]["cross_session_query_case_count"] == 2
    assert report["cross_client"]["coverage"]["cross_client_case_count"] == 2


def test_memory_coverage_counts_150_independent_cases_with_shared_sessions_and_25_abstentions():
    doc = tiny_document()
    doc["memory"]["queries"] = reused_session_memory_queries(old_version_cases=0)
    report = evaluate_document(doc)
    coverage = report["memory"]["coverage"]
    assert coverage["cross_session_query_case_count"] == 150
    assert coverage["unanswerable_query_count"] == 25
    assert coverage["distinct_qualifying_query_sessions"] == 1
    assert coverage["distinct_source_sessions"] == 1


def test_old_version_query_rate_accepts_exactly_three_of_150():
    doc = tiny_document()
    doc["memory"]["queries"] = reused_session_memory_queries(old_version_cases=3)
    metric = evaluate_document(doc)["memory"]["metrics"]["old_version_query_error_rate"]
    assert metric["numerator"] == 3
    assert metric["denominator"] == 150
    assert metric["value"] == 0.02


def test_authorized_only_sensitive_probes_do_not_satisfy_security_gate():
    doc = threshold_sized_synthetic()
    doc["gui"]["sensitive_probes"] = [
        {"probe_id": "authorized-only", "authorized": True, "operation_succeeded": False}
    ]
    report = evaluate_document(doc)
    metric = report["gui"]["metrics"]["unauthorized_sensitive_successes"]
    assert report["gui"]["coverage"]["unauthorized_probe_count"] == 0
    assert metric["value"] is None
    checks = {item["name"]: item["passed"] for item in report["acceptance"]["targets"]["gui"]["checks"]}
    assert checks["unauthorized_sensitive_probes_present"] is False


def test_stable_latency_excludes_received_but_state_inconsistent_cases():
    doc = threshold_sized_synthetic()
    cases = []
    for index in range(100):
        digest = "b" * 64 if index == 99 else DIGEST
        cases.append({
            "case_id": f"sync-{index}", "source_client_id": "desktop", "source_session_id": "source",
            "receiving_client_id": "mobile", "receiving_session_id": "target", "stable_network": True,
            "authoritative": {"tenant_id": "t", "user_id": "u", "memory_id": "m", "version_id": "v1", "state_digest": DIGEST, "committed_at_ms": 1000},
            "received": {"tenant_id": "t", "user_id": "u", "memory_id": "m", "version_id": "v1", "state_digest": digest, "session_id": "target", "delivered_at_ms": 1100},
        })
    doc["cross_client"]["cases"] = cases
    report = evaluate_document(doc)
    cross = report["cross_client"]
    assert cross["metrics"]["state_consistency_rate"]["value"] == 0.99
    assert cross["metrics"]["stable_network_sync_p95_ms"]["value"] == 100
    assert cross["metrics"]["stable_network_sync_p95_ms"]["denominator"] == 99
    assert cross["metrics"]["stable_network_sync_p95_ms"]["excluded"] == 1
    assert cross["excluded_records"]["stable_network_inconsistent_delivery_from_p95"] == 1
    assert cross["coverage"]["stable_network_synchronized_count"] == 99
    assert cross["coverage"]["stable_network_inconsistent_delivery_count"] == 1
    assert cross["case_results"][99]["receipt_duration_ms"] == 100
    assert cross["case_results"][99]["sync_duration_ms"] is None
    gates = {item["name"]: item["passed"] for item in report["acceptance"]["targets"]["cross_client"]["checks"]}
    assert gates["stable_network_sync_p95_ms"] is False
    assert gates["stable_network_inconsistent_deliveries"] is False


def test_stable_sync_p95_uses_linear_interpolation_for_nonuniform_durations():
    doc = tiny_document()
    cases = []
    for index, duration in enumerate((100, 200, 300)):
        cases.append({
            "case_id": f"p95-{index}", "source_client_id": "desktop", "source_session_id": "source",
            "receiving_client_id": "mobile", "receiving_session_id": "target", "stable_network": True,
            "authoritative": {"tenant_id": "t", "user_id": "u", "memory_id": "m", "version_id": "v1", "state_digest": DIGEST, "committed_at_ms": 1000},
            "received": {"tenant_id": "t", "user_id": "u", "memory_id": "m", "version_id": "v1", "state_digest": DIGEST, "session_id": "target", "delivered_at_ms": 1000 + duration},
        })
    doc["cross_client"]["cases"] = cases
    metric = evaluate_document(doc)["cross_client"]["metrics"]["stable_network_sync_p95_ms"]
    assert metric["value"] == 290
    assert metric["denominator"] == 3
    assert metric["excluded"] == 0


def test_empty_sections_are_null_and_not_measured_and_metadata_is_preserved():
    doc = {"schema_version": INPUT_SCHEMA_VERSION, "evidence_kind": "synthetic", "metadata": {"tag": "empty"}}
    report = evaluate_document(doc)
    assert report["metadata"] == {"tag": "empty"}
    for section in ("memory", "gui", "cross_client"):
        assert all(m["value"] is None and m["status"] == "not_measured" for m in report[section]["metrics"].values())
    assert report["acceptance"]["accepted"] is False


def test_inclusive_threshold_boundaries_pass_but_synthetic_never_accepted():
    report = evaluate_document(threshold_sized_synthetic())
    assert report["memory"]["metrics"]["macro_recall_at_5"]["value"] == 0.85
    assert report["gui"]["metrics"]["element_localization_accuracy"]["value"] == 0.85
    assert report["gui"]["metrics"]["complete_task_success_rate"]["value"] == 0.75
    assert report["cross_client"]["metrics"]["stable_network_sync_p95_ms"]["value"] == 3000
    assert all(group["passed"] for group in report["acceptance"]["targets"].values())
    assert report["acceptance"]["accepted"] is False
    assert report["acceptance"]["reason"] == "synthetic_evidence_cannot_pass"


@pytest.mark.parametrize("mutate", [
    lambda d: d["memory"]["credential_probes"].clear(),
    lambda d: d["memory"]["credential_probes"][0].update(admitted=True),
    lambda d: d["gui"]["sensitive_probes"].clear(),
    lambda d: d["gui"]["sensitive_probes"][0].update(operation_succeeded=True),
])
def test_security_gates_fail_when_probes_are_missing_or_violated(mutate):
    doc = threshold_sized_synthetic()
    mutate(doc)
    report = evaluate_document(doc)
    failed = [
        check for group in report["acceptance"]["targets"].values()
        for check in group["checks"]
        if "probe" in check["name"] or "violation" in check["name"] or "unauthorized" in check["name"]
    ]
    assert any(not check["passed"] for check in failed)


@pytest.mark.parametrize("mutation", [
    lambda d: d["memory"]["queries"][0].pop("relevant_current"),
    lambda d: d["memory"]["queries"][0].pop("hits"),
    lambda d: d["memory"]["queries"][1].update(case_id="q1"),
    lambda d: d["memory"]["queries"][0]["hits"][0].pop("version_id"),
    lambda d: d["gui"]["observations"][0].update(task_succeeded="false"),
    lambda d: d["cross_client"]["cases"][0]["received"].update(delivered_at_ms=-1),
    lambda d: d["cross_client"]["cases"][0]["authoritative"].update(state_digest="not-a-digest"),
    lambda d: d["memory"]["queries"][0]["relevant_current"][0].update(source_session_id="session-query"),
    lambda d: d["cross_client"]["cases"][0].update(receiving_client_id="desktop"),
])
def test_malformed_and_duplicate_records_are_rejected(mutation):
    doc = tiny_document()
    mutation(doc)
    with pytest.raises(EvaluationInputError):
        evaluate_document(doc)


def test_nonfinite_values_rejected_in_document_and_json_file(tmp_path):
    doc = tiny_document()
    doc["gui"]["observations"][0]["gold_bbox"]["x"] = float("inf")
    with pytest.raises(EvaluationInputError):
        evaluate_document(doc)
    path = tmp_path / "bad.json"
    path.write_text('{"schema_version":"meguri-thesis-evaluation-input-v1","evidence_kind":"synthetic","metadata":{},"gui":{"observations":[{"gold_bbox":{"x":NaN}}]}}', encoding="utf-8")
    with pytest.raises(EvaluationInputError):
        load_input(path)
    doc = tiny_document()
    doc["cross_client"]["cases"][0]["authoritative"]["committed_at_ms"] = 10**10000
    with pytest.raises(EvaluationInputError):
        evaluate_document(doc)


def test_cli_runs_example_and_returns_error_for_invalid_input(tmp_path):
    command = [sys.executable, str(ROOT / "scripts/evaluate_thesis_strengthening.py")]
    good = subprocess.run(command + [str(ROOT / "examples/thesis-strengthening-synthetic.json")], capture_output=True, text=True)
    assert good.returncode == 0
    report = json.loads(good.stdout)
    assert report["acceptance"]["accepted"] is False
    bad = tmp_path / "invalid.json"
    bad.write_text("{}", encoding="utf-8")
    failed = subprocess.run(command + [str(bad)], capture_output=True, text=True)
    assert failed.returncode != 0
    assert failed.stdout == ""
    assert "evaluation input error" in failed.stderr
