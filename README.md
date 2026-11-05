# Cinema — Minecraft 影院系统

基于 **Minecraft 1.21.1 + Fabric + Java 21** 的影院模组。第一版目标是把影院拆成真实的物理单元：屏幕、投影仪、AV 接收器、5.1 扬声器和座位。

当前版本：**Alpha 0.0.1**。

当前状态：核心 P1 链路已可玩并通过游戏内验收：投影仪光学与 GUI、屏幕渲染/尺寸配置、AV Receiver M1–M4、四种扬声器、物理 5.1 路由、线材配对、设备命名、可乘坐座位、Mod Menu 设置。剩余重点：64×64 屏幕尺寸显示、BSL wash、无音轨长播、多屏/LAN、遥控器、媒体轨/字幕、可变声道布局与房间声学。听者始终使用玩家实际位置/朝向，不使用皇帝位或声场校正。

## 环境要求

| 项目 | 要求 |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | 0.20.1+ |
| Fabric API | 0.116.17+1.21.1 |
| Java（游戏 / 编译） | 21 |
| Java（Gradle） | 25+ |
| Gradle | 9.8.2（wrapper） |
| FFmpeg | **必须安装**，full build |

## 安装 FFmpeg（必须）

模组不内置 FFmpeg。请从 [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) 下载 **full build**，把二进制放到：

```text
.minecraft/mods/ffmpeg/bin/ffmpeg.exe
.minecraft/mods/ffmpeg/bin/ffprobe.exe
```

开发环境对应：

```text
run/mods/ffmpeg/bin/ffmpeg.exe
run/mods/ffmpeg/bin/ffprobe.exe
```

也支持直接放 `mods/ffmpeg.exe`，或使用：

```text
-Dcinema.ffmpeg.path=C:\path\to\ffmpeg.exe
```

默认 HDR 缩到 720p、SDR 缩到 1080p；视频输出高度/缩放属于未来的 AV Receiver `Video Processing` 模块，暂不放在 Mod Menu。调试时仍可用启动参数 `-Dcinema.video.maxHeight=N` 覆盖；`0` 保留原生分辨率。
硬件解码默认 `auto`；驱动有问题时用 `-Dcinema.ffmpeg.hwaccel=none` 关闭。
屏幕默认最大 16×16；可在 Mod Menu → Cinema → Config 中调整（1..64，硬上限 64×64），或启动参数 `-Dcinema.screen.maxSize=N` 覆盖。设置保存在 `config/cinema.json`。
同一设置页还提供原版行为开关：设备 GUI 是否暂停游戏（默认关）、空手右键是否打开设备 GUI（默认开）、spike 测试是否修改世界（默认开）。

## 构建与运行

```powershell
$env:JAVA_HOME='<JDK-25+>'
.\gradlew build
.\gradlew runClient
```

`build` 会运行 Checkstyle 命名检查（配置在 `config/checkstyle/checkstyle.xml`），报告输出到 `build/reports/checkstyle/`。只跑命名检查可用 `.\gradlew checkstyleMain checkstyleClient`。

## 游戏内帮助

```text
/cinema_help
/cinema help
```

## 文档

- [交接清单](docs/HANDOFF.md)
- [项目规划与架构](docs/PLANNING.md)
- [待办列表](docs/TODO.md)
- [游戏内指令说明](docs/COMMANDS.md)
- [P0 技术验证报告（历史）](docs/design/P0-SPIKE-REPORT.md)
- [P0.5 投影/扬声器设计（历史）](docs/design/P05-DESIGN.md)
- [投影仪 GUI 开发大纲](docs/design/PROJECTOR-GUI.md)
- [AV Receiver 设计与迁移](docs/design/AV-RECEIVER.md)
- [可变声道布局规划](docs/design/CHANNEL-LAYOUTS.md)
- [音频声学规划](docs/design/AUDIO-ACOUSTICS.md)

## 目录结构

```text
src/                 Fabric 模组源码
docs/                规划、待办、指令文档、设计资料
docs/design/         当前设计文档与 P0/P0.5 历史报告
docs/assets/         文档截图
spike/               独立的 P0 JavaCV 基准工程（历史验证用）
```
