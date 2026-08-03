from __future__ import annotations

import json
import re
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType
from typing import Any, Callable, Protocol


PLUGIN_API_VERSION = "1"
_PLUGIN_ID = re.compile(r"^[a-z0-9]+(?:[._-][a-z0-9]+)+$")
_ACTION_ID = re.compile(r"^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
_KNOWN_PERMISSIONS = {
    "chat.context.read",
    "chat.draft.write",
    "model.invoke",
    "storage.read",
    "storage.write",
    "network.access",
}


class PluginError(ValueError):
    """Raised when a plugin cannot be discovered, loaded, or invoked."""


class PluginPermissionError(PluginError):
    """Raised when a plugin calls a host capability it did not declare."""


class PluginHost(Protocol):
    def read_dialogue_context(
        self,
        *,
        run_id: str,
        session_id: str,
        seed_text: str = "",
        direction: str = "",
    ) -> dict[str, Any]: ...

    def invoke_model(self, capability: str, payload: dict[str, Any]) -> Any: ...


@dataclass(frozen=True)
class ChatAction:
    id: str
    title: str
    placement: str
    icon: str = ""

    @classmethod
    def parse(cls, payload: Any) -> "ChatAction":
        if not isinstance(payload, dict):
            raise PluginError("chatActions entries must be JSON objects.")
        action_id = str(payload.get("id", "")).strip()
        title = str(payload.get("title", "")).strip()
        placement = str(payload.get("placement", "composer")).strip() or "composer"
        if not _ACTION_ID.fullmatch(action_id):
            raise PluginError(f"Invalid chat action id: {action_id!r}.")
        if not title or len(title) > 80:
            raise PluginError("A chat action title must contain 1-80 characters.")
        if placement not in {"composer", "message", "tools"}:
            raise PluginError(f"Unsupported chat action placement: {placement!r}.")
        return cls(
            id=action_id,
            title=title,
            placement=placement,
            icon=str(payload.get("icon", "")).strip()[:40],
        )

    def to_dict(self) -> dict[str, str]:
        return {
            "id": self.id,
            "title": self.title,
            "placement": self.placement,
            "icon": self.icon,
        }


@dataclass(frozen=True)
class PluginManifest:
    id: str
    name: str
    version: str
    api_version: str
    entry: str
    description: str
    permissions: tuple[str, ...]
    chat_actions: tuple[ChatAction, ...]
    default_enabled: bool

    @classmethod
    def load(cls, path: Path) -> "PluginManifest":
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise PluginError(f"Cannot read plugin manifest {path}: {exc}") from exc
        if not isinstance(raw, dict):
            raise PluginError("plugin.json must contain a JSON object.")

        plugin_id = str(raw.get("id", "")).strip()
        name = str(raw.get("name", "")).strip()
        version = str(raw.get("version", "")).strip()
        api_version = str(raw.get("apiVersion", "")).strip()
        entry = str(raw.get("entry", "")).strip()
        if not _PLUGIN_ID.fullmatch(plugin_id):
            raise PluginError(f"Invalid plugin id: {plugin_id!r}.")
        if not name or len(name) > 80:
            raise PluginError("Plugin name must contain 1-80 characters.")
        if not version or len(version) > 40:
            raise PluginError("Plugin version is required and must be at most 40 characters.")
        if api_version != PLUGIN_API_VERSION:
            raise PluginError(
                f"Plugin {plugin_id!r} requires API {api_version!r}; host supports {PLUGIN_API_VERSION!r}."
            )
        if not entry or Path(entry).is_absolute() or ".." in Path(entry).parts:
            raise PluginError("Plugin entry must be a relative path inside the plugin directory.")
        if Path(entry).suffix.lower() != ".py":
            raise PluginError("Plugin API v1 only supports Python entry files.")

        raw_permissions = raw.get("permissions", [])
        if not isinstance(raw_permissions, list):
            raise PluginError("permissions must be a JSON array.")
        permissions = tuple(dict.fromkeys(str(item).strip() for item in raw_permissions))
        unknown = sorted(set(permissions) - _KNOWN_PERMISSIONS)
        if unknown:
            raise PluginError(f"Unknown plugin permissions: {', '.join(unknown)}.")

        contributes = raw.get("contributes", {}) or {}
        if not isinstance(contributes, dict):
            raise PluginError("contributes must be a JSON object.")
        raw_actions = contributes.get("chatActions", []) or []
        if not isinstance(raw_actions, list):
            raise PluginError("contributes.chatActions must be a JSON array.")
        actions = tuple(ChatAction.parse(item) for item in raw_actions)
        action_ids = [item.id for item in actions]
        if len(action_ids) != len(set(action_ids)):
            raise PluginError("Chat action ids must be unique within a plugin.")
        if actions and not {"chat.context.read", "chat.draft.write"}.issubset(permissions):
            raise PluginError(
                "Plugins contributing chatActions must declare chat.context.read and chat.draft.write."
            )
        return cls(
            id=plugin_id,
            name=name,
            version=version,
            api_version=api_version,
            entry=entry,
            description=str(raw.get("description", "")).strip()[:500],
            permissions=permissions,
            chat_actions=actions,
            default_enabled=bool(raw.get("defaultEnabled", False)),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "version": self.version,
            "apiVersion": self.api_version,
            "description": self.description,
            "permissions": list(self.permissions),
            "contributes": {
                "chatActions": [item.to_dict() for item in self.chat_actions]
            },
            "defaultEnabled": self.default_enabled,
        }


@dataclass
class _PluginRecord:
    manifest: PluginManifest
    root: Path
    instance: Any = None
    module: ModuleType | None = None
    module_prefix: str = ""
    enabled: bool = False
    error: str = ""
    active_calls: int = 0


class PluginRegistry:
    """Discovers and hot-enables trusted, in-process API v1 plugins."""

    def __init__(
        self,
        roots: list[Path],
        *,
        host_factory: Callable[[str, frozenset[str]], PluginHost],
        state_path: Path | None = None,
    ) -> None:
        self._roots = tuple(Path(item) for item in roots)
        self._host_factory = host_factory
        self._state_path = state_path
        self._records: dict[str, _PluginRecord] = {}
        self._lock = threading.RLock()
        self._condition = threading.Condition(self._lock)
        self.refresh()

    def _load_enabled_state(self) -> set[str] | None:
        if self._state_path is None or not self._state_path.is_file():
            return None
        try:
            payload = json.loads(self._state_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return set()
        values = payload.get("enabled", []) if isinstance(payload, dict) else []
        return {str(item).strip() for item in values if str(item).strip()}

    def _save_enabled_state(self) -> None:
        if self._state_path is None:
            return
        self._state_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"enabled": sorted(key for key, item in self._records.items() if item.enabled)}
        temporary = self._state_path.with_suffix(self._state_path.suffix + ".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self._state_path)

    def refresh(self) -> list[dict[str, Any]]:
        with self._lock:
            desired = self._load_enabled_state()
            previously_enabled = {
                plugin_id for plugin_id, record in self._records.items() if record.enabled
            }
            discovered: dict[str, _PluginRecord] = {}
            for base in self._roots:
                if not base.is_dir():
                    continue
                for manifest_path in sorted(base.glob("*/plugin.json")):
                    try:
                        manifest = PluginManifest.load(manifest_path)
                        entry = (manifest_path.parent / manifest.entry).resolve()
                        if not entry.is_relative_to(manifest_path.parent.resolve()) or not entry.is_file():
                            raise PluginError(f"Plugin entry does not exist: {manifest.entry}.")
                        if manifest.id in discovered:
                            raise PluginError(f"Duplicate plugin id: {manifest.id}.")
                        discovered[manifest.id] = _PluginRecord(
                            manifest=manifest,
                            root=manifest_path.parent,
                        )
                    except PluginError:
                        continue

            for record in self._records.values():
                self._deactivate(record)
            self._records = discovered
            for plugin_id, record in self._records.items():
                should_enable = (
                    plugin_id in desired
                    if desired is not None
                    else (plugin_id in previously_enabled or record.manifest.default_enabled)
                )
                if should_enable and not record.enabled:
                    try:
                        self._activate(record)
                    except PluginError as exc:
                        record.error = str(exc)
            return self.list_plugins()

    def _activate(self, record: _PluginRecord) -> None:
        entry = (record.root / record.manifest.entry).resolve()
        module_prefix = f"_zaomeng_plugin_{record.manifest.id.replace('.', '_').replace('-', '_')}"
        entry_parts = Path(record.manifest.entry).with_suffix("").parts
        module_name = ".".join((module_prefix, *entry_parts))
        package = ModuleType(module_prefix)
        package.__path__ = [str(record.root)]  # type: ignore[attr-defined]
        package.__package__ = module_prefix
        sys.modules[module_prefix] = package
        module = ModuleType(module_name)
        module.__file__ = str(entry)
        module.__package__ = module_name.rpartition(".")[0]
        sys.modules[module_name] = module
        try:
            source = entry.read_text(encoding="utf-8")
            exec(compile(source, str(entry), "exec"), module.__dict__)
            factory = getattr(module, "create_plugin", None)
            if not callable(factory):
                raise PluginError("Plugin entry must export create_plugin().")
            instance = factory()
            execute = getattr(instance, "execute_chat_action", None)
            if record.manifest.chat_actions and not callable(execute):
                raise PluginError("Plugin contributes chatActions but has no execute_chat_action().")
            host = self._host_factory(record.manifest.id, frozenset(record.manifest.permissions))
            activate = getattr(instance, "activate", None)
            if callable(activate):
                activate(host)
            record.module = module
            record.module_prefix = module_prefix
            record.instance = instance
            record.enabled = True
            record.error = ""
        except Exception as exc:
            for loaded_name in tuple(sys.modules):
                if loaded_name == module_prefix or loaded_name.startswith(module_prefix + "."):
                    sys.modules.pop(loaded_name, None)
            if isinstance(exc, PluginError):
                raise
            raise PluginError(f"Plugin {record.manifest.id!r} failed to activate: {exc}") from exc

    def _deactivate(self, record: _PluginRecord) -> None:
        while record.active_calls > 0:
            self._condition.wait()
        if record.instance is not None:
            deactivate = getattr(record.instance, "deactivate", None)
            if callable(deactivate):
                try:
                    deactivate()
                except Exception:
                    pass
        if record.module_prefix:
            for loaded_name in tuple(sys.modules):
                if loaded_name == record.module_prefix or loaded_name.startswith(
                    record.module_prefix + "."
                ):
                    sys.modules.pop(loaded_name, None)
        record.instance = None
        record.module = None
        record.module_prefix = ""
        record.enabled = False

    def enable(self, plugin_id: str) -> dict[str, Any]:
        with self._lock:
            record = self._require_record(plugin_id)
            if not record.enabled:
                self._activate(record)
            self._save_enabled_state()
            return self._view(record)

    def disable(self, plugin_id: str) -> dict[str, Any]:
        with self._lock:
            record = self._require_record(plugin_id)
            self._deactivate(record)
            record.error = ""
            self._save_enabled_state()
            return self._view(record)

    def _require_record(self, plugin_id: str) -> _PluginRecord:
        normalized = str(plugin_id).strip()
        record = self._records.get(normalized)
        if record is None:
            raise PluginError(f"Plugin not found: {normalized!r}.")
        return record

    def invoke_chat_action(
        self, plugin_id: str, action_id: str, request: dict[str, Any]
    ) -> dict[str, Any]:
        with self._lock:
            record = self._require_record(plugin_id)
            if not record.enabled or record.instance is None:
                raise PluginError(f"Plugin is disabled: {plugin_id!r}.")
            if action_id not in {item.id for item in record.manifest.chat_actions}:
                raise PluginError(f"Chat action not found: {plugin_id}/{action_id}.")
            instance = record.instance
            record.active_calls += 1
        try:
            result = instance.execute_chat_action(action_id, dict(request))
        finally:
            with self._lock:
                record.active_calls -= 1
                self._condition.notify_all()
        if not isinstance(result, dict):
            raise PluginError("Chat action result must be a JSON object.")
        return result

    def list_plugins(self) -> list[dict[str, Any]]:
        with self._lock:
            return [self._view(item) for item in self._records.values()]

    @staticmethod
    def _view(record: _PluginRecord) -> dict[str, Any]:
        return {
            **record.manifest.to_dict(),
            "enabled": record.enabled,
            "status": "enabled" if record.enabled else ("error" if record.error else "disabled"),
            "error": record.error,
        }
