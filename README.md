# Real-time Transcription App

一个完全在设备本地（离线）运行的 Android 实时语音转写应用。

---

## 1. 应用简介

本应用通过麦克风实时采集语音，在手机端本地完成 **语音活动检测（VAD）→ 语音识别（ASR）→ 说话人分离（Diarization）→ 语义分段与润色（LLM）** 的完整链路，并将转写结果实时显示在界面上、自动保存到文件。

核心特性：

- **完全离线 / 本地推理**：所有模型（VAD、ASR、说话人分离、LLM）均在设备端运行，无需联网即可转写，保护隐私。
- **多语种识别**：基于 Whisper / SenseVoice 系列模型，支持中、英、日、韩等多语种。
- **说话人分离（Diarization）**：通过声纹（ECAPA-TDNN）区分不同说话人，标注「谁在什么时候说了什么」。
- **声纹滑窗换人检测（SCD）**：即便两人无缝接话、中间没有停顿，也能强制切段，避免被并成一段。
- **本地 LLM 语义润色**：内置LiteRT框架，按说话人 / 停顿边界对零散句子做语义分段、加标点、整理成段落（正式稿）。
- **多模型可选**：Tiny / Base / Small / SenseVoice 等多档精度与体积，适配不同性能的设备。
- **模型应用内下载 / 手动导入**：Whisper 等大模型不随 APK 打包，可在设置页下载或手动拷贝到私有存储。
- **实时保存（双份）**：转写文本自动保存到应用私有文件，也可由用户通过系统目录选择器指定保存目录。原始 ASR 结果与 LLM 润色后的正式稿**分别保存为两份文件**，互不覆盖。
- **LLM 润色可开关**：在设置页可关闭本地 LLM 语义润色，关闭后仅保留实时原始转写（不加载 LLM 引擎、不做分段润色）。

---

## 2. 设计架构

### 2.1 技术栈

- **语言**：Kotlin 2.2
- **UI**：Jetpack Compose（Material 3）+ Navigation Compose（单 Activity：`MainActivity`）
- **并发模型**：Kotlin 协程（Coroutines）+ 通道（Channel），以「生产者-消费者」管线串联各处理阶段
- **推理引擎**：ONNX Runtime（VAD）、sherpa-onnx（ASR）、Google AI Edge LiteRT（LLM）
- **最低 / 目标 SDK**：minSdk 26（Android 8.0）/ targetSdk 35

### 2.2 处理管线（Pipeline）

音频以 16kHz 单声道 PCM 采集，按 512 样本（32ms）为一帧，经以下阶段流动：

```mermaid
flowchart
    A[AudioRecorder\nAudioRecord 采集] -->|audioChannel\nByteArray| B[TranscriptionPipeline]
    B -->|Silero VAD\n检测语音段| C[Speech Buffer\n预滚缓冲+静音切段]
    C -->|SpeakerChangeDetector\n声纹滑窗换人检测| D[WhisperWrapper\nASR 转写]
    D -->|resultChannel\nAudioTextPair| E[SpeakerDiarizationManager\n声纹识别说话人]
    E -->|RawSpeakerTurn| F[SemanticBuffer\n语义分段批处理]
    F -->|批次| G[LocalLlmManager\nGemma 2B 润色]
    G -->|正式稿| H[UI 显示 + TranscriptionFileManager 保存]
```

### 2.3 关键模块

| 文件 | 职责 |
| :--- | :--- |
| `MainActivity.kt` | 入口 Activity，Compose 导航（转写页 / 设置页）。仅负责 UI 渲染与订阅 `TranscriptionState`，不再持有任何 Native 资源或录音协程 |
| `TranscriptionApplication.kt` | `Application` 子类，持有跨 Activity / Service 共享的 `ModelManager` 与 `TranscriptionFileManager` 单例，并创建前台服务通知渠道 |
| `TranscriptionService.kt` | **前台服务**：承载整条转写管线（AudioRecorder → VAD → Whisper → 说话人分离 → 语义分段 → LLM 润色）。通过 `startForeground` + `FOREGROUND_SERVICE_TYPE_MICROPHONE` 提升为前台服务，使应用切到后台 / Activity 销毁后录音与转写仍持续运行 |
| `TranscriptionState.kt` | 跨 Activity / Service 共享的状态单例（Flow）：服务把实时转写、正式稿、录音状态、音量推送到这里，Activity 仅订阅渲染 |
| `AudioRecorder.kt` | 通过 `AudioRecord` 采集麦克风音频，按帧写入 `audioChannel` |
| `AudioUtils.kt` | 音频格式转换工具（如 `ByteArray` → `FloatArray` 归一化） |
| `SileroVadWrapper.kt` | 基于 ONNX Runtime 加载 `silero_vad.onnx`，逐帧输出语音概率，维护 V4 隐藏状态 |
| `TranscriptionPipeline.kt` | 管线核心：VAD 状态机（IDLE/RECORDING）、预滚缓冲、静音切段、SCD 触发切段 |
| `SpeakerChangeDetector.kt` | 声纹滑窗换人检测（SCD），复用 Diarization 的 extractor，按滑动步长节流 |
| `WhisperWrapper.kt` | 基于 sherpa-onnx 的 `OfflineRecognizer`，内容驱动识别 Whisper（双 onnx）或 SenseVoice（单 onnx） |
| `SpeakerDiarizationManager.kt` | 加载 ECAPA-TDNN 模型，提取声纹 embedding，按余弦相似度聚类说话人 |
| `SemanticBuffer.kt` | 累积说话人轮次，按「说话人切换 / 明显停顿 / 超长字符」边界切批 |
| `LocalLlmManager.kt` | 加载 Gemma 2B（LiteRT LM），对批次做语义分段与标点润色；GPU 失败自动回退 CPU |
| `ModelManager.kt` | 模型元数据、应用内下载（`.tar.bz2`）、解压（Apache Commons Compress）、状态管理 |
| `TranscriptionFileManager.kt` | 转写文本保存（私有文件 / 用户指定目录），原始稿与正式稿分别落盘，含临时文件迁移 |
| `SettingsScreen.kt` | 设置页：选择 ASR / 说话人 / LLM 模型、音频源、下载链接、导入与删除 |
| `Constants.kt` | 全局参数（采样率、帧长、各类阈值） |
| `VadState.kt` | VAD 状态枚举 |

### 2.4 设计要点

- **内容驱动而非字符串驱动**：`WhisperWrapper` 通过目录内实际文件（是否存在 `encoder/decoder` onnx）判断模型类型，新增模型无需改代码。
- **无界通道防丢段**：`audioChannel` / `transcriptionChannel` / `processingChannel` 均使用 `Channel.UNLIMITED`，避免慢速处理（声纹提取、LLM 推理）期间覆盖丢弃音频段。
- **SCD 节流**：声纹 embedding 提取开销大（数百 ms），按 200ms 滑动步长触发，避免拖慢实时性。
- **GPU→CPU 回退**：LLM 引擎前两次尝试 GPU，失败则回退 CPU，且处理循环仅启动一次避免竞争。
- **后台持续录音转写**：整条管线运行在 `TranscriptionService` 前台服务中（声明 `FOREGROUND_SERVICE_TYPE_MICROPHONE`），Activity 销毁 / 应用切后台后录音与转写不中断；UI 与服务通过 `TranscriptionState` 单例解耦，服务在通知栏提供「停止」入口。

---

## 3. 依赖资源

### 3.1 核心技术依赖

| 核心能力 | 依赖 | 版本 |
| :--- | :--- | :--- |
| VAD | ONNX Runtime Android（+ Extensions） | 1.27.0 / 0.13.0 |
| ASR | sherpa-onnx（k2-fsa） | v1.13.4 |
| 说话人分离 | sherpa-onnx Speaker Embedding Extractor + ECAPA-TDNN 声纹模型 | v1.13.4 |
| 本地 LLM 润色 | Google AI Edge LiteRT / LiteRT LM（含 GPU 后端） | 1.4.2 / 0.14.0 |

### 3.2 下载的模型资源

以下资源 **不随 APK 打包**，由构建任务或应用内下载获取（名称即下载链接）：

| 资源 | 类型 | 大小 | 用途 |
| :--- | :--- | :--- | :--- |
| [`silero_vad.onnx`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx) | ONNX | ~600KB | VAD（构建期 `preBuild` 自动下载到 `assets/`） |
| [`sherpa-onnx-whisper-tiny.tar.bz2`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2) | TAR.BZ2 | ~150MB | Whisper Tiny ASR（多语种） |
| [`sherpa-onnx-whisper-base.tar.bz2`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2) | TAR.BZ2 | ~290MB | Whisper Base ASR（多语种） |
| [`sherpa-onnx-whisper-small.tar.bz2`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2) | TAR.BZ2 | ~960MB | Whisper Small ASR（多语种） |
| [`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2) | TAR.BZ2 | ~240MB | SenseVoice Small ASR（多语种）（**推荐**） |
| [`3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx) | ONNX | ~20MB | ECAPA-TDNN 说话人分离 |
| [`3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx `](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models/3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx ) | ONNX | ~70MB | ECAPA-TDNN 说话人分离 （**推荐**） |
| [`minicpm_dynamic_wi8_afp32_gpu_opt.litertlm`](https://huggingface.co/litert-community/MiniCPM5-1B/resolve/main/minicpm_dynamic_wi8_afp32_gpu_opt.litertlm?download=true)（[`modelscope`](https://www.modelscope.cn/models/litert-community/MiniCPM5-1B/files)） | TFLite | ~1.0GB | MiniCPM5 1B 本地 LLM 润色 |

> Whisper / SenseVoice 解压后需含 `[prefix]-encoder.int8.onnx`、`[prefix]-decoder.int8.onnx`、`[prefix]-tokens.txt`（SenseVoice 为单 onnx）。模型存放于 `/data/user/0/com.zeerd.real_timetranscriptionapp/files/models/[model-id]/`。

---

## 4. 第三方授权方式

> 本仓库根目录未包含独立的 `LICENSE` 文件；以下为各依赖与模型的上游授权方式，具体条款请以各自官方仓库 / 发布页为准。

### 4.1 代码依赖（库）

| 依赖 | 授权协议 |
| :--- | :--- |
| Apache Commons Compress | Apache License 2.0 |
| ONNX Runtime Android / Extensions | MIT License |
| sherpa-onnx (k2-fsa) | Apache License 2.0 |
| Google AI Edge LiteRT / LiteRT LM | Apache License 2.0 |

### 4.2 模型权重

| 模型 | 授权方式 |
| :--- | :--- |
| Silero VAD (`silero_vad.onnx`) | MIT License |
| Whisper 系列（Tiny/Base/Small，OpenAI，经 k2-fsa 导出） | MIT License |
| SenseVoice Small（阿里巴巴 FunASR） | Apache License 2.0 |
| ECAPA-TDNN 说话人模型（3D-Speaker / CAM++） | 见 k2-fsa 发布页（Apache 2.0 类开源协议） |
| Gemma 2B（`gemma-2b-it-cpu-int8.tflite`，Google） | **Gemma 使用条款（Gemma Terms of Use）**——非标准开源协议，使用前须遵守 Google 的 Gemma 许可与责任条款 |

### 4.3 合规提示

- 使用 Gemma 2B 模型须同意 [Gemma 使用条款](https://ai.google.dev/gemma/terms)，并遵守其使用限制（如月活用户阈值需另行申请）。
- 各模型权重与代码依赖的再分发、修改须保留相应版权与许可声明。
- 本应用仅用于本地离线推理，部署到生产环境前请逐一核对各上游许可的合规要求。

---

## 5. 构建与运行（简述）

```bash
# 克隆后，使用 Android Studio 打开本工程，或命令行：
./gradlew assembleDebug
```

- 构建期 `preBuild` 任务会自动下载 `silero_vad.onnx` 到 `app/src/main/assets/`（需联网）。
- 首次运行后，在「设置」页下载或手动导入 Whisper / SenseVoice、说话人、LLM 模型方可使用对应功能。
- 需要 `RECORD_AUDIO` 权限；Android 12+ 会尝试加载 vendor 层 `libOpenCL.so` 以启用 GPU 加速（缺失时自动回退 CPU）。

---

## 6. 保存文件说明（双份落盘）

转写结果会**同时保存两份**，分别对应界面上的「实时流 (Raw)」与「正式稿 (Formal)」两个标签页：

| 内容 | 内部私有文件 | 用户指定目录中的文件 |
| :--- | :--- | :--- |
| 原始 ASR 结果（润色前） | `files/autosave_transcription_raw.txt` | `<用户目录>/transcription_raw.txt` |
| LLM 润色后的正式稿 | `files/autosave_transcription_formal.txt` | `<用户目录>/transcription_formal.txt` |

- **默认（未指定目录）**：写入应用私有存储的两个文件，重启后自动恢复两个标签页的历史。
- **指定目录**：点击右下角保存按钮，通过系统目录选择器（`OpenDocumentTree`）选择一个文件夹；应用会持久化该目录的读写权限，并在其中创建 `transcription_raw.txt` 与 `transcription_formal.txt` 两个文件，分别写入原始稿与正式稿。
- 两份文件相互独立，正式稿不会覆盖原始稿，便于后续对照核对。
