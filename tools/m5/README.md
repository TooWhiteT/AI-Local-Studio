# 三集试制度量

在项目根目录运行，使用 Python 3.11+、FFmpeg 和 FFprobe。报告工具以只读方式打开 Studio 数据库，并将一个一致快照复制到内存。编码任务、媒体、审核记录和实时数据库都无需写入。代码根据镜头与场次的归属统计每集任务，共享角色图等项目级任务单独计数。

```sh
python3 tools/m5/audit.py --storage storage/m2-5090-20261125 \
  --project 17d8aab7-a70b-4702-bd39-077e2f0ee0f3 --expected-shots 12 \
  --samples storage/m5-pilot/resources.jsonl --voice-services storage/m5-pilot/voice-services.jsonl \
  --environment storage/m5-pilot/environment.json \
  --output storage/m5-pilot/audit.json
python3 -m unittest discover -s tools/m5 -v
```

省略可选的 samples 或 environment 参数时，相应指标留空。加 `--progress` 可以在试制中途快速查询排队与执行情况。最终验收使用完整模式，退出码 0 表示通过，2 表示报告列出了缺件或校验差异。

每集按当前时间轴查找同版本的两档成片。从磁盘逐一核对素材及输出的 SHA-256，通过 FFprobe 解码计帧，解码 AAC 测量时长和有效信号，并将 MP4 中提取的字幕逐条与冻结快照的文字、入点和出点核对。报告中 `execution` 为已结束尝试的执行耗时，`queueWait` 为每次入队到开始的等待。重试有各自的起点。单 GPU 完成一个任务后才调度下一个，同批任务累计的排队时间可以超过实际制作历时。

试制现场另有可选的 macOS 控制机旁路采样。Linux 或 Windows 上可以单独运行最终报告。运行采样时须使用当前 Studio 与 TTS 的真实启动记录，`session.json` 的 `pid` 须为实际服务进程 ID。下面的命令只读取 ComfyUI 的 system_stats、Studio 内当前任务和控制机进程树的 RSS，每三秒追加一行 JSON。完成后在工作目录下创建指定的 stop-file 即可停止，复测时选用新的输出目录。

```sh
python3 tools/m5/sample.py --storage storage/m2-5090-20261125 \
  --project 17d8aab7-a70b-4702-bd39-077e2f0ee0f3 --comfy http://192.168.31.154:8188 \
  --tts-session storage/m4-acceptance/tts.json --output storage/m5-pilot/resources.jsonl \
  --catalog-events storage/m5-pilot/voice-services.jsonl --stop-file storage/m5-pilot/metrics-finish.stop
```

Windows ComfyUI 的 system_stats 显存读数可能漏计 llama.cpp 的 CUDA 分配，跨引擎比较应结合工作台采集的整卡观测值。Studio/TTS 的进程树 RSS 统计驻留内存，共享物理页可能被多个进程计入。旁路样本在采样前后对应同一个尝试时才计入该任务的峰值。已运行的任务可能没有被三秒采样间隔覆盖。
