"""Command line entrypoint for the explicit offline feedback workflow."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .common import ARTIFACT_ROOT, PipelineError, read_json
from .feedback import build_feedback_dataset, import_feedback, record_review, validate_feedback_dataset


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="Import, explicitly review, and export Meguri feedback; never trains a model")
    commands = result.add_subparsers(dest="command", required=True)
    imported = commands.add_parser("import", help="Redact consented source candidates and leave them pending review")
    imported.add_argument("--source", type=Path, action="append", required=True)
    imported.add_argument("--output-root", type=Path, default=ARTIFACT_ROOT / "feedback")
    imported.add_argument("--allow-legacy-service-provenance", action="store_true",
                          help="Operator attests v1 was captured by the known consent-enforcing Java service; no automatic approval")
    review = commands.add_parser("review", help="Record an explicit human decision bound to the pending candidate digest")
    review.add_argument("--import-dir", type=Path, required=True)
    review.add_argument("--ledger", type=Path, required=True)
    review.add_argument("--feedback-id", required=True)
    review.add_argument("--candidate-digest", required=True)
    review.add_argument("--decision", choices=["approve", "reject", "revoke"], required=True)
    review.add_argument("--reviewer", required=True)
    review.add_argument("--privacy-reviewed", action="store_true")
    review.add_argument("--language", choices=["zh", "ja"])
    review.add_argument("--relationship-stage", choices=["sibling", "pursuit", "lover"])
    review.add_argument("--outfit-code")
    review.add_argument("--interaction-mode", choices=["work", "private", "sleep", "event"])
    review.add_argument("--corrected-response", type=Path, help="Complete schema-valid JSON target; free-text notes alone are not targets")
    review.add_argument("--scene-id")
    build = commands.add_parser("build", help="Combine approved feedback with the unchanged approved B baseline")
    build.add_argument("--import-dir", type=Path, required=True)
    build.add_argument("--ledger", type=Path, required=True)
    build.add_argument("--baseline-dir", type=Path, required=True)
    build.add_argument("--split-root", type=Path, required=True, help="Only locked scene ID metadata is read, never case contents")
    build.add_argument("--output-root", type=Path, default=ARTIFACT_ROOT / "datasets")
    build.add_argument("--validation-fraction", type=float, default=0.15)
    build.add_argument("--split-seed", default="meguri-feedback-split-v1")
    build.add_argument("--previous-dataset", type=Path, help="Preserve earlier group assignments; newly joined conflicting groups are excluded")
    validate = commands.add_parser("validate", help="Check hashes, current source consent, review decisions and split isolation")
    validate.add_argument("--dataset-dir", type=Path, required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "import":
            output = import_feedback(args.source, args.output_root,
                                     allow_legacy_service_provenance=args.allow_legacy_service_provenance)
            payload = {"status": "pass", "import_dir": str(output), "approval": "pending_human_review"}
        elif args.command == "review":
            runtime = {"language": args.language, "relationship_stage": args.relationship_stage,
                       "outfit_code": args.outfit_code, "interaction_mode": args.interaction_mode}
            review = record_review(args.import_dir, args.ledger, feedback_id=args.feedback_id,
                candidate_digest=args.candidate_digest, decision=args.decision, reviewer=args.reviewer,
                privacy_reviewed=args.privacy_reviewed, runtime=runtime,
                corrected_response=read_json(args.corrected_response) if args.corrected_response else None,
                scene_id=args.scene_id)
            payload = {"status": "pass", "feedback_id": review["feedback_id"], "decision": review["decision"]}
        elif args.command == "build":
            output = build_feedback_dataset(import_dir=args.import_dir, ledger=args.ledger,
                baseline_dir=args.baseline_dir, split_root=args.split_root, output_root=args.output_root,
                validation_fraction=args.validation_fraction, split_seed=args.split_seed,
                previous_dataset=args.previous_dataset)
            payload = {"status": "pass", "dataset_dir": str(output), "model_trained": False}
        else:
            manifest, quality = validate_feedback_dataset(args.dataset_dir)
            payload = {"status": quality["status"], "dataset_id": manifest["dataset_id"],
                       "train_count": manifest["train_count"], "validation_count": manifest["validation_count"]}
    except (PipelineError, OSError) as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps(payload, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
