# 技术架构 v0.1

## 运行拓扑
Vue 3 / TypeScript / Vite / Pinia / Naive UI → Ktor REST + SSE → 应用服务 → 持久化任务调度 → 引擎适配器（ComfyUI REST/WS、llama.cpp OpenAI兼容API、TTS FastAPI、FFmpeg进程）。SQLite WAL与本地文件系统为权威数据源。正式环境Ktor托管SPA；开发Vite代理/api。默认绑定127.0.0.1。端口建议8080/8188/8081/8000。

## 工程结构
```
frontend/src/{api,stores,router,features,components}
backend/{app,core,domain,application,infrastructure,engine-api,engine-comfy,engine-llm,engine-tts,engine-media,scheduler}
workflows/<id>/<version>/{workflow.json,manifest.json}
prompts/versions.json
prompts/<role>/<version>.md
storage/{projects,staging,comfy-input,comfy-output}
```
依赖方向：domain不依赖框架；application依赖domain和engine-api；infrastructure实现repository；各engine实现接口；app负责装配。初期允许feature包而非每个feature独立Gradle模块。前端按业务feature组织，服务端状态由API获取，Pinia不作为持久化来源。

## 工作流注册
真实ComfyUI中测试并导出API JSON，不使用UI布局JSON。manifest定义id、version、capabilities、参数类型/范围、node/input映射、输出节点、模型依赖、资源估算。启动时通过/object_info验证节点与参数，缺失则禁用模板。模型名称和H3模式以本机验证为准，不假定此前讨论的文件名必然存在。每次任务保存模板版本、参数、输入资产版本、seed及引擎版本。禁止LLM生成任意节点图直接执行。

## 执行链路
1. API事务写入任务及依赖，返回202。
2. 调度器选择依赖成功且审核通过的READY任务；获取GPU租约。
3. 解析固定资产版本，复制到受控input目录，注入模板。
4. 建立ComfyUI WS，POST /prompt，持久化prompt_id；监听事件并用/history确认结果。
5. 校验文件、FFprobe或图像解码，原子移动到资产库，事务写入结果及事件。
6. 释放GPU租约，唤醒依赖任务。失败记录错误，不覆盖已审核产物。

## 单GPU策略
全局并发重型任务=1；同类模型批处理但保留交互任务优先级与防饥饿。租约覆盖提交、执行、结果确认及必要卸载。切换引擎前检查实际队列和NVML显存、系统可用内存；ComfyUI /free仅释放自身模型，必要时停止LLM/TTS进程。48GB RAM不允许无限CPU offload。OOM默认不无限重试，提示降低帧数、分辨率或参考数。取消请求后等待引擎真正停止才释放租约。外部手工ComfyUI任务需检测或禁止并发。默认480P、3–5秒、batch=1，实际合法帧数由模板能力决定。

## 恢复与一致性
任务状态与事件同事务写入；启动检查RUNNING/SUBMITTED及ComfyUI queue/history。提交超时且未获得prompt_id时标记UNKNOWN，人工或引擎记录核对，不盲目重试。每次重试新attempt，业务任务ID不变。SSE断线用事件游标重放或快照恢复。数据库事务不得跨GPU执行。文件先写staging再原子重命名；启动清理孤立临时文件，已入库素材不可直接覆盖。

## 安全与可观测性
路径规范化并限制在存储根目录；拒绝路径穿越、任意命令、任意节点与任意远程URL。FFmpeg使用参数数组而非shell拼接。限制上传大小与类型。记录taskId、attemptId、promptId、耗时、峰值显存/RAM、错误类别；日志不记录敏感凭据。局域网开放前增加认证、CSRF与Origin校验。

## 测试
单元：模板绑定、DAG环检测、状态机、路径校验、失效传播。集成：SQLite迁移、模拟ComfyUI REST/WS、SSE恢复、提交不确定状态。真实GPU冒烟：Z-Image一张图、H3一段短视频、TTS一句话、FFmpeg合成；记录实际峰值和耗时。模型及依赖版本在M0锁定，不凭空指定最新版本。

## M0 运行细节

Mock 为独立的回环 REST/WS 测试服务，使用专用模板目录与带标记的 PNG。真实模式从可信的本地导出目录注册模板，编辑器仅将原有授权标量绑定发布为新版本。HTTP 生成请求通过版本及哈希解析到持久化图。单用户进程锁与数据库租约共同覆盖调度；空闲时先检查外部 ComfyUI 队列。每分钟的等待增加有效优先级，以避免持续到达的高优先级任务挤占老任务。

真实模式记录引擎 `system_stats` 报告的显存和主机内存，任务详情展示采样峰值。在 Windows 上，显存读数可能遗漏其他进程的 CUDA 分配。跨引擎调度的整卡采样方法见下文。节点进度来自 WS，最终状态与图片来自 queue/history 及声明的输出节点。M0 通过受控的 /view 读取图片，文件进入本平台的 staging 和项目目录。

目标机与开发机分开验收时，启动进程可通过 `STUDIO_ACCEPTANCE_COMFY_ORIGIN` 显式指定一个私网 IPv4 HTTP 地址及端口，仅适用于真实引擎。该地址固定用于 REST、WS 和恢复对账，禁用重定向；浏览器不能设置引擎地址。Studio 继续监听回环地址。验收期间由一个 Studio 安装调度该 GPU，进程锁位于控制机，SQLite 租约跨该实例的重启持续有效。常规同机部署使用回环地址。

## M1 文字生成

编剧服务通过 engine-api 的 TextEngine 调用可替换的 engine-llm 适配器。真实模式使用 llama.cpp 的模型列表、服务属性、槽位状态和结构化聊天补全接口。快照保存模型标识、服务版本、具体上游修订、提示词、输出 schema 和哈希。模型模板显式支持 `enable_thinking` 时，快照记录关闭思考的参数，补全请求沿用该值。单场剧本的 schema 固定为所选规划的该集时长。编辑器与模型结果均经过结构和引用校验。模型返回值只作为文字及结构化数据处理。

应用层通过 `prompts/versions.json` 为大纲、分集规划、剧本、资产提取、拆镜和单镜重做选择 Markdown 模板。配置中的同一版本号用于定位文件及生成任务快照。恢复和重试从原快照读取提示词与 schema。

图片和文字共用 render_tasks、单一调度循环及数据库唯一 GPU 租约。共享真实设备时，调度检查 ComfyUI 队列和 llama.cpp 槽位，在取得文字租约后请求 ComfyUI 释放模型。可配置模型冷加载所需的显存和系统内存。切回图片时，工作台从 `/props` 读取休眠状态。模型醒着且整卡可用显存不足或未知时，图片继续等待，调度器避免用 `/slots` 打断空闲计时。同机部署优先读取 `nvidia-smi`，分机部署可以使用已配置的 ComfyUI WebSocket 上单卡 Crystools 广播，样本五秒后过期。只有编剧和图像并用时才需要这些跨进程的切换检查。

llama.cpp 的聊天补全是同步 HTTP。每个真实 attempt 使用 Studio 自带的短生命周期 JVM 子进程维持一个请求。它没有监听端口，持有该 attempt 的文件锁，发送前独占创建标记，将回执原子写入受控目录。Studio 进程重启后接管同一个 attempt。有可靠回执时恢复校验与入库，回执不明时进入 UNKNOWN 并保留租约。取消中的请求待实际结束后丢弃结果，人工核对要求没有在运行的执行进程且模型槽位空闲。[llama.cpp 服务端协议](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)

## M2 资产与分镜

`Storyboards` 提供剧集分镜、镜头修订、场次人物状态和审核操作，SQLite 适配器在同一事务内检查项目归属、上游剧本及期望修订号。每次计划或编辑生成一份有序的分镜版本，重排复用镜头修订，单镜头重做只替换所选修订。查看历史时按照当时绑定的 UUID 读取媒体。

提取、拆镜与单镜重做使用 M1 的文字执行器、固定提示词和结构化输出校验。每场角色、地点和时长来自固定剧本，资产版本由工作台选定。分镜预览使用 M0 图像执行器，取已注册文生图模板的 `prompt` 绑定，组合镜头描述及所选资产版本的文字特征。真实 Z-Image-Turbo 1.0.0 提供文生图能力，参考图片在编辑器中可见。图像条件或视频生成需要支持相应能力的真实模板。

四类任务复用持久化队列、单 GPU 租约、取消、恢复与 SSE。模型执行期间若剧本、分镜或使用的场次状态发生变化，结果入库事务会回滚并记录冲突。已进入队列的预览图始终写回提交时的镜头修订。

## M3 视频渲染

镜头视频与文字、图片共用 SQLite 队列和 GPU 调度器。首帧、首尾帧和单参考图模板分别绑定已审核的预览文件或镜头资产版本。服务按源文件哈希读取图片，按模板尺寸等比居中裁切，将派生 PNG 以内容哈希命名上传。运行视频前等待同卡 llama.cpp 休眠。工作台仅绑定模板明示的参数和图像槽位。

GPU 租约覆盖准备输入、提交、执行和结果确认。视频尝试持久化提交标记，上传中重启可重新准备，进入提交边界后按 attempt 和 prompt ID 对账。视频按声明的输出节点读取并经 FFprobe 解码计帧，随审核版本原子入库。分镜、角色状态和已选图片改变会影响下游有效性。模板、媒体与回执见 [M3 开发和验收](docs/M3进度.md)。

## M4 配音与 FFmpeg

应用层通过 TtsEngine 和 ExportEngine 完成配音及成片，Ktor 装配实际引擎或 Mock。调度器将两种任务登记在原持久化队列，执行时共用单一租约。当前实际配音服务用 CPU 推理，FFmpeg 用 CPU 编码。声明使用 GPU 的 TTS 适配器还需提供释放模型能力，并在执行前等待 ComfyUI 和 llama.cpp 让出设备。

独立 FastAPI 服务以尝试 UUID 为幂等键，使用 SQLite 和原子发布的 WAV 保存作业。Studio 按原 UUID 获取状态、结果或请求取消。媒体适配器为每个导出尝试创建独立子进程，受文件锁协调。编码参数从冻结的素材快照构造，传递给 ProcessBuilder。工作台重启后可重新读取同一尝试的锁和完成文件。视频按项目画幅等比缩放并补边，原声与对白混成 48 kHz 立体声，字幕按片段偏移写入 MP4 文本轨道。输出经过媒体探测和哈希核对再入库。[M4 验收记录](docs/M4进度.md)
