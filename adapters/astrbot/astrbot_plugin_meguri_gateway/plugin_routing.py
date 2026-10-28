"""Read-only ownership checks for AstrBot's already filtered event handlers.

Never call a handler or re-evaluate its filters: commands can mutate parsed
parameters, and passive ALL-message listeners do not own ordinary chat.
"""

from __future__ import annotations

import json
import time
from typing import Any


OWN_PLUGIN = "astrbot_plugin_meguri_gateway"
OWNERSHIP_FILTERS = {"CommandFilter", "CommandGroupFilter", "RegexFilter"}


def activated_handlers(event: Any) -> list[Any] | None:
    getter = getattr(event, "get_extra", None)
    if not callable(getter):
        return None
    handlers = getter("activated_handlers")
    return handlers if isinstance(handlers, (list, tuple)) else None


def _plugin_instance(handler: Any) -> Any:
    # AstrBot metadata stores unbound handlers. Some hosts expose bound methods.
    instance = getattr(getattr(handler, "handler", None), "__self__", None)
    if instance is not None:
        return instance
    from astrbot.core.star.star import star_map

    metadata = star_map.get(handler.handler_module_path)
    return getattr(metadata, "star_cls", None)


def _share_cards(event: Any) -> list[dict]:
    cards = []
    for component in getattr(getattr(event, "message_obj", None), "message", []) or []:
        if getattr(component, "type", None) != "Json":
            continue
        data = getattr(component, "data", None)
        try:
            if isinstance(data, str):
                data = json.loads(data)
            if isinstance(data, dict) and isinstance(data.get("data"), str):
                data = json.loads(data["data"])
        except (ValueError, TypeError):
            continue
        if isinstance(data, dict):
            cards.append(data)
    return cards


def _media_parser_owns(instance: Any, event: Any, text: str) -> bool:
    whitelist = instance.whitelist
    if whitelist.get("enable"):
        allowed = event.get_sender_id() in whitelist.get("user", []) or (
            not event.is_private_chat()
            and event.get_group_id() in whitelist.get("group", [])
        )
        if not allowed:
            return False
    candidates = [text]
    for card in _share_cards(event):
        meta = card.get("meta", {})
        link = (meta.get("detail_1") or {}).get("qqdocurl") or (
            meta.get("news") or {}
        ).get("jumpUrl")
        if isinstance(link, str):
            candidates.append(link)
    return any(
        instance._should_parse(candidate)
        and bool(instance.parser_manager.extract_all_links(candidate))
        for candidate in candidates
    )


def _bilibili_card_owns(instance: Any, event: Any) -> bool:
    if not instance.enable_parse_miniapp:
        return False
    for card in _share_cards(event):
        meta = card.get("meta", {})
        detail = meta.get("detail_1") or {}
        news = meta.get("news") or {}
        if (detail.get("title") == "哔哩哔哩" and detail.get("qqdocurl")) or (
            news.get("tag") == "哔哩哔哩" and news.get("jumpUrl")
        ):
            return True
    return False


def _image_search_owns(instance: Any, event: Any, text: str) -> bool:
    # The installed searcher reads original Plain components, including '/'.
    raw = " ".join(
        str(getattr(component, "text", ""))
        for component in getattr(getattr(event, "message_obj", None), "message", []) or []
    ).strip() or text
    if any(raw.startswith(keyword) for keyword in instance.trigger_keywords if keyword):
        return True
    state = instance.user_states.get(event.get_sender_id())
    if not isinstance(state, dict):
        return False
    timeout = (
        min(instance.text_confirm_timeout, instance.search_params_timeout)
        if state.get("step") == "waiting_text_confirm"
        else instance.search_params_timeout
    )
    return time.time() - float(state.get("timestamp", 0)) <= timeout


def _meme_owns(instance: Any, text: str) -> bool:
    if not instance.meme_config.is_plugin_enabled():
        return False
    manager = instance.meme_manager.template_manager
    normalized = manager.normalize_trigger_message(text, instance.meme_config.trigger_prefix)
    words = normalized.split()
    return bool(words and words[0] in (getattr(manager, "_meme_keywords", None) or []))


def plugins_own_event(event: Any, handlers: list[Any]) -> bool:
    text = str(event.get_message_str() or "").strip()
    for handler in handlers:
        module = str(getattr(handler, "handler_module_path", ""))
        if OWN_PLUGIN in module:
            continue
        filters = getattr(handler, "event_filters", []) or []
        if any(type(f).__name__ in OWNERSHIP_FILTERS for f in filters):
            return True
        # Only inspect known ALL-message handlers on enabled, matched instances.
        name = str(getattr(handler, "handler_name", ""))
        try:
            if "astrbot_plugin_media_parser" in module and name == "auto_parse":
                if _media_parser_owns(_plugin_instance(handler), event, text):
                    return True
            elif "astrbot_plugin_bilibili" in module and name == "parse_miniapp":
                if _bilibili_card_owns(_plugin_instance(handler), event):
                    return True
            elif "astrbot_plugin_img_rev_searcher" in module and name == "on_message":
                if _image_search_owns(_plugin_instance(handler), event, text):
                    return True
            elif "astrbot_plugin_meme_generator" in module and name == "generate_meme":
                if _meme_owns(_plugin_instance(handler), text):
                    return True
        except Exception:
            # API/version drift must not break unrelated chat or load resources.
            continue
    return False
