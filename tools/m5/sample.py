"""Read-only observer for the M5 production run. Stop by creating --stop-file."""
import argparse
import json
import sqlite3
import subprocess
import time
from pathlib import Path
from urllib import request


def active(db, project):
    row = db.execute("""SELECT a.id FROM task_attempts a JOIN render_tasks t ON a.task_id=t.id
                        WHERE t.project_id=? AND a.started_at IS NOT NULL AND a.finished_at IS NULL
                        ORDER BY a.attempt_no DESC LIMIT 1""", (project,)).fetchone()
    return row[0] if row else None


def owned_processes(studio_pid, tts_pid):
    # Restrict the sample to the two supervised process trees, including FFmpeg children.
    raw = subprocess.check_output(["ps", "-ax", "-o", "pid=,ppid=,rss=,comm="], timeout=5, text=True)
    processes = {}
    for line in raw.splitlines():
        pid, parent, rss, name = line.split(None, 3)
        processes[int(pid)] = (int(parent), int(rss) * 1024, Path(name).name)
    studio_owner = studio_pid in processes and processes[studio_pid][2] in ("java", "java.exe")
    tts_owner = tts_pid in processes and processes[tts_pid][2].lower().startswith(("python", "uvicorn"))
    def owned(pid, parent):
        while pid and pid in processes:
            if pid == parent:
                return True
            pid = processes[pid][0]
        return False
    return {
        "studioTreeRssBytes": sum(rss for pid, (_, rss, _) in processes.items() if owned(pid, studio_pid)) if studio_owner else None,
        "ttsTreeRssBytes": sum(rss for pid, (_, rss, _) in processes.items() if owned(pid, tts_pid)) if tts_owner else None,
        "encoderRssBytes": sum(rss for pid, (_, rss, name) in processes.items() if name == "ffmpeg" and owned(pid, studio_pid)) if studio_owner else None
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--storage", type=Path, required=True)
    p.add_argument("--project", required=True)
    p.add_argument("--comfy", required=True, help="Existing ComfyUI origin")
    p.add_argument("--tts-session", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--stop-file", type=Path, required=True)
    p.add_argument("--catalog-events", type=Path, help="Append voice inventories and service PIDs when a deployment changes")
    p.add_argument("--interval", type=float, default=3.0)
    args = p.parse_args()
    if args.interval < 1:
        p.error("Choose an interval of at least one second")
    storage = args.storage.resolve()
    output = args.output.resolve()
    protected = {storage / name for name in ("studio.db", "studio.db-wal", "studio.db-shm", "studio.lock", "session.json")}
    if output in protected or output in {args.tts_session.resolve(), args.stop_file.resolve()} or output.is_relative_to(storage / "projects"):
        p.error("Sampler output would modify an input or Studio media")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    op = request.build_opener(request.ProxyHandler({}))
    uri = (args.storage / "studio.db").resolve().as_uri() + "?mode=ro"
    deployment = None
    if args.catalog_events:
        args.catalog_events.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(uri, uri=True) as db, args.output.open("a", buffering=1) as out:
        while not args.stop_file.exists():
            start = time.monotonic()
            attempt = active(db, args.project)
            sample = {"timeMs": time.time_ns() // 1_000_000, "projectId": args.project}
            try:
                with op.open(args.comfy.rstrip("/") + "/system_stats", timeout=3) as reply:
                    data = json.load(reply)
                device = next(d for d in data["devices"] if d["type"] == "cuda")
                sample["comfyVramUsedBytes"] = device["vram_total"] - device["vram_free"]
                sample["comfyRamUsedBytes"] = data["system"]["ram_total"] - data["system"]["ram_free"]
            except (OSError, StopIteration, KeyError, ValueError) as exc:
                sample["comfyError"] = str(exc)
            try:
                studio = json.loads((args.storage / "session.json").read_text())["pid"]
                session = json.loads(args.tts_session.read_text())
                tts = session["pid"]
                sample.update({"studioPid": studio, "ttsPid": tts}, **owned_processes(studio, tts))
                if args.catalog_events and deployment != (studio, tts):
                    with op.open(session["url"].rstrip("/") + "/voices", timeout=3) as response:
                        catalogue = json.load(response)
                    with args.catalog_events.open("a") as catalog_file:
                        catalog_file.write(json.dumps({"timeMs": sample["timeMs"], "studioPid": studio, "ttsPid": tts,
                            "service": session["url"], "catalog": catalogue}, ensure_ascii=False) + "\n")
                    deployment = (studio, tts)
            except (OSError, subprocess.SubprocessError, KeyError, ValueError) as exc:
                sample["hostError"] = str(exc)
            sample["attemptId"] = attempt if attempt == active(db, args.project) else None
            out.write(json.dumps(sample, ensure_ascii=False) + "\n")
            time.sleep(max(0, args.interval - (time.monotonic() - start)))


if __name__ == "__main__":
    main()
