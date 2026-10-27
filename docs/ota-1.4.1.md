# v1.4.1 连接诊断候选验收

日期：2026-10-27（Asia/Shanghai）。用户要求补充离线原因提示和连接诊断日志。

## 构建与自动验证

- TypeScript 两个 workspace 类型检查通过。
- Bun：52 pass / 0 fail / 405 assertions。
- Android JVM：37 pass / 0 fail / 0 error / 0 skipped；assembleDebug 成功。
- APK：v1.4.1/code15，22,465,837 bytes。
- SHA-256：`6a8bb5bd86cb78d3d0b6709723e571d0830cd575d6f6ca62c22fee53f613efb7`。
- 签名证书 SHA-256：`806caa5647ae4119df3ba6b32cd4131d94147e66d4a4992b80f7e7ac94a1e4b7`，与 v1.4.0 相同；仍为 Debug 签名。

## 本机部署

实现提交 `1e108f4` 已快进部署到安装仓库。v1.4.0 APK/元数据备份于 `~/.one_more_step/backups/pre-1.4.1-20261027-101215`，没有改动信任记录或清理用户数据。守护进程重启后 PID 1856 → 10564，HTTP OTA 元数据读回版本、码、哈希一致。

重启后的首次观察中尚无手机连接事件；这不能被记作自动重连通过。随后用户通过同一局域网 APK 地址覆盖安装，实时核验 1.4.1/code15 与原 v2 配对；未重新绑定。v1.4.1 沿用 v2 协议，不要求重新配对。

## 待完成的真机验收

- [x] 实时确认手机 v1.4.1/code15，原配对恢复 LAN/v2；诊断码 CONNECTED，队列为 0。
- [x] 用户打开首页诊断入口并复制成功；读回专用前缀、449 字符的诊断，包含网络元数据及 STARTED/WIFI_AVAILABLE/CONNECTING/HANDSHAKE_STARTED/CONNECTED 时间线，无配对字段或 64 位十六进制秘密。
- [x] 关闭 Wi-Fi 后，用户复制的诊断包含“未检测到 Wi-Fi”、当前链路 DISCONNECTED、Wi-Fi 可用 false，并记录 CONNECTION_LOST → WIFI_UNAVAILABLE；恢复后实时码 CONNECTED、LAN_WS/v2、待发送队列 0。
- [x] Mac 日志持久化可用，包含 OPENED/AUTHENTICATED/WS_CLOSED/CLOSED，按连接编号关联。

研究工作区证据：`one-more-step-analysis/verification/ota-1.4.1/`。本次尚未发布新的 GitHub Release。

手机界面无障碍观察返回 0 个节点，因此没有自动点击未知位置；诊断入口与复制由用户手动操作，剪贴板读回仅在专用前缀校验后保留固定事件。

## 验收结论

本次诊断入口、复制、无 Wi-Fi 提示及网络恢复提示的真机验收完成。候选安装包与已测代码一致；网络不可达和握手拒绝分类的其他组合由自动测试支撑，未在真实用户配对上注入恶意帧。此版本不宣称根治访客网断流。

证据包括 `build-evidence.json`、`host-deploy.json`、`post-install.json`、`ui-copy-results.json`、`wifi-copy-results.json`、`wifi-control-observation.json`。代码已在本机提交并部署，v1.4.1 尚未推送或发布 GitHub Release。
