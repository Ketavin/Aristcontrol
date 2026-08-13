# Arist AI Control Windows 安装包

版本：`{{VERSION}}`

1. 解压整个 ZIP。
2. 使用普通 Windows 用户双击 `Install.cmd`，不要以管理员身份运行。
3. 首次安装时按提示输入百炼 DashScope API Key。
4. 运行 `Run-Remote-Preflight.cmd`，查看桌面生成的预检报告。
5. 按 `REMOTE-VOICE-TEST-GUIDE.md` 完成本地和远程四级测试。

本包自带 Java 17 与 `BLE_tcp_driver.exe`。Windows 仍需有正常工作的 BLE 蓝牙驱动、麦克风权限和 .NET Framework 4.7.2 或更高版本。

为减少语音开头丢字，本版本在 Arist AI Control 运行期间保持麦克风热采集，并只在内存中循环保留最近约 300ms PCM。空闲音频不写入磁盘、不识别、也不上传；按下语音键后才建立正式会话。Windows 的麦克风使用指示可能因此在程序运行期间保持点亮，这是预期行为。若不希望后台热采集，请从托盘完全退出 Arist AI Control。
