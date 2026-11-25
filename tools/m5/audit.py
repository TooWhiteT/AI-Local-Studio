"""Measure a Studio pilot from a read-only SQLite snapshot and its actual media files."""
import argparse
import hashlib
import json
import os
import sqlite3
import statistics
import subprocess
import wave
from collections import Counter, defaultdict
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

from media import inspect_export, verify_file, media_path


def rowdicts(db, query, *params):
    return [dict(r) for r in db.execute(query, params)]


def highest(values):
    return max((v for v in values if v is not None), default=None)


def distribution(values):
    observed = sorted(x for x in values if x is not None)
    return {"count": len(observed), "totalMs": sum(observed) if observed else None,
            "medianMs": statistics.median(observed) if observed else None,
            "maxMs": max(observed, default=None)}


def read_samples(path, project, until=None):
    by_attempt = defaultdict(list)
    coverage = Counter()
    if not path:
        return by_attempt, coverage
    with path.open("rb") as f:
        for line in f:
            if not line.endswith(b"\n"):
                break  # A sampler may still be appending the last record.
            sample = json.loads(line)
            if sample["projectId"] != project or until is not None and sample["timeMs"] > until:
                continue
            coverage["samples"] += 1
            if sample.get("hostError"):
                coverage["hostErrors"] += 1
            if sample.get("comfyError"):
                coverage["comfyErrors"] += 1
            if sample["attemptId"]:
                by_attempt[sample["attemptId"]].append(sample)
                coverage["attributed"] += 1
    return by_attempt, coverage


def measured_peak(samples):
    keys = ("comfyVramUsedBytes", "comfyRamUsedBytes", "studioTreeRssBytes", "ttsTreeRssBytes", "encoderRssBytes")
    return {"sampleCount": len(samples), **{key: highest(s.get(key) for s in samples) for key in keys}}


def stage_stats(tasks):
    attempts = [a for t in tasks for a in t["attempts"]]
    timings = [a for a in attempts if a["finished_at"] is not None and a["started_at"] is not None]
    files = {r["id"]: r["sizeBytes"] for t in tasks for r in t.get("results", [])}
    return {"tasks": len(tasks), "taskStates": dict(Counter(t["status"] for t in tasks)),
            "registeredFiles": len(files), "registeredBytes": sum(files.values()),
            "attempts": len(attempts), "attemptStates": dict(Counter(a["status"] for a in attempts)),
            "retries": sum(a["attempt_no"] > 1 for a in attempts),
            "queueWait": distribution(a["queueMs"] for a in attempts),
            "execution": distribution(a["executionMs"] for a in timings),
            "firstQueuedAt": min((a["created_at"] for a in attempts), default=None),
            "lastFinishedAt": highest(a["finished_at"] for a in attempts),
            "schedulerResourceSamples": sum(a["resource_samples"] for a in attempts),
            "schedulerPeakVramBytes": highest(a["peak_vram_bytes"] for a in attempts if a["resource_samples"]),
            "schedulerPeakRamBytes": highest(a["peak_ram_bytes"] for a in attempts if a["resource_samples"]),
            "observed": {
                "sampleCount": sum(a["observed"]["sampleCount"] for a in attempts),
                **{k: highest(a["observed"].get(k) for a in attempts) for k in measured_peak([]) if k != "sampleCount"}
            }}


def summarize(tasks):
    grouped = defaultdict(list)
    for t in tasks:
        grouped[t["kind"]].append(t)
    return {"total": stage_stats(tasks), "byKind": {k: stage_stats(v) for k, v in sorted(grouped.items())}}


def overlaps(tasks):
    spans = sorted(((a["started_at"], a["finished_at"], a["id"])
                    for t in tasks for a in t["attempts"] if a["started_at"] is not None), key=lambda s: s[0])
    result, active = [], []
    for start, end, attempt in spans:
        active = [(finish, key) for finish, key in active if finish is None or finish > start]
        if end is None or end > start:
            result.extend([key, attempt] for _, key in active)
            active.append((end, attempt))
    return result


def tasks_for_project(db, project, samples):
    owners = {r[0]: r[1] for r in db.execute(
        "SELECT s.id,c.episode_id FROM shots s JOIN scenes c ON c.id=s.scene_id WHERE s.project_id=?", (project,))}
    scripts = dict(db.execute("SELECT id,episode_id FROM script_revisions WHERE project_id=?", (project,)))
    attempts = defaultdict(list)
    for a in rowdicts(db, """SELECT a.* FROM task_attempts a JOIN render_tasks t ON t.id=a.task_id
                             WHERE t.project_id=? ORDER BY a.created_at,a.attempt_no""", project):
        start, end = a["started_at"], a["finished_at"]
        a["queueMs"] = start - a["created_at"] if start is not None else None
        a["executionMs"] = end - start if start is not None and end is not None else None
        a["observed"] = measured_peak(samples.get(a["id"], []))
        a["error"] = json.loads(a.pop("error_json") or "null")
        attempts[a.pop("task_id")].append(a)
    results = defaultdict(list)
    for r in rowdicts(db, """SELECT a.task_id,a.id AS attemptId,r.role,m.id,m.relative_path AS relativePath,
                             m.mime,m.sha256,m.size_bytes AS sizeBytes,m.width,m.height,m.duration_ms AS durationMs,
                             m.frame_count AS frameCount,m.fps FROM render_results r
                             JOIN task_attempts a ON a.id=r.attempt_id JOIN render_tasks t ON t.id=a.task_id
                             JOIN media_files m ON m.id=r.file_id AND m.project_id=t.project_id
                             WHERE t.project_id=? ORDER BY r.ordinal""", project):
        results[r.pop("task_id")].append(r)
    tasks = []
    for t in rowdicts(db, """SELECT t.*,w.workflow_key FROM render_tasks t LEFT JOIN workflows w ON w.id=t.workflow_id
                             WHERE t.project_id=? ORDER BY t.created_at,t.rowid""", project):
        snap = json.loads(t["input_snapshot_json"])
        episode = None
        if t["target_type"] == "SHOT":
            episode = owners[t["target_id"]]
        elif t["target_type"] == "EPISODE":
            episode = t["target_id"]
        elif t["task_type"] == "ASSET_EXTRACT":
            script = snap["parameters"]["request"]["scriptRevisionId"]
            episode = scripts[script]
        tasks.append({"id": t["id"], "kind": t["task_type"], "status": t["status"], "targetId": t["target_id"],
                      "createdAt": t["created_at"], "episodeId": episode,
                      "workflow": t["workflow_key"], "workflowVersion": snap.get("workflowVersion"),
                      "templateHash": snap.get("templateHash"), "plannedFrameCount": snap.get("plannedFrameCount"),
                      "inputHash": hashlib.sha256(
                          t["input_snapshot_json"].encode()).hexdigest(), "requestHash": t["request_hash"],
                      "models": snap.get("models"), "engineVersion": snap.get("engineVersion"),
                      "speech": snap.get("speech"),
                      "seeds": snap.get("seeds", {}), "attempts": attempts[t["id"]], "results": results[t["id"]]})
    return tasks


def check_inputs(plan, clips, lines):
    """Check frozen task inputs against DB relations independently of the FFmpeg recipe."""
    if len(clips) != len(plan["clips"]):
        raise ValueError("export clip count differs from the timeline")
    by_ordinal = defaultdict(list)
    for line in lines:
        by_ordinal[line["clip_ordinal"]].append((line["file_id"], line["sha256"], line["start_ms"],
                                               line["gain"], line["subtitle"]))
    for saved, frozen in zip(clips, plan["clips"]):
        stored_video = (saved["file_id"], saved["sha256"], saved["source_in_ms"],
                        saved["duration_ms"], saved["original_gain"], saved["captions"])
        video = (frozen["video"]["id"], frozen["video"]["sha256"], frozen["sourceInMs"],
                 frozen["durationMs"], frozen["originalGain"], frozen["captions"])
        if video != stored_video:
            raise ValueError("selected video, cut, gain or captions differ from the frozen export")
        dialogue = [(line["audio"]["id"], line["audio"]["sha256"], line["startMs"], line["gain"], line["subtitle"])
                    for line in frozen["dialogues"]]
        if dialogue != by_ordinal[saved["ordinal"]]:
            raise ValueError("selected audio take, mix or dialogue text differs from the frozen export")


def episode_outputs(db, storage, eid, progress, hashes):
    timeline = db.execute("SELECT * FROM timelines WHERE episode_id=? ORDER BY revision DESC LIMIT 1", (eid,)).fetchone()
    if not timeline:
        return {"timeline": None, "clips": [], "exports": [], "issues": ["missing timeline"]}
    tid = timeline["id"]
    clips = rowdicts(db, """SELECT c.ordinal,c.shot_id,c.render_version_id,c.source_in_ms,c.duration_ms,c.original_gain,c.captions_json,
                            v.approval_status,v.shot_revision_id,v.attempt_id,v.file_id,m.sha256
                            FROM timeline_clips c JOIN shot_render_versions v ON v.id=c.render_version_id
                            JOIN media_files m ON m.id=v.file_id WHERE c.timeline_id=? ORDER BY c.ordinal""", tid)
    for c in clips:
        c["captions"] = json.loads(c.pop("captions_json"))
    lines = rowdicts(db, """SELECT d.clip_ordinal,d.start_ms,d.gain,d.subtitle,d.take_id,f.file_id,v.character_key,
                            v.voice_id,v.engine_id,v.engine_version,m.sha256 FROM timeline_dialogues d
                            JOIN dialogue_takes f ON d.take_id=f.id JOIN character_voices v ON v.id=f.voice_binding_id
                            JOIN media_files m ON m.id=f.file_id WHERE d.timeline_id=? ORDER BY d.clip_ordinal,d.ordinal""", tid)
    all_exports = rowdicts(db, """SELECT e.resolution,e.created_at,e.attempt_id,e.file_id,a.task_id,a.finished_at AS finishedAt,t.input_snapshot_json,
                                  m.relative_path,m.sha256,m.size_bytes,m.frame_count FROM episode_exports e
                                  JOIN task_attempts a ON a.id=e.attempt_id JOIN render_tasks t ON t.id=a.task_id
                                  JOIN media_files m ON m.id=e.file_id WHERE e.timeline_id=?
                                  ORDER BY e.created_at DESC,e.rowid DESC""", tid)
    latest = {}
    for r in all_exports:
        latest.setdefault(r["resolution"], r)
    issues = []
    if any(c["approval_status"] != "APPROVED" for c in clips):
        issues.append("timeline references a render no longer approved")
    for resolution in ("480p", "1080p"):
        if resolution not in latest:
            issues.append("missing " + resolution)
            continue
        row = latest[resolution]
        snap = json.loads(row.pop("input_snapshot_json"))
        if progress:
            continue
        media = {"id": row["file_id"], "relativePath": row["relative_path"], "sha256": row["sha256"],
                 "sizeBytes": row["size_bytes"], "frameCount": row["frame_count"]}
        try:
            plan = snap["export"]
            check_inputs(plan, clips, lines)
            row["decoded"] = inspect_export(storage, media, plan, resolution, hashes)
            issues.extend(f"{resolution}: {msg}" for msg in row["decoded"]["errors"])
        except (ValueError, KeyError, OSError, ArithmeticError, subprocess.SubprocessError) as exc:
            issues.append(f"{resolution}: {exc}")
    return {"timeline": dict(timeline), "clips": clips, "dialogues": lines, "exports": list(latest.values()),
            "uniqueShots": len({c["shot_id"] for c in clips}), "uniqueRenderedVideoHashes": len({c["sha256"] for c in clips}),
            "durationMs": sum(c["duration_ms"] for c in clips), "issues": issues}


def make_report(db, storage, project, samples, progress, expected_shots=None):
    info = db.execute("SELECT * FROM projects WHERE id=?", (project,)).fetchone()
    if not info:
        raise ValueError(f"No project {project} in {storage}")
    tasks = tasks_for_project(db, project, samples)
    issues, episodes, hashes = [], [], {}
    raw_plan = db.execute("SELECT content_json FROM episode_plans WHERE project_id=? ORDER BY revision DESC LIMIT 1",
                          (project,)).fetchone()
    plan = json.loads(raw_plan[0])["episodes"] if raw_plan else []
    cast, places = set(), set()
    for entry in rowdicts(db, "SELECT * FROM episodes WHERE project_id=? ORDER BY ordinal", project):
        n = entry["ordinal"]
        if n > len(plan):
            continue  # Archived episode outside the current published plan.
        eid = entry["id"]
        script = db.execute("""SELECT id,content_json,revision,approval_status,created_at,source_attempt_id FROM script_revisions
                               WHERE episode_id=? ORDER BY revision DESC LIMIT 1""", (eid,)).fetchone()
        scenes = json.loads(script["content_json"])["scenes"] if script else []
        characters = sorted({person for s in scenes for person in s["characters"]})
        locations = sorted({s["locationKey"] for s in scenes})
        cast.update(characters)
        places.update(locations)
        cut = episode_outputs(db, storage, eid, progress, hashes)
        target = plan[n-1]["targetDurationMs"]
        eps_issues = list(cut.pop("issues"))
        if not script or script["approval_status"] != "APPROVED":
            eps_issues.append("missing approved script")
        if cut["timeline"]:
            board = db.execute("SELECT id,revision,approval_status,script_revision_id FROM storyboards "
                               "WHERE episode_id=? AND id=?", (eid, cut["timeline"]["board_id"])).fetchone()
            cut["storyboard"] = dict(board) if board else None
            if not board or board["approval_status"] != "APPROVED" or not script or board["script_revision_id"] != script["id"]:
                eps_issues.append("selected timeline does not reference an approved storyboard for the current script")
        if cut["timeline"] and abs(cut["durationMs"] - target) > 5000:
            eps_issues.append("timeline differs from planned duration by over 5 seconds")
        if not (55_000 <= target <= 65_000):
            eps_issues.append("episode plan outside the pilot's 55–65 second range")
        if not cut.get("dialogues"):
            eps_issues.append("no selected dubbing in timeline")
        eps_tasks = [t for t in tasks if t["episodeId"] == eid]
        candidates = rowdicts(db, """SELECT v.id,v.shot_id,v.attempt_id,v.approval_status,v.revision,
                                     m.sha256,m.duration_ms,v.created_at FROM shot_render_versions v
                                     JOIN shots s ON s.id=v.shot_id JOIN scenes sc ON s.scene_id=sc.id
                                     JOIN media_files m ON v.file_id=m.id WHERE sc.episode_id=?
                                     ORDER BY v.created_at,v.rowid""", eid)
        selected = {c["render_version_id"] for c in cut["clips"]}
        render_ms = {a["id"]: a["executionMs"] for t in eps_tasks if t["kind"] == "SHOT_RENDER" for a in t["attempts"]}
        for candidate in candidates:
            candidate["usedInTimeline"] = candidate["id"] in selected
            candidate["executionMs"] = render_ms.get(candidate["attempt_id"])
        voice_takes = rowdicts(db, """SELECT d.id,d.shot_id,d.line_ordinal,d.revision,d.attempt_id,v.character_key,
                                      v.voice_id,v.voice_name,v.engine_id,v.engine_version,m.id AS fileId,
                                      m.relative_path AS relativePath,m.sha256,m.duration_ms AS durationMs
                                      FROM dialogue_takes d JOIN character_voices v ON v.id=d.voice_binding_id
                                      JOIN media_files m ON m.id=d.file_id JOIN shots s ON s.id=d.shot_id
                                      JOIN scenes sc ON sc.id=s.scene_id WHERE sc.episode_id=? ORDER BY d.created_at,d.rowid""", eid)
        speech = {a["id"]: t for t in eps_tasks if t["kind"] == "SHOT_AUDIO" for a in t["attempts"]}
        selected_takes = {d["take_id"] for d in cut.get("dialogues", [])}
        for take in voice_takes:
            task = speech[take["attempt_id"]]
            call = task["speech"]
            take.update(usedInTimeline=take["id"] in selected_takes, text=call["text"], speed=call["speed"],
                        modelVersion=call.get("modelVersion") or task["engineVersion"], taskId=task["id"])
        takes = len(voice_takes)
        edit = {"renderCandidates": len(candidates), "renderReviews": dict(Counter(c["approval_status"] for c in candidates)),
                "selectedRenderVersions": len(selected), "audioTakes": takes,
                "selectedRenderTime": distribution(c["executionMs"] for c in candidates if c["usedInTimeline"]),
                "otherCandidateRenderTime": distribution(c["executionMs"] for c in candidates if not c["usedInTimeline"]),
                "selectedAudioTakes": len({d["take_id"] for d in cut.get("dialogues", [])})}
        episodes.append({"id": eid, "ordinal": n, "title": plan[n-1]["title"], "targetDurationMs": target,
                         "scriptRevision": script["revision"] if script else None,
                         "scriptId": script["id"] if script else None,
                         "scriptSourceAttempt": script["source_attempt_id"] if script else None,
                         "scriptSha256": hashlib.sha256(script["content_json"].encode()).hexdigest() if script else None,
                         "cast": characters,
                         "locations": locations, "performance": summarize(eps_tasks), "edits": edit,
                         "renderCandidates": candidates, "voiceTakes": voice_takes, **cut, "issues": eps_issues})
        issues += [f"episode {n}: {i}" for i in eps_issues]
    series_shots = {clip["shot_id"] for episode in episodes for clip in episode["clips"]}
    series_sources = {clip["sha256"] for episode in episodes for clip in episode["clips"]}
    if expected_shots is not None:
        if len(series_sources) != expected_shots * len(episodes):
            issues.append("source render hashes do not meet the planned distinct shot count across the series")
        for episode in episodes:
            if any(episode.get(key, 0) != expected_shots for key in ("uniqueShots", "uniqueRenderedVideoHashes")):
                issues.append(f"episode {episode['ordinal']}: expected {expected_shots} distinct shots and source renders")
    if not progress:
        for t in tasks:
            for media in t["results"]:
                try:
                    if Path(media["relativePath"]).parts[:3] != ("projects", project, "media"):
                        raise ValueError("path does not belong to the requested project")
                    verify_file(storage, media, hashes)
                except (ValueError, OSError) as exc:
                    issues.append(f"task {t['id']}, media {media['id']}: {exc}")
    if len(episodes) != 3:
        issues.append("expected three planned episodes")
    if not 2 <= len(cast) <= 3 or len(places) != 3:
        issues.append("pilot calls for 2–3 recurring characters and three locations across the series")
    voice_ids = defaultdict(set)
    for e in episodes:
        for d in e.get("dialogues", []):
            voice_ids[d["character_key"]].add((d["voice_id"], d["engine_id"], d["engine_version"]))
    if not cast.issubset(voice_ids) or any(len(v) != 1 for v in voice_ids.values()):
        issues.append("a scripted character has no fixed voice across the exported timelines")
    if any(t["status"] not in ("SUCCEEDED", "FAILED", "CANCELED") for t in tasks):
        issues.append("project has unfinished tasks")
    if any("mock" in a["engine_id"].lower() for t in tasks for a in t["attempts"]):
        issues.append("project contains mock engine attempts")
    collisions = overlaps(tasks)
    if collisions:
        issues.append("attempt lease intervals overlap within the project")
    delivered = highest(e["finishedAt"] for ep in episodes for e in ep["exports"])
    project_elapsed = delivered - info["created_at"] if delivered is not None else None
    voice_groups = defaultdict(list)
    for episode in episodes:
        for take in episode["voiceTakes"]:
            voice_groups[(take["character_key"], take["voice_id"], take["modelVersion"])].append(take)
    examples = []
    for group in voice_groups.values():
        representative = next((t for t in group if t["usedInTimeline"]), group[0])
        example = {"role": representative["character_key"], "voiceId": representative["voice_id"],
                   "modelVersion": representative["modelVersion"], "takeCount": len(group),
                   "selectedCount": sum(t["usedInTimeline"] for t in group), "sample": representative}
        if not progress:
            try:
                with wave.open(str(media_path(storage, representative))) as wav:
                    example.update(sampleRate=wav.getframerate(), nativeChannels=wav.getnchannels(),
                                   bitsPerSample=wav.getsampwidth() * 8)
            except (OSError, ValueError, wave.Error) as exc:
                issues.append(f"unreadable representative WAV {representative['id']}: {exc}")
        examples.append(example)
    render_templates = defaultdict(list)
    for t in tasks:
        if t["kind"] == "SHOT_RENDER":
            render_templates[f'{t["workflow"]}@{t["workflowVersion"]}'].append(t)
    writing = {}
    for key, table in [("outline", "story_revisions"), ("plan", "episode_plans")]:
        row = db.execute(f"SELECT id,revision,approval_status,content_json,source_attempt_id,created_at FROM {table} "
                         "WHERE project_id=? ORDER BY revision DESC LIMIT 1", (project,)).fetchone()
        if row:
            item = dict(row)
            item["contentSha256"] = hashlib.sha256(item.pop("content_json").encode()).hexdigest()
            writing[key] = item
    return {"project": dict(info), "writing": writing, "production": summarize(tasks), "projectToLatestExportMs": project_elapsed, "lastSelectedExportFinishedAt": delivered,
            "projectShared": summarize([t for t in tasks if t["episodeId"] is None]),
            "videoWorkflowPerformance": {key: stage_stats(group) for key, group in render_templates.items()},
            "voiceModelSamples": examples,
            "projectAttemptOverlaps": collisions, "seriesUniqueShots": len(series_shots),
            "seriesUniqueRenderHashes": len(series_sources), "episodes": episodes, "tasks": tasks,
            "mode": "progress" if progress else "decoded", "verifiedMediaFileCount": len(hashes),
            "issues": issues, "passed": not issues and not progress}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--storage", type=Path, required=True)
    p.add_argument("--project", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--environment", type=Path)
    p.add_argument("--expected-shots", type=int, help="Planned number of unique shots and render hashes per episode")
    p.add_argument("--samples", type=Path)
    p.add_argument("--voice-services", type=Path, help="Catalog/PID transition JSONL produced by the sampler")
    p.add_argument("--progress", action="store_true", help="Skip decoding. Progress reports never certify completion.")
    args = p.parse_args()
    storage = args.storage.resolve()
    output = args.output.resolve()
    inputs = {storage / name for name in ("studio.db", "studio.db-wal", "studio.db-shm", "studio.lock")}
    inputs.update(path.resolve() for path in (args.samples, args.environment, args.voice_services) if path)
    if output in inputs or output.is_relative_to(storage / "projects"):
        p.error("Report output would overwrite a Studio file or a measurement input")
    # Copy one coherent SQLite snapshot, releasing the live WAL before expensive media decoding.
    with closing(sqlite3.connect(":memory:")) as db:
        with closing(sqlite3.connect((storage / "studio.db").as_uri() + "?mode=ro", uri=True)) as live:
            live.backup(db)
        db.row_factory = sqlite3.Row
        deliveries = rowdicts(db, """SELECT ep.id,x.resolution,max(a.finished_at) AS finished FROM episodes ep
            JOIN timelines l ON l.episode_id=ep.id AND l.revision=(SELECT max(revision) FROM timelines WHERE episode_id=ep.id)
            JOIN episode_exports x ON x.timeline_id=l.id JOIN task_attempts a ON a.id=x.attempt_id
            WHERE ep.project_id=? GROUP BY ep.id,x.resolution""", args.project)
        cutoff = highest(e["finished"] for e in deliveries) if not args.progress and len(deliveries) == 6 else None
        samples, coverage = read_samples(args.samples, args.project, cutoff)
        report = make_report(db, storage, args.project, samples, args.progress, args.expected_shots)
    report["environment"] = json.loads(args.environment.read_text()) if args.environment else None
    report["voiceServiceTransitions"] = []
    if args.voice_services:
        with args.voice_services.open("rb") as catalog_file:
            for line in catalog_file:
                if not line.endswith(b"\n"):
                    break
                event = json.loads(line)
                if cutoff is not None and event["timeMs"] > cutoff:
                    continue
                catalog = event.pop("catalog")
                counts = Counter(v.get("modelVersion") or catalog["version"] for v in catalog["voices"])
                report["voiceServiceTransitions"].append({**event, "voices": len(catalog["voices"]),
                                                         "device": catalog["device"], "modelCounts": dict(counts)})
    report["samplingCoverage"] = dict(coverage)
    report["samplingCutoffMs"] = cutoff
    report["reportedAtUtc"] = datetime.now(timezone.utc).isoformat()
    report["definitions"] = {
        "execution": "Sum of completed attempts' lease acquisition through terminal state, including failed and retried attempts.",
        "queueWait": "Per-attempt creation to lease acquisition, including dependency/engine waits. Canceled-before-start attempts have no measured wait-to-start.",
        "projectShared": "Project-wide outline, planning and image asset work is counted once at project level.",
        "schedulerMemory": "Studio task_attempts sampled maximum on the inference host. Unsampled stages have null peaks.",
        "observerMemory": "ComfyUI system_stats total minus free as reported. On Windows this may omit other CUDA processes including llama.cpp; use scheduler board-aware VRAM samples for cross-engine comparisons. process-tree RSS totals on Studio host can include shared pages. Sampling maxima are lower bounds. Zero encoder RSS means no Studio-owned FFmpeg process was visible at those instants.",
        "duration": "Pilot plan is approximately 60 s (55–65 s). Decoded video must match the frozen timeline frame count.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    tmp = args.output.with_name(args.output.name + f".{os.getpid()}.tmp")
    try:
        tmp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        os.replace(tmp, args.output)
    finally:
        tmp.unlink(missing_ok=True)
    print(f"{report['mode']}: {len(report['episodes'])} episodes, {len(report['tasks'])} tasks, "
          f"{len(report['issues'])} issue(s). {args.output}")
    return 0 if report["passed"] or args.progress else 2


if __name__ == "__main__":
    raise SystemExit(main())
