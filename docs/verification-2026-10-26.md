# 2026-10-26 稳定性修复验证记录

基线：v1.3.6 / `2f5b22a1ba0442d44550f0a03e5233549e38e176`。分支：`fix/reliable-device-bridge`。实现范围与兼容要求见 [implementation-status.md](implementation-status.md)。

## 已执行

| 检查 | 结果 |
|---|---|
| `bun run typecheck` | shared-protocol、mac-host 均通过 |
| `bun test` | **30 pass / 0 fail / 200 assertions**，含原 8 项测试 |
| Android `:app:compileDebugKotlin` | 通过；保留旧蓝牙 API deprecated 等编译警告 |
| Android `:app:testDebugUnitTest` | **19 tests / 0 failures / 0 errors** |
| Android `:app:assembleDebug` | 通过，产出本地 debug APK |
| Swift bridge `swiftc -typecheck` | 通过 |
| `git diff --check` | 通过 |

Android JVM 分布：GuiSessionGuard 8、DeliveryPolicy 7、StoragePathPolicy 2、FrameStreamDecoder 2。它们覆盖纯规则；实际 ContentResolver/SQLite、浮层回调和 GATT 行为仍需真机验收。

首轮 Android 离线测试因 JUnit/Hamcrest 未缓存失败；补齐标准测试依赖后离线复测通过。没有把这一环境失败当成产品测试通过。

## 针对性回归

- 固定签名和公开 room HMAC 拒绝；正确配对密钥认证通过。
- 未认证/其他连接的 RPC 与 ACK 不能兑现；替换连接和同连接重新配对均撤销旧等待器。
- 非法 UUID、错误 hash/长度/分块范围、跨连接分块、超过上限和畸形帧被拒绝。
- 文件推送等待接收端 COMPLETED；ERROR/超时不报告送达；等待器在超时后移除监听器。
- 缺附件不提交收件箱；重名文件不覆盖；索引写入失败保持旧状态；索引已提交但派生视图失败可由同 ID 重试恢复。
- 面板拒绝跨站/错误 Content-Type/非回环 Host；文件读取拒绝未注册路径、相邻前缀和符号链接外跳。
- 快速审批不丢响应；非法选项映射/过期响应拒绝；相同决策可幂等重试；GUI 与系统导航共享绝对 deadline 和外层等待预算。
- 旧观察、缺观察、急停、过期、已撤销连接不能执行 GUI；仅手机恢复能清急停。

## 构建产物

`android-app/app/build/outputs/apk/debug/app-debug.apk`

SHA-256：`f92a098073a422944183e525deb64732fc7dc84a95c1b9cdb20af2e5050f5414`

这是开发分支的 debug 构建，仍保留原版本号 1.3.6 / versionCode 10；未制作正式升级版本、上传 Release、安装手机或更新现有 oms 服务。

## 未覆盖

未进行真机、Wi-Fi 断线、BLE MTU/背压、WAN relay、Android 重启和操作系统权限验收。未实现首次配对/业务数据端到端加密或持久化断点续传。Mac 多文件派生视图没有跨文件事务与断电级持久性证明。下一阶段按 [真机清单](implementation-status.md#真机验收清单执行前需明确部署目标) 验收，再决定发布。
