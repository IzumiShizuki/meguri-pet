from __future__ import annotations

import os
from pathlib import Path

from training.llm.gateway.app import create_app, settings_from_env
from training.llm.gateway.manager import RegistryModelManager


def create_gateway():
    registry = Path(os.environ.get("MEGURI_LLM_MODEL_REGISTRY", "training/llm/registry/model_registry.json"))
    routing = Path(os.environ.get("MEGURI_LLM_ROUTING_STATE", "training/llm/gateway/routing_state.json"))
    return create_app(RegistryModelManager(registry, routing), settings_from_env())
