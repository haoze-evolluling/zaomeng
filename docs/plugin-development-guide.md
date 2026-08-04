# 造梦插件开发指南

本指南面向插件 API v1。可以复制官方最小模板 [`examples/plugin-template`](../examples/plugin-template)，也可以参考内置插件 [`src/builtin_plugins`](../src/builtin_plugins)。

## 创建插件

在运行数据目录的 `plugins` 下新建目录：

```text
plugins/
└── my-plugin/
    ├── plugin.json
    ├── main.py
    └── README.md
```

`plugin.json`：

```json
{
  "id": "com.example.quick-reply",
  "name": "快捷接话",
  "version": "0.1.0",
  "apiVersion": "1",
  "entry": "main.py",
  "defaultEnabled": false,
  "permissions": ["chat.context.read", "chat.draft.write"],
  "contributes": {
    "chatActions": [
      {
        "id": "quick-reply",
        "title": "快捷接话",
        "placement": "composer"
      }
    ]
  }
}
```

`main.py`：

```python
class QuickReplyPlugin:
    def activate(self, host):
        self.host = host

    def deactivate(self):
        self.host = None

    def execute_chat_action(self, action_id, request):
        if action_id != "quick-reply":
            raise ValueError(f"Unknown action: {action_id}")
        context = self.host.read_dialogue_context(
            run_id=request["run_id"],
            session_id=request["session_id"],
            seed_text=request.get("seed_text", ""),
            direction=request.get("direction", ""),
        )
        # 没有 model.invoke 权限时，可以只使用上下文生成确定性结果。
        mode = context.get("mode", "act")
        return {"suggestion": "继续说下去。" if mode != "observe" else "让场景继续推进。"}


def create_plugin():
    return QuickReplyPlugin()
```

## 使用宿主模型

在清单中增加 `model.invoke` 后，可以调用当前开放的模型能力：

```python
context = self.host.read_dialogue_context(
    run_id=request["run_id"],
    session_id=request["session_id"],
    seed_text=request.get("seed_text", ""),
    direction=request.get("direction", ""),
)
suggestion = self.host.invoke_model("dialogue_suggestion", context)
return {"suggestion": suggestion}
```

宿主负责模型配置、密钥、提示词协议、重试和输出解析。插件不应该自行复制这些逻辑。

## 生成临时 NPC

临时 NPC 使用独立的 `temporaryNpcGenerators` 贡献点，并声明 `chat.context.read`、`chat.cast.write`；需要模型时再声明 `model.invoke`。实例实现：

```python
def generate_temporary_npc(self, generator_id, request):
    context = self.host.read_dialogue_context(
        run_id=request["run_id"],
        session_id=request["session_id"],
    )
    npc = self.host.invoke_model("temporary_npc", context)
    return {"npc": npc}
```

插件只生成结构化人物，不能直接读写会话文件。宿主校验后将 NPC、入场描写和首句台词写入当前会话。标准字段为 `name`、`role`、`appearance`、`personality`、`speech_style`、`motive`、`entrance`、`opening_line`。

## 校验、打包与安装

在仓库根目录执行：

```powershell
python scripts/package_plugin.py examples/plugin-template
```

也可以指定输出路径：

```powershell
python scripts/package_plugin.py path/to/my-plugin --output dist/my-plugin.zip
```

打包器会先校验 `plugin.json`、入口文件、API 版本、权限、文件数量、解压大小和包内路径，再生成只含一个插件根目录的 ZIP。Android 插件管理页支持直接选择该 ZIP，也支持选择未打包的插件目录。

安装分为两个阶段：宿主先检查清单并展示权限；用户确认后才写入插件目录。重复的第三方插件 ID 会进入“更新”流程，官方插件 ID 不允许覆盖。不兼容的 `apiVersion` 会显示提示并阻止安装。

安装管理 API：

```text
POST   /api/web/plugins/packages/inspect
POST   /api/web/plugins/packages/{token}/install
DELETE /api/web/plugins/{plugin_id}
```

`inspect` 的请求正文包含 `filename` 和 `content_base64`；`install` 必须提交 `confirm_permissions: true`，更新时还必须提交 `allow_update: true`。

## 运行时管理

聊天页会把已启用插件声明的 `chatActions` 统一显示在独立“插件”菜单中。`placement: "composer"` 不代表插件可以向输入框或发送按钮区域注入任意控件。

Web API：

```text
GET  /api/web/plugins
POST /api/web/plugins/refresh
POST /api/web/plugins/{plugin_id}/enable
POST /api/web/plugins/{plugin_id}/disable
GET  /api/web/plugins/{plugin_id}/logs
GET  /api/web/plugins/{plugin_id}/config
PUT  /api/web/plugins/{plugin_id}/config
POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/actions/{action_id}
POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/npc-generators/{generator_id}
PUT  /api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/enhancers/{enhancer_id}/state
```

典型开发循环：

1. 把插件放入运行数据目录下的 `plugins`。
2. 调用 `refresh`，确认插件被发现且清单没有错误。
3. 调用 `enable`。
4. 修改代码后再次调用 `refresh`；宿主会等待在途调用完成，然后加载新实例。
5. 调用 `disable` 验证清理逻辑。

插件管理页的“日志与详情”会显示最近的发现、启停、动作执行和异常堆栈。日志使用 JSONL 保存并自动轮转；宿主不会把聊天正文或模型密钥写入插件日志。插件配置保存在独立的 `plugin-config.json` 中。

## 声明配置项

清单顶层可声明 `settings`。API v1 支持布尔、限定范围整数和枚举：

```json
{
  "settings": [
    {"key": "optionCount", "title": "候选数量", "type": "integer", "default": 3, "min": 2, "max": 4},
    {"key": "enabledByDefault", "title": "默认开启", "type": "boolean", "default": false},
    {
      "key": "strength",
      "title": "强度",
      "type": "enum",
      "default": "balanced",
      "options": [
        {"value": "light", "label": "轻微"},
        {"value": "balanced", "label": "均衡"},
        {"value": "strong", "label": "强烈"}
      ]
    }
  ]
}
```

宿主校验并保存配置后，会在每次贡献点调用的 `request["config"]` 中注入当前值。插件不能绕过 Schema 保存任意字段。

## 测试建议

至少覆盖：

- 清单可以被 `PluginManifest.load()` 解析。
- 每个动作 ID 都返回字典。
- 空输入和缺失上下文有明确错误。
- 并发调用不共享会话级可变状态。
- `activate → execute → deactivate → activate` 可以重复执行。
- 插件动作不改变会话历史，除非未来明确获得会话写权限。

在仓库内运行：

```powershell
python -m pytest tests/test_plugin_system.py tests/test_web_dialogue_suggestions.py -q
```

完整协议与安全边界见 [`plugin-system-rules.md`](plugin-system-rules.md)。
