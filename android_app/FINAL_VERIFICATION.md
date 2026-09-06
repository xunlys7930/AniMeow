# AniMeow 最终自动化验证报告

验证日期：2026-08-12（Asia/Shanghai）

> 说明：本报告是 `2.0.2 / versionCode 29` 的历史验证快照；当前项目版本为 `2.0.5 / versionCode 32`。以下哈希、包体和运行时结论仅对 2.0.2 产物有效，当前版本的最终验证报告需在发布前重新生成。

本报告记录 Android 原生版完成业务功能对照后的最终自动化、产物与安全边界审计。代码与自动化阶段已经收口；真机发布判定仍以 [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md) 为准。

## 1. 构建基线

- Release applicationId：`com.animeow.app`
- Debug applicationId：`com.animeow.app.debug`
- versionCode：`29`
- versionName：`2.0.2`（Debug 为 `2.0.2-debug`）
- minSdk：Android 8.0 / API 26
- targetSdk：Android 16 / API 36
- Release：R8 混淆/优化与资源压缩均已启用
- 正式签名：仓库内未配置；`package` 模式支持仓库外私有 keystore，未提供时回退到本机 Android Debug 证书用于本地验收

从项目根目录执行的最终集中验证命令：

```bat
scripts\build_android.bat all --no-pause
```

2.0.2 新增仅登录可用的社区交流、图文帖子、个人统计名片、随机群码、举报隔离和管理员复审，同时继续保留原角色群组分享工具，因此本次重新执行了 Android 单测、Lint、Debug/Release 构建、后端检查、生产依赖审计和最终打包。Android 核心验证命令为：

```bat
android_app\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
```

最终可安装包使用：

```bat
scripts\build_android.bat package --output "build\release-verification" --no-pause
```

该命令已完成 Release/R8 构建、zipalign、APK 签名与验证、RAR 压缩和 RAR 完整性测试。完整日志位于 `build/logs/native_build.log`。

## 2. 自动测试与静态检查

| 项目 | 结果 |
| --- | --- |
| Debug 单元测试 | 38 个测试套件，155 通过，0 失败，0 错误，0 跳过 |
| Android Lint | 0 errors，107 warnings，15 hints |
| Debug APK | 构建成功 |
| Release APK | 构建成功 |
| 后端检查 | `npm run check` 通过，包含社区路由与隔离规则检查 |
| 后端生产依赖审计 | `npm audit --omit=dev`：0 vulnerabilities |
| 社区 MySQL Schema 集成检查 | 本机 `.env` 中的开发账号被 MySQL 拒绝，未执行迁移、未创建临时库，也未修改现有数据库；有有效隔离库凭据后运行 `node scripts/check-community-schema.js` |

Lint 明细：

| ID | 数量 | 说明 |
| --- | ---: | --- |
| `IconLauncherShape` | 61 | 多套自定义启动图标的轮廓建议 |
| `UseKtx` | 18 | KTX 风格建议 |
| `AutoboxingStateCreation` | 15 hints | Compose 状态装箱建议 |
| `ModifierParameter` | 10 | Compose API 参数顺序建议 |
| `GradleDependency` | 6 | 依赖版本提示 |
| `NewerVersionAvailable` | 4 | 工具/依赖存在更新 |
| `AndroidGradlePluginVersion` | 2 | AGP 版本提示 |
| `ExifInterface` | 2 | 社区图片方向读取的兼容性建议 |
| `KaptUsageInsteadOfKsp` | 1 | kapt → KSP 迁移建议 |
| `UnusedAttribute` | 1 | 预测返回属性在低 API 上不生效 |
| `UseOfNonLambdaOffsetOverload` | 1 | Compose Offset API 风格建议 |
| `HardwareIds` | 1 | 设备标识使用审计提示；客户端仅发送 `ANDROID_ID`（不可用时为应用内随机 ID）加命名空间后的 SHA-256 派生值 |

以上均为非阻断维护项，没有 Lint error。导出组件和明文网络的真实边界以第 4 节的最终 Manifest 审计为准。

## 3. APK 产物、DEX 与签名

| 产物 | 大小 | DEX | SHA-256 | 状态 |
| --- | ---: | ---: | --- | --- |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 4,499,776 bytes / 4.29 MiB | 1 | `4434473A8D8E3D6CACAB5ABEA0EAE838AC4B952F77C8D1280070DF4C5B9E122A` | R8/资源压缩生效；未签名中间产物 |
| `build/release-verification/v2.0.2/AniMeow2.0.2.apk` | 4,522,567 bytes / 4.31 MiB | 1 | `107066BCA9BC694E706CF930B18C1EFF7649DB5CC8E2176C9A5858DD984787D9` | zipalign 通过；v2/v3 签名有效；可安装本地测试包 |
| `build/release-verification/v2.0.2/AniMeow2.0.2.rar` | 4,080,030 bytes / 3.89 MiB | — | `1B4F9902C54A496BECFB7424B25C0948B1DEBEF37FC29211DC7B866B78DD3804` | 仅包含 `AniMeow2.0.2.apk`；`Rar.exe t` 完整性测试通过 |

Debug 证书：

- Subject：`C=US, O=Android, CN=Android Debug`
- SHA-256：`E4EE68B2684E1CB5A2F208C90C348109464F0376DC2B43564A5B1E37F2F8E466`

`aapt dump badging` 已确认最终 APK 的 applicationId 为 `com.animeow.app`、versionCode 为 `29`、versionName 为 `2.0.2`。`apksigner verify --print-certs` 已确认 v2/v3 签名有效；本次仍使用 Android Debug 证书，仅适合作为本地测试包。Release APK 内只有 `classes.dex`，解压长度为 6,312,572 bytes。该 Release/R8 包已在不清除既有数据的前提下升级安装到 Android 16 / API 36 模拟器并完成冷启动，系统报告 `LaunchState: COLD`、`Activity: com.animeow.app/.MainActivity`、`TotalTime: 5955ms`；已有双前端选择和本地资料保持不变。

## 4. 最终 Manifest 权限与导出组件

最终 Release Manifest 权限：

- 网络：`INTERNET`、`ACCESS_NETWORK_STATE`
- 提醒与后台任务：`POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`、`FOREGROUND_SERVICE`
- 应用内更新：`REQUEST_INSTALL_PACKAGES`
- AndroidX：`com.animeow.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 动态 Receiver 隔离权限

未申请媒体/广泛存储、定位、相机、麦克风、通讯录、短信或电话权限。

导出组件审计：

- `MainActivity exported=true`：这是支持 `animeow://` 深链接和 `ACTION_SEND text/plain` 的必要外部入口。分享码、剪贴板和文本分享会先严格解析并显示确认；进入社区后也只加载差异预览，不会自动导入或修改资料库。提醒深链接只打开已有作品详情。
- 13 个 Launcher `activity-alias exported=true`：对应默认图标和 12 套可切换图标，仅声明 `MAIN/LAUNCHER`。
- AniMeow 自有的安装代理 Activity、提醒 Receiver、更新 Receiver 与 FileProvider 均为 `exported=false`。
- WorkManager 的 `SystemJobService` 由 `BIND_JOB_SERVICE` 系统权限保护；Diagnostics Receiver 与 ProfileInstaller Receiver 由 `DUMP` 系统权限保护。
- 其余 AndroidX Service、Receiver 与 Provider 不导出。
- Release 未声明 `debuggable=true`，因此按 Android 默认值不可调试。

明文网络策略：

- 构建逻辑仅在 `CLOUD_API_BASE` 以 `http://` 开头时把 `usesCleartextTraffic` 设为 `true`，否则设为 `false`。
- 这是一项全局开关，不是只允许某个固定域名的有限例外。
- 本次本机构建使用 HTTP 配置，因此最终 Release Manifest 中该值为 `true`。公开 Release 应改用 HTTPS 后重新构建，并复核最终 Manifest 为 `false`。

## 5. APK 内容、依赖与私密字符串审计

- Release 仅 1 个 DEX；Debug 为 18 个 DEX。
- 原生库仅为 AndroidX 的 `libandroidx.graphics.path.so` 与 `libdatastore_shared_counter.so`，覆盖 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`。
- Release DEX 未命中 Firebase Analytics、AdMob、Sentry、Bugly、友盟、AppCenter、AppsFlyer、Mixpanel、Amplitude、Adjust 或 Facebook App Events 命名空间。
- 高置信扫描未发现 PEM 私钥、OpenAI/GitHub/AWS 密钥模式或带用户名密码的数据库 URI。

对本机私密配置做了“精确值是否存在”检查，审计脚本没有回显值：

- 当前 `CLOUD_API_BASE` 和主要客户端 `API_TOKEN` 在 Debug/Release APK 中均有命中，这是 `BuildConfig` 的预期结果。
- 文档中记录的备用/测试 token 未命中 APK。
- 后端 `DB_USER`、`DB_PASSWORD`、`DB_NAME` 未命中 APK。
- `DB_HOST` 的本机值属于通用回环地址字符串，在 APK 中有命中；该字符串本身不构成数据库凭据泄露，也没有与用户名/密码组成连接 URI。

结论：未发现数据库密码、私钥或第三方特权密钥进入 APK；但任何编译进客户端的 `API_TOKEN` 都可被提取，不能被视为秘密。公开发行只能使用权限最小、可撤销、限速且允许公开的客户端凭据，并应在开源前轮换当前本地 token。

## 6. 2.0.2 定向运行时验收

Android 16 / API 36 模拟器上的 Debug 与 Release 定向验收结果：

- Release/R8 包可冷启动；首次启动明确提供“体证新版”和“返回经典版”，两套前端均未被裁剪。
- 新版“我的”保留整体风格与界面版本入口；“常用设置”和“帮助与维护”均带展开箭头并可折叠。
- 经典“我的”按原版分组展示整体风格、预测性返回、开放反馈池、帮助与维护等入口，版本显示为 `v2.0.2 · 经典界面 1.3.9`（Debug 包追加 `-debug`）。
- 经典发现页专属设置可选择 2–4 列、三档密度及评分/来源/放送年份/标签显隐；四列实际生效后已恢复三列默认值。
- 经典日历右上角只有一个页面定制入口，另一个按钮为“待补日期”，不存在重复设置按钮。
- 统计页保留经典卡片布局并新增参考资料页设计的“档案仪表盘”；总收录、动画、书籍、已看集数、估算时长、连续打卡和平均评分可独立显隐，旧配置升级后连续打卡默认关闭。模拟器已验证布局即时切换、平均评分关闭和连续打卡开启后顶部资料卡同步增删，并恢复默认状态。
- 29 个禁用外层拖拽的 Bottom Sheet 均跳过 Material 3 半展开锚点；经典发现筛选已滚动到“排序/查看结果”，在底部连续三次继续滑动后无上下抽搐、循环回弹或面板位移。
- 预测性返回开启时，边缘返回可取消并保持原页，也可提交返回；关闭后走传统返回分支，测试后已恢复开启状态。
- 开放反馈池未登录可读取公开列表并显示“登录后可提交反馈”；测试未创建账号、未提交反馈，也未修改真实云端数据。
- 新版“发现”中的“社区交流”入口可正常打开新社区；未登录页面只显示“登录后进入社区”和云账号登录按钮，不读取或展示帖子、图片、头像、群码及个人名片。验收截图位于 `build/qa/v2.0.2/community-guest.png`。
- 全局搜索已将“登录社区”和“角色群组分享工具”拆成两个独立结果，分别进入新交流社区和原角色组公开分享/导入流程，避免旧名称误导到新页面。
- 本次模拟器验收没有创建真实账号、帖子或举报。当前进程 Logcat 未发现 `FATAL EXCEPTION`、ANR 或应用进程异常退出；唯一匹配的错误级日志是模拟器系统的 `ashmem` 弃用提示，与应用社区逻辑无关。

## 7. 原版 v19 兼容样本

| 检查 | 结果 |
| --- | --- |
| `PRAGMA user_version` | 19 |
| `PRAGMA integrity_check` | ok |
| `PRAGMA quick_check` | ok |
| 业务表 | 16 张 |
| 作品 / 系列 / 标签 | 3 / 1 / 2 |
| 观看记录 / AI 分析历史 | 4 / 1 |
| 角色 / 关系 / 角色标签 | 3 / 5 / 1 |
| 角色组 | 1 |
| ZIP 条目 | 1 个 `anime_tracker_v5.db` + 5 张 PNG 封面 |
| ZIP CRC | 全部通过 |
| ZIP 路径安全 | 0 个绝对路径、反斜杠或 `..` 条目 |
| ZIP 内数据库与独立 DB | 字节与 SHA-256 均一致 |

- DB SHA-256：`CFB6EEADD63EB7BD581DA2E86B644B81FB02D3BE5F53F807438679443AB69BF6`
- ZIP SHA-256：`D0D1A50E1AB7AF7E50A55F535DFFEC5C665E0536B7343013B5A2F01636166E80`

样本覆盖作品、系列、标签、观看记录、提醒、角色图、角色组、AI 分析历史、本地/远程封面，以及需要导入器规范化或清理的越界、负数、重复、自关联和孤立数据。

## 8. 数据安全与并发收口

- 原版导入失败后保留同一次预检，可直接重试；同一预检不会重复创建 `before_legacy_import` 恢复点。
- 云恢复失败后保留预检与选择状态，并复用已经创建的恢复点重试。
- 云同步、原版导入、原生备份/恢复、Bangumi 导入和表格导入导出共用进程内数据传输互斥，避免完整快照并发读写。
- 后台云同步在应用前台时返回重试；若同步过程中用户打开应用，在覆盖本机前还会再次检查并主动延后。
- 新增取消语义测试，协程取消不会被普通 `Result` 错误处理吞掉。

## 9. 开源边界

当前存在且必须保持未提交的本地私密文件：

- 根目录 `PROJECT_CONFIG_KEYS.md`
- `android_app/local.properties`
- `backend/.env`

根目录 `.gitignore` 已覆盖以上文件、keystore、日志、真实数据库/备份及 APK/AAB。当前目录尚未初始化 Git，因此公开仓库建立后仍需检查是否有历史跟踪，并按 [OPEN_SOURCE_SANITIZATION.md](OPEN_SOURCE_SANITIZATION.md) 再执行一次源码与已签名 APK 审计。

## 10. 发布判定

自动化阶段：通过。

正式发布前仍需：

1. 完成 [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md) 的 77 个真机用例，其中 43 个 P0 必须全部通过。
2. 使用有效但隔离的开发数据库凭据运行 `backend/scripts/check-community-schema.js`，并完成社区登录、发帖、举报隔离和管理员复审的多账号联调；禁止直接对生产库试迁移。
3. 将云端地址切换为 HTTPS，并确认最终 `usesCleartextTraffic=false`。
4. 使用仓库外私有 keystore 为 Release 签名，重新记录 APK SHA-256 与正式证书指纹。
5. 对最终公开源码和已签名 APK 再执行开源脱敏与字符串审计。
