# Cairn

> 一座为遭遇人权侵害、投诉无门的人筑起的「石碑」。
>
> Cairn /kɛərn/ 源自苏格兰盖尔语，指登山者沿路堆砌的石头标记 ——
> 每块石头由不同的人留下，历经风雨仍指引后来者方向，纪念走过的旅人。
>
> 一份录音以多副本物理冗余分散各处，任一份留存即是「证据的标记」。

## 设计原则

1. **离线第一** —— 不申请 INTERNET 权限，整个 APK 拆开来不存在任何网络代码路径。
2. **物理冗余** —— 同一份 PCM 流并行写入 10 / 100 处隐蔽位置，各自 fsync。
3. **崩溃即可读** —— 自己写加密容器，header 可后修复。进程被杀也保已录部分。
4. **每秒持久化** —— 1 秒为 chunk 边界，强制刷盘，丢失上限 < 1 秒。
5. **取证链可验证** —— 哈希链 + 时间戳 + 传感器 + GPS + 静默照片。
6. **代码 100% 开源** —— 默认位置开放在 docs/，App 内部不显示。
7. **可降级** —— 普通用户开箱即用；进阶用户开极端模式；Root 用户开 Root 增强。
8. **零摩擦上手** —— 一键授权 + 自动厂商引导，2 分钟从下载到能用。

## 功能

### 核心
- **10 处隐蔽多副本同步写入**（默认）
- **100 处候选池**（极端模式，用户可勾选启用）
- **崩溃安全**：WAV header 占位 + sidecar 修复 + 每秒 fsync
- **每秒 GPS 位置**（sidecar CSV）
- **后台静默拍照**：Camera2 + 无 Root 关快门音组合技
- **三轴传感器同步采集**（加速度 / 陀螺 / 磁力计）
- **通知伪装**：前台服务通知做成 QQ 语音通话样式
- **七层后台保活**：前台服务 + WakeLock + 电池白名单 + AlarmManager + JobScheduler + 厂商 ROM 引导 + Root 自动化
- **存储空间保护**：< 10% 自动暂停（阈值可调 1-50%）

### 一键授权
- 普通模式：7 步状态机，约 2 分钟
- Root 模式：静默授权，约 2 秒

### Root 增强
- 静默授予全部权限（`pm grant`）
- bypass Doze（`dumpsys deviceidle whitelist`）
- 关快门音（`setprop audio.camera.shutter.disable 1`）
- 关隐私指示器（设备 12+ 的录音绿点、相机橙点）
- chattr +i 文件锁（普通用户删不掉）
- 自卸载（核爆后 `pm uninstall --user 0`）

### 紧急自救
- **伪评论暗码引爆**：评论框输入预设暗码 → DoD 3 遍覆写全部副本 + 清数据 + Root 自卸载，UI 显示"感谢反馈"
- **诱饵录音**：可生成假录音放桌面
- **应急清空键**：设置中手动核爆

### 取证导出
- Zip 取证包：音频 + GPS + 传感器 + 照片 + 哈希链 + manifest + README
- 独立 Python 验证脚本 `verify/verify_evidence.py`（仅依赖标准库）

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- minSdk 24 / targetSdk 34
- AudioRecord (PCM) + AES-GCM 分块加密 CNCE 容器
- Camera2 API + ShutterSilencer 组合技
- FusedLocationProviderClient
- libsu (Root)
- DataStore Preferences

## 关键路径

- `docs/STORAGE_LOCATIONS.md` — 默认 10 处副本位置（开源公开）
- `docs/DURESS_CODE_SETUP.md` — 销毁暗码使用指南
- `verify/verify_evidence.py` — 取证包独立验证脚本
- `app/src/main/kotlin/com/cairn/app/storage/StorageLocations.kt` — 副本路径源代码
- `app/src/main/kotlin/com/cairn/app/service/AudioPipeline.kt` — 核心录音管线
- `app/src/main/kotlin/com/cairn/app/notification/QqCallStyleNotification.kt` — QQ 通话伪装通知

## 分发

- **F-Droid**（推荐）
- **GitHub Releases** APK + AAB
- ❌ Google Play / 国内应用商店：被 `MANAGE_EXTERNAL_STORAGE` + 隐藏行为政策拦截

## 风险与提醒

- 本工具仅供公民取证 / 自卫记录用途，使用须遵守当地法律。
- QQ 通知伪装中的图标和文案为**足够差异化的自绘版本**，避免商标直接侵权。
- Root 是双刃剑 — Root 设备本身安全性低于非 Root，建议用 Magisk。
- 暗码核爆**不可逆**。出厂默认码开源公开，首次启动**强制**用户改码。
- 全程零网络权限是设计基石。任何 PR 引入 `INTERNET` 必须独立分支 + README 红字声明。

## License

GPL-3.0 — 强 copyleft 保证衍生版同样开源、不被植入后门。
