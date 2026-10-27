# 当前实现边界与升级验收

本文件描述 v1.3.8 的稳定性修复、v1.3.9 的 GUI 窗口事件修复与 v1.4.0 的安全传输（保留 v1.3.7 验证码同步功能）。它优先于旧模块设计文档中的能力设想。部署与真机验证进度见 [v1.3.8 OTA 记录](ota-1.3.8.md) 和 [GUI 安全验收及 v1.3.9 复测](gui-safety-acceptance-2026-10-26.md)。

v1.4.0 配对方式、加密范围见 [安全传输 v2](secure-transport-v2.md)，本机部署与 LAN 真机结果见 [v1.4.0 OTA 验收](ota-1.4.0.md)。

## 本批实现

| 范围 | 现在的行为 | 主要源码 |
|---|---|---|
| 重连认证 | v2 配对密钥认证临时 ECDH 握手，双向 AES-GCM 加密业务帧；旧配对需重配，拒绝明文降级 | `session-manager.ts`、`OmsTransportClient.kt` |
| 撤销 | 重新配对撤销旧连接和旧 RPC/文件等待器；同一物理连接重配也撤销旧操作 | `session-manager.ts` |
| RPC | 请求/响应绑定发起连接，不能被另一条已认证或未认证连接兑现 | 两端 transport |
| 手机到 Mac | 附件完成并校验后才能提交 inbox；收到相同 itemId 的成功 ACK 才标记 DELIVERED；失败保留 QUEUED | 两端 transport、`InboxManager` |
| Mac 到手机 | 校验 UUID、长度、分块范围与 SHA-256；文件和 SQLite 记录成功后返回 COMPLETED；Mac 收到 ACK 才返回成功 | 两端 transport |
| 资源边界 | 单流 64 MiB、单块 65,536 字节、最多 65,536 块、16 个同时接收流；单帧 payload 16 MiB、解码累计缓冲有上限 | 两端 framing / delivery policy |
| 收件箱 | UUID 路径校验、全部附件预检、重名保护；索引原子替换；latest、pending 队列、meta 从索引重建 | `inbox-manager.ts` |
| 本地面板 | 回环地址/Host、同源 Origin、JSON 类型校验；预览与文件读取仅允许注册附件的规范路径 | `inbox-web-dashboard.ts` |
| 目录授权 | content URI 必须是 MediaStore 且位于已授权目录；文件路径按 canonical 目录边界判断 | `ScopedStorageExecutor.kt` |
| GUI | 主线程串行；节点动作必须携带 observationId；执行前和确认后复核观察；绝对 deadline 最长 60 秒 | `OmsAccessibilityService.kt`、`GuiSessionGuard.kt` |
| 人工确认与急停 | 没有可用浮层则拒绝 GUI；取消/到期撤除确认框；急停持久化，手机端显式恢复后重新观察 | `SafetyStopOverlay.kt` |
| 审批 | 先登记等待器再发送；统一 1–3600 秒期限；保留自定义选项，第一项批准、其余拒绝；过期和非法选择不 ACK | daemon、IPC、手机 UI |

“写入成功”指应用已完成文件/索引/SQLite 提交并收到确认，**不等于跨两端事务或断电级持久性保证**。Mac 索引使用同目录临时文件后 rename；派生视图不是多文件原子事务，可由重试/重启恢复。删除收件箱条目和清理已处理项仅修改索引，磁盘附件保留。

## 升级兼容性

1. 主机与 Android 必须一起升级。旧版手机可能不回送文件 ACK，新主机会明确报告未确认送达。
2. v1.4.0 要求 `pairingVersion=2`；所有旧配对必须重新配对。新长期密钥由双方派生，不通过网络明文下发。完整一次性链接只在 Mac 本地展示，HTTP 六位码解析入口关闭。
3. 节点编号只对对应观察有效。`gui.observe` 返回 `observationId`；每次重新观察替换旧观察，应用窗口/内容改变也会使旧编号失效。
4. `gui.act` 的 `deadlineMs` 由当前 CLI/MCP/daemon 自动添加。直接调用手机 RPC 的客户端必须传入绝对毫秒期限。
5. `sys.control/system_action` 同样传递绝对期限并受 GUI 急停约束。原生 `sys.control` 打开网址、启动 App、音量等能力保持原独立路径，**急停不是所有远程系统能力的总开关**。
6. `content://` 读取仅支持可核验的 MediaStore 路径。其他 DocumentProvider URI 会被拒绝；系统分享入队仍由手机端持有的 URI 读取并复制到私有文件。

GUI 示例（把示例 observation 值替换为刚才返回的真实值）：

```sh
oms gui observe
oms gui act click "#3" --observation "刚才返回的 observationId" --confirm
# 页面变化或请求失败时，重新 observe，使用新的 observationId 与节点编号。
```

## 仍未实现或尚未验证

- v1.4.0 业务帧使用认证加密；完整配对链接仍须通过用户控制的可信途径传递。该组合协议未经第三方审计，HTTP APK 分发、端点/长度/时序信息、本地密钥存储和被控制的终端不在业务加密保护内。
- WAN 枚举仍叫 `WAN_WEBRTC`，实际实现为 WebSocket 中继接入；没有可据此验收的 WebRTC DataChannel/ICE/TURN 实现或随仓库部署的生产 relay。
- Mac 蓝牙桥实际使用 CoreBluetooth BLE GATT。`BT_RFCOMM` 为历史枚举名，不代表 Mac 已实现 RFCOMM。BLE 的 MTU 分片、确认与背压尚未完成专项修复/真机验收。
- 接收端保留同连接存活期间的重复起始帧处理；没有持久化断点续传、切换连接接续或发送端完整恢复状态机。连接关闭时未完成流清除；重试是重新投递。
- 文件 SHA-256 仍用于完整性校验，不是身份认证；v2 记录的认证由 AES-GCM 标签提供。
- `isSecureWindow` 是包名、活动名和密码字段的启发式策略。截图 API 的 secure-window 错误被保留，但不能识别所有 `FLAG_SECURE` 窗口或保证覆盖所有支付场景。
- 触摸事件的 OEM 覆盖、浮层权限撤销、蓝牙断线表现需真机验证。等待确认时允许触碰确认条；应用内容变化仍会使操作失效。
- 已被 Android 接受的手势不能保证中途撤回；取消会阻止后续动作并丢弃迟到回调。
- GUI/审批绝对期限依赖两端时钟大致同步；没有时钟偏差协商。日历、闹钟、通知与小米后台限制仍依赖系统权限，不能承诺 100% 执行或突破所有勿扰策略。

## 本地验证

```sh
bun install --frozen-lockfile --ignore-scripts
bun run typecheck
bun test
cd android-app
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon
```

Bun 测试通过仓库 `bunfig.toml` 自动加载测试隔离：仅监听回环地址、关闭真实设备发现和 osascript；临时测试文件保留用于检查，不递归删除。测试不启动用户已安装的 oms。

Android 现有 Gradle 配置绑定了本机 Android Studio JBR；在其他机器运行时需指定有效 JDK。JVM 测试覆盖纯状态与校验规则，不证明 Android UI、权限、ContentResolver 或 SQLite 集成已经过真机测试。

## 真机验收清单（执行前需明确部署目标）

- 新旧配对：旧密钥缺失应失败；重新配对后旧连接、旧请求失效，双端身份不串连。
- 投递：正常文件、0 字节、错误哈希、重复分块、连接中断、空间不足；确认手机/主机仅在对端成功 ACK 后显示交付。模拟丢失 ACK 后重试，不重复创建条目。
- GUI：观察后换页拒绝旧 ID；无浮层权限拒绝；确认拒绝/超时后不再执行；触摸/急停后请求失败，重启仍停止，手机恢复后重新观察。
- 期限：GUI/系统导航排队超过 deadline 不执行；审批过期后不能批准；自定义多个选项与 CLI/MCP 返回一致。
- 存储：未授权相册 URI、相邻前缀目录与路径穿越拒绝；授权截图/相机/下载正常读取。
- BLE：记录实际协商 MTU、不同大小文件与链路切换，不能用 LAN 测试替代。
