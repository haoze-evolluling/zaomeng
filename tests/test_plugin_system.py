import json
import tempfile
import unittest
from pathlib import Path

from src.plugin_system import (
    PluginError,
    PluginManifest,
    PluginPermissionError,
    PluginRegistry,
)
from src.web.plugin_host import ZaomengPluginHost


class _Host:
    def read_dialogue_context(self, **kwargs):
        return {"run_id": kwargs["run_id"], "mode": "act"}

    def invoke_model(self, capability, payload):
        return f"{capability}:{payload['run_id']}"


def _write_plugin(root: Path, *, result: str = "v1", permissions=None) -> Path:
    plugin_root = root / "demo"
    plugin_root.mkdir(parents=True, exist_ok=True)
    manifest = {
        "id": "com.example.demo",
        "name": "Demo",
        "version": "1.0.0",
        "apiVersion": "1",
        "entry": "main.py",
        "defaultEnabled": True,
        "permissions": permissions
        or ["chat.context.read", "chat.draft.write"],
        "contributes": {
            "chatActions": [
                {"id": "run", "title": "Run", "placement": "composer"}
            ]
        },
    }
    (plugin_root / "plugin.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )
    (plugin_root / "main.py").write_text(
        f'''class DemoPlugin:
    def activate(self, host):
        self.host = host

    def deactivate(self):
        self.host = None

    def execute_chat_action(self, action_id, request):
        context = self.host.read_dialogue_context(run_id=request["run_id"], session_id="s")
        return {{"suggestion": "{result}:" + context["run_id"]}}

def create_plugin():
    return DemoPlugin()
''',
        encoding="utf-8",
    )
    return plugin_root


class PluginSystemTests(unittest.TestCase):
    def test_android_bundle_includes_official_plugins(self):
        build_script = Path("android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('include("plugins/**")', build_script)

    def test_plugin_management_routes_are_registered(self):
        from src.web.app import create_app
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            paths = create_app(WebRunService(tmp)).openapi()["paths"]
        self.assertIn("/api/web/plugins", paths)
        self.assertIn("/api/web/plugins/refresh", paths)
        self.assertIn("/api/web/plugins/{plugin_id}/enable", paths)
        self.assertIn("/api/web/plugins/{plugin_id}/disable", paths)

    def test_manifest_rejects_unknown_permission(self):
        with tempfile.TemporaryDirectory() as tmp:
            plugin_root = _write_plugin(
                Path(tmp),
                permissions=[
                    "chat.context.read",
                    "chat.draft.write",
                    "host.everything",
                ],
            )
            with self.assertRaisesRegex(PluginError, "Unknown plugin permissions"):
                PluginManifest.load(plugin_root / "plugin.json")

    def test_plugin_can_enable_invoke_disable_and_hot_reload(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "plugins"
            _write_plugin(root, result="v1")
            state_path = Path(tmp) / "plugin-state.json"
            registry = PluginRegistry(
                [root],
                host_factory=lambda _plugin_id, _permissions: _Host(),
                state_path=state_path,
            )

            self.assertTrue(registry.list_plugins()[0]["enabled"])
            self.assertEqual(
                registry.invoke_chat_action(
                    "com.example.demo", "run", {"run_id": "run-1"}
                )["suggestion"],
                "v1:run-1",
            )

            registry.disable("com.example.demo")
            with self.assertRaisesRegex(PluginError, "disabled"):
                registry.invoke_chat_action(
                    "com.example.demo", "run", {"run_id": "run-1"}
                )

            registry.enable("com.example.demo")
            _write_plugin(root, result="v2")
            registry.refresh()
            self.assertEqual(
                registry.invoke_chat_action(
                    "com.example.demo", "run", {"run_id": "run-2"}
                )["suggestion"],
                "v2:run-2",
            )

    def test_host_denies_undeclared_model_permission(self):
        host = ZaomengPluginHost(object(), "com.example.demo", frozenset())
        with self.assertRaises(PluginPermissionError):
            host.invoke_model("dialogue_suggestion", {"run_id": "run-1"})


if __name__ == "__main__":
    unittest.main()
