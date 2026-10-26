# v1.3.8 / v1.3.9 GUI 安全真机验收

设备：Xiaomi 24115RA8EC / Android 16，局域网连接。版本 1.3.8 / code 12，无障碍与浮层权限开启。通过现有 IPC/手机 RPC 验收，不使用 USB，不新增调试后门。

测试动作统一为 `home`，强制手机确认。除专门的换页/恢复步骤外，不操作业务按钮。机器证据保存于研究工作区 `one-more-step-analysis/verification/ota-1.3.8/gui-*.json`；只保存响应、时间与前后台包名，不保存屏幕正文。

| 检查 | 结果 | 证据范围 |
|---|---|---|
| 人工拒绝 | 通过 | 用户点击拦截，主机返回 `ERR_USER_INTERRUPTED: User declined sensitive GUI action`；前后均为 com.oms.client |
| 确认超时 | 通过 | 58.44 秒后手机返回 timeout，用户确认浮条消失，前后应用包名均为 com.oms.client |
| 等待期间换页 | 通过 | 用户切到系统设置后点放行；返回 ERR_STALE_OBSERVATION: Window or package changed，前台保持 com.android.settings |
| 手机急停 | 通过 | 用户点击顶部急停，当前请求返回 floating_pill_clicked；后续请求 0.21 秒内返回 Resume GUI access on the phone first，前台保持系统设置 |
| 重启后保持急停 | 通过，附系统条件 | 用户强行停止并重新打开，连接断开后恢复；系统无障碍服务需手动重新启用。启用后用户确认急停条重新出现，新请求 0.35 秒内被 Resume GUI access on the phone first 拒绝 |
| 手机显式恢复 | v1.3.8 未通过正常执行验收 | 解除急停后，在 App 与系统设置两次放行均返回 Screen changed，未执行 home；保留失败证据，准备 v1.3.9 修复复测 |

已注入 Android 的手势无法保证撤回，本次主要验证执行前的确认和取消路径。触摸事件覆盖、权限动态撤销不等于已在本轮验收。

## 由验收发现的 v1.3.8 问题与 v1.3.9 补丁

正常放行在两个页面都误触发过期观察校验。v1.3.8 对所有非自身包的内容/状态事件都使观察失效，且将控件类名混入窗口身份、将非应用 active root 追加到应用窗口集合。补丁按真实应用窗口 ID/包名判断事件归属，排除无关浮层与系统窗口，保留目标应用变化的拒绝行为，并在失效消息中保留事件归属诊断；v1.3.9 真机复测已确认正常放行恢复、跨应用换页仍被拒绝；v1.3.8 未记录触发失效的具体事件，不能据此断言是哪一种系统事件导致两次失败。

v1.3.9 / versionCode 13：TypeScript 检查、31 项 Bun 测试 / 214 断言、23 项 Android JVM 测试及 APK 打包通过。已通过同签名 OTA 覆盖安装，手机实时状态确认 1.3.9 / code 13，无障碍权限保持启用。


## v1.3.9 OTA 与专项复测结果

安装包 SHA-256：`d7747851d08acc405aacf1c5db86eb37dd83ec6d0122d9a60d2d5fe16767666b`。签名证书与已安装的 v1.3.8 一致；没有卸载或清除应用数据。此次仅部署到本机与配对测试手机，未发布 GitHub Release。

| 检查 | 结果 | 证据 |
|---|---|---|
| 同页正常放行 | 通过 | 新观察后在系统设置直接放行；13.35 秒内返回 ok=true，前台从 com.android.settings 变成 com.miui.home；用户确认返回桌面，确认条和操作胶囊均消失。`gui-resumed-settings-139.json` |
| 等待期间切换应用 | 通过 | 新观察后从青简·咫尺切到系统设置再放行；17.30 秒返回 ERR_STALE_OBSERVATION: Window or package changed；前台保持 com.android.settings，用户确认仍停留设置。`gui-resumed-changed-screen-139.json` |

结论：v1.3.8 上复现的“正常放行仍误拦截”问题，在 v1.3.9 的系统设置场景已通过真机复测，同时保留跨应用换页保护。v1.3.8 的拒绝、超时、急停与急停持久化结果保留为上一版本证据，本次未在 v1.3.9 全量重跑这些人工步骤。仍需后续专项验证同一应用内动态内容变化、不同 OEM 的窗口事件与权限动态撤销，不将本轮结果外推为所有页面均已验证。

OTA 前的 v1.3.8 APK 和输出元数据保存在研究工作区 `verification/ota-1.3.8/pre-1.3.9-app-debug.apk` 与 `pre-1.3.9-output-metadata.json`。Android 通常拒绝低 versionCode 的直接降级；这些文件是回退构建素材，不代表可直接 OTA 降级。
