# Tilawa Native — Tilwat by TECHTONICPK

Native Android (Kotlin) Quran recitation mistake-detection app. Listens to
recitation through the microphone, follows the reciter word by word, and
flags memorization mistakes (wrong / omitted / added word, skipped passage)
as they happen — fully on-device, fully offline. Audio NEVER leaves the
device.

Engine: a Kotlin port of [Tilawa](https://github.com/yazinsai/tilawa)
(`@tilawa/core` 1.0.0-rc.0, MIT) — streaming Zipformer2-CTC ONNX model
(`spliced-clean-lite`, int8, 24,650,054 bytes) + the Tilawa Quran word
reference (`ref-v2.json`, Hafs, Uthmani text from Tanzil). See NOTICE for
full attribution — the original creators' credit is kept, never stripped.

## Build

Native builds go through GitHub Actions ONLY (never a local build for
distribution). Push to `main`; the workflow downloads the ONNX model from
tilawa.dev, verifies its SHA-256, and builds the debug APK.

Local build (development only): place `model.onnx` (spliced-clean-lite)
in `app/src/main/assets/` first — it is NOT committed to the repo
(see `.gitignore`). Verify its SHA-256:
`4974de78d3c35d2542e7a24d99b747e1160d6e31e366fb68492b74a789890f1b`

## App

- Package `pk.techtonic.tilawa`, minSdk 24 (Android 7.0), targetSdk 34
- Two modes: memorization session (declared passage) and free recitation
- Session history in local SQLite
- Urdu-first UI (Jameel Noori Nastaleeq), Uthmani Mushaf display (Amiri)
- No internet permission: the app is fully offline after install
- No trial, no licence lock — a personal ibadah/learning project

## Honest limits

The engine detects memorization mistakes (wrong / omitted / added words,
skipped passages). It does NOT judge fine tajweed / makhraj subtleties —
a qualified teacher remains final for that.
