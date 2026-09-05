package me.bili.unrestrict.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.bili.unrestrict.data.db.AppDatabase
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val logs by db.logDao().getRecentLogsFlow().collectAsState(initial = emptyList())
    val sp = remember { context.getSharedPreferences("module_config", Context.MODE_PRIVATE) }
    val isLoggingEnabled = remember { sp.getBoolean("enable_debug_logging", true) }

    var selectedLevel by remember { mutableStateOf("ALL") }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    val filteredLogs = remember(logs, selectedLevel) {
        if (selectedLevel == "ALL") logs
        else logs.filter { it.level.equals(selectedLevel, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("运行日志", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = "已收录 ${logs.size} 条记录",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = {
                            val text = logs.reversed().joinToString("\n") { log ->
                                "[${timeFormat.format(Date(log.timestamp))}] [${log.level}] [${log.tag}] ${log.message}"
                            }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("logs", text))
                            Toast.makeText(context, "✅ 全部日志已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制全部")
                        }
                        IconButton(onClick = {
                            scope.launch { db.logDao().clearAll() }
                        }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空日志", tint = MaterialTheme.colorScheme.error)
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
            // 1. 日志开关关闭提示横幅
            if (!isLoggingEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "当前「记录运行日志」处于关闭状态，新事件将不会被记录。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 2. 日志级别过滤筛选 Chip 栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val levels = listOf("ALL" to "全部", "INFO" to "INFO", "WARN" to "WARN", "ERROR" to "ERROR", "DEBUG" to "DEBUG")
                levels.forEach { (key, label) ->
                    val isSelected = selectedLevel == key
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedLevel = key },
                        label = {
                            val count = if (key == "ALL") logs.size else logs.count { it.level.equals(key, ignoreCase = true) }
                            Text("$label ($count)", fontSize = 12.sp)
                        }
                    )
                }
            }

            // 3. 日志列表区域
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (logs.isEmpty()) {
                            "暂无运行日志记录\n启动 B 站、执行青少年中和或发评后会在此实时呈现。"
                        } else {
                            "当前级别「$selectedLevel」下暂无日志"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { logItem ->
                        val (levelBg, levelFg) = when (logItem.level.uppercase()) {
                            "ERROR" -> Color(0xFFFFD1D1) to Color(0xFFB00020)
                            "WARN" -> Color(0xFFFFF1C2) to Color(0xFF8A6D00)
                            "DEBUG" -> Color(0xFFE4E4E4) to Color(0xFF555555)
                            else -> Color(0xFFD0E8FF) to Color(0xFF0055B3)
                        }

                        val msgColor = when {
                            logItem.level.equals("ERROR", ignoreCase = true) || logItem.message.contains("❌") -> MaterialTheme.colorScheme.error
                            logItem.message.contains("✅") || logItem.message.contains("🟢") -> Color(0xFF2E7D32)
                            logItem.message.contains("🛡️") || logItem.message.contains("🎯") || logItem.message.contains("🚀") -> Color(0xFF0066CC)
                            logItem.level.equals("WARN", ignoreCase = true) || logItem.message.contains("⚠️") -> Color(0xFFB26B00)
                            else -> MaterialTheme.colorScheme.onSurface
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = levelBg,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = logItem.level.uppercase(),
                                                color = levelFg,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = logItem.tag,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = timeFormat.format(Date(logItem.timestamp)),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = logItem.message,
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 17.sp,
                                    color = msgColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
