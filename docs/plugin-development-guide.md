# 造梦插件开发指南

本指南面向插件 API v1。可以直接参考首个官方插件 [`plugins/ai-association`](../plugins/ai-association)。

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

## 运行时管理

Web API：

```text
GET  /api/web/plugins
POST /api/web/plugins/refresh
POST /api/web/plugins/{plugin_id}/enable
POST /api/web/plugins/{plugin_id}/disable
```

典型开发循环：

1. 把插件放入运行数据目录下的 `plugins`。
2. 调用 `refresh`，确认插件被发现且清单没有错误。
3. 调用 `enable`。
4. 修改代码后再次调用 `refresh`；宿主会等待在途调用完成，然后加载新实例。
5. 调用 `disable` 验证清理逻辑。

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
