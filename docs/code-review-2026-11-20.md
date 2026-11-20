# 代码审查与改进建议（2026-11-20）

## 审查基线与边界

- 本地版本：v3.2.1，提交 `4cfa16fda261bb8e2cf454955cb2cc748391d066`。
- 本地 `main` 与已缓存的 `origin/main` 一致，审查开始时工作区干净。
- **未能拉取或确认远端最新版本**：`git fetch origin` 因 `.git/FETCH_HEAD` 不可写失败；`git ls-remote origin HEAD refs/heads/main` 因无法解析 GitHub 失败；网页读取也未成功。
- 范围：重点检查 v3.1.0～v3.2.1 的尺素阅读、文章同步和配额展示，并追查关联的离线补发队列。不是全仓库穷尽审计。
- 成功标准：明确审查版本；建议对应实际代码和触发条件；区分可复现缺陷、静态发现与环境导致的验证缺口。
- 本轮仅新增此报告，没有修改产品实现。

## 优先修复项

### 1. P1：离线补发期间新增队列项会丢失（已复现）

位置：`mac-host/src/daemon/oms-daemon.ts:1733–1779`。

`flushPendingWeChatPhoneQueue()` 读取队列快照后，逐项 `await pushStreamToPhone()`。期间 `enqueuePendingWeChatPhoneItem()` 可以向磁盘队列写入新项目。旧快照全部发送成功后，flush 删除整个队列文件；部分失败时也会用旧快照的 remaining 覆盖当前文件。

隔离复现：队列先有 A；暂停 A 的发送；新增 B，确认磁盘中有 A、B；再完成 A。最终队列文件消失，B 从未发送。复现直接调用现有类的方法，传输使用可控 Promise，不连接真实手机。

最小修复方向：限制同一时间只有一个 flush；发送完成时重新读取当前队列，只移除已确认发送的具体版本，保留发送期间新增或替换的项。单独加 flush 锁不能解决 enqueue 与 flush 之间的覆盖问题。

验收：A 发送期间新增 B，B 必须保留；同 key 更新内容不能被旧版本的发送确认删除；同时触发两次 flush 不得重复发送同一个队列项。

### 2. P2：精确 URL 匹配会被同名文章抢先命中（已复现）

位置：`mac-host/src/wechat/wechat-supervisor.ts:424–435`。

缺少 articleId 时，代码在一次 `find()` 中对每篇文章执行“URL 或标题匹配”。如果列表前面的文章标题相同但 URL 不同，后面真正匹配 URL 的文章不会被检查。旧手机条目和手机端本地采集条目可能因此打开另一篇原文。

隔离复现：两篇文章标题相同，id 分别为 1、2；请求明确传入第 2 篇的 URL，实际返回 id 1。使用 mock fetch 调用现有 `fetchArticleDetail()`，不依赖 HTTP 监听权限。

最小修复方向：先按 URL 搜索整个列表；没有 URL 时才使用唯一标题匹配。对于 URL 存在但未命中的情况，优先返回未找到，避免用同名内容替代。标题不唯一时显式返回歧义。

验收：同名不同 URL 的文章按 URL 正确命中；标题歧义不得随机选第一项。

### 3. P2：过期配额仍被表示为实时，未知配额还可能显示充足（已复现后端问题）

位置：`mac-host/src/agent/agent-session-hub.ts:2519–2522,2633–2645`；`android-app/app/src/main/java/com/oms/client/ui/cockpit/ChisuAndMissionWorkspaces.kt:1610`。

后端只要找到历史 rate_limits，就返回 `available: true` 和“实时同步自 Codex 本地会话遥测”。没有依据采样时间或窗口重置时间判定过期；文件指纹不变时直接复用缓存。Android 窗口列表为空且提示中没有“限流”时，则显示“额度充足”，把无法取得额度等同于有额度。

隔离复现：构造 30 天前、窗口早已重置的 token_count 事件，仍返回 available=true、“实时同步”及剩余 1%。没有读取用户真实配额。

最小修复方向：明确区分本地历史采样、过期和不可用，显示采样时间；不能凭本地日志证明当前官方额度。缓存命中时同样计算新鲜度。未知额度显示“暂无可用额度数据”。新鲜度阈值属于待确定的产品选择，窗口重置后至少不能继续当作当前窗口展示。

验收：历史事件不显示实时；已重置窗口不呈现为当前剩余额度；无样本不显示充足；刷新动作不得更新旧样本的采样时间。

### 4. P2：完整的文章缓存不会自动更新（静态确认，待 Android 验证）

位置：`android-app/app/src/main/java/com/oms/client/cockpit/CockpitRuntimeCoordinator.kt:835–863,971–1016`。

原文与报告都非空时，普通打开直接使用缓存，不再请求 Mac。虽然保存了 cachedAtMs，读取时没有用它判定过期。Mac 把初筛报告升级为深读报告后，手机即使同步了文章目录，再次打开仍可能显示旧正文。阅读器已有手动重新同步入口，因此不是完全无法更新，而是自动更新缺失。

最小修复方向：保留缓存首屏，联网时按合理有效期后台刷新；在已有状态或处理级别变化时使正文失效。暂不需要新建复杂缓存框架。

验收：先缓存初筛报告，再更新 Mac 报告与状态；手机正常打开后应自动更新正文，离线时仍能读缓存。

### 5. P2：旧文章请求会改变当前文章的加载与错误状态（静态确认，待 Android 验证）

位置：`android-app/app/src/main/java/com/oms/client/cockpit/CockpitRuntimeCoordinator.kt:866–922`。

成功结果设置 activeDetail 时检查了 stableKey，但错误分支和 finally 没有检查。快速从 A 切换到 B，A 的延迟失败会把错误写入 B；A 完成时也会提前清除 B 的 loading。关闭阅读器没有取消请求；同一文章连续刷新时，stableKey 也不能阻止旧结果覆盖新结果。

最小修复方向：维护当前详情请求的 Job 和请求序号，关闭或切换时取消旧任务；仅允许当前序号更新正文、错误和 loading，正确传递协程取消。

验收：A、B 乱序返回不串错误和 loading；同一文章连续刷新，旧响应不能覆盖新响应；关闭后旧任务不回写界面状态。

### 6. P2：图片大小上限在整包读取后才检查（静态确认，待 Android 验证）

位置：`android-app/app/src/main/java/com/oms/client/cockpit/CockpitRuntimeCoordinator.kt:938–945`；`mac-host/src/wechat/wechat-supervisor.ts:492–496`。

Android 先执行 `body.bytes()`，再检查是否超过 4 MiB；Mac 资源代理同样先读取完整 arrayBuffer，再检查 3 MiB 上限。这能限制返回数据大小，却无法限制读取期间的内存占用。图片地址返回很大的文件时，手机可能出现内存压力、卡顿或崩溃风险。现有 Bitmap 解码降采样发生在读取之后，不能解决这一点。

最小修复方向：检查已知 Content-Length，并在流读取过程中施加硬上限；读取超过上限立即终止。单纯检查响应头不足以覆盖分块响应。

验收：超过上限、有或没有 Content-Length 的响应均在有限读取后被拒绝；普通图片仍正常显示。无需用真实大文件压测用户手机。

## 验证记录

| 检查 | 结果 | 解释 |
| --- | --- | --- |
| `bun run typecheck` | 通过 | 三个 workspace 均退出 0 |
| `bun run test` | 144 通过，14 失败 | 9 项在 TCP/Unix socket 监听处失败；5 项在 Swift 编译缓存写入处失败 |
| `bun run test:wx` | 722 通过，1 失败 | 失败发生在测试 HTTP server 监听处 |
| `bun run test:android` | 未运行到测试 | Gradle 用户缓存锁文件不可写 |
| 三个隔离缺陷复现 | 均复现 | 调用现有代码，使用临时目录与 mock，不启动实际服务 |

测试失败输出包含 EADDRINUSE（port 0）、EPERM 和 Operation not permitted。本次不能据此判断相关实现有缺陷，也不能宣称全量测试通过。Android 三项静态发现需要在可运行 Gradle 的环境补充针对性测试或设备验收。

本次会话的诊断材料：`/tmp/oms-review-reproduce.ts`、`/tmp/oms-review-tests.log`、`/tmp/oms-review-wx-tests.log`、`/tmp/oms-review-android-tests.log`。这些是临时文件，不作为长期归档依赖；复现步骤和结果已写入本报告。

建议先处理队列丢项，再处理文章匹配、缓存与异步状态，随后校正配额展示并限制图片读取。当前不建议仅因文件较大而整体拆分或重构：先用针对性回归测试固定上述行为边界。
