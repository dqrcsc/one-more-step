# v1.6.4 诊断修复执行计划

**Goal:** 修复已确认的 1 个 P1、6 个 P2，补齐异常回归及可恢复存储；不发布、不部署、不操作真机。
**Spec:** 2026-11-15 用户批准诊断建议；完整问题见工作区 `one-more-step-analysis/reviews/2026-11-15/review.md`。
**Architecture:** 沿用现有守护进程、会话 guard、SQLite/Inbox 和 Swift IPC；仅补生命周期、预算、下载校验与失败分支。
**Tech Stack:** Kotlin/Android、TypeScript/Bun、Swift；不增加第三方运行依赖。

## 验收与分工

- [x] 镜像：确认绑定窗口与目标，等待后重读保护条件；确认期间窗口/目标/密码变化、断连、急停、超时均禁止原坐标执行。修正人工确认与5秒普通镜像预算冲突。负责文件：`OmsAccessibilityService.kt`、mirror guard/helper 与测试。
- [x] Mac HUD：读循环与写队列分离，写入与关闭协同；已处理状态等待主机确认；真实 Swift IPC 回归证明双向消息及重连工作。负责文件：`mac-hud/main.swift`、`hud/mac-hud-server.ts`、相关测试。
- [x] 传输：ACK 从创建起即观察拒绝；背压期间超时/断连/ERROR 均可回收且只向调用者报告，无 unhandledRejection。负责文件：`transport/session-manager.ts`、相关测试。
- [x] Android 附件与设置：统一最多9份/单件30MiB/总计64MiB（包括语音），校验未知长度真实字节；失败显式记录并提示，原临时URI失效不能假称已发送。相机胶囊缺省关闭且保留显式选择。负责文件：attachment/share/UI/transport/repository默认值及测试。
- [x] OTA：经过认证的预期摘要，限额下载到私有缓存，验证哈希、包名、版本与兼容签名后通过FileProvider交系统安装；失败不启动安装器、不回退明文浏览器下载。覆盖RPC和UI入口。负责文件：OTA helper、transport/UI、Manifest/FileProvider及测试。
- [x] Inbox：提交前校验500条/2GiB已索引附件预算（已有超限数据仍可读，拒绝新增，不自动删资料）；附件复制并同步后提交权威索引，索引失败保留原暂存文件以便重试；衍生视图失败不把已提交数据报告为未收到。负责文件：`inbox-manager.ts`及隔离故障测试。
- [x] 文档：校正旧发布说明的下载校验、配额、附件限制、默认设置、Cookie和语音默认值；记录本轮真实能力与未验收边界，不改已发布GitHub资产。
- [x] 完整回归：TypeScript、Bun、Android JVM、Release APK编译、Swift HUD/蓝牙桥编译；只使用临时目录与模拟设备，保存证据及真机验收清单。

每个模块先用既有复现或新增失败回归锁定问题，再实现最小改动并运行针对性检查。保留当前源码检出，不触碰运行目录；不使用批量删除。

执行结果：见 `docs/review-fixes-2026-11-15.md`。真机验收未执行，代码未推送、发布或部署。
