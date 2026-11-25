import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import uuid4

from fastapi import HTTPException

from server import Jobs, Speech, exclusive


class TestModel:
    version = "test-version"
    speakers = {"a": 0}

    def __init__(self):
        self.entered, self.release = threading.Event(), threading.Event()
        self.calls = 0

    def render(self, text, speaker, speed, output):
        self.calls += 1
        self.entered.set()
        if not self.release.wait(3):
            raise RuntimeError("test timed out")
        output.write_bytes(b"test-audio")


class JobTest(unittest.TestCase):
    def setUp(self):
        temp = TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.model = TestModel()
        self.jobs = Jobs(self.root, self.model)
        self.key = str(uuid4())
        self.speech = Speech(text="测试", voiceId="a", modelVersion=self.model.version)

    def start(self):
        self.jobs.thread.start()
        def stop():
            self.model.release.set()
            self.jobs.stopping.set()
            self.jobs.wake.set()
            self.jobs.thread.join(4)
        self.addCleanup(stop)

    def wait(self, status):
        for _ in range(100):
            if self.jobs.result(self.key)['status'] == status:
                return
            time.sleep(.02)
        self.fail(f"未进入 {status}")

    def test_two_puts_share_one_result_and_changed_input_conflicts(self):
        with ThreadPoolExecutor(2) as executor:
            self.assertEqual([r['status'] for r in executor.map(lambda _: self.jobs.put(self.key, self.speech), range(2))], ['queued'] * 2)
        with self.assertRaises(HTTPException) as exc:
            self.jobs.put(self.key, self.speech.model_copy(update={'text':'新内容'}))
        self.assertEqual(exc.exception.status_code, 409)
        self.start()
        self.model.release.set()
        self.wait('succeeded')
        self.assertEqual(self.jobs.put(self.key, self.speech)['status'], 'succeeded')
        self.assertEqual(self.model.calls, 1)

    def test_cancel_before_submit_and_during_execution(self):
        other = str(uuid4())
        self.jobs.cancel(other)
        self.assertEqual(self.jobs.put(other, self.speech)['status'], 'cancelled')
        self.jobs.put(self.key, self.speech)
        self.start()
        self.assertTrue(self.model.entered.wait(3))
        self.assertEqual(self.jobs.cancel(self.key)['status'], 'running')
        self.model.release.set()
        self.wait('cancelled')
        self.assertFalse(self.jobs.audio(self.key).exists())

    def test_recover_atomic_result_and_refuse_changed_model(self):
        with self.assertRaises(HTTPException):
            self.jobs.put(self.key, self.speech.model_copy(update={'modelVersion':'other'}))
        self.jobs.put(self.key, self.speech)
        with self.jobs.db() as db:
            db.execute("UPDATE jobs SET status='running' WHERE id=?", (self.key,))
        self.jobs.audio(self.key).write_bytes(b"already published")
        recovered = Jobs(self.root,self.model)
        self.assertEqual(recovered.result(self.key)['status'], 'succeeded')
        self.assertEqual(self.model.calls, 0)

    def test_additional_model_routes_audio_and_preserves_queued_old_voice(self):
        self.jobs.put(self.key, self.speech)
        extra = TestModel()
        extra.version = "second-version"
        extra.speakers = {"b": 3}
        self.jobs = Jobs(self.root, self.model, extra)
        self.assertEqual(self.jobs.result(self.key)["status"], "queued")
        self.assertEqual(self.jobs.default_version, self.model.version)
        another = str(uuid4())
        request = Speech(text="另一句", voiceId="b", modelVersion=extra.version)
        with self.assertRaises(HTTPException) as exc:
            self.jobs.put(another, request.model_copy(update={"modelVersion": self.model.version}))
        self.assertEqual(exc.exception.status_code, 409)
        self.jobs.put(another, request)
        self.model.release.set()
        extra.release.set()
        self.start()
        self.wait("succeeded")
        for _ in range(100):
            if self.jobs.result(another)["status"] == "succeeded":
                break
            time.sleep(.02)
        self.assertEqual(self.jobs.result(another)["status"], "succeeded")
        self.assertEqual((self.model.calls, extra.calls), (1, 1))
        self.assertEqual(Jobs(self.root, self.model).result(another)["status"], "succeeded")

    def test_removed_provider_only_fails_its_own_unstarted_jobs(self):
        extra = TestModel()
        extra.version, extra.speakers = "optional", {"b": 3}
        queue = Jobs(self.root, self.model, extra)
        other = str(uuid4())
        queue.put(self.key, self.speech)
        queue.put(other, Speech(text="在途", voiceId="b"))
        recovered = Jobs(self.root, self.model)
        self.assertEqual(recovered.result(self.key)["status"], "queued")
        self.assertEqual(recovered.result(other)["status"], "failed")

    def test_worker_directory_is_exclusive(self):
        with exclusive(self.root):
            with self.assertRaises(OSError), exclusive(self.root):
                self.fail("duplicate owner")


if __name__ == "__main__":
    unittest.main()
