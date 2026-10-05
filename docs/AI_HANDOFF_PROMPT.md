# 给接手 AI 的提示词

复制下方 prompt 给接手此项目的 AI。项目现状和证据集中在 [`PROJECT_LOG.md`](PROJECT_LOG.md)。

---

你正在接手 Xiaomi X04G / Mi Smart Clock 的 Muse Gadget 项目。请先通读项目里的 [`docs/PROJECT_LOG.md`](PROJECT_LOG.md)，然后根据其中的导航按需阅读 `README.md`、`docs/architecture.md`、`docs/muse-protocol.md`、`docs/x04g-hardware.md` 和 `docs/recovery.md`。处理当前问题前，实际阅读 `android/app/src/main/java/io/muse/x04g/Voice.java`、`MuseLink.java`、`MuseService.java`；不要依据交接文字猜实现。

当前目标是查明并修复 Muse 语音交互的这个问题：第一问正常；用户在语音回复期间按住中间键打断，第二条语音的录音在 iPhone Muse App 中清楚、完整，但 Muse 曾称它“没有转写出来”或给出混乱回复。X04G 仍使用 Android `AudioRecord` 录音、Muse 官方 `/chat/stream` voice note + `/chat/subscribe`、Gemini TTS 只朗读 Muse 返回的文字。已知设备 log 只能证明几条 WAV 流有发送、Muse ACK、发生回复及 Gemini PCM 播放；它不能证明 Muse ASR 正确，也没有证据能区分 Muse ASR 失败和新旧 turn 回复关联错误。

用户的持久偏好：实现简单、KISS、只复用官方 Muse SDK 已有能力、只做必要验证、不写大规模压力/极端测试。尤其用户希望尽量**不保存录音、识别文字、聊天历史**。不要新增两小时录音/聊天数据库、滚动文件、转写 log 或网络上传调试样本；使用 iPhone Muse App 已有录音，只在本进程临时检查音频链路和匿名元数据。debug 只允许记录时间、turn generation、状态、字节数、电平、API error code、message/parent 字段存在与否；不得记录原始 ID、base64、文字、语音或凭据。没有证据之前不要断言是 Chat history/context、mic gain、旧回复串线或某一段 Android 代码导致。

按这个次序推进：

1. 检查当前 X04G 是否通过 USB ADB 联机、Muse 全屏界面和服务是否已运行、当前 APK 是否安装。用户最近经历 `cold,powerkey` 冷启动；APK 曾从 `/data/app` 消失而 app 私有配对仍在，已经重装并恢复注册，但 APK 消失原因未知。不要先重启或清除 app data。
2. 对照 `MuseLink.event()` 与固定版本官方 `upstream/muse-gadget-sdk/esp32/components/muse/muse_chat_link.c`：ACK 字段、`message.user`、reply `message_id`/parent、turn submit/settle、subscription sequence。检查 Android 是不是漏解析事件根层字段、遗漏 note 与回复关联、或在新turn内接受旧的 parentless event。也检查 `Voice.start/capture/stop()`、WAV header、最终 chunk 和网络队列顺序；比较用户已确认完整的第二条录音和正常第一条的元数据。不记录音频正文或聊天文本。
3. 先指出最小、证据支持的根因和预期改动；保持原 Android 10、Kernel、设备 root、官方 Noise/BLE 和 Gemini TTS 不动。不更改 Android System TTS、Gemini、Avatar、普通 PTT 逻辑，除非证明它们与该缺陷直接相关。
4. 根因确认后再做最小代码改动，用少量状态/时序日志验证；不要实现持久音频/聊天保存。如果需要新的真实用户说话录音来判定两种互斥结果，应先使用已有 iPhone 录音和可获得的元数据，避免让用户反复重录。
5. 工作区没有 Git 仓库；不要擅自初始化 repo、提交或发布。设备序列号和 ADB、SDK、Gradle、构建、安装及回滚信息在 `docs/PROJECT_LOG.md`。读取 `.secrets/` 和 `backups/cold-boot-recovery/muse-private.tar` 时不要打印或复制敏感内容；Android Debug APK 内包含 Gemini Key，禁止分享该 APK。

项目不是 Linux migration：不得刷 Linux、替换 Kernel/vendor、改 bootloader/partition/recovery、factory reset 或删原厂组件。任何 Root/Magisk module/keylayout 修改都先查看 `docs/recovery.md` 并确认备份/回滚；普通 APK 更新用 `scripts/build.sh` + `scripts/install.sh 21065C0VR35518`。

最后清楚报告：查明了什么证据、是否确认根因、改动了哪几个文件、实机是否验证、还有什么未知。不要把“ACK/播放成功”写成“转写正确”。

---

**交接起点：[`PROJECT_LOG.md`](PROJECT_LOG.md)。当前首要工作：不保存聊天或音频，查明清楚完整的新录音为什么在打断后的 Muse turn 中没被正确转写/回答。**
