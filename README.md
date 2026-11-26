# Local AI Studio

本地中文短剧制作工作台。支持项目管理、故事大纲与分集剧本、图片资产与版本审核、分镜编排、H3 镜头视频、角色配音、剪辑与字幕、480p 预览、1080p 成片导出和任务中心。默认可使用标记为 Mock 的生成引擎进行体验。

## 启动工作台

在仓库根目录使用 JDK 21、Node.js 24 和 npm。Windows PowerShell 使用 `.\gradlew.bat` 运行下列 Gradle 任务。

首次构建前端。

```sh
npm --prefix frontend ci
npm --prefix frontend run build
```

在第一个终端启动 Mock 引擎。

```sh
./gradlew :backend:mock-comfy:run
```

在第二个终端启动工作台。

```sh
./gradlew :backend:app:run
```

打开 [本地工作台](http://127.0.0.1:8080)。新建项目，进入「资产生成」，新建一个角色或场景资产，填写画面描述并提交图片任务。图片入库后，点击版本可查看原图和 SHA-256。「任务中心」展示输入快照、尝试记录和结果。归档的项目可以恢复。

Mock 下可使用 `[mock:fail-once]` 作为画面描述，验证第一次失败后手动重试。`[mock:fail]` 可复现持续失败。两个独立服务允许在生成期间单独重启工作台，验证按原始 prompt ID 恢复。Mock 的队列和历史保存在 Mock 进程内，Studio 的项目、任务及图片保存在磁盘。

开发前端时运行 `npm --prefix frontend run dev`，Vite 将 `/api` 代理到工作台的 8080 端口。构建后的 SPA 由 Ktor 从 `frontend/dist` 提供。

## 从剧本到分镜

1. 在「编剧工作台」审核本集最新剧本。在「资产生成」选择该集，点击「从剧本提取资产」。
2. 选中角色、地点或道具，提交图片任务。可在「对比候选」并排查看原图，点击「审核并选用」确定版本。
3. 打开「分镜工作台」，选择剧集、参考版本和每场镜头数，点击「生成分镜计划」。
4. 在左栏选择或移动镜头，中栏查看场次、预览及绑定素材，右栏修改景别、机位、动作或时长。保存后可以重做单镜或生成预览图。「分镜版本」可回看原顺序和旧资产。完成后点击「审核当前分镜」。

[M2 验收记录与真机样片](docs/M2进度.md)

## 渲染镜头视频

在运行工作台的机器上准备可从 PATH 调用的 `ffprobe`。打开已审核的分镜，进入「视频渲染」，选择剧集和模板。

- **首帧生成**使用本镜头已选的预览图。在候选图上点击「审核并设为首帧」，再点击「渲染此镜头」。
- **首尾帧生成**分别选用两端的候选图。工作台按模板尺寸等比居中裁切，源图可继续查阅。
- **参考图生成**从该镜头已绑定的已审核资产版本中选择一张。此模式可跳过首帧制作。

勾选多个输入已就绪的镜头后点击「批量渲染」。每个镜头都有独立任务，可在任务中心查看节点进度、取消或重试失败的任务。生成后在「视频版本」播放并审核，或回看历史分镜对应的版本。当前三款真机模板输出 480 × 864、24 fps 的短视频，实际时长取决于模型允许的帧数。[M3 开发与验收](docs/M3进度.md)

## 角色配音与剪辑成片

1. 在「角色配音」选择剧集和镜头，给角色绑定服务提供的音色。逐句生成、试听并选用录音。修改语速可以重录。页面显示已选对白的总时长和超出镜头计划的时长。
2. 在「剪辑成片」添加已审核的视频，调整顺序、源视频入点、片段时长及原声音量。加入已选对白，设置开始时间、音量和字幕，也可加入单独定时的字幕。
3. 保存时间线，选择 480p 预览或 1080p 成片并提交。导出完成后可在页面内播放或下载带可切换中文字幕的 H.264 / AAC MP4。任务中心和版本历史可在刷新或重启后继续查询。

真实普通话配音可使用独立的本机 CPU 服务。真实模式下 Studio 默认连接回环 8000，Mock 模式下默认使用标明为模拟的测试音。导出要求工作台的 PATH 中有 ffprobe 和支持 libx264、aac、mov_text 的 ffmpeg。[语音服务、模型与启动命令](services/tts/README.md) · [M4 验收与样片](docs/M4进度.md)

## 三集试制

《第三盏灯》使用同一组角色音色与视觉设定，按三集约一分钟的竖屏短剧试制。可查阅每集工作台入口、镜头选用记录，并复核排队时间、实际执行耗时与资源观测。[M5 试制记录](docs/M5进度.md) · [性能核验工具](tools/m5/README.md)

## 编剧工作台与本地 Qwen

打开项目，在左侧选择「编剧工作台」。填写创作意图，生成故事大纲，编辑后点击「保存新版本」和「审核通过」。进入「分集规划」生成剧集目录，再按集生成场次与对白。每次保存或生成都会创建修订。任务中心可打开相应稿件，历史版本保留当时的角色、地点和审核决定。大纲发生变化后，更新分集规划，在旧剧本中选择「按当前规划修订」，即可为受影响的场次和对白重新选择角色或地点。

Windows 目标机已在 ComfyUI 的 Llama-cpp Model Loader 选项中报告 `Qwen3.5-27B-UD-Q4_K_XL.gguf`。该节点可配合 Llama-cpp Instruct 在 ComfyUI 工作流内推理。M1 的引擎入口是独立 llama-server 的 HTTP 接口，可直接读取同一份 GGUF。在适配 RTX 5090 的 Windows CUDA 发行目录打开 PowerShell，输入模型的实际完整路径。[llama.cpp 官方发行](https://github.com/ggml-org/llama.cpp/releases) · [Qwen GGUF 模型说明](https://huggingface.co/unsloth/Qwen3.5-27B-GGUF)

```powershell
$model = (Read-Host 'Qwen GGUF 完整路径').Trim('"')
.\llama-server.exe -m $model --alias studio-qwen --host 127.0.0.1 --port 8081 -c 8192 -np 1 --slots --sleep-idle-seconds 15
```

在同一台 Windows 机器另开 PowerShell，从本项目根目录启动工作台。前端首次使用时先按上文构建。

```powershell
Invoke-RestMethod 'http://127.0.0.1:8081/v1/models'
$env:STUDIO_ENGINE = 'comfy'
$env:STUDIO_LLM_MODE = 'llama'
$env:STUDIO_LLM_URL = 'http://127.0.0.1:8081'
$env:STUDIO_LLM_MODEL = 'studio-qwen'
.\gradlew.bat :backend:app:run
```

该示例使用一个推理槽位、8192 上下文。已跑通的启动命令可沿用原来的显卡与上下文参数，追加 `--sleep-idle-seconds 15` 并重新启动 llama-server 即可开启空闲休眠。下面的 5090 验收使用一个槽位、16384 上下文。启用服务密钥时，通过 `STUDIO_LLM_API_KEY` 提供相同凭据。[本次 llama.cpp 版本的休眠说明](https://github.com/ggml-org/llama.cpp/blob/b12676/tools/server/README.md#sleeping-on-idle)

沿用 M0 的分机部署时，在 Windows 启动命令中将 `--host 127.0.0.1` 改为 `--host 192.168.31.154`，使 Mac 可以访问模型服务。如果选择 8081 端口，可以从 Mac 的仓库根目录验证连接并启动工作台。

```sh
curl -fsS http://192.168.31.154:8081/v1/models
STUDIO_ENGINE=comfy \
STUDIO_ACCEPTANCE_COMFY_ORIGIN=http://192.168.31.154:8188 \
STUDIO_LLM_MODE=llama \
STUDIO_LLM_URL=http://192.168.31.154:8081 \
STUDIO_LLM_MODEL=studio-qwen \
STUDIO_LLM_LOAD_VRAM_MIB=19456 \
STUDIO_LLM_LOAD_RAM_MIB=20480 \
./gradlew :backend:app:run
```

这两个预算是该 5090 与 27B GGUF 在 16384 上下文下的工作台配置，分别要求唤醒模型前有 19 GiB 可用显存和 20 GiB 可用系统内存。数值包括余量。单次实测 llama.cpp 在 CUDA0 为权重、上下文和计算缓冲区合计分配了约 17.04 GiB。Z-Image-Turbo 模板的独立预算为 21,379 MiB。[模型内存实测](storage/m1-5090-updated-20261124/loaded-model.json) · [图片模板](workflows/z-image-turbo/1.0.0/manifest.json)

两种任务都从工作台提交。切到编剧时，工作台等待 ComfyUI 队列清空，调用 `POST /free` 释放图像模型，等内存满足冷加载预算，再发补全请求唤醒 Qwen。切到图片时，工作台核对文字任务回执与槽位状态，显存不足就等待 Qwen 休眠，再提交已注册的图片工作流。「任务中心」显示等候原因。休眠期间 8081 仍接受健康检查。 RTX 5090 已完成出图、唤醒编剧、再次休眠出图的双向验收。[实测时间与显存记录](docs/M1进度.md#双服务在线复测)

可在 Windows 的第二个 PowerShell 窗口中确认休眠。下面以启动参数 15 秒为例。若使用其他阈值，请在最后一个推理请求结束并超过该阈值后查询。返回 `True` 表示服务在线且模型已休眠。空闲期间只需查询 `/props`，持续查询 `/slots` 会干扰空闲计时。

```powershell
Start-Sleep -Seconds 20
(Invoke-RestMethod 'http://192.168.31.154:8081/props').is_sleeping
```

## 更新文字提示词

各文字任务的启用版本集中在 [`prompts/versions.json`](prompts/versions.json)。切换已有版本时，修改对应任务的配置值即可。发布新版本时，在 `prompts/<任务目录>/` 新增按版本号命名的 Markdown 文件，再将配置值切到该版本。

保存后沿用原启动配置重新运行 `./gradlew :backend:app:run`，安装包部署则重新构建并更新后端。新提交的任务使用选定版本，已有任务从提交时的输入快照恢复或重试。

## 接入本机 ComfyUI

仓库已包含在 RTX 5090 上通过出图验收的 Z-Image-Turbo 1.0.0。已有相同节点和模型的 ComfyUI 启动后，在同机用真实模式启动 Studio，进入「资产生成」即可选择模板。[Z-Image-Turbo 接入说明](workflows/z-image-turbo/README.md)

```sh
STUDIO_ENGINE=comfy ./gradlew :backend:app:run
```

在 Windows PowerShell 中运行以下命令。

```powershell
$env:STUDIO_ENGINE = "comfy"
.\gradlew.bat :backend:app:run
```

工作流页可重新检测节点、输入和模型，并查看禁用原因。编辑绑定会发布新的模板版本。原版本继续供历史任务查询。ComfyUI 交互依据其官方 REST 和 WebSocket 契约实现。[ComfyUI 服务端接口](https://docs.comfy.org/development/comfyui-server/comms_routes)

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `STUDIO_ENGINE` | `mock` | 选择 `mock` 或 `comfy` |
| `STUDIO_PORT` | `8080` | Ktor 回环端口 |
| `STUDIO_STORAGE` | `storage` | 数据库、图片、临时文件 |
| `STUDIO_WORKFLOWS` | 真实模式为 `workflows`，Mock 为 `storage/mock-workflows` | 本机可信模板目录 |
| `COMFY_URL` | 真实模式为 `http://127.0.0.1:8188`，Mock 为 `http://127.0.0.1:18188` | 引擎回环地址 |
| `STUDIO_ACCEPTANCE_COMFY_ORIGIN` | 未设置 | 分机验收时指定一个私网 IPv4 HTTP 地址及端口。若同时设置 COMFY_URL，两者须匹配。Studio 继续监听本机 |
| `MOCK_PORT` | `18188` | 独立 Mock HTTP/WS 服务的监听端口 |
| `COMFY_SCOPED_INTERRUPT` | 真实模式关闭 | 默认优先使用按任务 ID 取消的接口。旧版本缺少该接口时，工作台等待引擎结束后丢弃结果。已验证其 `/interrupt` 支持按 prompt ID 中断的安装可设为 `true` |
| `STUDIO_LLM_MODE` | 配置 LLM 地址时为 llama。否则图像 Mock 模式为 mock，真实图像模式为 disabled | 编剧引擎支持 llama、mock、disabled |
| `STUDIO_LLM_URL`、`STUDIO_LLM_MODEL` | llama 模式回环 8081，单模型自动发现 | 指定独立服务的来源地址及模型 ID 或别名 |
| `STUDIO_LLM_LOAD_VRAM_MIB`、`STUDIO_LLM_LOAD_RAM_MIB` | 0 | 可填写目标机实测的冷加载所需可用显存和内存，单位 MiB。共享真实 GPU 唤醒模型前按预算检查 |

真实引擎默认按 ID 撤销排队项。已经执行的任务进入取消中，持续持有租约，直至引擎停止，随后丢弃结果。开启定向中断后，调度器也会请求停止正在执行的指定 prompt。

## 持久化与恢复

SQLite 位于 `storage/studio.db`。每次状态转换与事件在同一事务提交，数据库的唯一索引限制活跃 GPU 租约。工作台还使用进程锁，防止同一用户启动多个真实 GPU 调度器。图片经过解码、尺寸检查、SHA-256 和原子重命名后才生成待审核的资产版本。

提交回执不确定时，调度器按 attempt ID 核对 ComfyUI 队列和历史，没有可靠结论则保持 `UNKNOWN` 并保留租约。操作者核实远端记录后，可在任务详情选择「人工核对」。后端再次检查远端，确认无匹配记录后将该次尝试记为失败。重试创建新的 attempt，保留原业务任务 ID 和输入快照。

备份时停止 Studio 写入，再完整复制 `STUDIO_STORAGE` 和 `STUDIO_WORKFLOWS`。恢复时使用同一份数据库、素材及模板，并保留原引擎地址以对账在途任务。

## 构建与验收

```sh
./gradlew test build
npm --prefix frontend ci
npm --prefix frontend run generate
npm --prefix frontend run build
```

浏览器测试会启动临时 Mock、工作台和数据库，并在结束时清理测试目录。在 `frontend` 目录运行以下命令。

```sh
npx playwright install chromium
npm test
```

已安装桌面 Chrome 时，也可运行 `STUDIO_BROWSER_CHANNEL=chrome npm test`。测试覆盖任务失败、取消、恢复、GPU 互斥、重复请求、HTTP/SSE、媒体 Range、模板版本和浏览器刷新。

| 路径 | 职责 |
| --- | --- |
| `backend/domain`、`backend/core` | 状态转换、依赖规则和通用类型 |
| `backend/application`、`backend/engine-api` | 应用契约、模板校验与引擎接口 |
| `backend/infrastructure` | Exposed 事务、SQLite 迁移、模板及媒体文件 |
| `backend/scheduler`、`backend/engine-comfy` | GPU 租约、对账、结果确认与 ComfyUI 客户端 |
| `backend/engine-llm`、`prompts/` | 同步补全的持久回执、编剧适配器与版本化提示词 |
| `backend/app`、`backend/mock-comfy` | Ktor 装配与独立的协议测试服务 |
| `frontend/src` | Vue、Pinia、Naive UI 工作台和类型化 API 客户端 |

前端从 OpenAPI 契约生成 TypeScript 类型，通过 openapi-fetch 调用端点。依赖版本由 Gradle Wrapper、版本目录、模块锁文件及 npm 锁文件固定。[OpenAPI 契约](openapi.json) · [M0 RTX 5090 验收](docs/M0进度.md) · [M1 编剧验收](docs/M1进度.md)
