# Cairn 项目完整交接文档

> 交接目标：让新的开发者、维护者或审计者无需追问原作者，也能理解这个项目的目标、用户路径、页面结构、核心技术、代码分层、构建方式、验证方式、风险点和后续工作。

---

## 0. 项目一句话说明

Cairn 是一个 Android 离线取证录音 App，面向用户在遭遇人权侵害、胁迫、冲突、投诉无门等场景时进行本地自卫记录。

它的核心思想不是“云同步”，而是：

1. **零网络路径**：不申请 `INTERNET` 权限，APK 内没有网络上传代码。
2. **多副本物理冗余**：同一份录音同时写入 10 个默认隐蔽目录，极端模式可扩展到 100 个候选目录。
3. **崩溃安全**：录音按 1 秒 chunk 写入，加密容器头可修复，进程被杀也尽量保留已写入部分。
4. **每秒强制落盘**：每秒 `fsync`，把“突然断电 / 被杀进程 / 系统回收”导致的数据损失限制在约 1 秒以内。
5. **取证可验证**：音频 chunk 生成 SHA-256 哈希链，同时记录 GPS、传感器、照片 sidecar。
6. **前台伪装**：主页面伪装成 VPN，前台服务通知伪装成通话通知。
7. **紧急销毁**：用户设置暗码后，可在伪评分页面输入暗码触发不可逆安全擦除。

---

## 1. 当前代码状态快照

### 1.1 技术栈

| 项目 | 当前值 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 构建 | Gradle Kotlin DSL |
| Android Gradle Plugin | `8.7.3` |
| Kotlin | `2.0.21` |
| compileSdk | `35` |
| minSdk | `24` |
| targetSdk | `35` |
| App ID | `com.cairn.app` |
| Root 库 | `com.github.topjohnwu.libsu:core/service:5.2.2` |
| 位置 | Google Play Services Location |
| 数据设置 | AndroidX DataStore Preferences |
| 加密 | Android Keystore + AES-GCM 分块加密容器 CNCE |

### 1.2 构建状态

最近验证命令：

```bash
./gradlew assembleDebug
```

结果：

```text
BUILD SUCCESSFUL
```

### 1.3 网络权限状态

项目设计要求“零网络权限”。当前源码中：

- 未声明 `INTERNET`
- 用 `tools:node="remove"` 明确移除依赖合并带入的 `ACCESS_NETWORK_STATE`
- 构建后 merged manifest 已确认不含：
  - `android.permission.INTERNET`
  - `android.permission.ACCESS_NETWORK_STATE`

相关文件：

- `app/src/main/AndroidManifest.xml`

关键片段：

```xml
<uses-permission
    android:name="android.permission.ACCESS_NETWORK_STATE"
    tools:node="remove" />
```

这不是申请权限，而是告诉 manifest merger：如果依赖引入这个权限，把它从最终 manifest 移除。

---

## 2. 顶层目录结构

项目根目录：`C:\Cairn`

```text
Cairn/
├── README.md
├── HANDOFF.md                         # 本交接文档
├── .gitignore
├── build.gradle.kts                   # 根 Gradle 配置
├── settings.gradle.kts                # include(:app)
├── gradle.properties
├── gradlew / gradlew.bat
├── local.properties                   # 本机 SDK 路径，不应提交
├── local.properties.template
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── decrypt.py             # 开发者用 CNCE 检查脚本，不再随普通导出包附带
│       ├── kotlin/com/cairn/app/
│       └── res/
├── docs/
│   ├── STORAGE_LOCATIONS.md
│   └── DURESS_CODE_SETUP.md
└── verify/
    ├── verify_evidence.py             # 独立取证包验证脚本
    └── decrypt.py                     # CNCE 加密容器检查脚本源码
```

### 2.1 不应提交的本地文件

`.gitignore` 已排除：

```gitignore
*.wav
build.log
ui.xml
screenshots/
.claude/
.kotlin/
local.properties
.gradle/
build/
```

此前根目录有测试 wav 和 uiautomator dump，已清理。

---

## 3. 用户视角：软件作用与页面拆解

### 3.1 主页面：FastLink VPN 伪装页

文件：

- `app/src/main/kotlin/com/cairn/app/ui/MainActivity.kt`
- `app/src/main/kotlin/com/cairn/app/vpn/VpnServerData.kt`

用户看到的是一个 VPN App：

- 顶部品牌：`FastLink VPN`
- 服务器卡片：香港 / 日本 / 新加坡 / 美国等节点
- 中央大 POWER 按钮
- 底部流量统计：下行、上行、已用流量、连接时长
- 设置入口齿轮

实际行为：

| 用户动作 | UI 伪装含义 | 实际行为 |
|---|---|---|
| 点击 POWER 连接 | 连接 VPN | 启动 `RecordingService` 开始录音 |
| 点击 POWER 断开 | 断开 VPN | **只改变 UI 状态，不停止录音** |
| 点击设置 | VPN 设置 | 进入真实控制中心，但文案继续伪装 |
| 选择线路 | 切换 VPN 节点 | 仅保存伪装用 serverId，不建立网络连接 |

关键设计点：

`MainActivity.onDisconnect()` 是空实现：

```kotlin
private fun onDisconnect() {
    // 不做任何后端操作 — UI 自管理状态
}
```

这意味着“对方要求你把 VPN 关掉”时，用户点击断开，界面显示已断开，但录音仍继续。

### 3.2 设置页：真实控制中心，但术语伪装

文件：

- `app/src/main/kotlin/com/cairn/app/ui/SettingsActivity.kt`

设置页实际控制录音、导出、暗码、极端模式、Root 功能等，但所有标签尽量写成 VPN / 网络加速术语。

主要区块：

| 设置页区块 | 表面文案 | 实际功能 |
|---|---|---|
| Connection Status | VPN 连接状态 | 真正停止录音的入口 `Full Disconnect` |
| Emergency Security Code | 紧急安全码 | 设置暗码，触发安全擦除 |
| Rate This App | 给 App 评分 | 打开伪评论页，暗码核爆入口 |
| Acceleration Modules | 加速模块 | GPS / 照片 / 传感器采集开关 |
| Stream Quality | 流质量 | 音频采样率设置 |
| Snapshot Settings | 快照设置 | 后台拍照质量与间隔 |
| Multi-Route Redundancy | 多路冗余 | 极端模式 100 副本开关 |
| Cache Management | 缓存管理 | 存储空间阈值，低空间自动暂停 |
| Deep Acceleration | 深度加速 | Root 增强功能开关 |
| Export Connection Logs | 导出连接日志 | 导出取证 zip 包 |
| Appearance | 外观 | 桌面图标皮肤切换 |

当前导出逻辑：

- 扫描所有存储目录
- 找最近一次 `sessionId`
- 调用 `EvidencePackager.export(...)`
- 输出到 Downloads：

```text
cairn_evidence_<sessionId>.zip
```

注意：这个文件名含 `cairn`，如果担心伪装一致性，后续可以改成 `FastLink_logs_<sessionId>.zip`。

### 3.3 伪评论页：暗码核爆入口

文件：

- `app/src/main/kotlin/com/cairn/app/nuke/FakeReviewActivity.kt`
- `app/src/main/kotlin/com/cairn/app/nuke/DuressCodeMatcher.kt`
- `app/src/main/kotlin/com/cairn/app/nuke/NukeEngine.kt`

页面表现：

- 标题：给我们评分
- 5 星评分
- 评论框
- 提交按钮
- 提交后显示“感谢反馈”

实际逻辑：

1. 用户在评论框输入普通内容：显示感谢反馈。
2. 用户输入自己设置的暗码：显示同样的感谢反馈，但后台异步执行 `NukeEngine.detonate()`。

暗码安全设计：

- 出厂默认码公开：`2345678765俄6879729.`
- 默认码不可直接使用
- 用户必须设置自己的暗码
- 存储的是 SHA-256 hex，不保存明文
- 比较使用常量时间比较 `constantTimeEquals`

核爆流程：

1. 停止录音服务
2. Root 模式下先 `chattr -i` 解锁文件
3. `SecureWipe` 对全部位置做三遍覆写
4. 删除文件
5. 清 App 私有数据
6. Root 模式下自卸载

重要：核爆不可逆。

### 3.4 一键授权页

文件：

- `app/src/main/kotlin/com/cairn/app/ui/OnboardingActivity.kt`
- `app/src/main/kotlin/com/cairn/app/permission/OneClickPermissionFlow.kt`
- `app/src/main/kotlin/com/cairn/app/permission/VendorRomDetector.kt`

作用：引导用户完成权限授予。

普通模式步骤：

1. 录音 / 相机 / 位置权限
2. 文件管理权限 `MANAGE_EXTERNAL_STORAGE`
3. 无障碍服务：音量键三连击触发录音
4. 电池白名单
5. 通知权限
6. 精确闹钟
7. 厂商 ROM 自启动 / 后台保活设置

Root 模式：

- 检测 Root 后使用 `RootPermissionGranter` 静默授权
- 速度更快，但用户需要知道 Root 本身有安全风险

### 3.5 前台服务通知：通话伪装

文件：

- `app/src/main/kotlin/com/cairn/app/notification/QqCallStyleNotification.kt`
- `app/src/main/res/drawable/ic_call_avatar.xml`
- `app/src/main/res/drawable/ic_call_mic_small.xml`
- `app/src/main/res/drawable/ic_call_hangup.xml`
- `app/src/main/res/values/strings.xml`

当前实现：

- 使用 `NotificationCompat.CallStyle.forOngoingCall`
- 显示为通话样式
- 标题：`语音通话`
- 内容：`通话中`
- 自绘绿色话筒头像
- Chronometer 自动计时
- 挂断按钮只打开主页面，不停止录音

为什么不用“QQ”字样：

- README 设计原则是足够差异化，避免商标直接侵权
- 代码里文案保持中性：`语音通话`

---

## 4. AndroidManifest 权限与组件

文件：

- `app/src/main/AndroidManifest.xml`

### 4.1 权限

核心权限：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

前台服务：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
```

保活：

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

其它：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

明确移除网络状态权限：

```xml
<uses-permission
    android:name="android.permission.ACCESS_NETWORK_STATE"
    tools:node="remove" />
```

### 4.2 组件

| 组件 | 文件 | exported | 作用 |
|---|---|---:|---|
| MainActivity | `ui/MainActivity.kt` | true | 主入口，VPN 伪装页 |
| CalculatorAlias | activity-alias | true | 计算器图标皮肤 |
| RecordingService | `service/RecordingService.kt` | false | 前台录音服务 |
| AlarmKeeper | `persist/AlarmKeeper.kt` | false | 30 秒保活检查 |
| BootReceiver | `persist/BootReceiver.kt` | true | 开机恢复扫描 + 调度保活 |
| QsTileService | `trigger/QsTileService.kt` | true | 快速设置磁贴录音开关 |
| VolumeKeyAccessibilityService | `trigger/VolumeKeyAccessibilityService.kt` | true | 音量键三连击触发录音 |
| FakeReviewActivity | `nuke/FakeReviewActivity.kt` | false | 伪评分页 / 暗码入口 |
| SettingsActivity | `ui/SettingsActivity.kt` | false | 控制中心 |
| OnboardingActivity | `ui/OnboardingActivity.kt` | false | 授权引导 |

---

## 5. 核心运行流程

### 5.1 用户点击主屏 POWER 后发生什么

路径：

```text
MainActivity.onConnectAttempt()
  → RecordingService.start(context)
    → startForegroundService(intent)
      → RecordingService.onStartCommand(ACTION_START)
        → startRecording()
```

`RecordingService.startRecording()` 做：

1. 创建通话样式前台通知
2. `startForeground(...)`
3. 获取 `PARTIAL_WAKE_LOCK`
4. 异步读取 DataStore 设置
5. 创建 `FolderRegistry`
6. 获取本次 activeLocations
7. 启动 `AudioPipeline`
8. 启动 `LocationPipeline`
9. 如果开启照片，启动 `PhotoPipeline`
10. 启动 `SensorPipeline`
11. 启动 `StorageWatchdog`
12. 每秒检查存储空间

关键文件：

- `service/RecordingService.kt`
- `storage/SettingsStore.kt`
- `storage/FolderRegistry.kt`

### 5.2 音频录制流程

文件：

- `service/AudioPipeline.kt`

参数：

| 参数 | 值 |
|---|---:|
| sample rate | 16000 Hz |
| channel | mono |
| format | PCM 16-bit |
| frame | 100 ms |
| chunk | 10 frames = 1 second |

流程：

```text
AudioRecord.read(100ms frame)
  → 缓冲 10 帧
    → writeChunk(1s PCM)
      → ParallelMultiWriter.writeAudio(...)
        → EncryptedChunkWriter.write(...)
          → AES-GCM 加密
          → 写入 CNCE 容器
      → flushAndSyncAll()
      → IntegrityChain.processChunk(...)
      → chainWriter.writeLine(entry.toCsvLine())
      → chainWriter.flushAndSyncAll()
```

### 5.3 加密容器 CNCE

文件：

- `crypto/CairnKeystore.kt`
- `crypto/ChunkCipher.kt`
- `storage/EncryptedChunkWriter.kt`

#### 5.3.1 密钥

`CairnKeystore.getOrCreateMasterKey()`：

- 使用 Android Keystore
- alias：`cairn_master_v1`
- 算法：AES
- key size：256-bit
- mode：GCM
- padding：None
- 不要求用户每次认证

#### 5.3.2 Chunk 加密

`ChunkCipher.encrypt(...)`：

- AES/GCM/NoPadding
- 12 字节随机 nonce
- 128-bit tag
- AAD：当前 chunk index 的 little-endian int

AAD 的意义：chunk 顺序也绑定在认证数据中，乱序或篡改会导致解密失败。

#### 5.3.3 CNCE 文件格式

每个音频文件表面仍沿用伪装扩展名：`.dat` / `.bin` / `.cache` / `.amr` 等。

内部格式：

```text
offset 0  : magic "CNCE"  (4B)
offset 4  : version (1B) = 1
offset 5  : reserved (3B)
offset 8  : sample_rate (4B LE)
offset 12 : channels (1B)
offset 13 : bits_per_sample (1B)
offset 14 : encryption_alg (1B) = 1 = AES-GCM
offset 15 : reserved (1B)
offset 16 : chunk_count (4B LE, -1 表示未正常关闭)
offset 20 : chunks...
```

每个 chunk：

```text
4B LE : ciphertext_length，不含 tag
12B   : nonce
N B   : ciphertext
16B   : GCM tag
```

#### 5.3.4 崩溃恢复

`EncryptedChunkWriter.flushAndSync()` 每秒写 `.len` sidecar：

```text
<原文件>.len = 当前 chunk_count
```

正常关闭：

- 修复 header 中 chunk_count
- 删除 `.len`

异常崩溃：

- `.len` 留在磁盘
- 下次启动 `RecoveryScanner.repairAll(...)`
- 调用 `EncryptedChunkWriter.repairFromLenFile(...)`
- 回填 header chunk_count
- 删除 `.len`

### 5.4 哈希链

文件：

- `storage/IntegrityChain.kt`
- `service/AudioPipeline.kt`
- `storage/ParallelMultiWriter.kt`

每个 1 秒音频 chunk 计算：

```text
current_hash = SHA256(prev_hash + chunk_pcm + timestamp_ms + device_fingerprint)
```

CSV header：

```text
chunk_index,timestamp_ms,prev_hash,current_hash,data_size,device_fingerprint
```

输出文件：

```text
<伪装文件前缀><sessionId>.chain
```

例如：

```text
dl_thumb_20260521_010203.chain
```

注意：哈希链当前对**加密前 PCM 明文 chunk**计算。这样可以证明录音内容连续，但导出验证要真正验证内容，需要能解密或信任 App 生成的链。

### 5.5 GPS sidecar

文件：

- `service/LocationPipeline.kt`

输出：

```text
<原文件>.gps
```

CSV header：

```text
epoch_ms,lat,lon,accuracy_m,altitude_m,bearing_deg,speed_mps,source
```

默认每秒请求一次高精度位置。

### 5.6 传感器 sidecar

文件：

- `service/SensorPipeline.kt`

采集：

- Accelerometer
- Gyroscope
- Magnetic field

CSV header：

```text
epoch_ms,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z
```

作用：

- 辅助证明事件发生时手机移动、摔落、抢夺、晃动等物理状态。

### 5.7 后台拍照

文件：

- `service/PhotoPipeline.kt`
- `service/ShutterSilencer.kt`

行为：

- Camera2 API
- 无预览
- 前后摄交替
- 默认每 10 秒一张
- JPEG quality 默认 70
- 写到所有 activeLocations
- 文件名：

```text
IMG_<sessionId>_<序号>.dat
```

静音策略：

- 非 Root：`STREAM_SYSTEM` 静音 + 勿扰模式尝试
- Root：可用 `RootShutterMute` 改系统属性

限制：

- 日韩 ROM 可能强制快门音，非 Root 无法保证静音。

### 5.8 存储保护

文件：

- `service/StorageWatchdog.kt`

行为：

- 每秒检查外部存储剩余比例
- 默认低于 10% 暂停录音
- 恢复到阈值 + 2% 才恢复
- 阈值可在设置页调 1%-50%

---

## 6. 存储位置体系

文件：

- `storage/StorageLocations.kt`
- `storage/FolderRegistry.kt`
- `docs/STORAGE_LOCATIONS.md`

### 6.1 默认 10 处

默认写入 10 个公共目录下的隐蔽子目录，例如：

| index | 目录 | 文件名模式 | 伪装含义 |
|---:|---|---|---|
| 0 | `/Download/.cache` | `dl_thumb_<sessionId>.dat` | 下载缩略图缓存 |
| 1 | `/DCIM/.thumbnails` | `.thumbdata4--<sessionId>` | 相册缩略图 |
| 2 | `/Pictures/.metadata` | `cover_<sessionId>.bin` | 相册元数据 |
| 3 | `/Music/.albumart` | `albumart_<sessionId>.cache` | 音乐封面缓存 |
| 4 | `/Movies/.thumbs` | `video_thumb_<sessionId>.dat` | 视频缩略图 |
| 5 | `/Documents/.tmp` | `doc_cache_<sessionId>.tmp` | 文档临时文件 |
| 6 | `/Alarms/.cache` | `alarm_log_<sessionId>.bin` | 闹钟缓存日志 |
| 7 | `/Notifications/.history` | `notif_<sessionId>.bak` | 通知历史 |
| 8 | `/Recordings/.system` | `voicemail_<sessionId>.amr` | 语音留言伪装 |
| 9 | `/Android/media/com.android.providers.downloads/.bak` | `media_<sessionId>.nomedia` | 媒体备份 |

每个目录会创建 `.nomedia`。

### 6.2 极端模式 100 处

`StorageLocations.generateExtremeCandidates(deviceSeed)`：

1. 前 10 个为默认位置。
2. 后 90 个由设备 seed + index 生成确定性路径。
3. 散布在多个公共目录、隐藏子目录和伪装包名路径中。

`FolderRegistry.getActive()`：

- 普通模式：返回 10 个默认位置
- 极端模式：默认 10 个 + 用户勾选的扩展 index

`FolderRegistry.getAll()`：

- 永远返回全部 100 个，用于恢复扫描和核爆擦除。

---

## 7. 导出与验证

### 7.1 Android 端导出

文件：

- `export/EvidencePackager.kt`
- `ui/SettingsActivity.kt`

导出入口：

```text
Settings → Export Connection Logs → Export Latest Session
```

流程：

1. `SettingsActivity.exportLatestSessionAsEvidence()`
2. `RecoveryScanner.scanAll(allLocations)` 扫描所有 session
3. 取最新 `sessionId`
4. 输出 zip 到 Downloads
5. 调 `EvidencePackager.export(...)`

zip 内容：

```text
audio/session_<sessionId>.wav
photos/*.jpg
gps/gps.csv
sensors/sensors.csv
integrity/integrity_chain.csv
manifest.json
README.md
```

manifest 内容包括：

- session_id
- export_time
- primary_copy_index
- primary_copy_sha256
- raw_copy_count
- raw_copies
- audio.file / audio.sha256 / sample_rate / channels / chunk_count / pcm_bytes
- counts.photos / counts.gps_records / counts.sensor_records / counts.integrity_chain_records
- files 中全部可交付文件的 SHA-256
- audio_stopped_at
- diagnostics_continued_until

### 7.2 独立验证脚本

文件：

- `verify/verify_evidence.py`

用途：律师、第三方、审计者在电脑上验证 zip 包。

当前验证：

1. zip 存在
2. `manifest.json` 存在
3. manifest 中全部导出文件 SHA-256 匹配
4. `audio/*.wav` 是可播放 PCM WAV，header 与 manifest 一致
5. GPS / 传感器 CSV 时间戳不倒退
6. 哈希链 prev/current 连续
7. 照片数量与 manifest 一致

运行：

```bash
python verify/verify_evidence.py cairn_evidence_20260521_010203.zip
```

### 7.3 CNCE 检查脚本

文件：

- `verify/decrypt.py`
- `app/src/main/assets/decrypt.py`

当前作用：

- 识别 CNCE 容器
- 打印 version / sample rate / channels / bits / algorithm / chunk count / chunk 数量

重要限制：

当前脚本只是开发者检查内部 CNCE 容器结构的工具，不再作为普通导出包内容。普通导出包由 App 在设备内用 Keystore 解密并合成为 WAV / JPG / CSV，可直接打开。

---

## 8. Root 功能

目录：

- `app/src/main/kotlin/com/cairn/app/root/`

文件：

| 文件 | 作用 |
|---|---|
| `RootDetector.kt` | libsu 检测 root、执行 root 命令 |
| `RootPermissionGranter.kt` | 静默授予权限、appops、电池白名单 |
| `RootDozeBypass.kt` | 绕过 Doze、锁 active standby bucket |
| `RootShutterMute.kt` | 系统级关闭相机快门音 |
| `RootPrivacyDotHide.kt` | 尝试关闭 Android 12+ 麦克风 / 相机隐私指示器 |
| `RootChattrLock.kt` | `chattr +i` 锁定文件，防普通删除 |

Root 风险：

- Root 设备本身安全性更低
- Root 功能可能被系统更新、厂商 ROM、安全软件拦截
- `RootPrivacyDotHide` 属高风险行为，需明确只服务于合法自卫取证场景

---

## 9. 保活机制

### 9.1 前台服务

文件：

- `service/RecordingService.kt`

使用：

```kotlin
startForeground(QqCallStyleNotification.NOTIFICATION_ID, notification)
```

前台服务类型：

```xml
android:foregroundServiceType="microphone|camera|location"
```

### 9.2 WakeLock

```kotlin
PowerManager.PARTIAL_WAKE_LOCK
wakeLock?.acquire(8 * 60 * 60 * 1000L)
```

最多 8 小时。

### 9.3 START_STICKY

```kotlin
return START_STICKY
```

系统杀服务后尽量重启。

### 9.4 AlarmKeeper

文件：

- `persist/AlarmKeeper.kt`

每 30 秒检查：

- 如果 `RecordingService.isRunning == false`
- 调 `RecordingService.start(context)`
- 再次 schedule 下一次

### 9.5 BootReceiver

文件：

- `persist/BootReceiver.kt`

开机后：

1. `AlarmKeeper.schedule(context)`
2. 异步 `RecoveryScanner.repairAll(...)`

### 9.6 厂商 ROM 引导

文件：

- `permission/VendorRomDetector.kt`

支持：

- Xiaomi / Redmi / POCO
- Huawei / Honor
- OPPO / Realme
- Vivo / iQOO
- Meizu
- Samsung
- OnePlus

用于跳转厂商自启动、后台耗电、电池优化页面。

---

## 10. 紧急销毁系统

### 10.1 暗码匹配

文件：

- `nuke/DuressCodeMatcher.kt`

规则：

- 最小 8 字符
- 必须含数字 + 字母或符号
- 不允许使用出厂默认码
- SHA-256 存储
- 常量时间比较

### 10.2 核爆执行

文件：

- `nuke/NukeEngine.kt`
- `storage/SecureWipe.kt`

流程：

```text
NukeEngine.detonate(context)
  → RecordingService.stop(context)
  → SettingsStore.getDeviceSeed()
  → FolderRegistry(... extremeMode = true).getAll()
  → RootChattrLock.unlockAllLocations(...)
  → SecureWipe.wipeAllLocations(...)
  → clearAppData(context)
  → pm uninstall --user 0 packageName（Root 模式）
```

`SecureWipe` 三遍覆写：

1. 0x00
2. 0xFF
3. SecureRandom

注意：现代 flash / F2FS / wear leveling 无法保证传统 DoD 覆写绝对不可恢复，但对普通文件系统访问和大部分用户级恢复足够强。

---

## 11. 代码包结构详解

```text
com.cairn.app/
├── CairnApp.kt
├── crypto/
├── disguise/
├── export/
├── notification/
├── nuke/
├── permission/
├── persist/
├── root/
├── service/
├── storage/
├── trigger/
├── ui/
└── vpn/
```

### 11.1 `CairnApp.kt`

作用：

- Application 初始化
- 创建通知 channel
- 启动时触发 RecoveryScanner 修复未关闭录音

重点：

```kotlin
createNotificationChannels()
triggerRecoveryScan()
```

### 11.2 `crypto/`

| 文件 | 作用 |
|---|---|
| `CairnKeystore.kt` | 创建 / 获取 Android Keystore AES-256 主密钥 |
| `ChunkCipher.kt` | AES-GCM 单 chunk 加密 / 解密 |

### 11.3 `disguise/`

| 文件 | 作用 |
|---|---|
| `IconAliasManager.kt` | 在主图标和计算器图标之间切换 |

### 11.4 `export/`

| 文件 | 作用 |
|---|---|
| `EvidencePackager.kt` | 扫描副本，打包 zip，写 manifest 和 README |

### 11.5 `notification/`

| 文件 | 作用 |
|---|---|
| `QqCallStyleNotification.kt` | 通话样式前台服务通知 |

### 11.6 `nuke/`

| 文件 | 作用 |
|---|---|
| `DuressCodeMatcher.kt` | 暗码强度、hash、常量时间比较 |
| `FakeReviewActivity.kt` | 伪评分 UI |
| `NukeEngine.kt` | 核爆总流程 |

### 11.7 `permission/`

| 文件 | 作用 |
|---|---|
| `OneClickPermissionFlow.kt` | 7 步授权状态机 |
| `VendorRomDetector.kt` | 厂商 ROM 检测与跳转 |

### 11.8 `persist/`

| 文件 | 作用 |
|---|---|
| `AlarmKeeper.kt` | 30 秒保活检查 |
| `BootReceiver.kt` | 开机恢复与保活 |

### 11.9 `root/`

Root 增强功能，见第 8 节。

### 11.10 `service/`

| 文件 | 作用 |
|---|---|
| `RecordingService.kt` | 前台服务总协调器 |
| `AudioPipeline.kt` | 音频采集、chunk、加密写入、哈希链 |
| `LocationPipeline.kt` | GPS sidecar |
| `SensorPipeline.kt` | 传感器 sidecar |
| `PhotoPipeline.kt` | 后台拍照 |
| `ShutterSilencer.kt` | 非 Root 快门静音尝试 |
| `StorageWatchdog.kt` | 存储阈值暂停 / 恢复 |

### 11.11 `storage/`

| 文件 | 作用 |
|---|---|
| `StorageLocations.kt` | 默认 10 处 + 生成 100 处候选 |
| `FolderRegistry.kt` | 当前 active locations 管理 |
| `ParallelMultiWriter.kt` | 多路并行写入器 |
| `EncryptedChunkWriter.kt` | CNCE 加密容器写入和修复 |
| `IntegrityChain.kt` | chunk 哈希链 |
| `RecoveryScanner.kt` | 扫描 / 修复 / SHA-256 |
| `SecureWipe.kt` | 安全擦除 |
| `SettingsStore.kt` | DataStore 设置 |

### 11.12 `trigger/`

| 文件 | 作用 |
|---|---|
| `QsTileService.kt` | 快速设置磁贴触发录音 |
| `VolumeKeyAccessibilityService.kt` | 音量键三连击触发录音 |

### 11.13 `ui/`

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | VPN 伪装主页 |
| `SettingsActivity.kt` | 真实控制中心 |
| `OnboardingActivity.kt` | 授权引导 |
| `components/RecordButton.kt` | 旧/组件化录音按钮 |
| `theme/` | Compose 颜色和主题 |

### 11.14 `vpn/`

| 文件 | 作用 |
|---|---|
| `VpnServerData.kt` | 假 VPN 节点和假流量统计 |

---

## 12. 开发环境与构建步骤

### 12.1 准备

需要：

- JDK 17
- Android SDK
- Gradle wrapper 已包含
- Windows 下建议使用 Git Bash 或 PowerShell

`local.properties` 示例：

```properties
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

项目已有：

- `local.properties.template`

### 12.2 构建 Debug APK

```bash
./gradlew assembleDebug
```

Windows cmd：

```bat
gradlew.bat assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 12.3 构建 Release APK

```bash
./gradlew assembleRelease
```

注意：当前 release 签名配置未显式配置，默认只能生成未签名或调试相关产物，正式分发需要补 signing config。

### 12.4 安装到设备

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 12.5 查看日志

```bash
adb logcat | grep Cairn
```

关键 tag：

- `RecordingService`
- `AudioPipeline`
- `ParallelMultiWriter`
- `RecoveryScanner`
- `EvidencePackager`
- `NukeEngine`

---

## 13. 手工测试清单

### 13.1 基础启动

1. 安装 APK
2. 打开 App
3. 看到 FastLink VPN 主页面
4. 点击设置能打开 Settings

### 13.2 权限

1. 录音权限
2. 相机权限
3. 位置权限
4. 文件管理权限
5. 通知权限
6. 电池白名单
7. 精确闹钟权限

### 13.3 开始录音

1. 主屏点击 POWER
2. 前台通知显示“语音通话 / 通话中”
3. 通知计时器开始跑
4. Settings 中 Connection Active
5. 存储目录出现伪装文件

### 13.4 文件生成

在设备 shell 中检查，例如：

```bash
adb shell ls -la /sdcard/Download/.cache
```

应看到类似：

```text
dl_thumb_20260521_010203.dat
dl_thumb_20260521_010203.dat.len   # 录音中存在，正常停止后删除
dl_thumb_20260521_010203.gps
dl_thumb_20260521_010203.chain
```

注意：主音频文件内部应以 `CNCE` magic 开头，不是 WAV。

### 13.5 停止录音

1. 主屏点击断开：只是假断开，录音继续。
2. Settings → Full Disconnect：真正停止录音。
3. 停止后 `.len` 应删除。
4. 主容器 header chunk_count 应修复。

### 13.6 导出

1. Settings → Export Latest Session
2. Downloads 出现 zip
3. 拉到电脑：

```bash
adb pull /sdcard/Download/cairn_evidence_<sessionId>.zip .
python verify/verify_evidence.py cairn_evidence_<sessionId>.zip
```

### 13.7 崩溃恢复

1. 开始录音
2. 录音中强杀：

```bash
adb shell am force-stop com.cairn.app
```

或更激烈方式杀进程。

3. 重新打开 App
4. `CairnApp.triggerRecoveryScan()` 应修复 `.len`
5. 导出应可完成

### 13.8 核爆测试

危险：只在测试设备和测试数据上做。

1. 设置 Emergency Security Code
2. 录音生成文件
3. 打开 Rate This App
4. 评论框输入暗码
5. 提交
6. 检查副本目录被清空
7. App 私有数据清空
8. Root 模式下 App 可能自卸载

---

## 14. 已知限制与风险

### 14.1 伦理与法律风险

本项目只能用于用户本人设备上的合法自卫取证。它具备隐藏录音、隐藏路径、静默拍照、伪装通知、Root 隐私指示器处理等能力。如果被安装在他人设备上，可能构成违法监控。

维护时必须坚持：

- 不做远程控制
- 不做远程上传
- 不做 C2
- 不做绕过杀软 / 隐藏进程
- 不做批量部署
- 不做供应链伪装

### 14.2 Google Play 分发风险

由于：

- `MANAGE_EXTERNAL_STORAGE`
- 隐蔽存储
- 前台伪装
- 后台拍照
- Root 增强

Google Play 和国内应用商店大概率无法上架。

推荐：

- F-Droid
- GitHub Releases
- 源码自编译

### 14.3 普通导出与原始审计包边界

当前录音落盘已经是 Android Keystore AES-GCM CNCE 容器。普通导出时，App 会在设备内读取 CNCE、用 Keystore 密钥解密 chunk、校验顺序，并合成为可直接打开的 WAV / JPG / CSV zip 包。

如果未来需要把内部 CNCE 原始副本交给第三方离线解密，仍需另做“导出密码重加密”或“原始审计包”流程。

### 14.4 Root 隐私点隐藏风险

`RootPrivacyDotHide` 修改系统隐私指示器相关设置，属于非常敏感行为，可能违反平台政策或当地法律。保留时应在 README 和 UI 中清楚说明。

### 14.5 `.nomedia` 与隐藏目录不是安全边界

隐藏目录和 `.nomedia` 只能防普通用户和相册/音乐扫描，不防：

- ADB
- Root
- 文件管理器高级模式
- 取证工具

真正保护来自：

- 多副本冗余
- CNCE 加密
- 暗码核爆
- Root chattr 锁

---

## 15. 关键维护原则

### 15.1 不要引入网络权限

任何 PR 如果新增：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

必须视为架构级变更。

如果真的要引入网络：

1. 单独分支
2. README 红字说明
3. UI 明确告知用户
4. 默认关闭
5. 独立安全审计

### 15.2 不要把录音变回明文

旧 `WavWriter` 已删除。新音频路径应始终经过：

```text
AudioPipeline → ParallelMultiWriter → EncryptedChunkWriter → ChunkCipher
```

不要新增任何绕过 `EncryptedChunkWriter` 的音频输出路径。

### 15.3 每秒 fsync 不要随便删

性能会受影响，但这是崩溃可恢复的设计基础。

关键调用：

```kotlin
multiWriter?.flushAndSyncAll()
chainWriter?.flushAndSyncAll()
```

### 15.4 保持 README、docs、代码一致

如果改存储格式，必须同步改：

- `README.md`
- `docs/STORAGE_LOCATIONS.md`
- `verify/verify_evidence.py`
- `verify/decrypt.py`
- `app/src/main/assets/decrypt.py`

### 15.5 Root 功能必须默认显式启用

不要让 Root 增强自动静默执行，除非用户明确开启。

---

## 16. 新开发者最该先读的文件顺序

推荐阅读顺序：

1. `README.md`
2. `HANDOFF.md`
3. `app/src/main/AndroidManifest.xml`
4. `app/src/main/kotlin/com/cairn/app/ui/MainActivity.kt`
5. `app/src/main/kotlin/com/cairn/app/ui/SettingsActivity.kt`
6. `app/src/main/kotlin/com/cairn/app/service/RecordingService.kt`
7. `app/src/main/kotlin/com/cairn/app/service/AudioPipeline.kt`
8. `app/src/main/kotlin/com/cairn/app/storage/ParallelMultiWriter.kt`
9. `app/src/main/kotlin/com/cairn/app/storage/EncryptedChunkWriter.kt`
10. `app/src/main/kotlin/com/cairn/app/crypto/CairnKeystore.kt`
11. `app/src/main/kotlin/com/cairn/app/crypto/ChunkCipher.kt`
12. `app/src/main/kotlin/com/cairn/app/storage/IntegrityChain.kt`
13. `app/src/main/kotlin/com/cairn/app/storage/RecoveryScanner.kt`
14. `app/src/main/kotlin/com/cairn/app/export/EvidencePackager.kt`
15. `app/src/main/kotlin/com/cairn/app/nuke/NukeEngine.kt`
16. `verify/verify_evidence.py`
17. `verify/decrypt.py`

---

## 17. 后续开发优先级

### 17.1 P0：原始审计包 / 导出密码重加密闭环

现状：

- 设备内录音已加密
- 普通导出包已经由 App 内合成为可读 WAV / JPG / CSV
- 外部电脑仍无法直接解密内部 CNCE 原始副本，因为 Keystore 密钥不可导出

目标：

1. 新增可选“导出原始审计包”入口。
2. Settings 导出时弹出“导出密码”输入框。
3. App 使用 Keystore key 解密 CNCE chunks。
4. 用 PBKDF2-SHA256 从导出密码派生导出 key。
5. 用导出 key 重新 AES-GCM 加密 chunks。
6. zip manifest 写入：

```json
{
  "export_encryption": {
    "kdf": "PBKDF2-SHA256",
    "iterations": 200000,
    "salt_b64": "...",
    "algorithm": "AES-256-GCM"
  }
}
```

7. `decrypt.py` 实现完整离线解密，输出 WAV 或 PCM。

### 17.2 P0：单元测试

至少覆盖：

- `ChunkCipher.encrypt/decrypt`
- `EncryptedChunkWriter` header / repair
- `IntegrityChain.verifyChain`
- `DuressCodeMatcher.validateStrength`
- `DuressCodeMatcher.constantTimeEquals`
- `StorageLocations.generateExtremeCandidates`
- `RecoveryScanner.extractSessionId` 相关路径

### 17.3 P1：导出 session 选择 UI

当前导出最近 session。更好的 UX：

1. 扫描所有 session
2. 列出时间、大小、副本数、是否有 GPS / sensor / chain
3. 用户选择导出哪一个

### 17.4 P1：Manifest JSON 改用正式 JSON 库或手写转义

当前 `EvidencePackager.buildManifest()` 用 StringBuilder 手写 JSON。字段目前简单安全，但长期建议：

- 使用 `org.json.JSONObject`
- 或 kotlinx serialization
- 或确保字符串字段做 escape

### 17.5 P1：合并导出验证增强

`verify_evidence.py` 后续应进一步验证：

- GPS / sensor record count 与 manifest 一致
- WAV duration 与 chunk_count / sample_rate 大致一致
- 照片 JPEG magic 与文件数量一致
- README 中 session id 与 manifest 一致

### 17.6 P1：低空间暂停语义修复

当前 `StorageWatchdog.onPause` 调 `audioPipeline.stop()`，这会关闭当前 session。恢复时新建 `AudioPipeline`，sessionId 变化。可能导致一次事件拆成多个 session。

更理想：

- AudioPipeline 支持 pause/resume 写同一 session
- 或明确 UI / manifest 标注分段

### 17.7 P1：Root 开关实际执行链

Settings 里 rootEnabled 当前主要是保存开关；后续需要把具体功能接起来：

- 开启后调用 `RootPermissionGranter.grantAll`
- `RootDozeBypass.enable`
- `RootShutterMute.mute`
- `RootPrivacyDotHide.hide`
- 录音结束后 `RootChattrLock.lockAllLocations`

### 17.8 P2：README 文案继续同步

README 目前仍有一些历史词：

- “WAV header 占位”应完全换成“CNCE header 占位”
- “QQ 通话样式”现在实际是中性通话样式，不出现 QQ 字样

### 17.9 P2：Release 签名和分发

需要补：

- signing config
- reproducible build 指南
- release checklist
- GitHub Actions 或本地脚本

---

## 18. 常见问题排查

### 18.1 构建报 SDK 路径错误

检查：

```text
local.properties
```

是否存在：

```properties
sdk.dir=...
```

### 18.2 App 没有录音文件

检查：

1. 是否授予 `RECORD_AUDIO`
2. 是否授予 `MANAGE_EXTERNAL_STORAGE`
3. 是否 Android 版本限制后台服务
4. `RecordingService` 是否启动
5. logcat 是否有 `AudioRecord failed to initialize`
6. activeLocations 是否为空

### 18.3 通知不显示

检查：

1. Android 13+ 是否授予 `POST_NOTIFICATIONS`
2. 通知 channel 是否被用户关闭
3. `startForeground` 是否调用在服务启动窗口内
4. 前台服务类型权限是否完整

### 18.4 位置文件为空

检查：

1. `ACCESS_FINE_LOCATION`
2. Google Play Services 是否可用
3. 室内 GPS 是否无信号
4. 系统定位总开关是否打开

### 18.5 照片没有生成

检查：

1. Camera permission
2. 是否有 camera hardware
3. 设备是否禁止后台相机
4. `PhotoPipeline` 是否开启
5. logcat `PhotoPipeline`

### 18.6 导出失败

检查：

1. Downloads 写权限
2. `MANAGE_EXTERNAL_STORAGE`
3. `RecoveryScanner.scanAll()` 是否发现 session
4. 最近 session 是否仍有可读副本
5. zip 目标文件是否被占用

### 18.7 merged manifest 又出现网络权限

运行：

```bash
./gradlew assembleDebug
```

检查：

```bash
grep -R "INTERNET\|ACCESS_NETWORK_STATE" app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
```

如果出现，检查 `AndroidManifest.xml` 是否仍有：

```xml
<uses-permission
    android:name="android.permission.ACCESS_NETWORK_STATE"
    tools:node="remove" />
```

`INTERNET` 如果出现，通常是新依赖引入，必须单独审计。

---

## 19. 安全审计重点

审计者应重点看：

1. 是否存在任何网络 API：`java.net`, okhttp, retrofit, WebSocket, Socket, HttpURLConnection
2. 是否存在任何后台上传 / 云同步 / remote config
3. 是否存在硬编码密钥
4. 是否存在明文音频输出路径
5. 暗码是否可能误触发
6. `NukeEngine` 是否可能被普通 Intent 外部触发
7. `FakeReviewActivity` 是否 exported=false
8. `RecordingService` 是否 exported=false
9. `VolumeKeyAccessibilityService` 是否只监听按键，不读取屏幕内容
10. Root 命令是否包含用户输入拼接风险
11. `SecureWipe` 是否会误删非 Cairn 文件
12. `FolderRegistry.getAll()` 是否只返回设计内的 100 个位置

---

## 20. 当前关键事实摘要

- App 主界面：VPN 伪装。
- 前台通知：通话伪装。
- 录音：AudioRecord PCM，每秒 chunk。
- 落盘：CNCE AES-GCM 加密容器，不再是 WAV。
- 哈希链：每秒 `.chain` sidecar。
- GPS：`.gps` CSV。
- 传感器：`.sensor` CSV。
- 照片：`IMG_<sessionId>_<index>.dat`。
- 默认副本：10 处。
- 极端候选：100 处。
- 网络权限：最终 manifest 无 `INTERNET` / `ACCESS_NETWORK_STATE`。
- 导出：最新 session zip，含合并 WAV、照片 JPG、GPS CSV、传感器 CSV、完整性链、manifest、README。
- 核爆：伪评分页暗码触发，三遍覆写 + 清私有数据 + Root 自卸载。
- 构建：`./gradlew assembleDebug` 通过。

---

## 21. 给接手者的第一天工作建议

如果你第一天接手，不要先改 UI。建议按这个顺序做：

1. 跑 `./gradlew assembleDebug`，确认本地环境 OK。
2. 安装到一台测试机。
3. 手动授权必要权限。
4. 录 30 秒。
5. 检查 `/sdcard/Download/.cache/` 是否出现 CNCE 主文件、`.chain`、`.gps`。
6. 停止录音。
7. 导出 zip。
8. 跑 `python verify/verify_evidence.py <zip>`。
9. 阅读 `AudioPipeline → ParallelMultiWriter → EncryptedChunkWriter`。
10. 再决定要不要做第 17.1 的导出密码重加密。

---

## 22. 最后提醒

Cairn 的技术目标是保护用户手里的证据，不是隐藏恶意行为。任何维护都应围绕：

- 用户知情
- 用户控制
- 本地离线
- 可验证取证
- 反胁迫
- 不远程上传
- 不批量部署

只要任何需求偏离这些原则，就应该停下来重新评估。
