# MiniMax H3 首帧图生视频 · RTX 5090 实测版

这份工作流把《最后一张船票》的分镜首帧变成一个竖屏镜头。在 ComfyUI 0.46.0 的 RTX 5090 上验证了 FL2VA 首帧模式、Turbo LoRA 和原生音视频输出。画布基于 [ComfyUI 官方 H3 图生视频模板](https://github.com/Comfy-Org/workflow_templates/blob/main/templates/video_minimax_h3_i2v.json)。

## 在 ComfyUI 中打开

1. 在目标机工作流列表打开 `LocalAIStudio_H3_Ferry_I2V_480p_v1`。也可以把包内的 `workflow-comfyui.json` 拖进 ComfyUI。
2. 在其他机器复现时，用「加载图像」上传包内的 `studio_h3_ferry_first_20261125.png` 并选中它。原始分镜为方图，这张输入已裁成老船长的竖屏近景。
3. 在 H3 节点中填写动作、镜头和声音提示。样片使用固定机位，要求老船长微微转头、点头，并保留安静的渡口环境声。
4. 使用同一 GPU 的工作台空闲时再在 ComfyUI 手动运行。成片由「保存视频」写入 ComfyUI 的 `output/video/` 目录。

## 工作台接入参数

`workflow-api.json` 是提交给 ComfyUI 的可执行节点图。工作台接入时应从已审核的镜头版本取得首帧，上传至引擎的 input 目录，把引擎返回的文件名绑定到节点 114，再由持久化任务队列持有 GPU 租约并提交。输出从该任务的 history 中读取。

| 参数 | API 节点与输入 | 本次实测值 |
| --- | --- | --- |
| 首帧 | `114.inputs.image` | `studio_h3_ferry_first_20261125.png` |
| 动作与声音提示 | `105:104.inputs.prompt` | 样片英文提示保存在 API 图内 |
| 画幅 | `115.inputs.aspect_ratio`、`megapixels`、`multiple` | 9:16 预设、0.4 MP、32 像素对齐，输出 480 × 864 |
| 目标时长 | `105:111.inputs.value` | 5 秒，模板按 24 fps 和合法帧数向上对齐 |
| 种子 | `105:15.inputs.noise_seed` | 20261125 |
| Turbo | `105:126.inputs.value`、`105:125.inputs.value` | 开启，8 步 |
| 输出 | `92` · `SaveVideo` | 文件名前缀为 `video/LocalAIStudio_H3_Ferry` |

## 目标机模型

| ComfyUI 模型目录 | 工作流使用的文件 |
| --- | --- |
| diffusion_models | `minimax_h3\minimax_h3_fl2va_pruned_int8_convrot.safetensors` |
| text_encoders | `qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors` |
| vae | `minimax_h3_video_vae_int8_convrot.safetensors` |
| vae | `minimax_h3_audio_vae_fp32.safetensors` |
| loras | `minimax_h3\minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors` |

`sample.mp4` 是实际输出。`verification.json` 记录 ComfyUI 回执、媒体探测结果和文件 SHA-256。样片只验证表中这组参数。M3 的工作台视频任务和批量渲染需单独接入验收。
