# Cinema 项目规划

> 本文是长期规划与架构记录。新会话先读 [HANDOFF.md](HANDOFF.md)。可执行任务见 [TODO.md](TODO.md)，指令说明见 [COMMANDS.md](COMMANDS.md)。

## 1. 项目范围

第一版只支持**本地媒体文件**，运行环境为单机 + LAN。服务端只同步播放状态和媒体指纹，不做公网媒体分发，也不要求服务端解码。

非目标（第一版）：

- 公网流媒体 / 媒体服务器
- 任意地形投影、真实聚光灯、Iris 深度图阴影
- HRTF 耳机虚拟化、自动房间校准
- 4K/HEVC/AV1 的默认档位（可以测试，不作为默认）

## 2. 已锁定决策

- **屏幕**：专用方块组成矩形投影面，第一版不做任意共面墙。
- **投影仪**：投掷比/变焦、对焦、梯形校正、环境光洗白、灯泡预热/风扇声。
- **可见光束**：已删除，不做光锥/粒子束。
- **扬声器**：5.1（FL/FR/FC/LFE/BL/BR），保留立体声回退；每只扬声器独立 mono source。
- **AV 接收器**：独立方块，负责扬声器配对、路由、音量、延迟、混响。
- **座位**：主座位定义“皇帝位”，作为投影/声场校准参考点。
- **编码默认**：1080p H.264/AAC；4K/HEVC/AV1 后置。
- **媒体依赖**：FFmpeg 是外部必须项，由用户从 gyan.dev 安装，不随模组打包。
- **测试方式**：复杂视觉效果由用户在游戏内检查，不只依赖截图或日志。

## 3. 当前架构

### 3.1 信号链

目标架构：

```text
AV Receiver（权威媒体单元）
  ├─ 本地文件 → ffmpeg 子进程 → 视频信号 → Projector（只做光学/灯泡/屏幕）
  └─ 本地文件 → ffmpeg 子进程 → 5.1 音频 → Speaker（只做回放）
```

当前过渡实现仍是全局 `CinemaPlayer` 直接驱动屏幕/OpenAL。后续必须把解码、播放状态、文件选择、音画时钟迁入 AV Receiver；投影仪只消费接收器输出的视频信号，扬声器只消费接收器输出的音频信号。

关键组件：

- `Ffmpeg`：定位 `ffmpeg.exe` / `ffprobe.exe`，读取 codec、尺寸、帧率、色彩元数据。
- `VideoDecoder`：外部 FFmpeg → RGBA；硬件解码、HDR tonemap、分辨率上限、PTS。
- `StreamingAudioDecoder`：外部 FFmpeg → 5.1 S16LE；音频块 PTS 由采样数计算。
- `CinemaPlayer`：当前全局播放器；最终归 AV Receiver 所有。
- `CinemaVideoTexture`：接收器视频输出 → RGBA 上传/对焦 LOD → 投影仪。
- `CinemaAudioEngine`：接收器音频输出 → 共享 Minecraft OpenAL device，六路 source。
- `CinemaPrewarm`：启动时检查外部 FFmpeg 版本。

职责边界：

- **AV Receiver**：解码、媒体文件、播放/暂停/seek、A/V 时钟、视频信号分发、音频路由。
- **Projector**：只处理物理模拟和基本投影设置（投掷比/对焦/梯形/亮度/wash/灯泡/配对）。
- **Speaker**：只接收接收器分配的声道并回放；不做解码。
- **Seat**：皇帝位/校准参考点。

默认参数：

- `-Dfirstmod.video.maxHeight=720`（HDR）/ `1080`（SDR）
- `-Dfirstmod.ffmpeg.hwaccel=auto`
- `-Dfirstmod.ffmpeg.path=...` 可覆盖 FFmpeg 位置
- HDR10/HLG 自动 `zscale + tonemap` 到 BT.709；HDR 默认 720p

### 3.2 屏幕与投影仪

- `ScreenBlock` / `ScreenBlockEntity` / `ScreenBlockEntityRenderer`
- `ScreenGeometry`：扫描同朝向、同平面的连续矩形；master 是观众左/下角。
- `ProjectorBlock` / `ProjectorBlockEntity`：投掷比、焦距、梯形、亮度、灯泡状态。
- `ProjectorOptics`：自动找屏幕；镜头 view/projection；逐块 UV 投影与裁剪。

- 对焦：mipmap + `GL_TEXTURE_LOD_BIAS`；仅在需要模糊时生成 mipmap。
- 环境光洗白：投影仪保存的 `ambientWash` 参数，可在投影仪 GUI 或命令中手动调整。
- 屏幕没有配对投影仪时保持黑屏，不做 flat fallback。

### 3.3 测试与命令

- 游戏内帮助：`/cinema_help [页码]` / `/cinema help [页码]`
- 客户端投影仪调参：`/cinema_projector_client`
- 服务端投影仪调参：`/cinema_projector`
- 手动模式：`-Dfirstmod.spike.manual=true`，不退出、不锁定玩家
- 自动模式：`-Dfirstmod.spike.exit=true`，测试结束后退出
- 自动测试固定出生点/朝南、正午、晴天、无动物
- 测试用屏幕/投影仪由玩家自行放置；不再提供自动测试场景命令

## 4. 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | FFmpeg 解码、纹理上传、A/V 同步、5.1 | ✅ |
| P0.5-A | Minecraft 内六路 OpenAL source，同一设备时钟 | ✅ |
| P0.5-B | 世界空间屏幕、投影 UV、Sodium/Iris 兼容 | ✅ |
| P0.5-C | 真实 720p30 + 5.1、有界队列、seek、预热 | ✅ |
| P1-1 | 屏幕方块、多方块扫描、master 渲染 | ✅ |
| P1-2 | 投影仪镜头、投掷比、对焦、梯形、环境光、灯泡 | ✅ 第一版 |
| P1-3 | 投影仪 GUI（仅物理模拟/光学/灯泡/wash、配对） | ⬜ |
| P1-4 | AV Receiver（解码、媒体、播放状态、视频/音频分发） | ⬜ |
| P1-5 | Speaker + Seat（只接收 AV 的音频） | ⬜ |
| P1-6 | 每接收器独立媒体、LAN 同步 | ⬜ |
| P2 | 任意投影面、真实光照、HRTF、自动校准 | ⬜ |

## 5. 测试原则

1. **构建和单元级验证**可以自动跑；涉及动态画面、对焦、梯形、环境光、HDR 的最终效果，交给用户在游戏内检查。
2. 自动测试必须固定时间、天气、玩家位置/朝向，并关闭动物生成。
3. 手动测试使用 `firstmod.spike.manual=true`，让玩家自由移动和调参。
4. 测试用屏幕/投影仪由玩家放置；不要留下自动生成的测试方块或不可见 barrier。
5. 日志中记录 codec、源/输出分辨率、color metadata、解码 fps、丢帧数；但日志不提交到仓库。

## 6. 已知风险

- 4K60 VP9/AV1 的软件解码仍可能受 CPU 限制；硬件解码取决于 GPU/驱动。
- HDR tone mapping 依赖 gyan.dev full build 的 `zscale`/`tonemap`；不同版本可能有差异。
- 无音轨视频使用墙钟，长时间播放可能存在漂移。
- 当前所有屏幕共用一个 `CinemaPlayer`；多屏/多投影仪需要重新设计。
- 投影仪和屏幕配对依赖附近已加载区块，跨区块/远距离需要显式配对工具（线材/配对器）。
- 未来 GUI 必须提供物理模拟（对焦/梯形/环境光/灯泡）的独立开关。
- 未来需要远程控制遥控器工具，用于在观众席调整投影仪/接收器。
- 资源重载、设备切换、两小时长片内存尚未完整测试。
- FFmpeg 是 GPL/LGPL 混合构建，发布前需要确认许可和分发方式。

## 7. 仓库约定

- `docs/PLANNING.md`：长期规划、架构、决策。
- `docs/TODO.md`：可执行任务清单。
- `docs/COMMANDS.md`：游戏内指令说明。
- `docs/design/PROJECTOR-GUI.md`：投影仪 GUI 开发大纲。
- `docs/design/`：历史设计与验证报告。
- `docs/assets/`：文档截图。
- `spike/`：独立 P0 基准工程，不参与主模组构建。
- 文档和提交中不写入个人绝对路径、真实姓名、邮箱、私人媒体文件名。
