# X04G Android实施设计

按Phase 0→4实现并部署到真实X04G；实机证据见硬件报告，维护命令见恢复文档。一个APK负责Muse和硬件API，一个小型root监督程序负责启动、恢复与独立硬件逃生。

## 最少组件

一个Java原生Android APK：一个Activity、一个foreground `MuseService`、一个JNI库。Java直接使用系统API，不引入Compose、WebView、Python运行时、chroot或独立Muse网络daemon。

JNI编译官方C++ `noise_core` 和官方C `muse_pixel`；Android服务拥有音频和网络，Activity只渲染和显示Settings。appliance阶段另用一个小型Magisk模块提供启动入口、root监督/逃生与systemless keylayout覆盖；它不运行第二套Muse连接。

| 官方部分 | 选择 |
|---|---|
| Noise XX、framing、HTTP service envelope | 原样编译官方noise_core，JNI薄封装；使用官方PsaCryptoBackend和mbedcrypto |
| Community Pairing v5 | 按官方Linux实现用Java JCA移植；P256/AES-GCM/SHA/HMAC由平台提供，保留协议边界检查 |
| BLE服务 | Android BluetoothGattServer/advertiser替换BlueZ和ESP-IDF；UUID和chunk格式保持官方 |
| API/token刷新、register、reconnect | 移植Linux逻辑，执行器/文件操作无需复制 |
| voice note、subscribe、消息关联、generation cancel | 移植ESP32语义，替换FreeRTOS队列与缓冲；没有重写私有协议 |
| 录音/播放 | AudioRecord录音，Gemini API合成 + AudioTrack播放；没有ALSA/I2S或vendor驱动适配 |
| Avatar renderer | 同一muse_pixel.c + muse_pixel.h，通过JNI；不复制LVGL UI |
| UI/Settings/字幕 | Android View/Canvas/TextView，原生字体处理CJK；重写小型平台层 |

相比纯Java重写Noise，JNI保留官方密码和wire codec，避免另做protobuf与X25519实现；相比native daemon，APK可以直接用Android音频、BLE、sensor与window API，省去进程间音频桥。C++ core本来不持有socket，足够自然地复用。

运行依赖仅：Android系统API、OkHttp及其必要传递依赖（WebSocket/TLS）、mbedcrypto静态库、官方Noise/Avatar源文件。Android TLS仍由网络库和系统信任库处理。编译工具是JDK17、已有Gradle/AGP、NDK/CMake；不把这些装到音箱。

## 连接和音频

一个串行网络worker拥有一个Noise session，承载link-control、chat-subscribe和当前chat请求。Callbacks投递到worker，JNI状态不被多线程同时写。采用官方指数退避和token refresh；Wi-Fi恢复触发重连，网络失效只进入OFFLINE。沿用官方录音/等待回复的阶段边界：录音提交之前不接纳助手回复；实机有些订阅消息不带parent，因此在内存保留前一轮的有限message ID，避免取消后的旧消息被新turn播放。

`AudioRecord`用PCM16/16kHz/mono，按运行时device type选择内置mic，不写死ID。KEY DOWN立即打开capture，UI LISTENING；有限内存队列边录边传voice note。KEY UP立即stop，提交最后一块和请求尾，进入THINKING；队列满或录音失败就结束该turn，不能静默丢音后假装成功。15秒上限先沿用官方。

官方回复只含文字。订阅流逐段更新字幕，不显示输入STT。Speech现在通过已有OkHttp在后台调用`gemini-3.8-flash-tts`，音色Leda；SSE音频delta为24kHz单声道PCM16LE，直接送入AudioTrack。保留原update/stop/busy/close入口及文字去重位置；PTT同时取消HTTP、清空播放缓冲，并丢弃旧generation回调。无Google GenAI SDK、音频临时文件或转码。Key由现有`.secrets/`目录经Gradle提供为build资产，配置入口见README。

新按下先停止当前播放/本地TTS、清字幕、递增generation，再reset旧chat stream并立即开始新capture。不能等待服务端ack才停声音。引用官方cancel语义；不承诺源码不存在的远端agent任务停止。

只把当前turn的录音块和文字放内存。结束、取消、断线均释放。持久化仅身份、token及用户设置；`allowBackup=false`，日志只记录状态、计时、字节数和错误码。自定义Avatar的生成源码是用户明确需要的显示资产，不保存生成聊天记录。

## 物理按钮和安全取消Mute

原厂映射把Linux248变成MUTE，Activity收到事件后仍有原厂静音图标。已备份原文件，通过新Magisk模块systemless覆盖pmic keylayout，仅将248改为F10，114保留VOLUME_DOWN。重启后InputReader使用该覆盖，APK收到F10/140、scan248的DOWN/UP；录音路由内置mic，开始和结束muted=false。数字增益×4后首次理解正确，现按用户反馈将默认值设为×5，正常问答及打断后的新问答均获用户确认。

随后用 `dispatchKeyEvent` 处理PTT、AudioManager处理volume。是否必须调用应用的显式音量调节由实机决定，避免框架和应用各调一次。范围通过getStreamMaxVolume读取，按本机0..100做约5%步进。

当前无需EVIOCGRAB、禁用Assistant Core或改HAL。原厂system文件从未直接改写。

紧急逃生独立于UI事件循环：root监督程序按实测input名称定位键盘，每100ms通过EVIOCGKEY读取当前按键位图，不grab。Volume+/-同时持续5秒即置维护标记、force-stop Muse并启动Android HOME。时钟用CLOCK_BOOTTIME；am命令在有10秒超时的子进程执行，主循环继续读取按键。实测SIGSTOP暂停整个APK后，用户仍成功返回Android桌面。Android app的双键监听只是辅助。

## Avatar、字幕与显示

JNI调用官方renderer，以RGB565 Bitmap在Canvas显示；可直接输出约320/384像素画面并以nearest-neighbour放大，保留官方像素网格。不使用大型图形引擎。Canvas重写会丢掉既有颜色/表情与生成C文件兼容性，单独移植renderer又需要复制一千行逻辑，所以两者都不选。

配对后通过官方prompt向用户Muse请求renderer，电脑编译检查，再ADB更新APK。无Avatar则保留默认；设备不能访问未配对账户的profile。当前使用官方默认renderer。原生idle继续随机眨眼/双眨眼、gaze、呼吸、轻微bob、手臂摆动、光环与星光。IdleMotion只用一个计时器随机选择六种已有pose：happy跳跃、OFF挥手、THINKING托腮、SPEAKING摆手踏步、LISTENING抬手和BOOT弹起；每次1.6–3.2秒，之后休息8–20秒，避免连续重复。实际Service仍为IDLE；真正录音/回复/断线/错误立即优先。挥手时OFF的mode_t固定为零，保留摆臂而不渐隐；ERROR不用于随机待机。没有新增角色或游戏引擎。

背景黑色是官方C_BG调色板及Android View的默认值，不是Android限制；非黑背景需要同时修改/合成renderer底色，否则RGB565头像会留下黑色方框。此次按恢复官方原版的要求保留黑色。

Activity有BOOT、IDLE、LISTENING、THINKING、SPEAKING、OFFLINE、ERROR。OFFLINE复用BOOT动态画面，加离线状态；官方OFF是消失动画，不用于常驻断线。真实麦克风RMS驱动LISTENING，TTS首次音频驱动SPEAKING；本次保留原有随时间变化的嘴型动画，不改动Avatar行为。

原生TextView/StaticLayout处理UTF-8、CJK、标点和换行，下方分段/滚动显示助手回复，角色表情留足空间。结束保持4秒后清空，回IDLE。TTS字幕按句更新；没有官方音频word timestamps，不能声称逐字精确同步。

landscape和Android10 immersive sticky隐藏bars，窗口 `FLAG_KEEP_SCREEN_ON` 保持亮屏。优先不改全局screen timeout。

已发现TYPE_LIGHT。Android listener显示当前lux；auto可先沿用设备已有AutomaticBrightnessController的平滑调节，window亮度设默认。手动slider使用window `screenBrightness`，auto off不需要改系统brightness设置。若原厂自动亮度实测不合适，再用简单lux映射、平滑和阈值驱动同一window属性，并保留可校准亮度范围。没有可靠读数就转手动，绝不修kernel。

Touch只接受右上角固定区域约3秒长按；移动超阈值、离开区域或抬手立即取消。其他touch不PTT、不调音量。Settings提供Muse状态/identity/VM/重连/服务重启、Android Wi-Fi入口、lux/自动/手动亮度、volume/mic/speaker、版本/IP/uptime、UI重启/设备重启/返回Android；重启设备和退出常驻确认一次。

## 自启动和watchdog

单个Magisk模块`/data/adb/modules/muse_x04g/service.sh`等待boot_completed再等30秒，启动唯一root监督程序；监督程序拉起foreground MuseService和Activity。原有service.d与Home均保留，不增加BOOT_COMPLETED/Device Owner/init冗余启动。

同一个root监督程序检查app私有heartbeat：UI每2秒更新、超过15秒重新拉起；串行Muse worker每5秒更新、超过45秒force-stop后重启服务和UI。恢复至少间隔15秒，flock阻止重复监督进程。网络故障由MuseService自身重连，不触发Android reboot；没有第二个网络watchdog。

维护/escape标记优先于自动恢复；退出后不会立刻被watchdog抢回全屏。启动Settings Wi-Fi页面时暂时停止UI重拉起，服务继续连接；回到Muse恢复正常监督。重启后正常启动，ADB可设置持久维护标记用于长时间调试。

## 按阶段推进和最小验收

| Phase | 输出和一个实际检查 |
|---|---|
| 0 | 只读报告；真实按钮DOWN/UP和已有配置，不安装应用 |
| 1 | 简单状态页+BLE配对+Noise/register/subscribe；iPhone完成一次配对，X04G发一条明确测试消息并收到当前回复，然后手机不参与的重连 |
| 2 | 中间按住录音/松开提交；一次真实问答与新press打断。使用API验证mic/speaker，不上ASR测试框架 |
| 3 | 官方renderer JNI、状态、字幕、idle；看一遍实机，不写像素单元测试 |
| 4 | 自启动、监督、亮度、Settings和逃生；一次实际重启、一次app故障、一次双键逃生、一次网络恢复 |
| 5 | 确认无会话残留、重复worker和多余依赖；记录回滚 |

配对移植保留一个官方v5 fixture兼容检查，JNI用官方Noise harness/真实握手检查。足以防止协议字节错误，不开展全方位安全、极端网络或长时间stress测试。

## 实际风险

1. 官方不提供回复音频、没有独立server-run cancel；这是源码能力限制，不能靠Android适配声称完成。
2. BLE peripheral已实测通过iPhone配对；配对调试版补全Android GATT读/订阅响应并记录握手步骤，完整握手和provision已通过；没有单独隔离早期Connecting卡住的原因。
3. 原mic信号较小，用户已将增益从×4调到×5并确认新问答正确；启动按键瞬态有少量削波，Settings保留×1..16校准。没有改变vendor采集增益或驱动。
4. 已有Launcher、自启动和屏幕edge overlay可能冲突；不删除组件，部署前备份，按实际症状做最小调整。
5. ARM32、约1GiB RAM、/data余511MiB：只编armeabi-v7a，限制buffer，不装SDK/编译器到设备。

待机调度只有一个可运行检查，不需要Android或测试框架：

```sh
javac -d /tmp/muse-idle-check android/app/src/main/java/io/muse/x04g/IdleMotion.java
java -ea -cp /tmp/muse-idle-check io.muse.x04g.IdleMotion
```

检查六种pose的选择、动作之间的休息、PTT优先及挥手不进入关机渐隐。
