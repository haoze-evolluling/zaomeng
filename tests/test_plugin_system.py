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
from src.web.service_facades.plugins import PluginServiceMixin


class _Host:
    def read_dialogue_context(self, **kwargs):
        return {"run_id": kwargs["run_id"], "mode": "act"}

    def invoke_model(self, capability, payload):
        if capability == "dialogue_reply_variants":
            return {
                "options": [
                    {"label": "克制回应", "suggestion": "我明白了。"},
                    {"label": "继续追问", "suggestion": "然后呢？"},
                ]
            }
        return f"{capability}:{payload['run_id']}"


class _PluginService(PluginServiceMixin):
    def __init__(self, plugins: PluginRegistry, dialogue=None) -> None:
        self.plugins = plugins
        self.dialogue = dialogue

    def _require_manifest(self, run_id: str):
        if not run_id:
            raise FileNotFoundError(run_id)
        return object()


class _EnhancerDialogue:
    def __init__(self) -> None:
        self.session = {"plugin_enhancer_states": {}}

    def get_session(self, run_id: str, session_id: str):
        return dict(self.session)

    def set_plugin_enhancer_state(
        self, run_id: str, session_id: str, enhancer_key: str, enabled: bool
    ):
        states = dict(self.session.get("plugin_enhancer_states", {}))
        states[enhancer_key] = enabled
        self.session = {"plugin_enhancer_states": states}
        return dict(self.session)


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
    def test_android_bundle_extracts_builtin_plugins_with_src_package(self):
        build_script = Path("android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('include("src/**")', build_script)
        self.assertIn('extractPackages("src")', build_script)
        self.assertTrue(
            Path("src/builtin_plugins/ai_association/plugin.json").is_file()
        )

    def test_web_service_discovers_packaged_builtin_plugin(self):
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            plugins = WebRunService(tmp).plugins.list_plugins()

        builtin = next(
            plugin
            for plugin in plugins
            if plugin["id"] == "com.zaomeng.ai-association"
        )
        self.assertEqual(builtin["source"], "official")
        self.assertTrue(builtin["enabled"])

    def test_builtin_voice_polish_and_reply_variants_plugins(self):
        with tempfile.TemporaryDirectory() as tmp:
            registry = PluginRegistry(
                [Path("src/builtin_plugins")],
                host_factory=lambda _plugin_id, _permissions: _Host(),
                state_path=Path(tmp) / "plugin-state.json",
            )

            plugin_ids = {plugin["id"] for plugin in registry.list_plugins()}
            self.assertIn("com.zaomeng.voice-polish", plugin_ids)
            self.assertIn("com.zaomeng.reply-variants", plugin_ids)
            polished = registry.invoke_chat_action(
                "com.zaomeng.voice-polish",
                "polish-draft",
                {
                    "run_id": "run-1",
                    "session_id": "session-1",
                    "seed_text": "原始草稿",
                },
            )
            variants = registry.invoke_chat_action(
                "com.zaomeng.reply-variants",
                "generate-variants",
                {"run_id": "run-1", "session_id": "session-1"},
            )

        self.assertEqual(polished["suggestion"], "dialogue_suggestion:run-1")
        self.assertEqual(len(variants["suggestions"]), 2)
        self.assertEqual(variants["suggestions"][0]["label"], "克制回应")

    def test_inner_thoughts_enhancer_has_per_chat_state_and_respects_plugin_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            registry = PluginRegistry(
                [Path("src/builtin_plugins")],
                host_factory=lambda _plugin_id, _permissions: _Host(),
                state_path=Path(tmp) / "plugin-state.json",
            )
            service = _PluginService(registry, _EnhancerDialogue())

            initial = service.resolve_generation_enhancer_options("run-1", "session-1")
            session = service.set_generation_enhancer_state(
                "com.zaomeng.inner-thoughts",
                "inner-thoughts",
                run_id="run-1",
                session_id="session-1",
                enabled=True,
            )
            active = service.resolve_generation_enhancer_options("run-1", "session-1")
            registry.disable("com.zaomeng.inner-thoughts")
            unavailable = service.resolve_generation_enhancer_options(
                "run-1", "session-1"
            )

        self.assertEqual(initial, {})
        self.assertTrue(
            session["plugin_enhancer_states"]
            ["com.zaomeng.inner-thoughts/inner-thoughts"]
        )
        self.assertTrue(active["include_inner_thoughts"])
        self.assertEqual(unavailable, {})

    def test_app_surfaces_plugin_management_entries(self):
        settings_home = Path(
            "android/app/src/main/java/top/wkbin/zaomeng/feature/settings/SettingsHomeScreen.kt"
        ).read_text(encoding="utf-8")
        web_modal = Path("src/web/static/fragments/settings-modal.html").read_text(
            encoding="utf-8"
        )
        web_bootstrap = Path("src/web/static/js/bootstrap.js").read_text(
            encoding="utf-8"
        )
        self.assertIn('title = "插件"', settings_home)
        self.assertIn('id="plugin-manager-modal"', web_modal)
        self.assertIn("/web/js/plugin-manager.js", web_bootstrap)

    def test_plugin_management_routes_are_registered(self):
        from src.web.app import create_app
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            paths = create_app(WebRunService(tmp)).openapi()["paths"]
        self.assertIn("/api/web/plugins", paths)
        self.assertIn("/api/web/plugins/refresh", paths)
        self.assertIn("/api/web/plugins/{plugin_id}/enable", paths)
        self.assertIn("/api/web/plugins/{plugin_id}/disable", paths)
        self.assertIn(
            "/api/web/runs/{run_id}/dialogue/sessions/{session_id}"
            "/plugins/{plugin_id}/actions/{action_id}",
            paths,
        )
        self.assertIn(
            "/api/web/runs/{run_id}/dialogue/sessions/{session_id}"
            "/plugins/{plugin_id}/enhancers/{enhancer_id}/state",
            paths,
        )

    def test_android_chat_surfaces_plugins_in_a_dedicated_menu(self):
        chat_screen = Path(
            "android/app/src/main/java/top/wkbin/zaomeng/feature/chat/ChatScreen.kt"
        ).read_text(encoding="utf-8")

        self.assertIn('Text(if (pluginActionBusy) "插件运行中…" else "插件")', chat_screen)
        self.assertIn("state.pluginActions.forEach", chat_screen)
        self.assertIn("state.generationEnhancers.forEach", chat_screen)
        self.assertNotIn('contentDescription = "一键生成续写建议"', chat_screen)
        self.assertNotIn('Text(if (state.includeInnerThoughts) "关闭读心"', chat_screen)

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
            self.assertEqual(registry.list_plugins()[0]["source"], "official")
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

    def test_plugin_service_invokes_discovered_chat_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "plugins"
            _write_plugin(root)
            registry = PluginRegistry(
                [root],
                host_factory=lambda _plugin_id, _permissions: _Host(),
                state_path=Path(tmp) / "plugin-state.json",
            )

            result = _PluginService(registry).invoke_plugin_chat_action(
                "com.example.demo",
                "run",
                run_id="run-1",
                session_id="session-1",
                seed_text="draft",
            )

        self.assertEqual(result["suggestion"], "v1:run-1")

    def test_host_denies_undeclared_model_permission(self):
        host = ZaomengPluginHost(object(), "com.example.demo", frozenset())
        with self.assertRaises(PluginPermissionError):
            host.invoke_model("dialogue_suggestion", {"run_id": "run-1"})


if __name__ == "__main__":
    unittest.main()
