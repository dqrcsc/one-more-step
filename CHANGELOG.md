# Changelog · 更新日志

## 3.3.2 - 2026-11-21 · 实时音色男/女声标注与按性别快筛、协同会话未发草稿自动持久化

- **实时音频 / 视频音色选择增强 (`RealtimeVoiceCatalog` & `LiveVoicePickerDialog`)**：为全部 10 款 OpenAI Realtime 与 14 款 Gemini 3.8 Live 音色补齐明确的「女声 / 男声 / 中性」彩色徽章与顶部胶囊简标（如 `Marin·女` / `Puck·男`），并在选择弹窗新增 `全部 / 女声 / 男声` 一键快筛。
- **协同会话输入框草稿自动持久化 (`EdgeSecureStore` & `ChisuAndMissionWorkspaces`)**：在「协同」工作区的单个 Agent 会话追问框（按 `backend:sessionId` 独立存储）与远程派单抽屉中实时持久化未发送草稿——退出会话、切 Tab 或重启 App 后再次进入自动还原，列表卡片显示醒目「草稿」角标；仅在成功提交后自动清空，若发送失败则自动回填保留。

## 3.3.1 - 2026-11-21 · 实时音频与 Glass 视频对话原生音色选择

- **实时音频 / 视频对话音色自由切换 (`RealtimeVoiceCatalog`、`EdgeSecureStore` & `GlassAndLiveOverlay`)**：为 OpenAI Realtime 散步语音（10 款官方音色：`Marin`、`Cedar`、`Coral`、`Shimmer`、`Sage`、`Ballad`、`Verse`、`Ash`、`Alloy`、`Echo`）与 Gemini 3.8 Live 实时视频语音（14 款演播室音色：`Puck`、`Kore`、`Aoede`、`Zephyr`、`Charon`、`Fenrir`、`Leda`、`Orus`、`Sulafat`、`Vindemiatrix`、`Achird`、`Gacrux`、`Despina`、`Umbriel`）提供独立持久化的音色选择器；通话顶部新增毛玻璃音色胶囊，点开即可按中文风格标签一键切换并无感重连生效，且跨引擎严格隔离音色 ID 避免误传。

## 3.3.0 - 2026-11-21 · 随身秘书今日简报与活记忆、尺素全模态（图文/视频/灵感）双文档、全库中文 FTS5 统一检索、EPUB 拆章入库与 Antigravity 额度修复

- **尺素支持图片笔记、视频与灵感的双文档查看 (`WeChatBridgeSupervisor`、`OmsDaemon` & `ChisuWorkspaceView`)**：打通 `wechat_context.db` 的 `conversation_turns`（`multimodal_ingest` / `capture_idea` / `capture_inbox`）与 `articles` 合并视图；扩展 `wechat.article.detail` / `wechat.article.asset` 支持 `turnId` 维度，自动将多媒体笔记中的相对路径图片附件提升为正文内嵌原图（支持灯箱缩放）；手机端新增 `图片 / 视频 / 灵感` 分类快筛，阅读器标签自动适配为「分析报告 / 原图与原文 / 转录与原文 / 原始灵感」。
- **Antigravity 额度与状态展示根治修复 (`AgentSessionHub` & `AgentQuotaDashboardCard`)**：移除已过期失效的旧版 IDE `state.vscdb`（2026-05）读取逻辑；合并扫描 `~/.gemini/antigravity/brain` 与 `~/.gemini/antigravity-cli/brain` 双目录真实推理轮次/Token/缓存命中率及近 6 小时真实限流报错；修复 Android 端 `statusNote.contains("限流")` 误把 `"未触发限流"` 判成 `"触发限流"` 的高亮 Bug，改为诚实准确的「运行正常 / 触发限流」状态呈现。
- **全库增量中文分词 FTS5 混合检索与手机端全局统一搜索 (`MobileMemoryMirror` & `CockpitShell`)**：突破旧版仅扫最近 180 篇、截断 12KB 与无排序的局限，新增基于 `Intl.Segmenter("zh-CN")` 中文分词 + 标题分块 + BM25 × 目录权重（`04_topics` > `03_ideas` > `05_outputs` > `01_inputs/books` > `01_inputs`）的增量 SQLite FTS5 全库索引，并同步覆盖微信尺素与手机对话；手机端侧边抽屉升级为全局统一搜索（Omni-Search），支持新增全屏知识库文档阅读器（`memory.read_vault_doc`）与一键朗读/追问；新增 `oms search "<query>"` CLI。
- **零依赖 EPUB 电子书拆章解析入库流水线 (`EpubVaultImporter` & `oms book import`)**：新增 `oms book import <path.epub>` 与 `oms book list`，自动解压解析 OPF 元数据、EPUB3/NCX 目录与章节 XHTML 为结构化 Markdown（保留插图至 `assets/`），严格遵循个人知识库规范写入 `01_inputs/books/sources/<slug>/`、生成非破坏性伴读笔记脚手架 `notes/<slug>_note.md`、幂等登记至 `bookshelf.md` 并立即同步进全库 FTS5 索引供跨书检索与通勤听书。
- **随身秘书中枢：活记忆、未闭环事项与今日简报台 (`SecretaryCore`、`CockpitRuntimeCoordinator` & `ChatWorkspaceView`)**：新增透明可控的个人长期记忆（Profile）与未闭环事项（Open Loops）持久化存储（支持 `oms brief` / `oms todo` CLI 及 App 内管理），在非无痕对话中自动注入个人偏好与待办上下文，支持对话中自动捕获 `[COMMITMENT:事项|时间]`；手机端对话空白页升级为「随身秘书今日简报台」，聚合今日问候、未闭环事项勾选与快速记录、1~3 张可一键执行或忽略的主动建议卡（尺素听读、Mac 协同任务验收、书架直达、主题回顾）及长期记忆编辑器。

## 3.2.1 - 2026-11-20 · 尺素移动端双文档杂志阅读器、Mac 归档库双向同步与图文/链接渲染升级

- **Mac 尺素归档库与手机端双向 RPC 同步 (`WeChatBridgeSupervisor`、`OmsDaemon` & `shared-protocol`)**：新增 `wechat.articles.list`、`wechat.article.detail` 与 `wechat.article.asset` 加密 RPC 协议，支持手机端实时拉取 Mac 微信尺素归档列表、按文章 ID/链接/标题按需获取完整「分析报告 / 初筛摘要 / 保存原文」Markdown 内容以及原文档内嵌图片资源（Base64 安全传输，单图上限 `3 MiB`）；同步在推送队列与 `StreamStartHeader` 中透传 `wechatArticleId` 与完整元数据。
- **尺素工作区升级为 Mac 同源双文档纸感阅读器 (`ChisuAndMissionWorkspaces`、`ChisuDocumentParser` & `CockpitRuntimeCoordinator`)**：重构手机端「尺素」为杂志摘要流 + 沉浸式双文档阅读页，支持「分析报告 / 初筛摘要 / 保存原文」一键切换、YAML Frontmatter 自动剥离与标题/摘要清洗、作者/日期/预计阅读时长等精简编目信息条、用户转发批注高亮卡片，以及本地磁盘离线缓存（目录与文章详情/图片资源分级缓存）；离家待机模式（`EdgeWeChatStandbyEngine`）初筛完成后亦同步保存原文至本地缓存供离线切换对照阅读。
- **增量 Markdown 引擎新增内嵌图片灯箱与可点击外链 (`MarkdownBlockParser` & `IncrementalMarkdownView`)**：补齐 `MdBlock.Image` 块级解析与异步图文加载渲染（同时支持网络图经内置代理拉取及 Mac 知识库相对路径图片通过 RPC 按需加载与点击放大查看），并升级正文段落为可点击外链渲染（`LinkableMarkdownText`）。

## 3.2.0 - 2026-11-20 · 实时视频快速故障转移、Live 语音联网查询、双脑桌面双向同步与接续自愈、AI 写歌/视频修复、模型去噪、检索消息精准定位与博物图鉴详情升级

- **实时视频与 Live 语音握手稳定性升级 (`ProxyTunnels`、`SmartRegionRouter`、`OmsProxyManager` & `RealtimeVoiceProtocol`)**：将代理节点首包握手超时从 `65s` 收敛至 `6s`（首包连通后再放宽至 `660s` 流式长超时），修复个别节点出站黑洞导致 OkHttp 客户端超时早于代理换线的问题；修复 `OmsProxyManager` 单例客户端吞掉自定义 `readTimeout`（WebSocket 现采用 `0s` 无空闲读超时 + `15s` Ping）；补齐 `WebSocketListener.onClosing` 关闭帧回执、`9s` `setupComplete` 看门狗及握手失败自动驱逐坏节点重连机制。
- **Live 全双工实时语音挂载联网搜索 (`RealtimeVoiceProtocol` & `CockpitRuntimeCoordinator`)**：为默认散步语音（OpenAI `gpt-realtime-2.1`）注册 `web_search` 实时函数工具并异步处理 `response.function_call_arguments.done`，结合 `DualLlmEngine` 搜索接地回传实时天气、新闻、股价等最新事实并继续语音播报；同步强化 Gemini 3.8 Live 的 Google Search 实时核查指令。
- **Mac Codex & Antigravity 历史消息完整还原、桌面锁自动派生接续与双端双向可见 (`AgentSessionHub` & `AgentSessionDetailScreen`)**：补齐 Codex `response_item`（`user` / `assistant`）历史消息提取与超大转录文件中段对话扫描，新增 `compactTrajectorySteps` 步骤压缩器防止长轮次工具调用冲掉历史提问与回复；Codex 自动优先选用 `Codex.app` 内置新版 CLI 走官方 `codex app-server` JSON-RPC 协议（同步写入 `source = 'vscode'`、`thread_source = 'user'`、`originator = 'Codex Desktop'`、`project_id` 与 `.codex-global-state.json` 本地项目归属，遇桌面活跃占用 `-32600 already has an active writer` 时自动携带完整上下文 `thread/fork` 派生接续并追加 `（手机接续）` 标题区分及 `codex://threads/<id>` 提示）；Antigravity 增加 `--dangerously-skip-permissions` 与 `~/.gemini/antigravity` ↔ `~/.gemini/antigravity-cli` `.db` + `brain` 原子双向快照桥接，根治 `trajectory not found` 并让手机新建/接续会话同步呈现在 Mac `Antigravity.app` 桌面端。
- **Google 写歌 (`lyria-3.5`) 与视频生成 (`gemini-omni-1.2-flash`) 全面修复 (`CreativeMediaEngine` & `ChatWorkspaceView`)**：切换至官方 `/v1beta/interactions`（携带 `Api-Revision: 2026-05-20` 与 `600s` 超时），移除对模型交互非法的 `background: true`，补齐 `response_format`（音频 `{"type":"audio"}`、视频 `{"type":"video","aspect_ratio":"16:9","duration":"5s"}`），并在对话流内新增专属音乐播放卡片与提取首帧封面的有声视频预览卡片。
- **OpenAI & Google 模型列表去噪精简 (`ModelCatalogRegistry` & `EdgeSecureStore`)**：自动隐藏带日期快照后缀（`-YYYY-MM-DD` / `-MM-YYYY`）、`-preview` / `-exp` 测试后缀及早期跨代旧模型（`gpt-4*`、`gpt-5*`、`o1/o3/o4`、`gemini-2.*`、`gemini-omni-1.1*`、`lyria-3-pro*` 等），启动加载缓存时同步清洗历史残留项。
- **会话全文检索自动滚动定位与关键词高亮 (`CockpitShell` & `ChatWorkspaceView`)**：点击侧边抽屉检索结果后自动解除底部跟随锁定，精准滚动至命中消息所在行并展示 `检索命中 ·「关键词」` 强调边框。
- **随身博物图鉴册全屏缩放看原图与完整档案页升级 (`SpecimenFieldGuideGalleryModal` & `OmsZoomableImageLightbox`)**：新增通用 `1x–4.5x` 双指捏合/双击缩放原图灯箱，图鉴册升级为「缩略图直点放大 + 卡片点击进入完整标本详情页」，完整呈现英雄大图、拉丁学名、科属别名、核心形态鉴别特征、近似种辨析、花果期生境、养护提示、参考来源及一键同步知识库与追问操作栏。

## 3.1.0 - 2026-11-20 · 双脑项目会话标题精准对齐、Live 响度与实时镜头视觉修复、双擎联网搜索打通与配额重置看板

- **Codex & Antigravity 官方 SQLite 会话标题与命名项目深度对齐 (`AgentSessionHub` & `MissionHubWorkspaceView`)**：直连解析 `~/.codex/state_*.sqlite`（`threads.name`、`projects` + `project_roots`、`session_index.jsonl`）与 `~/.gemini/antigravity/conversation_summaries.db`，完整还原桌面端精炼会话标题与用户自定义中文项目名；修复超长首行 `.jsonl` 标题为空及 `<user_information>` 路径正则误匹配源码问题，手机端新增横向项目快筛胶囊栏与醒目项目名徽章。
- **Live 全双工外放响度增强与实时镜头视觉全链路打通 (`LiveAudioDuplexController`、`GlassAndLiveOverlay` & `RealtimeVoiceProtocol`)**：在小米 HyperOS（Android 12+ API 31+）显式调用 `AudioManager.setCommunicationDevice(TYPE_BUILTIN_SPEAKER)` 根治音频误落顶部微弱听筒问题，自动对齐 `STREAM_VOICE_CALL` 通话声道音量、绑定物理音量键并内置无削波软限幅语音响度增益（支持面板内一键开关）；重写 CameraX 1.3 `ImageProxy.toBitmap()` + 旋转矩阵 JPEG 编码器，修复权限授权回调旧状态捕获与静默降级丢帧 Bug，开启 Gemini 3.8 Live `contextWindowCompression` 滑动窗口并新增实时同步帧数徽章与前后镜头翻转。
- **普通对话与深度思考双擎原生「联网搜索」全面修复 (`DualLlmEngine` & `CockpitRuntimeCoordinator`)**：为 OpenAI `/v1/responses`（`gpt-6.2-sol` / `gpt-6.2-astra`）补齐原生 `tools: [{"type": "web_search"}]` 挂载与 `url_citation` 引用溯源解析，修复同时开启「思考 + 联网」时搜索开关被吞的问题，并增加流式首包跨厂商搜索自动容灾切换。
- **Codex & Antigravity 额度余量与重置日期实时看板 (`AgentQuotaDashboardCard` & `oms agents quotas`)**：自动从 Codex 最新本地 `rate_limits` 遥测与 Antigravity 本地状态/推理轨迹提取套餐档位、剩余额度百分比进度条、精确重置日期与相对倒计时及今日缓存命中率，在「协同 · Agent 会话」顶部与「设置 · 模型与路由」双入口同步呈现。

## 3.0.0 - 2026-11-20 · 旗舰级消费态交互重塑、五套素笺/玄墨多主题引擎与纯矢量微交互设计系统

- **五套精选双态主题与日夜自适应系统 (`OmsTheme` & `OmsThemeController`)**：支持「跟随系统 / 素昼浅色 / 玄夜深色」自由切换，内置 **素笺·玄墨**（宣纸暖白 + 纯净 OLED 曜黑）、**极简·纯粹**（对标 ChatGPT 无色相黑白）、**星云·流光**（对标 Gemini 深空蓝紫）、**松烟·苔痕**（森系护眼）与 **琥珀·暖咖**（零蓝光羊皮纸）五套精心调校色板，全界面色彩平滑渐变过渡。
- **手绘圆头线性矢量图标库与阻尼按压物理反馈 (`OmsIcons` & `OmsDesignPrimitives`)**：全量清除控件 Emoji 与粗重彩色边框，构建 49 枚统一 `24x24` `1.85dp` 圆头描边矢量图标；所有按钮、胶囊与分段器内置弹簧缩放回弹阻尼（`omsBounceClick`）、复制/收藏图标原地微变形动画（`OmsMorphIconAction`）及顶部非打断灵动轻提示胶囊（`OmsFloatingToastHost`）。
- **无边框杂志级对话画布与一体式悬浮胶囊输入舱 (`ChatWorkspaceView`)**：启用全屏 `Edge-to-Edge` 穿透与 120Hz 软键盘 `imePadding` 逐帧托举；AI 回复升级为 `15.5sp / 25.5sp` 无外框纸感排版流，补齐用户多图气泡预览与长按重编辑；悬浮输入舱内聚 `+` 半屏多模态/快传面板、附图缩略预览、原地 60fps 实时声波谱听写（支持一键上屏或直发）及快捷模型切换。
- **彻底退役旧版套娃控制台并完成尺素精读器、协同舱轨迹聚合与流体声波球升级**：移除旧版 2,144 行套娃控制台，提炼平时零占位的顶层灵动安全胶囊（`GlobalAmbientStatusBanner`）与跨页通勤迷你播客条（`GlobalMiniAudioPlayerPill`）；`尺素` 升级为杂志摘要流 + 全屏沉浸伴读页；`协同` 融合 Agent 工具步骤手风琴折叠流与跨端双向投递箱；`Live 语音` 升级为 120fps `Canvas` 多层有机流体声波光球。

## 2.2.0 - 2026-11-20 · 腾讯云轻量零信任跨网中继 (`Tier 3 WAN`)、离家驻守防休眠与远程工作闭环

- **零依赖跨网加密会合中继 (`wan-relay/server.mjs`)**：新增纯 Node 22 / Bun 兼容的 RFC 6455 WebSocket 房间配对中继服务（常驻内存约 `12 MiB`），采用恒定时间 `Bearer` 鉴权、双向二进制密文帧透传、`waiting_for_peer` / `peer_paired` / `peer_left` 状态同步与心跳超时回收，复用标准 `443` 端口独立两级 ECDSA P-256 证书链并完全隔离保留同机既有业务服务。
- **Mac 与 Android 三级通道无感升降级 (`PhoneSessionManager` & `OmsTransportClient`)**：修复旧版 WAN 连接提前空发 `secure.hello` 握手超时的时序问题；Mac 常驻待命并在收到 `peer_paired` 时瞬间发起 v2 `P-256 ECDH + AES-256-GCM` 双向认证加密握手；手机端支持固定根证书（CA Pinning）、绕过第三方 VPN 直连国内中继、在同局域网 `LAN_WS` 就绪时自动释放云端连接，离席断连 `1.5s` 内自动切入 `🟣 跨网远程在线`，无缝承载 `🕹️ 任务舱`（Codex / Antigravity 远程派单、接续与审批）及文件/剪贴板协同。
- **离家 5G 微信尺素状态确定化 (`StandbyLeasePolicy`)**：中继明确回传 `hostOnline: false` 时，手机在户外 5G 下也能 100% 确定性感知家里 Mac 已休眠并安全接管微信 iLink 轮询，消除离家模糊等待态。
- **Mac 离家驻守防休眠与 CLI 扩展 (`oms wan` & `oms stay-awake`)**：新增 `oms stay-awake on|off|status`（受管 `caffeinate -i -s -w <pid>`，允许息屏锁屏但防止系统断网睡眠）及 `oms wan status|test|on|off|set` 实时跨网诊断命令。

## 2.1.1 - 2026-11-20 · 内置代理防 AI 锁区自动切换与带鉴权连通性探测

- **AI 地域限制自动熔断与无感换线重试 (`AiGeoBlockFailoverInterceptor`)**：自动识别 Google Gemini (`User location is not supported`) 与 OpenAI 地域锁区响应，将触发锁区的代理节点（含香港节点及被送中的假美区节点）实时标记为 `🚫 AI锁区`、立即淘汰复用的 HTTP/2 长连接并自动切换至新/日/美纯净节点无感重试。
- **冷启动选路与连接池即时驱逐修复 (`SmartRegionRouter` & `OmsProxyManager`)**：默认优先地区扩展为 `新加坡|日本|美国|台湾`，修复冷启动尚未完成测速时落入首个香港节点并被 HTTP/2 Keep-Alive 持续锁定的问题；手动切节点或全量测速优选变更后立即驱逐旧隧道连接。
- **HTTPS `CONNECT` 首包 `ClientHello` 预读故障转移**：对齐 `vpn_proxy` 握手时序，支持 TLS 握手阶段单次请求多节点自动兜底重试。
- **真实带 Key 鉴权与锁区感知的 AI 连通性测试**：`🌐 实测 AI 连通性` 升级为携带真实 OpenAI / Gemini 密钥校验，准确区分「鉴权通过 / 节点被锁区 / 密钥无效 / 网络不可达」，消除假阳性提示。

## 2.1.0 - 2026-11-20 · 青简·咫尺认知座舱、内置免 VPN 代理、尺素休眠接管与双脑协同中枢

- **内置免 VPN 纯 Kotlin 代理引擎 (`:core:net-proxy`)**：移植 `vpn_proxy` 协议栈（Shadowsocks-2022 `SIP022`/`SIP023`、SS-AEAD、Trojan、防 `198.18.0.0/15` UDP DNS 污染），支持「智能优选（美国|新加坡优先）/ 手动锁定节点 / 仅直连」切换、并发全节点测速、OpenAI/Gemini 端到端连通延迟探测及 Mac 预解析 `bootstrapNodes` 快照下发。
- **微信「尺素」早晚休眠零脑裂自动接管**：Mac 休眠且手机亮屏时自动接管 ClawBot iLink 长轮询，调用 `gpt-6.2-sol` 携 `ContextCapsule` 完成公众号初筛并直回微信；Mac 唤醒重连瞬间原子移交游标与已初筛笔记（`light_done` 免重跑入库）。
- **三工作区对话座舱与三级记忆漏斗**：重构为 `✦ 对话 | 📖 尺素 | 🕹️ 任务舱` 三工作区 + 左侧滑全文检索抽屉 + 120fps 块级冻结增量 Markdown 渲染，支持 `💡 深度思考`（`gpt-6.2-astra`）、`🌐 联网核查`（`gemini-3.8-flash`）、`🎙️ 极速听写`（`gpt-live-transcribe`）、内联日历防冲突/原生闹钟卡、以及 L0/L1/L2 三级记忆与 `search_my_context` 按需召回。
- **`🕹️ 任务舱` 项目下拉派单与跨端接续 (`AgentSessionHub`)**：自动发现并聚合 Mac 项目目录形成可搜索下拉菜单，支持新建会话或直接下拉选择已有 `Codex` / `Antigravity` 会话无缝 `resume` 接续。
- **博物之眼 Google Glass 镜头、Live 语音舱与十大场景模型矩阵**：CameraX `📷 识物`（`gemini-4-argon` + Search Grounding `🌿 博物图鉴卡`）、`🎧 Live` 全双工语音（`gpt-realtime-2.1` + `🇬🇧 English Only` 开关 + `gemini-3.8-live` 1fps 实时镜头）、十大场景模型自由绑定（含生图/视频/写歌）及一键联网刷新官方最新可用模型列表。

## 1.7.0 - 2026-11-19 · 青简合一：并入微信尺素（WeChat-as-Context）与四端统一工作台

将 **青简·尺素 (`wechat-context`)** 完整并入 `one-more-step` 单仓库与单一 `omsd` 守护进程体系，实现本地一套常驻服务同时承载小米 Android 双向协同与手机微信 ClawBot 上下文桥接：

- **单守护进程与受管子进程隔离 (`WeChatBridgeSupervisor`)**：`omsd` 在后台自动拉起、守护并回收 `wechat-context` 子进程（带父进程存活看门狗与 `BUN_CONFIG_DNS_TIME_TO_LIVE_SECONDS=0` 注入），数据统一收敛于 `~/.one_more_step/wechat/`；所有 `/wechat/*` 与 `/api/wechat/*` 接口均受 `127.0.0.1` 回环与同源守卫保护，非回环请求一律 `403` 拦截。
- **Mac 原生悬浮窗与轻通知升级 (`oms-mac-hud`)**：浮窗新增独立「💬 微信尺素」Tab（与「📬 最近投递 / 📱 手机镜像」并列），支持直接浏览微信归档文章、切换「伴读笔记 / 初筛摘要 / 归档原文」、一键同步至手机通勤朗读及 Finder 定位；修复侧边栏多行换行标题点击命中与镜像图片预览独立浮窗缩放；右上角轻通知卡新增翠绿「💬 青简·尺素」通道标识。
- **本地 Web 总控台与小米手机时间流扩展**：`127.0.0.1:19527/inbox` 顶部新增「📬 手机协同流 / 💬 微信尺素 / ⚙️ 尺素模型与路由配置」模块切换及「推送到手机微信」入口；Android 客户端（v1.7.0 / build 43）时间流新增独立「💬 微信尺素」Tab 与专属伴读卡片，支持耳机通勤 TTS 朗读及离线待发队列重连补推。
- **统一 CLI 与渐进式单入口 Skill**：`oms` 新增 `oms wx <subcommand>`（保留 `wx-ctx` 兼容别名）；全局 Skill 收敛为单入口 `one-more-step` 配合 `references/wechat-context-rules.md` 按需加载。

## 未发布 - 2026-11-15 · Mac 交互复核修复

- 相同投递的后台同步和无关新投递不再重建当前详情，保留阅读选择、文档位置与附件播放；当前条目发生变化或主动切换时仍正常刷新。
- 镜像文字等待回执时禁止重复提交，按钮暂时禁用但输入框仍可编辑；失败保留草稿，旧会话的回执不再清空后续草稿。
- 图片触控板缩放后调整窗口大小会保留手动缩放比例；点击“适应窗口”仍可重新适配。
- 投递详情中的“未读”改为“待处理”，区分阅读状态与处理状态。

146 项回归、937 个断言、类型检查与 Swift 编译通过。本次作为 Mac 源码修复提交到 GitHub，不另发 Release 或 APK。本地 Mac 已部署同一份实现；手机真机测试继续暂停，真实跨 App、手势、多屏及手机动作仍待验收。详见 [复核记录](docs/mac-ui-audit-2026-11-15.md)。

## 未发布 - 2026-11-15 · 镜像适配、置顶常驻与图片放大

- **屏幕高度适配**：最近投递和手机镜像两个 Tab 打开、恢复、切换及跨屏时默认使用当前显示器最大可用高度，保留现有宽度并避开菜单栏和 Dock；仍可手动缩小。
- **镜像画面布局**：修复横屏或窄窗口中侧边按钮挤压画面容器，导致画面比例与点击坐标不一致的问题。横竖屏画面完整等比显示；长应用名可换行，诊断文本限高滚动，底部输入栏保持可见。
- **置顶常驻**：修复 NSPanel 在应用失活时自动隐藏，导致开启钉住仍无法常驻的问题。投递页按钮改为“置顶常驻”：开时置顶且保持显示，关时投递失焦收起；镜像失焦保持显示，隐藏该投递专用按钮。
- **图片放大查看**：单击投递图片打开独立原生查看窗，支持放大、缩小、适应窗口、100%、滚动和 Esc 返回；支持键盘及无障碍激活，不修改原图。打开图片保留主窗，关闭主窗、切 Tab 或最小化时清理图片查看窗。
- **镜像恢复**：最小化恢复后加载未开始的页面，仍需手动点击“开始镜像”，不会自动恢复采集。

两轮修改与设计、验证记录一并作为 Mac 源码更新提交，不另发 Release 或 APK；本地 Mac 已部署同一份实现，无需重装手机 App。

完整回归 **144 项、931 个断言通过**，类型检查及 Swift 编译通过。手机真机测试继续暂停；真实多屏拖动、跨 App 切换体验、中文输入法、手机横竖屏点按和长期观看仍待人工/真机验收。详见 [镜像适配复核](docs/hud-mirror-fit-review-2026-11-15.md) 与 [投递及图片验证](docs/hud-inbox-image-review-2026-11-15.md)。

## 未发布 - 2026-11-15 · 浮窗尺寸修复

- 修复长图预览将原始像素尺寸传给 AppKit、把窗口撑出屏幕的问题；图片现在按预览区域等比缩放。
- 隐藏投递详情时移除其布局约束，避免影响镜像 Tab；附件操作栏在窄窗口中横向滚动。
- 打开、切换 Tab 和跨屏时校正窗口到显示器可用区域，并限制最大尺寸。
- 补齐长图、缩放、屏幕边界、Esc 和关闭按钮的原生回归；144 项测试、931 个断言通过。实际多显示器拖动与手机真机验收仍待完成，详见 [验证记录](docs/hud-window-layout-fix-2026-11-15.md)。

本次作为 Mac 源码修复提交，不另发 Release 或 APK。日常 Mac 浮窗助手已更新并重启，主服务保持运行；手机真机测试继续暂停。

## 未发布 - 2026-11-15 · Mac 原生镜像 Tab

- 在原生浮窗加入「最近投递 / 手机镜像」Tab 和菜单入口，内嵌现有镜像页面；点击「开始镜像」才加入观看，默认不自动唤醒或解锁手机。
- 切换到投递、关闭、最小化或 Mac 休眠时释放本窗口观看连接；其他浏览器观看端不受影响。最后一个观看端离开后沿用 25 秒停止宽限。
- 补齐输入回执、失败保留文字、有界重连、过期画面禁止操作与迟到解码丢弃。镜像页失焦保持显示，投递页沿用原来的收起规则。
- 使用动态本机端口、非持久化 WebKit 数据存储和受限导航；补齐安装、自动编译与手动构建的 WebKit 链接。修复镜像容器缺少固有高度时窗口可能被压缩的问题。

开发及模拟回归完成；本次作为 Mac 源码更新提供，不另发 Release/APK，真机测试继续暂停。部署需同步更新主服务与浮窗助手。验证范围和待验收项见 [实施记录](docs/hud-mirror-tab-implementation-2026-11-15.md)。

## 未发布 - 2026-11-15 · Mac 菜单栏浮窗修复

- 修复点击投递详情入口时，AppKit 因互斥的窗口行为选项而终止 HUD 助手、浮窗无法打开的问题；移除冲突的 `moveToActiveSpace`，保留跨桌面和全屏辅助窗口行为。
- 菜单改为“查看最近投递（N 条）”，明确显示最近最多 25 条记录，包含已处理记录，不代表未读数或全部历史数。
- 新增真实 AppKit 窗口构造回归，覆盖空列表、1/25/30 条记录及选择状态。Mac/协议 135 项、882 断言通过，类型检查与 Swift 编译通过。

本次仅提交 Mac 修复及回归测试，不另发 Release 或 APK。真实菜单点击、多桌面和全屏交互仍待验收；手机真机测试继续暂停。详见 [修复与验证记录](docs/hud-window-fix-2026-11-15.md)。

## 1.6.5 - 2026-11-15

本版修复 v1.6.4 复核确认的 1 个 P1、6 个 P2，并补齐收件箱提交失败恢复与错误提示。

**自动化检查和构建已通过，真机验收尚未完成。发布不代表手机安装、厂商无障碍事件、AirPods 或锁屏场景已经验证通过。**

## 主要修复

- **镜像确认后的安全复核**：敏感点按确认绑定原应用窗口和目标节点；批准后重新读取窗口、密码/金融保护与节点属性。等待期间切换页面、切回旧页或更改目标都会拒绝旧请求。保留急停、会话失效与期限检查。点按/长按预算最多65秒，普通输入仍5秒，调用方更短期限仍有效；Mac配套延长等待。
- **Mac 原生 HUD 双向通信**：分离阻塞读取与串行发送，处理连接关闭、重连和写入超时；“已处理”等待主机持久化后的同步，避免界面先成功、实际未保存。
- **传输 ACK 错误处理**：从确认 Promise 创建起即观察拒绝，背压等待中的超时、ERROR及断连不会形成未处理拒绝，原错误仍传给调用方。
- **附件预算与可解释失败**：系统分享、快传、持久化及发送统一最多9件（含语音）、单件30MiB、合计64MiB。未知长度按实际读取字节检查；保存失败不回退瞬时URI，不显示已送达。保存中断恢复为失败条目，保留原因及私有文件关联。
- **可验证 OTA**：从认证RPC或GitHub HTTPS元数据获取预期SHA-256；App私有缓存下载后验证摘要、包名、版本及当前签名，再交系统安装器。去除未认证HTTP元数据降级和浏览器下载假成功。APK上限128MiB、下载超时120秒；安装器启动不等于安装完成。
- **收件箱准入与提交恢复**：500条记录或2GiB已索引附件预算，超限明确拒绝新增并保留历史资料；手机显示原因，可整理Mac后按原条目重试。附件复制和同步完成后才提交权威索引，失败保留原暂存源；衍生视图写失败不再误报已提交条目未收到。
- **默认设置与说明勘误**：相机照片胶囊缺省关闭，已有显式选择保留。校正旧版关于Cookie、OTA校验、自动清理和语音路由默认值的描述。


Android 161项、Mac/协议134项通过，类型检查、原生编译、Release构建与验签通过。APK为1.6.5/build42；真机镜像保护、HUD界面、附件提供者、OTA安装与硬件回归尚待验收，详见 [完整更新说明](docs/releases/v1.6.5.md)。

## 1.6.4 - 2026-11-14

新增手机端相册多图与通用文档（PDF / Word / Excel / PPT / Markdown / ZIP）一键快传、macOS 原生无 Dock 菜单栏与内嵌速览浮窗（`oms-mac-hud`），并完成全栈安全边界、传输可靠性、三级语音播报路由及后台功耗/磁盘配额治理。

### 新增能力与交互升级

- **手机端相册多选与通用办公文件快传**：
  - **控制中心快捷磁贴半屏快传面板（`QuickSendSheetActivity`）**：下拉控制中心点击磁贴弹出轻量半屏面板，一键直达「发相册图片（多选）」「发文件（PDF/Word 等）」「发剪贴板」「按住发语音」。
  - **系统分享多附件与文档支持（`ShareReceiverActivity` + `AttachmentResolver`）**：支持 `ACTION_SEND` 与 `ACTION_SEND_MULTIPLE`（原版未统一执行数量和单文件限制），保留原始中文文件名与扩展名，新增 `file` 媒体类型投递。
  - **可选相机新拍照片悬浮胶囊（`ScreenshotPillController`）**：支持在 App 主界面开启相机新拍照片「发送到 Mac」悬浮胶囊（原版缺省开启，本轮修复调整缺省值）。
- **macOS 原生菜单栏与内嵌速览 HUD（`oms-mac-hud` + `MacHudServer`）**：
  - 新增无 Dock 原生 Swift AppKit 助手 `oms-mac-hud`，彻底替代 `osascript`（点击误开 `Script Editor.app`）与自动弹出 `Preview.app` 的打断式通知。
  - 提供右上角不抢焦点的毛玻璃通知卡片、顶部菜单栏最近 20 条收件历史，以及内嵌 `NSImageView` / `PDFKit` / `QuickLookUI` (`QLPreviewView`) / `AVAudioPlayer` 的原生速览浮窗（免打开外部软件直接阅读 PDF/Word/图片/语音，并支持一键复制文件以便 `⌘V` 粘贴）。

### 全栈安全、可靠性与功耗治理

- **安全与权限边界**：Web Dashboard 仅允许本机回环访问、跨源 `POST` 拦截与镜像 WebSocket 鉴权；统一离线/在线配对码过期校验；收紧无障碍窗口事件采样并移除测试 MAC 硬编码；OTA 原版仅比较版本/摘要元数据并打开浏览器下载，未校验下载后的 APK。
- **传输与语音路由**：`oms-bt-bridge` 监听 `STDIN` EOF 防孤儿驻留，`DeliveryPolicy` 队列落盘防丢；手机语音播报重构为三级声道策略（普通播报默认闹钟声道并尊重耳机，媒体声道需显式选择，寻机或紧急播报强制扬声器）。
- **功耗与存储治理**：蓝牙已连桌面时暂停手机后台 mDNS 持续扫描，息屏挂起传感器与前台应用轮询，`ScreenCapturer` 复用 `VirtualDisplay`；Mac 原版仅清理暂存缓存，未实现收件箱总配额。

### 自动化验证与兼容性

- Android JVM：**132 项通过**（25 个测试类，0 failure / error / skipped）；Mac/共享协议：**123 项通过**（16 个测试文件，0 fail，800 assertions）。
- APK：`OneMoreStep-v1.6.4.apk`（versionName **1.6.4** / versionCode **41**，Release 构建），与 v1.4.x–v1.6.2 签名一致可直接覆盖升级，SHA-256：`15f5d8493f7ba212d5688cc2c59a259cdf37fc7bfdac3950dc93cc670824f411`。

## 1.6.2 - 2026-11-06

修复 v1.6.1 审查发现的 3 个 P1、6 个 P2 问题，新增 38 项自动化回归。**本版已通过本地自动化测试与构建，以下真机验收尚未完成，不代表相关硬件场景已验证通过。**

### 核心修复

- **镜像窗口保护**：执行前恢复全部应用窗口及焦点密码检查，拦截分屏/小窗中的受保护目标，保留既有安全导航特例。
- **镜像急停与用户接管**：操作期间登记当前任务，急停胶囊展示成功后才执行；手势取消锁存急停，迟到回调和旧队列不能越过手机上的显式恢复。
- **通话会话与期限**：接听后的等待、Mac 播放、重复播放和挂断共享原连接与绝对期限；断连、连接替换、急停或到期时停止并回收播放进程，旧请求不会挂断新会话。
- **Keystore 配对一致性**：新配对加密成功后才提交配置；加密失败保留旧配对，不再写入明文；密文不可读时不回退旧明文；发现服务仅刷新路由字段。
- **AI 通话输入目标**：只在允许包及同一窗口内选择可见、启用、非密码输入框；聚焦前后再次核验，发送按钮也绑定原窗口。
- **Mac 外放路由核验**：通过原生 CoreAudio 确认内置扬声器后才播放；耳机、HDMI 或未知输出明确返回 `ERR_MAC_SPEAKER_ROUTE`，播放中路由变化会取消。不会自动改变用户的音频输出。
- **分享 URI 过滤**：拒绝带用户限定符的外部 authority，阻止 `0@sms`、`10@calendar` 等绕过，正常 content 附件仍可使用。
- **CI 与发布可追溯性**：删除仓库中写死的 macOS JDK 路径；校正旧 v1.6.1 发布说明的 build38 与 SHA-256，本版使用新版本及独立校验文件。

### 自动化验证

- Android JVM：117 项通过，0 failure / error / skipped。
- Mac/共享协议：112 项通过，0 fail，690 assertions。
- TypeScript 类型检查、Swift 桥接编译、Android Release 构建及 APK 签名校验通过。
- 测试使用隔离模拟设备和无声音频替身；不将上述结果视为真机验收。

### 尚需真机验收

1. **镜像**：分屏、小窗、密码焦点防护；急停胶囊、手指打断后的停止锁存、本地恢复及连续手势流畅度。
2. **AI 通话**：目标机型页面正常填文/发送，其他应用输入框不被写入，窗口或密码属性变化后停止。
3. **通话延续**：使用约好的测试号码验证接听后断连、重连、急停、超时，确认停止播放且不误挂新会话。
4. **真实音频路由**：内置扬声器、AirPods、HDMI 的识别及播放中切换。路由轮询间隔 250 ms、单次查询最长 1 秒；系统路由无法与 afplay 原子锁定，取消存在检测延迟。播放完成不等于对方听清。
5. **Keystore**：真实设备上的旧密钥迁移、不可用及恢复行为。不要对唯一的真实配对配置做破坏性故障注入。

### 安装与兼容性

- APK：`OneMoreStep-v1.6.2.apk`，Android 11+，包名 `com.oms.client`，versionName **1.6.2** / versionCode **39**，Release 构建（`debuggable=false`）。
- 使用与公开 v1.6.1 相同的签名，可直接覆盖安装；正常从 v1.4.x–v1.6.1 升级无需卸载、清除数据或重新配对。原 Keystore 密钥已失效时仍可能需要重新配对。
- Mac 端应同步更新源码并重新构建 Swift 桥接，再重启服务，以启用完整通话与音频路由保护。旧桥接不支持新的输出核验时，Mac 传话会明确失败；本次 GitHub 发布不会自动升级本机正在运行的服务。
- APK 校验值见同一 Release 的 `SHA256SUMS.txt`；详细修复记录见 [回归记录](./docs/review-fixes-2026-11-06.md)。

## 1.6.1 - 2026-10-31

完成双端代码诊断报告的 4 批次全栈安全加固、GUI Agent 防绕过护栏、Keystore 密钥保护、传输背压控制与主线程 I/O 性能优化。

### 核心升级与修复

- **Mac 宿主端安全加固与性能治理**：
  - 修复 Web Dashboard 存储型 XSS（补齐单引号转义、移除卡片动态内联 `onclick` 拼接、改用 `data-item-action` 事件委托，并注入 `Content-Security-Policy` 与 `X-Content-Type-Options: nosniff`）。
  - 收紧 `~/.one_more_step` 目录（`0700`）、`config.json`（`0600`）与 Unix Socket（`0600`）权限；`config.json` 原子写入且解析异常时自动备份为 `.corrupt.<ts>.bak` 不丢数据；IPC Socket 补齐 `error` 监听。
  - `InboxManager` 改为按变更项增量更新 `meta.json`（$O(1)$）；过滤高频轮询日志并为 `omsd.log` / `connections.jsonl` 增加 2 MB 自动轮转；修复 Swift 蓝牙桥 `fd` 锁保护、`SIGPIPE` 忽略及 `txQueue` 误清空问题。
- **Android 端安全护栏与系统入口加固**：
  - 坐标点击强制要求 `observationId`，并通过 `SemanticTreeExtractor.findSmallestNodeAt` 对坐标命中节点及子树聚合文本执行敏感词拦截；将 `com.oms.client` 本包、支付/安装器/权限控制器与非蓝牙设置页纳入黑名单，检查分屏/小窗多根节点并拦截 `isPassword` 节点。
  - 解锁、拨号、挂断、实时同屏全路径补齐急停胶囊展示；手势被用户触摸取消（`onCancelled`）时锁存急停，并在 `MainActivity` 顶部新增本地「▶️ 恢复 AI 操作」按钮。
  - 收紧系统分享与语音闪念标签白名单（禁止伪造 `sms_otp`），拦截外部 `file://` 与敏感 ContentProvider URI；修正 `AndroidManifest.xml` 中 `android.provider.Telephony.SMS_RECEIVED`，设置 `android:allowBackup="false"`。
- **传输可靠性、Keystore 密钥保护与 Release 构建**：
  - 修复 `isConnectingLan` 在 generation 过期退出路径未复位的死锁 Bug；增加 WebSocket 发送背压流控与断连自动停止屏幕镜像。
  - 使用 Android Keystore (`AES/GCM/NoPadding`) 加密存储配对密钥（支持旧版明文自动无感迁移）。
  - 切换为非调试版 `assembleRelease`（`debuggable=false`，保持签名兼容以支持平滑覆盖升级），缓存 APK SHA-256 并将耗时 I/O 移出主线程。

### 安装与兼容性

- APK：`OneMoreStep-v1.6.1.apk`，Android 11+，包名 `com.oms.client`，versionName **1.6.1** / versionCode **38**；可直接覆盖安装。
- APK SHA-256：`4a8b209362196d6a1e2770ce77153ad72d14adc6efb2451f76f02a01abbd5011`。

## 1.6.0 - 2026-10-30

新增自动电话拨打 + 接通检测 + 多模式智能语音传话（`--mac-say` / `--ai-call` / `--say`）与常用联系人速查表（`oms contacts`），并修复小米 HyperOS 通话界面免提/挂断嵌套控件点击、免 `READ_PHONE_STATE` 权限通话状态判定、小米画报锁屏自动退出以及蓝牙桥重连恢复。

### 新增能力与修复

- **常用联系人速查与按姓名极速拨号（`oms contacts` & `oms call <姓名>`）**：支持将常用联系人姓名、号码与默认 SIM 卡槽保存在 `~/.one_more_step/config.json` 中，`oms call 老婆` 直接 0ms 解析号码与卡槽，无需先打开手机通讯录 UI 搜索。
- **自动接通检测（`CallAnswerDetector`）**：轮询通话界面无障碍节点，识别拨号/振铃到接通计时器（同时兼容标准 `00:01` 与小米 HyperOS 中文秒数格式 `"7秒"`、`"SIM卡2 · 7秒"`、`"1分12秒"`），支持接通后可选自动挂断（`--hangup-after`）或保持通话。
- **通话传话三模式（应对高通/小米硬件级回声消除 AEC）**：
  - **方案 A（`--mac-say "<文本>" --voice <音色>`）**：手机自动开免提并在检测到接通瞬间由 Mac 扬声器播放指定 Edge Neural TTS 真人音色（如 `hsiaochen` 台湾女声），作为外部声源喂给手机远场麦克风，不被手机本机 DAC 硬件回声参考抵消；
  - **方案 B（`--ai-call "<文本>"`）**：检测到对方接通后自动点击小米通话界面右上角系统原生「AI通话」按钮，填入文字并发送，由系统直接注入蜂窝基带上行；
  - **本机播报（`--say "<文本>"`）**：手机端 `NotificationExecutor.speakTextAndAwait` 走 `USAGE_ALARM` + `TYPE_BUILTIN_SPEAKER` 播放并等待完成。
- **小米 HyperOS 免提/挂断嵌套按钮修复**：针对小米 `com.android.incallui` 底部按钮外层 `Switch`（`label="免提"`，`clickable=false`，`checkable=true`）嵌套内层无文本 `ImageButton`（`clickable=true`）的结构，支持向下穿透查找可点击子控件并直接读取 `Switch.isChecked` 验证免提开关状态。
- **免 `READ_PHONE_STATE` 权限通话状态判定与双卡默认回退**：当 `TelecomManager.isInCall` 因缺少运行时权限返回 `null` 时，自动回退使用 `AudioManager.mode` 与 `incallui` 窗口检测；双卡未显式指定 `--sim` 时默认选择首张卡，不再报错中断。
- **小米画报锁屏自动退出与 BLE 重连恢复**：解锁遇到 `com.mfashiongallery.emag`（小米画报）时自动先发送 `BACK` 退回系统锁屏再上滑绘制图案；修复 `oms daemon restart` 后 BLE GATT 写入帧自动恢复 `activeCentralId`。

### 安装与兼容性

- APK：`OneMoreStep-v1.6.0.apk`，Android 11+，包名 `com.oms.client`，versionName **1.6.0** / versionCode **36**；沿用既有 Android Debug 签名，可覆盖安装。
- 从 v1.4.x / v1.5.x 升级无需卸载、清除数据或重新配对。
- APK SHA-256：`6acffb51ec25678f829db2655cfdfc8d70f6a2fb3f53d53303b044a27c88481d`。

## 1.5.9 - 2026-10-30

修复抖音播放后 AirPods 未自动从 Mac 切到手机的问题，并补齐自动交接确认和媒体信号诊断。Mac 与 Android 请配套更新；已经安装 v1.5.9 build35 的用户无需再次安装。

### 修复与诊断

- **播放开始识别**：实测抖音暂停后底层播放器配置仍可能保持 STARTED，使旧逻辑一直认为正在播放，漏掉下一次播放。现在该配置仅用于诊断，不再独立触发耳机接管；保留系统音乐状态、媒体会话、通话和自身语音过滤。
- **先释放、后连接**：手机自动接管先等待 Mac 确认释放目标耳机，再建立手机连接。失败、超时、原加密会话失效、停止播放或新操作替代时，不继续旧自动请求。
- **交接互斥**：Mac 自动释放期间拒绝重叠交接；失败后正确解除占用，允许后续请求继续。
- **媒体诊断**：`oms status --live` 的 `phoneStatus.airpodsAutoSwitchDiagnostics` 提供分来源播放信号、回调次数、抑制时间及有界决策轨迹，不采集媒体标题或音频。
- **握手兼容**：诊断数据经认证后的状态 RPC 获取，排除在手机握手快照之外；Mac 兼容忽略早期 build34 的可选诊断字段，保留身份校验、必填字段检查与 32 KiB 限制。

### 安装与兼容性

- APK：`OneMoreStep-v1.5.9.apk`，Android 11+，包名 `com.oms.client`，versionName **1.5.9** / versionCode **35**；沿用既有 Android Debug 签名，可覆盖安装。
- 本次附件与已完成真机验收的 build35 候选 APK 字节一致。诊断 build34 用户需覆盖升级；v1.4.x / v1.5.x 无需卸载、清除数据或重新配对。
- Mac 更新代码后运行 `bun install --frozen-lockfile`、`bun run --cwd mac-host build:bt`，再运行 `oms daemon restart`。无新增第三方依赖。
- APK SHA-256：`3205ef5e86f0cfd38d881180fe1a85e4f4fb0841cde4ec0da6753ceb9842f54d`，另附 `SHA256SUMS.txt`。

### 已完成验证

- Android **82/82** 测试，Mac/协议 **96/96** 测试（624 assertions）；TypeScript、APK 构建及签名检查通过。
- 自动化回归覆盖释放确认先于连接、释放失败/取消、会话失效、播放停止或请求被替代，以及 Mac 交接互斥和失败后恢复。
- AirPods 4 + 本机手机 + 抖音：同一运行进程内连续两轮自动接管成功，用户均确认耳机声音正常；暂停等待时没有误抢，持续播放回调未重复发起切换，最终耳机留在手机。
- 两次 `claim_sent` 至活动音频设备确认分别为 **8697 ms**、**3046 ms**；这不是从用户点击开始计时，也不是长期延迟保证。

### 仍需验收及边界

- 长时间连续使用、锁屏、网络切换、其他手机系统/耳机型号和其他视频 App。
- 本轮未人为断开真机网络来注入失败；取消、失效与并发路径由回归测试覆盖。
- 本轮未重测通话、TTS/紧急语音或拨号功能；两轮针对性通过不代表全部场景兼容性验证完成。

## 1.5.8 - 2026-10-29

本版本合并此前未单独发布的 v1.5.7 拨号盘修复与 v1.5.8 AirPods 超时恢复修复。Mac 与 Android 需配套更新；已经安装 v1.5.8 build33 的手机无需再次安装。

### 修复与诊断

- **拨号盘就绪**：固定 520 ms 等待改为最多 3 秒的就绪轮询，核验拨号包及准确号码；等待期间持续检查锁屏、急停、会话和总期限。仅打开拨号盘模式不会进入呼叫步骤。
- **Mac AirPods 恢复**：每次连接使用独立原生短进程，结束、取消或超时后回收，避免失败客户端状态被后续请求复用。主桥接继续响应，整体期限 22 秒，成功前确认目标设备与默认音频输出。
- **进程退出保护**：连接子进程具备独立单调期限和父进程存活检查，即使主桥接退出前未处理取消，也会自行结束。
- **Android AirPods 恢复**：已接受或正在进行的 A2DP 连接不再重复提交；移除不必要的连接策略、设备级和非通话时 HFP 请求。手机等待最多 12 秒，Mac 为传输和响应留出余量。
- **切换护栏**：统一两端等待期限，确认真实释放和活动音频路由；拒绝重叠手动请求，绑定手机会话，明确指定目标时不回退其他耳机。Mac 按设备 UID 匹配目标音频输出，兼容蓝牙名与音频名不同。
- **底层诊断**：记录返回码、权限异常、profile 状态、耗时及连接进程启动/退出/取消。`oms airpods status` 可查看最近诊断，`~/.one_more_step/logs/airpods-operations.jsonl` 最多保留 200 条；不记录音频、配对密钥或密码。

### 安装与兼容性

- APK：`OneMoreStep-v1.5.8.apk`，Android 11+，包名 `com.oms.client`，versionName **1.5.8** / versionCode **33**；沿用原 Android Debug 签名，可覆盖安装。
- 已安装 v1.5.8 build33 的用户使用的就是本次 APK；早期诊断 build32 需要覆盖升级。v1.4.x/v1.5.x 配对与数据结构不变，无需卸载、清除数据或重新配对。
- Mac 更新代码后运行 `bun install --frozen-lockfile`、`bun run --cwd mac-host build:bt`，再运行 `oms daemon restart`。需要 Bun、Swift/Xcode Command Line Tools；无新增第三方依赖。
- APK SHA-256：`72ccacbf3edc827f3a1fffe3b68576e3b36d169764bc903420beb6e668fa452b`，另附 `SHA256SUMS.txt`。

### 已完成验证

- Mac/协议 **94/94** 测试（614 assertions），Android **74/74** 测试；TypeScript、Swift、正式 `build:bt`、Android APK 构建和签名检查通过。
- 原生进程回归覆盖挂起且忽略 SIGTERM 时的回收、只完成一次、新请求继续成功、独立期限和父进程突然退出。
- AirPods 4 本机真机：同一主服务与桥接进程经历合盖超时后，切到 Mac **2487 ms**、切回手机 **2581 ms**，最终确认手机活动音频设备；连接子进程已退出。合盖超时后约 28 秒内 6 次检查未发现迟到连接。
- 拨号盘首次打开（含解锁）**5119 ms**、重复打开 **869 ms**；两次均 `dialedOnly=true`、`callPlaced=false`。测试号码已清除，返回 App 并恢复原锁屏状态。
- 发布前双轴审查完成：进程退出边界已修复并通过测试，更新说明已补齐拨号盘范围。

### 仍需验收及边界

- 长时间反复切换、长时锁屏与网络变化后的稳定性，其他手机系统与耳机型号兼容性。
- 严格强制结束拨号器进程后的冷启动；实际呼叫/挂断、双卡、免提及通话音频。本轮仅测试拨号盘，没有拨出电话或播放声音。
- TTS/紧急语音和音质测试未在本轮执行。
- 本次是一轮针对性复现回归，不代表所有兼容性问题均已解决。系统已接受的蓝牙请求无法由应用保证撤销；应用在期限失效后停止后续重试和输出切换。原生返回码不能单独证明系统内部唯一根因。

## 1.5.7 - 候选，2026-10-29

**尚未发布 GitHub；Mac/手机已部署。基础真机验收通过，但扩展测试发现 AirPods 超时后的自动恢复连接不稳定，仍待修复。** 详见 [v1.5.7 候选说明](./docs/releases/v1.5.7.md)。

- 拨号盘冷启动由固定 520ms 等待改为最多 3 秒的就绪轮询，保留号码、锁屏、急停、会话及总期限校验。
- 统一 AirPods 原生连接、桥接、手机 RPC 和 CLI 的等待预算；超时、原生请求替代或宿主断开后停止后续步骤，不接受迟到成功。
- Android 移除阻塞 RFCOMM 兜底，直接等待可取消连接流程；两端只有确认目标连接及相应活动/默认音频输出才成功。
- 明确指定 MAC 时不回退其他耳机；实际确认释放，修复自动释放失败仍报成功；拒绝重叠手动切换，手机接管绑定原会话。
- 真机发现蓝牙名称与 CoreAudio 名称不一致，改为按目标地址匹配音频设备 UID，避免名称差异导致连接误判。
- 候选 APK versionCode 31；需同步更新 Mac 与 Android。更新说明附未完成真机验收清单。

## 1.5.6 - 2026-10-29

**验收状态：自动化检查及 APK 构建通过；本轮尚未进行真机验收。** 以下锁屏、通话、通知和耳机行为仍需按清单验证，不能将离线测试视为真实设备兼容性保证。

### 本次修复

- **急停与动作期限**：镜像输入接入 GUI 急停、串行锁与最多 5 秒期限；解锁/拨号共用最多 25 秒期限，在副作用前后和等待结束后复核授权。过期、撤权、急停或排队失效时停止后续操作。
- **解锁与拨号**：解锁失败不继续拨号，不自动重试凭据；删除拨号、免提、挂断及旧版兼容路径中的固定坐标回退。拨号前核验拨号包、号码、SIM 和按钮，挂断后确认通话结束，避免误触和成功误报。
- **凭据保护**：移除返回值/错误信息中的真实图案序列；PIN/图案输入要求可确认的系统锁屏窗口。
- **连接变化保护**：控制请求绑定原物理连接与路由代次，连接替换或切换后拒绝旧操作继续；补充超时、迟到/重复回复回归。
- **旧版镜像输入**：不再通过剪贴板或其他入口绕过受保护的输入路径；不支持时明确提示升级，不将复制成功当作输入成功。
- **镜像生命周期**：串行化启动/停止，丢弃过期结果；启动中关闭最后一个观看页面也能安排停止，避免残留采集。
- **AirPods 结果**：手机离线时不释放 Mac 连接；释放超时、手机接管失败明确报错。
- **紧急语音通知**：区分全屏自动展示与用户点击停止，修复自动展示误触发停止播报。
- **Neural TTS 交付**：使用仓库自带 Python 适配器和独立 venv，不再依赖外部 Codex Skill；固定 edge-tts 7.3.1，提供明确降级原因。

### 升级与兼容性

- 请同步更新 Mac 代码与 Android APK，完整护栏需要两端配合。
- APK：`OneMoreStep-v1.5.6.apk`，Android 11+，包名 `com.oms.client`，versionCode **30**，沿用 Android Debug 构建方式；已与 v1.5.5 公开 APK 比对，签名证书 SHA-256 一致，可覆盖升级。
- v1.4.x/v1.5.x 的配对协议与数据结构未更改；同签名覆盖安装无需清除数据或重新配对。更早的 v1.3.x 仍需按 v2 配对流程升级。
- Mac 更新依赖后，需要真人音色时在仓库运行 `bun run setup:tts`，再重启守护进程 `oms daemon restart`。安装只准备依赖；在线合成会向 Microsoft Edge TTS 服务提交待朗读文本，依赖缺失或合成失败时可回退手机本地语音。
- 无法确认 SIM/呼叫按钮时返回 `call_target_or_sim_not_verified`；无法确认系统锁屏窗口时返回 `ERR_KEYGUARD_SURFACE_UNAVAILABLE`。旧版镜像输入或不支持的解锁/拨号端点返回升级提示，可能需要更新两端或针对 OEM 做后续适配。
- RPC 超时仅表示 Mac 不再等待，不能撤回底层已发送的数据；已被系统接受的拨号或手势不保证能够撤销。

### 已完成的验证

- Bun：**83 pass / 0 fail**，575 assertions（真实设备发现关闭，使用回环及模拟对端）。
- Android JVM：**56 tests / 0 failures / 0 errors / 0 skipped**。
- Python TTS 离线适配测试：**3 pass**。
- TypeScript 两个 workspace 类型检查、冻结锁文件安装检查及 `git diff --check` 通过。
- Android `testDebugUnitTest assembleDebug --offline --no-daemon` 成功；APK manifest 版本及签名验证通过。
- TTS 隔离 venv 安装/导入已验证；未进行在线文本合成或实际播放。

### 仍需真机验收

1. **镜像和急停**：急停/恢复、排队输入、断线重连与页面切换时动作是否正确停止；快速开关镜像后采集及保亮是否退出。
2. **图案/PIN 解锁**：实际 OEM 锁屏窗口、键盘及已校准图案区域识别；解锁失败、急停或撤权后不继续输入/拨号。
3. **拨号/挂断与双卡**：真实拨号包、号码、SIM 标签、呼叫/挂断按钮、免提和通话状态确认。必须事先约定测试号码和参与人，不自动拨打真实联系人。
4. **紧急语音通知**：锁屏全屏自动展示后应继续播报；点击通知、停止按钮、侧键与解锁停止行为需验证。
5. **AirPods**：离线不释放 Mac、在线接管与接管失败提示，以及系统实际音频路由。
6. **Neural TTS**：新部署机器的在线合成、手机播放，以及断网/依赖缺失时的本地降级。

### APK 校验

- 大小：**22,721,753 bytes**。
- SHA-256：`f4905fdfffc76c76e9b18a8cd2e5c058e124bb5c5a9f90bc8328faf17f1dbab5`。
- Release 同时提供 `SHA256SUMS.txt` 和独立更新说明。

发布代码与附件不代表已在用户设备部署或完成上述真机验收。详细测试边界见 [审查修复验收记录](docs/review-fixes-2026-10-29.md)。

## 1.5.5 - 2026-10-28

### 手机端 5 种随手即停交互与 Mac `Music.app` 防误唤醒修复

- **手机端 5 种随手停止找手机 / 语音播报方式**：
  1. **侧面物理按键即停**：在 `NotificationExecutor.kt` 中注册系统音量与息屏广播监听（`android.media.VOLUME_CHANGED_ACTION` / `ACTION_SCREEN_OFF`），拿到手机后随手按一下侧面音量 `+`、音量 `-` 或电源键立即停止语音与震动。
  2. **解锁屏幕即停**：监听 `ACTION_USER_PRESENT`，指纹 / 面容 / 图案解锁进入桌面瞬间自动停声。
  3. **通知点按或左滑划掉即停**：紧急找手机通知绑定 `setContentIntent` 与 `setDeleteIntent`，点击通知横幅或左滑清除通知立即停止播报。
  4. **通知栏一键停止按钮**：新增 `OmsTtsStopReceiver`，在锁屏/顶部通知栏提供 **`[⏹ 我找到了 · 停止声音]`** 动作按钮。
  5. **App 顶部醒目红条**：在 `MainActivity` 顶栏新增实时联动的红色横幅 **`🔊 正在语音播报 / 寻找手机 · [⏹ 停止声音]`**。
- **彻底修复手机说话时 Mac 自动弹出 Apple Music (`Music.app`)**：
  1. 在 `mac-host/bluetooth-bridge/main.swift` 的 `pauseMacMediaIfPlaying()` 中移除硬件媒体键 `NX_KEYTYPE_PLAY` 模拟，并增加 `isDeviceRunning(defId)` 与 `MRMediaRemoteGetNowPlayingApplicationIsPlaying == true` 双重守卫，杜绝向空闲的 macOS `rcd` 守护进程发送媒体键而拉起 `Music.app`。
  2. 在 `AirPodsAutoSwitchManager.kt` 与 `oms-daemon.ts` 中豁免手机 TTS / 寻找手机播报期间的 `airpods.claimByPhone` 抢占请求。

### APK

- `OneMoreStep-v1.5.5.apk`：Android 11+，versionCode 29，22,818,517 bytes（沿用 Android Debug 签名，从 v1.4.x / v1.5.x 覆盖安装无需重新配对）。
- SHA-256：`1b08b4243b686b95c176b251458b24089f6997e492c779f60b0a08d1df3bad28`。

## 1.5.4 - 2026-10-28

### Microsoft Edge Neural TTS 9 款真人自然音色、循环寻机播报与 SHA-256 本地缓存

- **9 款 Microsoft Edge Neural TTS 真人自然语音集成**：在 Mac 守护进程 `omsd` 中内置免 Key 的神经网络语音合成引擎，提供 9 款高自然度音色（`xiaoyi` 晓伊·甜美元气女声[默认]、`xiaoxiao` 晓晓·温柔知性女声、`yunxia` 云夏·动漫元气少年音、`yunxi` 云希·阳光青年男声、`yunjian` 云健·沉稳磁性男声、`xiaobei` 晓北·东北话幽默女声、`xiaoni` 晓妮·陕西话亲切女声、`hsiaochen` 晓臻·台湾腔甜美女声、`hiugaai` 晓佳·新增粤语女声），支持中英文简称映射与 SHA-256 磁盘缓存（`~/.one_more_step/cache/tts/`）。
- **闹钟声道 MP3 音频流循环播放与离线回退**：Android 端 `NotificationExecutor` 支持通过 `MediaPlayer` + `USAGE_ALARM` 闹钟声道突破静音播放合成的 Neural TTS 音频，支持 `repeatCount`（`1..20` 次循环播报，`oms phone find` 默认 3 次），离线时自动挑选手机本地高自然度女声 Voice 模型并优化语调（`pitch=1.06f, speechRate=1.03f`）。
- **通勤随身听同步接入 Neural TTS**：新增 `tts.synthesize` 反向 RPC，手机端点击信息流卡片上的 `🎧 TTS朗读` 时同样自动调用 Mac 端 Neural TTS 真人音色。

## 1.5.3 - 2026-10-28

### WebP 硬件加速投屏与自适应 10ms 极速流控

- **WebP 屏幕帧编码与全链路升级**：将实时屏幕镜像编码从 JPEG 全面升级为 `WEBP_LOSSY`（`ScreenFrameCodec.WEBP = 2`），同画质下帧体积缩减约 30%~40%，显著提升局域网与无线投屏清晰度与帧率稳定性。
- **自适应 10ms 极速重抓与在途窗口扩容**：Android 端 `ScreenMirrorEngine` 在画面活跃变化时自动缩短重抓间隔至 `10ms`，并将 `maxInFlight` 窗口提升至 3 帧。

## 1.5.2 - 2026-10-28

### AirPods 跨生态无感自动流转、对端媒体自动暂停与实时屏幕镜像工作台

- **AirPods 跨 Mac ⇄ 小米手机蓝牙无感自动切换 (`oms airpods`)**：在 `oms-bt-bridge`（macOS `IOBluetooth` + `CoreAudio`）与 Android 端 `AirPodsAutoSwitchManager`（`BluetoothA2dp` / `BluetoothHeadset` + `AudioPlaybackConfiguration`）之间实现音频焦点仲裁；任意一端持续发声（`>320ms`）或来电通话时，自动将已配对 AirPods 抢占切换至当前活跃设备。
- **对端媒体自动暂停与切换窗口防外放保护**：当 AirPods 被对端抢占时，自动暂停原设备正在播放的音视频（Android 端结合 `MediaSessionManager.getActiveSessions`、`KEYCODE_MEDIA_PAUSE` 与 `AudioFocus` 抢占；Mac 端结合 `CoreAudio` 运行态守卫与 `MediaRemote` `kMRPause`）；蓝牙切换约 1.5s 窗口期内临时静音内置扬声器防止外放漏音。
- **实时低延迟手机屏幕镜像与远程触控工作台 (`oms mirror` / `/mirror`)**：新增 `FrameType.SCREEN_FRAME (0x07)` 16 字节二进制帧头与 `P1_STREAM_META` 优先级传输队列（`maxInFlight = 2` 背压丢帧控制），支持在 Mac 浏览器以最高 `12fps` 实时预览手机画面并直接用鼠标点击、拖拽滑动、输入文字、一键解锁与触发系统按键。

### APK

- `OneMoreStep-v1.5.2.apk`：Android 11+，versionCode 26，22,643,605 bytes（沿用 Android Debug 签名，从 v1.4.x 覆盖安装无需重新配对）。
- SHA-256：`c760477e78d0aa7aadd8aa15ba665db9cf63f7d2d20a52160b27723ac31efdb2`。

## 1.4.9 - 2026-10-28

### 远程 3×3 九宫格图案解锁与原生双卡电话拨打/挂断

- **远程点亮屏幕与 3×3 九宫格图案解锁 (`oms unlock` / `oms phone unlock`)**：支持通过 `PowerManager` 唤醒息屏手机、自动上滑呼出小米 HyperOS 锁屏密码层，并通过 `OmsAccessibilityService` 注入连续单笔轨迹 (`GestureDescription`) 划开 3×3 九宫格图案密码（`1~9` 点位序列，默认适配小米 `1220×2712` 分辨率 `175,1516,1045,2386`，支持 `--bounds` 自定义校准与 `--save` 本地持久化免输调用）。
- **原生双卡电话拨打与挂断 (`oms call` / `oms hangup`)**：若手机处于锁屏状态会自动先完成图案解锁，支持通过 `TelecomManager` 按卡槽或运营商名称（`--sim 1|2|电信|联通|移动`）发起呼叫、开启免提扬声器（`--speaker`）、仅打开拨号盘（`--dial-only`）以及远程挂断电话（`oms hangup`）。
- **多 URI Scheme 打开支持**：`AppLauncherHelper` 新增对 `tel:`、`smsto:`、`mailto:`、`market:` 等系统 URI Scheme 的直接跳转支持。

## 1.4.4 ~ 1.4.6 - 2026-10-27

### 蓝牙 BLE 配对回退、多子网 IP 记忆与连接稳定性优化

- **纯蓝牙 BLE 兜底增强 (`v1.4.6`)**：支持在局域网不可达时通过 BLE 通道完成 OTA 版本检查、在场状态刷新、压缩媒体流传输与配对回退（`pairing fallback`）。
- **BLE 扫描窗口释放与多子网历史 IP 保留 (`v1.4.5`)**：Android 端新增周期性 BLE 扫描窗口释放与 GATT 超时清理；保留多子网历史成功 IP 列表；Mac 端 `oms-bt-bridge` 新增 CoreBluetooth 发送队列与 Central 读回退通告。
- **跳过单 IP 阻塞探测与同子网优先 (`v1.4.4`)**：修复单配对 IP 在 WebSocket 连接前因 HTTP 预探测阻塞导致误报 `MAC_UNREACHABLE` 的问题，优先匹配与手机当前 Wi-Fi 同子网的 Mac 地址。

## 1.4.3 - 2026-10-27

### 大子网 Wi-Fi 锁屏保活与蓝牙/局域网协同修复

- **前台服务 `WifiLock` 低延迟保活**：在 `OmsCoreForegroundService` 中持有 `WifiManager.WifiLock(WIFI_MODE_FULL_LOW_LATENCY)`，解决小米 HyperOS 手机在锁屏熄屏 20～90 秒后 Wi-Fi 芯片进入 DTIM 深度休眠、不再响应局域网二层 ARP 导致 `closeCode=1006` 掉线的问题。
- **消除蓝牙扫描对局域网握手的抢占中断**：移除 `handleBleMacInfoBytes` 中强制重置 `isConnectingLan.set(false)` 的逻辑，且在局域网已连接时不再每 4.5 秒重复发起冗余 BLE 扫描，避免蓝牙回调打断正在进行的局域网握手（`4003 Stale connection`）。
- **BLE 流式分片写入队列**：在 Android 端 `OmsTransportClient` 实现基于 `onCharacteristicWrite` 回调的 GATT 分片写入队列（按协商 MTU 自动切片），解决 `secure.finish` 加密帧（~1.1KB）因超过 BLE 单包 512 字节上限而导致蓝牙握手最后一步失败重连死循环的问题。
- **双向主动心跳与路由自动回退**：手机端在局域网在线期间每 9 秒主动发送加密心跳帧并开启 10 秒 WebSocket Ping，保持企业级 `/23` 大子网无线 AP 的 ARP/转发表项常热；当 `findNonVpnWifiNetwork()` 自定义 SocketFactory 不可达但系统默认路由可达时自动同步回退至 WebSocket 客户端；Mac 端 `Bun.serve` 显式配置 `idleTimeout: 120` 与 `sendPings: true`。

### APK

- `OneMoreStep-v1.4.3.apk`：Android 11+，versionCode 17，22,485,137 bytes（沿用 Android Debug 签名，从 v1.4.x 覆盖安装无需重新配对）。
- SHA-256：`58c74476b5a3c4dd3404812ec6f6602e08f41217bda04da8956d14b9be7a0b9b`。

## 1.4.2 - 2026-10-27

### 手机端 UI 重构与诊断修复

- **首页布局重构**：修复展开「小米权限自检向导」或「v2 配对卡片」时把中间时间线挤压为 0、底部输入栏截断在屏幕外的问题；两个面板均增加高度上限、内部滚动与一键 `收起 ▲` 且互斥展开。
- **紧凑双层顶栏**：第一层展示图标、完整标题、版本胶囊、连接状态与 `🩺 诊断` 按钮，第二层为三等分工具栏（`🔄 检查更新` / `🔐 绑定Mac` / `⚡ 小米权限`），解决窄屏标题截断与 `v1.4.1` 诊断按钮 48dp 最小触控高度撑高顶栏的问题。
- **权限自检向导与标签栏打磨**：修复「刷新状态」文字折行，已开启权限显示柔和的 `已开启 ›`（自启动项显示 `去检查 ›`），未开启项高亮显示 `去开启`；底部标签栏与筛选栏统一收紧为 `28.dp` 胶囊高度。
- **GUI 安全护栏细节优化**：手势/点击动作完成后立即在 `delay(400)` 界面稳定等待前清除 `activeAction`，避免用户在动作完成后的 400ms 窗口内触碰屏幕误触发持久急停；允许 `home` / `lock_screen` 等安全退出动作在敏感窗口或未开悬浮窗时正常执行，且无目标节点的系统动作不再因前序动作更新观察修订号而在队列中被误拒。
- **蓝牙 BLE 与验证码提取优化**：修复 macOS CoreBluetooth 在订阅通知前过早触发 `central_connected` 的时序问题，Android 端连接 BLE 后主动协商 `requestMtu(512)`；修复机票/行程防诈提示（「请勿提供验证码」）结合航班号（如 `ZH9949`）误提取出 4 位数字验证码的问题。

### 升级方式

- **从 v1.4.0 / v1.4.1 升级无需重新配对。** 手机端点击顶栏 **🔄更新**（或访问局域网 `/install` 页面）直接覆盖安装 `OneMoreStep-v1.4.2.apk`，原配对、收件内容和离线队列完整保留。

### APK

- `OneMoreStep-v1.4.2.apk`：Android 11+，versionCode 16，22,482,269 bytes（沿用 Android Debug 签名）。
- SHA-256：`cc847169f51d4040cae483ce5f609dec8a9813872dc377aedcd5253a770e2f23`。

## 1.4.1 - 2026-10-27

### 离线原因与连接诊断

- 手机区分 Wi-Fi 不可用、Mac 探测不可达、连接失败、握手超时与安全校验失败；自动重试保留上一次原因，恢复后清除，备用链路错误不会覆盖 LAN 原因。
- 首页新增离线可用的 **连接诊断 → 复制诊断**，包含采集时的网络信息和最近连接事件。下次异常时可先复制诊断，再重连 Wi-Fi，保留排查现场。
- 手机和 Mac 各保留最近 200 条事件，记录 UTC 时间、固定事件码与本地尝试/连接编号。Mac 文件为 `~/.one_more_step/logs/connections.jsonl`，`oms status` 同时返回 `connectionDiagnostics`。
- 不记录密钥、配对链接、消息正文、屏幕或原始异常文本；复制报告包含用于排查的局域网 IPv4，不会自动外发。详情见 [连接诊断说明](docs/connection-diagnostics.md)。

### 升级方式

- **从 v1.4.0 升级无需重新配对。** 更新 Mac 代码并执行 `oms daemon restart`，手机覆盖安装附件 APK；保留原配对、收件内容和离线队列，无需卸载或清除数据。
- 从 v1.3.x 升级仍须完成 v2 配对迁移，见 [安全传输 v2](docs/secure-transport-v2.md)。
- 本次沿用 v2 协议，不改变认证或网络重试策略；此版本用于定位原因，**不宣称修复访客 Wi-Fi 的设备互通故障**。蓝牙与外网链路仍未完成专项真机验收。

### 验证结果

- 两个 TypeScript workspace 类型检查通过；Bun 52 项通过（405 次断言）；Android JVM 37 项通过，APK 构建成功。
- 真机验证：原配对自动连接、诊断入口与复制可用、关闭 Wi-Fi 后提示“未检测到 Wi-Fi”、恢复后诊断回到 `CONNECTED`，待发送队列为 0；Mac 有对应的断开与认证恢复时间线。
- 网络不可达和握手拒绝的其他分类由自动化测试支撑，不把单次真机验证外推到所有网络环境。见 [v1.4.1 验收记录](docs/ota-1.4.1.md)。

### APK

- `OneMoreStep-v1.4.1.apk`：Android 11+，versionCode 15，22,465,837 bytes。与本次已安装验收的是同一构建，沿用此前的 **Android Debug 签名**，尚未迁移到正式发布签名。
- SHA-256：`6a8bb5bd86cb78d3d0b6709723e571d0830cd575d6f6ca62c22fee53f613efb7`。
- 签名证书 SHA-256：`806caa5647ae4119df3ba6b32cd4131d94147e66d4a4992b80f7e7ac94a1e4b7`。

## 1.4.0 - 2026-10-27

### 升级须知

- **Mac 主机与 Android 必须一起升级，并重新配对一次。** 旧配对不兼容 v2；不支持回退明文。
- 手机覆盖安装本次 APK，无需卸载或清除数据；Mac 更新到此版本后重启 `oms daemon restart`，在 Mac 本地运行 `oms pair`，把完整 `oms://pair?v=2…` 链接安全地复制到手机配对输入框，点击“验证并绑定”。链接含一次性秘密，请勿公开分享。
- 已取消“6 位码通过 HTTP 获取配对密钥”。升级完成并配对后，原收件数据和离线队列保留。

### 新功能

- v2 配对使用 256 位一次性秘密；临时 P-256 ECDH、HKDF-SHA256 和双向 AES-256-GCM 保护 RPC、文件、ACK、心跳及设备遥测。
- 长期配对密钥由双方派生，不通过网络响应下发；每连接独立密钥和序号，拒绝篡改、重放、乱序和跨连接帧，并限制握手期限、大小和单密钥使用量。

### 修复（包含此前本地 OTA 的 v1.3.8 / v1.3.9）

- 双向文件传输校验长度、分块范围和 SHA-256，收到接收端保存确认后才报告成功；修复收件箱索引恢复、存储路径与本地看板访问边界。
- GUI 操作使用最新观察、绝对期限、人工确认及持久急停；修复正常点击“放行”被浮层窗口事件误判为页面变化的问题，真正切换页面仍拒绝执行。
- 重新配对撤销旧连接和等待中的操作，RPC / ACK 绑定来源；保留 v1.3.7 的验证码同步功能。

### 验证

- Bun 全量测试 49 项通过（391 次断言），两个 TypeScript workspace 类型检查通过；Android JVM 测试 32 项通过。
- TypeScript、JCA 和独立 Python 实现的公开密码测试向量一致；模拟测试覆盖加密 RPC、双向文件及失败拒绝场景。
- LAN 真机通过：新配对、v2 遥测、空文件与跨块文件下发、手机文字回传、Mac 重启后自动重连、GUI 正常放行与换页拒绝。详情见 [OTA 验收](docs/ota-1.4.0.md)。

### 安装包与边界

- 附件 `OneMoreStep-v1.4.0.apk`：Android 11+，versionCode 14。沿用此前版本的 **Android Debug 签名**，是本次已安装验收的同一构建；尚未迁移到正式发布签名。
- APK SHA-256：`402a4ce633e8d35b98000af4203b96574ef7d9c7e26a9fc8ef1b7da579de3e58`。
- 签名证书 SHA-256：`806caa5647ae4119df3ba6b32cd4131d94147e66d4a4992b80f7e7ac94a1e4b7`。
- 组合加密协议未经第三方审计；BLE / WAN 尚未真机验收，BLE MTU / 背压及持久化断点续传仍未完成。HTTP 局域网 APK 分发、端点元数据和本地密钥存储不在业务帧加密保护范围内，详见 [安全传输 v2](docs/secure-transport-v2.md)。

## 1.3.9 — GUI 确认窗口事件过滤（本地 OTA）

- 针对真机正常放行被过期观察校验拦截的问题，仅用实际应用窗口 ID/包名识别窗口，过滤无关窗口的内容/状态事件，保留目标窗口变化的拒绝行为。
- 避免把非应用浮层 root 追加到观察窗口；观察失效错误带事件类型、窗口 ID 等诊断字段，不包含页面正文。
- 补充窗口事件归属回归测试。Android versionCode 13，沿用相同签名；实际修复效果以真机复测为准。



## 1.3.8 — 可靠交付与设备会话边界（本地 OTA）

- 合并 v1.3.7 的验证码同步功能；Android versionCode 升至 12，使用原签名覆盖安装，不修改数据库版本。
- 移除旧固定签名/公开 room ID 认证；重新配对撤销旧会话，RPC 和 ACK 绑定来源。
- 两端流校验、限额与送达确认；收件箱完整附件预检、原子索引、派生视图恢复；面板同源与文件边界限制。
- GUI observationId、绝对期限、串行执行、失败拒绝的确认流程和持久急停；审批选项与等待期限一致。
- 升级须同步主机/手机，历史无密钥设备须重新配对；不含端到端加密、BLE 背压和持久化续传。
- 测试与真机验收边界见 [实现状态](docs/implementation-status.md)。以下历史版本记录不代表开发分支所有承诺已验证。

All notable changes to **青简·咫尺 (OneMoreStep)** are documented in this file.

## [1.3.7] - 2026-10-26

### Added
- **手机验证码（短信 / 微信 / 飞书 / 邮箱）锁屏实时同步 Mac 桌面通知与剪贴板 (`#sms_otp`)**：
  - **Android 四通道穿透捕获引擎 (`com.oms.client.otp`)**：融合 `OmsNotificationListenerService`（全渠道通知栏监听，支持 `MessagingStyle`）、`OmsSmsBroadcastReceiver`（直接解析底层 `SMS_RECEIVED` PDU 穿透锁屏脱敏）、`SmsContentObserver`（监听 `content://sms` 写入）与 `OmsAccessibilityService`（无障碍通知事件兜底），内置 25 秒幂等去重；
  - **高精度抗干扰验证码提取 (`OtpExtractor` & `extractOtpFromText`)**：内置语义关键词门控与干扰数字掩码（自动过滤尾号、账号、金额、时间期限、手机号与品牌数字名），精准提取 4~8 位验证码与短信签名（如 `【阿里云】`、`【招商银行】`）；
  - **Mac 剪贴板与通知分离体验**：收到验证码时，Mac 剪贴板**仅写入提取出的纯验证码数字**（网页输入框直接 `⌘V` 粘贴），Mac 桌面通知高亮展示 `🔑 【签名】验证码: 839201` 与完整短信原文；
  - **小米专属权限自检与一键测试**：在 `XiaomiAdaptationHelper` 与手机端「小米权限」向导中新增「通知使用权」与「短信及小米通知类短信 (`OpCode 10018`)」自检跳转，并提供 `[⚡ 测试验证码]` 一键联调按钮；
  - **CLI 与 Web 看板升级**：新增 `oms otp [--copy]` 命令，`/inbox` 看板新增 `🔑 验证码` 筛选页与一键复制纯验证码按钮。

---

## [1.3.6] - 2026-10-26

### Fixed
- **无缝版本迁移与重连优化**：修复 `v1.3.4 -> v1.3.6` 升级后的鉴权密钥持久化（`savePairedMac` 保留已有 `pairSecretHex` 与公钥），移除后台高频 508-IP ARP 子网扫描风暴，仅在用户手动触发重连或首次绑定时执行子网扫描。

---

## [1.3.5] - 2026-10-26

### Security (一机一码与局域网严格 1 对 1 安全加固)
- **Mac 端 `/inbox` 与控制 API 严格锁死本机回环 (`127.0.0.1` / `::1`)**：
  - `/inbox`、`/dashboard`、`/api/inbox`、`/api/inbox/file` 与 `/api/inbox/action` 增加客户端来源 IP 校验（`PhoneSessionManager.isLoopbackAddress`），彻底阻断同一局域网其他电脑通过 HTTP 访问收件箱或借道 Mac 操控手机（返回 `403 Forbidden`）。
- **双向 HMAC-SHA256 / Ed25519 挑战-应答认证与指令拦截**：
  - 彻底移除开发期 `"trusted_xiaomi_sig"` 调试签名放行逻辑；
  - 首次配对 (`pair.request`) 成功后协商生成 256-bit 专属配对密钥 (`pairSecretHex`) 并立即销毁一次性配对凭证；
  - 每次重连执行双向挑战签名校验（手机验 Mac `macAuthHmacHex`，Mac 验手机 `phoneSignatureBase64Url`），手机端在完成对 Mac 的双向验签前拒绝执行任何传入的 RPC 控制指令或接收文件流。
- **手机端唯一 `macId` 严格锁定（杜绝局域网自动改绑）**：
  - 手机端 UDP 广播监听、局域网子网扫描 (`discoverMacOnLan`)、BLE 近场发现与 OTA 更新检查均严格核对 `macId == paired.macId`，彻底移除 `discoverMacOnLan(port, null)` 任意匹配回退逻辑，即使已绑定的 Mac 休眠或离线，手机也绝不会自动连上同一局域网的其他电脑。
- **PIN 码保护的配对解析与防暴力破解 (`/pair/resolve`)**：
  - `/install` 网页不再向局域网明文暴露 `ephemeralToken` 与 `oms://pair` 链接，需输入 Mac 终端显示的 6 位配对码方可解锁（或直接在手机 App 输入 6 位配对码完成绑定），连续输错 5 次自动销毁配对会话。

---

## [1.3.4] - 2026-10-26

### Added
- **第 13 个 MCP Tool `phone_device_control` 与 `oms phone` 远程操控套件**：
  - 支持从 Mac AI 或 Web 看板远程响铃寻找手机（`find_phone`，突破静音走闹钟声道播报 + 强震动）；
  - 支持远程 TTS 语音播报（`tts_speak` / `stop_tts`）、四声道音量调节与静音（`set_volume`）、震动反馈（`vibrate`）；
  - 支持一键远程抓取手机屏幕截图并自动归档至 Mac 收件箱（`take_screenshot` / `oms phone screenshot`）；
  - 支持在手机端直接打开网址（`open_url`）、按别名启动常用 App（`launch_app`：`wechat`、`lark`、`dingtalk`、`camera`、`settings` 等）及执行系统级导航与锁屏（`home`、`back`、`recents`、`lock_screen`、`notifications`）。
- **手机全维实时遥测 (`PhoneDeviceStatus`)**：
  - 新增电池温度（`temperatureC`）、电压（`voltageMv`）、充电接口类型、省电模式状态；
  - 新增屏幕亮灭（`screenInteractive`）、亮度、当前前台应用中文名称（`foregroundAppLabel`）与包名；
  - 新增 Wi-Fi SSID、信号强度（`wifiRssiDbm`）、协商速率、响铃模式、各声道音量百分比、可用存储与内存（RAM）余量。
- **手机端原生语音录制与 Mac 离线 SenseVoice 自动转写**：
  - 手机端底部输入栏新增 `🎙️语音` 实时录制（`VoiceRecorderHelper`，支持 `.m4a` 音频流与振幅动效）；
  - Mac 守护进程 `omsd` 收到音频附件后自动调用本地 `local-asr`（SenseVoiceSmall 离线模型）转写为文字并回填 `voiceTranscript`，同时支持 `oms inbox transcribe <itemId>` 手动重跑。
- **局域网智能 OTA 升级 (`/apk/version` & `oms ota`)**：
  - 支持版本号（`versionCode` / `versionName`）与安装包 SHA-256 校验对比，手机端与 Mac 端均可一键触发无线升级。
- **工程化与自动化脚手架**：
  - 新增 `bun run setup`（`scripts/setup.ts`），一键编译 macOS 原生 `oms-bt-bridge`、挂载全局 `oms` 命令并安装全局 Agent Skill。

### Fixed & Changed
- 移除 Android 客户端局域网自动发现中的本机测试网段硬编码，统一基于已配对 IP、UDP 广播探测、ARP 邻居单播信标与手机真实 Wi-Fi 子网（`/24` & `/23`）分批扫描。
- 修复工作区 TypeScript `tsc --noEmit` 类型检查配置，补充 GitHub Actions CI 自动验证流水线。

---

## [1.2.0] - 2026-10-23

### Added
- **Mac 实时收件箱与双向协同 Web 看板 (`/inbox`)**：
  - 内置深色主题 Web 控制台（`oms web`），支持 2 秒无感增量同步、大图 Lightbox 预览、一键唤起 macOS `Preview.app`、一键在 Finder 中定位附件，以及从网页反向推送 Markdown/剪贴板至小米手机。
- **Mac 即时消费自动联动 (`oms prefs`)**：
  - 支持收到手机文字或语音转写时自动写入 Mac 系统剪贴板（直接 `⌘V` 粘贴），收到手机截图/照片时自动弹窗预览。
- **收件箱检索与生命周期管理**：
  - 支持按关键词搜索（`oms inbox --q`）、单条详情查看（`show`）、已处理标记（`ack`）与批量清理（`clear-processed`）。

---

## [1.0.0] - 2026-10-21

### Added
- **跨端双向协议与安全配对 (`@oms/shared-protocol`)**：
  - 8 字节优先级多路复用二进制帧协议（`P0` 控制信令抢占 `P2` 大文件分块流，消除队头阻塞）、Ed25519 双向挑战签名鉴权、UUIDv7 幂等去重与 `/install` 零数据线无线安装配对台。
- **三级自适应传输与蓝牙空间在场引擎 (`@oms/mac-host`)**：
  - `Tier 1 (LAN_WS)` 物理网卡直绑高速通道、`Tier 2 (BT_RFCOMM)` macOS Swift `CoreBluetooth` 近场桥接、`Tier 3 (WAN)` 跨网通道无缝热切换；
  - 基于蓝牙 RSSI EMA 与滞回滤波的 `AT_DESK` / `AWAY` 在场感知智能通知路由。
- **小米 Android 原生客户端 (`com.oms.client`)**：
  - 免 IM 极速投递套件：三指截屏 3.5 秒悬浮胶囊（`ScreenshotPillController`）、系统分享 3 大置顶 Direct Share 入口（`#pkb_vault` / `#default_inbox` / `#ui_bug`）、控制中心快捷磁贴（1×1 透明焦点窗口突破 Android 10+ 剪贴板限制 + 语音闪念）；
  - 确定性系统能力执行器：小米原生时钟静默闹钟（`AlarmExecutor`）、系统日历防冲突读写（`CalendarExecutor`）、受权目录白名单按需拉取（`ScopedStorageExecutor`）、L1~L3 分级通知与闹钟声道强制 TTS（`NotificationExecutor`）、阻塞式远程交互审批卡片与通勤 TTS 随身听；
  - 无障碍 GUI 屏幕自动化引擎（`OmsAccessibilityService`）：紧凑语义树提取（`SemanticTreeExtractor`）、Android 11+ 静默截屏与 Set-of-Mark (`#ID`) 视觉合成、以及四大硬件级安全护栏（顶部物理急停胶囊、触屏即让路、`FLAG_SECURE` 金融黑名单、敏感动作停顿确认）；
  - 小米 HyperOS / MIUI 专属权限（AppOps `10021` 后台弹出界面、`10008` 锁屏显示）反射自检与一键跳转向导。
