package com.example.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.vault.ApiKeyItem
import com.example.data.vault.KeyStatus
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun VaultScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
) {
    val keys by viewModel.keys.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var keyToEdit by remember { mutableStateOf<ApiKeyItem?>(null) }
    var keyToDelete by remember { mutableStateOf<ApiKeyItem?>(null) }

    // Live ticker to update countdown timer in real-time
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            ticker = System.currentTimeMillis()
        }
    }

    val coolingKeys = keys.filter { it.isCooldownActive }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(bottom = 70.dp)
                    .testTag("fab_add_key")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Key")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("کلید جدید", fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Security Shield Banner
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .testTag("vault_security_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "گاوصندوق امنیتی سخت‌افزاری (Keystore)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "کلیدها با الگوریتم AES-256-GCM در چیپ امنیتی اندروید رمزنگاری شده و برای حفاظت از حریم شما به صورت ماسک‌شده (ab****cd) نمایش می‌یابند.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // Cooldown Notification Banner (if any keys are cooling down)
            if (coolingKeys.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HourglassTop,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "${coolingKeys.size} کلید در دوره خنک‌سازی هستند",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "صف تبدیل به صورت هوشمند منتظر پایان زمان خنک‌سازی می‌ماند و خطا نمی‌دهد.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = { viewModel.resetAllCooldowns() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("لغو همه خنک‌سازی‌ها", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Keys Count and Status Summary
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "کلیدهای ذخیره شده (${keys.size} کلید)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "چرخش گردشی خودکار کلیدها و سوئیچینگ هوشمند در صورت لیمیت",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (keys.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth().animateContentSize()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "گاوصندوق خالی است!",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "برای استفاده از سامانه، حداقل یک کلید API جمینای گوگل را با زدن دکمه «کلید جدید» اضافه کنید.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(keys, key = { it.id }) { item ->
                ApiKeyCardItem(
                    item = item,
                    ticker = ticker,
                    onToggleEnabled = { viewModel.toggleKeyEnabled(item.id) },
                    onEdit = { keyToEdit = item },
                    onResetCooldown = { viewModel.resetKeyCooldown(item.id) },
                    onDelete = { keyToDelete = item }
                )
            }
        }
    }

    // Add Key Dialog
    if (showAddDialog) {
        var keyName by remember { mutableStateOf("") }
        var rawKey by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("افزودن کلید جدید به گاوصندوق", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = keyName,
                        onValueChange = { keyName = it },
                        label = { Text("نام یا شناسه کلید (مثلاً: کلید اصلی گوگل)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_key_name")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = rawKey,
                        onValueChange = { rawKey = it },
                        label = { Text("کلید اختصاصی API (AIzaSy...)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_raw_key")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rawKey.isNotBlank()) {
                            viewModel.addApiKey(keyName.trim(), rawKey.trim())
                            showAddDialog = false
                        }
                    },
                    modifier = Modifier.testTag("btn_save_new_key")
                ) {
                    Text("ذخیره امن")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }

    // Edit Key Dialog
    keyToEdit?.let { item ->
        var editName by remember { mutableStateOf(item.name) }
        var editRawKey by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { keyToEdit = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("ویرایش کلید API", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("نام کلید") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_edit_key_name")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editRawKey,
                        onValueChange = { editRawKey = it },
                        label = { Text("کلید جدید (در صورت تمایل به تغییر)") },
                        placeholder = { Text("برای حفظ کلید قبلی، خالی بگذارید") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_edit_raw_key")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank()) {
                            viewModel.updateApiKey(item.id, editName.trim(), editRawKey.trim().ifBlank { null })
                            keyToEdit = null
                        }
                    },
                    modifier = Modifier.testTag("btn_save_edit_key")
                ) {
                    Text("ذخیره تغییرات")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { keyToEdit = null }) {
                    Text("انصراف")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    keyToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { keyToDelete = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("حذف کلید «${item.name}»", fontWeight = FontWeight.Bold) },
            text = { Text("آیا از حذف این کلید از گاوصندوق اطمینان دارید؟") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteApiKey(item.id)
                        keyToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("btn_confirm_delete_key")
                ) {
                    Text("بله، حذف کن")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { keyToDelete = null }) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
fun ApiKeyCardItem(
    item: ApiKeyItem,
    ticker: Long,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onResetCooldown: () -> Unit,
    onDelete: () -> Unit
) {
    val isCooling = item.isCooldownActive
    val remainingSec = item.remainingCooldownSeconds

    val (statusText, statusColor, statusIcon) = when {
        !item.isEnabled -> Triple("خاموش (غیرفعال)", MaterialTheme.colorScheme.outline, Icons.Default.PowerSettingsNew)
        isCooling -> Triple("خنک‌سازی: $remainingSec ثانیه مانده", Color(0xFFF59E0B), Icons.Default.HourglassTop)
        item.status == KeyStatus.INVALID -> Triple("نامعتبر", MaterialTheme.colorScheme.error, Icons.Default.Warning)
        else -> Triple("سالم و آماده", Color(0xFF10B981), Icons.Default.CheckCircle)
    }

    val containerColor = if (item.isEnabled) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            1.dp,
            if (isCooling) Color(0xFFF59E0B).copy(alpha = 0.6f)
            else if (item.isEnabled) MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (item.isEnabled) 2.dp else 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("key_card_${item.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Name and Status Badge + On/Off Icon Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = if (isCooling) Color(0xFFF59E0B) else if (item.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(14.dp)
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

                    // ON/OFF Icon Switch
                    Switch(
                        checked = item.isEnabled,
                        onCheckedChange = { onToggleEnabled() },
                        thumbContent = {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = if (item.isEnabled) "خاموش کردن کلید" else "روشن کردن کلید",
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.testTag("switch_toggle_key_${item.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Masked Key Display (ab****cd requirement)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "کلید رمزنگاری شده:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = item.maskedKey,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons: Edit, Delete, and Cooldown Reset if active
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(36.dp).testTag("btn_edit_key_${item.id}")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp).testTag("btn_delete_key_${item.id}")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }

                if (isCooling) {
                    FilledTonalButton(
                        onClick = onResetCooldown,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(36.dp).testTag("btn_reset_cooldown_${item.id}")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("لغو خنک‌سازی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
