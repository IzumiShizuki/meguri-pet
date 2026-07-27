from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import re

from .enums import MemoryType, RiskLevel, Sensitivity, SourceKind
from .models import MemoryCandidateCreate


REDACTED_CANDIDATE_TEXT = "[redacted unsafe memory candidate]"
L0_REJECTION_REASONS = frozenset(
    {
        "candidate_risk_is_prohibited",
        "credential_or_high_risk_identifier",
        "sensitive_candidate_requires_separate_workflow",
        "unconfirmed_sensitive_inference",
    }
)
SAFE_REDACTION_KEYS = frozenset(
    {"content_sha256", "redacted", "rejection_reason", "risk_class"}
)


def candidate_payload_sha256(candidate: MemoryCandidateCreate) -> str:
    serialized = candidate.model_dump(mode="json")
    content_payload = {
        "content_text": serialized["content_text"],
        "content_json": serialized["content_json"],
        "provenance": serialized["provenance"],
    }
    encoded = json.dumps(
        content_payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def redact_rejected_candidate(
    candidate: MemoryCandidateCreate,
    evaluation: "PolicyEvaluation",
) -> MemoryCandidateCreate:
    digest = candidate_payload_sha256(candidate)
    is_l0 = evaluation.reason in L0_REJECTION_REASONS
    safe_details = {
        "content_sha256": digest,
        "redacted": True,
        "rejection_reason": evaluation.reason,
        "risk_class": "l0" if is_l0 else "policy_rejected",
    }
    return candidate.model_copy(
        update={
            "content_text": REDACTED_CANDIDATE_TEXT,
            "content_json": safe_details,
            "provenance": safe_details,
            "risk_level": RiskLevel.PROHIBITED if is_l0 else candidate.risk_level,
            "sensitivity": Sensitivity.SENSITIVE if is_l0 else candidate.sensitivity,
        }
    )


def is_redacted_candidate(candidate: MemoryCandidateCreate) -> bool:
    details = candidate.content_json
    digest = details.get("content_sha256")
    return (
        candidate.content_text == REDACTED_CANDIDATE_TEXT
        and frozenset(details) == SAFE_REDACTION_KEYS
        and details.get("redacted") is True
        and details.get("risk_class") in {"l0", "policy_rejected"}
        and isinstance(details.get("rejection_reason"), str)
        and bool(details["rejection_reason"])
        and isinstance(digest, str)
        and re.fullmatch(r"[0-9a-f]{64}", digest) is not None
        and candidate.provenance == details
    )


@dataclass(frozen=True)
class PolicyEvaluation:
    disposition: str
    reason: str

    @property
    def rejected(self) -> bool:
        return self.disposition == "reject"

    @property
    def auto_approved(self) -> bool:
        return self.disposition == "auto_approve"


class CandidateReviewPolicy:
    """Deterministic safety gate applied before any durable memory write."""

    _credential_pattern = re.compile(
        r"(?ix)\b(?:password|passphrase|api[ _-]?key|access[ _-]?token|"
        r"refresh[ _-]?token|cookie|private[ _-]?key|secret|bearer)\b"
        r"|密码|口令|令牌|私钥|银行卡|信用卡|身份证|护照"
    )
    _transient_pattern = re.compile(
        r"(?i)\b(?:right now|today only|for now|currently upset|temporary)\b"
        r"|刚才|现在有点|临时|今天心情|一次性"
    )
    _inference_pattern = re.compile(
        r"(?i)\b(?:probably|maybe|seems? (?:to be|like)|i infer|model inference)\b"
        r"|可能是|看起来像|推测|模型判断"
    )
    _raw_source_pattern = re.compile(
        r"(?i)\b(?:screenshot ocr|raw screenshot|tool log|webpage dump|rag excerpt)\b"
        r"|截图原文|工具日志|网页原文|原作 RAG"
    )
    _sensitive_inference_pattern = re.compile(
        r"(?i)\b(?:diagnos(?:is|ed)|political affiliation|religious belief)\b"
        r"|诊断为|政治倾向|宗教信仰"
    )
    _auto_approve_types = frozenset(
        {MemoryType.USER_PREFERENCE, MemoryType.RECURRING_HABIT}
    )

    def __init__(
        self,
        *,
        auto_approve_enabled: bool = False,
        confidence_threshold: float = 0.9,
    ) -> None:
        self.auto_approve_enabled = auto_approve_enabled
        self.confidence_threshold = confidence_threshold

    def evaluate(self, candidate: MemoryCandidateCreate) -> PolicyEvaluation:
        serialized = candidate.model_dump(mode="json")
        text = "\n".join(
            (
                candidate.content_text,
                json.dumps(serialized["content_json"], ensure_ascii=False, sort_keys=True),
                json.dumps(serialized["provenance"], ensure_ascii=False, sort_keys=True),
            )
        )
        if candidate.risk_level is RiskLevel.PROHIBITED:
            return PolicyEvaluation("reject", "candidate_risk_is_prohibited")
        if self._credential_pattern.search(text):
            return PolicyEvaluation("reject", "credential_or_high_risk_identifier")
        if candidate.sensitivity is Sensitivity.SENSITIVE:
            return PolicyEvaluation("reject", "sensitive_candidate_requires_separate_workflow")
        if self._sensitive_inference_pattern.search(text):
            return PolicyEvaluation("reject", "unconfirmed_sensitive_inference")
        if self._raw_source_pattern.search(text):
            return PolicyEvaluation("reject", "raw_external_or_rag_content")
        if self._transient_pattern.search(text):
            return PolicyEvaluation("reject", "transient_state")
        if self._inference_pattern.search(text):
            return PolicyEvaluation("reject", "model_inference_not_user_fact")
        if (
            self.auto_approve_enabled
            and candidate.memory_type in self._auto_approve_types
            and candidate.sensitivity is Sensitivity.NORMAL
            and candidate.risk_level is RiskLevel.LOW
            and candidate.confidence >= self.confidence_threshold
            and candidate.source_kind is SourceKind.DIRECT_USER
        ):
            return PolicyEvaluation("auto_approve", "allowlisted_direct_low_risk_fact")
        return PolicyEvaluation("queue", "manual_review_required")

    def assert_approval_safe(self, candidate: MemoryCandidateCreate) -> None:
        evaluation = self.evaluate(candidate)
        if evaluation.rejected:
            raise ValueError(f"candidate cannot be approved: {evaluation.reason}")
