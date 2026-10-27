# v1.4.0 安全传输 OTA 验收

日期：2026-10-27（Asia/Shanghai）。基线为 v1.3.9 / code13；用户已确认统一加密、重新配对一次、取消 HTTP 短码入口和拒绝明文回退。状态：两端已部署，已完成新配对、LAN 双向投递和服务重启后的自动加密重连；GUI 正向放行和换页拒绝回归亦通过，本次 LAN 升级验收完成。

## 已完成的本地检查

- TypeScript 两个 workspace 通过。
- 全量 Bun：49 项通过，0 失败，391 次断言。
- Android 最后冻结源码再次编译、打包与 JVM 测试：32 项通过，0 失败/错误/跳过。
- TypeScript、JCA 和独立 Python cryptography 的公开测试向量完全一致；标准 HKDF/GCM 已知答案通过。
- WebSocket 模拟新配对、两次重连、加密 RPC 和双向文件/ACK通过；文件覆盖 0 字节和跨 64 KiB 分块，合成内容/密钥/文件名/遥测未出现在原始 wire 中。
- 负面场景及限制详见 [安全传输 v2](secure-transport-v2.md)。模拟测试不代替实际手机验收或外部协议审计。

## 安装包

- `applicationId`: `com.oms.client`
- `versionName`: `1.4.0`
- `versionCode`: `14`
- 大小：22,770,825 bytes
- SHA-256：`402a4ce633e8d35b98000af4203b96574ef7d9c7e26a9fc8ef1b7da579de3e58`
- 签名证书 SHA-256：`806caa5647ae4119df3ba6b32cd4131d94147e66d4a4992b80f7e7ac94a1e4b7`，与已安装 v1.3.9 相同。

候选包和输出元数据已原子替换到安装仓库的 OTA 目录；本机 `/apk/version` 回读版本、码和哈希一致。用户随后确认覆盖安装，实时手机遥测再次核实版本为 1.4.0/code14。

## 备份与部署顺序

升级前备份位于本机受限目录：`~/.one_more_step/backups/pre-1.4.0-20261027-092236`。包含 config、host identity、trusted devices、旧 APK/元数据和已安装 Skill；目录权限0700，备份文件0600。此次不迁移数据库或清空收件内容。

旧 APK SHA-256：`d7747851d08acc405aacf1c5db86eb37dd83ec6d0122d9a60d2d5fe16767666b`。Android通常拒绝直接覆盖低 versionCode，不能把旧 APK 备份称为可直接 OTA 降级。

实际步骤：旧主机提供新 APK → 用户覆盖安装 → 切换 Mac 到 v1.4.0 → 本地生成一次性完整链接 → 用户完成新配对 → LAN 真机验证。新手机拒绝旧主机是预期的拒绝降级；期间离线队列保留。

## 当前设备状态与交付

准备升级时 Mac IPC 和19527监听正常，手机显示 DISCONNECTED；缓存显示1.3.9，不能据此判断手机实时锁屏/版本状态。用户打开App后仍反馈“离线队列模式”。提供当前 Mac LAN APK 下载链接，独立于App连接。

用户明确要求微信推送安装链接后，使用 wechat-as-context skill 发送：工具返回 `sentCount=1 / failedCount=0 / pendingCount=0`。消息仅包含 APK 地址和安装说明，不含新的配对 token。用户确认安装后，Mac 安装仓库快进到 `a9641dd` 并重启守护进程；同步全局 OMS Skill。新的一次性 URI 仅在本机保存并显示二维码，用户扫描、粘贴并确认配对成功。

## 真机验收进度

- [x] 手机安装 v1.4.0/code14；实时遥测核实。Mac 安装源码与候选构建一致。
- [x] 用户通过本地完整 URI 完成 v2 配对；实时状态为 LAN_WS、secureProtocolVersion=2。
- [x] Mac 到手机 0 字节和 65,792 字节文件收到落盘 COMPLETED，返回长度和 SHA-256 与测试文件一致。
- [x] 手机发送合成标记 `OMS140-OK`，Mac 查询到精确匹配记录，手机 pendingOutboxCount=0。
- [x] Mac 服务重启后采样先为 DISCONNECTED，随后自动恢复 LAN_WS/v2；无需再次扫码，实时状态 RPC 正常。
- [x] 已部署 HTTP `/pair/resolve` 返回 410，不含凭据字段。
- [x] GUI 确认放行：从系统设置返回桌面，RPC 成功；用户确认确认条、操作胶囊同时消失。
- [x] GUI 页面变化拒绝：从青简切换到系统设置后点放行，返回 `ERR_STALE_OBSERVATION: Window or package changed`；遥测与用户均确认仍在系统设置。

旧配对拒绝、重放/旧帧/旧请求隔离的负面证据来自自动化测试；本次真机未向日常设备注入旧密钥或恶意帧。自动重连成功不替代这些负面测试。BLE/WAN 尚未真机验收。

证据在研究工作区 `one-more-step-analysis/verification/ota-1.4.0/`：`host-upgrade.json`、`current-status.json`、`push-results.json`、`inbox-results.json`、`reconnect-results.json`、`removed-http-pairing.json`、`gui-allow-results.json`、`gui-change-results.json`、`user-confirmations.json`。仅保存过滤后的状态与合成测试记录，不记录真实配对密钥、通知正文或屏幕内容。尚未发布 GitHub Release。
