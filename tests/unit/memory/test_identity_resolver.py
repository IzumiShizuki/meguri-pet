import pytest

from services.meguri_core.api_auth import ApiPrincipal
from services.meguri_core.memory_service.identity import IdentityResolver


class Lookup:
    def __init__(self):
        self.bindings = {
            ("meguri-dev", "website", "web-1"): "user-shared",
            ("meguri-dev", "astrbot", "qq-9"): "user-shared",
            ("meguri-dev", "airi", "desktop-3"): "user-shared",
        }

    async def resolve_identity(self, *, tenant_id, platform, platform_user_id):
        return self.bindings.get((tenant_id, platform, platform_user_id))


@pytest.mark.asyncio
async def test_verified_bindings_share_user_but_keep_client_sessions_isolated():
    resolver = IdentityResolver(Lookup(), isolation_salt="0123456789abcdef")
    website = await resolver.resolve(
        tenant_id="meguri-dev",
        platform="website",
        platform_user_id="web-1",
        client_id="website",
        session_id="web-session",
    )
    astrbot = await resolver.resolve(
        tenant_id="meguri-dev",
        platform="astrbot",
        platform_user_id="qq-9",
        client_id="astrbot",
        session_id="private-session",
    )
    airi = await resolver.resolve(
        tenant_id="meguri-dev",
        platform="airi",
        platform_user_id="desktop-3",
        client_id="desktop_pet",
        session_id="desktop-session",
    )
    assert {website.user_id, astrbot.user_id, airi.user_id} == {"user-shared"}
    assert {website.session_id, astrbot.session_id, airi.session_id} == {
        "web-session",
        "private-session",
        "desktop-session",
    }
    assert all(identity.formal_memory_allowed for identity in (website, astrbot, airi))
    principal = ApiPrincipal.from_resolved_identity(airi)
    assert principal.user_id == "user-shared"
    assert principal.client_id == "desktop_pet"
    assert principal.session_id == "desktop-session"
    assert principal.formal_memory_allowed is True


@pytest.mark.asyncio
async def test_unbound_and_cross_environment_identities_never_merge():
    resolver = IdentityResolver(Lookup(), isolation_salt="0123456789abcdef")
    dev = await resolver.resolve(
        tenant_id="meguri-dev",
        platform="astrbot",
        platform_user_id="same-display-name",
        client_id="astrbot",
        session_id="group-a",
    )
    production = await resolver.resolve(
        tenant_id="meguri-production",
        platform="astrbot",
        platform_user_id="same-display-name",
        client_id="astrbot",
        session_id="group-a",
    )
    website = await resolver.resolve(
        tenant_id="meguri-dev",
        platform="website",
        platform_user_id="same-display-name",
        client_id="website",
        session_id="group-a",
    )
    assert len({dev.user_id, production.user_id, website.user_id}) == 3
    assert not dev.verified_binding
    assert not dev.formal_memory_allowed


def test_identity_salt_must_not_be_weak():
    with pytest.raises(ValueError):
        IdentityResolver(Lookup(), isolation_salt="short")
