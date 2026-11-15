# HUD Mirror Tab Implementation Plan

> Execution: 在当前会话按任务实施与检查；设计已批准。writing-plans 引用的 superpowers 执行技能本机未安装，使用现有代码工具完成相同检查，不安装额外技能。

**Goal:** 在 Mac 投递浮窗内手动启动手机镜像并安全释放观看连接。

**Architecture:** 原生 AppKit 容器管理两个 Tab；WKWebView 复用本机 /mirror?hud=1 页面。daemon 同步动态端口并支持不自动启动的观看者，现有手机协议保持不变。

**Tech Stack:** Swift/AppKit/WebKit、TypeScript/Bun、HTML Canvas/WebSocket。

**Spec:** `docs/plans/2026-11-15-hud-mirror-tab-design.md`

## Global Constraints

- 仅模拟设备与随机本机端口，不连接运行中的手机服务，不部署或发布。
- 现有浏览器镜像兼容；退出 HUD 只关自己的观看连接，保留 25 秒无人观看宽限。
- 打开 Tab 不采集；输入以手机回执为准，断线不重发动作。
- 不修改 Android、版本号、签名或配对配置，不批量删除文件。

### Task 1: 手动观看者与动态 URL

**Files:** `mac-host/src/hud/mac-hud-server.ts`, `mac-host/src/daemon/oms-daemon.ts`, `mac-host/src/transport/session-manager.ts`, `mac-host/tests/lifecycle-regression.test.ts`。

**Interfaces:** `HudStatusPayload.mirrorUrl?: string`; WebSocket `/ws/mirror?manual=1`；`mirrorViewerChanged(count, viewer?, autoStart=true)`。

- [ ] 添加测试：手动观看者加入只发状态而不调用手机，普通观看者仍自动开始；自定义端口生成正确 URL。
```ts
f.s.emit('mirrorViewerChanged', 1, { send() {} }, false);
await Promise.resolve();
expect(f.calls).toEqual([]);
expect(f.d.getHudStatusPayload().mirrorUrl).toContain('/mirror?hud=1');
```
- [ ] 运行 `bun test mac-host/tests/lifecycle-regression.test.ts` 观察新增检查失败。
- [ ] 在升级 WS 时记录 `manualMirror`；仅 `autoStart` 为真才触发自动启动，避免手动观察者影响旧入口。
```ts
if (autoStart && !this.mirrorDisposed && !this.mirrorRunning && !this.mirrorStartPending) { /* existing start path */ }
```
- [ ] 运行针对性测试和 `bun run typecheck`。

### Task 2: 嵌入页面生命周期与回执

**Files:** `mac-host/src/inbox/inbox-web-dashboard.ts`, 新建 `mac-host/tests/mirror-page-lifecycle.test.ts`。

**Interfaces:** `window.hostDeactivate()`；手动开始才创建 WS；请求 `reqId` 对应 `input_ack` / `mirror_error`。

- [ ] 从真实 HTTP renderer 提取 script，在 VM 中用假 DOM、WebSocket 与定时器验证：初始 0 连接，开始后 1 连接，退出后无重连；两个不同请求回执不串扰。
- [ ] 添加 HUD 模式、活动代际、最多 5 次退避重连、首帧/帧过期输入门禁。清理关闭、指针长按和延迟解码。
```js
function hostDeactivate() {
  active = false;
  generation++;
  clearTimeout(reconnectTimer);
  ws?.close();
}
```
- [ ] 输入 Promise 按 reqId 结算，业务 `ok:false` 拒绝；文本成功才清空；断线无 HTTP 动作 fallback；输入法组合输入忽略快捷键。
- [ ] 用假帧与延迟解码测试安全标志清屏、迟到帧不回写，运行新页面测试。

### Task 3: 原生 Tab、WebKit 与原生集成回归

**Files:** `mac-host/mac-hud/main.swift`, `mac-host/package.json`, `mac-host/tests/hud-window-construction.test.ts`, 新建 `mac-host/tests/hud-mirror-webkit.test.ts`。

**Interfaces:** 可选 URL snapshot；`selectDetailTab(_:)`；WebView 只加载校验过的 loopback origin。

- [ ] AppKit 测试添加 Tab 切换、未配置入口、非法 URL 与空投递不读记录检查。
- [ ] 原生增加分段控件与互斥内容容器、菜单入口。镜像失焦保持、关闭/最小化/睡眠释放，IPC 断线撤销页面。
```swift
let config = WKWebViewConfiguration()
config.websiteDataStore = .nonPersistent()
let view = WKWebView(frame: .zero, configuration: config)
```
- [ ] 导航代理限制本机镜像 URL，禁止弹窗/外链/下载；WebKit 进程退出显示可重试状态。
- [ ] 使用随机端口假服务加载真实页面，原生测试操作开始、显示合成帧、切 Tab、重新进入、URL 拦截；不连接真实手机。
- [ ] 所有编译实际 HUD 的测试补 `-framework WebKit`，执行原生 IPC、构造和集成回归。

### Task 4: 完整回归与交付

- [ ] 执行 `bun test`、`bun run typecheck`、`bun run build:hud`、`git diff --check`。
- [ ] 检查连接泄漏、停止失败、普通浏览器行为和多观看者回归；仅新失败或修改触发重测。
- [ ] 更新 CHANGELOG 与实施/验证记录，明确真实 WKWebView 测试结果与尚待真机验收的项目。
- [ ] 保留本地修改供审阅；不自动推送、发 Release 或替换运行服务。
