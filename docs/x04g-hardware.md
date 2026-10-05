# X04G 只读硬件检测

检测时间：2026-10-05，USB ADB，目标 `mico_x04g`。原始快照：`diagnostics/20261005T083512Z/`。只读查询已执行，没有安装APK、改settings、禁用组件或重启设备。

| 项目 | 实际结果 |
|---|---|
| Android | 10，API 29，原厂fingerprint版本1.7.5 |
| CPU/ABI | MT8167S，4个ARM Cortex-A35核心；系统仅 `armeabi-v7a,armeabi`，不能安装只含arm64库的APK |
| Kernel | 当前运行4.14.141+，armv7l；保持此kernel |
| RAM / data空间 | MemTotal约980 MiB；/data所在文件系统余约511 MiB |
| Root | ADB shell已为UID0；`su`可用；Magisk26.1/26100 |
| SELinux / boot | 已是Permissive；verified boot orange；本项目不修改这些状态 |
| Display | 实际800×480，55.57Hz，density240，自然方向landscape |
| Touch | `mtk-tpd`，目前 `/dev/input/event2`，ABS范围800×480 |
| Volume输入 | 实际Volume+来自 `mtk-kpd`/event0；实际Volume-来自 `mtk-pmic-keys`/event1；两者均有DOWN/UP |
| 中间键输入 | `mtk-pmic-keys`，目前event1，声明KEY_MICMUTE与KEY_VOLUMEDOWN |
| Keylayout | InputReader实际使用 `/system/usr/keylayout/mtk-kpd.kl` 和 `mtk-pmic-keys.kl` |
| 中间映射 | pmic文件只有 `key 114 VOLUME_DOWN`、`key 248 MUTE`；live capture确认KEY_MICMUTE DOWN/UP，持续约3.19秒 |
| 麦克风 | AudioPolicy列出Built-In Mic；PCM16，8000/16000/32000/44100/48000Hz；不能把运行时device ID固定为5 |
| Speaker | AudioPolicy列出Speaker，主输出48kHz/stereo；通过Android mixer适配应用采样率 |
| 音量 | 实测STREAM_MUSIC范围0..100，当前38；不能照普通手机假设0..15 |
| Light sensor | MTK `LIGHT`，type5/on-change；SensorService有224、225读数；Android自动亮度控制器已订阅 |
| Other sensor | MTK PROXIMITY/type8；本项目无需接管 |
| Wi-Fi | wlan0已连接，Android标为VALIDATED；网络凭据由Android持有 |
| Bluetooth | ON，支持BLE；Android advertiser和GATT已通过iPhone真实配对 |
| TTS | 已有GoogleTtsService；默认synth设置为空，离线中英文voice可用性未验证 |
| 当前Home | `com.gengyixiong.codexmeter/.MainActivity` |
| Device Owner | 没有活动Device Admin；无需重建Device Owner |
| 屏幕休眠 | screen_off_timeout=2147483647，当前有WindowManager亮屏wake lock；不能靠这两个既有状态代替应用自身keep-screen-on |
| 系统时间 | 本轮读取已同步到2026-10-05 UTC；timezone为GMT，不为项目改系统时间 |

## 现有改造和冲突点

设备已存在Magisk模块 `litegapps_lx04`、`lx04_ui_fix`。不能把这台设备当成完全未修改的原厂系统。

`/data/adb/service.d/lx04-ui-fixes.sh` 在boot_completed后等待8秒，处理屏幕、setup wizard、VirtualSoftKeys、Assistant Core以及Home启动；它的目标Home与当前实际Home不一致。Muse自动启动必须在这个脚本之后进行，避免两个Home在启动时争抢。

`lx04-hardware-buttons.sh.disabled` 是已禁用的旧硬件按键脚本。它直接读event0/event1，并用binder切换mic mute。当前不启用、不复用其中的硬编码transaction。需要确保部署Muse时没有旧worker同时处理按键。

Assistant Core仍运行，有屏幕边缘overlay；实际会不会消费MUTE或直接监听底层key尚未证实。Volume也不能只凭KeyEvent名称判定框架一定调音量。

`disable-rootshell.sh` 是用户已有脚本，保留；本次不改ADB/root入口。

## Phase 0结束时待验证项

- 三个物理按钮的DOWN/UP已确认，证据 `diagnostics/buttons-tty.txt`；双音量同时保持5秒仍待逃生阶段验收。不能用 `input keyevent` 注入代替物理按键验收。
- APK内 `dispatchKeyEvent` 是否收到中间键；remap后原麦克风静音是否停止、是否有HAL直接静音。
- Android `AudioManager.getDevices`、`isMicrophoneMute`及 `AudioRecord` 16kHz/mono的真实初始化和信号；Phase 2才录音，不在只读阶段偷偷录制。
- `SensorManager.TYPE_LIGHT` 实际listener读数与遮挡/恢复变化；SensorService已有读数证明HAL暴露，尚未证明长期可靠。
- `BluetoothAdapter.isMultipleAdvertisementSupported`、advertiser和GATT server；系统BLE feature不等于peripheral必然可用。
- 常亮/亮度window API、启动后自动恢复、真实Muse认证，以及拔开手机后的独立重连。

这些未确认项会按阶段实测，不开展全系统压力、安全或极端情况测试。

## Phase 1 API实测补充

安装独立APK后的运行结果：官方Community v5 fixture（transcript、ECDH/HKDF、AES-GCM client-finished）通过；`BluetoothAdapter.isMultipleAdvertisementSupported()`为true，GATT server已启动并成功广播 `MuseGadget214A4E`。Android AudioManager返回内置Speaker/type2、Built-In Mic/type15及BUS/type21；16kHz输入采样率由运行时API再次确认，mic muted=false，media volume=38/100。SensorManager能找到TYPE_LIGHT。尚未开始录音。

## Phase 1实机验收（2026-10-05）

MuseGadget214A4E通过iPhone完成Community v5握手、wifi_scan/current connection与provision_v2。设备保存私有凭据，Noise XX成功，link.register通过，chat/stream测试收到“X04G连接成功”。更新APK后自动重新注册，无再次手机配对。Bionic下mbedTLS默认/dev/random使第二次握手阻塞；仅在APK构建中配置MBEDTLS_PLATFORM_DEV_RANDOM=/dev/urandom，保持官方协议源码不变，新进程恢复正常连接。

## Phase 2进展

原映射MUTE以KeyEvent91/scan248完整传给Activity，AudioRecord已实际录得约3秒PCM，Muse确认请求，Android Google TTS朗读可用。但用户反馈Muse听不清，原厂静音图标仍出现，因此尚不能认定麦克风输入通过。已安装仅含pmic keylayout的muse_x04g模块（248改F10，114保持VOLUME_DOWN），备份见恢复文档。重启后读取已确认覆盖生效，Muse保存凭据仍可恢复注册；声音输入和取消原Mute行为等待第二次物理验证。

重映射后实测：KeyEvent140/F10，scan248，约6.4秒PCM提交204800bytes，路由type15、录音开始和结束muted=false，TTS再次朗读回复。重启后原桌面延迟覆盖Muse，手动am start恢复前台；Phase 4监督必须处理这一启动竞争。用户在Muse App听过上一段录音，确认上传成功但声音很小，正在校准数字增益与平均RMS，不应将听不清归结为token/配对失败。

数字增益×4后用户确认Muse理解正确。该turn PCM87680bytes，持续讲话原始RMS约125（16bit），确实偏小；削波170samples/43840，主要需观察按下启动瞬态。这一阶段暂保留×4，Settings保留×1..16校准滑条；后续根据用户反馈调整为×5，见下文。官方C renderer已在800×480全屏显示，应用无WebView。

## Phase 3/4实机验收（2026-10-05）

- 官方muse_pixel C renderer通过JNI在原生Canvas持续渲染，25fps、RGB565；800×480截图已检查，全屏隐藏系统栏，Avatar居中，字幕空间在下方。
- 一个root监督程序已部署；kill -9 APK后自动恢复服务和UI，保存的Muse凭据重新注册成功。没有增加第二个网络daemon。
- 为验证独立逃生，SIGSTOP暂停整个APK，并临时抑制watchdog重拉起；用户同时长按真实Volume+/−五秒，确认回到Android桌面。root监督仍运行、APK已force-stop，维护标记生效。验证后已清除临时维护标记。
- 10:11 UTC开始一次真实reboot，没有手动启动APK。boot完成后监督程序自动启动，10:13:25 Muse进程创建，10:13:31注册成功并进入IDLE；窗口焦点MainActivity，UI/service heartbeat正常。原Home保持不变。
- 10:14:23关闭Wi-Fi，IDLE→OFFLINE；恢复Wi-Fi后10:14:38自动注册，OFFLINE→IDLE。APK PID始终3558，没有重启Android或另起连接进程。
- 右上角三秒长按实测打开Settings。Android SensorManager listener实际得到8.0 lux，SensorService显示APK的LIGHT订阅；设备自带AutomaticBrightnessController继续负责平滑自动亮度，窗口默认亮度。手动slider和auto开关已实际操作，最后恢复auto。未修改系统亮度模式（仍为1）。
- Google TTS已实测朗读中文，Settings显示Android TTS ready；原蓝牙名MiSmartClock9183已恢复。APK此时RSS约95MiB，/data余约481MiB。

10:18—10:19真实打断时，按下后约42—49ms从SPEAKING进入LISTENING；用户确认旧声音立即停止，但第一轮新问题曾被Muse理解成咖啡相关问题。按用户建议将增益从×4改为×5；10:25—10:26对照普通算术问答/数数途中打断，用户确认新问题回答正确。采集日志分别为rawRms125/83/74、gain5，提交音频仅内存流式传输。回复有些不带parent字段；适配补上官方提交前忽略回复的阶段判断，并在内存过滤上一轮已知message ID。不能把原先的错误全部归因于录音增益。

本轮时间证据：10:26:23.640松开、24.128请求ack、29.980新回复开始、30.282首句进入TTS、30.592实际播放。之前一次TTS首次中文播放等待约15秒，此次约0.3秒；没有为此替换引擎或增加云API。

自定义Avatar：10:29:12通过已配对设备发送官方prompt+默认renderer，13.400 ack；34.919开始流式返回代码，59.470完整候选就绪（约41.7KB）。Muse声明账户角色Friday，红金机甲、奶油色身体。使用官方anim.c、host cc -O2 -Wall -Werror编译与普通状态渲染，再通过Android NDK编译；10:33部署，MainActivity显示新角色并自动连接IDLE。截图已检查。没有运行ASAN、压力或全系统安全测试。

Phase 5：本项目只有一个APK进程与一个root监督进程；app files仅SDK token及两个heartbeat，preferences仅配对/设置，cache中的头像候选取回后删除。没有录音、STT、字幕或聊天历史文件；原蓝牙名已恢复，维护测试标记已清理。

## 默认Avatar与待机动作调整

按用户要求恢复官方esp32/avatar/muse_pixel.c，Friday源文件留为friday.saved.c备份。已编译并ADB更新；保存的配对恢复，10:48:06进入IDLE。截图已确认恢复奶油色官方角色，待机中出现抬手动作。六种动作调度的单个Java self-check通过（随机选择、休息、真实状态优先、挥手不渐隐）；没有新增Android系统修改。
