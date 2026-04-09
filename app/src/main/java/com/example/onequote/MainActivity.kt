package com.example.onequote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.onequote.data.model.FavoriteQuote
import com.example.onequote.data.model.BuiltinSources
import com.example.onequote.data.model.LayoutMode
import com.example.onequote.data.model.QuoteContent
import com.example.onequote.data.model.ShadowPreset
import com.example.onequote.data.model.TextAlignMode
import com.example.onequote.data.model.WidgetClickAction
import com.example.onequote.data.model.WidgetStyleConfig
import com.example.onequote.data.repo.QuoteRepository
import com.example.onequote.data.util.AppDebugLogger
import com.example.onequote.data.util.StyleParsers
import com.example.onequote.scheduler.RefreshScheduler
import com.example.onequote.ui.theme.OneQuoteTheme
import com.example.onequote.widget.OneQuoteWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as OneQuoteApp).repository
        enableEdgeToEdge()
        setContent {
            OneQuoteTheme {
                OneQuoteAppRoot(
                    repository = repository,
                    onToast = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }
}

private object Route {
    const val ONBOARDING_WELCOME = "onboarding_welcome"
    const val ONBOARDING_ACTION = "onboarding_action"
    const val ONBOARDING_DONE = "onboarding_done"
    const val HOME = "home"
    const val FAVORITES = "favorites"
    const val API_SETTINGS = "api_settings"
    const val BEAUTY = "beauty"
    const val APP_SETTINGS = "app_settings"
}

@Composable
private fun OneQuoteAppRoot(
    repository: QuoteRepository,
    onToast: (String) -> Unit
) {
    val settings by repository.observeSettings().collectAsState(initial = null)
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val loadedSettings = settings

    if (loadedSettings == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载中…")
        }
        return
    }

    val startRoute = if (loadedSettings.onboardingCompleted) Route.HOME else Route.ONBOARDING_WELCOME

    androidx.compose.runtime.key(startRoute) {
        NavHost(
            navController = navController,
            startDestination = startRoute,
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(220),
                    initialOffsetX = { width -> width / 3 }
                ) + fadeIn(animationSpec = tween(220))
            },
            exitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(220),
                    targetOffsetX = { width -> -width / 3 }
                ) + fadeOut(animationSpec = tween(220))
            },
            popEnterTransition = {
                slideInHorizontally(
                    animationSpec = tween(220),
                    initialOffsetX = { width -> -width / 3 }
                ) + fadeIn(animationSpec = tween(220))
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(220),
                    targetOffsetX = { width -> width / 3 }
                ) + fadeOut(animationSpec = tween(220))
            }
        ) {
            composable(Route.ONBOARDING_WELCOME) {
                OnboardingWelcomeScreen(
                    onNext = { navController.navigate(Route.ONBOARDING_ACTION) },
                    onToast = onToast
                )
            }
            composable(Route.ONBOARDING_ACTION) {
                OnboardingActionScreen(
                    initialSingle = loadedSettings.singleClickAction,
                    initialDouble = loadedSettings.doubleClickAction,
                    onSave = { single, double ->
                        scope.launch(Dispatchers.IO) {
                            val current = repository.getSettings()
                            repository.saveSettings(current.copy(singleClickAction = single, doubleClickAction = double))
                        }
                        navController.navigate(Route.ONBOARDING_DONE)
                    }
                )
            }
            composable(Route.ONBOARDING_DONE) {
                OnboardingDoneScreen(
                    onStart = {
                        scope.launch(Dispatchers.IO) {
                            val current = repository.getSettings()
                            repository.saveSettings(current.copy(onboardingCompleted = true))
                        }
                        navController.navigate(Route.HOME) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        }
                    }
                )
            }
            composable(Route.HOME) {
                HomeScreen(
                    repository = repository,
                    onToast = onToast,
                    onOpenFavorites = { navController.navigate(Route.FAVORITES) },
                    onOpenApi = { navController.navigate(Route.API_SETTINGS) },
                    onOpenBeauty = { navController.navigate(Route.BEAUTY) },
                    onOpenAppSettings = { navController.navigate(Route.APP_SETTINGS) }
                )
            }
            composable(Route.FAVORITES) {
                FavoritesScreen(repository = repository, onToast = onToast)
            }
            composable(Route.API_SETTINGS) {
                ApiSettingsScreen(repository = repository, onToast = onToast)
            }
            composable(Route.BEAUTY) {
                BeautySettingsScreen(repository = repository, onToast = onToast)
            }
            composable(Route.APP_SETTINGS) {
                AppSettingsScreen(repository = repository, onToast = onToast)
            }
        }
    }
}

@Composable
private fun OnboardingWelcomeScreen(
    onNext: () -> Unit,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    var autoStartVisited by remember { mutableStateOf(false) }
    var showImpactDialog by remember { mutableStateOf(false) }
    var missingImpacts by remember { mutableStateOf(emptyList<String>()) }

    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onToast("省电策略设置页已返回")
    }
    val autoStartLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        autoStartVisited = true
        onToast("自启动设置页已返回")
    }

    if (showImpactDialog) {
        AlertDialog(
            onDismissRequest = { showImpactDialog = false },
            title = { Text("权限影响提示") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("检测到以下权限可能未完整配置：")
                    missingImpacts.forEach { Text("• $it") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImpactDialog = false
                    onNext()
                }) {
                    Text("确认")
                }
            }
        )
    }

    ScreenContainer(title = "欢迎") {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("欢迎使用OneQuote", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            FullWidthButton("省电权限请求") {
                onToast("用途：降低系统省电策略对自动刷新的影响")
                launchIgnoreBatteryOptimization(context, batteryLauncher::launch, onToast)
            }
            Spacer(Modifier.height(10.dp))
            FullWidthButton("自启动") {
                onToast("用途：提升后台定时刷新可达性")
                launchAutoStartSettings(context, autoStartLauncher::launch, onToast)
            }

            Spacer(Modifier.height(20.dp))
            FullWidthButton("下一步") {
                val impacts = buildList {
                    if (!isIgnoringBatteryOptimization(context)) {
                        add("未开启省电策略豁免：自动刷新可能延迟或失败")
                    }
                    if (!autoStartVisited) {
                        add("未完成自启动设置确认：后台刷新稳定性可能下降")
                    }
                }
                if (impacts.isEmpty()) {
                    onNext()
                } else {
                    missingImpacts = impacts
                    showImpactDialog = true
                }
            }
        }
    }
}

@Composable
private fun OnboardingActionScreen(
    initialSingle: WidgetClickAction,
    initialDouble: WidgetClickAction,
    onSave: (WidgetClickAction, WidgetClickAction) -> Unit
) {
    var singleAction by remember { mutableStateOf(initialSingle) }
    var doubleAction by remember { mutableStateOf(initialDouble) }

    ScreenContainer(title = "欢迎引导") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("请设置组件单击行为")
            ActionSelectorRow(selected = singleAction, onSelect = { singleAction = it })

            Text("请设置组件双击行为")
            ActionSelectorRow(selected = doubleAction, onSelect = { doubleAction = it })

            Text("后续可在“应用设置”页面修改", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FullWidthButton("下一步") { onSave(singleAction, doubleAction) }
        }
    }
}

@Composable
private fun OnboardingDoneScreen(onStart: () -> Unit) {
    ScreenContainer(title = "完成") {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("愿一言能陪你度过生活", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))
            FullWidthButton("开始使用", onClick = onStart)
        }
    }
}

@Composable
private fun HomeScreen(
    repository: QuoteRepository,
    onToast: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenBeauty: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.observeSettings().collectAsState(initial = null)
    val quote = settings?.lastQuote

    ScreenContainer(title = "首页") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeQuoteCard(
                quoteText = quote?.text ?: "请先在 API 设置中启用来源并主动刷新",
                authorText = quote?.author?.takeIf { it.isNotBlank() } ?: ""
            )

            FullWidthButton("主动刷新") {
                scope.launch(Dispatchers.IO) {
                    val result = repository.refreshFromEnabledSources()
                    withContext(Dispatchers.Main) {
                        if (result.isFailure) onToast("刷新失败，可能全部来源暂不可用")
                    }
                    OneQuoteWidgetProvider.refreshAll(context)
                }
            }

            FullWidthButton("收藏", onOpenFavorites)
            FullWidthButton("API设置", onOpenApi)
            FullWidthButton("自定义美化", onOpenBeauty)
            FullWidthButton("应用设置", onOpenAppSettings)
        }
    }
}

@Composable
private fun FavoritesScreen(
    repository: QuoteRepository,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.observeSettings().collectAsState(initial = null)
    val favorites = settings?.favorites.orEmpty().sortedByDescending { it.id }

    var pendingExportCsv by remember { mutableStateOf<String?>(null) }

    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                val importedText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (importedText.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { onToast("不支持该文件") }
                    return@runCatching
                }
                val summary = repository.importFavoritesFromCsv(importedText)
                withContext(Dispatchers.Main) {
                    if (summary.unsupportedFile) {
                        onToast("不支持该文件")
                    } else {
                        onToast("导入完成：新增${summary.importedCount}，重复${summary.duplicatedCount}")
                    }
                }
                if (!summary.unsupportedFile) OneQuoteWidgetProvider.refreshAll(context)
            }.onFailure {
                scope.launch(Dispatchers.Main) { onToast("不支持该文件") }
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
            withContext(Dispatchers.Main) { onToast(if (success) "导出成功" else "导出失败") }
        }
    }

    ScreenContainer(title = "收藏") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (!canOpenDocument(context)) {
                        onToast("当前系统不支持导入功能")
                        return@Button
                    }
                    importCsvLauncher.launch(arrayOf("text/*", "text/csv", "application/csv"))
                }, modifier = Modifier.fillMaxWidth(0.48f)) {
                    Text("导入CSV文件")
                }
                Button(onClick = {
                    if (!canCreateDocument(context)) {
                        onToast("当前系统不支持导出功能")
                        return@Button
                    }
                    scope.launch(Dispatchers.IO) {
                        pendingExportCsv = repository.exportFavoritesAsCsv()
                        withContext(Dispatchers.Main) { exportCsvLauncher.launch("onequote_favorites.csv") }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("导出CSV文件")
                }
            }

            if (favorites.isEmpty()) {
                Text("暂无收藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            favorites.forEach { favorite ->
                FavoriteRow(
                    favorite = favorite,
                    onCopy = {
                        copyFavoriteToClipboard(context, favorite)
                        onToast("已复制到剪贴板")
                    },
                    onDelete = { id ->
                        scope.launch(Dispatchers.IO) {
                            repository.removeFavorite(id)
                            OneQuoteWidgetProvider.refreshAll(context)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ApiSettingsScreen(
    repository: QuoteRepository,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.observeSettings().collectAsState(initial = null)

    var sourceType by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }
    var sourceAppKey by remember { mutableStateOf("") }
    var showBuiltinDialog by remember { mutableStateOf(false) }

    val builtinSource = settings?.sources.orEmpty().firstOrNull { it.id == BuiltinSources.HITOKOTO_ID }
    val builtinSelected = builtinSource?.selectedTypeCodes.orEmpty()

    ScreenContainer(title = "API设置") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = sourceType,
                onValueChange = { sourceType = it },
                label = { Text("自定义API命名") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = sourceUrl,
                onValueChange = { sourceUrl = it },
                label = { Text("URL地址") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = sourceAppKey,
                onValueChange = { sourceAppKey = it },
                label = { Text("appkey(选填)") },
                modifier = Modifier.fillMaxWidth()
            )
            FullWidthButton("保存来源") {
                if (sourceType.isBlank() || sourceUrl.isBlank()) {
                    onToast("请填写 API 命名与 URL 地址")
                    return@FullWidthButton
                }
                scope.launch(Dispatchers.IO) {
                    repository.addSource(sourceType, sourceUrl, sourceAppKey)
                    sourceType = ""
                    sourceUrl = ""
                    sourceAppKey = ""
                }
            }

            FullWidthButton("应用内置源") {
                showBuiltinDialog = true
            }

            if (showBuiltinDialog) {
                val allTypes = BuiltinSources.hitokotoTypeOptions
                var selectedCodes by remember(builtinSelected) { mutableStateOf(builtinSelected.toSet()) }
                AlertDialog(
                    onDismissRequest = { showBuiltinDialog = false },
                    title = { Text("应用内置源：一言 hitokoto") },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(max = 360.dp)
                        ) {
                            Text("句子类型筛选（全不选=不使用该来源）")
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(allTypes, key = { it.first }) { (code, label) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = selectedCodes.contains(code),
                                            onCheckedChange = { checked ->
                                                selectedCodes = if (checked) selectedCodes + code else selectedCodes - code
                                            }
                                        )
                                        Text("$code - $label")
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                repository.updateBuiltinHitokotoTypeSelection(selectedCodes.toList())
                                OneQuoteWidgetProvider.refreshAll(context)
                            }
                            showBuiltinDialog = false
                        }) {
                            Text("保存")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBuiltinDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }

            val sources = settings?.sources.orEmpty()
            if (sources.isEmpty()) {
                Text("请在上方添加来源", color = Color.Gray)
            } else {
                sources.forEach { source ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                                Text(source.typeName)
                                Text(source.url, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (source.tempDisabled) {
                                    Text("已停用，需手动重新勾选", color = Color.Red)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = source.enabled,
                                    onCheckedChange = { checked ->
                                        scope.launch(Dispatchers.IO) {
                                            if (source.isBuiltin && source.selectedTypeCodes.isEmpty()) {
                                                withContext(Dispatchers.Main) { onToast("请先在“应用内置源”中选择至少一个分类") }
                                                return@launch
                                            }
                                            if (checked) {
                                                val result = repository.testAndEnableSource(source.id)
                                                if (result.isFailure) {
                                                    withContext(Dispatchers.Main) { onToast("首次测试失败：该来源不可用") }
                                                }
                                            } else {
                                                repository.disableSource(source.id)
                                            }
                                            OneQuoteWidgetProvider.refreshAll(context)
                                        }
                                    }
                                )
                                if (!source.isBuiltin) {
                                    TextButton(onClick = { scope.launch(Dispatchers.IO) { repository.removeSource(source.id) } }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BeautySettingsScreen(
    repository: QuoteRepository,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.observeSettings().collectAsState(initial = null)

    var widgetRgb by remember { mutableStateOf("0.0.0") }
    var widgetAlpha by remember { mutableStateOf("140") }
    var cornerLevel by remember { mutableStateOf(4f) }

    var fontRgb by remember { mutableStateOf("255.255.255") }
    var fontAlpha by remember { mutableStateOf("255") }
    var quoteFontSpText by remember { mutableStateOf("16") }

    var authorRgb by remember { mutableStateOf("220.220.220") }
    var authorAlpha by remember { mutableStateOf("255") }
    var authorFontSpText by remember { mutableStateOf("12") }

    var shadowPreset by remember { mutableStateOf(ShadowPreset.NORMAL) }
    var layoutMode by remember { mutableStateOf(LayoutMode.HORIZONTAL) }
    var textAlignMode by remember { mutableStateOf(TextAlignMode.LEFT) }
    var previewUseEnglish by remember { mutableStateOf(false) }

    val sampleQuote = remember(previewUseEnglish) {
        if (previewUseEnglish) {
            QuoteContent(
                text = "Forgive others often, but rarely yourself",
                author = "None",
                sourceType = "示例预览",
                sourceTypeCode = "sample_en",
                sourceFrom = "beauty_preview"
            )
        } else {
            QuoteContent(
                text = "举头望明月，低头思故乡",
                author = "李白《静夜思》",
                sourceType = "示例预览",
                sourceTypeCode = "sample_zh",
                sourceFrom = "beauty_preview"
            )
        }
    }

    var previewModel by remember {
        mutableStateOf(
            BeautyPreviewModel(
                backgroundColor = Color(0x8C000000),
                cornerRadiusDp = 12f,
                quoteColor = Color.White,
                authorColor = Color(0xFFDDDDDD),
                quoteFontSp = 16f,
                authorFontSp = 12f,
                shadowPreset = ShadowPreset.NORMAL,
                layoutMode = LayoutMode.HORIZONTAL,
                textAlignMode = TextAlignMode.LEFT
            )
        )
    }
    var previewLastUpdateAt by remember { mutableStateOf(0L) }

    LaunchedEffect(settings?.savedPreviewVersion) {
        val current = settings ?: return@LaunchedEffect
        val bgParts = current.style.backgroundRgba.split('.')
        widgetRgb = bgParts.take(3).joinToString(".")
        widgetAlpha = bgParts.getOrNull(3) ?: "140"

        val textParts = current.style.textRgba.split('.')
        fontRgb = textParts.take(3).joinToString(".")
        fontAlpha = textParts.getOrNull(3) ?: "255"

        val authorParts = current.style.authorRgba.split('.')
        authorRgb = authorParts.take(3).joinToString(".")
        authorAlpha = authorParts.getOrNull(3) ?: "255"

        cornerLevel = current.style.cornerRadiusLevel.toFloat()
        quoteFontSpText = current.style.quoteFontSp.toString()
        authorFontSpText = current.style.authorFontSp.toString()
        shadowPreset = current.style.shadowPreset
        layoutMode = current.style.layoutMode
        textAlignMode = current.style.textAlignMode
    }

    LaunchedEffect(
        widgetRgb,
        widgetAlpha,
        cornerLevel,
        fontRgb,
        fontAlpha,
        quoteFontSpText,
        authorRgb,
        authorAlpha,
        authorFontSpText,
        shadowPreset,
        layoutMode,
        textAlignMode
    ) {
        // 防抖+短冷却：降低高频输入导致的重组抖动。
        delay(120)
        val now = SystemClock.elapsedRealtime()
        val gap = now - previewLastUpdateAt
        if (gap < 80L) {
            delay(80L - gap)
        }

        val quoteColor = StyleParsers.parseRgbaOrNull(toRgba(fontRgb, fontAlpha) ?: "255.255.255.255")
            ?.let { Color(it) }
            ?: Color.White
        val authorColor = StyleParsers.parseRgbaOrNull(toRgba(authorRgb, authorAlpha) ?: "220.220.220.255")
            ?.let { Color(it) }
            ?: Color(0xFFDDDDDD)
        val backgroundColor = StyleParsers.parseRgbaOrNull(toRgba(widgetRgb, widgetAlpha) ?: "0.0.0.140")
            ?.let { Color(it) }
            ?: Color(0x8C000000)
        val cornerRadiusDp = StyleParsers.levelToCornerDp(cornerLevel.toInt())
        val quoteFontSp = StyleParsers.clampQuoteFontSp(quoteFontSpText.toIntOrNull() ?: 16).toFloat()
        val authorFontSp = StyleParsers.clampAuthorFontSp(authorFontSpText.toIntOrNull() ?: 12).toFloat()

        previewModel = BeautyPreviewModel(
            backgroundColor = backgroundColor,
            cornerRadiusDp = cornerRadiusDp,
            quoteColor = quoteColor,
            authorColor = authorColor,
            quoteFontSp = quoteFontSp,
            authorFontSp = authorFontSp,
            shadowPreset = shadowPreset,
            layoutMode = layoutMode,
            textAlignMode = textAlignMode
        )
        previewLastUpdateAt = SystemClock.elapsedRealtime()
    }

    ScreenContainer(title = "自定义美化") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("组件自定义", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = widgetRgb,
                onValueChange = { widgetRgb = it },
                label = { Text("组件颜色(RGB格式)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = widgetAlpha,
                onValueChange = { widgetAlpha = it.filter(Char::isDigit) },
                label = { Text("透明度设置(0-255)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text("组件圆角：${cornerLevel.toInt()}")
            Slider(value = cornerLevel, onValueChange = { cornerLevel = it }, valueRange = 0f..10f, steps = 9)

            HorizontalDivider()

            Text("一言自定义", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = fontRgb,
                onValueChange = { fontRgb = it },
                label = { Text("字体颜色(RGB格式)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fontAlpha,
                onValueChange = { fontAlpha = it.filter(Char::isDigit) },
                label = { Text("字体透明度(0-255)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = quoteFontSpText,
                onValueChange = { quoteFontSpText = it.filter(Char::isDigit) },
                label = { Text("字体大小(12sp-25sp，默认16)") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Text("作者自定义", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = authorRgb,
                onValueChange = { authorRgb = it },
                label = { Text("作者颜色(RGB格式)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = authorAlpha,
                onValueChange = { authorAlpha = it.filter(Char::isDigit) },
                label = { Text("作者透明度(0-255)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = authorFontSpText,
                onValueChange = { authorFontSpText = it.filter(Char::isDigit) },
                label = { Text("作者字体大小(12sp-20sp，默认12)") },
                modifier = Modifier.fillMaxWidth()
            )

            Text("字体阴影")
            ShadowPresetSelector(selected = shadowPreset, onSelect = { shadowPreset = it })

            Text("字体排版")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { layoutMode = LayoutMode.HORIZONTAL }, modifier = Modifier.fillMaxWidth(0.48f)) {
                    Text(if (layoutMode == LayoutMode.HORIZONTAL) "✓ 横向" else "横向")
                }
                Button(onClick = { layoutMode = LayoutMode.VERTICAL }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (layoutMode == LayoutMode.VERTICAL) "✓ 竖向" else "竖向")
                }
            }

            Text("对齐方式（仅横排生效）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { textAlignMode = TextAlignMode.LEFT }, modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (textAlignMode == TextAlignMode.LEFT) "✓ 左对齐" else "左对齐",
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
                Button(onClick = { textAlignMode = TextAlignMode.CENTER }, modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (textAlignMode == TextAlignMode.CENTER) "✓ 居中" else "居中",
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
                Button(onClick = { textAlignMode = TextAlignMode.RIGHT }, modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (textAlignMode == TextAlignMode.RIGHT) "✓ 右对齐" else "右对齐",
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }

            BeautyPreviewCard(
                quote = sampleQuote.text,
                author = sampleQuote.author.orEmpty(),
                layoutMode = previewModel.layoutMode,
                textAlignMode = previewModel.textAlignMode,
                backgroundColor = previewModel.backgroundColor,
                cornerRadiusDp = previewModel.cornerRadiusDp,
                quoteColor = previewModel.quoteColor,
                authorColor = previewModel.authorColor,
                quoteFontSp = previewModel.quoteFontSp,
                authorFontSp = previewModel.authorFontSp,
                shadowPreset = previewModel.shadowPreset,
                modifier = Modifier.combinedClickable(
                    onClick = { previewUseEnglish = !previewUseEnglish },
                    onLongClick = {
                        val quoteForWidget = sampleQuote.copy(updatedAtMillis = System.currentTimeMillis())
                        scope.launch(Dispatchers.IO) {
                            val current = repository.getSettings()
                            repository.saveSettings(current.copy(lastQuote = quoteForWidget))
                            OneQuoteWidgetProvider.refreshAll(context)
                            withContext(Dispatchers.Main) {
                                onToast("已将当前示例推送到桌面小组件")
                            }
                        }
                    }
                )
            )
            Text(
                text = "点击示例切换中英文；长按示例可强制推送到桌面小组件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FullWidthButton("保存配置") {
                val bg = toRgba(widgetRgb, widgetAlpha)
                val text = toRgba(fontRgb, fontAlpha)
                val authorText = toRgba(authorRgb, authorAlpha)
                if (bg == null || text == null || authorText == null) {
                    onToast("颜色格式错误，RGB需为 r.g.b 且透明度 0-255")
                    return@FullWidthButton
                }

                val quoteFontSp = StyleParsers.clampQuoteFontSp(quoteFontSpText.toIntOrNull() ?: 16)
                val authorFontSp = StyleParsers.clampAuthorFontSp(authorFontSpText.toIntOrNull() ?: 12)

                scope.launch(Dispatchers.IO) {
                    val current = repository.getSettings()
                    repository.saveSettings(
                        current.copy(
                            style = WidgetStyleConfig(
                                backgroundRgba = bg,
                                textRgba = text,
                                authorRgba = authorText,
                                layoutMode = layoutMode,
                                textAlignMode = textAlignMode,
                                quoteFontSp = quoteFontSp,
                                authorFontSp = authorFontSp,
                                cornerRadiusLevel = cornerLevel.toInt(),
                                shadowPreset = shadowPreset
                            )
                        )
                    )
                    OneQuoteWidgetProvider.refreshAll(context)
                    withContext(Dispatchers.Main) { onToast("样式已保存") }
                }
            }
        }
    }
}

@Composable
private fun AppSettingsScreen(
    repository: QuoteRepository,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.observeSettings().collectAsState(initial = null)

    var singleAction by remember { mutableStateOf(WidgetClickAction.REFRESH) }
    var doubleAction by remember { mutableStateOf(WidgetClickAction.COPY) }
    var pendingExportLog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings?.savedPreviewVersion) {
        val current = settings ?: return@LaunchedEffect
        singleAction = current.singleClickAction
        doubleAction = current.doubleClickAction
    }

    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onToast("省电策略设置页已返回")
    }
    val autoStartLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onToast("自启动设置页已返回")
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
            withContext(Dispatchers.Main) { onToast(if (success) "日志导出成功" else "日志导出失败") }
        }
    }

    ScreenContainer(title = "应用设置") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalDivider()
            FullWidthButton("导出日志") {
                scope.launch(Dispatchers.IO) {
                    pendingExportLog = AppDebugLogger.dump()
                    withContext(Dispatchers.Main) { exportLogLauncher.launch("onequote_debug_log.txt") }
                }
            }
            HorizontalDivider()

            Text("组件单击行为")
            ActionSelectorRow(selected = singleAction, onSelect = {
                singleAction = it
                scope.launch(Dispatchers.IO) {
                    val current = repository.getSettings()
                    repository.saveSettings(current.copy(singleClickAction = it, doubleClickAction = doubleAction))
                    OneQuoteWidgetProvider.refreshAll(context)
                }
            })
            Text("组件双击行为")
            ActionSelectorRow(selected = doubleAction, onSelect = {
                doubleAction = it
                scope.launch(Dispatchers.IO) {
                    val current = repository.getSettings()
                    repository.saveSettings(current.copy(singleClickAction = singleAction, doubleClickAction = it))
                    OneQuoteWidgetProvider.refreshAll(context)
                }
            })

            Spacer(modifier = Modifier.height(10.dp))
            Text("权限管理")
            FullWidthButton("省电权限") {
                onToast("用途：保证自动刷新稳定")
                launchIgnoreBatteryOptimization(context, batteryLauncher::launch, onToast)
            }
            FullWidthButton("自启动") {
                onToast("用途：提升后台刷新可达性")
                launchAutoStartSettings(context, autoStartLauncher::launch, onToast)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenContainer(
    title: String,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun HomeQuoteCard(
    quoteText: String,
    authorText: String
) {
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            // 首页固定样式：无背景、18sp、无阴影、白色全不透明。
            Text(
                text = quoteText,
                color = Color.White,
                textAlign = TextAlign.Start,
                fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (authorText.isBlank()) "" else "— $authorText",
                color = Color.White,
                textAlign = TextAlign.End,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun BeautyPreviewCard(
    quote: String,
    author: String,
    layoutMode: LayoutMode,
    textAlignMode: TextAlignMode,
    backgroundColor: Color,
    cornerRadiusDp: Float,
    quoteColor: Color,
    authorColor: Color,
    quoteFontSp: Float,
    authorFontSp: Float,
    shadowPreset: ShadowPreset,
    modifier: Modifier = Modifier
) {
    val quoteDisplay = if (layoutMode == LayoutMode.VERTICAL) StyleParsers.asVerticalText(quote) else quote
    val shadowSpec = StyleParsers.shadowSpec(shadowPreset)
    val shadowColor = Color.Black.copy(alpha = shadowSpec.alpha)
    val quoteTextStyle = TextStyle(
        shadow = Shadow(
            color = shadowColor,
            offset = Offset(shadowSpec.dx, shadowSpec.dy),
            blurRadius = shadowSpec.radius
        )
    )
    val authorTextStyle = quoteTextStyle
    val quoteAlign = when (textAlignMode) {
        TextAlignMode.LEFT -> TextAlign.Start
        TextAlignMode.CENTER -> TextAlign.Center
        TextAlignMode.RIGHT -> TextAlign.End
    }
    Card(
        shape = RoundedCornerShape(cornerRadiusDp.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            if (layoutMode == LayoutMode.VERTICAL) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = StyleParsers.asVerticalText("— $author"),
                        color = authorColor,
                        fontSize = authorFontSp.sp,
                        textAlign = TextAlign.Start,
                        style = authorTextStyle
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = quoteDisplay,
                        color = quoteColor,
                        fontSize = quoteFontSp.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f),
                        style = quoteTextStyle
                    )
                }
            } else {
                Text(
                    text = quoteDisplay,
                    color = quoteColor,
                    fontSize = quoteFontSp.sp,
                    textAlign = quoteAlign,
                    modifier = Modifier.fillMaxWidth(),
                    style = quoteTextStyle
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "— $author",
                    color = authorColor,
                    fontSize = authorFontSp.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                    style = authorTextStyle
                )
            }
        }
    }
}

@Composable
private fun FullWidthButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text)
    }
}

@Composable
private fun ActionSelectorRow(
    selected: WidgetClickAction,
    onSelect: (WidgetClickAction) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = { onSelect(WidgetClickAction.REFRESH) }, modifier = Modifier.weight(1f)) {
            Text(
                text = if (selected == WidgetClickAction.REFRESH) "✓ 刷新" else "刷新",
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
        Button(onClick = { onSelect(WidgetClickAction.COPY) }, modifier = Modifier.weight(1f)) {
            Text(
                text = if (selected == WidgetClickAction.COPY) "✓ 复制" else "复制",
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
        Button(onClick = { onSelect(WidgetClickAction.FAVORITE) }, modifier = Modifier.weight(1f)) {
            Text(
                text = if (selected == WidgetClickAction.FAVORITE) "✓ 收藏" else "收藏",
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun ShadowPresetSelector(
    selected: ShadowPreset,
    onSelect: (ShadowPreset) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = { onSelect(ShadowPreset.NONE) }, modifier = Modifier.weight(1f)) {
            Text(if (selected == ShadowPreset.NONE) "✓ None" else "None", maxLines = 1)
        }
        Button(onClick = { onSelect(ShadowPreset.NORMAL) }, modifier = Modifier.weight(1f)) {
            Text(if (selected == ShadowPreset.NORMAL) "✓ Normal" else "Normal", maxLines = 1)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = { onSelect(ShadowPreset.BOLD) }, modifier = Modifier.weight(1f)) {
            Text(if (selected == ShadowPreset.BOLD) "✓ Bold" else "Bold", maxLines = 1)
        }
        Button(onClick = { onSelect(ShadowPreset.BOLD_LIGHT) }, modifier = Modifier.weight(1f)) {
            Text(if (selected == ShadowPreset.BOLD_LIGHT) "✓ Bold-Light" else "Bold-Light", maxLines = 1)
        }
    }
}

private data class BeautyPreviewModel(
    val backgroundColor: Color,
    val cornerRadiusDp: Float,
    val quoteColor: Color,
    val authorColor: Color,
    val quoteFontSp: Float,
    val authorFontSp: Float,
    val shadowPreset: ShadowPreset,
    val layoutMode: LayoutMode,
    val textAlignMode: TextAlignMode
)

@Composable
private fun FavoriteRow(
    favorite: FavoriteQuote,
    onCopy: () -> Unit,
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "复制",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onCopy)
                )
                Text(
                    text = "删除",
                    color = Color.Red,
                    modifier = Modifier.clickable { onDelete(favorite.id) }
                )
            }
        }
    }
}

private fun copyFavoriteToClipboard(context: Context, favorite: FavoriteQuote) {
    val author = favorite.author?.takeIf { it.isNotBlank() }
    val copyText = if (author.isNullOrBlank()) favorite.text else "${favorite.text}\n— $author"
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager.setPrimaryClip(ClipData.newPlainText("onequote_favorite", copyText))
}

private fun isIgnoringBatteryOptimization(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun launchIgnoreBatteryOptimization(
    context: Context,
    launch: (Intent) -> Unit,
    onToast: (String) -> Unit
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    if (isIgnoringBatteryOptimization(context)) {
        onToast("已开启省电策略豁免")
        return
    }

    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { launch(requestIntent) }
        .onFailure {
            runCatching { launch(fallbackIntent) }
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
        else -> listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    }

    val target = intents.firstOrNull { it.resolveActivity(context.packageManager) != null }
    if (target == null) {
        onToast("未找到自启动设置入口")
        return
    }
    runCatching { launch(target) }
        .onFailure { onToast("打开自启动设置失败") }
}

private fun launchAppPermissionSettings(
    context: Context,
    launch: (Intent) -> Unit,
    onToast: (String) -> Unit
) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching { launch(intent) }
        .onFailure { onToast("无法打开应用权限设置") }
}

private fun canOpenDocument(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }
    return intent.resolveActivity(context.packageManager) != null
}

private fun canCreateDocument(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "text/csv"
    }
    return intent.resolveActivity(context.packageManager) != null
}

private fun toRgba(rgbText: String, alphaText: String): String? {
    val rgbParts = rgbText.split('.').map { it.trim() }
    if (rgbParts.size != 3) return null

    val r = rgbParts[0].toIntOrNull() ?: return null
    val g = rgbParts[1].toIntOrNull() ?: return null
    val b = rgbParts[2].toIntOrNull() ?: return null
    val a = alphaText.toIntOrNull() ?: return null
    if (r !in 0..255 || g !in 0..255 || b !in 0..255 || a !in 0..255) return null

    val rgba = "$r.$g.$b.$a"
    return if (StyleParsers.parseRgbaOrNull(rgba) != null) rgba else null
}

@Preview(showBackground = true)
@Composable
private fun PreviewHomeCard() {
    OneQuoteTheme {
        HomeQuoteCard("举头望明月，低头思故乡", "李白《静夜思》")
    }
}
