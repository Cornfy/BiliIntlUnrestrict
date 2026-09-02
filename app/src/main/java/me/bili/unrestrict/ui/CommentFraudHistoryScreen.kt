package me.bili.unrestrict.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.bili.unrestrict.data.db.CommentFraudRecord
import me.bili.unrestrict.data.model.CommentFraudStatus
import me.bili.unrestrict.data.repository.CommentFraudRepository
import me.bili.unrestrict.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentFraudHistoryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allRecords by CommentFraudRepository.getAllRecordsFlow(context).collectAsState(initial = emptyList())

    var selectedFilter by remember { mutableStateOf<CommentFraudStatus?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var recordToDeleteOnBili by remember { mutableStateOf<CommentFraudRecord?>(null) }
    var recheckingRpid by remember { mutableStateOf<Long?>(null) }
    var deletingRpid by remember { mutableStateOf<Long?>(null) } // 👈 删评中状态

    val filteredRecords = remember(allRecords, selectedFilter) {
        if (selectedFilter == null) allRecords else allRecords.filter { it.fraudStatus == selectedFilter }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (!content.isNullOrBlank()) {
                        val result = CommentFraudRepository.importFromJson(context, content)
                        result.onSuccess { count ->
                            Toast.makeText(context, "✅ 成功导入 $count 条历史记录！", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(context, "❌ 导入失败: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "❌ 读取文件失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = CommentFraudRepository.exportToJson(context)
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                    Toast.makeText(context, "✅ 成功导出 JSON 备份文件！", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "❌ 导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发评反诈历史", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { importLauncher.launch("application/json") }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "导入备份")
                    }
                    IconButton(onClick = {
                        val fileName = "biliSendCheck_backup_${System.currentTimeMillis()}.json"
                        exportLauncher.launch(fileName)
                    }) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = "导出备份")
                    }
                    if (allRecords.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空全部", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (allRecords.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                            label = { Text("全部 (${allRecords.size})") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == CommentFraudStatus.NORMAL,
                            onClick = { selectedFilter = if (selectedFilter == CommentFraudStatus.NORMAL) null else CommentFraudStatus.NORMAL },
                            label = { Text("🟢 正常") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == CommentFraudStatus.SHADOW_BANNED,
                            onClick = { selectedFilter = if (selectedFilter == CommentFraudStatus.SHADOW_BANNED) null else CommentFraudStatus.SHADOW_BANNED },
                            label = { Text("🔴 仅自己可见") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == CommentFraudStatus.DELETED,
                            onClick = { selectedFilter = if (selectedFilter == CommentFraudStatus.DELETED) null else CommentFraudStatus.DELETED },
                            label = { Text("⚫ 已失效") }
                        )
                    }
                }
            }

            if (filteredRecords.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (allRecords.isEmpty()) "暂无发评记录" else "无匹配状态的评论",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "发出的评论将在此处沉淀并追踪全生命周期状态",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredRecords, key = { it.rpid }) { record ->
                        CommentFraudItemCard(
                            record = record,
                            isRechecking = recheckingRpid == record.rpid,
                            isDeleting = deletingRpid == record.rpid, // 👈 补上传入参数
                            onRecheck = {
                                scope.launch {
                                    recheckingRpid = record.rpid
                                    val res = CommentFraudRepository.recheckRecord(context, record)
                                    recheckingRpid = null
                                    res.onSuccess { status ->
                                        Toast.makeText(context, "复检完成: $status", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, "复检失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onCopyMessage = {
                                copyText(context, record.message)
                                Toast.makeText(context, "✅ 文案已复制！", Toast.LENGTH_SHORT).show()
                            },
                            onCopyScheme = {
                                val scheme = generateBiliUrlScheme(record)
                                copyText(context, scheme)
                                Toast.makeText(context, "✅ Scheme 已复制！", Toast.LENGTH_SHORT).show()
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(scheme)))
                                } catch (_: Exception) {}
                            },
                            onDeleteLocal = {
                                scope.launch { CommentFraudRepository.deleteLocalRecord(context, record.rpid) }
                            },
                            onDeleteBili = {
                                recordToDeleteOnBili = record
                            }
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空所有反诈记录？", fontWeight = FontWeight.Bold) },
            text = { Text("这将会删除本地保存的历史发评快照，此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { CommentFraudRepository.clearAllRecords(context) }
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    recordToDeleteOnBili?.let { record ->
        AlertDialog(
            onDismissRequest = { recordToDeleteOnBili = null },
            title = { Text("在 B 站永久删除此评论？", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = { Text("这将会调用 B 站官方接口彻底抹除这条评论，并同时清理本地记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = record
                        recordToDeleteOnBili = null
                        scope.launch {
                            deletingRpid = target.rpid
                            val res = CommentFraudRepository.deleteBiliComment(context, target)
                            deletingRpid = null
                            res.onSuccess {
                                Toast.makeText(context, "✅ 删评成功！", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, "❌ 删评失败: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { recordToDeleteOnBili = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 手风琴折叠全息卡片
 */
@Composable
private fun CommentFraudItemCard(
    record: CommentFraudRecord,
    isRechecking: Boolean,
    isDeleting: Boolean,
    onRecheck: () -> Unit,
    onCopyMessage: () -> Unit,
    onCopyScheme: () -> Unit,
    onDeleteLocal: () -> Unit,
    onDeleteBili: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    val postTimeStr = remember(record.post_time) {
        if (record.post_time > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(record.post_time))
        else "发评时间未记录"
    }

    val checkTimeStr = remember(record.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
    }

    val status = record.fraudStatus
    val (statusLabel, statusColor) = remember(status) {
        when (status) {
            CommentFraudStatus.NORMAL -> "评论正常" to iOSGreen
            CommentFraudStatus.SHADOW_BANNED -> "仅自己可见" to iOSRed
            CommentFraudStatus.DELETED -> "已失效" to Color.DarkGray
            CommentFraudStatus.INVISIBLE -> "软屏蔽" to iOSOrange
            CommentFraudStatus.UNDER_REVIEW -> "审核中" to iOSOrange
            CommentFraudStatus.UNKNOWN -> "状态未知" to Color.Gray
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // --- 顶部栏：时间 + OID + 状态徽章 ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = postTimeStr,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = record.source_id ?: "OID: ${record.oid}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 状态药丸徽章
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- 评论正文 (点击快速复制) ---
            Text(
                text = record.message,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { onCopyMessage() }
            )

            // --- 展开后的全息元数据区块 ---
            if (isExpanded) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow(label = "当前状态", value = statusLabel, valueColor = statusColor) {
                        TextButton(
                            onClick = onRecheck,
                            enabled = !isRechecking && !isDeleting,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(if (isRechecking) "更新中…" else "【更新状态】", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    record.initial_status?.let {
                        DetailRow(label = "初始状态", value = CommentFraudStatus.parse(it).name)
                    }

                    DetailRow(label = "风控定性", value = record.fraudAssessment, valueColor = statusColor)

                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(label = "评论区源 ID", value = record.source_id ?: "无")
                    DetailRow(label = "评论区 OID", value = "${record.oid}")
                    DetailRow(label = "评论区类型", value = record.typeName)

                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(label = "用户 UID", value = if (record.uid > 0L) "${record.uid}" else "未记录")
                    DetailRow(label = "评论 RPID", value = "${record.rpid}")

                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(label = "楼层 Root ID", value = if (record.root > 0L) "${record.root}" else "0 (根评论)")
                    DetailRow(label = "目标 Parent ID", value = if (record.parent > 0L) "${record.parent}" else "0")

                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(label = "发送日期", value = postTimeStr)
                    DetailRow(label = "检查日期", value = checkTimeStr)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- 底部操作栏 ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onRecheck,
                        enabled = !isRechecking && !isDeleting,
                        colors = ButtonDefaults.textButtonColors(contentColor = iOSBlue)
                    ) {
                        Text(if (isRechecking) "复检中…" else "🔄 复检", fontSize = 12.sp)
                    }

                    TextButton(
                        onClick = onCopyScheme,
                        enabled = !isDeleting
                    ) {
                        Text("📱 Scheme", fontSize = 12.sp)
                    }

                    TextButton(
                        onClick = onDeleteLocal,
                        enabled = !isDeleting
                    ) {
                        Text("❌ 移除", fontSize = 12.sp)
                    }

                    TextButton(
                        onClick = onDeleteBili,
                        enabled = !isDeleting && !isRechecking,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (isDeleting) "删除中…" else "🗑️ 删评", fontSize = 12.sp)
                    }
                }

                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "收起" else "展开详情",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = "$label：",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                fontSize = 12.sp,
                color = valueColor,
                fontWeight = FontWeight.Normal
            )
        }
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
}

private fun generateBiliUrlScheme(record: CommentFraudRecord): String {
    val rootId = if (record.root > 0L) record.root else 0L
    return if (record.type == 1) {
        if (rootId > 0L) "bilibili://video/${record.oid}/?comment_root_id=${rootId}&comment_secondary_id=${record.rpid}"
        else "bilibili://video/${record.oid}/?comment_root_id=${record.rpid}"
    } else {
        if (rootId > 0L) "bilibili://comment/detail/${record.type}/${record.oid}/${rootId}?anchor=${record.rpid}"
        else "bilibili://comment/detail/${record.type}/${record.oid}/${record.rpid}"
    }
}
