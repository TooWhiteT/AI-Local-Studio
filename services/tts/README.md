# 本地普通话配音服务

工作台通过 FastAPI 按任务尝试 ID 调用 sherpa-onnx。这里使用 AISHELL-3 普通话 VITS 模型，角色音色来自压缩包内的 speakers.txt。推理在 CPU 上完成，原生输出为 8 kHz 单声道 PCM WAV。下载模型后可以离线运行。[模型发布与试听](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html) · [模型下载](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2)

在工作台主机准备 Python 3.12，并把模型解压到项目的 `storage/models/vits-icefall-zh-aishell3/`。该目录应直接包含 model.onnx、speakers.txt、tokens.txt、lexicon.txt 及随包的规则文件。本次验收压缩包的 SHA-256 为 `ab468db3a3308cdd861495e0db2f25d79418a0c00639f74944c7cdf5dd8c6ec1`。仓库只包含服务代码和锁定的 Python 依赖。

在项目根目录执行以下命令。macOS 或 Linux 使用：

```sh
python3.12 -m venv storage/tts-venv
storage/tts-venv/bin/python -m pip install -r services/tts/requirements.txt
export STUDIO_TTS_MODEL_DIR="$PWD/storage/models/vits-icefall-zh-aishell3"
storage/tts-venv/bin/python -m uvicorn server:app --app-dir services/tts --host 127.0.0.1 --port 8000
```

Windows PowerShell 使用：

```powershell
py -3.12 -m venv storage/tts-venv
.\storage\tts-venv\Scripts\python.exe -m pip install -r services/tts/requirements.txt
$env:STUDIO_TTS_MODEL_DIR = (Resolve-Path storage/models/vits-icefall-zh-aishell3).Path
.\storage\tts-venv\Scripts\python.exe -m uvicorn server:app --app-dir services/tts --host 127.0.0.1 --port 8000
```

打开 [本机音色列表](http://127.0.0.1:8000/voices) 可查看实际音色 ID 和模型版本。在另一个终端启动 Studio，将 `STUDIO_TTS_MODE` 设为 `fastapi`，`STUDIO_TTS_URL` 设为 `http://127.0.0.1:8000`。每次生成固定音色、速度和模型版本。模型文件或规则更新后，在配音页重新绑定当前音色。

单个 Uvicorn worker 独占 `STUDIO_TTS_STORAGE`（默认 `storage/tts`），其中保存 SQLite 作业记录和按 UUID 命名的 WAV。服务重启会读取已有回执。备份 Studio 时同时保留此目录、模型文件和 Studio 的媒体目录。在 macOS 上可用以下命令运行服务的作业恢复与取消测试。

```sh
storage/tts-venv/bin/python -m unittest discover -s services/tts
```

## 可选的 24 kHz 普通话音色

M5 试制使用 sherpa-onnx 的 Kokoro v1.1-zh int8 发行包。在项目目录内单独解压，然后将 `STUDIO_TTS_KOKORO_DIR` 设为含有 model.int8.onnx 和 voices.bin 的目录，使用上文同一条 uvicorn 命令启动。压缩包的 SHA-256 为 `259c9c8bc906d6078e4e0570421885dabb68b539c2f970fcbb890a58eeaf3e9c`。[模型来源及音色试听](https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese-English/kokoro-multi-lang-v1_1.html) · [int8 发行包](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2)

`/voices` 会在 AISHELL-3 声线之外注册两种经过试制的 24 kHz 声线，`kokoro-zh-003` 使用模型的第 3 号女声，`kokoro-zh-058` 使用第 58 号男声。这些编号是从 0 开始的 speaker ID。服务为每个音色返回实际的 modelVersion。角色绑定和任务快照使用所选音色的模型版本，升级后仍能读取已登记的历史 WAV。两款模型都在工作台主机的 CPU 上推理。
