# OneQuote

OneQuote 是一个运行在 Android 12+ 设备上的桌面小组件应用。核心目标是：在桌面展示一句话，并提供高自由度的样式与来源配置。

## 功能概览

- 桌面小组件展示句子与作者
- 手动刷新（5 秒冷却，防止重复点击）
- 自动刷新（用户可配置，最小 30 分钟）
- 多来源配置（类型 + 地址 + appkey）
- 来源首次勾选先做可用性测试
- 运行期失败熔断（连续失败 2 次自动停用，需手动重新勾选）
- 样式配置（保存后示例框）
  - 组件背景 RGBA
  - 文本 RGBA
  - 作者 RGBA
  - 横排/竖排
  - 字号等级（0-10）
  - 圆角等级（0-10）
  - 阴影等级（0-10，方向右下）

## 技术栈

- Kotlin
- Jetpack Compose（设置页）
- AppWidget + RemoteViews（桌面组件）
- DataStore（配置持久化）
- WorkManager（周期刷新）
- OkHttp + Kotlinx Serialization（网络请求与解析）

## API 约定（当前默认实现）

- 请求方式：GET + Query
- 参数：
  - `format=json`
  - `appkey=<用户输入>`
- 成功判定：`code == 200`
- 句子字段：`data.text`
- 作者字段：`data.author`

## 项目结构（简要）

- `app/src/main/java/com/example/onequote/data/`：模型、存储、网络、仓库
- `app/src/main/java/com/example/onequote/widget/`：小组件 Provider
- `app/src/main/java/com/example/onequote/worker/`：自动刷新 Worker
- `app/src/main/java/com/example/onequote/scheduler/`：任务调度
- `app/src/main/java/com/example/onequote/MainActivity.kt`：设置页入口

## 本地构建

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

生成 APK：

- `app/build/outputs/apk/debug/app-debug.apk`

## 测试

```bash
./gradlew testDebugUnitTest
```

## 当前状态

- 已完成基础功能与核心策略实现
- 已通过 Debug 构建与基础单元测试
