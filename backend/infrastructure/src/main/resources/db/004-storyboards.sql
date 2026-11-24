ALTER TABLE assets ADD COLUMN description TEXT NOT NULL DEFAULT '';
ALTER TABLE assets ADD COLUMN review_revision INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX script_project_id ON script_revisions(project_id,id);
CREATE UNIQUE INDEX script_project_episode_id ON script_revisions(project_id,episode_id,id);
CREATE TABLE scenes (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL, episode_id TEXT NOT NULL, script_revision_id TEXT NOT NULL,
 ordinal INTEGER NOT NULL CHECK(ordinal>0), UNIQUE(script_revision_id,ordinal), UNIQUE(project_id,id),
 FOREIGN KEY(project_id,episode_id,script_revision_id) REFERENCES script_revisions(project_id,episode_id,id)
);
CREATE TABLE asset_keys (
 project_id TEXT NOT NULL, type TEXT NOT NULL, source_key TEXT NOT NULL, asset_id TEXT NOT NULL,
 PRIMARY KEY(project_id,type,source_key), FOREIGN KEY(project_id,asset_id) REFERENCES assets(project_id,id)
);
CREATE TABLE asset_extractions (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL, script_revision_id TEXT NOT NULL, source_attempt_id TEXT NOT NULL UNIQUE REFERENCES task_attempts(id),
 created_at INTEGER NOT NULL, FOREIGN KEY(project_id,script_revision_id) REFERENCES script_revisions(project_id,id)
);
CREATE TABLE extracted_assets (
 extraction_id TEXT NOT NULL REFERENCES asset_extractions(id), scene_id TEXT NOT NULL REFERENCES scenes(id),
 asset_id TEXT NOT NULL REFERENCES assets(id), PRIMARY KEY(extraction_id,scene_id,asset_id)
);
CREATE INDEX extracted_by_scene ON extracted_assets(scene_id,asset_id);
CREATE TABLE character_states (
 scene_id TEXT NOT NULL REFERENCES scenes(id), character_key TEXT NOT NULL, revision INTEGER NOT NULL,
 before_json TEXT NOT NULL, after_json TEXT NOT NULL, PRIMARY KEY(scene_id,character_key)
);
CREATE TABLE storyboards (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL, episode_id TEXT NOT NULL, script_revision_id TEXT NOT NULL,
 revision INTEGER NOT NULL, approval_status TEXT NOT NULL, source_attempt_id TEXT UNIQUE REFERENCES task_attempts(id), created_at INTEGER NOT NULL,
 UNIQUE(episode_id,revision), UNIQUE(project_id,id),
 FOREIGN KEY(project_id,episode_id,script_revision_id) REFERENCES script_revisions(project_id,episode_id,id)
);
CREATE TABLE shots (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL, scene_id TEXT NOT NULL, active_revision_id TEXT, UNIQUE(project_id,id),
 FOREIGN KEY(project_id,scene_id) REFERENCES scenes(project_id,id), FOREIGN KEY(id,active_revision_id) REFERENCES shot_revisions(shot_id,id)
);
CREATE TABLE shot_revisions (
 id TEXT PRIMARY KEY, shot_id TEXT NOT NULL REFERENCES shots(id), revision INTEGER NOT NULL, framing TEXT NOT NULL,
 camera TEXT NOT NULL, action TEXT NOT NULL, duration_ms INTEGER NOT NULL CHECK(duration_ms>0),
 source_attempt_id TEXT REFERENCES task_attempts(id), created_at INTEGER NOT NULL, UNIQUE(shot_id,id), UNIQUE(shot_id,revision)
);
CREATE TABLE storyboard_shots (
 board_id TEXT NOT NULL REFERENCES storyboards(id), ordinal INTEGER NOT NULL CHECK(ordinal>0), shot_id TEXT NOT NULL,
 shot_revision_id TEXT NOT NULL, PRIMARY KEY(board_id,ordinal), UNIQUE(board_id,shot_id),
 FOREIGN KEY(shot_id,shot_revision_id) REFERENCES shot_revisions(shot_id,id)
);
CREATE INDEX boards_by_shot ON storyboard_shots(shot_id,board_id);
CREATE TABLE shot_assets (
 shot_revision_id TEXT NOT NULL REFERENCES shot_revisions(id), ordinal INTEGER NOT NULL, project_id TEXT NOT NULL,
 asset_id TEXT NOT NULL, asset_version_id TEXT NOT NULL, role TEXT NOT NULL,
 PRIMARY KEY(shot_revision_id,ordinal), UNIQUE(shot_revision_id,asset_id),
 FOREIGN KEY(project_id,asset_id) REFERENCES assets(project_id,id), FOREIGN KEY(asset_id,asset_version_id) REFERENCES asset_versions(asset_id,id)
);
CREATE INDEX shots_by_version ON shot_assets(asset_version_id);
CREATE TABLE shot_previews (
 shot_revision_id TEXT NOT NULL REFERENCES shot_revisions(id), file_id TEXT NOT NULL REFERENCES media_files(id),
 attempt_id TEXT NOT NULL REFERENCES task_attempts(id), ordinal INTEGER NOT NULL, PRIMARY KEY(attempt_id,ordinal)
);
CREATE INDEX previews_by_revision ON shot_previews(shot_revision_id);

CREATE TABLE render_tasks_next (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), target_type TEXT NOT NULL, target_id TEXT NOT NULL,
 task_type TEXT NOT NULL, workflow_id TEXT REFERENCES workflows(id), input_snapshot_json TEXT NOT NULL, request_hash TEXT NOT NULL,
 status TEXT NOT NULL, priority INTEGER NOT NULL, idempotency_key TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL, writing_result_id TEXT,
 image_asset TEXT GENERATED ALWAYS AS (CASE WHEN task_type='IMAGE' THEN target_id END) VIRTUAL,
 script_episode TEXT GENERATED ALWAYS AS (CASE WHEN task_type IN ('SCRIPT','SHOT_PLAN') THEN target_id END) VIRTUAL,
 shot_target TEXT GENERATED ALWAYS AS (CASE WHEN task_type IN ('SHOT_REGENERATE','KEYFRAME') THEN target_id END) VIRTUAL,
 CHECK((task_type='IMAGE' AND target_type='ASSET' AND workflow_id IS NOT NULL) OR
       (task_type IN ('OUTLINE','EPISODE_PLAN','ASSET_EXTRACT') AND target_type='PROJECT' AND target_id=project_id AND workflow_id IS NULL) OR
       (task_type IN ('SCRIPT','SHOT_PLAN') AND target_type='EPISODE' AND workflow_id IS NULL) OR
       (task_type='SHOT_REGENERATE' AND target_type='SHOT' AND workflow_id IS NULL) OR
       (task_type='KEYFRAME' AND target_type='SHOT' AND workflow_id IS NOT NULL)),
 FOREIGN KEY(project_id,shot_target) REFERENCES shots(project_id,id),
 FOREIGN KEY(project_id,image_asset) REFERENCES assets(project_id,id),
 FOREIGN KEY(project_id,script_episode) REFERENCES episodes(project_id,id), UNIQUE(project_id,idempotency_key)
);
INSERT INTO render_tasks_next(rowid,id,project_id,target_type,target_id,task_type,workflow_id,input_snapshot_json,
 request_hash,status,priority,idempotency_key,cancel_requested,created_at,writing_result_id)
 SELECT rowid,id,project_id,target_type,target_id,task_type,workflow_id,input_snapshot_json,request_hash,status,priority,idempotency_key,cancel_requested,created_at,writing_result_id FROM render_tasks;
DROP TABLE render_tasks;
ALTER TABLE render_tasks_next RENAME TO render_tasks;
CREATE UNIQUE INDEX one_gpu_lease ON render_tasks((1)) WHERE status IN ('SUBMITTING','SUBMITTED','RUNNING','CANCEL_REQUESTED','UNKNOWN');
CREATE INDEX tasks_dispatch ON render_tasks(status,priority,created_at);
CREATE INDEX tasks_project ON render_tasks(project_id);
CREATE INDEX tasks_asset ON render_tasks(target_id);
CREATE INDEX tasks_workflow ON render_tasks(workflow_id);
