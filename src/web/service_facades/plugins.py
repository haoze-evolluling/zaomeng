from __future__ import annotations

from typing import Any

from src.core.exceptions import LLMRequestError
from src.web.chat import friendly_dialogue_llm_error


class PluginServiceMixin:
    def list_plugins(self) -> list[dict[str, Any]]:
        return self.plugins.list_plugins()

    def refresh_plugins(self) -> list[dict[str, Any]]:
        return self.plugins.refresh()

    def enable_plugin(self, plugin_id: str) -> dict[str, Any]:
        return self.plugins.enable(plugin_id)

    def disable_plugin(self, plugin_id: str) -> dict[str, Any]:
        return self.plugins.disable(plugin_id)

    def invoke_plugin_chat_action(
        self,
        plugin_id: str,
        action_id: str,
        *,
        run_id: str,
        session_id: str,
        seed_text: str = "",
        direction: str = "",
    ) -> dict[str, Any]:
        self._require_manifest(run_id)
        try:
            return self.plugins.invoke_chat_action(
                plugin_id,
                action_id,
                {
                    "run_id": run_id,
                    "session_id": session_id,
                    "seed_text": seed_text,
                    "direction": direction,
                },
            )
        except LLMRequestError as exc:
            raise ValueError(friendly_dialogue_llm_error(exc)) from exc

    @staticmethod
    def _generation_enhancer_key(plugin_id: str, enhancer_id: str) -> str:
        return f"{plugin_id}/{enhancer_id}"

    def set_generation_enhancer_state(
        self,
        plugin_id: str,
        enhancer_id: str,
        *,
        run_id: str,
        session_id: str,
        enabled: bool,
    ) -> dict[str, Any]:
        self._require_manifest(run_id)
        self.plugins.require_generation_enhancer(plugin_id, enhancer_id)
        return self.dialogue.set_plugin_enhancer_state(
            run_id,
            session_id,
            self._generation_enhancer_key(plugin_id, enhancer_id),
            enabled,
        )

    def resolve_generation_enhancer_options(
        self, run_id: str, session_id: str
    ) -> dict[str, Any]:
        session = self.dialogue.get_session(run_id, session_id)
        states = dict(session.get("plugin_enhancer_states", {}) or {})
        options: dict[str, Any] = {}
        for plugin in self.plugins.list_plugins():
            if not plugin.get("enabled"):
                continue
            plugin_id = str(plugin.get("id", "")).strip()
            contributes = dict(plugin.get("contributes", {}) or {})
            for enhancer in list(contributes.get("generationEnhancers", []) or []):
                enhancer_id = str(enhancer.get("id", "")).strip()
                key = self._generation_enhancer_key(plugin_id, enhancer_id)
                active = bool(states.get(key, enhancer.get("defaultActive", False)))
                if not active:
                    continue
                try:
                    result = self.plugins.invoke_generation_enhancer(
                        plugin_id,
                        enhancer_id,
                        {"run_id": run_id, "session_id": session_id},
                    )
                except Exception:
                    continue
                options.update(result)
        return options
