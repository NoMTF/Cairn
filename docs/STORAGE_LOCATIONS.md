# Cairn 存储位置（开源公开）

> **此文档为开源透明义务的一部分**：所有默认副本位置在此公开。
> App 内部不显示这些路径字符串，避免被对方在 App 设置中看到。

## 普通模式：10 处默认位置

每次录音会同时写入以下 10 处。任一处文件完整即可恢复录音。

| # | 路径 | 文件名模式 | 假装是 |
|---|---|---|---|
| 0 | `/Download/.cache/` | `dl_thumb_<sessionId>.dat` | 下载缩略图缓存 |
| 1 | `/DCIM/.thumbnails/` | `.thumbdata4--<sessionId>` | Android 相册缩略图（系统就长这样） |
| 2 | `/Pictures/.metadata/` | `cover_<sessionId>.bin` | 相册元数据 |
| 3 | `/Music/.albumart/` | `albumart_<sessionId>.cache` | 音乐封面缓存 |
| 4 | `/Movies/.thumbs/` | `video_thumb_<sessionId>.dat` | 视频缩略图 |
| 5 | `/Documents/.tmp/` | `doc_cache_<sessionId>.tmp` | 文档临时文件 |
| 6 | `/Alarms/.cache/` | `alarm_log_<sessionId>.bin` | 闹钟缓存日志 |
| 7 | `/Notifications/.history/` | `notif_<sessionId>.bak` | 通知历史 |
| 8 | `/Recordings/.system/` | `voicemail_<sessionId>.amr` | **用户可自定义路径的那一处** |
| 9 | `/Android/media/com.android.providers.downloads/.bak/` | `media_<sessionId>.nomedia` | 媒体备份 |

每处目录会同时放一个 `.nomedia` 文件，避免被 MediaStore 索引出现在用户的相册/音乐 App 中。

## 极端模式：100 处候选

首次启动时，App 基于设备指纹（持久化在 DataStore）生成确定性的另外 90 处候选目录。
这些目录散布在：

- 12 个标准公共目录的 10 种隐藏子目录组合中
- 多个 `/Android/data/<伪装包名>/cache/` 路径
- 多个 `/Android/media/<伪装包名>/.bak/` 路径

具体路径由 SHA-256(device_seed + index) 决定，重装 App 可通过 manifest 重建。

## 文件命名约定

- `sessionId` = 录音开始时刻的 `yyyyMMdd_HHmmss` 格式
- 音频主文件：见上表
- GPS sidecar：`<原文件>.gps`（CSV）
- 传感器 sidecar：`<原文件>.sensor`（CSV）
- 照片：`IMG_<sessionId>_<序号>.dat`（实为 JPEG）
- 长度修复 sidecar：`<原文件>.len`（仅崩溃恢复用，正常关闭后会删除）

## 恢复流程

1. App 启动时，`RecoveryScanner.scanAll()` 扫描全部 100 处目录
2. 按 `sessionId` 聚合同一次录音的不同副本
3. 检测 `.len` sidecar 存在 → 调用 `EncryptedChunkWriter.repairFromLenFile()` 修复未关闭的 CNCE 加密容器头
4. SHA-256 校验完整性
5. 任一份完整即可还原；若所有副本都损坏，从最大的那份截断恢复
