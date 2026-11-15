# HUD Mirror Fit Implementation Plan

> Execution: execute tasks directly in the current checkout; no additional agents required.

**Goal:** 镜像默认适配显示器最大可用高度，并修复复核证实的界面问题。
**Architecture:** AppKit 负责窗口与生命周期；现有页面负责等比画面布局。保持手动开始和观看者生命周期协议。
**Tech Stack:** Swift/AppKit/WKWebView, TypeScript/Bun.
**Spec:** docs/plans/2026-11-15-hud-mirror-fit-design.md

## Constraints
- 不执行真机动作，不改 Android；不增加全屏模式或偏好框架。
- 最小修改、保留回退文件；未验证的多屏/输入法行为明确标记。

## Tasks
- [x] 在 mac-host/tests/hud-mirror-webkit.test.ts 加入横竖屏、窄视口与最大高度的复现断言；核对失败原因。
- [x] 在 mac-host/mac-hud/main.swift 实现进入/重开/跨屏最大高度，检查最小化恢复与控件提示，补齐相关回归。
- [x] 仅在复现布局问题后修改 mac-host/src/inbox/inbox-web-dashboard.ts 的镜像 CSS/必要布局逻辑，验证画面完整且坐标匹配。
- [x] 运行原生回归、类型检查和完整 Bun 回归；更新 CHANGELOG.md 与验证记录。
- [x] 比较运行目录、备份、部署必要的 Mac 文件并重启所需进程；核验本地 IPC、静态页面和镜像未启动。
