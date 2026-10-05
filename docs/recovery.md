# 安装与恢复

实际安装：APK `io.muse.x04g`，新Magisk模块 `/data/adb/modules/muse_x04g`。保留原Android、Kernel、vendor、Home、原有模块和service.d；不刷任何分区。模块systemless覆盖一个pmic keylayout（248 MUTE→F10）并运行唯一启动/监督程序。

原keylayout备份：电脑 `backups/phase2-input/mtk-pmic-keys.kl`，设备 `/data/local/tmp/muse-x04g-backup/phase2-input/mtk-pmic-keys.kl`。Phase 4部署前再次备份整个本项目模块、原service.d和Home信息：设备 `/data/local/tmp/muse-x04g-backup/20261005T095437Z/`，电脑 `backups/20261005T095437Z/`。没有直接修改原system文件，无需向system拷贝备份。

## 硬件逃生

同时按住Volume+与Volume-五秒：root监督程序写维护标记、force-stop Muse、启动Android HOME。已在整个APK被SIGSTOP暂停时，由用户实测成功返回桌面；不依赖Touch或Avatar UI线程。

本次退出后不会立即被watchdog拉回。手动打开Muse可恢复；正常重启也会恢复。原Home仍是 `com.gengyixiong.codexmeter/.MainActivity`，没有替换Launcher。

## ADB维护

下面命令中的 `adb` 指Android SDK的真实platform-tools/adb；本机mise的adb快捷入口不可用，可先设置：

```sh
export PATH="$HOME/.local/share/mise/installs/android-sdk/23.0/platform-tools:$PATH"
```

进入持续维护模式（跨重启），保持ADB/root和硬件逃生：

```sh
adb -s 21065C0VR35518 shell touch /data/adb/modules/muse_x04g/maintenance
adb -s 21065C0VR35518 shell am force-stop io.muse.x04g
adb -s 21065C0VR35518 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

恢复常驻：

```sh
adb -s 21065C0VR35518 shell rm -f /data/adb/modules/muse_x04g/maintenance /data/user/0/io.muse.x04g/files/maintenance
adb -s 21065C0VR35518 shell am start -n io.muse.x04g/.MainActivity
```

两个标记不同：module/maintenance是ADB持续维护；app私有files/maintenance是UI退出/硬件逃生的临时维护，MainActivity恢复前台或正常boot会清除它。不要删除凭据文件或 `pm clear`，否则需要重新配对。

## 更新与完全回滚

日常仅更新APK，原配对和设置保留：

```sh
scripts/build.sh
scripts/install.sh 21065C0VR35518
```

Gemini TTS替换前的已安装APK备份在`backups/pre-gemini/app.apk`。仅回滚语音版本时执行`adb -s 21065C0VR35518 install -r backups/pre-gemini/app.apk`，再启动MainActivity；保留配对和系统模块，不需要重启或重新配对。

2026-10-05用户确认Leda声音正常，打断时疑似碰动供电线，随后发生整机冷启动（`cold,powerkey`）。未发现Muse崩溃、内核panic或I/O错误记录，但`/data/app`中的Muse APK缺失，配对数据仍在，文件丢失原因尚未确定。已将私有配对/设置备份至忽略提交的`backups/cold-boot-recovery/muse-private.tar`，重新安装同一APK并执行`sync`，确认原凭据自动重新注册Muse。安装脚本现均在结束前执行`sync`；这不代表已证明文件丢失根因。

随后实机日志确认三轮录音提交、回复音频及多次SPEAKING→LISTENING打断；boot ID和Muse进程保持不变，没有再次重启。两次新回复首块音频延迟分别1438ms和1377ms。

只有改了root监督程序或模块文件才运行 `scripts/install-appliance.sh 21065C0VR35518`；脚本先自动备份再替换，只操作本项目模块，不覆盖用户其他脚本。新设备首次安装要先运行 `scripts/install-input.sh` 并重启应用按键覆盖，再安装appliance监督；这个脚本只适用于Phase 0确认过的X04G原映射。

保留APK/配对、恢复原Mute和取消自启动：创建模块disable，然后重启。disable只在重启后卸下keylayout覆盖：

```sh
adb -s 21065C0VR35518 shell touch /data/adb/modules/muse_x04g/maintenance /data/adb/modules/muse_x04g/disable
adb -s 21065C0VR35518 reboot
```

重新启用已disable的模块时，删除module下的disable和maintenance两个标记，再重启；这样按键覆盖和自启动一起恢复。

完全卸载：`scripts/uninstall.sh 21065C0VR35518`。脚本先禁用本项目模块并停止监督，再正常停服务、返回HOME、卸载APK；**配对凭据随APK删除**。随后 `adb reboot` 恢复原按键。原模块保留为disabled，备份仍在；无需factory reset。

配对窗口临时蓝牙名为MuseGadget214A4E，原名MiSmartClock9183保存在APK私有preferences，窗口正常关闭或下次启动恢复。强制停止发生在配对窗口内时，应先再打开Muse让它恢复原名，或通过Android蓝牙设置恢复，再卸载APK。

不改bootloader、partition table、kernel、vendor image、modem、recovery、boot动画和SELinux；不删除原厂组件，不重新锁Bootloader。ADB/root保持原有入口。
