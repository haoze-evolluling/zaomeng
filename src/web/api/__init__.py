from __future__ import annotations

from typing import Any

__all__ = [
    "ROUTERS",
    "dialogue_router",
    "BranchDialogueSessionRequest",
    "opening_presets_router",
    "CreateDialogueSessionRequest",
    "CreateRunRequest",
    "DialogueResponseItem",
    "get_run_service",
    "IngestCharacterRequest",
    "IngestDialogueTurnRequest",
    "IngestRelationRequest",
    "PrepareDialogueTurnRequest",
    "RecommendSceneCardRequest",
    "RestartRunRequest",
    "runs_router",
    "scene_cards_router",
    "self_cards_router",
    "SaveModelSettingsRequest",
    "SaveOpeningPresetRequest",
    "SaveSceneCardRequest",
    "SavePersonaReviewRequest",
    "SaveSelfCardRequest",
    "settings_router",
    "SwitchDialogueSceneCardRequest",
]


def __getattr__(name: str) -> Any:
    if name == "get_run_service":
        from .deps import get_run_service

        return get_run_service
    if name in {
        "ROUTERS",
        "dialogue_router",
        "opening_presets_router",
        "runs_router",
        "scene_cards_router",
        "self_cards_router",
        "settings_router",
    }:
        from .routes import (
            ROUTERS,
            dialogue_router,
            opening_presets_router,
            runs_router,
            scene_cards_router,
            self_cards_router,
            settings_router,
        )

        mapping = {
            "ROUTERS": ROUTERS,
            "dialogue_router": dialogue_router,
            "opening_presets_router": opening_presets_router,
            "runs_router": runs_router,
            "scene_cards_router": scene_cards_router,
            "self_cards_router": self_cards_router,
            "settings_router": settings_router,
        }
        return mapping[name]
    if name in {
        "CreateDialogueSessionRequest",
        "BranchDialogueSessionRequest",
        "CreateRunRequest",
        "DialogueResponseItem",
        "IngestCharacterRequest",
        "IngestDialogueTurnRequest",
        "IngestRelationRequest",
        "PrepareDialogueTurnRequest",
        "RecommendSceneCardRequest",
        "RestartRunRequest",
        "SaveModelSettingsRequest",
        "SaveOpeningPresetRequest",
        "SaveSceneCardRequest",
        "SavePersonaReviewRequest",
        "SaveSelfCardRequest",
        "SwitchDialogueSceneCardRequest",
    }:
        from . import schemas as _schemas

        return getattr(_schemas, name)
    raise AttributeError(f"module 'src.web.api' has no attribute {name!r}")
