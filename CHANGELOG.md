# Changelog

## 1.0.2 — 2026-08-14

Model-name recognition vocabulary update.

- Adds current Claude family names including Claude Fable 5, Claude Opus 5 and Claude Sonnet 5.
- Adds common GPT, Gemini, DeepSeek, Qwen, GLM, Kimi and MiniMax model names.
- Adds conservative full-model-name normalization without rewriting ambiguous ordinary words such as `cloud`.
- Extends offline terminology smoke coverage to prove the new names reach the ASR glossary and normalize safely.

## 1.0.1 — 2026-08-13

Laptop upgrade release based on the verified 1.0 public source.

- Preserves ASR-derived questions, exclamations, ellipses and chat boundaries through text polishing.
- Allows at most one conservative, cue-backed final emoji in local WeChat chat mode.
- Protects user terminology and personal names at runtime without bundling private names publicly.
- Prioritizes user terminology in the ASR prompt and preserves local terminology, API Key and BLE pairing data during upgrade.
- Bootstraps the official sherpa-onnx Java API with a pinned SHA-256 for reproducible clean builds.

## 1.0.0 — 2026-08-13

First public Arist AI Control release for Windows.

### Controller and desktop

- Bluetooth controller discovery, reconnect and state synchronization through the local BLE bridge.
- Four-key, three-mode mapping with OLED upload and light-state feedback.
- Codex hook integration with physical manual/automatic approval control.
- Product rename, Windows paths, migration and branded packaging for Arist AI Control.

### Voice input

- Hot microphone capture with a short memory-only pre-roll to avoid clipped first syllables.
- Bailian Qwen ASR with a user-editable terminology library and conservative high-confidence correction rules.
- Clipboard-first, focus-guarded text injection for local and remote application workflows.
- Separate WeChat `CHAT` and general `WORK` polishing modes.
- Prosody-aware commas, questions, exclamations and ellipses, plus a tightly allowlisted optional final emoji in clear chat contexts.
- Runtime protection for custom terminology and personal names without bundling private names in the public release.

### Packaging and validation

- Versioned Windows x64 ZIP with bundled Java 17 runtime, BLE bridge, installer, preflight checks and remote voice test guide.
- Standalone offline smoke coverage for transcript normalization, text-polish safety, terminology priority, focus policy, hot capture, hooks, device sync and persistence.
