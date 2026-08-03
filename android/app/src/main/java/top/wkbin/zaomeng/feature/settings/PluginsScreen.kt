package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.ui.theme.AppDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    viewModel: PluginsViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.load(refresh = true) },
                        enabled = !state.refreshing && state.busyPluginId.isBlank(),
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新插件")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            ) {
                item {
                    PluginIntroductionCard()
                }
                if (state.error.isNotBlank()) {
                    item { StatusCard(state.error, error = true) }
                }
                if (state.message.isNotBlank()) {
                    item { StatusCard(state.message, error = false) }
                }
                if (state.plugins.isEmpty()) {
                    item {
                        Text(
                            "当前没有发现插件。把插件放入运行目录后点击右上角刷新。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.plugins, key = PluginDto::id) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            busy = state.busyPluginId == plugin.id,
                            interactionsEnabled = state.busyPluginId.isBlank() && !state.refreshing,
                            onEnabledChange = { enabled -> viewModel.setEnabled(plugin, enabled) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginIntroductionCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppDimens.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.Extension, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("扩展造梦能力", fontWeight = FontWeight.SemiBold)
                Text(
                    "插件可以为聊天增加新的动作。API v1 插件会在应用进程内运行，只安装你信任的第三方插件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginDto,
    busy: Boolean,
    interactionsEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppDimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (plugin.source == "official") "官方" else "第三方",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (plugin.source == "official") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "v${plugin.version} · API ${plugin.apiVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = plugin.enabled,
                        onCheckedChange = onEnabledChange,
                        enabled = interactionsEnabled,
                    )
                }
            }
            if (plugin.description.isNotBlank()) {
                Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (plugin.contributes.chatActions.isNotEmpty()) {
                Text(
                    "聊天动作：${plugin.contributes.chatActions.joinToString("、") { it.title }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plugin.contributes.generationEnhancers.isNotEmpty()) {
                Text(
                    "聊天生成增强：${plugin.contributes.generationEnhancers.joinToString("、") { it.title }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "具体开关在各聊天的“插件”菜单中设置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plugin.permissions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("权限", style = MaterialTheme.typography.labelMedium)
                    Text(
                        plugin.permissions.joinToString(" · ") { it.permissionLabel() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plugin.error.isNotBlank()) {
                Text(
                    plugin.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(message: String, error: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun String.permissionLabel(): String = when (this) {
    "chat.context.read" -> "读取聊天上下文"
    "chat.draft.write" -> "写入聊天草稿"
    "generation.enhance" -> "增强回复生成"
    "model.invoke" -> "调用模型"
    "storage.read" -> "读取插件存储"
    "storage.write" -> "写入插件存储"
    "network.access" -> "访问网络"
    else -> this
}
