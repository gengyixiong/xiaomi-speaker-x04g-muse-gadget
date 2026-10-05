# 官方 Muse Gadget SDK 分析

研究基线：[官方仓库固定提交](https://github.com/facebookincubator/muse-gadget-sdk/tree/3229892e93c18a768ace42cbe1fe7133f91ca203)。这是本次从 `main` 拉取的最新版；后续构建固定此版本，避免开发中协议漂移。

## 配对和独立运行

参考 `linux/src/musegadget/pairing.py`、`ble_setup.py`、`ble_framing.py`、`ble_server.py`，以及 ESP32 的 `main/pairing_transcript.*`、`main/ble_setup.c`。

Community Pairing v5 的算法是 P-256 ECDH、HKDF-SHA256、AES-256-GCM。手机发送 `pairing_client_hello`；设备生成临时密钥、nonce 和逐字段规范化 transcript，返回 `pairing_ready`。双方派生两个方向的密钥和 session ID，随后用带方向、递增计数器和 AAD 的加密记录传输配置。不能只照抄几个 JSON 字段而省去 transcript、计数器或标签检查。

Linux 官方路径支持 `pairing_auth: none`、epoch 0、`confirm_app`：手机确认后，第一个加密记录必须是 `pairing_client_finished`。无需抢占 PTT 按钮来确认。ESP32 也有 `confirm_press` 路径。Android 首版采用已有 `confirm_app`。

GATT 服务 UUID 是 `7fdd3d1c-38ea-46cf-8b46-314ecf5f240c`，RX 是 `4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01`，TX 是 `d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c`。双向长消息帧为 `0xFE,index,total,payload`。通知按实际 MTU 分片、依次等待完成，保留官方大小上限。

身份持久化一次生成的本地 MAC 形随机值。沿用 `homelink-xxxxxx` node ID、`hatch-link:<mac>` device ID、`MuseGadgetXXXXXX` 广播名和 `hatch_link` 配对 model；BLE 名尾号与 node ID 尾号必须一致。这里的历史名称属于线上协议，不改名。

`provision_v2` 下发 device access/refresh token、API 地址、Noise host 等。Wi-Fi 已由 Android 配好时，复用 Linux “当前连接”条目，不接管 Android Wi-Fi 凭据。保留 `api_url_v2`；旧 `api_url` 不是新接口根地址。

配对需要用户的 SDK token，参见[官方 Linux 使用说明](https://github.com/facebookincubator/muse-gadget-sdk/blob/3229892e93c18a768ace42cbe1fe7133f91ca203/linux/README.md)。SDK token、device token、VM bearer 是三种用途不同的凭据。配对后 X04G 用自己的 Wi-Fi 和保存的 device refresh token 获取连接，手机不承担音频或网络中继。实际离线手机验收须在 Phase 1 完成。

## 认证、Noise 和 Muse Link

参考 `linux/src/musegadget/muse_api.py`、`link_client.py`、`service.py` 和 `esp32/components/noise_core/`。

`GET /fetch_vms` 用 device access token，返回默认 VM、VM ID 和 per-VM bearer。连接 `wss://<noise_host>/v1/noise?vm_id=<escaped id>`，WebSocket Upgrade 的 Authorization 使用 VM bearer，不能直接把 SDK token 放进去。握手为 `Noise_XX_25519_AESGCM_SHA256`；认证已在 Upgrade 完成，第三条 Noise 握手消息的载荷为空。

Noise 后的 HTTP 请求是官方 service envelope/framing 多路复用，不是把 HTTP 字符串简单 AES 加密。C++ `ClientSession` 不持有 socket，适合 JNI 直接复用，调用方负责串行访问与 scratch buffer 生命周期。

建立长驻 `POST /link-control`，请求 body 不关闭。发送 `link.register`；双向控制 JSON 都带 little-endian uint32 长度前缀。返回 `link.result` 对应 `link.invoke`。只登记本机实际支持的命令，不照搬 Linux 的任意 shell/file 执行功能。

沿用 `device_family: homehub`，不登记 `device.ota`。官方提醒 `link` family 会收到 ESP32 更新，Android 不能使用它。Android platform/model 是否被服务端接受需要 Phase 1 实测。

Device access token 约四小时寿命，Linux 实现在三小时刷新，调用 `POST /device_token/refresh`。Authorization 使用规范化的 `hatch_refresh:` refresh token，body 带 node ID 和 SDK token；必须原子保存新 access/refresh token。`fetch_vms` 的 401 触发 device token 刷新；Noise Upgrade 的 401/403 触发重新获取 VM 凭据，不一直重试过期 bearer。瞬时网络错误保留配对，只有确认撤销才清除。

## 语音输入和回复文字：源码中的真实能力

重点参考 [muse_chat_session.cpp](https://github.com/facebookincubator/muse-gadget-sdk/blob/3229892e93c18a768ace42cbe1fe7133f91ca203/esp32/components/muse/muse_chat_session.cpp)、`muse_chat_priv.h`、`main/voice.c`、`components/muse/muse_voice.c`。

当前编译开关是 `VOICE_NOTE=1`。注释明确说明 VM 的 `/api/voice/dictation` 当前没有 ASR 后端；不能把这个保留分支作为首版依赖。

按下时开 `POST /chat/stream` 的 JSON body，写入 audio/wav file item，再边录边发送 base64 WAV。输入为 16 kHz、mono、PCM16，WAV 使用流式未知长度头。base64 分块必须保持三个字节的对齐，不能对每个任意 PCM chunk 分别加 padding 后拼接。松开时写完剩余字节和 JSON 尾部，half-close body。由 Muse 服务端处理 voice note，不显示本地输入转写。

`/chat/stream` 响应主要是提交 ack，不是助手回复流。回复来自长驻 `POST /chat/subscribe` 的 NDJSON。处理 `delta.message_start`、`delta.text_append`、`delta.message_done`、`message.assistant`，跟踪 seq、message ID 和 parent/reply-to ID，只接收当前 turn 的回复。最新提交专门加强了无关聊天回复的过滤。

没有统一的明确 turn-end 事件。官方等待消息完成、无新事件约三秒，并参考 `agent.status`/`task.status` busy 状态及超时。Android沿用这个边界，不自行发明协议事件。

**官方没有真实的回复音频。** `start_tts()` 将 `silent` 置 true，`pace_silently()` 写零值 PCM 来控制字幕阅读节奏。MP3 缓冲和解码器是扩展挂点、bench 音频是内置测试素材，都不是 Muse 实际返回的语音。首版官方路径可以实现 voice note + 文字字幕；如果选择 Android 自带 TTS，必须明确这是本地朗读，音色与 Muse App 不同，不是 Muse streaming audio。

`main/voice.c` 原版在松开后额外采集 250 ms，UI voice 路径也保留短 tail。用户要求立即停止，所以 Android KEY UP 立即 stop，不照搬这个 tail。原版默认 15 秒上限、约 300 ms 最短有效输入，先保留作为已知录音边界。

## Barge-in / cancel 的准确语义

`voice.c::reply()` 检测新 press 后，先 `muse_hatch_turn_cancel()`，再停止 player，立即进入新录音。JNI/Java 适配可以复用这段控制顺序。

`muse_hatch_turn_cancel()` 增加 generation，清空音频输出，再发送取消命令。`turn_finish()` 给 dict/chat/TTS streams 发官方 `ResetCode::Cancelled` 并回到 idle。旧 generation 的事件被丢弃。

这是当前 turn 在设备端的取消与协议 stream reset；源码没有独立的服务端 agent-run cancel API。提交已经被服务器接受后，不能承诺 reset 必定停止 Muse 内部推理或撤回云端聊天。必须保证本机立即停音、清字幕、不让旧回复污染新 turn；不能把这个限制掩盖为完整服务端任务取消。

## Avatar 和自定义 Muse Avatar

参考 [AVATAR_RECIPE.md](https://github.com/facebookincubator/muse-gadget-sdk/blob/3229892e93c18a768ace42cbe1fe7133f91ca203/esp32/tools/muse/AVATAR_RECIPE.md)、`avatar.py`、`avatar_prompt.md`、`esp32/avatar/muse_pixel.c`、`muse_pixel.h`。

不是下载手机里的单张 Avatar 图片。官方工具通过已配对设备的 typed chat，把 prompt 和当前 renderer 交给用户 Muse。Muse 查询 profile/persona/files 中的自身 Avatar，返回新的完整 `muse_pixel.c`。如果找不到则回复 `NO AVATAR`。工具备份旧 renderer，检查编译、渲染动画、必要时请求修正，然后重新构建和安装。

Android保留这个 prompt、C renderer API 与生成流程，只替换 ESP32 serial/flash 传输为 APK 的 ADB 开发入口和 APK 更新。生成在电脑上编译，不能直接在设备运行返回的未验证 native 源码。生成请求是一次用户明确需要的开发操作；原文回复仅作临时生成输入，不作为本地聊天历史保存。

Renderer 的依赖是标准 C/math 与 Muse mode/pose 头文件，64×64 palette grid，RGB565输出，最大放大至512像素。JNI可以直接编译同一源文件；不用带 LVGL，也不重写角色绘制代码。

默认 renderer 已有呼吸、随机眨眼/双眨眼、随机视线、body bob、手脚运动、aura、sparkles以及每种状态动画。`happy` 是现成开心跳跃参数，可低频随机触发，不需要游戏引擎。官方 mode 有 BOOT/IDLE/LISTENING/THINKING/SPEAKING/ERROR/OFF；OFF是关机渐隐，不能拿它当长期OFFLINE，否则角色会消失。OFFLINE先复用BOOT的持续动画并加断线状态文字。

默认 Jollybot美术不在 Apache 2.0授权范围；保留上游版权说明，不重新标成 Apache。用户自定义 renderer 单独留在本机，不提交 profile生成内容。

## 重连

Linux service使用2、4、8秒等指数退避，最高60秒；健康连接30秒重置退避。WebSocket ping约20秒。ESP32 voice session自身也有5到120秒自动重连、20秒ping和60秒无数据检测，闲置十分钟主动断开。

Android只采用一套连接：一个foreground service内的一个串行网络执行器，单个Noise session承载control、subscribe和当前chat stream。这是基于官方多路复用能力的适配设计，须通过Phase 1确认同一session的实际服务端行为。Android网络回调负责及时唤醒重连；断线中止当前turn，清内存，不重启整个Android。无需复制ESP32用于省电的idle-close。

## 本机自定义Avatar结果与更新

2026-10-05实测：设备经官方typed chat工作流发送原版avatar_prompt.md及默认muse_pixel.c，Muse流式返回完整C文件，注释声明账户头像Friday（红金机甲、奶油色身体）。保存的是显示资产C源码，不保存生成对话。主机使用官方anim.c正常渲染，cc -O2 -Wall -Werror通过；NDK构建并ADB更新后，实机显示Friday。`avatar/custom/muse_pixel.c`是本机资产，目录不提交Git；存在时替代默认renderer，删除/移走后构建回退官方默认。CMake跟踪该可选文件的出现和消失。

重新按当前Muse profile生成（会占用当前turn，不要同时PTT）：

```sh
adb -s 21065C0VR35518 shell am start-foreground-service -n io.muse.x04g/.MuseService -a io.muse.x04g.AVATAR_REQUEST
scripts/logs.sh 21065C0VR35518
```

日志只打印状态和代码字节数。等到Avatar candidate ready后，取回候选：

```sh
mkdir -p avatar/custom
adb -s 21065C0VR35518 exec-out run-as io.muse.x04g cat cache/avatar-candidate.c >avatar/custom/candidate.c
```

先检查源码仍只实现官方pixel renderer，再使用官方anim.c编译/预览，通过后替换 `avatar/custom/muse_pixel.c`、运行 `scripts/build.sh` 与 `scripts/install.sh`。不在设备直接执行回复C代码。若Muse明确返回NO AVATAR，保留默认；超时不等于账户没有Avatar。当前生成等待上限采用官方typed turn的15分钟，避免按普通voice响应超时提前取消。

## 本机打断适配的补充

真实订阅中的部分回复没有parent字段。除了官方ack/parent过滤，Android在录音提交之前忽略回复，并在内存保留前一轮有限message ID，排除取消后的旧消息；不保存正文或ID历史。停止播放使用Speech.stop取消Gemini请求并清空AudioTrack缓冲，generation拒绝旧回调；远端agent任务是否停止仍受上面的官方协议限制。Gemini仅合成Muse已经返回的文字，不参与Agent或STT。

后续用户选择恢复官方默认形象：Friday源码移至avatar/custom/friday.saved.c，CMake不再选中它。随机待机动作仅复用上述官方renderer的mode/happy参数；保持协议状态为真实IDLE，不发送模拟聊天/录音。后台网络与音频无改动。
