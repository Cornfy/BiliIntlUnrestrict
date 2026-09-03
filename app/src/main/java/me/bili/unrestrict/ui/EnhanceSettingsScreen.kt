package me.bili.unrestrict.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bili.unrestrict.ui.theme.iOSGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhanceSettingsScreen(onNavigateToLogs: () -> Unit) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("module_config", Context.MODE_PRIVATE) }
    var bypassTeenager by remember { mutableStateOf(sp.getBoolean("bypass_teenager_mode", true)) }
    var debugLogEnabled by remember { mutableStateOf(sp.getBoolean("enable_debug_logging", true)) }

    fun notifyBiliConfigChange(key: String, value: Boolean) {
        try {
            val intent = Intent("me.bili.unrestrict.ACTION_UPDATE_CONFIG").apply {
                setPackage("com.bilibili.app.in")
                putExtra(key, value)
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("功能增强", fontWeight = FontWeight.Bold) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "🔒 隐私与合规增强",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    SwitchPreferenceItem(
                        icon = Icons.Outlined.Shield,
                        title = "解除异常青少年模式限制",
                        description = "自动中和 B 站国际版 COPPA 限制，免手持身份证直接恢复成人满血体验",
                        checked = bypassTeenager,
                        onCheckedChange = { isChecked ->
                            bypassTeenager = isChecked
                            sp.edit().putBoolean("bypass_teenager_mode", isChecked).apply()
                            notifyBiliConfigChange("bypass_teenager_mode", isChecked)
                        }
                    )
                }
            }

            item {
                Text(
                    text = "🛠️ 调试与诊断",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        SwitchPreferenceItem(
                            icon = Icons.Outlined.BugReport,
                            title = "记录运行日志",
                            description = "记录模块在 B 站内的拦截、探测与通信报文，便于排查异常",
                            checked = debugLogEnabled,
                            onCheckedChange = { isChecked ->
                                debugLogEnabled = isChecked
                                sp.edit().putBoolean("enable_debug_logging", isChecked).apply()
                                notifyBiliConfigChange("enable_debug_logging", isChecked)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        // 点击进入日志页面
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToLogs() }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "查看运行日志",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "查看最近捕获到的发评响应与存活探测详情",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "🚀 即将支持 (路线图预览)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column {
                        ComingSoonItem(
                            icon = Icons.Outlined.Link,
                            title = "净化视频分享链接 (开发中)",
                            description = "自动去除 B 站复制链接中的追踪参数 (buvid/mid)，还原干净短链"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        ComingSoonItem(
                            icon = Icons.Outlined.Refresh,
                            title = "首页刷新保留历史视频流 (开发中)",
                            description = "下拉刷新时不清除上一批视频，追加保留浏览历史"
                        )
                    }
                }
            }

            item {
                Text(
                    text = "ℹ️ 模块状态",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = iOSGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("BiliIntlUnrestrict", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("架构: LibXposed 102", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("引擎: biliSendCheck 双重视角反诈探针", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("作用域: com.bilibili.app.in", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchPreferenceItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ComingSoonItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}
