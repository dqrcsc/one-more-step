# Connection Diagnostics Implementation Plan

**Goal:** 离线时可查看原因并复制无密钥的连接时间线。
**Architecture:** Kotlin 独立有界日志与固定事件枚举；transport 只传固定码；Compose 对话框消费状态。Mac 用固定事件码和每连接编号写现有守护日志。
**Tech Stack:** Kotlin/JVM, Compose, Bun/TypeScript，沿用现有依赖。
**Spec:** `docs/plans/2026-10-27-connection-diagnostics-design.md`

本次在当前会话顺序实施；不另开代理，不改变连接协议。

## Task 1: 手机原因与日志
- [ ] 新建 `ConnectionDiagnostics.kt` 和 JVM 测试。接口 `record(Event, tier, attempt)`、`offlineSummary()`、`exportText()`；只允许固定枚举，最多 200 条，重复事件 30 秒内合并。异常仅分类为超时/拒绝/网络 IO/其他，不存 message。
- [ ] 在 `OmsTransportClient.kt` 接入 Wi-Fi 回调、LAN 尝试/探测失败、socket 失败、握手超时/拒绝、认证成功及关闭；旧 generation 的回调不覆盖当前原因。
- [ ] `MainActivity.kt` 添加离线原因与诊断对话框、复制入口。导出离线可用，不自动外发。
- [ ] JVM 测试检查失败后重试保留原因、恢复清除原因、备用链路隔离、历史重载、200 条上限与敏感消息不进入导出。

## Task 2: Mac 与验证
- [ ] `session-manager.ts` 添加连接事件、关联编号与固定失败分类；不记录远端关闭原因或原始握手异常。
- [ ] Bun 测试截获事件，验证打开/认证/关闭和安全拒绝，确保不包含测试凭据。
- [ ] 运行类型检查、Bun 测试、Android JVM/构建；记录结果和安装验收边界。
- [ ] 生成新候选 APK（v1.4.1/code15），保留 v1.4.0 发布包；完成本地交付，不自动发布新 GitHub Release。
