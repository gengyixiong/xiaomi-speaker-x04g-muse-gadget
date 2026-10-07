# Muse X04G 项目日志与当前状态

**整理日期：2026-10-05。** 这是交接时的状态索引；技术细节与原始设备检测结果以本文件列出的代码、文档和忽略提交的诊断档案为准。只记录必要元数据，不收录 API Key、SDK token、配对凭据、语音录音、识别文字或聊天正文。

## 目标和硬件边界

将已 root 的 Xiaomi X04G / Mi Smart Clock 变成独立 Muse Gadget：保持原厂 Android 10、Kernel、vendor 和硬件驱动；Android APK 使用系统 mic、speaker、display、touch、Wi-Fi、BLE、环境光传感器和按键。只用 Muse 官方设备协议和 UI 概念；不刷 Linux，不用容器或第二个 Muse daemon。

设备 USB ADB 序列号 `21065C0VR35518`，Android 型号 `mico_x04g`。只读检测和设备细节在 [x04g-hardware.md](x04g-hardware.md)；架构与原计划在 [architecture.md](architecture.md)；协议分析在 [muse-protocol.md](muse-protocol.md)；系统修改备份、恢复及逃生在 [recovery.md](recovery.md)。

## 当前状态

- 设备：最近一次检查为 Android 10、`sys.boot_completed=1`；APK 当前运行于 PID 5876，Muse 已自动注册并处于 `IDLE` 状态，Android 前台 Activity 是 `io.muse.x04g/.MainActivity`。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`；application ID `io.muse.x04g`，version `0.2.0`，32位 `armeabi-v7a`。
- Git 版本控制与标签：工作区已完全纳入 Git 版本控制。
  - `v0.2.0-baseline` (Commit `505bf88`)：打断串轮修复与音频输入优化后的完整稳定基线。
  - `v0.2.1-bust-anchored` (Commit `075b9d6`)：宽屏大半身特写（贴底全高版）稳定版本。
  - `master` 最新 HEAD：包含大半身特写、按下说话键 Siri 风格全边缘霓虹流光跑马灯、设置独立开关。
- 原待解决问题（打断后第二问混乱）处理结果：**已彻底解决并经用户实机确认通过**。根因为 Android 端缺少对上游 `/chat/subscribe` 事件 root 级 `reply_to_message_id`/`parent_message_id` 的关联，且在收到 `/chat/stream` ACK 之前过早放行了上一个回复的残留事件。严格对齐官方 `muse_chat_link.c` 逻辑后，打断后第二问能稳定正确回答。
- 用户隐私原则依然严格贯彻：**不保存录音、不保存识别文字、不持久化聊天正文**。Debug 日志仅记录时间、turn generation、状态、字节数、电平、SDK 状态码及字段存在性，绝无敏感数据泄露。

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
| Phase 9 · 柔光渐隐与实屏圆角 | 将两条等宽描边替换为圆角距离遮罩 + SweepGradient，以窄亮边和宽柔光连续向内衰减；色带缓慢旋转并轻微漂移，电平及状态透明度采用平滑过渡。遮罩半分辨率缓存，仅尺寸或圆角改变时重建；Settings 提供 24–120 px 圆角校准，默认 64 px。已编译部署，实机检查通过向内渐隐、圆角裁切、硬件显示及淡出；检查未启用麦克风或 TTS。按用户选择开启流光开关。 |

## 已确认的根因修复细节与验证结果

1. **打断后第二问回复混乱的根因**：
   - 上游 Muse 服务端向 Gadget 返回回复文本前，会在 WebSocket `/chat/subscribe` 流中先收到各种状态事件（包括 `message.user`、上一轮残留事件、以及当前 voice note 的 ACK 确认事件）。
   - 原先 Android 端 `MuseLink.java` 在收到当前 voice note 的 ACK 之前，如果接收到无 parent 的 reply start，会将其错误认作是新回复而过早发给 TTS，导致上轮回复与新一轮录音发生串轮。
   - 此外，部分回复的关联字段仅位于 envelope 根级的 `reply_to_message_id` 或 `parent_message_id`，原代码只解析了嵌套在 `message` 对象下的字段。
   - **修复**：对齐上游 `muse_chat_link.c`，严格在收到当前 note ACK 后才接受回复、加入前序消息追踪队列、补全 root 级 parent 字段匹配，彻底消除了串轮现象。
2. **拾音优化**：
   - 录音源从 `VOICE_RECOGNITION` 改为 `MIC`，移除了原厂针对近讲手持电话的侵略性压制滤波，提高了远距离与快速语速的识别灵敏度；
   - 增加有理数无损软限幅算法（`softLimit`），在数字增益放大至 24000 以上时进行平滑渐进式饱和压缩，避免方波削顶破音。
3. **视觉与交互升级**：
   - 实现了完整的触控反馈系统（摸摸头、滑动手势、轻触打断）；
   - 大半身宽屏特写（Upper Body Zoom）与 Siri 风格边缘跑马灯（Siri Edge Glow）已实机验证并通过截屏确认，在设置界面均有开关独立控制。
   - 新版流光采用柔光渐隐与可校准的大圆角。更新前安装的 APK 留在本地忽略提交的 `backups/pre-edge-glow-20261005T132332Z/app.apk`；临时检查 APK 已卸载。可重复检查入口：先构建并安装 Android test APK，再运行 `adb -s 21065C0VR35518 shell am instrument -w -e check edge-glow io.muse.x04g.test/io.muse.x04g.TtsCheck`。检查保存一张无字幕的 UI 预览至 app cache，结束后需删除截图并卸载 test APK；默认恢复原流光开关，显式 `-e enable true` 可保持开启。

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

## 中间键长按硬件复位修复（2026-10-05 13:50 UTC）

用户报告按住发送键约 6–10 秒出现两次整机重启。最近一次重启前内核记录 `PMICKEYS pressed key=248`，约 7.58 秒后日志突然结束，没有 release；下次启动为 `cold,powerkey`，没有本次 Muse crash/panic 证据。设备树把中间键映射为 PMIC pwrkey/248，并配置单键长按、8 秒档；只读 regmap 确认 `MT6392_TOP_RST_MISC`（0x011A）=0x005B，bit6 `PWRKEY_RST_EN` 开启。Android keylayout 改 F10 不会关闭此硬件动作。完整本机诊断在忽略提交的 `diagnostics/ptt-reboot-20261005T134349Z/REPORT.md`。

已添加 `device/disable-ptt-reset.sh`，通过 `TOP_RST_MISC_CLR`（0x011E）写 0x0040，只清 bit6；回读 0x005B→0x001B，其他位保持不变。现有 `service.sh` 开机早期调用，`install-appliance.sh` 安装后立即应用并 sync。使用原有 supervisor 二进制，校验 SHA256 与备份一致，未更新 APK、刷 Kernel/设备树或改变双音量键逃生。

验证：shell 语法和 git diff 检查通过；脚本重复执行仍为 0x001B；脚本 `--check` 只读校验通过。用户确认长按不再重启；实机 PTT DOWN 13:50:49.323、UP 13:51:05.798，持续约 16.475 秒。录音于 15 秒上限提交 480000 字节 PCM，Muse ACK、TTS 播放、返回 IDLE 均正常，boot ID 与 Muse PID 3633 保持不变。开机 hook 已部署，但本轮没有主动重启以测试跨启动应用。模块备份与回滚步骤见 [recovery.md](recovery.md)。

## 交接资料导航

1. 先读本文件，确认最新状态和当前未解决问题。
2. 按需读 [README](../README.md)、[architecture.md](architecture.md)、[muse-protocol.md](muse-protocol.md)、[x04g-hardware.md](x04g-hardware.md)、[recovery.md](recovery.md)。这些文件有更早阶段的原始记录；如与本文件“当前状态”冲突，以最新用户反馈和本文件的当前状态为准。
3. 查看代码入口 `Voice.java` + `MuseLink.java` + `MuseService.java`，按上面的元数据建议追查转写/轮次，不扩大成持久历史功能。
4. 下一位 AI 可直接使用的完整交接 prompt 在 [AI_HANDOFF_PROMPT.md](AI_HANDOFF_PROMPT.md)。
