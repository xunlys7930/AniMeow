# AniMeow（追番喵）

AniMeow 是一款高颜值、高度可自定义的二次元动漫 / 漫画 / 小说追番进度管理与元数据打卡应用，使用 Kotlin + Jetpack Compose 原生实现。

本仓库开源 **Android 客户端** 的源码。后端、网页端、桌面端与构建脚本暂不开源。

## 目录

- `android_app/`：Android 原生客户端（Jetpack Compose + Room + DataStore + WorkManager）。

## 功能特性

- 完整追番资料库：作品、系列、标签、自定义状态、观看记录、提醒、回收站、查重、统计、Tier List。
- 联网发现：Bangumi、AniList、trace.moe、排行榜、Bangumi 收藏导入和可选服务器资料库。
- 角色系统：角色资料、作品关联、标签、关系图、查重合并、角色组与 8 位分享码。
- 登录社区：官方群、图文帖子、举报隔离与管理员复审。
- 原版数据迁移：兼容 SQLite/ZIP v1–19 的旧版数据导入。
- 数据安全：原生完整备份、三范围选择性恢复、云端非破坏合并。
- 高度自定义：多套视觉风格、主题 / 强调色、字体、布局、模块、密度、手势与启动封面。
- 云端协作：一机一账号注册、多设备登录、公开反馈池。

## Android 构建

需要 Android SDK 与 JDK 17（或 Android Studio 自带 JBR）。

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd android_app
.\gradlew.bat :app:assembleDebug --console=plain
```

发布构建与最终打包请参见 `android_app/README.md`。正式发布需使用仓库外私有 keystore 签名。

## 云端服务

云账号、云备份、登录社区、角色组分享等可选功能依赖后端服务。后端源码暂不开源，云端功能需连接官方服务，或等待后端开放后再自行部署。

## 可选云端配置（Android）

本地资料库与全部离线功能不依赖云端。以下值可通过 Gradle 属性、环境变量或未提交的
`android_app/local.properties` 提供：

```properties
CLOUD_API_BASE=https://your-server.example
API_TOKEN=replace-with-a-restricted-read-token
```

请勿把特权令牌写入公开仓库或发行 APK；`BuildConfig` 中的字符串可被反编译读取。

## 安全与开源说明

- `.env`、`local.properties`、keystore、日志、数据库与构建产物均已被 `.gitignore` 忽略，切勿提交。
- 客户端内的 `API_TOKEN` 必须视为可公开提取的受限凭据，只能使用最小权限、可撤销、限速的令牌。
- 提交前请确认未包含任何凭据、私密路径或个人数据。

## 许可

[MIT](LICENSE)
