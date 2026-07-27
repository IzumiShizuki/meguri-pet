from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from services.meguri_core.runtime import RuntimeStateMachine
from services.meguri_core.schemas import RuntimeOverride, TurnRequest


def test_expired_user_override_falls_back_to_active_client_override() -> None:
    now = datetime.now(ZoneInfo("Asia/Shanghai"))
    machine = RuntimeStateMachine()
    machine.set_override(
        "user-a:website",
        RuntimeOverride(outfit_code="05", expires_at=now + timedelta(minutes=10)),
    )
    machine.set_override(
        "user-a",
        RuntimeOverride(outfit_code="06", expires_at=now - timedelta(minutes=10)),
    )

    state = machine.state_for(
        TurnRequest(
            user_id="user-a",
            client_id="website",
            session_id="session-a",
            message="hello",
        )
    )

    assert state.outfit_code == "05"
    assert "user-a" not in machine.overrides


def test_temporal_mode_never_changes_relationship() -> None:
    zone = ZoneInfo("Asia/Shanghai")
    daytime = RuntimeStateMachine(
        now=lambda: datetime(2026, 7, 21, 12, 0, tzinfo=zone)
    )
    nighttime = RuntimeStateMachine(
        now=lambda: datetime(2026, 7, 21, 23, 0, tzinfo=zone)
    )
    request = TurnRequest(
        user_id="user-a",
        client_id="website",
        session_id="session-a",
        message="hello",
    )

    assert daytime.state_for(request).relationship_profile == "sibling"
    assert nighttime.state_for(request).relationship_profile == "sibling"


def test_only_user_scoped_server_override_can_change_shared_relationship() -> None:
    now = datetime(2026, 7, 21, 12, 0, tzinfo=ZoneInfo("Asia/Shanghai"))
    machine = RuntimeStateMachine(now=lambda: now)
    machine.set_override(
        "user-a:website",
        RuntimeOverride(relationship_profile="lover", outfit_code="05"),
    )
    forged_request = TurnRequest(
        user_id="user-a",
        client_id="website",
        session_id="session-web",
        message="hello",
        relationship_profile="lover",
    )

    untrusted = machine.state_for(forged_request)
    assert untrusted.relationship_profile == "sibling"
    assert untrusted.outfit_code == "05"

    machine.set_override(
        "user-a",
        RuntimeOverride(relationship_profile="pursuit"),
    )

    assert machine.state_for(forged_request).relationship_profile == "pursuit"
    assert machine.state_for(
        TurnRequest(
            user_id="user-a",
            client_id="airi",
            session_id="session-airi",
            message="hello",
        )
    ).relationship_profile == "pursuit"
