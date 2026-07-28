from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from src.web.api.compat import model_to_dict
from src.web.api.deps import get_run_service
from src.web.api.schemas import (
    BranchDialogueSessionRequest,
    BranchDialogueTurnRequest,
    CreateDialogueSessionRequest,
    DialogueAssociationsRequest,
    DialogueDirectorRequest,
    IngestDialogueTurnRequest,
    PrepareDialogueTurnRequest,
    SuggestDialogueTurnRequest,
    SwitchDialogueSceneCardRequest,
    UpdateDialogueBranchMetaRequest,
    UpdateDialogueRelationLockRequest,
    UpsertDialogueMemoryRequest,
)
from src.web.workflow import WebRunService

router = APIRouter()


@router.get("/api/web/runs/{run_id}/dialogue/sessions")
def list_dialogue_sessions(
    run_id: str, run_service: WebRunService = Depends(get_run_service)
) -> dict[str, Any]:
    try:
        return {"items": run_service.list_dialogue_sessions(run_id)}
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run not found.") from exc


@router.post("/api/web/runs/{run_id}/dialogue/sessions")
def create_dialogue_session(
    run_id: str,
    payload: CreateDialogueSessionRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.create_dialogue_session(
            run_id,
            mode=payload.mode,
            participants=payload.participants,
            controlled_character=payload.controlled_character,
            scene_card_id=payload.scene_card_id,
            scene_profile=payload.scene_profile,
            self_card_id=payload.self_card_id,
            self_profile=payload.self_profile,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/api/web/runs/{run_id}/dialogue/sessions/{session_id}")
def get_dialogue_session(
    run_id: str,
    session_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.get_dialogue_session(run_id, session_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc


@router.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/recover")
def recover_dialogue_session(
    run_id: str,
    session_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.recover_dialogue_session(run_id, session_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc


@router.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch")
def branch_dialogue_session(
    run_id: str,
    session_id: str,
    payload: BranchDialogueSessionRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.branch_dialogue_session_from_scene(
            run_id,
            session_id=session_id,
            scene_index=payload.scene_index,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch-turn"
)
def branch_dialogue_session_from_turn(
    run_id: str,
    session_id: str,
    payload: BranchDialogueTurnRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.branch_dialogue_session_from_turn(
            run_id,
            session_id=session_id,
            turn_id=payload.turn_id,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.patch(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch-meta"
)
def update_dialogue_branch_metadata(
    run_id: str,
    session_id: str,
    payload: UpdateDialogueBranchMetaRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.update_dialogue_branch_metadata(
            run_id,
            session_id=session_id,
            label=payload.label,
            is_mainline=payload.is_mainline,
            locked_event_ids=payload.locked_event_ids,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.put(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}/relation-lock"
)
def update_dialogue_relation_lock(
    run_id: str,
    session_id: str,
    payload: UpdateDialogueRelationLockRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.set_dialogue_relation_lock(
            run_id,
            session_id=session_id,
            pair_key=payload.pair_key,
            locked=payload.locked,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories")
def create_dialogue_memory(
    run_id: str,
    session_id: str,
    payload: UpsertDialogueMemoryRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.save_dialogue_memory(
            run_id,
            session_id=session_id,
            text=payload.text,
            category=payload.category,
            pinned=payload.pinned,
            enabled=payload.enabled,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.put(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories/{memory_id}"
)
def update_dialogue_memory(
    run_id: str,
    session_id: str,
    memory_id: str,
    payload: UpsertDialogueMemoryRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.save_dialogue_memory(
            run_id,
            session_id=session_id,
            memory_id=memory_id,
            text=payload.text,
            category=payload.category,
            pinned=payload.pinned,
            enabled=payload.enabled,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.delete(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories/{memory_id}"
)
def delete_dialogue_memory(
    run_id: str,
    session_id: str,
    memory_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.delete_dialogue_memory(
            run_id, session_id=session_id, memory_id=memory_id
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.delete("/api/web/runs/{run_id}/dialogue/sessions/{session_id}")
def delete_dialogue_session(
    run_id: str,
    session_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, str]:
    try:
        run_service.delete_dialogue_session(run_id, session_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    return {"status": "deleted"}


@router.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/prepare")
def prepare_dialogue_turn(
    run_id: str,
    session_id: str,
    payload: PrepareDialogueTurnRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.prepare_dialogue_turn(
            run_id,
            session_id=session_id,
            message=payload.message,
            message_kind=payload.message_kind,
            suppress_transcript_message=payload.suppress_transcript_message,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply")
def reply_dialogue_turn(
    run_id: str,
    session_id: str,
    payload: PrepareDialogueTurnRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.reply_dialogue_turn(
            run_id,
            session_id=session_id,
            message=payload.message,
            message_kind=payload.message_kind,
            suppress_transcript_message=payload.suppress_transcript_message,
            fast_response=True,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggest")
def suggest_dialogue_turn(
    run_id: str,
    session_id: str,
    payload: SuggestDialogueTurnRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, str]:
    try:
        return run_service.suggest_dialogue_turn(
            run_id,
            session_id=session_id,
            seed_text=payload.seed_text,
            direction=payload.direction,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}/correct-latest"
)
def correct_latest_dialogue_turn(
    run_id: str,
    session_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.correct_latest_dialogue_turn(
            run_id, session_id=session_id
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}/deep-review"
)
def deep_review_latest_dialogue_turn(
    run_id: str,
    session_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.deep_review_latest_dialogue_turn(
            run_id, session_id=session_id
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/associations")
def associate_dialogue_turn(
    run_id: str,
    session_id: str,
    payload: DialogueAssociationsRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.associate_dialogue_turn(
            run_id,
            session_id=session_id,
            option_count=payload.option_count,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/director-options")
def direct_dialogue_turn(
    run_id: str,
    session_id: str,
    payload: DialogueDirectorRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.direct_dialogue_turn(
            run_id,
            session_id=session_id,
            goal=payload.goal,
            action=payload.action,
            option_count=payload.option_count,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.put("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card")
def switch_dialogue_scene_card(
    run_id: str,
    session_id: str,
    payload: SwitchDialogueSceneCardRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.switch_dialogue_scene_card(
            run_id,
            session_id=session_id,
            scene_card_id=payload.scene_card_id,
            scene_profile=payload.scene_profile,
            transition_message=payload.transition_message,
            auto_continue=payload.auto_continue,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post(
    "/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card/recommend"
)
def recommend_dialogue_scene_card(
    run_id: str,
    session_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.recommend_dialogue_scene_card(run_id, session_id=session_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/ingest")
def ingest_dialogue_turn(
    run_id: str,
    session_id: str,
    payload: IngestDialogueTurnRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.ingest_dialogue_turn(
            run_id,
            session_id=session_id,
            responses=[model_to_dict(item) for item in payload.responses],
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
