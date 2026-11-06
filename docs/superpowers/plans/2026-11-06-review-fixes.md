# v1.6.1 审查问题修复计划

**Goal:** 按用户批准的审查报告先修三个 P1，再修六个 P2，完成自动化回归并列出真机边界。
**Spec:** 用户 2026-11-06 明确同意先修三个 P1、再修六个 P2 并完成回归；问题与实现对照见 [修复记录](../../review-fixes-2026-11-06.md)。
**Architecture:** 保留既有 daemon/session 和 Android guard；恢复缺失防护，给跨等待操作绑定同一会话/绝对期限/取消信号；失败明确返回，不伪造成功。无需新依赖。
**Tech Stack:** Kotlin/Android、TypeScript/Bun、Swift/CoreAudio（只在路由核验需要时修改）。

## 约束与成功标准

- 在现有源码检出 c111c31 上工作；不覆盖运行目录的未提交改动、不重启服务，不拨号、播放声音、切换耳机或操作真机。
- 不改既有公开标签/APK；发布说明校正到已下载验证的 build38 与 SHA-256。
- 镜像：分屏/密码窗口坐标手势被拒；胶囊可见才允许手势；本地触摸中止使后续请求失败，旧排队请求不因恢复而重获授权。
- 通话：Mac 等待、播放、挂断全过程受原会话和绝对期限约束，失效时取消播放，不挂断新会话。
- 配对：加密失败不提交任何新配对元数据，不回退明文；旧配置保持可读。
- AI 通话：只写允许包/窗口内可见、启用、非密码输入框，写前复核。
- 外部分享：带 user-info 的 authority 不绕过敏感 provider 拦截。
- Mac 传话：输出未确认是内置扬声器时明确失败，不以 afplay 结束代替路由确认。
- CI：仓库不再硬编码 Mac JDK，现有 Ubuntu job 可用 JAVA_HOME；本地测试使用显式环境变量。

## 阶段 1：三个 P1

- [x] Android OmsAccessibilityService / GuiSessionGuard：恢复镜像多窗口/密码判断、activeAction、急停胶囊与取消锁存；补纯策略及操作生命周期回归。
- [x] Mac oms-daemon/session-manager：接听后动作复用原绑定会话和期限，可取消音频进程；补过期、会话替换、取消播放和挂断目标回归。
- [x] 运行 P1 针对性检查并确认完成，再进入 P2。

## 阶段 2：六个 P2

- [x] StreamRepository：原子准备加密后才提交配对，失败关闭；补读取/保存失败回归。
- [x] AI 通话输入：限定包/窗口和非密码属性，写入前复核，补策略测试。
- [x] Mac 外放：明确核验内置扬声器或失败关闭；测试未知/蓝牙输出拒绝。
- [x] ShareInputPolicy：拒绝外部 user-info authority，测试 0@sms 等与正常附件。
- [x] gradle.properties/CI：删除平台私有 JDK 路径，验证显式 JAVA_HOME 生效。
- [x] v1.6.1 文档：校正 build38/SHA，添加校验文件，不替换已发布包。

## 阶段 3：回归与交付

- [x] TypeScript + Bun 完整测试；Android JVM 完整测试与 APK 编译；若改 Swift 则构建桥接。
- [x] 检查差异、更新修复说明与必要真机验收清单；不将静态/隔离测试称为真机通过。
