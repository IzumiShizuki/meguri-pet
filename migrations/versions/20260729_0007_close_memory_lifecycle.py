"""Close the Notion 20.2 memory lifecycle and projection contract.

Revision ID: 20260729_0007
Revises: 20260728_0006
"""
from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "20260729_0007"
down_revision: str | Sequence[str] | None = "20260728_0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.drop_index("uq_memory_items_active_canonical_key", table_name="memory_items")
    op.create_index(
        "uq_memory_items_authoritative_canonical_key", "memory_items",
        ["tenant_id", "user_id", "memory_type", "canonical_key"], unique=True,
        postgresql_where=sa.text("status IN ('active','conflicted') AND canonical_key IS NOT NULL"),
    )
    op.drop_constraint("ck_memory_candidates_valid_source_kind", "memory_candidates", type_="check")
    op.create_check_constraint(
        "ck_memory_candidates_valid_source_kind", "memory_candidates",
        "source_kind IN ('user_manual','direct_user','repeated_evidence','local_edit','summary','external',"
        "'llm_candidate','memoryos_import','mem0_shadow','admin')",
    )
    op.drop_constraint("ck_memory_candidates_valid_merge_policy", "memory_candidates", type_="check")
    op.create_check_constraint(
        "ck_memory_candidates_valid_merge_policy", "memory_candidates",
        "merge_policy IN ('replace','set_union','set_remove','confirm','state_transition',"
        "'three_way_merge','manual','review','create_only','supersede')",
    )
    op.drop_constraint("ck_memory_candidates_valid_status", "memory_candidates", type_="check")
    op.create_check_constraint(
        "ck_memory_candidates_valid_status", "memory_candidates",
        "status IN ('pending','auto_approved','needs_confirmation','approved','rejected','expired',"
        "'pending_review','processing')",
    )
    op.drop_constraint("ck_memory_items_valid_status", "memory_items", type_="check")
    op.create_check_constraint(
        "ck_memory_items_valid_status", "memory_items",
        "status IN ('active','conflicted','deleted','superseded','expired','archived')",
    )
    op.add_column("memory_items", sa.Column("last_stable_version_id", postgresql.UUID(as_uuid=True)))
    op.create_foreign_key(
        "fk_memory_items_last_stable_version_same_item", "memory_items", "memory_versions",
        ["memory_id", "last_stable_version_id"], ["memory_id", "version_id"],
        use_alter=True, deferrable=True,
        initially="DEFERRED",
    )
    op.add_column(
        "memory_versions",
        sa.Column("status", sa.String(30), nullable=False, server_default=sa.text("'active'")),
    )
    op.add_column("memory_versions", sa.Column("base_version_id", postgresql.UUID(as_uuid=True)))
    op.create_check_constraint(
        "ck_memory_versions_valid_status", "memory_versions",
        "status IN ('active','superseded','conflict_branch','tombstone')",
    )
    op.create_foreign_key(
        "fk_memory_versions_base_version", "memory_versions", "memory_versions",
        ["base_version_id"], ["version_id"], ondelete="SET NULL",
    )
    op.execute("UPDATE memory_items SET last_stable_version_id = current_version_id WHERE status = 'active'")
    op.create_check_constraint(
        "ck_memory_candidates_acceptance_consistent",
        "memory_candidates",
        "(status IN ('approved','auto_approved') AND accepted_memory_id IS NOT NULL) OR "
        "(status NOT IN ('approved','auto_approved') AND accepted_memory_id IS NULL)",
    )
    op.execute("DROP TRIGGER IF EXISTS trg_memory_versions_immutable ON memory_versions")
    op.execute("DROP FUNCTION IF EXISTS meguri_reject_memory_version_update()")
    op.execute(
        """
        CREATE FUNCTION meguri_enforce_memory_version_transition()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF OLD.status = 'active' AND NEW.status = 'superseded'
               AND (to_jsonb(NEW) - 'status') = (to_jsonb(OLD) - 'status') THEN
                RETURN NEW;
            END IF;
            RAISE EXCEPTION 'illegal or mutable memory version transition: % -> %',
                OLD.status, NEW.status;
        END
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_memory_versions_immutable
        BEFORE UPDATE ON memory_versions
        FOR EACH ROW EXECUTE FUNCTION meguri_enforce_memory_version_transition()
        """
    )
    op.execute(
        """
        CREATE FUNCTION meguri_enforce_candidate_transition()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF (to_jsonb(NEW) - ARRAY[
                    'status','review_reason','reviewed_by','reviewed_at',
                    'accepted_memory_id','updated_at'
                ]) <> (to_jsonb(OLD) - ARRAY[
                    'status','review_reason','reviewed_by','reviewed_at',
                    'accepted_memory_id','updated_at'
                ]) THEN
                RAISE EXCEPTION 'memory candidate payload is immutable after creation';
            END IF;
            IF OLD.status = NEW.status THEN
                IF NEW IS DISTINCT FROM OLD THEN
                    RAISE EXCEPTION 'candidate review metadata requires a state transition';
                END IF;
                RETURN NEW;
            END IF;
            IF (OLD.status = 'pending' AND NEW.status IN
                    ('auto_approved','needs_confirmation','approved','rejected','expired'))
               OR (OLD.status = 'auto_approved' AND NEW.status IN ('approved','needs_confirmation'))
               OR (OLD.status = 'needs_confirmation' AND NEW.status IN ('approved','rejected','expired'))
               OR (OLD.status = 'pending_review' AND NEW.status IN ('processing','approved','rejected','expired'))
               OR (OLD.status = 'processing' AND NEW.status IN ('approved','rejected')) THEN
                RETURN NEW;
            END IF;
            RAISE EXCEPTION 'illegal memory candidate transition: % -> %', OLD.status, NEW.status;
        END
        $$
        """
    )
    op.execute(
        """
        CREATE FUNCTION meguri_enforce_version_aggregate()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF NEW.base_version_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM memory_versions
                WHERE version_id = NEW.base_version_id AND memory_id = NEW.memory_id
            ) THEN
                RAISE EXCEPTION 'memory version base belongs to another aggregate';
            END IF;
            IF NEW.supersedes_version_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM memory_versions
                WHERE version_id = NEW.supersedes_version_id AND memory_id = NEW.memory_id
            ) THEN
                RAISE EXCEPTION 'superseded version belongs to another aggregate';
            END IF;
            RETURN NEW;
        END
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_memory_versions_same_aggregate
        BEFORE INSERT ON memory_versions
        FOR EACH ROW EXECUTE FUNCTION meguri_enforce_version_aggregate()
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_memory_candidates_state
        BEFORE UPDATE OF status ON memory_candidates
        FOR EACH ROW EXECUTE FUNCTION meguri_enforce_candidate_transition()
        """
    )
    op.execute(
        """
        CREATE FUNCTION meguri_enforce_item_transition()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF OLD.status = NEW.status THEN RETURN NEW; END IF;
            IF (OLD.status = 'archived' AND NEW.status IN ('active','deleted'))
               OR (OLD.status = 'active' AND NEW.status IN
                    ('conflicted','superseded','expired','archived','deleted'))
               OR (OLD.status = 'conflicted' AND NEW.status IN ('active','deleted'))
               OR (OLD.status = 'expired' AND NEW.status IN ('active','deleted'))
               OR (OLD.status = 'deleted' AND NEW.status = 'active')
               OR (OLD.status = 'superseded' AND NEW.status = 'deleted') THEN
                RETURN NEW;
            END IF;
            RAISE EXCEPTION 'illegal memory item transition: % -> %', OLD.status, NEW.status;
        END
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_memory_items_state
        BEFORE UPDATE OF status ON memory_items
        FOR EACH ROW EXECUTE FUNCTION meguri_enforce_item_transition()
        """
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS trg_memory_versions_same_aggregate ON memory_versions"
    )
    op.execute("DROP FUNCTION IF EXISTS meguri_enforce_version_aggregate()")
    op.execute("DROP TRIGGER IF EXISTS trg_memory_items_state ON memory_items")
    op.execute("DROP FUNCTION IF EXISTS meguri_enforce_item_transition()")
    op.execute("DROP TRIGGER IF EXISTS trg_memory_candidates_state ON memory_candidates")
    op.execute("DROP FUNCTION IF EXISTS meguri_enforce_candidate_transition()")
    op.execute("DROP TRIGGER IF EXISTS trg_memory_versions_immutable ON memory_versions")
    op.execute("DROP FUNCTION IF EXISTS meguri_enforce_memory_version_transition()")
    op.execute(
        """
        CREATE FUNCTION meguri_reject_memory_version_update()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION 'memory_versions are immutable';
        END
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_memory_versions_immutable
        BEFORE UPDATE ON memory_versions
        FOR EACH ROW EXECUTE FUNCTION meguri_reject_memory_version_update()
        """
    )
    op.drop_constraint(
        "ck_memory_candidates_acceptance_consistent", "memory_candidates", type_="check"
    )
    op.drop_index("uq_memory_items_authoritative_canonical_key", table_name="memory_items")
    op.create_index(
        "uq_memory_items_active_canonical_key", "memory_items",
        ["tenant_id", "user_id", "memory_type", "canonical_key"], unique=True,
        postgresql_where=sa.text("status = 'active' AND canonical_key IS NOT NULL"),
    )
    op.drop_constraint("fk_memory_versions_base_version", "memory_versions", type_="foreignkey")
    op.drop_constraint("ck_memory_versions_valid_status", "memory_versions", type_="check")
    op.drop_column("memory_versions", "base_version_id")
    op.drop_column("memory_versions", "status")
    op.drop_constraint(
        "fk_memory_items_last_stable_version_same_item",
        "memory_items",
        type_="foreignkey",
    )
    op.drop_column("memory_items", "last_stable_version_id")
    op.drop_constraint("ck_memory_items_valid_status", "memory_items", type_="check")
    op.create_check_constraint(
        "ck_memory_items_valid_status", "memory_items",
        "status IN ('active','superseded','expired','archived','deleted')",
    )
    op.drop_constraint("ck_memory_candidates_valid_status", "memory_candidates", type_="check")
    op.create_check_constraint(
        "ck_memory_candidates_valid_status", "memory_candidates",
        "status IN ('pending_review','processing','approved','rejected','expired')",
    )
    op.drop_constraint("ck_memory_candidates_valid_merge_policy", "memory_candidates", type_="check")
    op.create_check_constraint(
        "ck_memory_candidates_valid_merge_policy", "memory_candidates",
        "merge_policy IN ('review','create_only','supersede')",
    )
    op.drop_constraint("ck_memory_candidates_valid_source_kind", "memory_candidates", type_="check")
    op.create_check_constraint(
        "ck_memory_candidates_valid_source_kind", "memory_candidates",
        "source_kind IN ('direct_user','llm_candidate','memoryos_import','mem0_shadow','admin')",
    )
