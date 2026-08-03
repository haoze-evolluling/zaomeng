from __future__ import annotations

import base64
import binascii
import shutil
import tempfile
from pathlib import Path
from typing import Any
from uuid import uuid4

from src.core.exceptions import LLMRequestError
from src.plugin_system import PluginError
from src.plugin_system.packages import (
    extract_plugin_archive,
    inspect_plugin_archive,
    read_stage_metadata,
    write_stage_metadata,
)
from src.web.chat import friendly_dialogue_llm_error
from src.web.time_utils import utc_now


class PluginServiceMixin:
    def list_plugins(self) -> list[dict[str, Any]]:
        return self.plugins.list_plugins()

    def refresh_plugins(self) -> list[dict[str, Any]]:
        return self.plugins.refresh()

    def enable_plugin(self, plugin_id: str) -> dict[str, Any]:
        return self.plugins.enable(plugin_id)

    def disable_plugin(self, plugin_id: str) -> dict[str, Any]:
        return self.plugins.disable(plugin_id)

    def list_plugin_logs(self, plugin_id: str, *, limit: int = 100) -> dict[str, Any]:
        return {"items": self.plugins.list_logs(plugin_id, limit=limit)}

    def get_plugin_config(self, plugin_id: str) -> dict[str, Any]:
        return {"config": self.plugins.get_config(plugin_id)}

    def update_plugin_config(
        self, plugin_id: str, config: dict[str, Any]
    ) -> dict[str, Any]:
        return {"config": self.plugins.update_config(plugin_id, config)}

    @property
    def _plugin_staging_root(self):
        root = self.storage_root / "plugin-staging"
        root.mkdir(parents=True, exist_ok=True)
        return root

    @property
    def _user_plugins_root(self):
        root = self.storage_root / "plugins"
        root.mkdir(parents=True, exist_ok=True)
        return root

    def inspect_plugin_package(
        self, *, filename: str, content_base64: str
    ) -> dict[str, Any]:
        try:
            data = base64.b64decode(str(content_base64 or ""), validate=True)
        except (ValueError, binascii.Error) as exc:
            raise PluginError("插件包内容不是有效的 Base64 数据。") from exc
        token = uuid4().hex
        package_path = self._plugin_staging_root / f"{token}.zip"
        metadata_path = self._plugin_staging_root / f"{token}.json"
        package_path.write_bytes(data)
        try:
            inspected = inspect_plugin_archive(package_path)
            manifest = inspected.manifest.to_dict()
            duplicate = next(
                (
                    item
                    for item in self.plugins.list_plugins()
                    if item.get("id") == inspected.manifest.id
                ),
                None,
            )
            operation = "install"
            blocked_reason = ""
            if not inspected.compatible:
                operation = "blocked"
                blocked_reason = (
                    f"插件需要 API {inspected.manifest.api_version}，"
                    "当前应用仅支持 API 1。"
                )
            elif duplicate:
                if duplicate.get("source") == "official":
                    operation = "blocked"
                    blocked_reason = "内置插件不能由外部插件包覆盖。"
                else:
                    operation = "update"
            metadata = {
                "token": token,
                "filename": str(filename or "plugin.zip")[:200],
                "plugin_id": inspected.manifest.id,
                "root_prefix": inspected.root_prefix,
                "operation": operation,
            }
            write_stage_metadata(metadata_path, metadata)
            return {
                "token": token,
                "plugin": manifest,
                "operation": operation,
                "blockedReason": blocked_reason,
                "currentVersion": str((duplicate or {}).get("version", "")),
                "compatible": inspected.compatible,
                "hostApiVersion": "1",
                "fileCount": inspected.file_count,
                "extractedBytes": inspected.extracted_bytes,
            }
        except Exception:
            package_path.unlink(missing_ok=True)
            metadata_path.unlink(missing_ok=True)
            raise

    def install_inspected_plugin_package(
        self,
        token: str,
        *,
        confirm_permissions: bool,
        allow_update: bool,
    ) -> dict[str, Any]:
        normalized_token = str(token or "").strip().lower()
        if len(normalized_token) != 32 or any(
            char not in "0123456789abcdef" for char in normalized_token
        ):
            raise PluginError("插件安装会话标识无效。")
        if not confirm_permissions:
            raise PluginError("必须确认插件权限后才能安装。")
        package_path = self._plugin_staging_root / f"{normalized_token}.zip"
        metadata_path = self._plugin_staging_root / f"{normalized_token}.json"
        metadata = read_stage_metadata(metadata_path)
        inspected = inspect_plugin_archive(package_path)
        if not inspected.compatible:
            raise PluginError(
                f"插件需要 API {inspected.manifest.api_version}，当前应用仅支持 API 1。"
            )
        plugin_id = inspected.manifest.id
        if plugin_id != str(metadata.get("plugin_id", "")):
            raise PluginError("插件包内容在确认后发生变化，请重新检查。")
        duplicate = next(
            (item for item in self.plugins.list_plugins() if item.get("id") == plugin_id),
            None,
        )
        if duplicate and duplicate.get("source") == "official":
            raise PluginError("内置插件不能由外部插件包覆盖。")
        if duplicate and not allow_update:
            raise PluginError("插件 ID 已存在；请明确选择更新现有插件。")
        if not duplicate and allow_update:
            raise PluginError("要更新的插件已不存在，请重新检查插件包。")
        target = self._user_plugins_root / plugin_id
        if duplicate:
            target, source = self.plugins.plugin_location(plugin_id)
            if source != "third-party":
                raise PluginError("只能更新第三方插件。")
        elif target.exists():
            raise PluginError("插件目标目录已存在，但没有可识别的插件清单。")

        backup = self._plugin_staging_root / f"backup-{normalized_token}"
        try:
            with tempfile.TemporaryDirectory(
                prefix="plugin-install-", dir=self._plugin_staging_root
            ) as tmp:
                incoming = Path(tmp) / "plugin"
                extract_plugin_archive(
                    package_path, incoming, root_prefix=inspected.root_prefix
                )
                if target.exists():
                    target.replace(backup)
                shutil.copytree(incoming, target)
            self.plugins.refresh()
            installed = next(
                (item for item in self.plugins.list_plugins() if item.get("id") == plugin_id),
                None,
            )
            if not installed:
                raise PluginError("插件文件已写入，但刷新后没有发现该插件。")
            self.plugins.record_event(
                plugin_id,
                "info",
                "updated" if duplicate else "installed",
                "插件已更新。" if duplicate else "插件已安装。",
                details={"version": inspected.manifest.version},
            )
            shutil.rmtree(backup, ignore_errors=True)
            return installed
        except Exception:
            if target.exists():
                shutil.rmtree(target, ignore_errors=True)
            if backup.exists():
                backup.replace(target)
            self.plugins.refresh()
            raise
        finally:
            package_path.unlink(missing_ok=True)
            metadata_path.unlink(missing_ok=True)

    def uninstall_plugin(self, plugin_id: str) -> dict[str, Any]:
        root, source = self.plugins.plugin_location(plugin_id)
        if source != "third-party":
            raise PluginError("内置插件不能卸载，只能停用。")
        self.plugins.disable(plugin_id)
        trash_root = self.storage_root / "plugin-trash"
        trash_root.mkdir(parents=True, exist_ok=True)
        target = trash_root / f"{plugin_id}-{uuid4().hex[:8]}"
        root.replace(target)
        self.plugins.refresh()
        self.plugins.record_event(
            plugin_id,
            "info",
            "uninstalled",
            "插件已卸载。",
        )
        return {
            "status": "uninstalled",
            "pluginId": plugin_id,
            "recoverablePath": str(target),
            "uninstalledAt": utc_now(),
        }

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
            plugin_config = dict(plugin.get("config", {}) or {})
            contributes = dict(plugin.get("contributes", {}) or {})
            for enhancer in list(contributes.get("generationEnhancers", []) or []):
                enhancer_id = str(enhancer.get("id", "")).strip()
                key = self._generation_enhancer_key(plugin_id, enhancer_id)
                active = bool(
                    states.get(
                        key,
                        plugin_config.get(
                            "defaultActive", enhancer.get("defaultActive", False)
                        ),
                    )
                )
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
