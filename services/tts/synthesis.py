"""Offline Mandarin synthesis with versioned local speaker inventories."""
import hashlib
import wave
from importlib.metadata import version
from pathlib import Path

import numpy as np
import sherpa_onnx


class SherpaVoiceModel:
    def render(self, text: str, voice: str, speed: float, output: Path):
        audio = self.engine.generate(text, sid=self.speakers[voice], speed=speed)
        samples = np.asarray(audio.samples)
        if not 0 < len(samples) <= audio.sample_rate * 180 or not np.all(np.isfinite(samples)):
            raise ValueError("语音引擎输出时长或采样无效")
        if np.max(np.abs(samples)) < 1 / 32768:
            raise ValueError("语音引擎返回静音")
        with wave.open(str(output), "wb") as stream:
            stream.setparams((1, 2, audio.sample_rate, 0, "NONE", "not compressed"))
            stream.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())


class MandarinVoiceModel(SherpaVoiceModel):
    def __init__(self, directory: Path):
        config = sherpa_onnx.OfflineTtsConfig()
        files = ["model.onnx", "lexicon.txt", "tokens.txt", "speakers.txt",
                 "phone.fst", "date.fst", "number.fst", "new_heteronym.fst", "rule.far"]
        digest = hashlib.sha256()
        for name in files:
            with (directory / name).open("rb") as source:
                digest.update(hashlib.file_digest(source, "sha256").digest())
        config.model.vits.model = str(directory / files[0])
        config.model.vits.lexicon = str(directory / files[1])
        config.model.vits.tokens = str(directory / files[2])
        config.rule_fsts = ",".join(str(directory / name) for name in files[4:-1])
        config.rule_fars = str(directory / "rule.far")
        config.model.provider = "cpu"
        config.model.num_threads = 2
        if not config.validate():
            raise ValueError("AISHELL-3 模型文件不完整")
        self.engine = sherpa_onnx.OfflineTts(config)
        self.version = f"aishell3-{digest.hexdigest()[:20]}-sherpa-{version('sherpa-onnx')}"
        speakers = (directory / "speakers.txt").read_text().splitlines()
        if len(speakers) != self.engine.num_speakers or len(set(speakers)) != len(speakers):
            raise ValueError("音色表与语音模型不匹配")
        self.speakers = {speaker: index for index, speaker in enumerate(speakers)}


class KokoroMandarinModel(SherpaVoiceModel):
    """Two Mandarin production voices from the sherpa Kokoro v1.1-zh int8 package."""

    def __init__(self, directory: Path):
        config = sherpa_onnx.OfflineTtsConfig()
        k = config.model.kokoro
        k.model = str(directory / "model.int8.onnx")
        k.voices = str(directory / "voices.bin")
        k.tokens = str(directory / "tokens.txt")
        k.data_dir = str(directory / "espeak-ng-data")
        lexicons = ["lexicon-us-en.txt", "lexicon-zh.txt"]
        k.lexicon = ",".join(str(directory / name) for name in lexicons)
        config.model.provider = "cpu"
        config.model.num_threads = 2
        if not config.validate():
            raise ValueError("Kokoro 1.1-zh 模型文件不完整")
        self.engine = sherpa_onnx.OfflineTts(config)
        if self.engine.num_speakers != 103 or self.engine.sample_rate != 24000:
            raise ValueError("音色表或采样率与 Kokoro 1.1-zh 不匹配")

        digest = hashlib.sha256()
        files = [directory / name for name in ["model.int8.onnx", "voices.bin", "tokens.txt", *lexicons]]
        for path in files:
            digest.update(path.relative_to(directory).as_posix().encode("utf-8") + bytes([0]))
            with path.open("rb") as source:
                digest.update(hashlib.file_digest(source, "sha256").digest())
        self.version = f"kokoro-1.1-zh-{digest.hexdigest()[:20]}-sherpa-{version('sherpa-onnx')}"
        self.speakers = {"kokoro-zh-003": 3, "kokoro-zh-058": 58}
