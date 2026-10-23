package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.ConversionLogEntity
import com.example.ui.viewmodel.MainViewModel
import com.example.util.FileManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
) {
    val context = LocalContext.current
    val projects by viewModel.projects.collectAsState()
    val selectedProject by viewModel.selectedProject.collectAsState()
    val allLogs by viewModel.logs.collectAsState()

    // Project selection: default to active project, or allows picking any project
    var targetProjectId by remember(selectedProject?.id) {
        mutableStateOf(selectedProject?.id ?: -1L)
    }

    val currentLogs = remember(allLogs, targetProjectId) {
        if (targetProjectId == -1L) allLogs
        else allLogs.filter { it.projectId == targetProjectId }
    }

    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredLogs = remember(currentLogs, selectedFilter) {
        when (selectedFilter) {
            "DOWNLOAD" -> currentLogs.filter { it.status == "DOWNLOAD" }
            "SAVED" -> currentLogs.filter { it.status == "SAVED" || it.status == "MERGED" || !it.filePath.isNullOrBlank() }
            "SUCCESS" -> currentLogs.filter { it.status == "SUCCESS" }
            "ERROR" -> currentLogs.filter { it.status == "ERROR" || it.status == "RATE_LIMIT_429" || it.httpCode >= 400 }
            else -> currentLogs
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth().testTag("logs_header_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Assessment,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "گزارش‌ها و وقایع سیستم",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val targetProjectName = projects.firstOrNull { it.id == targetProjectId }?.name ?: "تمام پروژه‌ها"
                            Text(
                                text = "نمایش رویدادها برای: $targetProjectName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (currentLogs.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.clearLogs(if (targetProjectId != -1L) targetProjectId else null)
                            },
                            modifier = Modifier.testTag("btn_clear_logs")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear logs",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // Project Switcher Chips (Requirement 3: گزارش‌گیری هر پروژه مجزا است)
        if (projects.isNotEmpty()) {
            item {
                Column {
                    Text(
                        text = "تفکیک گزارش به ازای پروژه:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = targetProjectId == -1L,
                                onClick = { targetProjectId = -1L },
                                label = { Text("همه پروژه‌ها (${allLogs.size})", fontSize = 11.sp) },
                                modifier = Modifier.testTag("chip_project_all")
                            )
                        }
                        items(projects) { project ->
                            val isSel = targetProjectId == project.id
                            val count = allLogs.count { it.projectId == project.id }
                            FilterChip(
                                selected = isSel,
                                onClick = { targetProjectId = project.id },
                                label = { Text("${project.name} ($count)", fontSize = 11.sp) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                modifier = Modifier.testTag("chip_project_${project.id}")
                            )
                        }
                    }
                }
            }
        }

        // Filter Category Chips
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text("همه (${currentLogs.size})", fontSize = 11.sp) }
                    )
                }
                item {
                    val count = currentLogs.count { it.status == "DOWNLOAD" }
                    FilterChip(
                        selected = selectedFilter == "DOWNLOAD",
                        onClick = { selectedFilter = "DOWNLOAD" },
                        label = { Text("دانلودها ($count)", fontSize = 11.sp) }
                    )
                }
                item {
                    val count = currentLogs.count { it.status == "SAVED" || it.status == "MERGED" || !it.filePath.isNullOrBlank() }
                    FilterChip(
                        selected = selectedFilter == "SAVED",
                        onClick = { selectedFilter = "SAVED" },
                        label = { Text("ذخیره‌شده‌ها ($count)", fontSize = 11.sp) }
                    )
                }
                item {
                    val count = currentLogs.count { it.status == "SUCCESS" }
                    FilterChip(
                        selected = selectedFilter == "SUCCESS",
                        onClick = { selectedFilter = "SUCCESS" },
                        label = { Text("موفق ($count)", fontSize = 11.sp) }
                    )
                }
                item {
                    val count = currentLogs.count { it.status == "ERROR" || it.status == "RATE_LIMIT_429" || it.httpCode >= 400 }
                    FilterChip(
                        selected = selectedFilter == "ERROR",
                        onClick = { selectedFilter = "ERROR" },
                        label = { Text("خطاها ($count)", fontSize = 11.sp) }
                    )
                }
            }
        }

        if (filteredLogs.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Assessment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "گزارشی در این دسته‌بندی ثبت نشده است.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "با انجام عملیات ساخت، تغییر نام، تبدیل و ادغام، تمام وقایع اینجا ثبت می‌شوند.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        items(filteredLogs, key = { it.id }) { log ->
            LogReportCardItem(
                log = log,
                onOpenFile = { path -> FileManager.openFileOrFolder(context, path) }
            )
        }
    }
}

@Composable
fun LogReportCardItem(
    log: ConversionLogEntity,
    onOpenFile: (String) -> Unit
) {
    val (statusText, statusColor, statusIcon) = resolveLogStatus(log)

    // Extract path from filePath field or parse from message (e.g. "در مسیر /path ذخیره شد")
    val resolvedPath: String? = log.filePath ?: extractPathFromMessage(log.message)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("log_card_${log.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Status Badge, Project Name, and Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (log.chunkIndex > 0) "بخش ${log.chunkIndex} • ${log.projectName}" else log.projectName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = formatLogDateTime(log.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Message Text
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )

            // Key info and latency row (if applicable)
            if (log.keyMasked != "---" && log.keyName.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${log.keyName} (${log.keyMasked})",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (log.latencyMs > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${log.latencyMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Capsule Button for Path Opening (Requirement 3: دکمه کپسولی مینیمال جهت باز کردن مسیر در فایل‌ها)
            if (!resolvedPath.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = resolvedPath.substringAfterLast("/"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clickable { onOpenFile(resolvedPath) }
                            .testTag("capsule_btn_${log.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "باز کردن در فایل‌ها",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun resolveLogStatus(log: ConversionLogEntity): Triple<String, Color, ImageVector> {
    return when (log.status) {
        "DOWNLOAD" -> Triple("دانلود گوگل", Color(0xFF0284C7), Icons.Default.CloudDownload)
        "SAVED" -> Triple("ذخیره فایل", Color(0xFF10B981), Icons.Default.Save)
        "SUCCESS" -> Triple("انجام شد", Color(0xFF10B981), Icons.Default.CheckCircle)
        "MERGED" -> Triple("ادغام فایل‌ها", Color(0xFF8B5CF6), Icons.AutoMirrored.Filled.CallMerge)
        "RATE_LIMIT_429" -> Triple("محدودیت (429)", Color(0xFFF59E0B), Icons.Default.HourglassTop)
        "INFO", "CREATED", "RENAMED" -> Triple("رویداد پروژه", Color(0xFF3B82F6), Icons.Default.Info)
        else -> Triple("خطا (${log.httpCode})", Color(0xFFEF4444), Icons.Default.ErrorOutline)
    }
}

private fun extractPathFromMessage(message: String): String? {
    if (!message.contains("/")) return null
    // Matches path patterns like /storage/... or /data/... or similar absolute paths
    val regex = Regex("(/[^\\s]+)")
    return regex.find(message)?.value
}

private fun formatLogDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
