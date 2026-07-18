# ASR 数据流通道陷阱

## 已验证问题：transcriptionChannel 用 CONFLATED 导致丢句
- 现象：连续说话时"10 句只识别 8 句"。
- 根因：`MainActivity.transcriptionChannel` 原为 `Channel.CONFLATED`（容量 1，只保留最新值）。
  接收循环消费每条结果时还要做数百 ms 的声纹提取（`identifySpeaker`），期间 pipeline 送来的新段被覆盖丢弃。
- 修复：改为 `Channel.UNLIMITED`（与 `LocalLlmManager.processingChannel` 一致）。
- 经验：任何"生产者快、消费者慢且每条都必须保留"的通道，绝不能用 CONFLATED。

## 次要风险：TranscriptionPipeline 在接收循环里同步调用 whisperWrapper.transcribe()
- `TranscriptionPipeline.startProcessing()` 在 `Dispatchers.Default` 上同步推理，期间不 `audioChannel.receive()`。
- `audioChannel` 容量仅 100 帧（≈3.2s），且 `AudioRecord` 内部缓冲约 1s；若单段推理 > 缓冲时长，采集到的原始音频被覆盖 → 漏句。
- 长段（接近 MAX_SPEECH_DURATION_SAMPLES=20s）尤其易触发。
- 彻底修复方向：把 transcribe 从接收循环解耦（单独消费者协程池），让接收循环持续 drain audioChannel。
