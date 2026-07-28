package top.wkbin.zaomeng.feature.relations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.RelationItemDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelationsScreen(
    viewModel: RelationsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人物关系校对") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load, enabled = !state.loading && !state.refreshing) {
                        if (state.refreshing) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新关系")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> LoadingRelations(Modifier.padding(innerPadding))
            state.details == null -> MissingRelations(
                message = state.error.ifBlank { "暂时没有找到关系资料。" },
                onRetry = viewModel::load,
                modifier = Modifier.padding(innerPadding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    val details = requireNotNull(state.details)
                    Text(
                        "共 ${details.relationCount} 组关系${if (details.conflictCount > 0) "，${details.conflictCount} 组需要留意" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.error.isNotBlank() || state.message.isNotBlank()) {
                    item {
                        Notice(
                            text = state.error.ifBlank { state.message },
                            isError = state.error.isNotBlank(),
                            onDismiss = viewModel::dismissNotice,
                        )
                    }
                }
                if (state.details?.items.isNullOrEmpty()) {
                    item {
                        Text(
                            "这份书卷还没有关系明细。完成关系抽取后会显示在这里。",
                            modifier = Modifier.padding(vertical = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(requireNotNull(state.details).items, key = RelationItemDto::pairKey) { item ->
                        RelationEditor(
                            item = item,
                            saving = state.savingPairKey == item.pairKey,
                            enabled = state.savingPairKey.isBlank(),
                            onChange = { change -> viewModel.updateItem(item.pairKey, change) },
                            onSave = { viewModel.save(item.pairKey) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationEditor(
    item: RelationItemDto,
    saving: Boolean,
    enabled: Boolean,
    onChange: ((RelationItemDto) -> RelationItemDto) -> Unit,
    onSave: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.characters.joinToString("、").ifBlank { item.pairKey },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (item.relationshipType.isNotBlank()) {
                        Text(item.relationshipType, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Button(onClick = onSave, enabled = enabled) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Save, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (saving) "保存中" else "保存")
                }
            }

            RelationMetric("信任", item.trust, enabled) { value ->
                onChange { it.copy(trust = value) }
            }
            RelationMetric("情感", item.affection, enabled) { value ->
                onChange { it.copy(affection = value) }
            }
            RelationMetric("敌意", item.hostility, enabled) { value ->
                onChange { it.copy(hostility = value) }
            }
            RelationMetric("暧昧", item.ambiguity, enabled) { value ->
                onChange { it.copy(ambiguity = value) }
            }

            RelationField("关系类型", item.relationshipType, enabled) { value ->
                onChange { it.copy(relationshipType = value) }
            }
            RelationField("互动摘要", item.typicalInteraction, enabled) { value ->
                onChange { it.copy(typicalInteraction = value) }
            }
            RelationField("冲突点", item.conflictPoint, enabled) { value ->
                onChange { it.copy(conflictPoint = value) }
            }
            RelationField("关系变化", item.relationChange, enabled) { value ->
                onChange { it.copy(relationChange = value) }
            }

            if (item.evidenceLines.isNotEmpty()) {
                Text("证据句", style = MaterialTheme.typography.labelLarge)
                item.evidenceLines.take(6).forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RelationMetric(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value.toString(), fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 10)) },
            valueRange = 0f..10f,
            steps = 9,
            enabled = enabled,
        )
    }
}

@Composable
private fun RelationField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        minLines = if (label == "关系类型") 1 else 2,
        maxLines = if (label == "关系类型") 1 else 4,
    )
}

@Composable
private fun Notice(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun LoadingRelations(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun MissingRelations(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("重试") }
        }
    }
}
