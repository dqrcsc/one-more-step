# OneMoreStep 项目设计文档库

> **项目定位**：打通 macOS 本地 AI Agent（Codex、Antigravity、OpenCode 等）与小米 Android 手机的双向互操作与跨端协同底座。
> **技术栈**：macOS 端 `TypeScript (Node.js / Bun)` + `Swift (CoreBluetooth 桥接)` | 手机端 `Android 原生 Kotlin (Jetpack Compose)`。

---

## 文档目录与细化设计总览

### 第一部分：立项与顶层架构（已完成）
- [x] **[01. 可行性分析报告](./01-feasibility-analysis.md)**：Android + macOS 技术可行性、三级无感连接、免 IM 原生交互及小米 ROM 避坑指南。
- [x] **[02. 全景功能与高价值场景分析](./02-scenarios-and-value.md)**：6 大核心价值维度、22 个具体协同场景及 ROI 优先级评估。
- [x] **[03. 系统顶层架构概览](./03-architecture-overview.md)**：Monorepo 结构、技术选型、12 个核心 MCP 工具集与三阶段演进路线。

### 第二部分：全模块详细设计（7 大核心模块已全部完成落盘，待逐个审阅确认）
- [x] **[04. 模块一：设备配对、安全认证与统一通信协议](./04-module-protocol-and-pairing.md)**
  - 零账号首次扫码/短码绑定、Ed25519 双向挑战鉴权、P0/P2 优先级多路复用 8 字节帧协议（防大文件阻塞控制信令）、双向 JSON-RPC 2.0 字典、离线队列与分块断点续传。
- [x] **[05. 模块二：三级自适应传输层与蓝牙空间感知](./05-module-transport-and-presence.md)**
  - `LAN_WS`（局域网满速）⇄ `BT_RFCOMM`（BLE 感知 + 经典蓝牙离线数据 + 带外 IP 握手）⇄ `WAN_WEBRTC`（免服务端跨网 P2P + 可选 WSS 中继）无缝热切换、底层物理网卡直绑绕过常驻 VPN、`AT_DESK / AWAY` 蓝牙 RSSI 滞回滤波在场引擎。
- [x] **[06. 模块三：Android 免 IM 极速投递、内置时间流与知识库联动](./06-module-android-share-and-stream.md)**
  - 截屏秒传悬浮胶囊（3.5 秒自动消隐 + 按住语音附言）、系统分享面板 3 大置顶 Direct Share 目标（`发给 Mac` / `存入个人知识库` / `发给 AI 修 Bug`）、控制中心快捷磁贴（1x1 透明焦点窗口突破 Android 10+ 剪贴板读取限制 + 语音闪念速记）、Compose 双向时间流、远程审批卡片与通勤 TTS 随身听。
- [x] **[07. 模块四：Android 确定性系统能力与小米 HyperOS 深度适配](./07-module-android-system-capabilities.md)**
  - 原生系统闹钟双保险、系统日历周期性实例防冲突查询与写入、受权目录别名白名单（`screenshots` / `camera` / `download` / SAF）防越界读取、L1~L3 分级通知（含突破勿扰与闹钟声道强制 TTS 播报），以及小米专属权限（OpCode `10021` 后台弹出界面、`10008` 锁屏显示等）反射自检与一键跳转向导。
- [x] **[08. 模块五：Android 无障碍 GUI 屏幕自动化引擎与安全护栏](./08-module-android-gui-agent.md)**
  - `OmsAccessibilityService` 三步语义剪枝与子节点文本聚合算法、Android 11+ 零弹窗静默截屏与 Set-of-Mark (`#ID` 视觉角标) 合成、语义优先+拟人贝塞尔手势注入、以及四大硬件级安全护栏（顶部急停胶囊、用户触碰让路、`FLAG_SECURE`/金融黑名单、最后一步「提交/发送」停顿确认）。
- [x] **[09. 模块六：Mac 宿主守护进程 (`omsd`)、本地收件箱与知识库触发器](./09-module-mac-daemon-and-inbox.md)**
  - 单例 `omsd` + 本地 Unix Socket 多 Agent 并发共享架构、`~/.one_more_step/` 目录与 `latest.json` 规范、与个人知识库 (`personal-knowledge-base`) 的暂存队列协同机制、在场感知智能通知路由、可选钉钉 Webhook 插件及 macOS `LaunchAgent` 自启保活。
- [x] **[10. 模块七：Mac AI Agent 接口层（13 个 MCP Tools、CLI `oms` 与全局 Skill）](./10-module-mcp-cli-and-skill.md)**
  - 13 个 MCP 工具的完整 JSON Schema 契约、`oms` CLI 命令行全集设计，以及 [`skills/one-more-step/SKILL.md`](../skills/one-more-step/SKILL.md) 全局 Skill 协同准则。
