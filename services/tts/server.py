"""Run one local FastAPI worker per CPU voice-model directory."""
import json
import logging
import os
import sqlite3
import threading
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path
from uuid import UUID

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field

from synthesis import KokoroMandarinModel, MandarinVoiceModel


class Speech(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    text: str = Field(min_length=1, max_length=2000)
    voiceId: str = Field(min_length=1, max_length=100)
    speed: float = Field(default=1, ge=0.5, le=2)
    modelVersion: str | None = Field(default=None, max_length=160)


@contextmanager
def exclusive(directory):
    """Prevent two processes from recovering a job which is still running."""
    with (directory / "worker.lock").open("a+b") as lock:
        if os.name == "nt":
            import msvcrt
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


class Jobs:
    def __init__(self, directory, model, *additional):
        self.directory = directory
        self.default_version = model.version
        self.by_voice = {}
        for engine in (model, *additional):
            for voice in engine.speakers:
                if voice in self.by_voice:
                    raise ValueError(f"重复的音色 ID {voice}")
                self.by_voice[voice] = engine
        self.wake = threading.Event()
        self.stopping = threading.Event()
        with self.db() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS jobs(
              id TEXT PRIMARY KEY, request TEXT, model TEXT, status TEXT NOT NULL,
              cancel INTEGER NOT NULL DEFAULT 0, error TEXT)""")
            # The lock proves the preceding CPU worker exited. Reuse an atomically
            # published WAV, even if its terminal transaction was interrupted.
            for row in db.execute("SELECT id,cancel FROM jobs WHERE status='running'").fetchall():
                status = "cancelled" if row['cancel'] else ("succeeded" if self.audio(row['id']).is_file() else "queued")
                db.execute("UPDATE jobs SET status=? WHERE id=?", (status, row['id']))
            for row in db.execute("SELECT id,request,model FROM jobs WHERE status='queued'").fetchall():
                job = Speech.model_validate_json(row["request"])
                engine = self.by_voice.get(job.voiceId)
                if engine is None or row["model"] != engine.version:
                    db.execute("UPDATE jobs SET status='failed',error='任务固定的模型已改变' WHERE id=?", (row["id"],))
        self.thread = threading.Thread(target=self.work, name="tts-worker")

    @contextmanager
    def db(self):
        conn = sqlite3.connect(self.directory / "jobs.db", timeout=10)
        conn.row_factory = sqlite3.Row
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    def audio(self, key):
        return self.directory / (str(UUID(key)) + ".wav")

    def result(self, key):
        with self.db() as db:
            row = db.execute("SELECT status,error FROM jobs WHERE id=?", (key,)).fetchone()
        if not row:
            raise HTTPException(404, "语音任务不存在")
        return dict(row)

    def put(self, key, job):
        body = json.dumps(job.model_dump(), sort_keys=True, ensure_ascii=False)
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            prior = db.execute("SELECT request FROM jobs WHERE id=?", (key,)).fetchone()
            if prior:
                if prior["request"] is not None and body != prior["request"]:
                    raise HTTPException(409, "相同尝试 ID 已绑定另一段对白")
            else:
                engine = self.by_voice.get(job.voiceId)
                if engine is None or not job.text.strip():
                    raise HTTPException(422, "无效音色或空白对白")
                if job.modelVersion is not None and job.modelVersion != engine.version:
                    raise HTTPException(409, "任务固定的模型与当前音色不一致")
                db.execute("INSERT INTO jobs(id,request,model,status) VALUES(?,?,?,'queued')", (key, body, engine.version))
        self.wake.set()
        return self.result(key)

    def cancel(self, key):
        with self.db() as db:
            db.execute("""INSERT INTO jobs(id,status,cancel) VALUES(?,'cancelled',1) ON CONFLICT(id) DO UPDATE
                SET cancel=1,status=CASE WHEN status='queued' THEN 'cancelled' ELSE status END""", (key,))
        return self.result(key)

    def work(self):
        while not self.stopping.is_set():
            self.wake.clear()
            with self.db() as db:
                db.execute("BEGIN IMMEDIATE")
                row = db.execute("SELECT id,request FROM jobs WHERE status='queued' ORDER BY rowid LIMIT 1").fetchone()
                if row:
                    db.execute("UPDATE jobs SET status='running' WHERE id=?", (row['id'],))
            if not row:
                self.wake.wait(1)
                continue
            dest = self.audio(row['id'])
            tmp = dest.with_suffix(".part")
            try:
                job = Speech.model_validate_json(row['request'])
                self.by_voice[job.voiceId].render(job.text, job.voiceId, job.speed, tmp)
                with tmp.open('rb+') as file:
                    os.fsync(file.fileno())
                with self.db() as db:
                    db.execute("BEGIN IMMEDIATE")
                    cancel = db.execute("SELECT cancel FROM jobs WHERE id=?", (row['id'],)).fetchone()[0]
                    if not cancel:
                        os.replace(tmp, dest)
                    db.execute("UPDATE jobs SET status=? WHERE id=?", ("cancelled" if cancel else "succeeded", row['id']))
            except Exception:
                logging.exception("语音尝试 %s 合成失败", row['id'])
                with self.db() as db:
                    db.execute("UPDATE jobs SET status=CASE WHEN cancel=1 THEN 'cancelled' ELSE 'failed' END,error='语音合成失败' WHERE id=?", (row['id'],))
            finally:
                tmp.unlink(missing_ok=True)


@asynccontextmanager
async def lifespan(app):
    directory = Path(os.environ.get("STUDIO_TTS_STORAGE", "storage/tts")).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    with exclusive(directory):
        model = MandarinVoiceModel(Path(os.environ["STUDIO_TTS_MODEL_DIR"]).resolve())
        extra_dir = os.environ.get("STUDIO_TTS_KOKORO_DIR")
        extra = [KokoroMandarinModel(Path(extra_dir).resolve())] if extra_dir else []
        jobs = Jobs(directory, model, *extra)
        app.state.jobs = jobs
        jobs.thread.start()
        try:
            yield
        finally:
            jobs.stopping.set()
            jobs.wake.set()
            jobs.thread.join()


app = FastAPI(lifespan=lifespan)


@app.get("/voices")
def voices(request: Request):
    jobs = request.app.state.jobs
    return {"device": "cpu", "version": jobs.default_version,
            "voices": [{"id": voice, "name": f"普通话 · {voice}", "modelVersion": engine.version}
                       for voice, engine in jobs.by_voice.items()]}


@app.put("/jobs/{key}")
def put(key: UUID, speech: Speech, request: Request):
    return request.app.state.jobs.put(str(key), speech)


@app.get("/jobs/{key}")
def status(key: UUID, request: Request):
    return request.app.state.jobs.result(str(key))


@app.delete("/jobs/{key}")
def cancel(key: UUID, request: Request):
    return request.app.state.jobs.cancel(str(key))


@app.get("/jobs/{key}/audio")
def audio(key: UUID, request: Request):
    jobs = request.app.state.jobs
    if jobs.result(str(key))["status"] != "succeeded":
        raise HTTPException(409, "音频尚不可读")
    if not jobs.audio(str(key)).is_file():
        raise HTTPException(410, "已完成尝试的音频文件丢失")
    return FileResponse(jobs.audio(str(key)), media_type="audio/wav")
