# 造梦插件系统规则（API v1）

本文定义插件与造梦宿主之间的稳定边界。API v1 的目标是支持可发现、可审计、可在运行时启停的聊天动作插件，同时为后续隔离进程协议保留兼容空间。

## 1. 基本原则

1. 宿主拥有会话、人物包、模型密钥和持久化数据；插件只能通过宿主能力访问它们。
2. 插件必须先声明贡献点和权限，未声明的宿主能力调用会失败。
3. 插件输出必须是 JSON 可序列化数据，不得把宿主内部对象带出调用边界。
4. 插件 ID、动作 ID 和已发布数据字段一经发布不得改变语义。
5. 单个插件失败不得改变会话历史，也不得阻止其他插件加载。
6. API v1 插件是受信任的进程内 Python 代码。权限系统提供能力收口与审计，不是操作系统安全沙箱。只安装来源可信的 v1 插件。

## 2. 目录与发现

宿主按顺序扫描两个位置：

- 官方插件：应用包内 `src/builtin_plugins/<plugin-directory>/plugin.json`
- 用户插件：运行数据目录 `plugins/<plugin-directory>/plugin.json`

每个插件占用一个独立目录。一个插件必须包含 `plugin.json` 和清单所指向的 Python 入口文件。插件 ID 在所有发现目录中必须唯一；重复 ID 不参与加载。

## 3. 清单协议

```json
{
  "id": "com.example.my-plugin",
  "name": "示例插件",
  "version": "1.0.0",
  "apiVersion": "1",
  "entry": "main.py",
  "description": "一句话说明插件用途。",
  "defaultEnabled": false,
  "permissions": [
    "chat.context.read",
    "chat.draft.write"
  ],
  "contributes": {
    "chatActions": [
      {
        "id": "my-action",
        "title": "执行示例",
        "icon": "sparkles",
        "placement": "composer"
      }
    ]
  }
}
```

约束：

- `id` 使用反向域名风格的小写标识，例如 `com.example.my-plugin`。
- `apiVersion` 必须等于宿主支持的主版本；v1 当前只接受字符串 `"1"`。
- `entry` 必须是插件目录内的相对 `.py` 路径，禁止绝对路径和 `..`。
- `chatActions[].id` 在插件内唯一；完整动作名是 `<plugin-id>/<action-id>`。
- `placement` 可取 `composer`、`message` 或 `tools`。
- `composer` 表示动作出现在聊天页的独立“插件”分类中；插件不得直接占用输入框或发送按钮区域。
- 提供聊天动作时必须声明 `chat.context.read` 和 `chat.draft.write`。

## 4. API v1 权限

| 权限 | 含义 | v1 状态 |
|---|---|---|
| `chat.context.read` | 读取宿主裁剪后的当前聊天上下文 | 已实现 |
| `chat.draft.write` | 返回可写入输入框的动作结果 | 已实现并作为聊天动作必需声明 |
| `generation.enhance` | 在回复生成前返回受限的生成选项 | 已实现；用于有状态生成增强贡献点 |
| `model.invoke` | 通过宿主代理调用允许的模型能力 | 已实现；当前开放 `dialogue_suggestion`、`dialogue_reply_variants` |
| `storage.read` | 读取插件名字空间存储 | 已保留，尚未开放宿主方法 |
| `storage.write` | 写入插件名字空间存储 | 已保留，尚未开放宿主方法 |
| `network.access` | 访问清单声明的外部域名 | 已保留；v1 不提供网络代理 |

插件不得要求用户把模型密钥写入插件配置。模型调用必须经过宿主代理。

## 5. 生命周期与热插拔

生命周期顺序如下：

1. `refresh`：重新扫描并严格校验全部清单。
2. `enable`：加载全新的模块实例，调用 `create_plugin()`，然后调用可选的 `activate(host)`。
3. `invoke`：按贡献点调用插件。聊天动作调用 `execute_chat_action(action_id, request)`。
4. `disable`：停止接收新调用，等待在途调用完成，调用可选的 `deactivate()`，卸载模块引用。
5. 再次 `refresh`：已启用插件会以新模块实例重新加载，因而可以应用代码更新而无需重启 Web 服务。

启用状态保存在运行数据目录的 `plugin-state.json`。首次发现时使用 `defaultEnabled`；用户显式启停后，以保存状态为准。

插件可在清单顶层使用 `settings` 声明 `boolean`、`integer` 或 `enum` 配置。宿主负责类型与范围校验，值保存在 `plugin-config.json`，并以只读快照注入贡献点请求的 `config` 字段。配置不是权限机制，也不得用于收集模型密钥。

`generationEnhancers` 另有聊天级状态。插件管理中的启用/停用只决定贡献点是否可用；聊天中的开关按 `run_id + session_id + plugin_id + enhancer_id` 保存。停用插件会立即让所有聊天中的增强失效，但不会删除各聊天原有选择，重新启用后可以恢复。

## 6. 聊天动作契约

入口模块必须导出：

```python
def create_plugin():
    return MyPlugin()
```

有 `chatActions` 贡献时，实例必须实现：

```python
def execute_chat_action(self, action_id: str, request: dict) -> dict:
    ...
```

调用结果必须是字典且可 JSON 序列化。用于输入框草稿的标准结果为：

```json
{"suggestion": "一段可以直接发送的文本"}
```

需要用户选择的动作可以返回候选列表：

```json
{"suggestions": [{"label": "克制回应", "suggestion": "一段可以直接发送的文本"}]}
```

插件不得在生成草稿的过程中直接修改会话。若将来需要写入会话，必须使用单独的宿主命令和对应写权限。

## 6.1 生成增强契约

清单可以声明有状态生成增强：

```json
{
  "permissions": ["generation.enhance"],
  "contributes": {
    "generationEnhancers": [
      {
        "id": "inner-thoughts",
        "title": "角色读心",
        "defaultActive": false
      }
    ]
  }
}
```

插件实例必须实现 `enhance_generation(enhancer_id, request)` 并返回 JSON 对象。宿主负责保存聊天级开关、合并允许的生成选项，并在插件失败时退回普通生成。

## 7. 错误、并发与兼容性

- 清单错误：跳过该插件，其他插件继续发现。
- 激活错误：插件进入 `error` 状态并暴露错误信息。
- 动作错误：只终止当前动作，由 API 层转换为用户可理解的错误。
- 宿主记录发现、启停、调用结果和异常堆栈，日志不得包含聊天正文、模型密钥或完整请求负载。
- 同一插件必须假设多个会话可能并发调用；不要把某个会话的数据保存在插件实例字段中。
- `deactivate()` 必须幂等、快速且不抛异常。
- 新增可选清单字段属于向后兼容；删除字段、收紧输出协议或改变权限语义需要提升 `apiVersion`。

## 8. 安全演进

API v2 计划把第三方插件移动到独立进程，通过版本化 JSON-RPC 调用同名宿主能力。v1 插件若只使用 `activate(host)`、`execute_chat_action()` 和 JSON 数据边界，可平滑迁移。插件不应导入 `src.web.*` 或读取造梦内部文件。
