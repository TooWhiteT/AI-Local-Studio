CREATE TABLE storyboard_states (
 board_id TEXT NOT NULL REFERENCES storyboards(id), scene_id TEXT NOT NULL REFERENCES scenes(id),
 states_json TEXT NOT NULL, PRIMARY KEY(board_id,scene_id)
);
INSERT INTO storyboard_states(board_id,scene_id,states_json)
 SELECT b.id,s.id,'[]' FROM storyboards b JOIN scenes s ON s.script_revision_id=b.script_revision_id;
