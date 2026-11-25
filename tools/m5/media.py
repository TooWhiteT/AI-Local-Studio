"""Decode a Studio export and compare its streams with the recorded timeline."""
import array
import hashlib
import html
import json
import math
import re
import subprocess
from fractions import Fraction
from pathlib import Path


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def media_path(storage, media):
    relative = Path(media["relativePath"])
    if relative.is_absolute() or len(relative.parts) < 3 or relative.parts[0] != "projects":
        raise ValueError("Invalid media path")
    path = (storage / relative).resolve()
    if relative.parts[1] in (".", "..") or not path.is_relative_to(storage / "projects" / relative.parts[1]):
        raise ValueError("Media path escapes the project's media store")
    return path


def verify_file(storage, media, cache):
    """Hash each source once, checking every reference against its own snapshot."""
    path = media_path(storage, media)
    if path not in cache:
        cache[path] = (digest(path), path.stat().st_size)
    actual, size = cache[path]
    if actual != media["sha256"] or size != media["sizeBytes"]:
        raise ValueError(f"Media {media['id']} has changed on disk")
    return path


def run(command):
    result = subprocess.run(command, capture_output=True, check=True, timeout=600)
    if result.stderr.strip():
        raise ValueError(result.stderr.decode(errors="replace")[:500])
    return result.stdout


def expected_cues(plan):
    events = {}
    count = 0
    offset = 0
    for clip in plan["clips"]:
        cues = list(clip.get("captions", []))
        for line in clip["dialogues"]:
            cues.append({"startMs": line["startMs"], "durationMs": line["audio"]["durationMs"],
                         "text": line["subtitle"]})
        for cue in cues:
            if not cue["text"].strip():
                continue
            start = offset + cue["startMs"]
            end = start + cue["durationMs"]
            events.setdefault(start, []).append((count, cue["text"]))
            events.setdefault(end, []).append((count, None))
            count += 1
        offset += clip["durationMs"]
    result, active = [], {}
    previous = 0
    for point in sorted(events):
        if active:
            text = " ".join(active[i] for i in sorted(active))
            result.append({"startMs": previous, "endMs": point, "text": " ".join(text.split())})
        for number, text in events[point]:
            if text is None:
                del active[number]
            else:
                active[number] = text
        previous = point
    return result


def parse_srt(data):
    result = []
    stamp = r"(\d+):(\d+):(\d+),(\d+)"
    for block in re.split(r"\n\s*\n", data.replace("\r", "").strip()):
        if not block:
            continue
        _, times, *lines = block.split("\n")
        match = re.fullmatch(stamp + r" --> " + stamp, times)
        if not match:
            raise ValueError(f"Unrecognized SRT timestamps: {times}")
        values = [int(part) for part in match.groups()]
        def milliseconds(h, m, s, ms):
            return ((h * 60 + m) * 60 + s) * 1000 + ms
        result.append({"startMs": milliseconds(*values[:4]), "endMs": milliseconds(*values[4:]),
                       "text": " ".join(html.unescape(" ".join(lines)).split())})
    return result


def inspect_export(storage, media, plan, resolution, hashes):
    path = verify_file(storage, media, hashes)
    for clip in plan["clips"]:
        verify_file(storage, clip["video"], hashes)
        for line in clip["dialogues"]:
            verify_file(storage, line["audio"], hashes)
    probe = json.loads(run(["ffprobe", "-v", "error", "-count_frames", "-show_streams", "-show_format", "-of", "json", str(path)]))
    streams = {stream["codec_type"]: stream for stream in probe["streams"]}
    video, audio, sub = (streams[k] for k in ("video", "audio", "subtitle"))
    fps = Fraction(video["avg_frame_rate"])
    why = []
    if sorted(stream["codec_type"] for stream in probe["streams"]) != ["audio", "subtitle", "video"]:
        why.append("expected one video, one audio and one subtitle stream")
    size = (video["width"], video["height"])
    if (video["codec_name"] != "h264" or size != (plan["width"], plan["height"])
            or min(size) != int(resolution[:-1]) or size[0] >= size[1] or fps != plan["fps"]):
        why.append("video stream differs from frozen vertical export specification")
    duration = sum(clip["durationMs"] for clip in plan["clips"])
    frames = int(video["nb_read_frames"])
    if frames != (duration * plan["fps"] + 500) // 1000 or frames != media["frameCount"]:
        why.append("decoded frame count differs from timeline or stored media")
    video_ms = float(video["duration"]) * 1000
    container_ms = float(probe["format"]["duration"]) * 1000
    frame_ms = 1000 / plan["fps"]
    if abs(video_ms - duration) > frame_ms or abs(container_ms - duration) > frame_ms:
        why.append("stream or container duration differs from the timeline by more than one frame")
    if audio["codec_name"] != "aac" or audio["channels"] != 2 or int(audio["sample_rate"]) != 48000:
        why.append("audio stream differs from stereo 48 kHz AAC")
    if sub["codec_name"] != "mov_text" or sub.get("tags", {}).get("language") not in ("zho", "chi", "zh"):
        why.append("Chinese timed text stream missing")
    raw_audio = run(["ffmpeg", "-v", "error", "-xerror", "-i", str(path), "-map", "0:a:0", "-ac", "1",
                     "-ar", "8000", "-f", "s16le", "pipe:1"])
    samples = array.array("h", raw_audio)
    rms = math.sqrt(sum(v*v for v in samples) / len(samples)) if samples else 0
    if rms == 0 or abs(len(samples) / 8 - duration) > 100:
        why.append("decoded soundtrack is silent or does not span the timeline")
    text = run(["ffmpeg", "-v", "error", "-xerror", "-i", str(path), "-map", "0:s:0", "-f", "srt", "pipe:1"])
    actual_cues = parse_srt(text.decode("utf-8-sig"))
    if not actual_cues or actual_cues != expected_cues(plan):
        why.append("decoded subtitle times or text differ from the frozen edit")
    return {"width": size[0], "height": size[1], "fps": str(fps), "decodedFrames": frames,
            "durationMs": round(video_ms), "containerDurationMs": round(container_ms), "videoCodec": video["codec_name"],
            "audioCodec": audio["codec_name"], "channels": audio["channels"], "sampleRate": int(audio["sample_rate"]),
            "decodedAudioDurationMs": round(len(samples) / 8), "audioRms16BitMono8k": round(rms, 2),
            "subtitleCodec": sub["codec_name"], "subtitleLanguage": sub.get("tags", {}).get("language"),
            "cues": actual_cues, "errors": why}
