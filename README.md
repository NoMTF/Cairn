# Cairn

> 为没有安全见证人、没有可信网络、也没有第二次机会的人，留下一块离线的证据石标。

Cairn 是一个 Android 离线取证录音 App，面向极端个人安全场景设计：被胁迫谈话、被盘问、被拘禁、被家暴或机构性压迫、遭遇抗议现场暴力、投诉无门，或者因为性别身份、性倾向、族裔、宗教、残障、政治表达等身份而遭到针对、威胁、羞辱、驱赶或迫害。

它不是云录音机，也不是远程监控工具。Cairn 的核心目标只有一个：当用户在自己的手机上主动开启记录时，证据应尽可能在断网、恐慌、进程被杀、低电量、临时检查和普通删除尝试中保留下来。

App 的前台表现为 **FastLink VPN**。这不是装饰，而是自卫语境下的界面策略：在高压房间里，一个普通的 VPN / 网络加速工具，比一个写着“取证录音”的 App 更容易解释。

## 适用场景

Cairn 适合用户在自己的设备上，为自己正在经历或亲眼见到的事件做本地记录。

- 因性别身份、性倾向、外貌表达、亲密关系或家庭控制而被威胁、围堵、审问、羞辱或强制带离。
- 在学校、单位、医院、收容、拘留、边检、社区或家庭场景中，缺少安全的第三方见证人。
- 遭遇家暴、跟踪、胁迫控制、职场报复、投诉无门或机构推诿。
- 记者、抗议者、维权者、志愿者、少数群体成员或普通目击者，在网络不可信或可能被检查手机时，需要离线冗余证据。

边界同样清楚：不得把 Cairn 安装到他人设备上，不得用于非法监控，不得绕过当地录音同意法律，不得加入远程控制、云上传、批量部署、静默安装或任何后门。

## 表面效果

用户打开 App 后看到的是一个完整的 VPN 工具：

- 品牌显示为 `FastLink VPN`
- 主页面有服务器节点、连接按钮、上下行流量和连接时长
- `已用流量` 显示当前会话主副本目录的真实本地缓存大小
- 点击连接后显示 VPN 已连接
- 前台通知显示为普通语音通话
- 设置页全部使用路由、缓存、诊断、加速等 VPN 术语
- 导出文件名使用 FastLink 诊断包命名

真实效果：

- 主屏点击连接会启动前台录音服务
- 主屏点击断开只改变界面状态，不真正停止记录
- 设置页里的 `完整线路重置` 才是真正停止入口
- 录音按 1 秒 chunk 写入本地加密容器
- 同一 session 默认写入 10 处伪装缓存目录
- 可选 GPS、传感器、周期性快照 sidecar
- 导出包包含 manifest、哈希、sidecar 和验证脚本
- 用户设置过的恢复密钥可在反馈页触发本地清理

## 核心能力

- **零网络路径**：不申请 `INTERNET`，当前 merged manifest 不含 `INTERNET` / `ACCESS_NETWORK_STATE`。
- **本地加密容器**：音频写入 CNCE AES-GCM 分块容器，不再落明文 WAV。
- **每秒落盘**：1 秒一个 chunk，并强制 flush / fsync，把异常断电或进程被杀造成的数据损失压到约 1 秒。
- **崩溃修复**：录音中保留 `.len` sidecar，异常退出后可修复 CNCE header。
- **多副本冗余**：默认 10 个隐蔽缓存路径，极端模式可扩展到 100 个候选路径。
- **取证上下文**：可同步生成 GPS、传感器和周期性快照 sidecar。
- **哈希链**：每秒音频 chunk 生成 SHA-256 链，辅助证明连续性。
- **前台伪装**：主界面像 VPN，通知像通话，设置像网络诊断面板。
- **紧急清理**：用户自设恢复密钥后，可从反馈页触发本地不可逆清理。

## 使用指南

1. 构建或安装 APK。
2. 打开 App，桌面和主界面显示为 `FastLink VPN`。
3. 按引导完成权限：麦克风、相机、位置、通知、文件管理、电池、精确闹钟等。
4. 回到主屏，点击大号电源按钮。界面显示 VPN 已连接，实际开始本地记录。
5. 如果被要求“断开 VPN”，主屏断开只改变可见状态，记录仍会继续。
6. 真正停止时，进入设置页，点击 `完整线路重置`。
7. 需要导出时，使用 `导出连接日志` -> `导出最新诊断包`。
8. 导出文件在 Downloads 中，命名类似：

```text
FastLink_diagnostics_<sessionId>.zip
```

9. 在可信电脑上验证：

```bash
python verify/verify_evidence.py FastLink_diagnostics_<sessionId>.zip
```

## 设置项对照表

App 内所有设置项都刻意写成 VPN / 网络工具术语。下面是维护者和审计者需要知道的真实含义。

| App 内可见设置 | 实际功能 |
|---|---|
| `线路初始化` | 首次启动权限引导 |
| `线路诊断模块` | 麦克风、相机、位置运行时权限 |
| `本地缓存访问` | 文件管理权限，用于写入公共缓存目录 |
| `快捷连接助手` | 无障碍服务，用于音量键三连击触发 |
| `后台连接保活` | 电池优化白名单 |
| `连接状态通知` | 前台服务通知权限 |
| `定时线路巡检` | 精确闹钟保活权限 |
| `厂商连接优化` | 厂商 ROM 自启动 / 后台运行设置 |
| `连接已启用` / `隧道空闲` | 录音前台服务是否正在运行 |
| `完整线路重置` | 真正停止录音服务 |
| `私有线路恢复密钥` | 设置一次性紧急清理暗码 |
| `新线路密钥` | 输入暗码 |
| `保存恢复密钥` | 保存暗码 hash，保存后该面板隐藏 |
| `连接体验评分` | 打开伪反馈页 |
| `打开反馈表单` | 进入伪评分 / 反馈页面 |
| `加速模块` | 取证 sidecar 功能集合 |
| `智能地区路由` | GPS / 位置 sidecar |
| `连接诊断` | 周期性后台快照 sidecar |
| `信号质量监控` | 加速度、陀螺仪、磁力计 sidecar |
| `流质量` | 音频采样率 |
| `紧凑线路` | 8 kHz 音频 |
| `标准线路` | 16 kHz 音频 |
| `高保真线路` | 44.1 kHz 音频 |
| `快照调校` | 快照质量和间隔 |
| `诊断帧质量` | JPEG 快照质量 |
| `诊断间隔` | 快照间隔秒数 |
| `多线路冗余` | 极端多路径副本模式 |
| `启用 100 节点路由` | 启用最多 100 个候选写入位置 |
| `缓存管理` | 低存储保护 |
| `缓存预留` | 可用空间低于阈值时停止 / 保护写入 |
| `深度加速` | Root 增强功能区 |
| `启用深度加速` | 开启 Root 辅助权限、待机、静音、锁定等增强标志 |
| `导出连接日志` | 导出最新取证 session |
| `导出最新诊断包` | 打包 zip，包含 manifest、哈希和 sidecar |
| `外观` | 桌面图标伪装 |
| `FastLink` / `计算器` | 切换启动器图标身份 |
| 主屏 `已用流量` | 当前会话主副本目录的真实文件大小 |

## 文件与证据结构

每次记录会生成一个 session id，例如：

```text
20260521_010203
```

默认写入多个公共存储下的隐藏缓存式目录。每个 active location 可能包含：

```text
<prefix><sessionId><suffix>          CNCE 加密音频容器
<prefix><sessionId><suffix>.len      录音中存在的崩溃修复 sidecar
<prefix><sessionId>.chain            音频哈希链 CSV
<prefix><sessionId>.gps              位置 CSV
<prefix><sessionId><suffix>.sensor   传感器 CSV
IMG_<sessionId>_0001.dat             伪装扩展名的快照 JPEG 数据
```

干净停止后 `.len` 会被删除；异常退出后，恢复扫描会用它回填 CNCE header。

## 导出包内容

导出的 zip 包通常包含：

```text
recording/
manifest.json
decrypt.py
README.md
```

`manifest.json` 记录：

- session id
- 导出时间
- 主副本 index
- 主副本 SHA-256
- 副本数量
- 哈希链文件及 SHA-256
- 全部可读副本 hash

`decrypt.py` 当前用于检查 CNCE 容器结构。由于设备内主密钥来自 Android Keystore，完整离线解密需要后续实现“导出密码重加密”流程。

## 技术架构

| 模块 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 构建 | Gradle Kotlin DSL |
| Android Gradle Plugin | 8.9.1 |
| Kotlin | 2.0.21 |
| compileSdk | 36 |
| minSdk | 24 |
| targetSdk | 35 |
| 音频 | AudioRecord PCM |
| 加密 | Android Keystore + AES-GCM |
| 位置 | Google Play Services Location |
| 设置 | AndroidX DataStore Preferences |
| Root | libsu |

核心源码入口：

- `app/src/main/kotlin/com/cairn/app/ui/MainActivity.kt`
- `app/src/main/kotlin/com/cairn/app/ui/SettingsActivity.kt`
- `app/src/main/kotlin/com/cairn/app/service/RecordingService.kt`
- `app/src/main/kotlin/com/cairn/app/service/AudioPipeline.kt`
- `app/src/main/kotlin/com/cairn/app/storage/EncryptedChunkWriter.kt`
- `app/src/main/kotlin/com/cairn/app/storage/IntegrityChain.kt`
- `app/src/main/kotlin/com/cairn/app/export/EvidencePackager.kt`
- `app/src/main/kotlin/com/cairn/app/nuke/NukeEngine.kt`

## 构建

需要：

- JDK 17
- Android SDK
- API 36 平台
- 项目内 Gradle wrapper

Windows：

```powershell
.\gradlew.bat app:check app:assembleRelease
```

类 Unix shell：

```bash
./gradlew app:check app:assembleRelease
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 产物：

```text
app/build/outputs/apk/release/
```

正式分发前仍需配置 release signing。

## 验证

项目检查：

```bash
./gradlew app:check
python -m py_compile verify/verify_evidence.py verify/decrypt.py app/src/main/assets/decrypt.py
```

导出包检查：

```bash
python verify/verify_evidence.py FastLink_diagnostics_<sessionId>.zip
```

验证脚本会检查 manifest、主副本 hash、CNCE magic、GPS 连续性和哈希链连续性。

## 安全模型与限制

Cairn 能提高离线证据在普通检查、误删、进程死亡、单点路径丢失和低空间风险下的存活率。它不是万能反取证工具。

可以期待：

- 默认无网络上传路径
- 无远程控制通道
- 无分析 SDK
- 本地音频加密
- 多副本冗余
- 每秒 fsync
- 哈希链和 sidecar 辅助验证

必须理解：

- Android Keystore 密钥不可导出，外部完整解密需要后续导出密码功能。
- 隐藏目录和 `.nomedia` 是伪装，不是安全边界。
- flash / F2FS / wear leveling 下，多遍覆写只能视为尽力而为。
- Root 功能依赖设备、ROM、系统策略和用户风险判断。
- 录音是否合法取决于当地法律和具体场景。

## 维护红线

不要加入：

- `INTERNET` 权限
- 云上传
- 远程控制
- analytics / remote config
- 批量部署
- 静默安装
- 绕过杀软或隐藏进程
- 明文音频落盘路径
- 在用户不知情时启动记录的逻辑

如果未来必须引入网络功能，应单独分支、默认关闭、显著文档说明，并进行独立安全审计。

## 分发建议

由于 Cairn 使用麦克风、相机、位置、前台服务、全文件访问和可选 Root 增强，主流应用商店大概率无法接受。

建议：

- 源码自构建
- GitHub Releases
- F-Droid 风格的可复现构建流程

不建议：

- 国内应用商店伪装上架
- Google Play 强行规避审核
- 任何形式的静默安装或远程部署

## License

GPL-3.0。衍生版本应继续开源、可审计，并对它声称要保护的人负责。
