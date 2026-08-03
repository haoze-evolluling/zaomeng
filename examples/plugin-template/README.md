# 快捷接话示例插件

这是造梦插件 API v1 的官方最小模板。修改 `plugin.json` 中的反向域名 ID、名称和版本，再实现 `main.py` 中的动作即可。

在仓库根目录打包：

```powershell
python scripts/package_plugin.py examples/plugin-template
```

生成的 `dist/com.example.quick-reply-0.1.0.zip` 可在 Android 的“设置 → 插件 → 安装插件”中导入。安装前应用会显示插件申请的权限。
