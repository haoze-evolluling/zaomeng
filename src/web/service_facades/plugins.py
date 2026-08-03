from __future__ import annotations

from typing import Any


class PluginServiceMixin:
    def list_plugins(self) -> list[dict[str, Any]]:
        return self.plugins.list_plugins()

    def refresh_plugins(self) -> list[dict[str, Any]]:
        return self.plugins.refresh()

    def enable_plugin(self, plugin_id: str) -> dict[str, Any]:
        return self.plugins.enable(plugin_id)

    def disable_plugin(self, plugin_id: str) -> dict[str, Any]:
        return self.plugins.disable(plugin_id)
