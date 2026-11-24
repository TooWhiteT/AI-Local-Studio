# 数据库规范 v0.1

SQLite WAL，foreign_keys=ON，busy_timeout；单写入协调，迁移版本化。ID使用UUID文本，时间UTC毫秒整数，时长毫秒整数；JSON字段在应用层校验。大文件仅保存相对路径、SHA256、媒体元数据。

|表|关键字段（除特别说明均含id、created_at）|
|---|---|
|projects|title, genre, style, aspect_ratio, status|
|story_revisions|project_id, revision, content_json, approval_status|
|episodes|project_id, ordinal, title, target_duration_ms|
|script_revisions|episode_id, revision, story_revision_id, content_json, approval_status|
|scenes|script_revision_id, ordinal, location_asset_id, description|
|shots|scene_id, ordinal, active_revision_id|
|shot_revisions|shot_id, revision, action, camera_json, duration_ms, render_mode, approval_status|
|dialogues|shot_revision_id, ordinal, character_asset_id, text, emotion|
|character_states|scene_id, character_asset_id, before_json, after_json|
|assets|project_id, type, name, active_version_id|
|asset_versions|asset_id, revision, file_id, metadata_json, approval_status, parent_version_id|
|media_files|project_id, relative_path, sha256, mime, size_bytes, width, height, duration_ms|
|shot_assets|shot_revision_id, asset_version_id, role, ordinal|
|workflows|workflow_key, version, manifest_path, template_hash, enabled|
|render_tasks|project_id, target_type, target_id, task_type, workflow_id, input_snapshot_json, status, priority, idempotency_key|
|task_dependencies|task_id, dependency_id（复合主键）|
|task_attempts|task_id, attempt_no, engine_id, external_id, status, error_json, started_at, finished_at|
|render_results|attempt_id, file_id, role, ordinal|
|task_events|event_seq INTEGER PRIMARY KEY, task_id, event_type, payload_json, created_at|
|artifact_dependencies|upstream_version_id, downstream_version_id（使用统一版本索引或明确类型外键）|
|timelines|episode_id, revision, fps_num, fps_den, width, height|
|timeline_tracks|timeline_id, type, ordinal|
|timeline_clips|track_id, file_id, start_ms, source_in_ms, duration_ms, settings_json|

## 约束
项目内episode ordinal唯一；同一父实体revision唯一；任务idempotency_key项目内唯一；attempt_no任务内唯一；依赖不可自指且应用层检查环。资产引用必须属于同项目且绑定具体版本。active_version指针必须属于对应资产，事务更新。历史版本不级联删除；归档优先。索引：任务(status,priority,created_at)、事件(task_id,event_seq)、外键列、镜头(scene_id,ordinal)、资产(project_id,type)。

## 状态机
任务 PENDING→READY→SUBMITTING→SUBMITTED→RUNNING→SUCCEEDED/FAILED/CANCELED；提交不确定进入UNKNOWN，取消中进入CANCEL_REQUESTED。允许直接由SUBMITTED进入终态（缓存或快速执行）。失败重试创建新attempt。业务审核状态独立：DRAFT、REVIEW_REQUIRED、APPROVED、STALE、ARCHIVED。旧版本审核记录保留；新版本生效时只标记受影响下游，不能覆盖历史渲染输入。

## 版本快照
渲染任务记录shot_revision、asset_version列表及顺序、workflow版本/hash、模型标识、prompt、seed、尺寸、帧数、音频条件。结果不可变。时间轴引用明确文件版本。剧情状态按场次而非仅按集记录，避免同集换装错误。

## 迁移与备份
M0先实现projects、media_files、assets、asset_versions、workflows、render_tasks、task_attempts、task_events、task_dependencies；其余按里程碑迁移。备份使用SQLite backup API或停写后安全快照，同时保存素材和模板。恢复测试必须验证文件哈希及引用完整性。

M0 同时建立 render_results，关联一次尝试的媒体输出及顺序。schema_migrations 记录版本、校验和与应用时间。项目 revision 用于乐观锁，工作流表保留每个版本的原图与 manifest。使用 SQLite 部分唯一索引约束占用 GPU 的任务状态，UNKNOWN 与 CANCEL_REQUESTED 同样持有租约。task_attempts 的资源采样数及显存、内存占用最大观测值按尝试保存，缺少采样时峰值为空。

## M1 迁移 003

story_revisions、episode_plans 和 script_revisions 保存不可变内容、递增修订号、审核决定及来源 attempt。episodes 保留项目内序号与稳定 ID，标题、梗概及目标时长由选定版本的规划给出。场次和对白属于相应剧本修订的 content_json，以修订内稳定的角色和地点标识引用大纲。替换规划不删除已移出的剧集。上游版本变化时查询返回 stale，旧内容与当时的审核决定均可查阅。

render_tasks 同时支持图片资产、项目大纲、分集规划和剧本。图片工作流外键仍生效，生成列与复合外键约束图片及剧集目标所属项目。迁移保留原任务 rowid、事件序号、尝试、素材和结果，在专用连接的事务内重建任务表，提交前检查所有外键。文字结果 ID、修订内容、终态和 SSE 事件在同一事务中提交。
