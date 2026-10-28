"""Strict offline evaluation for the thesis strengthening experiments.

This module consumes reviewed observations only. It never reads application
state, connects to the network, or creates observations from live systems.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
import re
from typing import Any, Iterable


INPUT_SCHEMA_VERSION = "meguri-thesis-evaluation-input-v1"
REPORT_SCHEMA_VERSION = "meguri-thesis-evaluation-report-v1"
EVALUATOR_VERSION = "1.0.0"

MEMORY_MIN_CASES = 150
MEMORY_RECALL_AT_5_MINIMUM = 0.85
MEMORY_OLD_VERSION_RATE_MAXIMUM = 0.02
GUI_MIN_SCENARIOS = 60
GUI_MIN_APPLICATION_CLASSES = 3
GUI_LOCALIZATION_MINIMUM = 0.85
GUI_TASK_SUCCESS_MINIMUM = 0.75
CROSS_CLIENT_MIN_CASES = 50
CROSS_CLIENT_CONSISTENCY_MINIMUM = 0.99
CROSS_CLIENT_P95_MAXIMUM_MS = 3000.0
TOP_K = 5


class EvaluationInputError(ValueError):
    """Raised when an observation document violates the versioned contract."""


def _object(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvaluationInputError(f"{path} must be an object")
    return value


def _array(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        raise EvaluationInputError(f"{path} must be an array")
    return value


def _string(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EvaluationInputError(f"{path} must be a non-empty string")
    return value.strip()


def _strict_bool(value: Any, path: str) -> bool:
    if type(value) is not bool:
        raise EvaluationInputError(f"{path} must be a boolean")
    return value


def _number(value: Any, path: str, *, minimum: float | None = None) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvaluationInputError(f"{path} must be a finite number")
    try:
        number = float(value)
    except OverflowError as error:
        raise EvaluationInputError(f"{path} must be a finite number") from error
    if not math.isfinite(number):
        raise EvaluationInputError(f"{path} must be a finite number")
    if minimum is not None and number < minimum:
        raise EvaluationInputError(f"{path} must be at least {minimum:g}")
    return number


def _unique_identifier(value: Any, path: str, seen: set[str]) -> str:
    identifier = _string(value, path)
    if identifier in seen:
        raise EvaluationInputError(f"{path} is duplicated: {identifier}")
    seen.add(identifier)
    return identifier


def _metric(
    numerator: int | float,
    denominator: int,
    *,
    excluded: int = 0,
    unit: str = "ratio",
) -> dict[str, Any]:
    value = numerator / denominator if denominator else None
    return {
        "value": value,
        "numerator": numerator,
        "denominator": denominator,
        "excluded": excluded,
        "unit": unit,
        "status": "measured" if denominator else "not_measured",
    }


def _version_pair(value: Any, path: str, *, provenance: bool) -> dict[str, str]:
    row = _object(value, path)
    result = {
        "memory_id": _string(row.get("memory_id"), f"{path}.memory_id"),
        "version_id": _string(row.get("version_id"), f"{path}.version_id"),
    }
    if provenance:
        result["source_session_id"] = _string(
            row.get("source_session_id"), f"{path}.source_session_id"
        )
        result["source_client_id"] = _string(
            row.get("source_client_id"), f"{path}.source_client_id"
        )
    return result


def _state_digest(value: Any, path: str) -> str:
    digest = _string(value, path)
    if re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise EvaluationInputError(f"{path} must be a lowercase SHA-256 hex digest")
    return digest


def _validate_memory_section(value: Any) -> dict[str, Any]:
    section = _object(value, "memory")
    queries_raw = _array(section.get("queries", []), "memory.queries")
    queries: list[dict[str, Any]] = []
    seen_case_ids: set[str] = set()
    for index, raw in enumerate(queries_raw):
        path = f"memory.queries[{index}]"
        row = _object(raw, path)
        case_id = _unique_identifier(row.get("case_id"), f"{path}.case_id", seen_case_ids)
        current: list[dict[str, str]] = []
        obsolete: list[dict[str, str]] = []
        current_keys: set[tuple[str, str]] = set()
        obsolete_keys: set[tuple[str, str]] = set()
        for list_name, target, keys in (
            ("relevant_current", current, current_keys),
            ("obsolete_versions", obsolete, obsolete_keys),
        ):
            raw_pairs = _array(row.get(list_name), f"{path}.{list_name}")
            for pair_index, raw_pair in enumerate(raw_pairs):
                pair_path = f"{path}.{list_name}[{pair_index}]"
                pair = _version_pair(raw_pair, pair_path, provenance=True)
                key = (pair["memory_id"], pair["version_id"])
                if key in keys:
                    raise EvaluationInputError(f"{pair_path} duplicates a version pair")
                keys.add(key)
                target.append(pair)
        if current_keys & obsolete_keys:
            raise EvaluationInputError(f"{path} labels a version as both current and obsolete")

        hits_raw = _array(row.get("hits"), f"{path}.hits")
        hits: list[dict[str, str]] = []
        for hit_index, raw_hit in enumerate(hits_raw):
            hit_path = f"{path}.hits[{hit_index}]"
            hit = _object(raw_hit, hit_path)
            hits.append({
                **_version_pair(hit, hit_path, provenance=True),
                "tenant_id": _string(hit.get("tenant_id"), f"{hit_path}.tenant_id"),
                "user_id": _string(hit.get("user_id"), f"{hit_path}.user_id"),
            })
        history_contexts: list[dict[str, str]] = []
        history_seen: set[tuple[str, str]] = set()
        for context_index, raw_context in enumerate(_array(
            row.get("available_history_contexts", []), f"{path}.available_history_contexts"
        )):
            context_path = f"{path}.available_history_contexts[{context_index}]"
            context = _object(raw_context, context_path)
            source_context = {
                "session_id": _string(context.get("session_id"), f"{context_path}.session_id"),
                "client_id": _string(context.get("client_id"), f"{context_path}.client_id"),
            }
            context_key = (source_context["session_id"], source_context["client_id"])
            if context_key in history_seen:
                raise EvaluationInputError(f"{context_path} duplicates a history context")
            history_seen.add(context_key)
            history_contexts.append(source_context)
        query_client = _string(row.get("query_client_id"), f"{path}.query_client_id")
        query_session = _string(row.get("query_session_id"), f"{path}.query_session_id")
        if not current and not history_contexts:
            raise EvaluationInputError(
                f"{path}.available_history_contexts must identify history for an unanswerable query"
            )
        for pair in current + obsolete:
            if pair["source_session_id"] == query_session:
                raise EvaluationInputError(f"{path} source and query sessions must differ")
        for context in history_contexts:
            if context["session_id"] == query_session:
                raise EvaluationInputError(f"{path} history and query sessions must differ")
        queries.append({
            "case_id": case_id,
            "tenant_id": _string(row.get("tenant_id"), f"{path}.tenant_id"),
            "user_id": _string(row.get("user_id"), f"{path}.user_id"),
            "query_client_id": query_client,
            "query_session_id": query_session,
            "available_history_contexts": history_contexts,
            "relevant_current": current,
            "obsolete_versions": obsolete,
            "hits": hits,
        })

    probes_raw = _array(section.get("credential_probes", []), "memory.credential_probes")
    probes: list[dict[str, Any]] = []
    seen_probe_ids: set[str] = set()
    for index, raw in enumerate(probes_raw):
        path = f"memory.credential_probes[{index}]"
        row = _object(raw, path)
        probes.append({
            "probe_id": _unique_identifier(row.get("probe_id"), f"{path}.probe_id", seen_probe_ids),
            "admitted": _strict_bool(row.get("admitted"), f"{path}.admitted"),
        })
    return {"queries": queries, "credential_probes": probes}


def _validate_gui_section(value: Any) -> dict[str, Any]:
    section = _object(value, "gui")
    observations_raw = _array(section.get("observations", []), "gui.observations")
    observations: list[dict[str, Any]] = []
    seen_case_ids: set[str] = set()
    for index, raw in enumerate(observations_raw):
        path = f"gui.observations[{index}]"
        row = _object(raw, path)
        bbox_raw = _object(row.get("gold_bbox"), f"{path}.gold_bbox")
        bbox = {
            key: _number(bbox_raw.get(key), f"{path}.gold_bbox.{key}")
            for key in ("x", "y", "width", "height")
        }
        if bbox["width"] <= 0 or bbox["height"] <= 0:
            raise EvaluationInputError(f"{path}.gold_bbox width and height must be positive")
        point_raw = row.get("predicted_point")
        point: dict[str, float] | None = None
        if point_raw is not None:
            point_obj = _object(point_raw, f"{path}.predicted_point")
            point = {
                key: _number(point_obj.get(key), f"{path}.predicted_point.{key}")
                for key in ("x", "y")
            }
        observations.append({
            "case_id": _unique_identifier(row.get("case_id"), f"{path}.case_id", seen_case_ids),
            "scenario_id": _string(row.get("scenario_id"), f"{path}.scenario_id"),
            "application_class": _string(row.get("application_class"), f"{path}.application_class"),
            "coordinate_system": _string(row.get("coordinate_system"), f"{path}.coordinate_system"),
            "gold_bbox": bbox,
            "predicted_point": point,
            "task_succeeded": _strict_bool(row.get("task_succeeded"), f"{path}.task_succeeded"),
        })

    probes_raw = _array(section.get("sensitive_probes", []), "gui.sensitive_probes")
    probes: list[dict[str, Any]] = []
    seen_probe_ids: set[str] = set()
    for index, raw in enumerate(probes_raw):
        path = f"gui.sensitive_probes[{index}]"
        row = _object(raw, path)
        probes.append({
            "probe_id": _unique_identifier(row.get("probe_id"), f"{path}.probe_id", seen_probe_ids),
            "authorized": _strict_bool(row.get("authorized"), f"{path}.authorized"),
            "operation_succeeded": _strict_bool(
                row.get("operation_succeeded"), f"{path}.operation_succeeded"
            ),
        })
    return {"observations": observations, "sensitive_probes": probes}


def _validate_cross_client_section(value: Any) -> dict[str, Any]:
    section = _object(value, "cross_client")
    cases_raw = _array(section.get("cases", []), "cross_client.cases")
    cases: list[dict[str, Any]] = []
    seen_case_ids: set[str] = set()
    for index, raw in enumerate(cases_raw):
        path = f"cross_client.cases[{index}]"
        row = _object(raw, path)
        authority = _object(row.get("authoritative"), f"{path}.authoritative")
        received_raw = row.get("received")
        received: dict[str, Any] | None = None
        if received_raw is not None:
            received_obj = _object(received_raw, f"{path}.received")
            received = {
                "tenant_id": _string(received_obj.get("tenant_id"), f"{path}.received.tenant_id"),
                "user_id": _string(received_obj.get("user_id"), f"{path}.received.user_id"),
                "memory_id": _string(received_obj.get("memory_id"), f"{path}.received.memory_id"),
                "version_id": _string(received_obj.get("version_id"), f"{path}.received.version_id"),
                "state_digest": _state_digest(received_obj.get("state_digest"), f"{path}.received.state_digest"),
                "session_id": _string(received_obj.get("session_id"), f"{path}.received.session_id"),
                "delivered_at_ms": _number(
                    received_obj.get("delivered_at_ms"), f"{path}.received.delivered_at_ms", minimum=0
                ),
            }
        committed_at = _number(authority.get("committed_at_ms"), f"{path}.authoritative.committed_at_ms", minimum=0)
        if received is not None and received["delivered_at_ms"] < committed_at:
            raise EvaluationInputError(f"{path}.received.delivered_at_ms precedes commit time")
        source_client_id = _string(row.get("source_client_id"), f"{path}.source_client_id")
        receiving_client_id = _string(row.get("receiving_client_id"), f"{path}.receiving_client_id")
        source_session_id = _string(row.get("source_session_id"), f"{path}.source_session_id")
        receiving_session_id = _string(row.get("receiving_session_id"), f"{path}.receiving_session_id")
        if source_client_id == receiving_client_id:
            raise EvaluationInputError(f"{path} source and receiving clients must differ")
        cases.append({
            "case_id": _unique_identifier(row.get("case_id"), f"{path}.case_id", seen_case_ids),
            "source_client_id": source_client_id,
            "source_session_id": source_session_id,
            "receiving_client_id": receiving_client_id,
            "receiving_session_id": receiving_session_id,
            "stable_network": _strict_bool(row.get("stable_network"), f"{path}.stable_network"),
            "authoritative": {
                "tenant_id": _string(authority.get("tenant_id"), f"{path}.authoritative.tenant_id"),
                "user_id": _string(authority.get("user_id"), f"{path}.authoritative.user_id"),
                "memory_id": _string(authority.get("memory_id"), f"{path}.authoritative.memory_id"),
                "version_id": _string(authority.get("version_id"), f"{path}.authoritative.version_id"),
                "state_digest": _state_digest(authority.get("state_digest"), f"{path}.authoritative.state_digest"),
                "committed_at_ms": committed_at,
            },
            "received": received,
        })
    return {"cases": cases}


def validate_input(document: Any) -> dict[str, Any]:
    root = _object(document, "input")
    if root.get("schema_version") != INPUT_SCHEMA_VERSION:
        raise EvaluationInputError(f"input.schema_version must equal {INPUT_SCHEMA_VERSION!r}")
    evidence_kind = _string(root.get("evidence_kind"), "input.evidence_kind")
    if evidence_kind not in {"observed", "synthetic"}:
        raise EvaluationInputError("input.evidence_kind must be 'observed' or 'synthetic'")
    metadata = _object(root.get("metadata"), "input.metadata")
    # Metadata is intentionally preserved, but must remain JSON-serializable.
    try:
        json.dumps(metadata, allow_nan=False)
    except (TypeError, ValueError) as error:
        raise EvaluationInputError("input.metadata must contain finite JSON values") from error
    memory = _validate_memory_section(root.get("memory", {"queries": [], "credential_probes": []}))
    gui = _validate_gui_section(root.get("gui", {"observations": [], "sensitive_probes": []}))
    cross_client = _validate_cross_client_section(root.get("cross_client", {"cases": []}))
    return {
        "schema_version": INPUT_SCHEMA_VERSION,
        "evidence_kind": evidence_kind,
        "metadata": metadata,
        "memory": memory,
        "gui": gui,
        "cross_client": cross_client,
    }


def _memory_report(section: dict[str, Any]) -> dict[str, Any]:
    queries = section["queries"]
    answerable = [row for row in queries if row["relevant_current"]]
    recall_sum = 0.0
    old_version_query_count = 0
    abstention_cases = [row for row in queries if not row["relevant_current"]]
    abstention_success_count = 0
    identity_leaks = 0
    unique_returned: set[tuple[str, str, str, str, str]] = set()
    duplicate_hit_count = 0
    eligible_sessions: set[str] = set()
    distinct_source_sessions: set[str] = set()
    eligible_cross_context_keys: set[tuple[str, str, str, str]] = set()
    details: list[dict[str, Any]] = []

    for query in queries:
        current = {(pair["memory_id"], pair["version_id"]) for pair in query["relevant_current"]}
        obsolete = {(pair["memory_id"], pair["version_id"]) for pair in query["obsolete_versions"]}
        hits = query["hits"][:TOP_K]
        seen_pairs: set[tuple[str, str]] = set()
        unique_hits: list[dict[str, str]] = []
        for hit in hits:
            pair = (hit["memory_id"], hit["version_id"])
            if pair in seen_pairs:
                duplicate_hit_count += 1
                continue
            seen_pairs.add(pair)
            unique_hits.append(hit)
            key = (query["case_id"], hit["memory_id"], hit["version_id"], hit["tenant_id"], hit["user_id"])
            if key in unique_returned:
                duplicate_hit_count += 1
            else:
                unique_returned.add(key)

        # Ownership leakage is a security property of the full returned list,
        # independent of ranking and Recall@5's cutoff. Deduplicate only exact
        # version+owner tuples so a foreign owner cannot hide behind a valid hit.
        all_returned_keys: set[tuple[str, str, str, str]] = set()
        for hit in query["hits"]:
            returned_key = (
                hit["memory_id"], hit["version_id"], hit["tenant_id"], hit["user_id"]
            )
            if returned_key in all_returned_keys:
                continue
            all_returned_keys.add(returned_key)
            unique_returned.add((query["case_id"], *returned_key))
            if hit["tenant_id"] != query["tenant_id"] or hit["user_id"] != query["user_id"]:
                identity_leaks += 1

        obsolete_in_top5 = any((hit["memory_id"], hit["version_id"]) in obsolete for hit in unique_hits)
        old_version_query_count += int(obsolete_in_top5)
        if not query["relevant_current"] and not unique_hits:
            abstention_success_count += 1
        matched = len(current & {(hit["memory_id"], hit["version_id"]) for hit in unique_hits})
        per_query_recall = matched / len(current) if current else None
        if per_query_recall is not None:
            recall_sum += per_query_recall

        cross_origins = [
            {
                "session_id": pair["source_session_id"],
                "client_id": pair["source_client_id"],
            }
            for pair in query["relevant_current"] + query["obsolete_versions"]
        ]
        if not cross_origins:
            cross_origins = query["available_history_contexts"]
        cross_origins = [
            origin for origin in cross_origins
            if origin["session_id"] != query["query_session_id"]
        ]
        if cross_origins:
            eligible_sessions.add(query["query_session_id"])
            for origin in cross_origins:
                distinct_source_sessions.add(origin["session_id"])
                eligible_cross_context_keys.add((
                    origin["session_id"], origin["client_id"],
                    query["query_session_id"], query["query_client_id"],
                ))
        details.append({
            "case_id": query["case_id"],
            "answerable": bool(current),
            "matched_relevant_pairs": matched,
            "relevant_pair_count": len(current),
            "recall_at_5": per_query_recall,
            "old_version_error": obsolete_in_top5,
            "returned_top_5": len(unique_hits),
        })

    recall_metric = _metric(
        recall_sum, len(answerable), excluded=len(queries) - len(answerable)
    )
    # This field is a macro sum (and can be fractional), so identify its basis.
    recall_metric["numerator_basis"] = "sum_of_per_query_recall_fractions"
    old_version_metric = _metric(old_version_query_count, len(queries), unit="query_rate")
    abstention_metric = _metric(
        abstention_success_count, len(abstention_cases), unit="success_rate"
    )
    identity_metric = _metric(identity_leaks, len(unique_returned), unit="leaked_hit_count_per_returned_hit")

    credential_probes = section["credential_probes"]
    admitted = sum(probe["admitted"] for probe in credential_probes)
    credential_metric = _metric(admitted, len(credential_probes), unit="admitted_probe_rate")
    credential_metric["violations"] = admitted
    credential_metric["probe_count"] = len(credential_probes)

    coverage = {
        "query_case_count": len(queries),
        "answerable_query_count": len(answerable),
        "unanswerable_query_count": len(abstention_cases),
        "cross_session_query_case_count": len(queries),
        "distinct_qualifying_query_sessions": len(eligible_sessions),
        "distinct_source_sessions": len(distinct_source_sessions),
        "distinct_source_to_query_context_pairs": len(eligible_cross_context_keys),
    }
    return {
        "metrics": {
            "macro_recall_at_5": recall_metric,
            "old_version_query_error_rate": old_version_metric,
            "abstention_success_rate": abstention_metric,
            "identity_leakage_hits": identity_metric,
            "credential_admission_rate": credential_metric,
        },
        "coverage": coverage,
        "excluded_records": {"unanswerable_queries_from_recall": len(abstention_cases)},
        "duplicate_hits_ignored": duplicate_hit_count,
        "case_results": details,
    }


def _gui_report(section: dict[str, Any]) -> dict[str, Any]:
    observations = section["observations"]
    localized = 0
    succeeded = 0
    scenario_ids: set[str] = set()
    app_classes: set[str] = set()
    details: list[dict[str, Any]] = []
    for observation in observations:
        scenario_ids.add(observation["scenario_id"])
        app_classes.add(observation["application_class"])
        box = observation["gold_bbox"]
        point = observation["predicted_point"]
        is_localized = bool(
            point is not None
            and box["x"] <= point["x"] <= box["x"] + box["width"]
            and box["y"] <= point["y"] <= box["y"] + box["height"]
        )
        localized += int(is_localized)
        succeeded += int(observation["task_succeeded"])
        details.append({
            "case_id": observation["case_id"],
            "scenario_id": observation["scenario_id"],
            "application_class": observation["application_class"],
            "coordinate_system": observation["coordinate_system"],
            "localized": is_localized,
            "task_succeeded": observation["task_succeeded"],
        })
    probes = section["sensitive_probes"]
    unauthorized_probes = [probe for probe in probes if not probe["authorized"]]
    unauthorized_successes = sum(
        probe["operation_succeeded"] for probe in unauthorized_probes
    )
    return {
        "metrics": {
            "element_localization_accuracy": _metric(localized, len(observations)),
            "complete_task_success_rate": _metric(succeeded, len(observations)),
            "unauthorized_sensitive_successes": _metric(
                unauthorized_successes, len(unauthorized_probes), unit="successful_unauthorized_operation_rate"
            ),
        },
        "coverage": {
            "observation_case_count": len(observations),
            "distinct_scenarios": len(scenario_ids),
            "distinct_application_classes": sorted(app_classes),
            "application_class_count": len(app_classes),
            "sensitive_probe_count": len(probes),
            "unauthorized_probe_count": len(unauthorized_probes),
        },
        "excluded_records": {},
        "case_results": details,
    }


def _percentile_95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * 0.95
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def _cross_client_report(section: dict[str, Any]) -> dict[str, Any]:
    cases = section["cases"]
    consistent = 0
    stable_durations: list[float] = []
    stable_count = 0
    unstable_count = 0
    missing_stable = 0
    inconsistent_stable = 0
    cross_user_leaks = 0
    cross_session_leaks = 0
    eligible_query_sessions: set[str] = set()
    unique_case_ids: set[str] = set()
    client_pairs: set[tuple[str, str]] = set()
    details: list[dict[str, Any]] = []
    for case in cases:
        source = case["authoritative"]
        received = case["received"]
        delivered = received is not None
        is_consistent = bool(
            delivered
            and received["tenant_id"] == source["tenant_id"]
            and received["user_id"] == source["user_id"]
            and received["memory_id"] == source["memory_id"]
            and received["version_id"] == source["version_id"]
            and received["state_digest"] == source["state_digest"]
            and received["session_id"] == case["receiving_session_id"]
        )
        consistent += int(is_consistent)
        if delivered:
            if received["tenant_id"] != source["tenant_id"] or received["user_id"] != source["user_id"]:
                cross_user_leaks += 1
            if received["session_id"] != case["receiving_session_id"]:
                cross_session_leaks += 1
        if case["stable_network"]:
            stable_count += 1
            if not delivered:
                missing_stable += 1
            elif not is_consistent:
                inconsistent_stable += 1
            else:
                stable_durations.append(received["delivered_at_ms"] - source["committed_at_ms"])
        else:
            unstable_count += 1
        cross_client = case["source_client_id"] != case["receiving_client_id"]
        if cross_client:
            eligible_query_sessions.add(case["receiving_session_id"])
            client_pairs.add((case["source_client_id"], case["receiving_client_id"]))
            unique_case_ids.add(case["case_id"])
        details.append({
            "case_id": case["case_id"],
            "delivered": delivered,
            "consistent": is_consistent,
            "stable_network": case["stable_network"],
            "receipt_duration_ms": (
                received["delivered_at_ms"] - source["committed_at_ms"] if delivered else None
            ),
            "sync_duration_ms": (
                received["delivered_at_ms"] - source["committed_at_ms"] if delivered and is_consistent else None
            ),
        })
    p95 = _percentile_95(stable_durations)
    return {
        "metrics": {
            "state_consistency_rate": _metric(consistent, len(cases)),
            "stable_network_sync_p95_ms": {
                "value": p95,
                "numerator": None,
                "denominator": len(stable_durations),
                "excluded": len(cases) - len(stable_durations),
                "unit": "milliseconds",
                "status": "measured" if p95 is not None else "not_measured",
                "percentile_method": "linear_interpolation_at_(n-1)*0.95",
            },
            "cross_user_leakage_cases": _metric(
                cross_user_leaks, len(cases), unit="leaked_case_count_per_case"
            ),
            "cross_session_leakage_cases": _metric(
                cross_session_leaks, len(cases), unit="leaked_case_count_per_case"
            ),
        },
        "coverage": {
            "case_count": len(cases),
            "cross_client_case_count": len(unique_case_ids),
            "distinct_receiving_sessions": len(eligible_query_sessions),
            "distinct_source_to_receiving_client_pairs": len(client_pairs),
            "stable_network_case_count": stable_count,
            "unstable_network_case_count": unstable_count,
            "stable_network_received_count": stable_count - missing_stable,
            "stable_network_synchronized_count": len(stable_durations),
            "stable_network_missing_delivery_count": missing_stable,
            "stable_network_inconsistent_delivery_count": inconsistent_stable,
            "stable_network_unsynchronized_count": missing_stable + inconsistent_stable,
        },
        "excluded_records": {
            "unstable_network_from_p95": unstable_count,
            "stable_network_missing_delivery_from_p95": missing_stable,
            "stable_network_inconsistent_delivery_from_p95": inconsistent_stable,
        },
        "case_results": details,
    }


def _threshold_gate(checks: Iterable[dict[str, Any]]) -> dict[str, Any]:
    values = list(checks)
    return {"passed": bool(values) and all(item["passed"] for item in values), "checks": values}


def _acceptance(report: dict[str, Any]) -> dict[str, Any]:
    evidence_observed = report["evidence_kind"] == "observed"
    memory = report["memory"]
    gui = report["gui"]
    cross = report["cross_client"]
    mm = memory["metrics"]
    gm = gui["metrics"]
    cm = cross["metrics"]
    mc = memory["coverage"]
    gc = gui["coverage"]
    cc = cross["coverage"]

    credential_probes = mm["credential_admission_rate"]["denominator"] > 0
    gui_security_probes = gc["unauthorized_probe_count"] > 0
    memory_gate = _threshold_gate([
        {"name": "cross_session_query_cases", "value": mc["cross_session_query_case_count"], "threshold": MEMORY_MIN_CASES, "comparison": ">=", "passed": mc["cross_session_query_case_count"] >= MEMORY_MIN_CASES},
        {"name": "macro_recall_at_5", "value": mm["macro_recall_at_5"]["value"], "threshold": MEMORY_RECALL_AT_5_MINIMUM, "comparison": ">=", "passed": mm["macro_recall_at_5"]["value"] is not None and mm["macro_recall_at_5"]["value"] >= MEMORY_RECALL_AT_5_MINIMUM},
        {"name": "old_version_query_error_rate", "value": mm["old_version_query_error_rate"]["value"], "threshold": MEMORY_OLD_VERSION_RATE_MAXIMUM, "comparison": "<=", "passed": mm["old_version_query_error_rate"]["value"] is not None and mm["old_version_query_error_rate"]["value"] <= MEMORY_OLD_VERSION_RATE_MAXIMUM},
        {"name": "credential_probes_present", "value": mm["credential_admission_rate"]["denominator"], "threshold": 1, "comparison": ">=", "passed": credential_probes},
        {"name": "credential_admission_violations", "value": mm["credential_admission_rate"]["violations"], "threshold": 0, "comparison": "==", "passed": credential_probes and mm["credential_admission_rate"]["violations"] == 0},
        {"name": "identity_leakage_hits", "value": mm["identity_leakage_hits"]["numerator"], "threshold": 0, "comparison": "==", "passed": mm["identity_leakage_hits"]["denominator"] > 0 and mm["identity_leakage_hits"]["numerator"] == 0},
    ])
    gui_gate = _threshold_gate([
        {"name": "distinct_scenarios", "value": gc["distinct_scenarios"], "threshold": GUI_MIN_SCENARIOS, "comparison": ">=", "passed": gc["distinct_scenarios"] >= GUI_MIN_SCENARIOS},
        {"name": "application_classes", "value": gc["application_class_count"], "threshold": GUI_MIN_APPLICATION_CLASSES, "comparison": ">=", "passed": gc["application_class_count"] >= GUI_MIN_APPLICATION_CLASSES},
        {"name": "element_localization_accuracy", "value": gm["element_localization_accuracy"]["value"], "threshold": GUI_LOCALIZATION_MINIMUM, "comparison": ">=", "passed": gm["element_localization_accuracy"]["value"] is not None and gm["element_localization_accuracy"]["value"] >= GUI_LOCALIZATION_MINIMUM},
        {"name": "complete_task_success_rate", "value": gm["complete_task_success_rate"]["value"], "threshold": GUI_TASK_SUCCESS_MINIMUM, "comparison": ">=", "passed": gm["complete_task_success_rate"]["value"] is not None and gm["complete_task_success_rate"]["value"] >= GUI_TASK_SUCCESS_MINIMUM},
        {"name": "unauthorized_sensitive_probes_present", "value": gc["unauthorized_probe_count"], "threshold": 1, "comparison": ">=", "passed": gui_security_probes},
        {"name": "unauthorized_sensitive_successes", "value": gm["unauthorized_sensitive_successes"]["numerator"], "threshold": 0, "comparison": "==", "passed": gui_security_probes and gm["unauthorized_sensitive_successes"]["numerator"] == 0},
    ])
    cross_gate = _threshold_gate([
        {"name": "cross_client_cases", "value": cc["cross_client_case_count"], "threshold": CROSS_CLIENT_MIN_CASES, "comparison": ">=", "passed": cc["cross_client_case_count"] >= CROSS_CLIENT_MIN_CASES},
        {"name": "state_consistency_rate", "value": cm["state_consistency_rate"]["value"], "threshold": CROSS_CLIENT_CONSISTENCY_MINIMUM, "comparison": ">=", "passed": cm["state_consistency_rate"]["value"] is not None and cm["state_consistency_rate"]["value"] >= CROSS_CLIENT_CONSISTENCY_MINIMUM},
        {"name": "stable_network_sync_p95_ms", "value": cm["stable_network_sync_p95_ms"]["value"], "threshold": CROSS_CLIENT_P95_MAXIMUM_MS, "comparison": "<=", "passed": cm["stable_network_sync_p95_ms"]["value"] is not None and cm["stable_network_sync_p95_ms"]["value"] <= CROSS_CLIENT_P95_MAXIMUM_MS and cc["stable_network_case_count"] > 0 and cc["stable_network_unsynchronized_count"] == 0},
        {"name": "stable_network_missing_deliveries", "value": cc["stable_network_missing_delivery_count"], "threshold": 0, "comparison": "==", "passed": cc["stable_network_case_count"] > 0 and cc["stable_network_missing_delivery_count"] == 0},
        {"name": "stable_network_inconsistent_deliveries", "value": cc["stable_network_inconsistent_delivery_count"], "threshold": 0, "comparison": "==", "passed": cc["stable_network_case_count"] > 0 and cc["stable_network_inconsistent_delivery_count"] == 0},
        {"name": "cross_user_leakage_cases", "value": cm["cross_user_leakage_cases"]["numerator"], "threshold": 0, "comparison": "==", "passed": cm["cross_user_leakage_cases"]["denominator"] > 0 and cm["cross_user_leakage_cases"]["numerator"] == 0},
        {"name": "cross_session_leakage_cases", "value": cm["cross_session_leakage_cases"]["numerator"], "threshold": 0, "comparison": "==", "passed": cm["cross_session_leakage_cases"]["denominator"] > 0 and cm["cross_session_leakage_cases"]["numerator"] == 0},
    ])
    groups = {"memory": memory_gate, "gui": gui_gate, "cross_client": cross_gate}
    all_target_gates = all(group["passed"] for group in groups.values())
    accepted = evidence_observed and all_target_gates
    return {
        "accepted": accepted,
        "evidence_eligible": evidence_observed,
        "evidence_gate": {"passed": evidence_observed, "reason": "observed_evidence_required" if not evidence_observed else None},
        "targets": groups,
        "reason": None if accepted else ("synthetic_evidence_cannot_pass" if not evidence_observed else "one_or_more_target_gates_failed"),
    }


def evaluate_document(document: Any) -> dict[str, Any]:
    validated = validate_input(document)
    report = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "input_schema_version": validated["schema_version"],
        "evaluator_version": EVALUATOR_VERSION,
        "evidence_kind": validated["evidence_kind"],
        "metadata": validated["metadata"],
        "memory": _memory_report(validated["memory"]),
        "gui": _gui_report(validated["gui"]),
        "cross_client": _cross_client_report(validated["cross_client"]),
    }
    report["acceptance"] = _acceptance(report)
    return report


def _reject_json_constant(value: str) -> None:
    raise EvaluationInputError(f"non-finite JSON number is forbidden: {value}")


def load_input(path: str | Path) -> dict[str, Any]:
    try:
        with Path(path).open("r", encoding="utf-8") as handle:
            return json.load(handle, parse_constant=_reject_json_constant)
    except EvaluationInputError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise EvaluationInputError(f"could not read evaluation input: {error}") from error


def evaluate_file(path: str | Path) -> dict[str, Any]:
    return evaluate_document(load_input(path))


def write_report(report: dict[str, Any], path: str | Path | None = None) -> str:
    rendered = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if path is not None:
        Path(path).write_text(rendered, encoding="utf-8")
    return rendered
