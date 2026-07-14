from __future__ import annotations

from dataclasses import dataclass
import re

from .enums import MemoryType, Sensitivity, SourceKind
from .models import MemoryCandidateCreate


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
        text = candidate.content_text
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
            and candidate.confidence >= self.confidence_threshold
            and candidate.source_kind is SourceKind.DIRECT_USER
        ):
            return PolicyEvaluation("auto_approve", "allowlisted_direct_low_risk_fact")
        return PolicyEvaluation("queue", "manual_review_required")

    def assert_approval_safe(self, candidate: MemoryCandidateCreate) -> None:
        evaluation = self.evaluate(candidate)
        if evaluation.rejected:
            raise ValueError(f"candidate cannot be approved: {evaluation.reason}")
