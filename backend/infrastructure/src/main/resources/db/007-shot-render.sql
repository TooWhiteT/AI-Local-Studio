CREATE TABLE render_tasks_next (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), target_type TEXT NOT NULL, target_id TEXT NOT NULL,
 task_type TEXT NOT NULL, workflow_id TEXT REFERENCES workflows(id), input_snapshot_json TEXT NOT NULL, request_hash TEXT NOT NULL,
 status TEXT NOT NULL, priority INTEGER NOT NULL, idempotency_key TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL, writing_result_id TEXT,
 image_asset TEXT GENERATED ALWAYS AS (CASE WHEN task_type='IMAGE' THEN target_id END) VIRTUAL,
 script_episode TEXT GENERATED ALWAYS AS (CASE WHEN task_type IN ('SCRIPT','SHOT_PLAN') THEN target_id END) VIRTUAL,
 shot_target TEXT GENERATED ALWAYS AS (CASE WHEN task_type IN ('SHOT_REGENERATE','KEYFRAME','SHOT_RENDER') THEN target_id END) VIRTUAL,
 CHECK((task_type='IMAGE' AND target_type='ASSET' AND workflow_id IS NOT NULL) OR
       (task_type IN ('OUTLINE','EPISODE_PLAN','ASSET_EXTRACT') AND target_type='PROJECT' AND target_id=project_id AND workflow_id IS NULL) OR
       (task_type IN ('SCRIPT','SHOT_PLAN') AND target_type='EPISODE' AND workflow_id IS NULL) OR
       (task_type='SHOT_REGENERATE' AND target_type='SHOT' AND workflow_id IS NULL) OR
       (task_type IN ('KEYFRAME','SHOT_RENDER') AND target_type='SHOT' AND workflow_id IS NOT NULL)),
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

ALTER TABLE task_attempts ADD COLUMN submit_started INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_files ADD COLUMN frame_count INTEGER;
ALTER TABLE media_files ADD COLUMN fps REAL;
CREATE UNIQUE INDEX preview_revision_file ON shot_previews(shot_revision_id,file_id);
CREATE TABLE shot_frame_reviews (
 shot_revision_id TEXT PRIMARY KEY REFERENCES shot_revisions(id), first_file_id TEXT, last_file_id TEXT, review_revision INTEGER NOT NULL,
 FOREIGN KEY(shot_revision_id,first_file_id) REFERENCES shot_previews(shot_revision_id,file_id),
 FOREIGN KEY(shot_revision_id,last_file_id) REFERENCES shot_previews(shot_revision_id,file_id)
);
CREATE TABLE render_batches (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), board_id TEXT NOT NULL,
 idempotency_key TEXT NOT NULL, request_hash TEXT NOT NULL, created_at INTEGER NOT NULL,
 UNIQUE(project_id,idempotency_key), FOREIGN KEY(project_id,board_id) REFERENCES storyboards(project_id,id)
);
CREATE TABLE render_batch_tasks (
 batch_id TEXT NOT NULL REFERENCES render_batches(id), ordinal INTEGER NOT NULL, task_id TEXT NOT NULL UNIQUE REFERENCES render_tasks(id),
 PRIMARY KEY(batch_id,ordinal)
);
CREATE UNIQUE INDEX shot_bound_version ON shot_assets(shot_revision_id,asset_version_id);
CREATE TABLE shot_render_versions (
 id TEXT PRIMARY KEY, shot_id TEXT NOT NULL, shot_revision_id TEXT NOT NULL, revision INTEGER NOT NULL,
 board_id TEXT NOT NULL REFERENCES storyboards(id), first_file_id TEXT, last_file_id TEXT, reference_version_id TEXT,
 attempt_id TEXT NOT NULL REFERENCES task_attempts(id), file_id TEXT NOT NULL UNIQUE REFERENCES media_files(id),
 approval_status TEXT NOT NULL DEFAULT 'REVIEW_REQUIRED', review_revision INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
 CHECK((first_file_id IS NOT NULL AND reference_version_id IS NULL) OR
       (first_file_id IS NULL AND last_file_id IS NULL AND reference_version_id IS NOT NULL)),
 FOREIGN KEY(shot_revision_id,reference_version_id) REFERENCES shot_assets(shot_revision_id,asset_version_id),
 UNIQUE(shot_id,revision), FOREIGN KEY(shot_id,shot_revision_id) REFERENCES shot_revisions(shot_id,id),
 FOREIGN KEY(shot_revision_id,first_file_id) REFERENCES shot_previews(shot_revision_id,file_id),
 FOREIGN KEY(shot_revision_id,last_file_id) REFERENCES shot_previews(shot_revision_id,file_id)
);
CREATE INDEX render_by_revision ON shot_render_versions(shot_revision_id);
