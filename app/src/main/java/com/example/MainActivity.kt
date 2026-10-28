package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AudioPlayerBar
import com.example.ui.components.RestartPromptDialog
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.theme.AvaTtsTheme
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable

enum class AppTab(val title: String, val icon: ImageVector, val tag: String) {
    STUDIO("استودیو", Icons.Default.Audiotrack, "nav_studio"),
    PROJECTS("پروژه‌ها", Icons.Default.Folder, "nav_projects"),
    LOGS("گزارش‌ها", Icons.Default.History, "nav_logs"),
    SETTINGS("تنظیمات", Icons.Default.Settings, "nav_settings")
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsState()
            val showRestartDialog by viewModel.showRestartDialog.collectAsState()
            val snackbarMessage by viewModel.snackbarMessage.collectAsState()
            val playerState by viewModel.playerState.collectAsState()
            val keys by viewModel.keys.collectAsState()

            var currentTab by remember { mutableStateOf(AppTab.STUDIO) }
            val snackbarHostState = remember { SnackbarHostState() }
            val saveableStateHolder = rememberSaveableStateHolder()

            // Notification permission request for Android 13+ (POST_NOTIFICATIONS)
            // BUG-17: رد مجوز نوتیفیکیشن دیگر بی‌صدا نمی‌ماند — راهنمای یک‌باره + دکمهٔ تنظیمات
            val notificationScope = rememberCoroutineScope()
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val prefs = getSharedPreferences("avatts_notification_perm", Context.MODE_PRIVATE)
                    val hintShown = prefs.getBoolean("denied_hint_shown", false)
                    // اگر کاربر هنوز می‌تواند دوباره پرسیده شود، راهنما در هر رد شدن می‌آید؛
                    // در غیر این صورت فقط یک‌بار (تا اذیت نشود)
                    val canAskAgain = shouldShowRequestPermissionRationale(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                    if (canAskAgain || !hintShown) {
                        prefs.edit().putBoolean("denied_hint_shown", true).apply()
                        notificationScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "بدون دسترسی نوتیفیکیشن، روند تبدیل و اعلان پایان صف دیده نمی‌شود.",
                                actionLabel = "تنظیمات",
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                val intent = Intent(
                                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                ).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                    // EXTRA_APP_UID عضو پنهان Settings است و در SDK عمومی وجود ندارد؛ خود PACKAGE برای ACTION_APP_NOTIFICATION_SETTINGS کافی است
                                }
                                startActivity(intent)
                            }
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            // Hoisted scroll states to preserve scroll position across tab switching (Requirement 5)
            val studioListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val vaultListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val logsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val settingsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

            // Back button handling: if on secondary tab, return to Studio first
            BackHandler(enabled = currentTab != AppTab.STUDIO) {
                currentTab = AppTab.STUDIO
            }

            // Snackbar listener
            LaunchedEffect(snackbarMessage) {
                snackbarMessage?.let {
                    snackbarHostState.showSnackbar(it)
                    viewModel.dismissSnackbar()
                }
            }

            // BUG-34: خطاهای پخش‌کننده (مثل فایل گم‌شده) هرگز بی‌پیام نمی‌مانند
            LaunchedEffect(playerState.error) {
                playerState.error?.let {
                    snackbarHostState.showSnackbar(it)
                    viewModel.clearPlayerError()
                }
            }

            // Provide RTL layout for Persian language support
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AvaTtsTheme(
                    palette = settings.themePalette,
                    themeMode = settings.themeMode
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Audiotrack,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "AvaTTS",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                },
                                actions = {
                                    // Vault key quick badge in top bar
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { currentTab = AppTab.SETTINGS }
                                            .testTag("topbar_vault_badge")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Key,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "${keys.size} کلید",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        },
                        bottomBar = {
                            Column {
                                // Sticky Audio Player Bar if audio loaded
                                if (playerState.isLoaded || playerState.currentAudioPath != null) {
                                    AudioPlayerBar(
                                        playerState = playerState,
                                        onPlayPause = { viewModel.togglePlayPause() },
                                        onSeek = { viewModel.seekPlayer(it) },
                                        onClose = { viewModel.closePlayer() }
                                    )
                                }

                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 6.dp,
                                    modifier = Modifier.testTag("main_bottom_nav")
                                ) {
                                    AppTab.values().forEach { tab ->
                                        val isSelected = currentTab == tab
                                        NavigationBarItem(
                                            selected = isSelected,
                                            onClick = { currentTab = tab },
                                            modifier = Modifier.testTag(tab.tag),
                                            icon = {
                                                if (tab == AppTab.SETTINGS && keys.isEmpty()) {
                                                    BadgedBox(badge = { Badge { Text("!") } }) {
                                                        Icon(tab.icon, contentDescription = tab.title)
                                                    }
                                                } else {
                                                    Icon(tab.icon, contentDescription = tab.title)
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = tab.title,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            saveableStateHolder.SaveableStateProvider(currentTab) {
                                when (currentTab) {
                                    AppTab.STUDIO -> StudioScreen(viewModel = viewModel, listState = studioListState)
                                    AppTab.PROJECTS -> ProjectsScreen(
                                        viewModel = viewModel,
                                        onNavigateToStudio = { currentTab = AppTab.STUDIO }
                                    )
                                    AppTab.LOGS -> LogsScreen(viewModel = viewModel, listState = logsListState)
                                    AppTab.SETTINGS -> SettingsScreen(viewModel = viewModel, listState = settingsListState)
                                }
                            }
                        }
                    }

                    // Theme Change Restart Prompt Dialog
                    if (showRestartDialog) {
                        RestartPromptDialog(
                            onDismiss = { viewModel.dismissRestartDialog() }
                        )
                    }
                }
            }
        }
    }
}
