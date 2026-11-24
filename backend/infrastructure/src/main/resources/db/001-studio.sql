CREATE TABLE projects (
 id TEXT PRIMARY KEY, title TEXT NOT NULL, genre TEXT NOT NULL, style TEXT NOT NULL,
 aspect_ratio TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')), revision INTEGER NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE media_files (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), relative_path TEXT NOT NULL UNIQUE,
 sha256 TEXT NOT NULL, mime TEXT NOT NULL, size_bytes INTEGER NOT NULL, width INTEGER NOT NULL,
 height INTEGER NOT NULL, duration_ms INTEGER, created_at INTEGER NOT NULL
);
CREATE TABLE assets (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), type TEXT NOT NULL, name TEXT NOT NULL,
 active_version_id TEXT, created_at INTEGER NOT NULL,
 FOREIGN KEY (id,active_version_id) REFERENCES asset_versions(asset_id,id)
);
CREATE TABLE asset_versions (
 id TEXT PRIMARY KEY, asset_id TEXT NOT NULL REFERENCES assets(id), revision INTEGER NOT NULL,
 file_id TEXT NOT NULL REFERENCES media_files(id), metadata_json TEXT NOT NULL, approval_status TEXT NOT NULL,
 parent_version_id TEXT REFERENCES asset_versions(id), created_at INTEGER NOT NULL, UNIQUE(asset_id,revision), UNIQUE(asset_id,id)
);
CREATE TABLE workflows (
 id TEXT PRIMARY KEY, workflow_key TEXT NOT NULL, version TEXT NOT NULL, mode TEXT NOT NULL, manifest_path TEXT NOT NULL,
 manifest_json TEXT NOT NULL, graph_json TEXT NOT NULL, template_hash TEXT NOT NULL, enabled INTEGER NOT NULL,
 reason TEXT, engine_version TEXT, created_at INTEGER NOT NULL, UNIQUE(mode,workflow_key,version)
);
CREATE TABLE render_tasks (
 id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id), target_type TEXT NOT NULL,
 target_id TEXT NOT NULL REFERENCES assets(id), task_type TEXT NOT NULL, workflow_id TEXT NOT NULL REFERENCES workflows(id),
 input_snapshot_json TEXT NOT NULL, request_hash TEXT NOT NULL, status TEXT NOT NULL, priority INTEGER NOT NULL,
 idempotency_key TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
 UNIQUE(project_id,idempotency_key)
);
CREATE UNIQUE INDEX one_gpu_lease ON render_tasks((1)) WHERE status IN ('SUBMITTING','SUBMITTED','RUNNING','CANCEL_REQUESTED','UNKNOWN');
CREATE INDEX tasks_dispatch ON render_tasks(status,priority,created_at);
CREATE INDEX tasks_project ON render_tasks(project_id);
CREATE INDEX tasks_asset ON render_tasks(target_id);
CREATE INDEX tasks_workflow ON render_tasks(workflow_id);
CREATE TABLE task_dependencies (
 task_id TEXT NOT NULL REFERENCES render_tasks(id), dependency_id TEXT NOT NULL REFERENCES render_tasks(id),
 PRIMARY KEY(task_id,dependency_id), CHECK(task_id <> dependency_id)
);
CREATE INDEX dependencies_upstream ON task_dependencies(dependency_id);
CREATE TABLE task_attempts (
 id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES render_tasks(id), attempt_no INTEGER NOT NULL, engine_id TEXT NOT NULL,
 external_id TEXT, status TEXT NOT NULL, error_json TEXT, started_at INTEGER, finished_at INTEGER, created_at INTEGER NOT NULL,
 node_progress REAL, node TEXT, UNIQUE(task_id,attempt_no)
);
CREATE TABLE render_results (
 id TEXT PRIMARY KEY, attempt_id TEXT NOT NULL REFERENCES task_attempts(id), file_id TEXT NOT NULL REFERENCES media_files(id),
 role TEXT NOT NULL, ordinal INTEGER NOT NULL, created_at INTEGER NOT NULL, UNIQUE(attempt_id,ordinal)
);
CREATE TABLE task_events (
 event_seq INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL REFERENCES render_tasks(id),
 event_type TEXT NOT NULL, payload_json TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX events_task_seq ON task_events(task_id,event_seq);
CREATE INDEX assets_project_type ON assets(project_id,type);
CREATE INDEX files_project ON media_files(project_id);
CREATE INDEX versions_file ON asset_versions(file_id);
CREATE INDEX results_file ON render_results(file_id);
