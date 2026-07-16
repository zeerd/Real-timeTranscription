# Local Real-time Transcription App (Multilingual)

This app uses **Silero VAD** for voice detection and **Sherpa-ONNX (Whisper)** for transcription.

## Model Setup

### 1. Silero VAD (Voice Activity Detection)
```bash
curl -L https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx -o app/src/main/assets/silero_vad.onnx
```

### 2. Whisper (ASR) - Multilingual (Chinese Support)
Due to Hugging Face connectivity issues, we recommend downloading models from **ModelScope (魔搭)** or **GitHub**.

Run this script in the project root:
```bash
mkdir -p app/src/main/assets/whisper-tiny
cd app/src/main/assets/whisper-tiny
# Download the model package which contains encoder, decoder, and tokens
curl -L https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2 -o tiny.tar.bz2
tar -jxvf tiny.tar.bz2 --strip-components=1
# Ensure tokens file is named correctly if needed
mv -n tokens.txt tiny-tokens.txt
```

## Architecture
- **AudioRecord**: 16kHz Mono capture.
- **Silero VAD**: On-device speech detection.
- **Sherpa-ONNX**: Optimized Whisper inference engine for Android.
