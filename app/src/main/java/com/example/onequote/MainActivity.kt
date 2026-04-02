package com.example.onequote

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import com.example.onequote.data.model.LayoutMode
import com.example.onequote.data.model.WidgetStyleConfig
import com.example.onequote.data.repo.QuoteRepository
import com.example.onequote.data.util.StyleParsers
import com.example.onequote.scheduler.RefreshScheduler
import com.example.onequote.ui.theme.OneQuoteTheme
import com.example.onequote.widget.OneQuoteWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    var fontLevel by remember { mutableStateOf(5f) }
    var cornerLevel by remember { mutableStateOf(4f) }
    var shadowLevel by remember { mutableStateOf(2f) }
    var autoMinutesText by remember { mutableStateOf("30") }
    var showSavedPreview by remember { mutableStateOf(false) }

    LaunchedEffect(settings?.savedPreviewVersion) {
        settings?.let {
            bgRgba = it.style.backgroundRgba
            textRgba = it.style.textRgba
            authorRgba = it.style.authorRgba
            layoutMode = it.style.layoutMode
            fontLevel = it.style.fontSizeLevel.toFloat()
            cornerLevel = it.style.cornerRadiusLevel.toFloat()
            shadowLevel = it.style.shadowLevel.toFloat()
            autoMinutesText = it.autoRefreshMinutes.toString()
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("OneQuote 设置", style = MaterialTheme.typography.headlineSmall)

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
                                    if (result.isFailure) onToast("首次测试失败：该来源不可用")
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

        Text("字号级别: ${fontLevel.toInt()}")
        Slider(fontLevel, { fontLevel = it }, valueRange = 0f..10f, steps = 9)
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
                            fontSizeLevel = fontLevel.toInt(),
                            cornerRadiusLevel = cornerLevel.toInt(),
                            shadowLevel = shadowLevel.toInt()
                        ),
                        autoRefreshMinutes = minutes
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
                if (result.isFailure) onToast("刷新失败，可能全部来源暂不可用")
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

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    OneQuoteTheme {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("OneQuote")
        }
    }
}

