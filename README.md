# Muse X04G

保留 Xiaomi X04G 当前 Android 10、Kernel 和驱动，适配 Meta 官方 Muse Gadget SDK。

已适配：独立Muse连接、按住中间键录音/松开提交、Gemini TTS回复、官方C Avatar全屏动画和字幕、隐藏Settings、常亮/亮度、开机自启及一个root监督程序。打断后第二问串轮与转写问题已彻底修复并经实测通过；麦克风录音升级为系统原生MIC并带有理软限幅平滑抗削顶；界面新增触控摸摸头宠溺反馈、说话真实口型同步、回答完毕卖萌、屏幕边缘左右滑动调光调音、轻触打断；适配 800×480 贴底全高半身特写（Upper Body Zoom）与按键说话时的 Siri 风格全边缘霓虹极光跑马灯（Siri Edge Glow），支持在设置菜单中无缝独立切换。工作区已完全纳入 Git 版本控制，包含安全回退标签。详见[当前状态及开发日志](docs/PROJECT_LOG.md)和[接手AI提示词](docs/AI_HANDOFF_PROMPT.md)。

实测通过：iPhone首次配对后APK更新/设备重启自动认证；开机无需手动启动即可全屏进入IDLE；Wi-Fi断开进入OFFLINE，恢复后同一进程自动重连；强制杀进程后自动恢复；整个APK暂停时，双音量键五秒仍能返回Android桌面。

- [实施设计](docs/architecture.md)：复用边界、Android 实现、阶段验收。
- [官方协议分析](docs/muse-protocol.md)：配对、认证、Link、voice note、字幕、取消、自定义 Avatar。
- [真实硬件检测](docs/x04g-hardware.md)：本机 ADB 结果及未确认项。
- [恢复方案](docs/recovery.md)：安装前的备份和回滚约束。
- [当前状态、完整开发日志与接手导航](docs/PROJECT_LOG.md)。
- [可直接交给下一位AI的提示词](docs/AI_HANDOFF_PROMPT.md)。

官方版本固定为 `3229892e93c18a768ace42cbe1fe7133f91ca203`（2026-10-04，美国太平洋时间），本地源码在 `upstream/muse-gadget-sdk/`。上游保持原样。

官方SDK向Gadget返回文字，没有Muse回复音频；输入采用voice note。回复文字由Google Gemini API的`gemini-3.8-flash-tts`合成，音色为用户指定的`Leda`，不是Muse App音频。Android系统TTS已替换，不新增SDK依赖。

配置Google AI Studio的Gemini API Key：Windows双击`setup-gemini-key.cmd`，或执行`powershell -NoProfile -ExecutionPolicy Bypass -File .\setup-gemini-key.ps1`；本机Linux执行`./setup-gemini-key.sh`。窗口提示`Enter Gemini API Key:`，输入不回显，覆盖保存到现有secret目录的`.secrets/gemini-api-key`。更换Key后需要重新构建并安装APK。

Gradle读取本地Key，只写入忽略提交的build/generated资产，不写入Java/Kotlin源码；`.secrets/`、build和APK均已被`.gitignore`覆盖。Key随APK提供给设备，因此生成的APK也应只用于本机安装。SDK token仍沿用原流程，两个Key互不替代。

Speech沿用现有按句去重队列：字幕先出现，OkHttp后台请求Gemini Interactions API，收到SSE音频delta立即用AudioTrack播放24kHz/mono/PCM16LE。无音频文件、无重复转码、无自动重试；`store:false`不建立可供后续请求引用的服务端interaction。PTT取消HTTP和播放缓冲，并忽略过期回调；失败保留文字并简短提示。字幕结束后保留4秒；分页按播放进度估算，API没有逐字时间戳。官方格式依据：[TTS文档](https://ai.google.dev/gemini-api/docs/speech-generation)、[模型](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash-tts)。

只读检测：

```sh
scripts/diagnostics.sh 21065C0VR35518
```

完整输出留在忽略提交的 `diagnostics/`，权限仅限本机用户。SDK token 保存在 `.secrets/sdk-token`，不写进源码或构建产物。

构建与ADB更新APK（保留配对和设置）：

```sh
scripts/build.sh
scripts/install.sh 21065C0VR35518
```

Windows可用`gradle -p android :app:assembleDebug`构建，然后`adb install -r android\app\build\outputs\apk\debug\app-debug.apk`，再执行`adb shell am start -n io.muse.x04g/.MainActivity`。

实机TTS检查：`scripts/check-tts.sh 21065C0VR35518`。仅有一个可重复执行的检查：真实Leda合成/PCM播放、同一回复快照去重、字幕延时消失与取消；使用两段固定测试文字，会调用Gemini API。检查结束卸载测试APK并恢复主界面。日常验证仍使用中间PTT键问话，回答期间再次按住可打断。

2026-10-05实机检查通过：Google认证/model查询HTTP 200；Leda播放119040字节PCM并正常结束、字幕4秒后清空，第二段开始后取消成功且无旧回调恢复。首块音频延迟分别3461ms和1322ms；测试APK已卸载，app cache没有语音文件。Windows配置脚本尚未在Windows实测，Linux隐藏输入与本地Key经Gradle进入APK资产的链路已验证。

开发电脑所需上游：`upstream/muse-gadget-sdk`固定上述commit；`upstream/mbedtls`为v3.6.7并包含framework submodule。APK约4.9MiB，仅包含实际系统支持的armeabi-v7a。设备不安装编译器或其他Linux环境；运行依赖Android API、OkHttp、mbedcrypto和官方Noise/Avatar代码。

按住中间键讲话，松开提交；新按下立即停TTS并取消旧stream。Volume+/−调扬声器音量。长按屏幕右上角三秒打开Settings。双音量键同时五秒退出到Android；手动再打开Muse或重启可恢复，ADB持续维护与完整回滚见恢复文档。

本地不保存录音、STT或回复历史，只持久化配对、设置与Avatar显示资产。自动更新/OTA、WebView、第二个Muse daemon和Device Owner均未引入。
