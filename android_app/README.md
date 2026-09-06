# AniMeow Android 原生版

AniMeow 是对原版 `anime_tracker` 的 Kotlin + Jetpack Compose 原生重写。项目保留原版完整业务能力和数据迁移能力，并同时提供现代新版与经典旧版两套可自由切换的前端；默认采用 MIUIX 方向的现代交互，外观、布局、导航、手势和信息密度均可组合配置。

当前 Android 构建版本：`2.0.5`（versionCode 32）。业务功能对照和既有自动化基线已经收口，公开发布前仍需执行真机手动验收、切换 HTTPS 并使用正式 Release 密钥签名。

## 平台基线

- Release 包名：`com.animeow.app`
- Debug 包名：`com.animeow.app.debug`
- 最低版本：Android 8.0 / API 26
- 目标版本：Android 16 / API 36
- 语言与 UI：Kotlin、Jetpack Compose、Material 3
- 数据：Room、DataStore、WorkManager、Android Keystore
- 默认风格：MIUIX
- 可选风格：Anime Dynamic、Cyber Glass、Material 3、Minimal、Retro Pixel

## 已实现能力

- 追番资料库：新增、编辑、详情、回收站、打卡与撤销、多选批处理、筛选、排序、查重、系列聚合、标签和自定义状态。
- 首页：五种布局、2–6 列双指缩放、三种封面画幅、标题/状态/评分/进度显隐、独立书架、左右滑动动作和网络封面补全。
- 详情与编辑器：四种详情布局、模块排序与隐藏、三种卡片材质、本地封面焦点裁剪、未保存编辑保护和可配置退出行为。
- 发现：Bangumi、AniList、trace.moe、排行榜、高级筛选、官方/代理策略、图片代理和可选服务器资料库；统一客户端过滤，沿用原版季度边界，并支持列表/网格、密度和信息字段显隐。
- 日历与提醒：月/周/议程、事件筛选、缺失日期补全、通知权限、精确闹钟、WorkManager 安全降级、开机/时区/更新时间重建和通知详情深链。
- 组织与分析：系列、标签索引、自定义状态、动画/书籍分类计数、经典卡片/档案仪表盘双统计布局、可逐项显隐的概览指标、可继承自定义颜色并点击下钻的统计面板、Tier List 图片分享、AI 看番风格分析与历史。
- 角色与分享：角色资料、作品关联、标签、关系、关系星图、查重合并、角色组收藏包、分享社区浏览、分享码及账号发布管理。
- 登录社区：单一官方群“追番喵茶话会”、随机群码、图文帖子、客户端图片压缩、群消息开关、隐私个人名片、可选统计名片、举报隔离与管理员人工复审。
- 数据交换：原版 SQLite/ZIP v1–19、原生完整备份、恢复点、本地/云端三范围选择性恢复、非破坏云合并、Bangumi 收藏、Excel/CSV 导入导出；完整快照操作使用统一互斥，避免同步、导入与恢复并发覆盖。
- 双前端与交互：现代新版和经典旧版自由切换；追番、发现、日历拥有页面专属设置；可选预测性返回手势，长列表 Bottom Sheet 在触底时保持稳定。
- 账号与维护：一机一账号注册、多设备登录、云备份、自动同步、冲突处理、开放反馈池、诊断日志、崩溃恢复、缓存、更新检查、APK 安装引导、帮助、更新日志和声明。
- 品牌定制：自定义启动封面、焦点/缩放/背景/时长、默认图标加 12 套原版可选图标。

完整对照见 [FEATURE_PARITY.md](FEATURE_PARITY.md)。

## 高度自定义

“我的 → 打造我的 AniMeow”提供实时预览、草稿回退、撤销、搜索、预设和配置档案。可调整：

- 视觉风格、浅色/深色/跟随系统、系统动态色、自定义或随机强调色。
- 字体 80%–140%、圆角、密度、动效等级、触觉和轻量操作音效。
- 首页/详情/发现/角色/社区/统计/日历布局与模块；统计页可切换经典卡片和档案仪表盘，并独立控制各概览指标，发现页还可独立设置结果密度和来源、评分、日期、标签显隐。
- 封面比例、列数、角标、评分图标、标题位置和信息显隐。
- 主导航入口、顺序、启动落点及“我的”页搜索入口样式；追番/我的固定保留，发现、日历、社区、统计可选，可见入口限制为 3–5 项，社区默认隐藏。
- 左右滑动动作、完成后状态、编辑器模块和退出行为。
- 多份配置档案的保存、复制、导入和导出。

配置档案使用向后兼容的 JSON 格式；当前外观 Schema 为 v12，配置档案封装 Schema 为 v2。

## 原版数据迁移

推荐在原版中导出 ZIP，然后在原生版进入“我的 → 导入原版数据”：

1. 选择 `AniMeow_backup_*.zip`，或直接选择 `anime_tracker_v5.db`。
2. 查看版本、作品、系列、标签、角色、角色组、观看记录和封面统计。
3. 确认后，应用先创建完整恢复点，再以单一 Room 事务替换资料库。
4. ZIP 中的本地封面会复制到新包名对应的私有目录并重写路径。

原版包名不同不影响导入。兼容 SQLite v1–19；更高版本允许只读预检，但会明确提示未知字段可能被忽略。导入失败后会保留同一次预检，可直接重试且不会重复创建恢复点。详见：

- [LEGACY_DATA_IMPORT.md](LEGACY_DATA_IMPORT.md)
- [LEGACY_COMPATIBILITY_MATRIX.md](LEGACY_COMPATIBILITY_MATRIX.md)
- [test-fixtures/README.md](test-fixtures/README.md)

## 可选云端配置

本地资料库和全部离线功能不依赖云端。以下值可通过 Gradle 属性、环境变量或未提交的 `local.properties` 提供：

```properties
CLOUD_API_BASE=https://your-server.example
API_TOKEN=replace-with-a-restricted-read-token
```

值为空时，账号、云备份、登录社区和服务器资料库入口会显示“未配置”说明，不影响本地使用。不要把特权令牌写入公开仓库或发行 APK；`BuildConfig` 中的字符串可被反编译读取。登录社区使用用户会话 Token，管理员权限由服务端 `users.is_admin` 判定，不依赖 APK 内的全局密钥。

若 `CLOUD_API_BASE` 以 `http://` 开头，该次构建会把 `usesCleartextTraffic` 全局设为 `true`；否则为 `false`。这不是按域名限制的例外，正式发布必须优先使用 HTTPS。客户端 `API_TOKEN` 应视为可公开提取的受限凭据，只能赋予最小权限、限速并支持撤销。

## 构建

使用 Android Studio 自带 JBR 或 JDK 17：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug --console=plain
```

只构建 Android，并按固定名称输出 APK 与 RAR：

```powershell
..\scripts\build_android.bat package --output "$env:USERPROFILE\Desktop\总" --no-pause
```

输出为 `AniMeow2.0.5.apk` 与 `AniMeow2.0.5.rar`，RAR 内只包含同名 APK。该模式执行 Release/R8 构建、zipalign、签名校验、RAR 压缩和压缩包完整性测试，不执行桌面端任务，也不重复运行全量单测/Lint。

最终集中验证：

```powershell
..\scripts\build_android.bat all --no-pause
```

该命令集中执行 Android 单测、Lint、后端检查/生产依赖审计及 Debug/Release 打包。当前基线为 38 个测试套件、155 个测试全部通过，Lint 0 errors；Release 已启用 R8 与资源压缩。若未提供 `ANIMEOW_KEYSTORE_*` 环境变量，脚本会使用本机 Android Debug keystore 生成可安装的本地测试包；正式发布必须改用仓库外私有 keystore，并按 [OPEN_SOURCE_SANITIZATION.md](OPEN_SOURCE_SANITIZATION.md) 执行脱敏检查。

## 外部入口安全边界

`MainActivity` 为支持 `animeow://` 深链接和系统 `ACTION_SEND text/plain` 而导出。角色组分享码也可从剪贴板识别。上述外部内容均会先经过严格格式解析，再显示确认和差异预览；只有用户再次明确点击导入才会修改资料库。提醒深链接只会打开已有作品详情。

## 验收资料

- [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md)：需要在真机/模拟器完成的发布前手动测试。
- [FEATURE_PARITY.md](FEATURE_PARITY.md)：原版功能到原生实现的对照。
- [FINAL_VERIFICATION.md](FINAL_VERIFICATION.md)：最终单测、Lint、APK、Manifest、签名、依赖和 v19 样本审计结果。
- [OPEN_SOURCE_SANITIZATION.md](OPEN_SOURCE_SANITIZATION.md)：开源前的凭据、日志、数据库和构建产物清理清单。

开发期间只执行轻量编译和针对性测试；全量单测、Lint 与 Debug/Release 打包集中在最终收口阶段，减少重复构建成本。
