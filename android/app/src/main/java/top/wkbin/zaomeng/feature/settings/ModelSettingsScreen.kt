package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("故事声源", style = MaterialTheme.typography.titleMedium)
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

            item {
                OutlinedTextField(
                    value = state.model,
                    onValueChange = viewModel::updateModel,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型名称") },
                    placeholder = { Text("例如 deepseek-chat / gpt-4.1") },
                    singleLine = true,
                )
            }

            item {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::updateBaseUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("接口地址") },
                    placeholder = { Text("留空使用服务商默认地址") },
                    supportingText = {
                        if (state.provider == "ollama") {
                            Text("手机上的 127.0.0.1 指手机自身；电脑上的 Ollama 请填写局域网地址。")
                        }
                    },
                    singleLine = true,
                )
            }

            item {
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    placeholder = {
                        Text(if (state.apiKeyConfigured) "已保存；留空继续沿用" else "输入模型服务密钥")
                    },
                    supportingText = { Text("密钥只保存在应用的本地服务目录，不写入界面偏好。") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }

            item {
                OutlinedTextField(
                    value = state.maxTokens,
                    onValueChange = viewModel::updateMaxTokens,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("单次输出上限") },
                    placeholder = { Text("0 或留空表示使用默认值") },
                    singleLine = true,
                )
            }

            if (state.error.isNotBlank()) {
                item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            }
            if (state.message.isNotBlank()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(state.message, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            item {
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.saving,
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp).height(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (state.saving) "保存中…" else "保存模型配置")
                }
            }
        }
    }
}
