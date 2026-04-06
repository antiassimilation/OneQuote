package com.example.onequote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.onequote.data.model.FavoriteQuote
import com.example.onequote.data.model.LayoutMode
import com.example.onequote.data.model.WidgetClickAction
import com.example.onequote.data.model.WidgetStyleConfig
import com.example.onequote.data.repo.QuoteRepository
import com.example.onequote.data.util.AppDebugLogger
import com.example.onequote.data.util.StyleParsers
import com.example.onequote.scheduler.RefreshScheduler
import com.example.onequote.ui.theme.OneQuoteTheme
import com.example.onequote.widget.OneQuoteWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as OneQuoteApp).repository
        enableEdgeToEdge()
        setContent {
            OneQuoteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(
                        repository = repository,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onToast = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    repository: QuoteRepository,
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit
) {
    // 功能模块：设置、收藏与权限引导。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.observeSettings().collectAsState(initial = null)

    var sourceType by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }
    var sourceAppKey by remember { mutableStateOf("") }
    var bgRgba by remember { mutableStateOf("0.0.0.140") }
    var textRgba by remember { mutableStateOf("255.255.255.255") }
    var authorRgba by remember { mutableStateOf("220.220.220.255") }
    var layoutMode by remember { mutableStateOf(LayoutMode.HORIZONTAL) }
    var fontPercent by remember { mutableStateOf(100f) }
    var cornerLevel by remember { mutableStateOf(4f) }
    var shadowLevel by remember { mutableStateOf(2f) }
    var singleClickAction by remember { mutableStateOf(WidgetClickAction.REFRESH) }
    var doubleClickAction by remember { mutableStateOf(WidgetClickAction.COPY) }
    var autoMinutesText by remember { mutableStateOf("30") }
    var showSavedPreview by remember { mutableStateOf(false) }
    var showFavoritesPage by remember { mutableStateOf(false) }
    var pendingExportCsv by remember { mutableStateOf<String?>(null) }
    var pendingExportLog by remember { mutableStateOf<String?>(null) }
    var permissionQueueEnabled by remember { mutableStateOf(false) }
    var currentPermissionStep by remember { mutableStateOf(PermissionGuideStep.NONE) }
    var runAutoStartStep: (() -> Unit)? = null
    var runStorageStep: (() -> Unit)? = null

    val batteryOptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onToast("省电策略引导已返回")
        AppDebugLogger.log("MainActivity", "permission_result battery_returned")
        if (!permissionQueueEnabled) return@rememberLauncherForActivityResult
        runAutoStartStep?.invoke()
    }
    val autoStartLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onToast("自启动设置页已返回")
        AppDebugLogger.log("MainActivity", "permission_result autostart_returned")
        if (!permissionQueueEnabled) return@rememberLauncherForActivityResult
        runStorageStep?.invoke()
    }
    val storageSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onToast(if (hasStoragePermission(context)) "存储权限已可用" else "存储权限仍未授予")
        AppDebugLogger.log("MainActivity", "permission_result storage_settings_returned granted=${hasStoragePermission(context)}")
        if (permissionQueueEnabled) {
            permissionQueueEnabled = false
            currentPermissionStep = PermissionGuideStep.NONE
        }
    }
    val runtimeStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        onToast(if (granted) "存储权限已授予" else "存储权限被拒绝")
        AppDebugLogger.log("MainActivity", "permission_result storage_runtime granted=$granted detail=$grants")
        if (permissionQueueEnabled) {
            permissionQueueEnabled = false
            currentPermissionStep = PermissionGuideStep.NONE
        }
    }

    runAutoStartStep = {
        currentPermissionStep = PermissionGuideStep.AUTO_START
        onToast("申请自启动权限：用于保证自动刷新")
        AppDebugLogger.log("MainActivity", "permission_request autostart")
        launchAutoStartSettings(context, autoStartLauncher::launch, onToast)
    }
    runStorageStep = {
        currentPermissionStep = PermissionGuideStep.STORAGE
        onToast("申请存储权限：用于收藏夹导入导出")
        AppDebugLogger.log("MainActivity", "permission_request storage")
        requestStoragePermission(
            context = context,
            requestRuntime = runtimeStoragePermissionLauncher::launch,
            requestManageAllFiles = storageSettingsLauncher::launch,
            onToast = onToast
        )
    }

    LaunchedEffect(Unit) {
        if (markFirstLaunchPermissionGuide(context)) {
            permissionQueueEnabled = true
            currentPermissionStep = PermissionGuideStep.BATTERY
            onToast("申请省电策略权限：用于保证自动刷新")
            AppDebugLogger.log("MainActivity", "permission_request battery(first_launch)")
            launchIgnoreBatteryOptimization(context, batteryOptLauncher::launch, onToast)
            return@LaunchedEffect
        }

        if (consumeWidgetRefreshAutoStartGuideFlag(context)) {
            AppDebugLogger.log("MainActivity", "permission_request autostart(from_widget_refresh)")
            onToast("检测到小组件刷新：建议开启自启动权限以提升后台刷新可达性")
            launchAutoStartSettings(context, autoStartLauncher::launch, onToast)
        }
    }

    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                val importedText = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                if (importedText.isNullOrBlank()) {
                    AppDebugLogger.log("MainActivity", "import_csv_rejected empty_file")
                    withContext(Dispatchers.Main) { onToast("不支持该文件") }
                    return@runCatching
                }
                val summary = repository.importFavoritesFromCsv(importedText)
                withContext(Dispatchers.Main) {
                    if (summary.unsupportedFile) {
                        AppDebugLogger.log("MainActivity", "import_csv_rejected unsupported invalid=${summary.invalidCount}")
                        onToast("不支持该文件")
                    } else {
                        AppDebugLogger.log("MainActivity", "import_csv_success imported=${summary.importedCount} duplicated=${summary.duplicatedCount}")
                        onToast("导入完成：新增${summary.importedCount}，重复${summary.duplicatedCount}")
                    }
                }
                if (!summary.unsupportedFile) {
                    OneQuoteWidgetProvider.refreshAll(context)
                }
            }.onFailure {
                AppDebugLogger.log("MainActivity", "import_csv_exception=${it.message}")
                scope.launch(Dispatchers.Main) {
                    onToast("不支持该文件")
                }
            }
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csvText = pendingExportCsv
        pendingExportCsv = null
        if (uri == null || csvText.isNullOrBlank()) return@rememberLauncherForActivityResult

        scope.launch(Dispatchers.IO) {
            val success = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(csvText)
                }
            }.isSuccess
            withContext(Dispatchers.Main) {
                AppDebugLogger.log("MainActivity", "export_csv_result success=$success")
                onToast(if (success) "导出成功" else "导出失败")
            }
        }
    }

    val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val logText = pendingExportLog
        pendingExportLog = null
        if (uri == null || logText.isNullOrBlank()) return@rememberLauncherForActivityResult

        scope.launch(Dispatchers.IO) {
            val success = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(logText)
                }
            }.isSuccess
            withContext(Dispatchers.Main) {
                AppDebugLogger.log("MainActivity", "export_log_result success=$success")
                onToast(if (success) "日志导出成功" else "日志导出失败")
            }
        }
    }

    LaunchedEffect(settings?.savedPreviewVersion) {
        settings?.let {
            bgRgba = it.style.backgroundRgba
            textRgba = it.style.textRgba
            authorRgba = it.style.authorRgba
            layoutMode = it.style.layoutMode
            fontPercent = it.style.fontScalePercent.toFloat()
            cornerLevel = it.style.cornerRadiusLevel.toFloat()
            shadowLevel = it.style.shadowLevel.toFloat()
            singleClickAction = it.singleClickAction
            doubleClickAction = it.doubleClickAction
            autoMinutesText = it.autoRefreshMinutes.toString()
        }
    }

    if (showFavoritesPage) {
        FavoritesScreen(
            favorites = settings?.favorites.orEmpty(),
            onBack = { showFavoritesPage = false },
            onImport = {
                if (!hasStoragePermission(context)) {
                    requestStoragePermission(
                        context = context,
                        requestRuntime = runtimeStoragePermissionLauncher::launch,
                        requestManageAllFiles = storageSettingsLauncher::launch,
                        onToast = onToast
                    )
                    return@FavoritesScreen
                }
                if (!canOpenDocument(context)) {
                    onToast("当前系统不支持导入功能")
                    return@FavoritesScreen
                }
                importCsvLauncher.launch(arrayOf("text/*", "text/csv", "application/csv"))
            },
            onExport = {
                if (!hasStoragePermission(context)) {
                    requestStoragePermission(
                        context = context,
                        requestRuntime = runtimeStoragePermissionLauncher::launch,
                        requestManageAllFiles = storageSettingsLauncher::launch,
                        onToast = onToast
                    )
                    return@FavoritesScreen
                }
                if (!canCreateDocument(context)) {
                    onToast("当前系统不支持导出功能")
                    return@FavoritesScreen
                }
                scope.launch(Dispatchers.IO) {
                    pendingExportCsv = repository.exportFavoritesAsCsv()
                    withContext(Dispatchers.Main) {
                        exportCsvLauncher.launch("onequote_favorites.csv")
                    }
                }
            },
            onDelete = { favoriteId ->
                scope.launch(Dispatchers.IO) {
                    repository.removeFavorite(favoriteId)
                }
            }
        )
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("OneQuote 设置", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showFavoritesPage = true }) { Text("收藏") }
            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    AppDebugLogger.log("MainActivity", "user_export_log_clicked")
                    pendingExportLog = AppDebugLogger.dump()
                    withContext(Dispatchers.Main) {
                        exportLogLauncher.launch("onequote_debug_log.txt")
                    }
                }
            }) { Text("导出日志") }
        }

        Text("权限与系统限制")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onToast("申请省电策略权限：用于保证自动刷新")
                AppDebugLogger.log("MainActivity", "permission_request battery(manual)")
                launchIgnoreBatteryOptimization(context, batteryOptLauncher::launch, onToast)
            }) { Text("省电策略") }
            Button(onClick = {
                onToast("申请自启动权限：用于保证自动刷新")
                AppDebugLogger.log("MainActivity", "permission_request autostart(manual)")
                launchAutoStartSettings(context, autoStartLauncher::launch, onToast)
            }) { Text("自启动") }
            Button(onClick = {
                onToast("申请存储权限：用于收藏夹导入导出")
                AppDebugLogger.log("MainActivity", "permission_request storage(manual)")
                requestStoragePermission(
                    context = context,
                    requestRuntime = runtimeStoragePermissionLauncher::launch,
                    requestManageAllFiles = storageSettingsLauncher::launch,
                    onToast = onToast
                )
            }) { Text("存储权限") }
        }

        Text("句子来源")
        settings?.sources?.forEach { source ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${source.typeName} | ${source.url}")
                    if (source.tempDisabled) {
                        Text("已停用，需手动重新勾选", color = Color.Red)
                    }
                }
                Row {
                    Checkbox(
                        checked = source.enabled,
                        onCheckedChange = { checked ->
                            scope.launch(Dispatchers.IO) {
                                if (checked) {
                                    val result = repository.testAndEnableSource(source.id)
                                    if (result.isFailure) {
                                        withContext(Dispatchers.Main) {
                                            onToast("首次测试失败：该来源不可用")
                                        }
                                    }
                                } else {
                                    repository.disableSource(source.id)
                                }
                                OneQuoteWidgetProvider.refreshAll(context)
                            }
                        }
                    )
                    Button(onClick = { scope.launch(Dispatchers.IO) { repository.removeSource(source.id) } }) {
                        Text("删")
                    }
                }
            }
        }

        OutlinedTextField(sourceType, { sourceType = it }, label = { Text("类型") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourceUrl, { sourceUrl = it }, label = { Text("地址") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourceAppKey, { sourceAppKey = it }, label = { Text("appkey") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (sourceType.isBlank() || sourceUrl.isBlank() || sourceAppKey.isBlank()) {
                onToast("来源信息不完整")
            } else {
                scope.launch(Dispatchers.IO) {
                    repository.addSource(sourceType, sourceUrl, sourceAppKey)
                    sourceType = ""
                    sourceUrl = ""
                    sourceAppKey = ""
                }
            }
        }) { Text("新增来源") }

        Spacer(Modifier.height(6.dp))
        Text("样式配置（保存后显示示例框）")
        OutlinedTextField(bgRgba, { bgRgba = it }, label = { Text("组件 RGBA") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(textRgba, { textRgba = it }, label = { Text("文本 RGBA") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(authorRgba, { authorRgba = it }, label = { Text("作者 RGBA") }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { layoutMode = LayoutMode.HORIZONTAL }) { Text("横向") }
            Button(onClick = { layoutMode = LayoutMode.VERTICAL }) { Text("竖向") }
        }

        Text("组件单击行为")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetActionButton("刷新", singleClickAction == WidgetClickAction.REFRESH) {
                singleClickAction = WidgetClickAction.REFRESH
            }
            WidgetActionButton("复制", singleClickAction == WidgetClickAction.COPY) {
                singleClickAction = WidgetClickAction.COPY
            }
            WidgetActionButton("收藏", singleClickAction == WidgetClickAction.FAVORITE) {
                singleClickAction = WidgetClickAction.FAVORITE
            }
        }

        Text("组件双击行为")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetActionButton("刷新", doubleClickAction == WidgetClickAction.REFRESH) {
                doubleClickAction = WidgetClickAction.REFRESH
            }
            WidgetActionButton("复制", doubleClickAction == WidgetClickAction.COPY) {
                doubleClickAction = WidgetClickAction.COPY
            }
            WidgetActionButton("收藏", doubleClickAction == WidgetClickAction.FAVORITE) {
                doubleClickAction = WidgetClickAction.FAVORITE
            }
        }

        Text("字号比例: ${fontPercent.toInt()}%（100%=18sp）")
        Slider(fontPercent, { fontPercent = it }, valueRange = 0f..200f, steps = 19)
        Text("圆角级别: ${cornerLevel.toInt()}")
        Slider(cornerLevel, { cornerLevel = it }, valueRange = 0f..10f, steps = 9)
        Text("阴影级别: ${shadowLevel.toInt()}")
        Slider(shadowLevel, { shadowLevel = it }, valueRange = 0f..10f, steps = 9)

        OutlinedTextField(
            value = autoMinutesText,
            onValueChange = { autoMinutesText = it.filter(Char::isDigit) },
            label = { Text("自动刷新分钟（>=30）") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = {
            val bgOk = StyleParsers.parseRgbaOrNull(bgRgba) != null
            val textOk = StyleParsers.parseRgbaOrNull(textRgba) != null
            val authorOk = StyleParsers.parseRgbaOrNull(authorRgba) != null
            val minutes = autoMinutesText.toIntOrNull() ?: 30
            if (!bgOk || !textOk || !authorOk) {
                onToast("RGBA 格式错误，应为 r.g.b.a 且每段 0-255")
                return@Button
            }
            if (minutes < 30) {
                onToast("自动刷新不能小于30分钟")
                return@Button
            }

            scope.launch(Dispatchers.IO) {
                val current = repository.getSettings()
                repository.saveSettings(
                    current.copy(
                        style = WidgetStyleConfig(
                            backgroundRgba = bgRgba,
                            textRgba = textRgba,
                            authorRgba = authorRgba,
                            layoutMode = layoutMode,
                            fontScalePercent = fontPercent.toInt(),
                            cornerRadiusLevel = cornerLevel.toInt(),
                            shadowLevel = shadowLevel.toInt()
                        ),
                        autoRefreshMinutes = minutes,
                        singleClickAction = singleClickAction,
                        doubleClickAction = doubleClickAction
                    )
                )
                RefreshScheduler.schedule(context, minutes)
                OneQuoteWidgetProvider.refreshAll(context)
                showSavedPreview = true
            }
        }) {
            Text("保存配置")
        }

        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                val result = repository.refreshFromEnabledSources()
                if (result.isFailure) {
                    withContext(Dispatchers.Main) {
                        onToast("刷新失败，可能全部来源暂不可用")
                    }
                }
                OneQuoteWidgetProvider.refreshAll(context)
            }
        }) {
            Text("立即刷新一次")
        }

        if (showSavedPreview) {
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(StyleParsers.parseRgbaOrNull(bgRgba) ?: 0x66000000))
                        .padding(12.dp)
                ) {
                    Text(
                        text = if (layoutMode == LayoutMode.VERTICAL) StyleParsers.asVerticalText("保存后示例框") else "保存后示例框",
                        color = Color(StyleParsers.parseRgbaOrNull(textRgba) ?: 0xFFFFFFFF.toInt())
                    )
                    Text(
                        text = "— OneQuote",
                        color = Color(StyleParsers.parseRgbaOrNull(authorRgba) ?: 0xDDDDDDFF.toInt())
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(
    favorites: List<FavoriteQuote>,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDelete: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("收藏", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Button(onClick = onImport) { Text("导入CSV") }
            Button(onClick = onExport) { Text("导出CSV") }
        }

        if (favorites.isEmpty()) {
            Text("暂无收藏")
            return@Column
        }

        favorites.sortedByDescending { it.id }.forEach { favorite ->
            FavoriteRow(favorite = favorite, onDelete = onDelete)
        }
    }
}

@Composable
private fun FavoriteRow(
    favorite: FavoriteQuote,
    onDelete: (Int) -> Unit
) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("#${favorite.id} ${favorite.sourceApiName}")
            val authorText = favorite.author?.takeIf { it.isNotBlank() } ?: ""
            if (authorText.isNotBlank()) {
                Text("作者：$authorText")
            }
            Text(text = favorite.text)
            Text(
                text = "删除",
                color = Color.Red,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable { onDelete(favorite.id) }
            )
        }
    }
}

@Composable
private fun WidgetActionButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(if (selected) "✓ $text" else text)
    }
}

private fun canOpenDocument(context: android.content.Context): Boolean {
    val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(android.content.Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }
    return intent.resolveActivity(context.packageManager) != null
}

private fun canCreateDocument(context: android.content.Context): Boolean {
    val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(android.content.Intent.CATEGORY_OPENABLE)
        type = "text/csv"
    }
    return intent.resolveActivity(context.packageManager) != null
}

private fun markFirstLaunchPermissionGuide(context: Context): Boolean {
    val prefs = context.getSharedPreferences("onequote_runtime_flags", Context.MODE_PRIVATE)
    if (prefs.getBoolean("permission_guided_once", false)) return false

    prefs.edit().putBoolean("permission_guided_once", true).apply()
    return true
}

private fun consumeWidgetRefreshAutoStartGuideFlag(context: Context): Boolean {
    val prefs = context.getSharedPreferences("onequote_runtime_flags", Context.MODE_PRIVATE)
    val key = "need_autostart_guide_after_widget_refresh"
    if (!prefs.getBoolean(key, false)) return false
    prefs.edit().putBoolean(key, false).apply()
    return true
}

private fun launchIgnoreBatteryOptimization(
    context: Context,
    launch: (Intent) -> Unit,
    onToast: (String) -> Unit
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
        AppDebugLogger.log("MainActivity", "battery_optimization_already_ignored=true")
        onToast("已开启省电策略豁免")
        return
    }

    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { launch(requestIntent) }
        .onSuccess { AppDebugLogger.log("MainActivity", "battery_optimization_launch=request") }
        .onFailure {
            runCatching { launch(fallbackIntent) }
                .onSuccess { AppDebugLogger.log("MainActivity", "battery_optimization_launch=fallback") }
                .onFailure { onToast("无法打开省电策略设置") }
        }
}

private fun launchAutoStartSettings(
    context: Context,
    launch: (Intent) -> Unit,
    onToast: (String) -> Unit
) {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val intents = when {
        manufacturer.contains("xiaomi") -> listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        manufacturer.contains("oppo") -> listOf(
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Intent().setClassName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
        )
        manufacturer.contains("vivo") -> listOf(
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        )
        manufacturer.contains("samsung") -> listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
        else -> listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    }

    val launched = intents.firstOrNull { intent ->
        intent.resolveActivity(context.packageManager) != null
    }
    if (launched == null) {
        AppDebugLogger.log("MainActivity", "autostart_launch=not_found")
        onToast("未找到自启动设置入口")
        return
    }
    runCatching { launch(launched) }
        .onSuccess { AppDebugLogger.log("MainActivity", "autostart_launch=success") }
        .onFailure {
            AppDebugLogger.log("MainActivity", "autostart_launch=failed error=${it.message}")
            onToast("打开自启动设置失败")
        }
}

private fun hasStoragePermission(context: Context): Boolean {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
            val readGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            readGranted && writeGranted
        }
        else -> true
    }
}

private fun requestStoragePermission(
    context: Context,
    requestRuntime: (Array<String>) -> Unit,
    requestManageAllFiles: (Intent) -> Unit,
    onToast: (String) -> Unit
) {
    if (hasStoragePermission(context)) {
        AppDebugLogger.log("MainActivity", "storage_permission_already_granted=true")
        onToast("存储权限已可用")
        return
    }

    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            runCatching { requestManageAllFiles(intent) }
                .onSuccess { AppDebugLogger.log("MainActivity", "storage_permission_launch=manage_app_all_files") }
                .onFailure {
                    runCatching { requestManageAllFiles(fallback) }
                        .onSuccess { AppDebugLogger.log("MainActivity", "storage_permission_launch=manage_all_files_fallback") }
                        .onFailure { onToast("无法打开存储权限设置") }
                }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
            val permissions = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
            AppDebugLogger.log("MainActivity", "storage_permission_launch=runtime permissions=$permissions")
            requestRuntime(permissions.toTypedArray())
        }
    }
}

private enum class PermissionGuideStep {
    NONE,
    BATTERY,
    AUTO_START,
    STORAGE
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    OneQuoteTheme {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("OneQuote")
        }
    }
}

