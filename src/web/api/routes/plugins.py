from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from src.plugin_system import PluginError
from src.web.api.deps import get_run_service
from src.web.workflow import WebRunService


router = APIRouter()


@router.get("/api/web/plugins")
def list_plugins(
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    return {"items": run_service.list_plugins()}


@router.post("/api/web/plugins/refresh")
def refresh_plugins(
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    return {"items": run_service.refresh_plugins()}


@router.post("/api/web/plugins/{plugin_id}/enable")
def enable_plugin(
    plugin_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.enable_plugin(plugin_id)
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/plugins/{plugin_id}/disable")
def disable_plugin(
    plugin_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.disable_plugin(plugin_id)
    except PluginError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
