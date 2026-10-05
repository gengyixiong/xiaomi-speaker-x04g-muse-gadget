# Muse X04G 项目日志与当前状态

**整理日期：2026-10-05。** 这是交接时的状态索引；技术细节与原始设备检测结果以本文件列出的代码、文档和忽略提交的诊断档案为准。只记录必要元数据，不收录 API Key、SDK token、配对凭据、语音录音、识别文字或聊天正文。

## 目标和硬件边界

将已 root 的 Xiaomi X04G / Mi Smart Clock 变成独立 Muse Gadget：保持原厂 Android 10、Kernel、vendor 和硬件驱动；Android APK 使用系统 mic、speaker、display、touch、Wi-Fi、BLE、环境光传感器和按键。只用 Muse 官方设备协议和 UI 概念；不刷 Linux，不用容器或第二个 Muse daemon。

设备 USB ADB 序列号 `21065C0VR35518`，Android 型号 `mico_x04g`。只读检测和设备细节在 [x04g-hardware.md](x04g-hardware.md)；架构与原计划在 [architecture.md](architecture.md)；协议分析在 [muse-protocol.md](muse-protocol.md)；系统修改备份、恢复及逃生在 [recovery.md](recovery.md)。

## 当前状态

- 设备：最近一次读取为 Android 10、`sys.boot_completed=1`；APK 当前运行于 PID 3980，Muse 已自动注册，Android 前台 Activity 是 `io.muse.x04g/.MainActivity`。这些是该次检查的快照，不保证设备之后一直联网或在线。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`；application ID `io.muse.x04g`，version `0.2.0`，只编译设备需要的 `armeabi-v7a`。
- 正常交互：按住顶部中间键开始录音，松开提交 Muse voice note。PTT 的 KEY DOWN 会立即停止 Gemini TTS 和旧本地播放；Leda TTS、音量键、默认官方 pixel Avatar、随机 idle 动作、Settings、启动监督及双音量键逃生已有实机运行记录。
- **当前待解决问题：**用户报告第一次问答正确，打断后第二问却被 Muse 说成“没有转写出来”或回复混乱。用户在 iPhone Muse App 回听该第二条 voice note，确认录音清楚完整。这个结果支持“X04G 确实录到并上传了语音”，不代表 Muse 已正确识别/理解，也尚不能证明新旧轮次串线。
- 用户不希望持久保存语音或聊天历史。维持当前设计：语音仅当前 turn 内存/网络缓冲，完成后释放；debug 可记录 turn generation、提交结果、时序、消息字段是否存在、字节数、电平、SDK 状态码；**不要写入录音、transcription 或 agent 回复**。手机 App 已提供回听证据，先不要在 X04G 再复制一份或做两小时录音轮转。

## 开发过程和阶段结果

| 阶段 | 已完成事项及证据 |
|---|---|
| Phase 0 · 只读设备检测 | 确认 Android 10/API 29、MT8167S ARM32、800×480、root/Magisk、系统音频、BLE、Wi-Fi、light sensor 与物理按键。原始快照在被忽略的 `diagnostics/20261005T083512Z/`；细节见硬件报告。未改 Kernel/vendor 或刷系统。|
| Phase 1 · Muse SDK 连接 | 复用 SDK Community Pairing v5 和官方 Noise core；X04G 自己 Wi-Fi 连接 Muse。iPhone 首次配对后，重启及 APK 更新可自动恢复注册。官方上游固定在 `3229892e93c18a768ace42cbe1fe7133f91ca203`；mbedTLS 为 3.6.7。 |
| Phase 2 · mic/PTT | 真实按键和 AudioRecord 已连接：系统映射 248 从 MUTE 改为 F10，仅通过 Magisk systemless keylayout；DOWN/UP 作为 PTT，mic 为 16 kHz mono PCM16。数字增益由 ×4 调到用户选择的 ×5。没有修改 Kernel、HAL 或 vendor。 |
| Phase 3 · Avatar/UI | 原生 Canvas + JNI 复用官方 `muse_pixel` renderer；全屏、CJK 回复字幕、状态动画和随机 idle pose 已运行。用户选择恢复官方默认 pixel 形象；旧 Friday renderer 保存在 `avatar/custom/friday.saved.c`，不参与构建。 |
| Phase 4 · appliance | 一个 Magisk module 启动一个 root supervisor；Android 前台服务及 Activity 自启，heartbeat 看护 UI/service。实体设备上验过 Wi-Fi 恢复、进程恢复和在 APK `SIGSTOP` 时使用真实 Volume+/−五秒逃生至 Android。系统原 Home、Android、Kernel、vendor 保留。 |
| Phase 5 · Gemini TTS | 系统 TextToSpeech 替换为 `gemini-3.8-flash-tts` / `Leda`；现有 OkHttp 异步调用 Gemini Interactions SSE，24 kHz mono PCM16LE 首块直接进 AudioTrack。实机 API 模型查询 HTTP 200，播放/打断/字幕 4 秒留存/同一回复快照去重检查通过；没有音频文件，也不额外引入 SDK。 |
| Phase 6 · 打断转写修复与音频输入优化 | 对齐上游 `muse_chat_link.c` 协议：校验 root 级 `reply_to_message_id` 与 `parent_message_id`、在 ACK 前阻断预泄露回复、追踪 note ID 建立 afterNote 关系。录音输入改用 `AudioSource.MIC` 规避近讲滤波，引入有理软限幅平滑抗削顶。实机测试确认打断后第二问回答恢复正常。 |
| Phase 7 · 动效与交互增强 | 触控摸摸头宠溺反馈（happy 跳跃/弯眼笑/爱心泡泡）；回答完毕卖萌反馈；实时音频振幅计算驱动动态口型同步（Lip Sync）；屏幕左侧垂直滑动调亮度、右侧调音量并显示半透明数值反馈；轻触屏幕随时打断语音播报。 |
| Phase 8 · 宽屏贴底下半身特写与 Siri 边缘跑马灯 | 宽屏半身像放大（3:2比例裁切下半身、紧贴屏幕下沿 480p 满高，占屏 720×480，保留完整手部、爱心与气泡，带半透明字幕底条）；按键说话时触发双层 Siri 风格全边缘霓虹极光流光跑马灯（SweepGradient 旋转循环、随麦克风实时音浪脉冲呼吸、释放后平滑缓释）；Settings 中提供半身特写与跑马灯独立实时切换开关。 |
首次 X04G 问答和后续用户问答的体验反馈与合成 TTS 测试不是一回事。早期用户曾确认一次打断后的新问题回答正确；最新反馈证明不能据此认定当前多轮打断识别问题已经解决。设备日志显示正常录音提交、Muse ACK 和回复事件仅验证数据流走通，并不能检验语音识别语义。

## 当前最重要的调查方向

Gemini TTS 输入的是 Muse 已经返回的**文字**，不接收 mic，也不做 ASR；它不可能把“转写”变错。问题在 Muse voice note / Agent 路径或新旧 turn 对应关系。

从代码看，下一位维护者应优先逐一核对：

1. [Voice.java](../android/app/src/main/java/io/muse/x04g/Voice.java) 的每次 PTT 是否各自完整启动/结束一个 AudioRecord 和 WAV 请求，短 press、录音启动竞态及取消旧 recording 的路径。
2. [MuseLink.java](../android/app/src/main/java/io/muse/x04g/MuseLink.java) 中 `beginVoice`、`voiceChunk`、`cancelTurn`、`turn/submitted/acked` 的顺序；第二条 note 的 `/chat/stream` ACK 是否提供新 message ID，订阅中哪些消息属于新 note。
3. 上游 `muse_chat_link.c` 的 note ACK、`message.user` 与 reply parent 过滤。当前 Android 的 `event()` 处理字段及有限 previous-message ID 集合；不少实际 reply 日志显示 parent absent。比较 Android 和官方事件解析后，再决定是缺字段解析、订阅时序，还是服务器本身对这条录音的 transcription 失败。现有证据不足以直接认定任一根因。

追查时只记录 generation、ACK 成败、ID 是否存在/是否匹配（需要关联时用当前进程内临时编号）、event type/父字段是否存在、提交和回复时间、电平及字节数。日志不得包含 text、ID 原文、base64、audio bytes 或 token。可利用 iPhone 里已经存在的第二条录音进行一次对照；避免要求用户重复录制多轮或建立两小时持久录音机制。

## 运行依赖、构建和安装

- 官方源码：`upstream/muse-gadget-sdk/`；不要直接改 upstream。检查 `git -C upstream/muse-gadget-sdk rev-parse HEAD` 应为上表锁定 commit。
- Android 项目：`android/app/`；Java package `io.muse.x04g`，OkHttp 4.12.0 已是原有依赖，Android TTS 已不再使用。
- 构建：`scripts/build.sh`。安装普通 APK：`scripts/install.sh 21065C0VR35518`。设备已配对时此更新保留 app 私有配对。
- 只改 Magisk supervisor/module/keylayout 时才运行 `scripts/install-appliance.sh`；keylayout 更改要重启 Android。不要用它更新普通 APK。
- Gemini 本机输入脚本：Windows `setup-gemini-key.cmd` / `setup-gemini-key.ps1`；Linux `./setup-gemini-key.sh`。配置在被忽略的 `.secrets/gemini-api-key`。Muse SDK token 单独存在 `.secrets/sdk-token`。绝不把其中任何值粘贴到聊天、prompt、日志或输出；生成的 APK 包含 Gemini Key，不能分享/提交。
- 一次实机 TTS 集成检查：`scripts/check-tts.sh 21065C0VR35518`。它会调用 Gemini API、安装临时 Android test APK、测试合成播放并卸载 test APK；没有录音/STT测试。只在需要时运行，不作为当前转写问题的替代诊断。
- 工作区根目录当前**没有 Git 仓库**；`.gitignore` 已忽略 `.secrets/`、`diagnostics/`、`backups/`、`upstream/`、build 输出和 `local.properties`，但不能因此声称已经有 Git commit 或 PR。

## 近期冷启动记录与回滚

用户怀疑打断时碰到供电线。设备确实出现 cold boot；这和瞬间断电相符，但仅凭 `cold,powerkey` 不能证明物理根因。当时 PackageManager 报 missing scanned package，Muse APK 在 `/data/app` 中缺失；Muse app 私有 preferences、SDK token 和原配对数据仍在，APK 重装后 `registered with Muse`。备份文件 `backups/cold-boot-recovery/muse-private.tar` 包含敏感 app 私有数据，权限为本地用户读写；**不要解包、显示、复制到 Git 或交给其他服务**。这里保留文件路径是为需要时恢复数据。

上个已安装 APK 备份 `backups/pre-gemini/app.apk`。回滚语音应用可用：

```sh
adb -s 21065C0VR35518 install -r backups/pre-gemini/app.apk
adb -s 21065C0VR35518 shell am start -n io.muse.x04g/.MainActivity
```

完整 Android 恢复/ADB maintenance 和退出 kiosk 的方法见 [recovery.md](recovery.md)。不要 factory reset、刷分区或删除 app 私有数据。

## 交接资料导航

1. 先读本文件，确认最新状态和当前未解决问题。
2. 按需读 [README](../README.md)、[architecture.md](architecture.md)、[muse-protocol.md](muse-protocol.md)、[x04g-hardware.md](x04g-hardware.md)、[recovery.md](recovery.md)。这些文件有更早阶段的原始记录；如与本文件“当前状态”冲突，以最新用户反馈和本文件的当前状态为准。
3. 查看代码入口 `Voice.java` + `MuseLink.java` + `MuseService.java`，按上面的元数据建议追查转写/轮次，不扩大成持久历史功能。
4. 下一位 AI 可直接使用的完整交接 prompt 在 [AI_HANDOFF_PROMPT.md](AI_HANDOFF_PROMPT.md)。
