# 给接手 AI 的提示词 (AI Handoff Prompt)

复制下方 prompt 直接发送给接手本项目的下一位 AI。

---

你正在接手 Xiaomi X04G / Mi Smart Clock 的 Muse Gadget 项目。这是一个将已 root 的小爱触屏音箱打造成独立 Meta Muse AI 硬件助手的成熟项目。

请先通读项目里的 [`docs/PROJECT_LOG.md`](PROJECT_LOG.md)，并根据需要查阅 `README.md`、`docs/architecture.md`、`docs/muse-protocol.md`、`docs/x04g-hardware.md` 和 `docs/recovery.md`。

### 1. 硬件与架构边界（严格约束）
- **原厂系统保留**：设备型号 `mico_x04g`（ADB 序列号 `21065C0VR35518`），保持原厂 Android 10、Kernel 4.9、vendor 驱动、系统音频与按键；**严禁刷 Linux、替换 Kernel/vendor、修改分区或恢复出厂设置**。
- **纯粹与轻量**：仅复用 Meta 官方 Muse Gadget 协议（Community Pairing v5、Noise core、WebSocket）与 Google Gemini TTS（`gemini-3.8-flash-tts` / `Leda`）。
- **用户绝对隐私原则**：**禁止持久化录音、禁止保存转写文字或聊天正文**。Debug 日志只记录时间、turn generation、状态、字节数、电平、SDK 状态码及字段存在性，绝不能输出或保存语音与文字。

### 2. 当前状态与近期已解决的关键里程碑
1. **打断后第二问回复混乱问题（已彻底解决）**：
   - 根因：原 `MuseLink.java` 在收到当前 voice note 的 `/chat/stream` ACK 前过早放行了上轮未关联 parent 的残留回复，且遗漏了 root 级 `reply_to_message_id`/`parent_message_id` 解析。
   - 修复：严格对齐上游 `muse_chat_link.c`，校验 root 级 parent 字段、阻断 pre-ACK 泄露回复、维护 FIFO 消息队列建立 note 关联。用户多轮实机测试确认第二问回复稳定正确。
2. **拾音优化（已完成）**：
   - [Voice.java](file:///home/gengyixiong/Projects/xiaomi-speaker-x04g-muse-gadget/android/app/src/main/java/io/muse/x04g/Voice.java) 切换为系统原生 `AudioSource.MIC` 规避近讲滤波；加入有理软限幅平滑抗削顶（`softLimit`）。
3. **动效与交互增强（已完成）**：
   - 触控摸摸头宠溺反馈（点击屏幕触发 `makeHappy()`：小人蹦跳、挥手大笑、头顶冒爱心气泡）；
   - 回答完毕卖萌收尾反馈（对标官方固件 `muse_voice.c` 行为）；
   - 真实音频振幅口型同步（[Speech.java](file:///home/gengyixiong/Projects/xiaomi-speaker-x04g-muse-gadget/android/app/src/main/java/io/muse/x04g/Speech.java) 实时计算 PCM RMS 振幅直接驱动口型）；
   - 屏幕左半边垂直滑动调亮度、右半边调音量（带半透明实时数值浮层）；轻触屏幕打断播放。
4. **大半身特写与 Siri 风格边缘跑马灯（已完成并实机验证）**：
   - **大半身特写（Upper Body Zoom）**：针对 800×480 宽屏截取上半身（3:2比例，720×480），贴靠屏幕最下沿，面积放大 2.5 倍以上，带半透明字幕底条；
   - **Siri 风格边缘跑马灯（Siri Edge Glow）**：按住说话键时，沿大圆角屏幕轮廓亮起柔和渐隐的极光，光晕向中央连续衰减，色带缓慢流动并轻微漂移；麦克风电平驱动平滑呼吸，思考时柔和环绕，说话时平滑淡出。Settings 可校准屏幕圆角（默认 64 px，范围 24–120 px）；
   - **设置独立开关**：长按右上角 3 秒在系统设置菜单中提供 `Upper Body Zoom` 与 `Siri Edge Glow` 两个独立开关，可实时切换。

### 3. Git 版本控制与一键回退指引
整个工作区已完全纳入 Git 版本控制。如需回退到任一历史节点，可直接检出或 reset：
- **`v0.2.0-baseline`** (Commit `505bf88`)：修复打断问题与音频优化后的全身基础版本。
- **`v0.2.1-bust-anchored`** (Commit `075b9d6`)：贴底大半身特写（未加跑马灯）稳定版本。
- **`master`** 最新 HEAD：包含大半身特写 + Siri 边缘跑马灯 + 设置独立开关。
- 回退命令：`git reset --hard <TAG> && ./scripts/build.sh && ./scripts/install.sh 21065C0VR35518`。

### 4. 关键源码地图
- [AvatarView.java](file:///home/gengyixiong/Projects/xiaomi-speaker-x04g-muse-gadget/android/app/src/main/java/io/muse/x04g/AvatarView.java)：UI 呈现层、半身像缩放、Siri 边缘跑马灯渲染、屏幕触摸与滑动手势。
- [MuseService.java](file:///home/gengyixiong/Projects/xiaomi-speaker-x04g-muse-gadget/android/app/src/main/java/io/muse/x04g/MuseService.java)：Android 前台服务、状态机控制（BOOT/IDLE/LISTENING/THINKING/SPEAKING/OFFLINE）、`makeHappy()` 宠溺状态调度。
- [MuseLink.java](file:///home/gengyixiong/Projects/xiaomi-speaker-x04g-muse-gadget/android/app/src/main/java/io/muse/x04g/MuseLink.java)：Noise 握手、WebSocket 连接、`/chat/stream` 语音上传、`/chat/subscribe` 事件关联与过滤。
- [Speech.java](file:///home/gengyixiong/Projects/xiaomi-speaker-x04g-muse-gadget/android/app/src/main/java/io/muse/x04g/Speech.java)：Gemini TTS SSE 请求、AudioTrack 播放、实时 PCM 振幅测算、字幕同步。
- [Voice.java](file:///home/gengyixiong/Projects/xiaomi-speaker-x04g-muse-gadget/android/app/src/main/java/io/muse/x04g/Voice.java)：AudioRecord 录音、MIC 软限幅算法、分块 base64 发送。
- [MainActivity.java](file:///home/gengyixiong/Projects/xiaomi-speaker-x04g-muse-gadget/android/app/src/main/java/io/muse/x04g/MainActivity.java)：全屏 Activity、物理按键调度、光感与亮度/音量调节、Settings 菜单弹窗。

### 5. 构建与部署常用命令
- 编译 APK：`./scripts/build.sh`
- 安装到设备：`./scripts/install.sh 21065C0VR35518`
- 查看设备实时日志：
  `~/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb -s 21065C0VR35518 logcat -d -s MuseX04G:V`
- 屏幕截图检查：
  `~/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb -s 21065C0VR35518 shell screencap -p /sdcard/s.png && ~/.local/share/mise/installs/android-sdk/23.0/platform-tools/adb -s 21065C0VR35518 pull /sdcard/s.png /tmp/s.png`

---
