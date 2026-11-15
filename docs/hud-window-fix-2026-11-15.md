# Mac 菜单栏详情窗口修复 · 2026-11-15

基线为 v1.6.5 / `821cae6`，本修复作为后续源码修复提交，已应用到本机 HUD 助手；不另发 Release 或 APK，不移动 v1.6.5 标签，手机真机验收继续暂停。

## 现象与确认原因

菜单中的“打开投递详情浮窗（25条）”确实连接了 `openDetailFromMenu → openDetailPanel → buildDetailPanelWindow`，不是占位入口。25是主机查询并同步的最近投递列表数量，上限25，包含已处理记录；不是全部历史数或未读数。

真实生产窗口构造代码的隔离AppKit探针退出码134，明确报错：

```
NSInternalInconsistencyException:
window behavior cannot be both NSWindowCollectionBehaviorCanJoinAllSpaces
and NSWindowCollectionBehaviorMoveToActiveSpace
```

原代码同时设置了两个互斥选项，构造窗口时被AppKit拒绝。这个构造路径也供左键、通知“详情”和peek共用。主服务有HUD退出后重启逻辑，因此助手异常后菜单可能重新出现，掩盖了窗口初始化失败。

## 最小修复

- 保留 `.canJoinAllSpaces` 与 `.fullScreenAuxiliary`，删除冲突的 `.moveToActiveSpace`。
- 菜单改为“查看最近投递（N条）”，N与最多25条的列表展示保持一致。
- 沿用当前左侧记录列表、右侧文本/附件详情、Esc关闭、钉住和处理状态功能，没有另建一套界面。

## 验证

- 新增 `hud-window-construction.test.ts`：编译实际Swift源代码，真实创建但不显示AppKit窗口；覆盖空列表、1/25/30条合成记录、选择状态和内容区域，验证不会抛异常且无约束冲突。
- 窗口构造与原生双向IPC两项针对性回归通过。
- Mac/协议完整回归：135项、0失败、882断言；TypeScript类型检查、完整Swift HUD编译与diff检查通过。
- 本机仅替换HUD源码与二进制，原源码/二进制已备份；主服务PID保持26990，HUD由26995替换为37159。没有重启主服务、执行手机操作或重新配对。
- 当前UI工具无法定位该独立菜单栏进程，未完成真实菜单点击/多桌面/全屏交互验收；不把窗口构造测试当作可见界面验收。用户可稍后点击新菜单确认。
- 复现、回归及本地部署证据位于工作区 `one-more-step-analysis/reviews/2026-11-15/hud-window-fix/`。
