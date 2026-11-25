import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from audit import check_inputs, measured_peak, overlaps, read_samples, stage_stats
from media import digest, expected_cues, inspect_export, parse_srt


class MeasurementTests(unittest.TestCase):
    def test_retries_and_queue_wait_do_not_double_count_old_attempts(self):
        def attempt(n, state, created, start, end):
            return {"id": str(n), "attempt_no": n, "status": state, "created_at": created, "started_at": start,
                    "finished_at": end, "executionMs": None if start is None else end-start,
                    "queueMs": None if start is None else start-created, "peak_vram_bytes": None,
                    "peak_ram_bytes": None, "resource_samples": 0, "observed": measured_peak([])}
        tasks = [{"status": "SUCCEEDED", "attempts": [attempt(1, "FAILED", 0, 10, 50),
                                                      attempt(2, "SUCCEEDED", 150, 190, 270)]},
                 {"status": "CANCELED", "attempts": [attempt(1, "CANCELED", 5, None, 8)]}]
        stats = stage_stats(tasks)
        self.assertEqual((stats["tasks"], stats["attempts"], stats["retries"]), (2, 3, 1))
        self.assertEqual(stats["attemptStates"], {"FAILED": 1, "SUCCEEDED": 1, "CANCELED": 1})
        self.assertEqual(stats["execution"]["totalMs"], 120)
        self.assertEqual(stats["queueWait"], {"count": 2, "totalMs": 50, "medianMs": 25, "maxMs": 40})
        self.assertIsNone(stats["schedulerPeakVramBytes"])
        self.assertIsNone(stats["observed"]["comfyVramUsedBytes"])

    def test_running_attempt_overlap_and_touching_endpoints(self):
        def task(key, a, b):
            return {"attempts": [{"id": key, "started_at": a, "finished_at": b}]}
        self.assertEqual(overlaps([task("A", 0, 20), task("B", 20, 40), task("C", 40, None)]), [])
        self.assertEqual(overlaps([task("A", 0, None), task("B", 0, 20)]), [["A", "B"]])

    def test_samples_belong_to_project_and_matching_attempt(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "samples"
            rows = [{"projectId": "p", "attemptId": None, "timeMs": 10}, {"projectId": "elsewhere", "attemptId": "x", "timeMs": 20},
                    {"projectId": "p", "attemptId": "a", "ttsTreeRssBytes": 321, "timeMs": 22}]
            path.write_bytes(b"".join(json.dumps(r).encode()+b"\n" for r in rows) + b'{"incomplete":')
            samples, coverage = read_samples(path, "p")
            self.assertEqual(coverage, {"samples": 2, "attributed": 1})
            self.assertEqual(measured_peak(samples["a"])["ttsTreeRssBytes"], 321)
            self.assertIsNone(measured_peak(samples["a"])["comfyVramUsedBytes"])
            self.assertEqual(read_samples(path, "p", until=21)[1], {"samples": 1})

    def test_export_is_tied_to_exact_selected_take_and_mix(self):
        clip = {"ordinal": 0, "file_id": "v", "sha256": "vhash", "source_in_ms": 0, "duration_ms": 5000,
                "original_gain": 0.4, "captions": []}
        line = {"clip_ordinal": 0, "file_id": "wav1", "sha256": "speech", "start_ms": 50,
                "gain": 0.9, "subtitle": "台词"}
        frozen = {"video": {"id": "v", "sha256": "vhash"}, "durationMs": 5000, "sourceInMs": 0, "originalGain": 0.4,
                  "captions": [], "dialogues": [{"audio": {"id": "wav1", "sha256": "speech"},
                                                "startMs": 50, "gain": 0.9, "subtitle": "台词"}]}
        check_inputs({"clips": [frozen]}, [clip], [line])
        for field, new_value in [("file_id", "wav2"), ("gain", 1.2), ("start_ms", 150)]:
            with self.assertRaisesRegex(ValueError, "audio take"):
                check_inputs({"clips": [frozen]}, [clip], [line | {field: new_value}])

    def test_overlapping_cues_keep_independent_timings_across_shots(self):
        plan = {"clips": [{"durationMs": 750, "captions": [{"startMs": 0, "durationMs": 500, "text": "甲"}],
                            "dialogues": [{"startMs": 250, "audio": {"durationMs": 500}, "subtitle": "乙"}]},
                           {"durationMs": 350, "captions": [{"startMs": 100, "durationMs": 100, "text": "丙"}],
                            "dialogues": []}]}
        self.assertEqual([(c["startMs"], c["endMs"], c["text"]) for c in expected_cues(plan)],
                         [(0, 250, "甲"), (250, 500, "甲 乙"), (500, 750, "乙"), (850, 950, "丙")])
        self.assertEqual(parse_srt("1\r\n00:00:00,250 --> 00:00:00,500\r\n甲\r\n乙\r\n")[0],
                         {"startMs": 250, "endMs": 500, "text": "甲 乙"})


class EncodedMediaTests(unittest.TestCase):
    def test_decode_and_detect_timeline_subtitle_or_checksum_changes(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d).resolve()
            media_dir = root / "projects" / "episode"
            media_dir.mkdir(parents=True)
            srt = root / "input.srt"
            srt.write_text("1\n00:00:00,050 --> 00:00:00,650\n验收\n")
            file = media_dir / "test.mp4"
            subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "color=s=480x854:r=24:d=1",
                            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1", "-i", str(srt),
                            "-map", "0:v", "-map", "1:a", "-map", "2:s", "-c:v", "libx264", "-threads", "1",
                            "-c:a", "aac", "-ac", "2", "-c:s", "mov_text", "-metadata:s:s:0", "language=zho",
                            str(file)], check=True)
            media = {"id": "test", "relativePath": str(file.relative_to(root)), "sha256": digest(file),
                     "sizeBytes": file.stat().st_size, "frameCount": 24}
            clip = {"video": media, "durationMs": 1000, "captions": [{"startMs": 50, "durationMs": 600, "text": "验收"}],
                    "dialogues": []}
            plan = {"width": 480, "height": 854, "fps": 24, "clips": [clip]}
            result = inspect_export(root, media, plan, "480p", {})
            self.assertEqual(result["errors"], [])
            self.assertGreater(result["audioRms16BitMono8k"], 0)
            clip["captions"][0]["text"] = "不同字幕"
            self.assertTrue(inspect_export(root, media, plan, "480p", {})["errors"])
            media["sha256"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "changed on disk"):
                inspect_export(root, media, plan, "480p", {})


if __name__ == "__main__":
    unittest.main()
