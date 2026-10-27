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

重启后的首次观察中尚无手机连接事件；这不能被记作自动重连通过。已提供同一局域网 APK 地址，等待覆盖安装。v1.4.1 沿用 v2 协议，不要求重新配对。

## 待完成的真机验收

- [ ] 实时确认手机 v1.4.1/code15，原配对恢复 LAN/v2。
- [ ] 首页诊断入口显示正确，复制内容包含当前网络与历史事件，不含配对秘密。
- [ ] 关闭 Wi-Fi 时提示明确；重新打开后恢复连接、清除上次失败原因。
- [ ] Mac 日志有对应打开/认证/关闭时间线。

研究工作区证据：`one-more-step-analysis/verification/ota-1.4.1/`。本次尚未发布新的 GitHub Release。
