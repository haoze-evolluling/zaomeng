package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.BuildConfig
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型设置") },
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

        val selectedCatalog = modelCatalogs.firstOrNull { it.id == state.selectedCatalogId }
        val usesBuiltInConnection = selectedCatalog != null && selectedCatalog.id != "custom"
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item { SettingsSectionTitle("模型档案", topPadding = 16.dp) }
            item {
                SettingsGroup {
                    if (state.profiles.isEmpty()) {
                        SettingsRow(
                            title = "暂无模型档案",
                            subtitle = "填写下方配置并保存，即可创建一个档案。",
                        )
                    } else {
                        state.profiles.forEachIndexed { index, profile ->
                            if (index > 0) SettingsDivider()
                            ModelProfileRow(
                                profile = profile,
                                selected = profile.profileId == state.selectedProfileId,
                                active = profile.profileId == state.activeProfileId,
                                onSelect = { viewModel.selectProfile(profile) },
                            )
                        }
                    }
                    SettingsDivider()
                    SettingsRow(
                        title = "添加模型档案",
                        subtitle = "为另一套模型服务保存独立配置。",
                        leadingIcon = Icons.Default.Add,
                        enabled = !state.saving,
                        onClick = viewModel::beginCreateProfile,
                    )
                }
            }

            item { SettingsSectionTitle("模型服务") }
            item {
                SettingsGroup {
                    modelCatalogs.forEachIndexed { index, catalog ->
                        if (index > 0) SettingsDivider()
                        ModelCatalogRow(
                            catalog = catalog,
                            selected = state.selectedCatalogId == catalog.id,
                            enabled = !state.saving,
                            onSelect = { viewModel.selectModelCatalog(catalog) },
                        )
                    }
                }
            }

            item { SettingsSectionTitle("当前配置") }
            item {
                SettingsGroup {
                    SettingsField(
                        value = state.profileName,
                        onValueChange = viewModel::updateProfileName,
                        label = "配置名称",
                        placeholder = "例如：DeepSeek 写作",
                    )
                    if (!usesBuiltInConnection) {
                        SettingsDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("服务商", style = MaterialTheme.typography.bodyLarge)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                providers.take(2).forEach { (value, label) ->
                                    FilterChip(
                                        selected = state.provider == value,
                                        onClick = { viewModel.updateProvider(value) },
                                        label = { Text(label) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                providers.drop(2).forEach { (value, label) ->
                                    FilterChip(
                                        selected = state.provider == value,
                                        onClick = { viewModel.updateProvider(value) },
                                        label = { Text(label) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    SettingsDivider()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        ModelNameField(state, viewModel::selectCatalogModel, viewModel::updateModel)
                    }
                    if (!usesBuiltInConnection) {
                        SettingsDivider()
                        SettingsField(
                            value = state.baseUrl,
                            onValueChange = viewModel::updateBaseUrl,
                            label = "接口地址",
                            placeholder = "留空使用服务商默认地址",
                            supportingText = if (state.provider == "ollama") "127.0.0.1 代表手机自身；电脑上的 Ollama 请填写局域网地址。" else null,
                        )
                    }
                    if (state.provider != "ollama") {
                        SettingsDivider()
                        SettingsField(
                            value = state.apiKey,
                            onValueChange = viewModel::updateApiKey,
                            label = "API Key",
                            placeholder = if (state.apiKeyConfigured) "已保存；留空继续沿用" else "输入模型服务密钥",
                            supportingText = "密钥由 Android Keystore 加密保护，不显示在档案列表或诊断信息中。",
                            password = true,
                        )
                    }
                }
            }

            if (state.error.isNotBlank() || state.message.isNotBlank()) {
                item {
                    Text(
                        text = state.error.ifBlank { state.message },
                        color = if (state.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        modifier = Modifier.weight(1f),
                        enabled = !state.saving && !state.testing,
                    ) {
                        if (state.testing) ProgressIcon()
                        Text(if (state.testing) "测试中…" else "测试连接")
                    }
                    Button(
                        onClick = viewModel::save,
                        modifier = Modifier.weight(1f),
                        enabled = !state.saving && !state.testing,
                    ) {
                        if (state.saving) ProgressIcon()
                        Text(if (state.saving) "保存中…" else if (state.creatingProfile) "创建并启用" else "保存")
                    }
                }
            }
            if (!state.creatingProfile && state.selectedProfileId.isNotBlank()) {
                item {
                    TextButton(
                        onClick = viewModel::deleteSelectedProfile,
                        enabled = !state.saving,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("删除当前档案")
                    }
                }
            }

        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String, topPadding: androidx.compose.ui.unit.Dp = 24.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, top = topPadding, bottom = 8.dp, end = 32.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(content = { content() }) }
}

@Composable
private fun SettingsDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val modifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leadingIcon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supportingText: String? = null,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true,
    )
}

@Composable
private fun ProgressIcon() {
    CircularProgressIndicator(
        modifier = Modifier.padding(end = 8.dp).height(18.dp),
        strokeWidth = 2.dp,
    )
}

@Composable
internal fun AppUpdateRow(state: SettingsUiState, onCheck: () -> Unit, onDownload: () -> Unit) {
    Column {
        SettingsRow(
            title = "应用更新",
            subtitle = state.availableUpdate?.let { "发现新版本 ${it.version}" }
                ?: state.updateError.ifBlank { state.updateMessage.ifBlank { "当前版本 ${BuildConfig.VERSION_NAME} · GitHub Release" } },
            value = if (state.checkingUpdate) "检查中" else "检查",
            enabled = !state.checkingUpdate && !state.downloadingUpdate,
            onClick = onCheck,
        )
        state.availableUpdate?.let { update ->
            SettingsDivider()
            SettingsRow(
                title = "下载 ${update.version}",
                subtitle = update.releaseNotes.takeIf { it.isNotBlank() },
                value = if (state.downloadingUpdate) "已开始" else "下载",
                enabled = !state.downloadingUpdate,
                onClick = onDownload,
            )
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
            value = state.model, onValueChange = onUpdateModel, modifier = Modifier.fillMaxWidth(),
            label = { Text("模型名称") }, placeholder = { Text("例如 deepseek-v4-flash / gpt-5-mini") }, singleLine = true,
        )
        return
    }
    var expanded by remember(catalog.id) { mutableStateOf(false) }
    val selectedLabel = catalog.models.firstOrNull { it.id == state.model }?.title ?: state.model
    Text("模型名称", style = MaterialTheme.typography.bodyLarge)
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedLabel, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("选择")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            catalog.models.forEach { model ->
                DropdownMenuItem(text = { Text(model.title) }, onClick = { onSelectModel(model.id); expanded = false })
            }
        }
    }
}

@Composable
private fun ModelCatalogRow(catalog: ModelCatalog, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.clickable(enabled = enabled, onClick = onSelect).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderLogo(catalog.id)
        Column(Modifier.weight(1f)) {
            Text(catalog.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (catalog.id == "custom") "手动填写接口与模型参数" else catalog.models.firstOrNull()?.title.orEmpty(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
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
private fun ModelProfileRow(profile: ModelProfileDto, selected: Boolean, active: Boolean, onSelect: () -> Unit) {
    SettingsRow(
        title = profile.name.ifBlank { profile.model },
        subtitle = "${profile.provider} · ${profile.model}",
        value = when { active -> "当前"; selected -> "已选择"; profile.configured -> "可用"; else -> "未完成" },
        onClick = onSelect,
    )
}
