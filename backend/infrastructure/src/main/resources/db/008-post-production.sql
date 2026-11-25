CREATE TABLE render_tasks_next (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), target_type TEXT NOT NULL, target_id TEXT NOT NULL,
 task_type TEXT NOT NULL, workflow_id TEXT REFERENCES workflows(id), input_snapshot_json TEXT NOT NULL, request_hash TEXT NOT NULL,
 status TEXT NOT NULL, priority INTEGER NOT NULL, idempotency_key TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL, writing_result_id TEXT,
 image_asset TEXT GENERATED ALWAYS AS (CASE WHEN task_type='IMAGE' THEN target_id END) VIRTUAL,
 script_episode TEXT GENERATED ALWAYS AS (CASE WHEN task_type IN ('SCRIPT','SHOT_PLAN','EPISODE_EXPORT') THEN target_id END) VIRTUAL,
 shot_target TEXT GENERATED ALWAYS AS (CASE WHEN task_type IN ('SHOT_REGENERATE','KEYFRAME','SHOT_RENDER','SHOT_AUDIO') THEN target_id END) VIRTUAL,
 CHECK((task_type='IMAGE' AND target_type='ASSET' AND workflow_id IS NOT NULL) OR
       (task_type IN ('OUTLINE','EPISODE_PLAN','ASSET_EXTRACT') AND target_type='PROJECT' AND target_id=project_id AND workflow_id IS NULL) OR
       (task_type IN ('SCRIPT','SHOT_PLAN','EPISODE_EXPORT') AND target_type='EPISODE' AND workflow_id IS NULL) OR
       (task_type IN ('SHOT_REGENERATE','SHOT_AUDIO') AND target_type='SHOT' AND workflow_id IS NULL) OR
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


CREATE TABLE character_voices (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), character_key TEXT NOT NULL, revision INTEGER NOT NULL,
 voice_id TEXT NOT NULL, voice_name TEXT NOT NULL, engine_id TEXT NOT NULL, engine_version TEXT NOT NULL, created_at INTEGER NOT NULL,
 UNIQUE(project_id,character_key,revision), UNIQUE(project_id,id)
);
CREATE TABLE dialogue_takes (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL, shot_id TEXT NOT NULL, shot_revision_id TEXT NOT NULL,
 line_ordinal INTEGER NOT NULL CHECK(line_ordinal>0), revision INTEGER NOT NULL, voice_binding_id TEXT NOT NULL,
 attempt_id TEXT NOT NULL UNIQUE REFERENCES task_attempts(id), file_id TEXT NOT NULL UNIQUE REFERENCES media_files(id), created_at INTEGER NOT NULL,
 FOREIGN KEY(project_id,shot_id) REFERENCES shots(project_id,id), FOREIGN KEY(shot_id,shot_revision_id) REFERENCES shot_revisions(shot_id,id),
 FOREIGN KEY(project_id,voice_binding_id) REFERENCES character_voices(project_id,id), UNIQUE(shot_revision_id,line_ordinal,revision),
 UNIQUE(shot_revision_id,line_ordinal,id)
);
CREATE TABLE dialogue_selections (
 shot_revision_id TEXT NOT NULL, line_ordinal INTEGER NOT NULL, take_id TEXT NOT NULL, review_revision INTEGER NOT NULL,
 PRIMARY KEY(shot_revision_id,line_ordinal), FOREIGN KEY(shot_revision_id,line_ordinal,take_id) REFERENCES dialogue_takes(shot_revision_id,line_ordinal,id)
);
CREATE UNIQUE INDEX board_episode_id ON storyboards(episode_id,id);
CREATE TABLE timelines (
 id TEXT PRIMARY KEY,episode_id TEXT NOT NULL,board_id TEXT NOT NULL,revision INTEGER NOT NULL,aspect_ratio TEXT NOT NULL,
 fps INTEGER NOT NULL,duration_ms INTEGER NOT NULL,created_at INTEGER NOT NULL,
 UNIQUE(episode_id,revision),UNIQUE(episode_id,id), FOREIGN KEY(episode_id,board_id) REFERENCES storyboards(episode_id,id)
);
CREATE TABLE timeline_clips (
 timeline_id TEXT NOT NULL REFERENCES timelines(id),ordinal INTEGER NOT NULL,shot_id TEXT NOT NULL REFERENCES shots(id),
 render_version_id TEXT NOT NULL REFERENCES shot_render_versions(id),source_in_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,
 original_gain REAL NOT NULL,captions_json TEXT NOT NULL,PRIMARY KEY(timeline_id,ordinal)
);
CREATE TABLE timeline_dialogues (
 timeline_id TEXT NOT NULL,clip_ordinal INTEGER NOT NULL,ordinal INTEGER NOT NULL,take_id TEXT NOT NULL REFERENCES dialogue_takes(id),
 start_ms INTEGER NOT NULL,gain REAL NOT NULL,subtitle TEXT NOT NULL,PRIMARY KEY(timeline_id,clip_ordinal,ordinal),
 FOREIGN KEY(timeline_id,clip_ordinal) REFERENCES timeline_clips(timeline_id,ordinal)
);
CREATE TABLE episode_exports (
 id TEXT PRIMARY KEY,episode_id TEXT NOT NULL,timeline_id TEXT NOT NULL,resolution TEXT NOT NULL,attempt_id TEXT NOT NULL UNIQUE REFERENCES task_attempts(id),
 file_id TEXT NOT NULL UNIQUE REFERENCES media_files(id),created_at INTEGER NOT NULL,
 FOREIGN KEY(episode_id,timeline_id) REFERENCES timelines(episode_id,id)
);
CREATE INDEX exports_by_episode ON episode_exports(episode_id);
