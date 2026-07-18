# Local Real-time Transcription App (Multilingual)

This app supports multiple Whisper models for varying accuracy and speed.

## Model Options & Accuracy

| Model | Size | Accuracy | Recommended for |
| :--- | :--- | :--- | :--- |
| **Tiny** | ~150MB | Low | High-speed, Low memory |
| **Base** | ~290MB | Medium | Balanced (Most users) |
| **Small** | ~960MB | High | High accuracy, Powerful phones |

## Model Installation

### 1. VAD Model (Required)
The VAD model is small (~600KB) and is typically bundled in the app or downloaded once:
```bash
curl -L https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx -o app/src/main/assets/silero_vad.onnx
```

### 2. Whisper Models (In-App Download)
The app no longer bundles Whisper models to keep the size small. You have two options:

### 1. In-App Download
Go to **Settings** (Gear icon) and click the **Download** icon next to the model you want.
*Note: Ensure you have a stable internet connection.*

### 2. Manual Installation (Copy to Storage)
If you already have the models, you can place them manually in the app's private storage:
`/data/user/0/com.zeerd.real_timetranscriptionapp/files/models/[model-id]/`

**Filename naming convention:**
- `[prefix]-encoder.int8.onnx`
- `[prefix]-decoder.int8.onnx`
- `[prefix]-tokens.txt`

*(Where `prefix` is `tiny`, `base`, or `small`)*

## Download Links (Manual)

#### Whisper Tiny (Multilingual)
[sherpa-onnx-whisper-tiny.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2)

#### Whisper Base (Multilingual)
[sherpa-onnx-whisper-base.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2)

#### Whisper Small (Multilingual)
[sherpa-onnx-whisper-small.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2)

### 3. Speaker Diarization

https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models



## Architecture
- **VAD**: Silero VAD (v4)
- **ASR**: Sherpa-ONNX with Whisper (Quantized)
