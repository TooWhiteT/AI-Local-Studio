import type { Model } from '../../api/client'

type SavedClip = Model['TimelineVersion']['clips'][number]
export type Clip = Omit<Required<SavedClip>, 'dialogues'> & {
  dialogues: Required<NonNullable<SavedClip['dialogues']>[number]>[]
}
export function editableClip(clip: SavedClip): Clip {
  return {
    ...clip,
    originalGain: clip.originalGain ?? 1,
    captions: (clip.captions ?? []).map((c) => ({ ...c })),
    dialogues: (clip.dialogues ?? []).map((d) => ({
      ...d,
      startMs: d.startMs ?? 0,
      gain: d.gain ?? 1,
      subtitle: d.subtitle ?? '',
    })),
  }
}
export type Source = { shot: Model['Shot']; videos: Model['RenderVersion'][]; audio: Model['ShotAudio'] }
export const seconds = (milliseconds: number) => Number((milliseconds / 1000).toFixed(3))
export const displayTime = (milliseconds: number) => seconds(milliseconds) + ' 秒'
export const shotLabel = (shot: Model['Shot']) => '场 ' + shot.sceneOrdinal + ' · 镜头 ' + shot.ordinal
export const validVideos = (source: Source) =>
  source.videos.filter((v) => v.approvalStatus === 'APPROVED' && !v.stale)
export function selectedAudio(source: Source) {
  return source.audio.lines.flatMap((line) => {
    const take = line.takes.find((t) => t.id === line.selectedTakeId && !t.stale)
    return take ? [{ take, line }] : []
  })
}
export function newClip(source: Source): Clip | undefined {
  const video = validVideos(source)[0]
  if (!video?.media.durationMs) return
  return {
    shotId: source.shot.id,
    renderVersionId: video.id,
    sourceInMs: 0,
    durationMs: video.media.durationMs,
    originalGain: 1,
    dialogues: [],
    captions: [],
  }
}

export function clipIssue(clip: Clip, source?: Source) {
  const media = source && validVideos(source).find((v) => v.id === clip.renderVersionId)?.media
  if (!media?.durationMs) return '请重新选择已审核的视频。'
  if (clip.sourceInMs < 0 || clip.durationMs < 42 || clip.sourceInMs + clip.durationMs > media.durationMs)
    return '裁剪后至少需保留一帧，片段末尾不可超过素材时长。'
  if (clip.originalGain < 0 || clip.originalGain > 2) return '原声音量可设为 0% 至 200%。'
  const available = selectedAudio(source!)
  for (const d of clip.dialogues) {
    const audio = available.find((a) => a.take.id === d.takeId)?.take.media
    if (!audio?.durationMs) return '有配音版本已失效，请替换或移除。'
    if (d.startMs < 0 || d.startMs + audio.durationMs > clip.durationMs)
      return '对白超出片段，请调整配音起点或镜头时长。'
    if (d.gain < 0 || d.gain > 2) return '配音音量可设为 0% 至 200%。'
  }
  if (
    clip.captions.some(
      (c) =>
        !c.text.trim() || c.startMs < 0 || c.durationMs <= 0 || c.startMs + c.durationMs > clip.durationMs,
    )
  )
    return '请填写画面字幕，并把起止时间设在片段内。'
  return ''
}
