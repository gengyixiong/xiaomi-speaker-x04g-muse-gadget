# 升级与维护

## 运行边界与源码入口

目标设备为已 root、安装 Magisk 的 Xiaomi X04G（`mico_x04g`）：Android 10/API 29、MT8167S、约 1 GiB RAM、800×480 屏幕。实测系统仅支持 `armeabi-v7a`，内核为 4.14.141+。保留 Android、内核、vendor、驱动、原桌面和已有模块；本项目不刷分区或接管系统 Wi-Fi 配置。

已测设备存在其他 Magisk/UI 修复脚本；`device/service.sh` 等待开机完成后再延迟 30 秒启动 supervisor，避免原桌面覆盖 Muse。排查自启动冲突时先检查已有 `service.d`，不要删除其他模块。

运行组件为一个原生 Android APK（`io.muse.x04g`）和一个 Magisk 模块（`/data/adb/modules/muse_x04g`）。APK 管理连接、音频和界面；模块提供按键映射、开机启动、root 监督与独立硬件逃生。

| 源码 | 维护职责 |
|---|---|
| `android/app/src/main/java/io/muse/x04g/MuseService.java` | 前台服务、状态机、字幕和互动反馈 |
| 同目录 `MuseLink.java` | Muse 认证、Noise/WebSocket、语音提交、消息关联与重连 |
| 同目录 `BleSetup.java`、`Pairing.java`、`Store.java` | BLE 配对、密码学边界、身份与凭据保存 |
| 同目录 `Voice.java`、`Speech.java` | 麦克风录音、增益/软限幅、Gemini TTS、播放与取消 |
| 同目录 `AvatarView.java`、`IdleMotion.java`、`MainActivity.java` | 角色、流光、触摸、物理按键与设置 |
| `android/app/src/main/cpp/native.cpp`、`CMakeLists.txt` | 官方 Noise/Avatar 的 JNI 封装和构建 |
| `android/app/src/main/cpp/supervisor.c`、`device/` | 启动、心跳监督、双音量键逃生、PTT 硬件复位设置 |

## 构建与本地配置

构建工具：JDK 17、Gradle 8.7、Android SDK 35、NDK 27.2.12479018、CMake 3.22.1；Android Gradle Plugin 8.6.1，OkHttp 4.12.0。原生依赖和生成产物均不提交 Git。

在项目根目录获取固定版本的依赖：

```sh
git clone https://github.com/facebookincubator/muse-gadget-sdk.git upstream/muse-gadget-sdk
git -C upstream/muse-gadget-sdk checkout 3229892e93c18a768ace42cbe1fe7133f91ca203
git clone https://github.com/Mbed-TLS/mbedtls.git upstream/mbedtls
git -C upstream/mbedtls checkout 068ff080b369adfac81509f9b57b2afabaf82dc5
git -C upstream/mbedtls submodule update --init --recursive
```

通过本地编辑器把 Gemini API Key 保存到 `.secrets/gemini-api-key`，把 Muse SDK token 保存到 `.secrets/sdk-token`；每个文件只放一行实际值，不带引号。Linux 下可先创建私有目录，再编辑文件并收紧权限：

```sh
mkdir -p .secrets
chmod 700 .secrets
# 使用本地编辑器创建上述两个文件后：
chmod 600 .secrets/gemini-api-key .secrets/sdk-token
```

Gradle 将 Gemini Key 写入生成的 APK 资产，更换 Key 需要重新构建安装；未配置时可编译，但语音回复不可用。安装脚本单独把 SDK token 写入 app 私有目录。密钥、配对数据、包含 Key 的 APK、备份和诊断输出仅留本机，不上传或分发；日志不记录凭据、录音、转写或聊天正文。

`scripts/build.sh` 默认使用本机 mise 路径，其他电脑设置 `JAVA_HOME`、`ANDROID_HOME`、`GRADLE`；ADB 脚本通过 `ADB` 指定可执行文件。下面用 `SERIAL` 表示 `adb devices` 中的目标设备序列号，先按实际路径设置环境：

```sh
export ADB="$ANDROID_HOME/platform-tools/adb"
export PATH="$(dirname "$ADB"):$PATH"
export SERIAL='替换为设备序列号'
scripts/build.sh
scripts/install.sh "$SERIAL"
```

APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。日常更新使用 `install -r` 保留配对和设置；不要 `pm clear`。Windows 可用 `gradle -p android :app:assembleDebug` 构建，再用 ADB 更新 APK；首次安装还需授予录音/定位权限并将 SDK token 写入 app 私有目录，具体操作见 `scripts/install.sh`。

新设备首次部署：先通过 `scripts/diagnostics.sh "$SERIAL"` 核对硬件和原按键映射，构建后运行 `scripts/install-input.sh "$SERIAL"`，重启使按键覆盖生效，再安装 APK 和运行 `scripts/install-appliance.sh "$SERIAL"`。后者也用于更新 supervisor 或模块文件，执行前自动备份至本机 `backups/` 与设备 `/data/local/tmp/muse-x04g-backup/`。这些脚本要求 root ADB，不能直接用于其他型号。

## 升级时必须保留的行为

- **单连接与协议边界**：一个串行 worker 管理单个 Noise session。Community Pairing v5 使用 P-256/HKDF/AES-GCM，保留 transcript、计数器、标签和分片上限检查；WebSocket 使用 VM bearer，SDK token 和 device token 各有用途，不能混用。`device_family` 保持 `homehub`，不登记 ESP32 OTA。
- **防止打断后串轮**：`/chat/stream` 返回提交 ACK，回复来自 `/chat/subscribe`。录音提交及 ACK 前不接收回复；同时读取根级/嵌套的 `reply_to_message_id`、`parent_message_id`，保留当前 note 关联和前一轮有限消息 ID 过滤。升级上游时重点对照 `esp32/components/muse/muse_chat_link.c`。
- **立即取消**：新 PTT 按下立即停止 Gemini HTTP 请求和 AudioTrack、清字幕并取消旧 stream；generation 拒绝过期回调。官方 stream reset 不保证终止已接受的云端 agent 任务。
- **音频**：`AudioSource.MIC`、16 kHz/mono/PCM16，按住录音、松开立即提交，上限 15 秒；保留 base64 三字节对齐、有限队列与背压错误处理。数字增益默认 ×5，可在设置中校准 ×1–16，并保留软限幅。麦克风 device ID 动态查询，不写死。
- **TTS**：Muse 返回文字，`Speech.java` 使用 Gemini Interactions SSE 合成（当前 `gemini-3.8-flash-tts`、`Leda`），24 kHz/mono/PCM16LE 流式播放。Gemini 不参与 STT 或 Agent；保留回复去重、取消和错误提示。字幕分页按播放进度估算，没有逐字时间戳，结束后保留 4 秒。
- **Android 原生集成**：JNI 复用官方 Noise core 和 Avatar，Bionic 下 mbedTLS 保留 `/dev/urandom` 配置，避免后续握手阻塞。音量按系统范围读取（本机 0–100）；亮度优先使用窗口 API，自动模式沿用系统控制器。
- **界面与资源**：800×480 半身特写、PCM 电平口型、触摸反馈与手势均由现有 View/Canvas 实现。流光遮罩半分辨率缓存，圆角默认 64 px、可调 24–120 px；真正的录音、回复与离线状态优先于待机动作。
- **监督与隐私**：UI/worker 心跳分别约 2/5 秒更新，超时约 15/45 秒恢复；网络故障由 APK 重连，不重启 Android。维护标记优先于监督。录音和回复只在内存中，不新增本地会话历史。

## 按键、维护模式与回滚

Magisk systemless keylayout 只把 Linux key 248 从 MUTE 改为 F10，114 保持 VOLUME_DOWN。不要直接修改 `/system`。双音量键同时按住 5 秒由 root supervisor 返回 Android 桌面，即使 APK 卡住也可使用；退出后不会立即被监督程序拉回，手动打开 Muse 或正常重启可恢复。

中间键原有约 8 秒 PMIC 长按复位与 Android keylayout 无关。`device/disable-ptt-reset.sh` 通过 MT6392 `TOP_RST_MISC_CLR`（0x011E）写 0x0040，只清 `TOP_RST_MISC`（0x011A）的 bit6，并检查其他位不变。模块开机重新应用，安装时立即应用；曾实测 0x005B→0x001B，长按超过 15 秒不再重启。开机 hook 已部署，但这一修复尚未单独做跨启动验证。

只读检查：

```sh
adb -s "$SERIAL" shell sh /data/adb/modules/muse_x04g/disable-ptt-reset.sh --check
```

持续维护（跨重启）与恢复：

```sh
# 暂停自动拉起，返回原桌面
adb -s "$SERIAL" shell touch /data/adb/modules/muse_x04g/maintenance
adb -s "$SERIAL" shell am force-stop io.muse.x04g
adb -s "$SERIAL" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME

# 恢复常驻
adb -s "$SERIAL" shell rm -f /data/adb/modules/muse_x04g/maintenance /data/user/0/io.muse.x04g/files/maintenance
adb -s "$SERIAL" shell am start -n io.muse.x04g/.MainActivity
```

模块 `maintenance` 是持续维护标记；app 私有 `files/maintenance` 是 UI/硬件逃生的临时标记，恢复前台或开机时清除。

回滚 APK：使用本机已安装版本的备份执行 `adb -s "$SERIAL" install -r 路径/app.apk` 后打开 MainActivity。也可在独立 Git worktree 检出历史版本后构建，避免丢失当前工作区；已有标签 `v0.2.0-baseline`、`v0.2.1-bust-anchored`，均早于柔光和 PMIC 修复。

保留 APK/配对，恢复原按键并取消自启动：

```sh
adb -s "$SERIAL" shell touch /data/adb/modules/muse_x04g/maintenance /data/adb/modules/muse_x04g/disable
adb -s "$SERIAL" reboot
```

禁用模块后重启也恢复原长按复位；仅删除修复脚本不会撤销当前寄存器位。若只撤销 PMIC 修复，先从本机 `backups/20261005T135019Z/` 或设备同名备份恢复旧 `service.sh`，再正常重启。重新启用模块需删除模块的 `disable` 和 `maintenance` 后重启。

完全卸载使用 `scripts/uninstall.sh "$SERIAL"` 后重启；卸载 APK 会删除配对凭据。若在配对窗口内强制停止，先重新打开 Muse 让它恢复原蓝牙名，再卸载。

已发生过冷启动后 APK 缺失、私有配对仍在的情况，文件丢失根因未确认；重新安装可恢复连接，脚本安装后执行 `sync`。本机 `backups/cold-boot-recovery/muse-private.tar` 包含敏感恢复数据，不能上传或公开。

## 最小验证与排障

按改动范围运行检查，不必每次完整实机回归：

```sh
# 构建主 APK 和实机检查 APK
scripts/build.sh :app:assembleDebug :app:assembleDebugAndroidTest

# 待机动作检查，无需设备
javac -d /tmp/muse-idle-check android/app/src/main/java/io/muse/x04g/IdleMotion.java
java -ea -cp /tmp/muse-idle-check io.muse.x04g.IdleMotion

# TTS：调用 Gemini API，检查真实播放、去重、字幕清理和取消
scripts/check-tts.sh "$SERIAL"

# 只读设备诊断与应用日志
scripts/diagnostics.sh "$SERIAL"
scripts/logs.sh "$SERIAL"
```

流光改动可运行已有 instrumentation（等待已配对设备空闲，不启用麦克风/TTS）：

```sh
adb -s "$SERIAL" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w -e check edge-glow io.muse.x04g.test/io.muse.x04g.TtsCheck
adb -s "$SERIAL" shell run-as io.muse.x04g rm -f cache/edge-glow-preview.png
adb -s "$SERIAL" uninstall io.muse.x04g.test
```

该检查覆盖向内渐隐、圆角、硬件显示和淡出，默认恢复原流光开关。涉及连接/音频时，实测普通问答及回复中打断后的第二问；涉及监督/模块时，检查开机、Wi-Fi 恢复、进程恢复和双音量键逃生。已有这些路径的实机通过记录，新的改动需针对受影响路径重新确认。

## 自定义角色与上游升级

默认编译官方 renderer；本机 `avatar/custom/muse_pixel.c` 存在时由 CMake 替换，移走后恢复默认。通过已配对 Muse 生成候选：

```sh
adb -s "$SERIAL" shell am start-foreground-service -n io.muse.x04g/.MuseService -a io.muse.x04g.AVATAR_REQUEST
# 日志出现 Avatar candidate ready 后：
mkdir -p avatar/custom
adb -s "$SERIAL" exec-out run-as io.muse.x04g cat cache/avatar-candidate.c > avatar/custom/candidate.c
adb -s "$SERIAL" shell run-as io.muse.x04g rm -f cache/avatar-candidate.c cache/avatar-result
```

生成会占用当前 turn，最长等待 15 分钟，期间不要 PTT。先审查候选仅实现官方 renderer，再用上游 `anim.c` 编译预览，通过后替换 `muse_pixel.c` 并构建安装；不要直接执行未经检查的生成 C 代码。`NO AVATAR` 保留默认，超时不代表账户无头像。

上游升级重点核对 `linux/src/musegadget/` 的配对/认证实现、`esp32/components/noise_core/`、`esp32/components/muse/muse_chat_link.c` 和 Avatar API。更新固定 commit 与本地适配，保持上游源码原样。SDK 和 mbedTLS 的授权见根目录 `NOTICE.md`；默认 Jollybot 美术不在 SDK 的 Apache-2.0 授权范围，自定义账户角色不提交 Git。
