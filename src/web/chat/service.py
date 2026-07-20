from __future__ import annotations

import random
import shutil
from pathlib import Path
from typing import Any, Callable
from uuid import uuid4

from src.core.config import Config
from src.core.path_provider import PathProvider
from src.core.session_store import MarkdownSessionStore
from src.web.manifest.compat import (
    normalized_parts,
    relative_candidates,
    relative_to_run_dir,
)
import src.web.chat.event_signals as _event_signals
from src.web.chat.cache_stats import (
    empty_generation_cache_stats,
    record_generation_cache_observation,
)
from src.web.chat.io_utils import read_json, write_json
import src.web.chat.memory_summary as _memory_summary
import src.web.chat.persona_context as _persona_context
import src.web.chat.relation_excerpt as _relation_excerpt
import src.web.chat.prompt_rules as _prompt_rules
import src.web.chat.relation_state as _relation_state
import src.web.chat.runtime_overview as _runtime_overview
import src.web.chat.scene_progress as _scene_progress
import src.web.chat.scene_signals as _scene_signals
from src.web.chat.session_storage import SessionFileStore, with_session_lock
import src.web.chat.session_views as _session_views
import src.web.chat.state_utils as _state_utils
import src.web.chat.text_utils as _text_utils
import src.web.chat.turn_memory as _turn_memory
from src.web.artifacts.ingest import load_relations_source
from src.web.time_utils import utc_now as _utc_now


class DialogueService:
    SESSION_STATE_VERSION = 1

    def __init__(
        self,
        runs_root: str | Path,
        *,
        memory_store_resolver: Callable[[str], MarkdownSessionStore] | None = None,
    ) -> None:
        self._session_files = SessionFileStore(runs_root)
        self.runs_root = self._session_files.runs_root
        self._memory_store_resolver = memory_store_resolver
        self._memory_stores: dict[str, MarkdownSessionStore] = {}

    @classmethod
    def _empty_session_state(cls) -> dict[str, Any]:
        return _state_utils.empty_session_state(cls.SESSION_STATE_VERSION)

    def _ensure_session_state(self, session: dict[str, Any]) -> dict[str, Any]:
        return _state_utils.ensure_session_state(
            session, version=self.SESSION_STATE_VERSION
        )

    def _session_scene_progress(self, session: dict[str, Any]) -> dict[str, Any]:
        state = self._ensure_session_state(session)
        return _state_utils.session_scene_progress(state)

    def _set_session_scene_progress(
        self, session: dict[str, Any], scene_progress: dict[str, Any] | None
    ) -> None:
        state = self._ensure_session_state(session)
        payload = dict(scene_progress or {})
        updated_at = str(payload.get("updated_at", "")).strip() or _utc_now()
        _state_utils.set_session_scene_progress(state, payload, updated_at=updated_at)
        self._sync_character_runtime_cards(session, payload, updated_at=updated_at)

    def _session_relation_matrix(self, session: dict[str, Any]) -> dict[str, Any]:
        state = self._ensure_session_state(session)
        return _state_utils.relation_matrix(state)

    def _set_session_relation_matrix(
        self, session: dict[str, Any], payload: dict[str, Any] | None
    ) -> None:
        state = self._ensure_session_state(session)
        _state_utils.set_relation_matrix(state, payload)

    def _session_relation_delta(self, session: dict[str, Any]) -> dict[str, Any]:
        state = self._ensure_session_state(session)
        return _state_utils.relation_delta(state)

    def _set_session_relation_delta(
        self, session: dict[str, Any], payload: dict[str, Any] | None
    ) -> None:
        state = self._ensure_session_state(session)
        _state_utils.set_relation_delta(state, payload)

    def _session_character_snapshots(self, session: dict[str, Any]) -> dict[str, Any]:
        state = self._ensure_session_state(session)
        return _state_utils.character_snapshots(state)

    def _set_session_character_snapshots(
        self, session: dict[str, Any], payload: dict[str, Any] | None
    ) -> None:
        state = self._ensure_session_state(session)
        _state_utils.set_character_snapshots(state, payload)

    def _sync_character_runtime_cards(
        self,
        session: dict[str, Any],
        scene_progress: dict[str, Any] | None,
        *,
        updated_at: str,
    ) -> None:
        state = self._ensure_session_state(session)
        snapshots = dict(state.get("characters", {}).get("snapshots", {}) or {})
        progress = dict(scene_progress or {})
        participants = [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ]
        present = {
            str(item).strip()
            for item in list(progress.get("present_participants", []) or [])
            if str(item).strip()
        }
        location = str(progress.get("location", "")).strip()
        time_hint = str(progress.get("time_hint", "")).strip()
        for name in participants:
            current = dict(snapshots.get(name, {}) or {})
            current["present_state"] = "onstage" if name in present else "offstage"
            if location:
                current["scene_location"] = location
            if time_hint:
                current["time_hint"] = time_hint
            current["updated_at"] = updated_at
            snapshots[name] = current
        state.setdefault("characters", {})["snapshots"] = snapshots

    def _session_event_signals(self, session: dict[str, Any]) -> dict[str, Any]:
        state = self._ensure_session_state(session)
        return _state_utils.event_signals(state)

    def _set_session_event_signals(
        self, session: dict[str, Any], payload: dict[str, Any] | None
    ) -> None:
        state = self._ensure_session_state(session)
        _state_utils.set_event_signals(state, payload)

    def _session_memory_summary_state(self, session: dict[str, Any]) -> dict[str, Any]:
        state = self._ensure_session_state(session)
        return _state_utils.memory_summary(state)

    def _set_session_memory_summary_state(
        self, session: dict[str, Any], payload: dict[str, Any] | None
    ) -> None:
        state = self._ensure_session_state(session)
        _state_utils.set_memory_summary(state, payload)

    def list_sessions(self, run_id: str) -> list[dict[str, Any]]:
        root = self._sessions_root(run_id)
        items: list[dict[str, Any]] = []
        if not root.exists():
            return items
        for path in sorted(root.glob("*/session.json"), reverse=True):
            payload = self._read_json(path)
            items.append(self._serialize_session(run_id, payload))
        items.sort(key=lambda item: item.get("updated_at", ""), reverse=True)
        return items

    def create_session(
        self,
        run_manifest: dict[str, Any],
        *,
        mode: str,
        participants: list[str],
        controlled_character: str = "",
        scene_profile: dict[str, str] | None = None,
        self_profile: dict[str, str] | None = None,
        carried_memory_summary: dict[str, str] | None = None,
        branch_origin: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        novel_id = str(run_manifest.get("novel_id", "")).strip()
        available = self._character_index(run_manifest)
        available_names = [item["name"] for item in available]
        selected = [name for name in participants if name in available_names]
        if not selected:
            selected = available_names
        if not selected:
            raise ValueError("No persona bundles available for dialogue.")
        if mode not in {"act", "insert", "observe"}:
            raise ValueError("Unsupported dialogue mode.")
        if mode == "act" and controlled_character not in selected:
            raise ValueError(
                "Controlled character must be one of the selected participants."
            )

        session_id = f"dlg-{uuid4().hex[:10]}"
        root = self._session_dir(run_id, session_id)
        root.mkdir(parents=True, exist_ok=True)
        payload = {
            "kind": "zaomeng_dialogue_session",
            "session_id": session_id,
            "run_id": run_id,
            "novel_id": novel_id,
            "mode": mode,
            "participants": selected,
            "controlled_character": controlled_character if mode == "act" else "",
            "scene_card": dict(scene_profile or {}),
            "scene_card_id": str(
                (scene_profile or {}).get("scene_card_id", "")
            ).strip(),
            "scene_history": [],
            "self_insert": dict(self_profile or {}) if mode == "insert" else {},
            "self_card_id": (
                str((self_profile or {}).get("self_card_id", "")).strip()
                if mode == "insert"
                else ""
            ),
            "carried_memory_summary": dict(carried_memory_summary or {}),
            "branch_origin": dict(branch_origin or {}),
            "history": [],
            "pending_turn": {},
            "generation_cache_stats": empty_generation_cache_stats(),
            "state": self._empty_session_state(),
            "created_at": _utc_now(),
            "updated_at": _utc_now(),
            "status": "ready",
        }
        self._set_session_relation_matrix(
            payload, self._seed_relation_matrix(run_manifest, selected)
        )
        if dict(scene_profile or {}):
            initial_summary = self._build_session_memory_summary(run_id, payload, [])
            payload["scene_history"] = [
                self._build_scene_history_entry(
                    scene_profile or {},
                    transition_message="",
                    memory_summary=initial_summary,
                )
            ]
        self._set_session_scene_progress(
            payload, self._derive_scene_progress_state(payload, [])
        )
        self._write_json(root / "session.json", payload)
        if carried_memory_summary:
            session_store = self._resolve_memory_store(run_id)
            if session_store is not None:
                session_store.append_long_term_memory(
                    session_id,
                    _memory_summary.branch_memory_seed_text(carried_memory_summary),
                    metadata={
                        "run_id": run_id,
                        "kind": "branch_summary",
                        "speaker": "分支摘要",
                        "target": "",
                        "ts": _utc_now(),
                    },
                )
        return self._serialize_session(run_id, payload)

    @with_session_lock
    def get_session(self, run_id: str, session_id: str) -> dict[str, Any]:
        payload = self._read_json(self._session_file(run_id, session_id))
        return self._serialize_session(run_id, payload)

    @with_session_lock
    def delete_session(self, run_id: str, session_id: str) -> None:
        session_dir = self._session_dir(run_id, session_id)
        if not session_dir.exists():
            raise FileNotFoundError(str(session_dir))
        shutil.rmtree(session_dir)

    @with_session_lock
    def update_scene_card(
        self,
        run_id: str,
        session_id: str,
        *,
        scene_profile: dict[str, str] | None = None,
        transition_message: str = "",
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        if session.get("pending_turn"):
            raise ValueError("当前还有一轮待收口，请先等这拍结束再转场。")
        normalized_scene = dict(scene_profile or {})
        session["scene_card"] = normalized_scene
        session["scene_card_id"] = str(
            normalized_scene.get("scene_card_id", "")
        ).strip()
        scene_note = self._build_scene_switch_note(normalized_scene, transition_message)
        if scene_note:
            session.setdefault("history", []).append(
                {
                    "speaker": "场景提示",
                    "message": scene_note,
                    "target": "",
                    "ts": _utc_now(),
                }
            )
        self._set_session_scene_progress(
            session,
            self._derive_scene_progress_state(
                session, self._serialize_transcript(session)
            ),
        )
        transcript = self._serialize_transcript(session)
        memory_summary = self._build_session_memory_summary(run_id, session, transcript)
        scene_history = list(session.get("scene_history", []) or [])
        scene_history.append(
            self._build_scene_history_entry(
                normalized_scene,
                transition_message=transition_message,
                memory_summary=memory_summary,
            )
        )
        session["scene_history"] = scene_history
        session["updated_at"] = _utc_now()
        session["status"] = "ready"
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def update_scene_progress_state(
        self,
        run_id: str,
        session_id: str,
        scene_progress: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        self._set_session_scene_progress(
            session,
            self._merge_scene_progress_state(
                session,
                dict(scene_progress or {}),
            ),
        )
        session["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def branch_session_from_scene(
        self,
        run_manifest: dict[str, Any],
        session_id: str,
        *,
        scene_index: int,
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        session = self._read_json(self._session_file(run_id, session_id))
        scene_history = list(session.get("scene_history", []) or [])
        if scene_index < 0 or scene_index >= len(scene_history):
            raise ValueError("指定的场景时间线节点不存在。")
        target = dict(scene_history[scene_index] or {})
        scene_profile = dict(target.get("scene_card", {}) or {})
        if not scene_profile:
            scene_profile = {
                "scene_card_id": str(target.get("scene_card_id", "")).strip(),
                "title": str(target.get("title", "")).strip(),
                "location": str(target.get("location", "")).strip(),
                "atmosphere": str(target.get("atmosphere", "")).strip(),
            }
        memory_summary = dict(target.get("memory_summary", {}) or {})
        return self.create_session(
            run_manifest,
            mode=str(session.get("mode", "observe")).strip() or "observe",
            participants=list(session.get("participants", []) or []),
            controlled_character=str(session.get("controlled_character", "")).strip(),
            scene_profile=scene_profile,
            self_profile=dict(session.get("self_insert", {}) or {}),
            carried_memory_summary=memory_summary,
            branch_origin={
                "session_id": str(session.get("session_id", "")).strip(),
                "scene_index": scene_index,
                "scene_title": str(target.get("title", "")).strip(),
            },
        )

    @with_session_lock
    def prepare_turn(
        self,
        run_manifest: dict[str, Any],
        *,
        session_id: str,
        message: str,
        message_kind: str = "dialogue",
        speaker_override: str = "",
        transcript_message: str | None = None,
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        session = self._read_json(self._session_file(run_id, session_id))
        if session.get("pending_turn"):
            raise ValueError("当前已有一轮等待回复，请勿重复提交。")
        normalized_message_kind = self._normalize_message_kind(message_kind)
        effective_speaker_override = str(speaker_override or "").strip()
        if normalized_message_kind == "narration" and not effective_speaker_override:
            effective_speaker_override = "场景提示"
        turn_id = f"turn-{uuid4().hex[:8]}"
        payload = self._build_turn_payload(
            run_manifest,
            session,
            turn_id=turn_id,
            message=message,
            speaker_override=effective_speaker_override,
            message_kind=normalized_message_kind,
        )
        turn_dir = self._session_dir(run_id, session_id) / "turns"
        turn_dir.mkdir(parents=True, exist_ok=True)
        turn_payload_path = turn_dir / f"{turn_id}.payload.json"
        self._write_json(turn_payload_path, payload)
        session["pending_turn"] = {
            "turn_id": turn_id,
            "user_message": message,
            "transcript_message": (
                message if transcript_message is None else transcript_message
            ),
            "message_kind": normalized_message_kind,
            "speaker": payload["input"]["speaker"],
            "mode": payload["mode"],
            "participants": list(payload["input"]["participants"]),
            "active_participants": list(
                payload["input"].get("active_participants", [])
            ),
            "response_limit_hint": payload["host_action"]["response_limit_hint"],
            "payload_path": str(turn_payload_path.resolve()),
            "created_at": _utc_now(),
        }
        session["updated_at"] = _utc_now()
        session["status"] = "waiting_for_host_reply"
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def build_suggestion_payload(
        self,
        run_manifest: dict[str, Any],
        *,
        session_id: str,
        seed_text: str = "",
        direction: str = "",
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        session = self._read_json(self._session_file(run_id, session_id))
        payload = self._build_turn_payload(
            run_manifest,
            session,
            turn_id=f"suggest-{uuid4().hex[:8]}",
            message=seed_text,
        )
        mode = str(payload.get("mode", "observe")).strip() or "observe"
        speaker = str(payload.get("input", {}).get("speaker", "")).strip()
        participants = list(payload.get("input", {}).get("participants", []))
        payload["kind"] = "zaomeng_dialogue_suggestion"
        selected_direction = str(direction or "").strip()
        if selected_direction:
            payload["selected_direction"] = selected_direction
        scene_progress = dict(payload.get("scene_progress", {}) or {})
        session_summary = dict(
            dict(payload.get("memory_context", {}) or {}).get("session_summary", {})
            or {}
        )
        payload["user_persona"] = self._build_user_suggestion_persona(
            mode,
            session,
            payload.get("persona_contexts", []),
            scene_progress=scene_progress,
            session_summary=session_summary,
        )
        payload["instructions"] = {
            "mode": mode,
            "generation_goal": (
                "Draft one complete, natural, directly sendable next user message that fits the current scene, "
                "relationships, and persona voices. Use one to three sentences when the selected direction needs room to land."
            ),
            "mode_rule": self._suggestion_mode_rule(mode),
            "speaker_rule": self._speaker_rule(mode, session),
            "response_style": self._suggestion_style_rule(mode),
        }
        payload["host_action"] = {
            "expected_output": {"suggestion": "一段完整、可直接发送的文案"},
            "output_rule": "Keep it complete, in-scene, directly sendable, and never explanatory.",
        }
        payload["host_prompt_brief"] = self._host_suggestion_prompt_brief(
            mode,
            speaker,
            participants,
            scene_progress=scene_progress,
        )
        payload["updated_at"] = _utc_now()
        return payload

    def build_association_payload(
        self,
        run_manifest: dict[str, Any],
        *,
        session_id: str,
        option_count: int = 3,
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        session = self._read_json(self._session_file(run_id, session_id))
        payload = self.build_suggestion_payload(
            run_manifest,
            session_id=session_id,
        )
        count = max(2, min(int(option_count or 3), 4))
        payload["kind"] = "zaomeng_dialogue_associations"
        payload["latest_exchange"] = self._build_latest_exchange(session)
        payload["instructions"] = {
            "generation_goal": (
                "Propose distinct user-facing next directions that continue directly from "
                "the completed latest exchange. Treat older scene and relationship context "
                "as background only."
            ),
            "option_count": count,
        }
        payload["host_action"] = {
            "expected_output": {
                "options": [
                    {
                        "label": "4-10字的推进选项",
                        "direction": "供下一步代写使用的明确剧情方向",
                        "anchor_speaker": "该方向所依据的最新回复角色",
                        "anchor_quote": "从该角色最新回复中原样摘录的4-20字",
                    }
                ],
            },
            "output_rule": (
                "Return exactly the requested number of options as JSON. "
                "Every option must cite an exact anchor from the latest replies. "
                "Never present a completed event as a future direction or invent a new fact."
            ),
        }
        payload["updated_at"] = _utc_now()
        return payload

    def _build_latest_exchange(self, session: dict[str, Any]) -> dict[str, Any]:
        transcript = [
            item
            for item in self._serialize_transcript(session)
            if str(item.get("message", "")).strip()
        ]
        mode = str(session.get("mode", "observe")).strip() or "observe"
        anchor_roles = {"user"} if mode in {"act", "insert"} else {"director"}
        anchor_index = -1
        for index in range(len(transcript) - 1, -1, -1):
            if str(transcript[index].get("role", "")).strip() in anchor_roles:
                anchor_index = index
                break

        if anchor_index >= 0:
            exchange = transcript[anchor_index:]
            user_turn = dict(exchange[0])
            replies = [dict(item) for item in exchange[1:]]
        else:
            user_turn = {}
            replies = [dict(item) for item in transcript[-4:]]
        if len(replies) > 6:
            replies = replies[-6:]

        scene_progress = self._session_scene_progress(session)
        participants = [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ]
        present = [
            str(item).strip()
            for item in list(scene_progress.get("present_participants", []) or [])
            if str(item).strip()
        ] or participants
        offstage = [
            str(item).strip()
            for item in list(scene_progress.get("offstage_participants", []) or [])
            if str(item).strip()
        ]
        return {
            "status": "completed",
            "user_turn": user_turn,
            "replies": replies,
            "latest_reply": dict(replies[-1]) if replies else {},
            "speakers_who_just_replied": [
                str(item.get("speaker", "")).strip()
                for item in replies
                if str(item.get("speaker", "")).strip()
            ],
            "present_participants": present,
            "offstage_participants": offstage,
        }

    @with_session_lock
    def ingest_turn_responses(
        self,
        run_id: str,
        *,
        session_id: str,
        responses: list[dict[str, str]],
        remember_turn_memory: bool = False,
        generation_cache: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        pending = dict(session.get("pending_turn", {}) or {})
        if not pending:
            raise ValueError("No pending turn to ingest.")
        session_store = (
            self._resolve_memory_store(run_id) if remember_turn_memory else None
        )
        clean_responses = []
        for item in responses:
            speaker = str(item.get("speaker", "")).strip()
            message = str(item.get("message", "")).strip()
            if not speaker or not message:
                continue
            clean_responses.append(
                {"speaker": speaker, "message": message, "ts": _utc_now()}
            )
        if not clean_responses:
            raise ValueError("No valid responses provided.")
        transcript_message = str(
            pending.get("transcript_message", pending.get("user_message", ""))
        ).strip()
        if transcript_message:
            user_entry = {
                "speaker": pending.get("speaker", "User"),
                "message": transcript_message,
                "target": "",
                "ts": pending.get("created_at", _utc_now()),
            }
            if session_store is not None:
                session_store.append_long_term_memory(
                    session_id,
                    self._entry_to_memory_text(user_entry),
                    metadata={
                        "run_id": run_id,
                        "kind": self._normalize_message_kind(
                            str(pending.get("message_kind", "")).strip()
                        ),
                        "speaker": str(user_entry.get("speaker", "")).strip(),
                        "target": "",
                        "ts": user_entry.get("ts", ""),
                    },
                )
                user_entry["memory_archived"] = True
            session.setdefault("history", []).append(user_entry)
        remembered_responses = []
        pending_speaker = str(pending.get("speaker", "")).strip()
        active_participants = [
            str(item).strip()
            for item in pending.get("active_participants", [])
            if str(item).strip()
        ]
        session["history"].extend(clean_responses)
        for item in clean_responses:
            response_entry = item
            if session_store is not None:
                target = (
                    pending_speaker
                    if pending_speaker not in {"", "User", "场景提示", "旁白"}
                    else ""
                )
                if not target:
                    pool = [
                        name
                        for name in active_participants
                        if name
                        and name != str(response_entry.get("speaker", "")).strip()
                    ]
                    target = pool[0] if pool else ""
                session_store.append_long_term_memory(
                    session_id,
                    self._entry_to_memory_text(response_entry),
                    metadata={
                        "run_id": run_id,
                        "kind": "dialogue",
                        "speaker": str(response_entry.get("speaker", "")).strip(),
                        "target": target,
                        "ts": response_entry.get("ts", ""),
                    },
                )
                response_entry["memory_archived"] = True
            remembered_responses.append(response_entry)
        if remembered_responses:
            session["history"][-len(remembered_responses) :] = remembered_responses
        session["pending_turn"] = {}
        completed_at = _utc_now()
        session["updated_at"] = completed_at
        session["status"] = "ready"
        if generation_cache is not None:
            record_generation_cache_observation(
                session,
                generation_cache,
                turn_id=str(pending.get("turn_id", "")).strip(),
                updated_at=completed_at,
            )
        if session_store is not None:
            session_store.compress_context(session)
        result_path = (
            self._session_dir(run_id, session_id)
            / "turns"
            / f"{pending.get('turn_id', 'turn')}.result.json"
        )
        result_payload = {
            "kind": "zaomeng_dialogue_result",
            "session_id": session_id,
            "turn_id": pending.get("turn_id", ""),
            "responses": clean_responses,
            "updated_at": completed_at,
        }
        if generation_cache is not None:
            result_payload["generation_cache"] = dict(
                session.get("generation_cache_stats", {}).get("latest", {}) or {}
            )
        self._write_json(result_path, result_payload)
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    def _build_turn_payload(
        self,
        run_manifest: dict[str, Any],
        session: dict[str, Any],
        *,
        turn_id: str,
        message: str,
        message_kind: str = "dialogue",
        speaker_override: str = "",
    ) -> dict[str, Any]:
        participants = list(session.get("participants", []))
        mode = str(session.get("mode", "observe")).strip() or "observe"
        normalized_message_kind = self._normalize_message_kind(message_kind)
        speaker = str(speaker_override or "").strip() or (
            session.get("controlled_character", "")
            if mode == "act"
            else (
                session.get("self_insert", {}).get("display_name", "你")
                if mode == "insert"
                else "User"
            )
        )
        character_index = self._character_index(run_manifest)
        persona_map = {item["name"]: item for item in character_index}
        relation_graph = dict(
            run_manifest.get("artifact_index", {}).get("relation_graph", {}) or {}
        )
        full_history = list(session.get("history", []))
        scene_progress = self._session_scene_progress(session)
        character_snapshots = self._session_character_snapshots(session)
        active_participants = self._resolve_active_participants(
            participants, full_history, mode, speaker, scene_progress
        )
        scene_card = dict(session.get("scene_card", {}) or {})
        transcript = self._serialize_transcript(session)

        persona_contexts = self._build_persona_contexts(
            participants=participants,
            active_participants=active_participants,
            persona_map=persona_map,
            mode=mode,
            controlled_character=str(session.get("controlled_character", "")).strip(),
            character_snapshots=character_snapshots,
        )

        latest_history = full_history[-8:]
        relation_excerpt = self._build_relation_excerpt(
            relation_graph.get("relations_file", ""),
            participants=participants,
            active_participants=active_participants,
            message=message,
            scene_card=scene_card,
        )
        session_relation_excerpt = self._build_session_relation_excerpt(
            session,
            participants=participants,
            active_participants=active_participants,
        )
        if session_relation_excerpt:
            relation_excerpt = (
                f"{relation_excerpt}\n\n# SESSION_RELATION_STATE\n{session_relation_excerpt}".strip()
                if relation_excerpt
                else f"# SESSION_RELATION_STATE\n{session_relation_excerpt}"
            )
        memory_context = self._build_turn_memory_context(
            run_id=str(run_manifest.get("run_id", "")).strip(),
            session=session,
            transcript=transcript,
            speaker=speaker,
            message=message,
            participants=participants,
            active_participants=active_participants,
            scene_card=scene_card,
            scene_progress=scene_progress,
        )
        controlled_character_name = str(session.get("controlled_character", "")).strip()
        response_limit_hint = self._choose_response_limit_hint(
            mode=mode,
            active_count=len(active_participants),
            turn_id=turn_id,
            message_kind=normalized_message_kind,
        )
        response_count_rule = (
            f"Return 1-{response_limit_hint} in-world replies. "
            "Let only characters who are currently present respond; do not force every participant to speak each turn."
        )
        if normalized_message_kind == "narration" and mode == "act" and controlled_character_name:
            response_lower_bound = min(response_limit_hint, max(1, min(2, len(active_participants))))
            response_count_rule = (
                f"Return {response_lower_bound}-{response_limit_hint} in-world replies "
                f"when multiple cast members are present. Other participants besides {controlled_character_name} must speak; "
                "do not return only the controlled character's line."
            )
        instructions = {
            "mode": mode,
            "generation_goal": "Keep every reply faithful to the persona bundle, relationship context, and scene mode.",
            "mode_rule": self._mode_rule(mode, normalized_message_kind, controlled_character_name),
            "speaker_rule": self._speaker_rule(mode, session, normalized_message_kind),
            "response_style": self._response_style_rule(
                mode,
                normalized_message_kind,
                controlled_character_name,
            ),
            "scene_rule": self._scene_rule(scene_card),
            "progression_rule": self._scene_progress_rule(scene_progress),
            "response_count_rule": response_count_rule,
        }
        responder_hints = self._responder_hints(
            mode,
            active_participants,
            speaker,
            normalized_message_kind,
            controlled_character_name,
        )

        return {
            "kind": "zaomeng_dialogue_turn",
            "run_id": run_manifest.get("run_id", ""),
            "session_id": session.get("session_id", ""),
            "turn_id": turn_id,
            "novel_id": run_manifest.get("novel_id", ""),
            "mode": mode,
            "input": {
                "speaker": speaker,
                "message": message,
                "message_kind": normalized_message_kind,
                "participants": participants,
                "active_participants": active_participants,
                "controlled_character": session.get("controlled_character", ""),
                "scene_card": scene_card,
                "scene_progress": scene_progress,
                "character_snapshots": character_snapshots,
                "self_insert": dict(session.get("self_insert", {})),
            },
            "history": latest_history,
            "scene_card": scene_card,
            "memory_context": memory_context,
            "scene_progress": scene_progress,
            "persona_contexts": persona_contexts,
            "relation_context": {
                "graph": relation_graph,
                "relations_excerpt": relation_excerpt,
            },
            "instructions": instructions,
            "responder_hints": responder_hints,
            "host_action": {
                "expected_output": [{"speaker": "CharacterName", "message": "..."}],
                "response_limit_hint": response_limit_hint,
                "output_rule": (
                    "Return only in-world character replies. Do not explain the workflow or mention prompts. "
                    "Do not split obvious small actions into standalone narration; keep them inside the speaking character's line with brief parenthetical action."
                ),
            },
            "host_prompt_brief": self._host_prompt_brief(
                mode,
                speaker,
                participants,
                normalized_message_kind,
                controlled_character_name,
            ),
            "updated_at": _utc_now(),
        }

    _mode_rule = staticmethod(_prompt_rules._mode_rule)
    _speaker_rule = staticmethod(_prompt_rules._speaker_rule)
    _response_style_rule = staticmethod(_prompt_rules._response_style_rule)
    _scene_rule = staticmethod(_prompt_rules._scene_rule)
    _scene_progress_rule = staticmethod(_prompt_rules._scene_progress_rule)
    _suggestion_mode_rule = staticmethod(_prompt_rules._suggestion_mode_rule)
    _suggestion_style_rule = staticmethod(_prompt_rules._suggestion_style_rule)
    _build_user_suggestion_persona = staticmethod(
        _prompt_rules._build_user_suggestion_persona
    )
    _responder_hints = staticmethod(_prompt_rules._responder_hints)
    _host_prompt_brief = staticmethod(_prompt_rules._host_prompt_brief)
    _host_suggestion_prompt_brief = staticmethod(
        _prompt_rules._host_suggestion_prompt_brief
    )
    _normalize_message_kind = staticmethod(_prompt_rules._normalize_message_kind)

    @classmethod
    def _resolve_active_participants(
        cls,
        participants: list[str],
        history: list[dict[str, Any]],
        mode: str,
        speaker: str,
        scene_progress: dict[str, Any] | None = None,
    ) -> list[str]:
        deduped: list[str] = []
        seen: set[str] = set()
        for name in participants:
            normalized = str(name or "").strip()
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            deduped.append(normalized)
        if not deduped:
            return []

        state_present = [
            str(item).strip()
            for item in list(
                dict(scene_progress or {}).get("present_participants", []) or []
            )
            if str(item).strip() in deduped
        ]
        state_offstage = {
            str(item).strip()
            for item in list(
                dict(scene_progress or {}).get("offstage_participants", []) or []
            )
            if str(item).strip() in deduped
        }
        departed = _scene_signals.infer_departed_participants(deduped, history)
        if state_present:
            active = [
                name
                for name in state_present
                if name not in state_offstage and name not in departed
            ]
            if mode == "act":
                active = [name for name in active if name != speaker]
            if active:
                return active

        active = [name for name in deduped if name not in departed]
        if mode == "act":
            active = [name for name in active if name != speaker]
        if active:
            return active
        # Never end up with an empty speaker pool.
        fallback = [name for name in deduped if not (mode == "act" and name == speaker)]
        return fallback or deduped[:1]

    def _merge_scene_progress_state(self, session: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
        return _scene_progress.merge_scene_progress_state(
            session,
            incoming,
            transcript=self._serialize_transcript(session),
            state_version=self.SESSION_STATE_VERSION,
        )

    def _derive_scene_progress_state(
        self,
        session: dict[str, Any],
        transcript: list[dict[str, Any]],
    ) -> dict[str, Any]:
        return _scene_progress.derive_scene_progress_state(
            session,
            transcript,
            state_version=self.SESSION_STATE_VERSION,
        )


    @staticmethod
    def _choose_response_limit_hint(
        *, mode: str, active_count: int, turn_id: str, message_kind: str
    ) -> int:
        if active_count <= 0:
            return 1
        seed = sum(ord(ch) for ch in str(turn_id or ""))
        rng = random.Random(seed)
        if mode == "observe":
            upper = min(4, max(2, active_count))
            lower = 3 if active_count >= 4 else 2
            if message_kind == "narration":
                upper = min(5, max(upper, 3))
                lower = min(upper, 2 if active_count <= 2 else 3)
            return rng.randint(lower, upper)
        if message_kind == "narration" and mode in {"act", "insert"}:
            upper = min(4, max(1, active_count))
            lower = 2 if active_count >= 2 else 1
            return rng.randint(lower, upper)
        upper = min(3, max(1, active_count))
        lower = 1 if active_count <= 1 else 2
        return rng.randint(lower, upper)

    @staticmethod
    def _load_text_excerpt(path_text: str, *, limit: int) -> str:
        return _relation_excerpt.load_text_excerpt(path_text, limit=limit)

    @staticmethod
    def _pair_key(left: str, right: str) -> str:
        return _relation_state.pair_key(left, right)

    @staticmethod
    def _default_relation_entry() -> dict[str, Any]:
        return _relation_state.default_relation_entry()

    @classmethod
    def _normalize_relation_entry(cls, raw: dict[str, Any] | None) -> dict[str, Any]:
        return _relation_state.normalize_relation_entry(raw)

    def _seed_relation_matrix(
        self, run_manifest: dict[str, Any], participants: list[str]
    ) -> dict[str, Any]:
        relation_graph = dict(
            run_manifest.get("artifact_index", {}).get("relation_graph", {}) or {}
        )
        relation_path = Path(str(relation_graph.get("relations_file", "")).strip())
        if not relation_path.exists():
            return {}
        try:
            payload = load_relations_source(relation_path)
        except Exception:
            return {}
        relations = dict(payload.get("relations", {}) or {})
        return _relation_state.seed_relation_matrix(relations, participants)

    def _merged_relation_matrix(
        self, session: dict[str, Any], participants: list[str]
    ) -> dict[str, Any]:
        return _relation_state.merged_relation_matrix(
            self._session_relation_matrix(session),
            self._session_relation_delta(session),
            participants,
        )

    @staticmethod
    def _empty_event_signals_state() -> dict[str, Any]:
        return _state_utils.empty_event_signals_state()

    def _merge_event_signals_state(
        self, session: dict[str, Any], incoming: list[dict[str, Any]]
    ) -> dict[str, Any]:
        return _event_signals.merge_event_signals_state(
            self._session_event_signals(session),
            incoming,
            participants=list(session.get("participants", []) or []),
            updated_at=_utc_now(),
        )

    def _latest_event_signal(
        self, session: dict[str, Any], *kinds: str
    ) -> dict[str, Any]:
        return _event_signals.latest_event_signal(
            self._session_event_signals(session), *kinds
        )

    def _build_session_relation_excerpt(
        self,
        session: dict[str, Any],
        *,
        participants: list[str],
        active_participants: list[str],
    ) -> str:
        deltas = self._session_relation_delta(session)
        if not deltas:
            return ""
        merged = self._merged_relation_matrix(session, participants)
        focus_keys: list[str] = []
        focus_names = [
            str(item).strip()
            for item in [*active_participants, *participants]
            if str(item).strip()
        ]
        for index, left in enumerate(focus_names):
            for right in focus_names[index + 1 :]:
                pair_key = self._pair_key(left, right)
                if pair_key and pair_key not in focus_keys:
                    focus_keys.append(pair_key)
        lines: list[str] = []
        for pair_key in focus_keys:
            delta = dict(deltas.get(pair_key, {}) or {})
            if not delta:
                continue
            relation = dict(merged.get(pair_key, {}) or {})
            metric_bits: list[str] = []
            for field, label in (
                ("trust", "信任"),
                ("affection", "好感"),
                ("hostility", "敌意"),
                ("ambiguity", "暧昧/摇摆"),
            ):
                change = int(delta.get(field, 0) or 0)
                if change:
                    metric_bits.append(f"{label}{change:+d}")
            if not metric_bits:
                continue
            status_bits = [
                f"trust={int(relation.get('trust', 5) or 5)}",
                f"affection={int(relation.get('affection', 5) or 5)}",
                f"hostility={int(relation.get('hostility', 0) or 0)}",
                f"ambiguity={int(relation.get('ambiguity', 3) or 3)}",
            ]
            line = f"## {pair_key}\n- session_delta: {', '.join(metric_bits)}\n- merged_state: {', '.join(status_bits)}"
            last_event = str(delta.get("last_event", "")).strip()
            if last_event:
                line = (
                    f"{line}\n- last_event: {self._trim_summary_text(last_event, 120)}"
                )
            last_actor = str(delta.get("last_actor", "")).strip()
            last_target = str(delta.get("last_target", "")).strip()
            if last_actor or last_target:
                line = f"{line}\n- drift: {self._trim_summary_text(' -> '.join([item for item in (last_actor, last_target) if item]), 80)}"
            lines.append(line)
            if len("\n".join(lines)) >= 1200:
                break
        return "\n".join(lines).strip()

    def _build_session_event_excerpt(
        self, session: dict[str, Any]
    ) -> list[dict[str, Any]]:
        return _event_signals.build_session_event_excerpt(
            self._session_event_signals(session)
        )

    def _build_persona_contexts(
        self,
        *,
        participants: list[str],
        active_participants: list[str],
        persona_map: dict[str, dict[str, Any]],
        mode: str,
        controlled_character: str,
        character_snapshots: dict[str, Any] | None = None,
    ) -> list[dict[str, Any]]:
        return _persona_context.build_persona_contexts(
            participants=participants,
            active_participants=active_participants,
            persona_map=persona_map,
            mode=mode,
            controlled_character=controlled_character,
            character_snapshots=character_snapshots,
        )

    @staticmethod
    def _load_persona_profile(meta: dict[str, Any]) -> tuple[dict[str, Any], Path]:
        return _persona_context.load_persona_profile(meta)

    @staticmethod
    def _persona_preview_payload(
        meta: dict[str, Any], normalized_profile: dict[str, Any]
    ) -> dict[str, Any]:
        return _persona_context.persona_preview_payload(meta, normalized_profile)

    @staticmethod
    def _persona_profile_payload(
        normalized_profile: dict[str, Any], *, detailed: bool
    ) -> dict[str, Any]:
        return _persona_context.persona_profile_payload(
            normalized_profile, detailed=detailed
        )

    @staticmethod
    def _persona_snapshot_payload(
        snapshot: dict[str, Any], *, detailed: bool
    ) -> dict[str, Any]:
        return _persona_context.persona_snapshot_payload(snapshot, detailed=detailed)

    def _build_relation_excerpt(
        self,
        path_text: str,
        *,
        participants: list[str],
        active_participants: list[str],
        message: str,
        scene_card: dict[str, Any],
    ) -> str:
        return _relation_excerpt.build_relation_excerpt(
            path_text,
            participants=participants,
            active_participants=active_participants,
            message=message,
            scene_card=scene_card,
        )

    @staticmethod
    def _choose_relation_excerpt_limit(
        *, participants: list[str], active_participants: list[str]
    ) -> int:
        return _relation_excerpt.choose_relation_excerpt_limit(
            participants=participants,
            active_participants=active_participants,
        )

    @staticmethod
    def _choose_relation_excerpt_scan_limit(
        *, participants: list[str], active_participants: list[str]
    ) -> int:
        return _relation_excerpt.choose_relation_excerpt_scan_limit(
            participants=participants,
            active_participants=active_participants,
        )

    @staticmethod
    def _extract_relevant_relation_excerpt(
        text: str, focus_terms: list[str], limit: int
    ) -> str:
        return _relation_excerpt.extract_relevant_relation_excerpt(
            text, focus_terms, limit
        )

    def _build_turn_memory_context(
        self,
        *,
        run_id: str,
        session: dict[str, Any],
        transcript: list[dict[str, Any]],
        speaker: str,
        message: str,
        participants: list[str],
        active_participants: list[str],
        scene_card: dict[str, Any],
        scene_progress: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        session_summary = self._build_session_memory_summary(run_id, session, transcript)
        context = _turn_memory.build_turn_memory_context(
            state_summary=self._session_memory_summary_state(session),
            scene_progress=scene_progress,
            character_snapshots=self._session_character_snapshots(session),
            relation_delta=self._session_relation_delta(session),
            event_signals=self._build_session_event_excerpt(session),
            session_summary=session_summary,
            memory_hits=[],
        )
        context["retrieved_memories"] = self._search_turn_memory_hits(
            run_id=run_id,
            session_id=str(session.get("session_id", "")).strip(),
            speaker=speaker,
            message=message,
            participants=participants,
            active_participants=active_participants,
            scene_card=scene_card,
            session_summary=session_summary,
            scene_progress=dict(context.get("scene_progress", {}) or {}),
        )
        return context

    def _search_turn_memory_hits(
        self,
        *,
        run_id: str,
        session_id: str,
        speaker: str,
        message: str,
        participants: list[str],
        active_participants: list[str],
        scene_card: dict[str, Any],
        session_summary: dict[str, Any],
        scene_progress: dict[str, Any],
    ) -> list[dict[str, Any]]:
        return _turn_memory.search_turn_memory_hits(
            self._resolve_memory_store(run_id),
            session_id=session_id,
            speaker=speaker,
            message=message,
            participants=participants,
            active_participants=active_participants,
            scene_card=scene_card,
            session_summary=session_summary,
            scene_progress=scene_progress,
        )


    @staticmethod
    def _character_index(run_manifest: dict[str, Any]) -> list[dict[str, Any]]:
        return list(run_manifest.get("artifact_index", {}).get("characters", []) or [])

    def _serialize_session(
        self, run_id: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        session = dict(payload)
        session["file_urls"] = self._build_file_urls(run_id, session)
        session["mode_display"] = self._mode_display(
            str(session.get("mode", "")).strip()
        )
        transcript = self._serialize_transcript(session)
        session["transcript"] = transcript
        session["scene_progress"] = self._session_scene_progress(session)
        session["relation_delta"] = self._session_relation_delta(session)
        session["character_snapshots"] = self._session_character_snapshots(session)
        session["event_signals"] = self._session_event_signals(session)
        session["relation_matrix"] = self._merged_relation_matrix(
            session, list(session.get("participants", []) or [])
        )
        session["last_entry_preview"] = self._build_last_entry_preview(session)
        session["session_card"] = self._build_session_card(session)
        session["scene_history"] = self._serialize_scene_history(session)
        session["branch_origin"] = dict(session.get("branch_origin", {}) or {})
        session["pending_turn_summary"] = self._build_pending_turn_summary(session)
        session["session_memory_summary"] = self._build_session_memory_summary(
            run_id, session, transcript
        )
        session["runtime_state_overview"] = self._build_runtime_state_overview(session)
        return session

    _serialize_transcript = staticmethod(_session_views.serialize_transcript)

    _mode_display = staticmethod(_text_utils.mode_display)

    def _build_session_card(self, session: dict[str, Any]) -> dict[str, Any]:
        return _session_views.build_session_card(session, mode_display=self._mode_display)

    _serialize_scene_history = staticmethod(_session_views.serialize_scene_history)

    _build_scene_history_entry = staticmethod(_session_views.build_scene_history_entry)

    def _build_pending_turn_summary(self, session: dict[str, Any]) -> dict[str, Any]:
        return _session_views.build_pending_turn_summary(
            session,
            normalize_message_kind=self._normalize_message_kind,
        )

    def _build_runtime_state_overview(self, session: dict[str, Any]) -> dict[str, Any]:
        return _runtime_overview.build_runtime_state_overview(
            scene_progress=self._session_scene_progress(session),
            session_summary=dict(session.get("session_memory_summary", {}) or {}),
            character_snapshots=self._session_character_snapshots(session),
            relation_delta=self._session_relation_delta(session),
            event_signals=self._session_event_signals(session),
        )

    def _build_session_memory_summary(
        self,
        run_id: str,
        session: dict[str, Any],
        transcript: list[dict[str, Any]],
    ) -> dict[str, str]:
        semantic_hint = ""
        session_id = str(session.get("session_id", "")).strip()
        if session_id and self._ensure_memory_store(run_id):
            try:
                hits = self._memory_stores[run_id].search_long_term_memory(
                    session_id,
                    "关系 冲突 目标",
                    top_k=1,
                )
            except Exception:
                hits = []
            if hits:
                semantic_hint = str((hits[0] or {}).get("text", "")).strip()
        return _memory_summary.build_session_memory_summary(
            session,
            transcript,
            scene_progress=self._session_scene_progress(session),
            relation_delta=self._session_relation_delta(session),
            event_signals=self._session_event_signals(session),
            semantic_hint=semantic_hint,
        )

    def _ensure_memory_store(self, run_id: str) -> bool:
        return self._resolve_memory_store(run_id) is not None

    def _resolve_memory_store(self, run_id: str) -> MarkdownSessionStore | None:
        normalized_run_id = str(run_id or "").strip()
        if not normalized_run_id:
            return None
        cached = self._memory_stores.get(normalized_run_id)
        if cached is not None:
            return cached
        try:
            if callable(self._memory_store_resolver):
                resolved = self._memory_store_resolver(normalized_run_id)
                if resolved is not None:
                    self._memory_stores[normalized_run_id] = resolved
                    return resolved
            config = Config()
            config.update(
                {
                    "paths": {
                        "sessions": str(
                            self.runs_root
                            / normalized_run_id
                            / "__session_memory_cache"
                        )
                    }
                }
            )
            resolved = MarkdownSessionStore(PathProvider(config))
            self._memory_stores[normalized_run_id] = resolved
            return resolved
        except Exception:
            return None

    _trim_summary_text = staticmethod(_text_utils.trim_summary_text)
    _build_last_entry_preview = staticmethod(_text_utils.build_last_entry_preview)

    def _build_file_urls(self, run_id: str, session: dict[str, Any]) -> dict[str, str]:
        session_id = str(session.get("session_id", "")).strip()
        urls: dict[str, str] = {}
        run_dir = self.runs_root / run_id
        session_relative = self._relative_to_run_dir(
            self._session_file(run_id, session_id), run_dir
        )
        if session_relative is not None:
            urls["session"] = self._file_url(run_id, session_relative)
        pending_path_text = str(
            session.get("pending_turn", {}).get("payload_path", "")
        ).strip()
        if pending_path_text:
            pending_path = Path(pending_path_text)
        else:
            pending_path = None
        if pending_path and pending_path.exists():
            pending_relative = self._relative_to_run_dir(pending_path, run_dir)
            if pending_relative is not None:
                urls["pending_turn_payload"] = self._file_url(run_id, pending_relative)
        return urls

    _build_scene_switch_note = staticmethod(_text_utils.build_scene_switch_note)
    _entry_to_memory_text = staticmethod(_text_utils.entry_to_memory_text)

    def _sessions_root(self, run_id: str) -> Path:
        return self._session_files.sessions_root(run_id)

    def _session_dir(self, run_id: str, session_id: str) -> Path:
        return self._session_files.session_dir(run_id, session_id)

    def _session_file(self, run_id: str, session_id: str) -> Path:
        return self._session_files.session_file(run_id, session_id)

    def session_lock(self, run_id: str, session_id: str):
        return self._session_files.lock(run_id, session_id)

    def _file_url(self, run_id: str, relative_path: Path) -> str:
        return f"/api/web/runs/{run_id}/files/{relative_path.as_posix()}"

    @staticmethod
    def _relative_to_run_dir(path: Path, run_dir: Path) -> Path | None:
        return relative_to_run_dir(path, run_dir)

    @staticmethod
    def _relative_candidates(path: Path, run_dir: Path) -> list[tuple[Path, Path]]:
        return relative_candidates(path, run_dir)

    @staticmethod
    def _normalized_parts(path: Path) -> tuple[str, ...]:
        return normalized_parts(path)

    @staticmethod
    def _read_json(path: Path) -> dict[str, Any]:
        return read_json(path)

    @staticmethod
    def _write_json(path: Path, payload: dict[str, Any]) -> None:
        write_json(path, payload)
