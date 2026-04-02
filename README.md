# OneQuote

OneQuote 是一个运行在 Android 12+ 设备上的桌面小组件应用。作用是：在桌面展示一句话，并提供高自由度的样式与来源配置。

# AI实现大部分功能，能工智人的作者只做了API调用

## 核心功能

- 桌面小组件展示句子与作者
- 手动刷新（5 秒冷却）
- 自动刷新
- 多来源配置（类型 + 地址 + appkey）
- 运行期失败熔断（连续失败 2 次自动停用，需手动重新勾选）
- 自定义组件

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

## 当前状态

- 随缘更新，我先好好学一学
- 已完成基础功能与核心策略实现
- 已通过 Debug 构建与基础单元测试
