# Changelog

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
