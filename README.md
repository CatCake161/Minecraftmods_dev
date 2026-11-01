# Cinema — Minecraft 影院系统

基于 **Minecraft 1.21.1 + Fabric + Java 21** 的影院模组。第一版目标是把影院拆成真实的物理单元：屏幕、投影仪、AV 接收器、5.1 扬声器和座位。

当前状态：**P1 投影仪光学第一版可运行**；4K60/HDR、投影对齐、对焦/梯形等已通过用户游戏内验收；**AV Receiver M1 播放管线已完成并验收**，四种扬声器（落地式/书架式/中置/低音炮）、线材、遥控器已创建；接收器 GUI、扬声器 5.1 路由与座位仍待实现。

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
-Dfirstmod.ffmpeg.path=C:\path\to\ffmpeg.exe
```

默认 HDR 缩到 720p、SDR 缩到 1080p；要保留原生分辨率使用 `-Dfirstmod.video.maxHeight=0`。
硬件解码默认 `auto`；驱动有问题时用 `-Dfirstmod.ffmpeg.hwaccel=none` 关闭。

## 构建与运行

```powershell
$env:JAVA_HOME='<JDK-25+>'
.\gradlew build
.\gradlew runClient
```

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
- [P0 技术验证报告](docs/design/P0-SPIKE-REPORT.md)
- [P0.5 投影/扬声器设计](docs/design/P05-DESIGN.md)
- [投影仪 GUI 开发大纲](docs/design/PROJECTOR-GUI.md)
- [AV Receiver 设计与迁移](docs/design/AV-RECEIVER.md)

## 目录结构

```text
src/                 Fabric 模组源码
docs/                规划、待办、指令文档、设计资料
docs/design/         P0/P0.5 设计与验证报告
docs/assets/         文档截图
spike/               独立的 P0 JavaCV 基准工程（历史验证用）
```
