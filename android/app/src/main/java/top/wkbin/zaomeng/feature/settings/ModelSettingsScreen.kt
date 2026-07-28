package top.wkbin.zaomeng.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.R
import top.wkbin.zaomeng.data.api.ModelProfileDto

private val providers = listOf(
    "openai-compatible" to "通用接口",
    "openai" to "OpenAI",
    "anthropic" to "Anthropic",
    "ollama" to "Ollama",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val diagnosticsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportDiagnostics(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在读取模型配置…")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val selectedCatalog = modelCatalogs.firstOrNull { it.id == state.selectedCatalogId }
            val usesBuiltInConnection = selectedCatalog != null && selectedCatalog.id != "custom"
            item { ChatDisplaySettingsCard() }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("模型档案", style = MaterialTheme.typography.titleMedium)
                        Text("每套接口、模型和密钥独立保存在本机。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = viewModel::beginCreateProfile, enabled = !state.saving) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加")
                    }
                }
            }
            if (state.profiles.isEmpty()) {
                item { Text("还没有模型档案；填写下方内容并保存即可创建。") }
            } else {
                items(state.profiles, key = ModelProfileDto::profileId) { profile ->
                    ModelProfileCard(
                        profile = profile,
                        selected = profile.profileId == state.selectedProfileId,
                        active = profile.profileId == state.activeProfileId,
                        onSelect = { viewModel.selectProfile(profile) },
                    )
                }
            }
            item {
                Text("模型服务", style = MaterialTheme.typography.titleMedium)
            }
            items(
                modelCatalogs.chunked(2),
                key = { row -> row.joinToString(separator = "-") { it.id } },
            ) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { catalog ->
                        ModelCatalogCard(
                            catalog = catalog,
                            selected = state.selectedCatalogId == catalog.id,
                            enabled = !state.saving,
                            onSelect = { viewModel.selectModelCatalog(catalog) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (!state.creatingProfile && state.selectedProfileId.isNotBlank()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::activateSelectedProfile,
                            enabled = state.selectedProfileId != state.activeProfileId && !state.switching,
                        ) {
                            Text(if (state.switching) "切换中…" else "启用此模型")
                        }
                        TextButton(onClick = viewModel::deleteSelectedProfile, enabled = !state.saving) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Text("删除")
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.profileName,
                    onValueChange = viewModel::updateProfileName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("配置名称") },
                    placeholder = { Text("例如：DeepSeek 写作") },
                    singleLine = true,
                )
            }
            if (!usesBuiltInConnection) {
                item {
                    Text("服务商", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        providers.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (value, label) ->
                                    FilterChip(
                                        selected = state.provider == value,
                                        onClick = { viewModel.updateProvider(value) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { ModelNameField(state, viewModel::selectCatalogModel, viewModel::updateModel) }
            if (!usesBuiltInConnection) {
                item {
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::updateBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("接口地址") },
                        placeholder = { Text("留空使用服务商默认地址") },
                        supportingText = {
                            if (state.provider == "ollama") {
                                Text("127.0.0.1 代表手机自身；电脑上的 Ollama 请填写局域网地址。")
                            }
                        },
                        singleLine = true,
                    )
                }
            }
            if (state.provider != "ollama") {
                item {
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = viewModel::updateApiKey,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        placeholder = { Text(if (state.apiKeyConfigured) "已保存；留空继续沿用" else "输入模型服务密钥") },
                        supportingText = { Text("密钥由 Android Keystore 加密保护，不显示在档案列表或诊断信息中。") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            }
            if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            if (state.message.isNotBlank()) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(state.message, modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        modifier = Modifier.weight(1f),
                        enabled = !state.saving && !state.testing,
                    ) {
                        if (state.testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp).height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(if (state.testing) "测试中…" else "测试连接")
                    }
                    Button(
                        onClick = viewModel::save,
                        modifier = Modifier.weight(1f),
                        enabled = !state.saving && !state.testing,
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp).height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(if (state.saving) "保存中…" else if (state.creatingProfile) "创建并启用" else "保存")
                    }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.BugReport, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("运行诊断", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "导出启动自检、任务状态与模型连接摘要；不包含 API Key、小说正文或聊天内容。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { diagnosticsLauncher.launch("zaomeng-diagnostics.json") },
                            enabled = !state.exportingDiagnostics,
                        ) {
                            Text(if (state.exportingDiagnostics) "导出中" else "导出")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelNameField(
    state: SettingsUiState,
    onSelectModel: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
) {
    val catalog = modelCatalogs.firstOrNull { it.id == state.selectedCatalogId }
    if (catalog == null || catalog.models.isEmpty()) {
        OutlinedTextField(
            value = state.model,
            onValueChange = onUpdateModel,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型名称") },
            placeholder = { Text("例如 deepseek-v4-flash / gpt-5-mini") },
            singleLine = true,
        )
        return
    }
    var expanded by remember(catalog.id) { mutableStateOf(false) }
    val selectedLabel = catalog.models.firstOrNull { it.id == state.model }?.title ?: state.model
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("模型名称", style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("选择")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                catalog.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.title) },
                        onClick = {
                            onSelectModel(model.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCatalogCard(
    catalog: ModelCatalog,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onSelect,
        enabled = enabled,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(catalog.id)
                Spacer(Modifier.width(9.dp))
                Text(
                    catalog.title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProviderLogo(catalogId: String) {
    when (catalogId) {
        "deepseek" -> Image(painterResource(R.drawable.ic_provider_deepseek), null, Modifier.height(24.dp))
        "qwen" -> Image(painterResource(R.drawable.ic_provider_qwen), null, Modifier.height(24.dp))
        "mimo" -> Image(painterResource(R.drawable.ic_provider_mimo), null, Modifier.height(24.dp))
        "anthropic" -> Image(painterResource(R.drawable.ic_provider_anthropic), null, Modifier.height(24.dp))
        "openai" -> Image(painterResource(R.drawable.ic_provider_openai), null, Modifier.height(24.dp))
        "ollama" -> Image(painterResource(R.drawable.ic_provider_ollama), null, Modifier.height(24.dp))
        else -> Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelProfileCard(
    profile: ModelProfileDto,
    selected: Boolean,
    active: Boolean,
    onSelect: () -> Unit,
) {
    Card(onClick = onSelect) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name.ifBlank { profile.model }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (active) Text("当前", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                if (selected && !active) Text("已选择", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
            }
            Text("${profile.provider} · ${profile.model}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (profile.apiKeyConfigured || profile.provider == "ollama") "可用" else "缺少 API Key",
                style = MaterialTheme.typography.labelSmall,
                color = if (profile.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}
