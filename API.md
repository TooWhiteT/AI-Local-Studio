# API 与事件协议 v0.1

统一 /api/v1，JSON camelCase，UUID字符串，UTC毫秒时间。OpenAPI作为前后端契约，生成TypeScript客户端。分页使用cursor/limit（默认30，上限100）。错误格式：{"code":"INVALID_REFERENCE","message":"资产版本不存在","details":{},"requestId":"..."}。

|Method|Path|语义|
|---|---|---|
|POST/GET|/projects|创建/列表|
|GET/PATCH|/projects/{id}|详情/更新|
|POST|/projects/{id}/outline/generate|生成大纲任务|
|POST|/projects/{id}/episodes/plan|分集规划任务|
|GET|/projects/{id}/episodes|剧集列表|
|POST|/episodes/{id}/script/generate|剧本生成任务|
|PUT|/episodes/{id}/script|创建剧本修订|
|POST|/projects/{id}/assets/extract|提取资产任务|
|GET/POST|/projects/{id}/assets|资产列表/创建|
|POST|/assets/{id}/generate|资产生成任务|
|POST|/assets/{id}/versions/{vid}/approve|审核并选定版本|
|POST|/episodes/{id}/shots/plan|拆镜任务|
|GET|/episodes/{id}/shots|镜头列表|
|PATCH|/shots/{id}|创建镜头修订|
|POST|/shots/{id}/keyframes/generate|分镜图任务|
|POST|/shots/{id}/render|视频渲染任务|
|POST|/shots/{id}/audio/generate|对白任务|
|POST|/episodes/{id}/render|批量任务计划|
|GET/PUT|/episodes/{id}/timeline|读取/保存时间轴修订|
|POST|/episodes/{id}/export|成片任务|
|GET|/tasks/{id}|任务详情及attempt|
|GET|/tasks|任务列表，按项目/状态过滤|
|POST|/tasks/{id}/retry|创建重试attempt|
|POST|/tasks/{id}/cancel|请求取消|
|GET|/events|SSE事件流|
|GET|/engines|服务状态与能力|
|GET|/workflows|可用模板与参数schema|
|GET|/system/resources|GPU/RAM/磁盘|
|GET|/media/{id}|媒体读取，支持Range|

## 示例
POST /api/v1/shots/{id}/render，Header: Idempotency-Key: <uuid>
```json
{"shotRevisionId":"<镜头修订 UUID>","workflowId":"<已注册模板 UUID>","workflowVersion":"1.0.0","firstFrameId":"<已审核预览图文件 UUID>","parameters":{"durationMs":5000,"resolution":"480p","seed":12345}}
```
示例ID为占位，实际使用UUID。后端校验模板能力、版本、审批、资源限制，计算合法帧数。返回202：{"taskId":"...","status":"PENDING"}。相同幂等键与相同请求返回原任务；不同请求返回409。编辑请求带expectedRevision，冲突返回409。404不存在，422业务校验失败，503引擎不可用（或按策略等待）。

## SSE
```
id: 123
event: task.updated
data: {"taskId":"...","status":"RUNNING","stage":"SAMPLING","nodeProgress":0.75,"overallProgress":null}

```
事件序号持久化；Last-Event-ID重放，超出保留窗口发送reset事件并重新获取快照。心跳15–30秒。前端按事件序号去重。节点进度不冒充全局进度。终态以持久化任务及结果为准。

## M0 接口补充

项目删除使用 `DELETE /projects/{id}?expectedRevision=N` 归档项目。归档保留媒体和历史任务，通过 PATCH 可恢复。模板来自本地可信导出目录。`POST /workflows/refresh` 重新检测引擎，`POST /workflows/{id}/versions` 在原图授权的标量输入内编辑参数绑定并发布新版本。M0 图片生成的输入由模板 manifest 定义，实际字段、响应和状态码见根目录 openapi.json。

`POST /assets/{id}/generate` 使用项目内唯一的 UUID 格式 Idempotency-Key，JSON 包含 workflowId、workflowVersion 和 parameters。可选 dependencies 引用同项目已有任务，priority 范围为 0 至 9。M0 模板能力为 text-to-image，每个结果生成独立待审核图片版本。

`GET /assets/{id}` 返回资产版本和媒体元数据。`POST /tasks/{id}/reconcile` 接受 attemptId 与 confirmedStopped，用于人工核实 UNKNOWN 提交后解除租约。存在匹配的远端记录时由调度器继续自动恢复。`GET /events?after=N` 支持首次指定游标，重连时优先采用 Last-Event-ID。M0 保留全部事件。

## ComfyUI内部契约
POST /prompt（API图、client_id）；WS /ws?clientId=...；GET /history/{prompt_id}；GET /queue；POST /queue或/interrupt按实际版本取消；POST /free按需卸载；GET /object_info检查模板。输出解析器按节点类型处理图片、音频、视频，不假定所有输出均为images。内部API不直接暴露前端。真实版本接口需在M0冒烟验证。

## M1 编剧接口

| 路径 | 操作 |
| --- | --- |
| /projects/{id}/outline | GET 分页修订历史，PUT 创建人工大纲修订 |
| /projects/{id}/outline/generate | POST 创建大纲生成任务 |
| /projects/{id}/episodes/plan | GET 分页规划历史，PUT 保存规划，POST 创建分集规划生成任务 |
| /projects/{id}/episodes | GET 当前规划、当前剧集及已移出的历史剧集 |
| /episodes/{id}/script | GET 分页剧本历史，PUT 保存场次和对白 |
| /episodes/{id}/script/generate | POST 创建剧本生成任务 |
| 上述三个修订资源追加 /{revisionId}/approve | POST 审核当前版本，正文含 expectedRevision |
| /engines/writer | GET 编剧模式、模型标识、版本、可用性和原因 |

三个生成接口均使用 Idempotency-Key 并返回 202。首次创建 expectedRevision 填 0，编辑或再生成时填当前修订号。分集规划需要已审核的 storyRevisionId，剧本另需当前 planId。提交内容和角色、地点、出场对白引用由后端校验。并发修改返回 409。场次、对白、人物及地点的可选数量约束保存在任务快照里。所有类型共用 /tasks 与 /events。文字任务的 assetId、workflowId 为空，kind、targetId 和 writingResultId 标识产物。[完整字段与响应](openapi.json)

文字任务详情的 `snapshot.prompt.enableThinking` 记录工作台为支持此开关的模板选定的值。未设置时沿用模型模板默认行为。申请生成一场的剧本时，输出 schema 将该场 `durationMs` 固定为请求所引用规划中的该集时长。

## M2 资产与分镜接口

| 方法与路径 | 用途 |
| --- | --- |
| POST /projects/{id}/assets/extract | 从已审核剧本提取资产，expectedRevision 为剧本修订号 |
| GET /projects/{id}/assets/extractions/{resultId} | 按场次查看提取结果 |
| POST /assets/{id}/versions/{vid}/approve 或 /reject | 审核并选用、或弃用待审核候选，传入资产的 reviewRevision 作为 expectedRevision |
| POST /episodes/{id}/shots/plan | 按场拆镜，提交已审核剧本、参考版本 ID 和每场镜头数 |
| GET /episodes/{id}/shots | 当前分镜，可通过 revisionId 查询历史 |
| GET /episodes/{id}/shots/history | 分页查看分镜修订 |
| GET 或 PATCH /shots/{id} | 定位镜头所在分镜，或修订景别、机位、动作、毫秒时长及绑定版本。GET 支持 shotRevisionId |
| PUT /episodes/{id}/shots/order | 提交完整镜头 ID 顺序 |
| POST /episodes/{id}/shots/{boardId}/approve | 审核当前有效的分镜版本 |
| POST /shots/{id}/regenerate | 保留时长和其他镜头，重新生成指定镜头 |
| POST /shots/{id}/keyframes/generate | 由明确的 shotRevisionId 和工作流版本创建预览图 |
| PUT /scenes/{id}/characters/{key}/state | 按场次和大纲角色键保存入场与出场状态 |

拆镜、编辑、重排、单镜重做和分镜审核均传 expectedRevision，首次拆镜填 0。生成接口返回 202 并接受 Idempotency-Key。文字任务的 writingResultId 对应提取记录或分镜版本。预览图任务返回媒体，snapshot 固定 shotRevisionId 和 assetVersionIds。历史图片沿用 /media/{id} 读取。[完整契约](openapi.json)

镜头参考里的 isCurrent 描述该图是否为资产的当前默认图，needsReview 描述其绑定之后的默认图变化。PATCH 省略 assetVersionIds 会原样保留这些绑定，显式提交版本列表表示选用该组合，可包括已审核的历史候选。

## M3 镜头渲染

| 方法与路径 | 用途 |
| --- | --- |
| POST /shots/{id}/keyframes/{fileId}/approve | 选定本镜头修订的一张预览图。正文包含 shotRevisionId、expectedRevision 和可选 role（first 或 last），修订号取 Shot.frameReviewRevision |
| POST /shots/{id}/render | 提交一个镜头，返回 202 和 taskId |
| POST /episodes/{id}/render | 提交 boardId、workflowId、workflowVersion、shots 和可选 parameters，返回 202、batchId 和有序 taskIds |
| GET /shots/{id}/renders | 分页读取已生成的视频版本，可按 shotRevisionId 过滤 |
| POST /shots/{id}/renders/{versionId}/approve 或 /reject | 用该视频的 reviewRevision 作为 expectedRevision 审核或弃用 |

两个渲染入口都要求 Idempotency-Key。单镜头及每个批量项按能力提交固定的 shotRevisionId 和输入 ID。首帧模板用 firstFrameId，首尾帧模板同时用 lastFrameId，参考图模板用镜头上已绑定并审核的 referenceVersionId。批量按序原子入队，每镜头随后独立执行、取消、失败或重试。任务种类为 `SHOT_RENDER`，沿用任务详情、取消、重试、SSE 与媒体 Range 接口。现有 H3 模板使用 480p 竖屏，接受 3 至 5 秒的目标时长。模板为模型计算合法帧数，Media 的 durationMs、frameCount、fps 来自实际文件。[OpenAPI 契约](openapi.json)

## M4 配音与剪辑

| 方法与路径 | 用途 |
| --- | --- |
| GET /engines/tts、GET /engines/media | 查询实际可用音色、设备和服务版本，以及 FFmpeg / FFprobe 可用性 |
| GET /projects/{id}/voices、PUT /projects/{id}/voices/{characterKey} | 查询或绑定角色音色。写入传 voiceId 和 expectedRevision，首次使用 0 |
| GET /shots/{id}/audio | 返回所选镜头修订的场内对白、候选 WAV、选用修订、实际累计时长和超时毫秒数，可传 shotRevisionId |
| POST /shots/{id}/audio/generate | 提交 shotRevisionId、从 1 开始的 lineOrdinal、voiceBindingId 和 speed（0.5 到 2） |
| POST /shots/{id}/audio/{takeId}/select | 用该行的 reviewRevision 作为 expectedRevision 选用录音 |
| GET、PUT /episodes/{id}/timeline | 查询当前或指定 revisionId。保存时传 expectedRevision、boardId 和有序 clips |
| GET /episodes/{id}/timeline/history | 分页查询该集时间轴历史 |
| POST /episodes/{id}/export | 用 timelineRevisionId 和 resolution（480p 或 1080p）创建导出任务 |
| GET /episodes/{id}/exports | 分页查询成片、任务 ID、时间轴修订和媒体元数据 |

配音服务可在每个音色返回可选的 `modelVersion`。角色绑定和任务快照固定所选音色的模型版本，省略或返回 null 时使用服务的 `version`。服务增加其他模型后，仍提供原音色版本的任务可以正常继续。

两个任务提交入口均需 UUID 格式的 Idempotency-Key，成功入队返回 HTTP 202。各片段使用毫秒设置源视频入点和保留时长，原声音量和对白音量取 0 至 2。对白输入引用已选 takeId，并独立设置片段内开始时间和字幕文本。手工字幕具有独立的开始时间和时长。WAV 和 MP4 共用 /media/{id}，支持 HTTP Range。字段和分页规则见 [OpenAPI 契约](openapi.json)。
