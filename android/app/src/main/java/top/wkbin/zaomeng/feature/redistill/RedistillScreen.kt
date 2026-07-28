package top.wkbin.zaomeng.feature.redistill

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.RedistillSegmentDto
import top.wkbin.zaomeng.backend.DistillationForegroundController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedistillScreen(
    viewModel: RedistillViewModel,
    onBack: () -> Unit,
    onStarted: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.loadDocument(uri)
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.submit()
    }

    fun submitRedistill() {
        if (DistillationForegroundController.hasNotificationPermission(context)) {
            viewModel.submit()
        } else {
            notificationPermission.launch(DistillationForegroundController.NOTIFICATION_PERMISSION)
        }
    }

    LaunchedEffect(state.completed) {
        if (state.completed) onStarted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("继续蒸馏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "可以沿用当前正文，换入新的 TXT 书段，或从原文中选择推荐片段。已有资料会作为基线继续补充。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = state.characters,
                    onValueChange = viewModel::updateCharacters,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("本轮人物") },
                    supportingText = { Text("可新增人物，用逗号、顿号或换行分隔。") },
                    minLines = 2,
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("换入新书段", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(
                            onClick = { picker.launch(arrayOf("text/plain", "application/octet-stream")) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.submitting && !state.readingFile,
                        ) {
                            if (state.readingFile) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.UploadFile, contentDescription = null)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                when {
                                    state.readingFile -> "正在读取…"
                                    state.fileName.isBlank() -> "选择 TXT"
                                    else -> state.fileName
                                },
                            )
                        }
                        if (state.fileName.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${state.fileSize / 1024} KB", Modifier.weight(1f))
                                TextButton(onClick = viewModel::clearFile) { Text("移除") }
                            }
                        }
                    }
                }
            }
            item {
                RecommendationHeader(state = state, viewModel = viewModel)
            }
            state.suggestions?.let { suggestions ->
                if (suggestions.weakFieldLabels.isNotEmpty()) {
                    item {
                        Text(
                            "建议优先补充：${suggestions.weakFieldLabels.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(suggestions.segments, key = RedistillSegmentDto::segmentId) { segment ->
                    SegmentCard(
                        segment = segment,
                        selected = state.selectedSegmentId == segment.segmentId,
                        onSelect = { viewModel.selectSegment(segment.segmentId) },
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.maxSentences,
                        onValueChange = viewModel::updateMaxSentences,
                        modifier = Modifier.weight(1f),
                        label = { Text("每批句数") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.maxChars,
                        onValueChange = viewModel::updateMaxChars,
                        modifier = Modifier.weight(1f),
                        label = { Text("每批字符") },
                        singleLine = true,
                    )
                }
            }
            if (state.error.isNotBlank()) {
                item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Button(
                    onClick = ::submitRedistill,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.submitting,
                ) {
                    if (state.submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(if (state.submitting) "正在启动…" else "开始这一轮蒸馏")
                }
            }
        }
    }
}

@Composable
private fun RecommendationHeader(state: RedistillUiState, viewModel: RedistillViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val characters = state.characters
        .split(',', '，', '、', ';', '；', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("从原文推荐片段", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(state.recommendationCharacter.ifBlank { "选择人物" }, Modifier.weight(1f))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    characters.forEach { character ->
                        DropdownMenuItem(
                            text = { Text(character) },
                            onClick = {
                                expanded = false
                                viewModel.selectRecommendationCharacter(character)
                            },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = viewModel::recommendSegments,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.recommendationCharacter.isNotBlank() && !state.recommending,
            ) {
                if (state.recommending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (state.recommending) "正在挑选…" else "推荐三段正文")
            }
        }
    }
}

@Composable
private fun SegmentCard(segment: RedistillSegmentDto, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    "第 ${segment.startSentence}–${segment.endSentence} 句",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(segment.preview)
            Text(
                listOf(segment.reason, segment.estimatedFieldLabels.joinToString("、"))
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
