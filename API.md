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
{"shotRevisionId":"rev-1","workflowId":"h3-i2v","workflowVersion":"1.0.0","references":[{"assetVersionId":"asset-v1","role":"first_frame"}],"parameters":{"durationMs":5000,"resolution":"480p","seed":12345}}
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
