# v1.3.8 本地 OTA 与验收记录

## 基线与范围

2026-10-26 检查实际安装时，主机源码与手机已是 v1.3.7（`6b23f6a`）；手机型号 24115RA8EC，Android 16，LAN_WS 在线。没有 ADB 调试连接。用户选择局域网 App 更新，无需 USB。

修复分支合并该版本，保留验证码同步，解决无障碍通知事件、出站附件/OTP metadata、收件箱提取等重叠逻辑。候选版本为 **1.3.8 / versionCode 12**，applicationId 仍为 `com.oms.client`，数据库版本仍为 1。

## 验证

- 合并后 TypeScript 检查通过。
- Bun：31 tests / 0 failures / 214 assertions，包括 OTP E2E 和之前的安全回归。
- Android：19 JVM tests 通过；Kotlin 编译、debug APK 打包通过。
- 版本名由候选名调整为正式 1.3.8 后重新打包；使用本机已有 Android debug 签名。
- 手机覆盖安装前对照原 v1.3.7 APK 证书。安装是否成功以手机重新上报的版本为准，不能把 OTA 返回 `ok` 视为安装成功。

## 更新流程

1. 保存已安装源码基线及旧 APK；备份主机配置、配对身份、收件箱索引和全局技能。
2. 已安装主机仓库仅在保持干净且仍是已检查基线时快进到修复提交；替换 OTA APK 及对应 metadata。
3. 重启主机后确认原配对恢复，`/apk/version` 返回 1.3.8/code 12 和正确哈希。
4. 经原配对连接触发 `sys.otaUpdate` 打开下载链接，由用户确认 Android 安装并重新打开 App。
5. 观察新手机版本、连接与权限；使用明确标注的验收内容检查文件 ACK 和 GUI/审批边界。

## 回退边界

主机可回到保留的旧提交，恢复已备份配置/索引后启动；不用删除收件箱或配对密钥。Android 没有数据库迁移，但普通 OTA 不允许用较低 versionCode APK 降级。若需无 USB 回退，应以同一签名、比当前更高的 versionCode 重建旧代码；不能承诺直接安装旧 APK 即完成回退。

## 真机覆盖限制

无 USB 仍可检查 LAN 连接、版本、权限、文件 ACK、GUI 观察与失败请求。物理触摸、浮层安装确认、权限撤销、蓝牙故障和 SQLite 实际落盘检查需要手机侧配合；不以模拟测试替代。

## 已完成的实际部署

- 已安装仓库 `/Users/dqrcsc/x_project/one_more_step` 从 v1.3.7 快进到 `ac79e5f`，保留本地回退分支 `backup/pre-1.3.8-20261026`。
- 配置、配对身份、派生队列和旧技能备份到 `~/.one_more_step/backups/pre-1.3.8-20261026-180537`（私有目录）；未删除应用数据。原备份清单误用了 `index.jsonl`，实际权威索引为 `items_index.jsonl`，已另存明确标注的启动后快照 `post-start-items_index.jsonl`，不能将其当作升级前索引。
- 已更新全局技能并重启 omsd；原手机通过 LAN_WS 恢复连接，无需重新配对。
- OTA 元数据返回 1.3.8 / versionCode 12。APK SHA-256：`42320462a5201914e947413ff760f698b08bf2a24dd7ae06b2607377a2f5f34b`。
- 新旧 APK 证书 SHA-256 相同：`806caa5647ae4119df3ba6b32cd4131d94147e66d4a4992b80f7e7ac94a1e4b7`。
- 用户完成安装后，手机实时上报 **1.3.8 / 12**，无障碍与浮层权限保持开启。

## 真机结果

| 项目 | 证据与结果 |
|---|---|
| Mac → 手机文本文件 | 74 字节，收到 COMPLETED 后返回成功，SHA-256 匹配 |
| Mac → 手机空文件 | 0 字节，收到 COMPLETED 后返回成功，空文件 SHA-256 匹配 |
| Mac → 手机跨分块文件 | 65,792 字节，收到 COMPLETED 后返回成功，SHA-256 匹配 |
| GUI 观察 | 返回 observationId 和 71 个节点；不保存私人页面内容 |
| 缺少/过期 observationId | 两次返回 ERR_STALE_OBSERVATION，未执行实际点击 |
| 自定义审批 | 用户实际点“仅测试：批准”，返回 approved=true 和原选项文字；本次未覆盖第二项拒绝分支 |
| 手机 → Mac 测试文本 | 已收到 `OMS-V1.3.8-验收完成`，北京时间 2026-10-26 18:11:40 入库；手机实时待发送队列为 0 |

未发布 GitHub Release。上述文件校验依据对端保存 ACK 与源码提交路径；没有直接导出手机私有 SQLite 或对实际断电持久性作保证。物理急停、确认取消、权限撤销与 BLE 故障仍待专项真机验证。
