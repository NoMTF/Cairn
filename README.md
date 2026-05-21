# Cairn / FastLink VPN

<p align="center">
  <a href="https://github.com/NoMTF/Cairn/releases/latest/download/FastLink.apk">
    <img alt="一键下载 APK" src="https://img.shields.io/badge/一键下载-FastLink.apk-16a34a?style=for-the-badge&logo=android&logoColor=white">
  </a>
</p>

<p align="center">
  <a href="https://github.com/NoMTF/Cairn/releases/latest">
    <img alt="Latest Release" src="https://img.shields.io/github/v/release/NoMTF/Cairn?style=flat-square&label=latest">
  </a>
  <img alt="Offline" src="https://img.shields.io/badge/network-offline-lightgrey?style=flat-square">
  <img alt="No Internet Permission" src="https://img.shields.io/badge/permission-no%20INTERNET-blue?style=flat-square">
  <img alt="Android" src="https://img.shields.io/badge/android-24%2B-3ddc84?style=flat-square">
</p>

> Cairn 是一个 Android 离线取证 App。它在前台伪装成 **FastLink VPN**，在本地保存加密录音、位置、传感器和诊断照片，用于极端个人安全场景下的自卫记录。

Cairn 面向没有安全见证人、没有可信网络、也没有第二次机会的人。比如因为性别身份、性倾向、亲密关系、家庭控制、族裔、宗教、残障、政治表达、职业举报或公共抗议而遭到威胁、羞辱、围堵、盘问、驱赶、暴力或机构性迫害。

它不是云录音机，也不是远程监控工具。Cairn 不申请 `INTERNET` 权限，不上传，不远程控制，不做批量部署。它只为用户在自己的设备上主动开启记录时，尽量保住本地证据。

## 下载安装

点击顶部绿色按钮，或直接下载：

[下载最新版 FastLink.apk](https://github.com/NoMTF/Cairn/releases/latest/download/FastLink.apk)

SHA-256 校验文件：

[FastLink.apk.sha256](https://github.com/NoMTF/Cairn/releases/latest/download/FastLink.apk.sha256)

## 看起来是什么

打开后你看到的是一个完整的 VPN 工具：

- 主页面品牌：`FastLink VPN`
- 服务器节点、延迟、上下行速率、连接时长
- `已用流量` 显示当前会话真实本地缓存大小
- 前台通知显示为普通语音通话
- 设置页全部使用线路、缓存、加速、诊断等 VPN 术语

真实行为：

- 点击主屏电源按钮：开始本地记录
- 主屏点断开：只改变界面状态，不真正停止记录
- 设置页 `完整线路重置`：停止音频，诊断照片 / 位置 / 传感器可继续
- 设置页 `关闭全部线路缓存`：停止全部记录和恢复状态
- `导出最新诊断包`：输出可直接打开的证据 zip

## 首次使用

1. 安装并打开 `FastLink VPN`。
2. 首次进入会出现 `线路初始化`。
3. 选择运行模式：`标准线路`、`长续航线路` 或 `极限保活线路`。
4. 选择流质量、诊断模块、快照策略和多线路冗余。
5. 继续授权麦克风、相机、位置、通知、文件管理、电池和定时巡检。
6. 回到主屏，点击电源按钮开始连接。
7. 需要导出时：`设置 -> 导出连接日志 -> 导出最新诊断包`。

导出文件位于 Downloads，命名类似：

```text
FastLink_diagnostics_<sessionId>.zip
```

## 极端场景设计

Cairn 为高压、断网、被检查手机、进程被杀、低电量、普通删除尝试等情况设计：

- **零网络路径**：最终 manifest 不含 `INTERNET` / `ACCESS_NETWORK_STATE`。
- **本地加密**：内部音频写入 CNCE AES-GCM 分块容器。
- **每秒落盘**：1 秒 chunk + flush / fsync，降低突然中断损失。
- **崩溃修复**：`.len` sidecar 可修复未正常关闭的容器头。
- **多副本冗余**：默认 10 处隐蔽缓存目录，极端模式扩展到 100 个候选位置。
- **取证上下文**：可记录 GPS、传感器、周期性诊断照片。
- **诊断续跑**：音频停止后，已开启的诊断 sidecar 仍可继续写同一 session。
- **按需恢复**：只有用户明确开启过的音频或诊断才会被 Alarm / WorkManager / 开机恢复链尝试恢复。
- **哈希链**：每秒音频 chunk 生成 SHA-256 连续性链。
- **紧急清理**：用户设置恢复密钥后，可在反馈页触发本地不可逆清理。

Android 限制也必须讲清楚：用户 Force stop 之后，普通 App 不能保证自行复活。Cairn 不实现隐藏绕过用户强停的复活逻辑。

## 导出包内容

普通导出包不要求第三方解析内部加密块。App 会在设备内读取 CNCE、用 Android Keystore 解密 chunk，并合成为可直接查看的文件：

```text
audio/session_<sessionId>.wav
photos/*.jpg
gps/gps.csv
sensors/sensors.csv
integrity/integrity_chain.csv
manifest.json
README.md
```

`manifest.json` 记录导出时间、原始主副本 SHA-256、输出文件 SHA-256、chunk 数、照片数量、GPS / 传感器记录数、音频停止时间和诊断续跑时间。

在电脑上验证：

```bash
python verify/verify_evidence.py FastLink_diagnostics_<sessionId>.zip
```

## 设置项对照

App 内显示 | 实际功能
---|---
`线路初始化` | 首次运行配置和授权
`运行模式` | 选择耗电 / 诊断 / 保活策略
`标准线路` | 均衡模式
`长续航线路` | 降低照片、定位、传感器和巡检频率
`极限保活线路` | 提高巡检频率，默认偏向冗余
`流质量` | 音频采样率
`紧凑线路` | 8 kHz
`标准线路` | 16 kHz
`高保真线路` | 44.1 kHz
`加速模块` | 取证 sidecar 功能
`智能地区路由` | GPS / 位置记录
`连接诊断` | 周期性诊断照片
`信号质量监控` | 加速度、陀螺仪、磁力计记录
`快照调校` | 照片间隔和 JPEG 质量
`多线路冗余` | 多副本候选位置
`启用 100 节点路由` | 极端模式候选目录
`完整线路重置` | 停止音频，诊断可继续
`关闭全部线路缓存` | 停止音频、照片、位置、传感器和恢复状态
`私有线路恢复密钥` | 紧急清理暗码
`连接体验评分` | 伪反馈页 / 暗码入口
`导出连接日志` | 导出最新证据包
`外观` | 桌面图标伪装
主屏 `已用流量` | 当前会话主副本目录真实大小

## 构建

需要 JDK 17、Android SDK 和 API 36：

```powershell
.\gradlew.bat app:check assembleDebug
```

Release 签名使用 `keystore.properties`，不要提交 keystore：

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

推送 `vX.Y.Z` tag 会触发正式 GitHub Release，上传：

```text
FastLink.apk
FastLink.apk.sha256
```

## 审计红线

不要加入：

- `INTERNET` 权限
- 云上传、远程控制、remote config、analytics
- 批量部署、静默安装、后门
- 绕过杀软或隐藏进程
- 明文音频落盘路径
- 用户不知情时启动记录的逻辑

Cairn 只能用于用户本人设备上的合法自卫取证。录音、拍照和证据保存是否合法，取决于当地法律和具体场景。

## License

GPL-3.0。衍生版本应继续开源、可审计，并对它声称要保护的人负责。
