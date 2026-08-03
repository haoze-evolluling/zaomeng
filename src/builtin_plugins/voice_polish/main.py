from __future__ import annotations

from typing import Any


class VoicePolishPlugin:
    def __init__(self) -> None:
        self._host: Any = None

    def activate(self, host: Any) -> None:
        self._host = host

    def deactivate(self) -> None:
        self._host = None

    def execute_chat_action(
        self, action_id: str, request: dict[str, Any]
    ) -> dict[str, str]:
        if action_id != "polish-draft":
            raise ValueError(f"Unsupported voice polish action: {action_id!r}.")
        if self._host is None:
            raise RuntimeError("Voice polish plugin is not active.")
        seed_text = str(request.get("seed_text", "")).strip()
        if not seed_text:
            raise ValueError("请先在输入框写下需要润色的草稿。")
        context = self._host.read_dialogue_context(
            run_id=str(request.get("run_id", "")).strip(),
            session_id=str(request.get("session_id", "")).strip(),
            seed_text=seed_text,
            direction=(
                "把输入草稿改写成当前受控角色真正会说或会做的成品文本。"
                "严格保留原意、事实和行动意图，不新增剧情前提；只返回润色结果。"
            ),
        )
        suggestion = self._host.invoke_model("dialogue_suggestion", context)
        return {"suggestion": str(suggestion).strip()}


def create_plugin() -> VoicePolishPlugin:
    return VoicePolishPlugin()
