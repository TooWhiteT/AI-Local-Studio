CREATE TABLE story_revisions (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), revision INTEGER NOT NULL,
 content_json TEXT NOT NULL, approval_status TEXT NOT NULL, source_attempt_id TEXT UNIQUE REFERENCES task_attempts(id),
 created_at INTEGER NOT NULL, UNIQUE(project_id,revision), UNIQUE(project_id,id)
);
CREATE TABLE episode_plans (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), revision INTEGER NOT NULL,
 story_revision_id TEXT NOT NULL, content_json TEXT NOT NULL, approval_status TEXT NOT NULL,
 source_attempt_id TEXT UNIQUE REFERENCES task_attempts(id), created_at INTEGER NOT NULL, UNIQUE(project_id,revision),
 UNIQUE(project_id,id), FOREIGN KEY(project_id,story_revision_id) REFERENCES story_revisions(project_id,id)
);
CREATE TABLE episodes (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), ordinal INTEGER NOT NULL CHECK(ordinal>0),
 UNIQUE(project_id,ordinal), UNIQUE(project_id,id)
);
CREATE TABLE script_revisions (
 id TEXT PRIMARY KEY, episode_id TEXT NOT NULL REFERENCES episodes(id), project_id TEXT NOT NULL,
 revision INTEGER NOT NULL, plan_id TEXT NOT NULL, story_revision_id TEXT NOT NULL, content_json TEXT NOT NULL,
 approval_status TEXT NOT NULL, source_attempt_id TEXT UNIQUE REFERENCES task_attempts(id), created_at INTEGER NOT NULL,
 UNIQUE(episode_id,revision), FOREIGN KEY(project_id,episode_id) REFERENCES episodes(project_id,id),
 FOREIGN KEY(project_id,plan_id) REFERENCES episode_plans(project_id,id),
 FOREIGN KEY(project_id,story_revision_id) REFERENCES story_revisions(project_id,id)
);

CREATE UNIQUE INDEX assets_project_id ON assets(project_id,id);
CREATE TABLE render_tasks_next (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), target_type TEXT NOT NULL, target_id TEXT NOT NULL,
 task_type TEXT NOT NULL, workflow_id TEXT REFERENCES workflows(id), input_snapshot_json TEXT NOT NULL, request_hash TEXT NOT NULL,
 status TEXT NOT NULL, priority INTEGER NOT NULL, idempotency_key TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL, writing_result_id TEXT,
 image_asset TEXT GENERATED ALWAYS AS (CASE WHEN task_type='IMAGE' THEN target_id END) VIRTUAL,
 script_episode TEXT GENERATED ALWAYS AS (CASE WHEN task_type='SCRIPT' THEN target_id END) VIRTUAL,
 CHECK((task_type='IMAGE' AND target_type='ASSET' AND workflow_id IS NOT NULL) OR
       (task_type IN ('OUTLINE','EPISODE_PLAN') AND target_type='PROJECT' AND target_id=project_id AND workflow_id IS NULL) OR
       (task_type='SCRIPT' AND target_type='EPISODE' AND workflow_id IS NULL)),
 FOREIGN KEY(project_id,image_asset) REFERENCES assets(project_id,id),
 FOREIGN KEY(project_id,script_episode) REFERENCES episodes(project_id,id), UNIQUE(project_id,idempotency_key)
);
INSERT INTO render_tasks_next(rowid,id,project_id,target_type,target_id,task_type,workflow_id,input_snapshot_json,
 request_hash,status,priority,idempotency_key,cancel_requested,created_at) SELECT rowid,* FROM render_tasks;
DROP TABLE render_tasks;
ALTER TABLE render_tasks_next RENAME TO render_tasks;
CREATE UNIQUE INDEX one_gpu_lease ON render_tasks((1)) WHERE status IN ('SUBMITTING','SUBMITTED','RUNNING','CANCEL_REQUESTED','UNKNOWN');
CREATE INDEX tasks_dispatch ON render_tasks(status,priority,created_at);
CREATE INDEX tasks_project ON render_tasks(project_id);
CREATE INDEX tasks_asset ON render_tasks(target_id);
CREATE INDEX tasks_workflow ON render_tasks(workflow_id);
