package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.pref.ThemeMode
import com.example.data.pref.ThemePalette
import com.example.data.vault.ApiKeyItem
import com.example.data.vault.KeyStatus
import com.example.ui.viewmodel.MainViewModel
import com.example.util.FileManager
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val keys by viewModel.keys.collectAsState()

    var showResetDialog by remember { mutableStateOf(false) }

    // Vault Dialog states
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var keyToEdit by remember { mutableStateOf<ApiKeyItem?>(null) }
    var keyToDelete by remember { mutableStateOf<ApiKeyItem?>(null) }

    // Live ticker for countdown timer
    var ticker by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            ticker = System.currentTimeMillis()
        }
    }

    val coolingKeys = keys.filter { it.isCooldownActive }

    // Folder Picker via OpenDocumentTree
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {}

            // Extract displayable folder path
            val pathSegment = uri.path ?: uri.toString()
            val cleanPath = when {
                pathSegment.contains("primary:") -> {
                    val sub = pathSegment.substringAfter("primary:")
                    "/storage/emulated/0/$sub"
                }
                pathSegment.contains(":") -> pathSegment.substringAfter(":")
                else -> pathSegment
            }
            viewModel.userPreferences.updateStorageFolder(cleanPath)
            viewModel.showSnackbar("پوشه مقصد با موفقیت تنظیم شد: $cleanPath")
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "تنظیمات برنامه و گاوصندوق",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "مدیریت کلیدهای امنیتی، پوشه ذخیره، مدل صوتی گوگل و تم ظاهری",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // =========================================================
        // VAULT SECTION (Requirement 2: انتقال کامل گاوصندوق به تنظیمات)
        // =========================================================
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .testTag("vault_settings_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "گاوصندوق کلیدهای API",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "رمزنگاری AES-256 سخت‌افزاری (${keys.size} کلید)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Button(
                            onClick = { showAddKeyDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("btn_vault_add_key")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("کلید جدید", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "سوئیچینگ هوشمند، چرخش دوره‌ای و مدیریت خودکار خطای کوئری ۴۲۹",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Cooldown Notice Banner if active
                    if (coolingKeys.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.HourglassTop,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${coolingKeys.size} کلید در حال خنک‌سازی",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.resetAllCooldowns() },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("لغو خنک‌سازی", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (keys.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "هنوز کلیدی در گاوصندوق ذخیره نشده است.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "برای تبدیل صوت با هوش مصنوعی گوگل، حداقل یک کلید API وارد کنید.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            keys.forEach { item ->
                                VaultKeyRowItem(
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
                }
            }
        }

        // =========================================================
        // STORAGE DIRECTORY SETTING (Requirement 4: رفع باگ مسیر ذخیره و انتخاب پوشه)
        // =========================================================
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("storage_folder_card")
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "پوشه مقصد ذخیره‌سازی فایل‌های صوتی",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "مسیر ذخیره خروجی تمام قطعات و فایل‌های ادغام‌شده",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    var folderText by remember(settings.defaultStorageFolder) {
                        mutableStateOf(settings.defaultStorageFolder)
                    }

                    OutlinedTextField(
                        value = folderText,
                        onValueChange = {
                            folderText = it
                            viewModel.userPreferences.updateStorageFolder(it)
                        },
                        label = { Text("نام یا مسیر پوشه مقصد") },
                        placeholder = { Text("TTS یا مسیر کامل") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("input_storage_folder")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Buttons: Open folder picker and View in file manager
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).testTag("btn_browse_folder")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("انتخاب پوشه با فایل‌ها", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val rootDir = viewModel.fileManager.getRootTtsDirectory(settings.defaultStorageFolder)
                                FileManager.openFileOrFolder(context, rootDir.absolutePath)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).testTag("btn_open_storage_in_files")
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("مشاهده در فایل‌ها", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    val resolvedDir = remember(settings.defaultStorageFolder) {
                        viewModel.fileManager.getRootTtsDirectory(settings.defaultStorageFolder).absolutePath
                    }
                    Text(
                        text = "مسیر فیزیکی فعال در دستگاه:\n$resolvedDir",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // =========================================================
        // GOOGLE TTS MODEL CONFIGURATION
        // =========================================================
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .testTag("tts_model_card")
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "مدل صوتی گوگل (Google TTS Model)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "شناسه مدل در حال ارسال به Gemini Speech API",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    var modelInputText by remember(settings.ttsModelName) {
                        mutableStateOf(settings.ttsModelName)
                    }

                    OutlinedTextField(
                        value = modelInputText,
                        onValueChange = {
                            modelInputText = it
                            viewModel.userPreferences.updateTtsModelName(it)
                        },
                        label = { Text("نام مدل صوتی") },
                        placeholder = { Text("gemini-2.5-flash-preview-tts") },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_tts_model_name")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "مدل استاندارد و پایدار: gemini-2.5-flash-preview-tts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val suggestions = listOf(
                            "gemini-2.5-flash-preview-tts",
                            "gemini-3.8-flash-tts",
                            "gemini-3.5-flash"
                        )
                        suggestions.forEach { modelSuggestion ->
                            val isCurrent = settings.ttsModelName == modelSuggestion
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = if (isCurrent) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        modelInputText = modelSuggestion
                                        viewModel.updateTtsModel(modelSuggestion)
                                    }
                            ) {
                                Text(
                                    text = modelSuggestion.replace("gemini-", ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // =========================================================
        // THEME MODE SECTION (System, Light, Dark)
        // =========================================================
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("theme_mode_card")
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BrightnessAuto,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "حالت تم (تم تیره / روشن)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val modes = listOf(
                        ThemeMode.SYSTEM to "پیروی از حالت سیستم (خودکار)",
                        ThemeMode.LIGHT to "لایت مود (روشن)",
                        ThemeMode.DARK to "دارک مود (تاریک)"
                    )

                    modes.forEach { (mode, title) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.setThemeMode(mode) }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.testTag("radio_mode_${mode.name.lowercase()}")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = when (mode) {
                                    ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                    ThemeMode.LIGHT -> Icons.Default.LightMode
                                    ThemeMode.DARK -> Icons.Default.DarkMode
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (settings.themeMode == mode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // =========================================================
        // THEME PALETTES SECTION
        // =========================================================
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("theme_palette_card")
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "پالت رنگی تم (۵ پالت متنوع)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ThemePalette.values().forEach { palette ->
                        val isSelected = settings.themePalette == palette
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setThemePalette(palette) }
                                .testTag("palette_item_${palette.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color(palette.primaryHex))
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = palette.persianTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }

                                if (isSelected) {
                                    Text(
                                        text = "فعال",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================
        // RESET TO DEFAULTS
        // =========================================================
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "بازنشانی به تنظیمات کارخانه",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "تنظیمات تم، اندازه بخش‌بندی و گوینده پیش‌فرض را به حالت اولیه برمی‌گرداند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("btn_reset_defaults")
                    ) {
                        Text("بازنشانی به تنظیمات پیش‌فرض")
                    }
                }
            }
        }
    }

    // Add Key Dialog
    if (showAddKeyDialog) {
        var keyName by remember { mutableStateOf("") }
        var rawKey by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddKeyDialog = false },
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
                        modifier = Modifier.fillMaxWidth().testTag("input_key_name")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = rawKey,
                        onValueChange = { rawKey = it },
                        label = { Text("کلید اختصاصی API (AIzaSy...)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("input_raw_key")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rawKey.isNotBlank()) {
                            viewModel.addApiKey(keyName.trim(), rawKey.trim())
                            showAddKeyDialog = false
                        }
                    },
                    modifier = Modifier.testTag("btn_save_new_key")
                ) {
                    Text("ذخیره امن")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddKeyDialog = false }) {
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
                        modifier = Modifier.fillMaxWidth().testTag("input_edit_key_name")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editRawKey,
                        onValueChange = { editRawKey = it },
                        label = { Text("کلید جدید (اختیاری)") },
                        placeholder = { Text("برای حفظ کلید قبلی، خالی بگذارید") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("input_edit_raw_key")
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

    // Delete Key Confirmation Dialog
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

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("بازنشانی تنظیمات", fontWeight = FontWeight.Bold) },
            text = { Text("آیا مایلید تمام تنظیمات برنامه به حالت پیش‌فرض اولیه بازنشانی شوند؟") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetSettingsToDefault()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("btn_confirm_reset")
                ) {
                    Text("بله، بازنشانی کن")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
fun VaultKeyRowItem(
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
        !item.isEnabled -> Triple("خاموش", MaterialTheme.colorScheme.outline, Icons.Default.PowerSettingsNew)
        isCooling -> Triple("خنک‌سازی: ${remainingSec}s", Color(0xFFF59E0B), Icons.Default.HourglassTop)
        item.status == KeyStatus.INVALID -> Triple("نامعتبر", MaterialTheme.colorScheme.error, Icons.Default.Warning)
        else -> Triple("سالم", Color(0xFF10B981), Icons.Default.CheckCircle)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (item.isEnabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, if (isCooling) Color(0xFFF59E0B).copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth().testTag("key_row_${item.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = if (item.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.maskedKey,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = statusText, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Switch(
                        checked = item.isEnabled,
                        onCheckedChange = { onToggleEnabled() },
                        modifier = Modifier.testTag("switch_key_${item.id}")
                    )
                }
            }

            // Actions row: Edit, Delete, Reset Cooldown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }

                if (isCooling) {
                    FilledTonalButton(
                        onClick = onResetCooldown,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("لغو خنک‌سازی", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
