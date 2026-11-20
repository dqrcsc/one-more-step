# Inbox Height and Image Preview Implementation Plan

**Goal:** 两 Tab 默认最大可用高度，钉住行为明确，图片点击放大可用。
**Architecture:** 原有 Swift HUD 中复用窗口约束与关闭路径；图片使用独立原生滚动缩放窗。现有镜像网页不改。
**Tech Stack:** Swift, AppKit, Bun native integration tests.
**Spec:** docs/plans/2026-11-15-hud-inbox-height-image-design.md

- [x] 核验按钮真实文字和 NSPanel 属性，补齐两 Tab 最大高度及钉住状态回归。
- [x] 最小实现图片点击打开、缩放/适应/100%、关闭/Esc 和主窗生命周期。
- [x] 执行原生/WebKit 与全量回归，类型检查、编译、更新说明。
- [x] 比较运行目录与上轮部署摘要，备份并只替换 HUD 源码/二进制，重启 HUD 并核验 IPC；不启动手机测试。
