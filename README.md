# Arist AI Control

Arist AI Control is the Windows companion for the Arist/AhaKey vibecoding controller. It connects to the controller over Bluetooth, manages keys, OLED content and light state, and turns the voice key into context-aware dictation for coding tools, work apps and WeChat.

> Current release: **1.0.1** · Windows 10/11 x64

## What it does

- Connects to the controller through the local BLE bridge and restores the last paired device.
- Configures four physical keys across three modes and synchronizes state with the device.
- Uploads OLED artwork and reflects supported IDE/Codex state on the light bar.
- Integrates with Codex approval hooks while preserving an explicit manual/automatic approval boundary.
- Captures voice locally, sends an active utterance to Alibaba Cloud Bailian Qwen ASR, and injects the transcript into the focused application.
- Uses separate `CHAT` and `WORK` polishing modes. Local WeChat keeps conversational wording, restores cue-backed `？` / `！` / `……`, and may add at most one conservative final emoji.
- Loads user terminology from `%LOCALAPPDATA%\AristAIControl\terminology.txt`; detected terms and names are protected from later rewriting.

## Install

1. Download `Arist-AI-Control-v1.0.1-Windows-x64.zip` from [GitHub Releases](https://github.com/Ketavin/Aristcontrol/releases).
2. Extract the complete ZIP.
3. Run `Install.cmd` as a normal Windows user, not as Administrator.
4. On first install, enter a Bailian DashScope API Key when prompted.
5. Run `Run-Remote-Preflight.cmd`, then follow `REMOTE-VOICE-TEST-GUIDE.md` in the package.

The package includes Java 17 and the BLE bridge. The computer still needs working Bluetooth/BLE drivers, microphone permission, and .NET Framework 4.7.2 or newer.

## Privacy and security

- The DashScope API Key is stored for the current Windows user with DPAPI; it is not committed to this repository.
- Idle microphone audio is held only as an in-memory pre-roll of roughly 300 ms. It is not written to disk, transcribed or uploaded until a voice session begins.
- Personal names are not bundled in the public release. Add them to the local terminology file, one entry per line.
- Focus protection is strict outside the same local WeChat top-level window and process. A remote WeChat session appears locally as `mstsc.exe`/ToDesk and therefore uses `WORK` mode.

## Source layout

```text
Aristcontrol/
├── ahakeyconfig-win-java/   # Java 17 desktop app, packaging and smoke tests
├── BLE_tcp_bridge/          # .NET Framework BLE TCP bridge
├── Resources/DefaultOLED/   # OLED and brand assets
├── scripts/                 # local integration probes
├── .github/workflows/       # Windows Java CI
├── CHANGELOG.md
└── LICENSE
```

The public repository intentionally excludes local keys, paired-device configuration, logs, Maven caches, build output and personalized terminology.

## Build from source

Prerequisites:

- Windows 10/11 x64
- JDK 17
- Maven 3.9+
- Visual Studio Build Tools with .NET Framework 4.7.2 targeting support

Build the BLE bridge first:

```powershell
msbuild .\BLE_tcp_bridge\BLE_tcp_driver.sln /p:Configuration=Release
```

Then build and test the Java app:

```powershell
Set-Location .\ahakeyconfig-win-java
.\Install-SherpaJavaApi.ps1
mvn "-Dmaven.repo.local=.m2repo" package -DskipTests
java -cp "target\classes;target\test-classes;target\lib\*" com.example.ahakey.service.QwenTranscriptNormalizerSmokeTest
java -cp "target\classes;target\test-classes;target\lib\*" com.example.ahakey.service.QwenTextPolisherSmokeTest
java -cp "target\classes;target\test-classes;target\lib\*" com.example.ahakey.service.TerminologyManagerSmokeTest
.\build-laptop-package.ps1
```

The bootstrap script downloads the official `sherpa-onnx` v1.13.3 Java API release and verifies its pinned SHA-256 before installing it into the project-local Maven repository. The packaging scripts invoke the same verification automatically.

The versioned ZIP and SHA-256 sidecar are written to `ahakeyconfig-win-java\target\distribution\`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
