CREATE TABLE shot_assets_next (
 shot_revision_id TEXT NOT NULL REFERENCES shot_revisions(id), ordinal INTEGER NOT NULL, project_id TEXT NOT NULL,
 asset_id TEXT NOT NULL, asset_version_id TEXT NOT NULL, role TEXT NOT NULL, default_version_id TEXT,
 PRIMARY KEY(shot_revision_id,ordinal), UNIQUE(shot_revision_id,asset_id),
 FOREIGN KEY(project_id,asset_id) REFERENCES assets(project_id,id),
 FOREIGN KEY(asset_id,asset_version_id) REFERENCES asset_versions(asset_id,id),
 FOREIGN KEY(asset_id,default_version_id) REFERENCES asset_versions(asset_id,id)
);
INSERT INTO shot_assets_next SELECT shot_revision_id,ordinal,project_id,asset_id,asset_version_id,role,asset_version_id FROM shot_assets;
DROP TABLE shot_assets;
ALTER TABLE shot_assets_next RENAME TO shot_assets;
CREATE INDEX shots_by_version ON shot_assets(asset_version_id);
