# Korg Player — Android rhythm prototype

This is an Android source project. It currently provides:
- USB-MIDI device discovery and a C4 test note.
- A generated General MIDI rhythm pattern sent to a connected keyboard over USB-MIDI.
- Tempo control and selectable Variation / Fill / Break / Ending pattern modes.
- ZIP/SET archive scanning to list `.STY` / `.STG` and count supported file extensions.
- GitHub Actions workflow to build a debug APK.

## Important limits

The generated patterns are generic GM MIDI patterns, **not** decoded events from the supplied Korg `.STY` bank. Korg's proprietary style/sound data is not yet decoded into the playback engine. This project is not a Pa5X/Pa1X emulator and does not reproduce the uploaded set's original styles or PCM sounds yet.

## Build APK

Open this folder in Android Studio and build `app` / `assembleDebug`, or push it to a GitHub repository and run the workflow under Actions. The workflow uploads `app-debug.apk` as an artifact. This environment did not run an Android SDK build, so the APK has not been verified here.

## Try rhythm output

1. Connect an Android phone to a keyboard that accepts USB-MIDI input, using a compatible USB-OTG adapter/cable.
2. Open Korg Player, tap **MIDI CİHAZLARINI YENİLE**.
3. Tap **RİTMİ BAŞLAT**, select the keyboard, and enable external MIDI receive on the keyboard if needed.
4. Adjust tempo or change the pattern mode. Tap **STOP** to stop.

The receiving keyboard must respond to General MIDI percussion on channel 10 and bass notes on channel 1 for the generated pattern to sound as intended.

## Ses motoru / OC31 ilerlemesi

`Oc31Decoder.kt` legacy KORG OC31 bloklarını açar. `SampleVoiceEngine.kt` düşük gecikmeli Android `AudioTrack` tabanlı PCM sampler/mixer iskeletidir. SET içindeki gerçek örneklerin çalınması için PCM/KMP metadata ve STY olay eşlemesinin tamamlanması gerekir. Test düğmesi yalnızca dahili motorun çalıştığını duyurmak için sentetik test tonu üretir; bu, Korg sesi değildir. Ayrıntılar `SOUND_ENGINE_PROGRESS_TR.txt` içindedir.

## Audio bridge update (25 Oct 2026)

The rhythm step scheduler now triggers the internal `SampleVoiceEngine` as well as a connected USB-MIDI output. A lightweight synthesized GM-like drum kit and bass voice are installed on startup, so rhythm playback does not require a connected keyboard. This is a working audio-engine integration prototype, not yet a KORG SET renderer: original `.STY` events and `.PCM`/`.KMP`/`.PCG` sample zones are not fully decoded/mapped yet.

### STG embedded MIDI playback
The app scans `.stg` containers for embedded standard MIDI chunks. It recognizes NUL-separated scene markers and merges Bass, Drum, Perc and Acc1–Acc5 MIDI leaves for a selected scene by tick. The mode control requests Var1, Fill1, Break or Ending1; if a scene does not exist in a style, it falls back to the generated demo rhythm. Parsed events are sent to the internal synth bank and USB-MIDI. Standalone parser tests on supplied STG files pass; this is not yet an Android device/audio test. See `STG_MIDI_PLAYBACK_TR.txt`.

Latest playback work: the app can select a playable `.stg` entry from the imported ZIP (up to 120 parseable styles), tracks MIDI program changes, and synthesizes a fallback voice for parsed melodic notes when no matching sample zone is installed. This is a compatibility fallback only; original Korg PCM/KMP zones and legacy Pa1X `.STY` event decoding are still not implemented. The source has not been built into an APK or device-tested here.
