# 接入本机 Z-Image-Turbo

### 已验收版本 1.0.0

RTX 5090 的 ComfyUI 0.46.0 已验证这份 1024 × 1024 单张文生图模板。工作台开放正向描述 `67.text`、负面描述 `71.text` 和种子 `70.seed`。采样保持导出值，8 步、CFG 1、`res_multistep`、`simple`，最终 PNG 从 `9.images` 读取。[API 图](1.0.0/workflow.json) · [参数、模型和输出声明](1.0.0/manifest.json)

用户导出文件 `z_image_turbo_test_workflow.json` 的 SHA-256 为 `439ccd495b27f06ca05125b2da77b7765ff8901f54619d3badb7cad8107ccf39`。原始成功记录 `335966fe-806e-43a0-8770-410f21804bbd` 使用种子 42。节点 9 上游的 10 个节点及其输入与该记录一致。运行副本只移除了孤立的 LoRA 节点 72，该节点缺少 model 和 clip 输入。运行图文件 SHA-256 为 `4ede3a7b846a444d5c22115e78df3c2983c581345345a7d62a8a823e70c987d5`。

已核对目标机上的扩散模型 `z_image_turbo_bf16.safetensors`、文本编码器 `qwen_3_4b.safetensors` 和 VAE `ae.safetensors`。manifest 的显存估算取自独立校准任务在 5090 上观测到的整卡已用显存峰值，向上取整为 21,379 MiB。正式两次生成的资源样本和图片已另行记录。[M0 真机验收](../../docs/M0进度.md#rtx-5090-真机验收)

### 从 RTX 5090 交付一份工作流

1. 在目标机打开 ComfyUI，加载已经出过图的 Z-Image-Turbo 文生图工作流。保留成功的模型和采样设置。如果希望新建一条基准记录，使用固定种子、单张输出，等最终图片保存后记下任务 ID。固定种子可以避免画布在执行后自动换种子而与实际提交不一致。
2. 在「文件」中选择「导出工作流（API）」并将下载的 JSON 保存为 `workflow.json`。如果菜单中没有 API 导出，打开设置，搜索 `dev mode`，启用开发者选项后再查看文件菜单。[ComfyUI 官方导出说明](https://docs.comfy.org/development/api-development/workflow-api-format) · [开发者模式设置](https://docs.comfy.org/interface/settings/comfy)
3. 打开 JSON 核对格式。顶层用节点 ID 作键，每个节点含 `class_type` 和 `inputs`。顶层出现画布的 `nodes`、`links` 列表时，需要再选一次 API 导出。
4. 把导出的文件交给集成人员即可。如果 ComfyUI 服务可访问，也可以只发刚才成功任务的 `prompt_id`。集成人员可从 `/history/<prompt_id>` 提取实际提交的 API 图，并核对最终输出文件。拿不到任务 ID 时，原始 ComfyUI PNG 若仍含 `prompt` 元数据，也可用来提取执行图。

在 Windows PowerShell 中，下面的命令列出最近一条历史记录的 ID。请核对它对应刚才完成的图片。

```powershell
$recent = Invoke-RestMethod 'http://127.0.0.1:8188/history?max_items=1'
$recent.PSObject.Properties | Select-Object -ExpandProperty Name
```

引擎在线时，集成人员可直接读取操作系统、GPU、内存、ComfyUI/Python/PyTorch 版本、已注册节点及可选模型。离线交接时，可附上述版本、所用自定义节点包的名称或版本、基准图的实际种子与尺寸。驱动版本在排查设备差异时可用 `nvidia-smi` 查询。模型权重留在运行 ComfyUI 的机器上。

### 注册模板

集成人员根据执行图、实际节点声明及基准结果准备 `manifest.json`，与 `workflow.json` 一起放入 `workflows/z-image-turbo/<version>/`。manifest 的 id 为 `z-image-turbo`，mode 为 `comfy`。节点、输入名、输出节点、模型名及资源估算均取自本机验证。manifest 结构的可运行 Mock 示例位于 `fixtures/mock-workflows/mock-image/1/`。

参数绑定限于可信导出图中已有的标量输入。每个数值参数填写 min、max，模型选择器按实际节点、输入和选项声明。工作流页的「重新检测」校验当前服务的节点、参数、资源声明和模型选项。编辑绑定会发布独立新版本。

| manifest 字段 | 填写依据 |
| --- | --- |
| `id`、`version`、`title`、`mode` | 前两项与目录一致，title 用于工作台显示，mode 填 comfy |
| `capabilities` | M0 图片模板填 `["text-to-image"]` |
| `parameters[]` | 每项含 name、label、type、node、input、default。类型为 STRING、INT、FLOAT 或 BOOLEAN，数值范围须落在本机 object_info 公布的范围内 |
| `models[]` | 每项的 node、input 和 name 对应图上的模型选择器及其在本机验证过的选项 |
| `outputs[]` | node 为返回最终图片的输出节点，field 为该节点在 history 中对应的 images 字段 |
| `estimatedVramMb` | 依据本机基准生成填写显存估算，单位 MiB |

### 目标机验收

1. 在运行 ComfyUI 的 Windows 主机上，从仓库根目录启动 Studio。先按 [运行说明](../../README.md) 构建前端。工作台监听回环地址，工作流页应显示实际引擎版本及已启用的模板。

   ```powershell
   $env:STUDIO_ENGINE = 'comfy'
   $env:COMFY_URL = 'http://127.0.0.1:8188'
   .\gradlew.bat :backend:app:run
   ```

2. 在工作台创建项目和图片资产。用两组不同的固定种子接连提交独立任务 A、B，令两次采样都实际执行。记录任务 ID、attempt ID、prompt ID 和节点进度。
3. A 出现在 ComfyUI 运行队列时，确认 B 仍在 Studio 等待，B 尚无 prompt ID。记下 A 的 prompt ID，只重启 Studio，保持 ComfyUI 运行，使用原来的存储目录、模板目录和引擎地址。恢复后 A 的业务 ID、attempt ID、prompt ID 应相同，ComfyUI 历史中该 attempt 仅对应一次提交。
4. 核对 A 的结果入库和租约释放后 B 才取得租约。B 的开始时间应大于或等于 A 的结束时间，远端队列中同时在途的验收任务最多为一个。
5. 两项任务均应成功。逐项核对 history 声明的最终图片、Studio 媒体字节的 SHA-256、解码尺寸及刷新后的资产版本。将模板版本、模型、输入、耗时和资源采样峰值记入 [M0 进度](../../docs/M0进度.md)。
