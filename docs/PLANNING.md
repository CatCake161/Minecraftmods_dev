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
- **扬声器**：当前 5.1（FL/FR/FC/LFE/BL/BR），保留立体声回退；每只扬声器独立 mono source。未来规划可变布局（mono/stereo/2.1/3.1/5.1/7.1/5.1.2/5.1.4/7.1.2/7.1.4）及天空/吸顶音响（同一种吸顶式扬声器），见 [CHANNEL-LAYOUTS.md](design/CHANNEL-LAYOUTS.md)。
- **AV 接收器**：独立方块，负责媒体/播放状态、扬声器配对与路由、音量；延迟/混响为后续功能。
- **座位**：可右键乘坐的影院座位；不参与投影/声场校准，也不定义皇帝位或听者参考。
- **编码默认**：1080p H.264/AAC；4K/HEVC/AV1 后置。
- **媒体依赖**：FFmpeg 是外部必须项，由用户从 gyan.dev 安装，不随模组打包。
- **测试方式**：复杂视觉效果由用户在游戏内检查，不只依赖截图或日志。

## 3. 当前架构

### 3.1 信号链

信号链（已实现）：

```text
AV Receiver（权威媒体单元）
  ├─ 本地文件 → ffmpeg 子进程 → 视频信号 → Projector（只做光学/灯泡/屏幕）
  └─ 本地文件 → ffmpeg 子进程 → 5.1 音频 → Speaker（只做回放）
```

解码、播放状态、媒体选择和音画时钟已迁入 AV Receiver：客户端 `AvReceiverPlaybackManager` 按接收器状态驱动独立 `CinemaPlayer` 实例，视频经投影仪路由到屏幕。每个接收器持有独立 `CinemaAudioEngine`（独立 OpenAL context），两个接收器可同时播放；M4 已把配对扬声器按 FL/FR/FC/LFE/BL/BR 路由到物理位置。

客户端声学层（A2 直达声已实现，反射/混响规划中；详见 [AUDIO-ACOUSTICS.md](design/AUDIO-ACOUSTICS.md)）：

> 声学模拟（EFX/早期反射/晚期混响/Spinorama 指向性原型）统一在 `acoustic-simulation` 分支开发，`main` 保持可玩的稳定基线；只有通过游戏内听感验收的部分才分段合并回 `main`。

```text
5.1 PCM → 直达声（Java 距离/ISO 空气吸收/遮挡/指向性）
        → 一阶早期反射（镜像源 + Java 距离/空气吸收/材质/指向性）  // A2b 待实现
        → 晚期混响（EAXReverb 仅 late tail / FDN 回退）              // A3 待实现
        → 6 个直达 OpenAL source + 最多 24–36 个反射 OpenAL source
```

- 直达声应用 Java `1/max(r,1)` 距离衰减、ISO 9613-1 空气吸收（1 m 为 0 dB 参考点）、遮挡和扬声器指向性；OpenAL 距离模型为 `AL_NONE`，source 位置只负责声像/HRTF。早期反射按房间几何、材质、距离、空气吸收和扬声器指向性计算。
- 早期反射每个 tap 使用独立 OpenAL source，位置为镜像源；OpenAL 负责方向/声像，距离由 Java 按 `1/max(L,1)` 计算，不混入 5.1 直达声道。
- 扬声器指向性使用 **Spinorama 实测水平/垂直指向性 + 轴上响应**，不手写猜测曲线；直达声和反射共用同一份数据，按各自辐射角查表；具体计算方法待用户确认。
- 反射增益按物理链路计算，不设置“反射默认比直达声低 6–12 dB”的固定值。
- 空气吸收开关/温湿度、早反调试 gain 放 Mod Menu（客户端）；空气吸收与环境 FIR 已接入直达声，反射频带精度待 A2b 确定。
- 只做扬声器箱体指向性，不做原始声源指向性；5.1 bus 已混合，无法从混音恢复每个原始声源方向。
- 晚期尾音优先交给 OpenAL EFX；EAXReverb 自带的固定 5 m 早期反射不适用于不同尺寸影院，计划关闭，只取晚期混响。
- 声学计算是客户端本地行为，不改变接收器的媒体/播放权威，也不影响 A/V 时钟。

关键组件：

- `Ffmpeg`：定位 `ffmpeg.exe` / `ffprobe.exe`，读取 codec、尺寸、帧率、色彩元数据。
- `VideoDecoder`：外部 FFmpeg → RGBA；硬件解码、HDR tonemap、分辨率上限、PTS。
- `StreamingAudioDecoder`：外部 FFmpeg → 5.1 `f32le`；音频块 PTS 由采样数计算。
- `CinemaPlayer`：每接收器播放代理持有的播放器实例；不再有全局播放器。
- `CinemaVideoTexture`：接收器视频输出 → RGBA 上传/对焦 LOD → 投影仪。
- `CinemaAudioEngine`：每接收器独立的音频引擎与 OpenAL context，共享 Minecraft OpenAL device；当前六路 source，未来按目标布局动态 source；A2 起在音频线程做 Java 距离/遮挡增益和两级 FIR（指向性 + 空气/遮挡环境）。
- `CinemaPrewarm`：启动时检查外部 FFmpeg 版本。

职责边界：

- **AV Receiver**：解码、媒体文件、播放/暂停/seek、A/V 时钟、视频信号分发、音频路由。
- **Projector**：只处理物理模拟和基本投影设置（投掷比/对焦/梯形/亮度/wash/灯泡/配对）。
- **Speaker**：只接收接收器分配的声道并回放；不做解码。
- **Seat**：可乘坐的影院座位；不参与声学/校准。

默认参数：

- 视频输出高度：归未来 AV Receiver `Video Processing`，不放在 Mod Menu；当前默认 HDR 720 / SDR 1080，调试可用 `-Dcinema.video.maxHeight` 覆盖，0 = 原生
- `-Dcinema.ffmpeg.hwaccel=auto`
- `-Dcinema.ffmpeg.path=...` 可覆盖 FFmpeg 位置
- HDR10/HLG 自动 `zscale + tonemap` 到 BT.709；HDR 默认 720p

### 3.2 屏幕与投影仪

- `ScreenBlock` / `ScreenBlockEntity` / `ScreenBlockEntityRenderer`
- `ScreenGeometry`：扫描同朝向、同平面的连续矩形（硬上限 64×64，逐帧缓存）；master 是观众左/下角。实际宽高按 Mod Menu 设置（1..64，默认 16×16，保存到 `config/cinema.json`）裁剪；`-Dcinema.screen.maxSize` 覆盖保存值。
- 每个屏幕方块实体只绘制自己那一格，避免 master 所在 section 被视锥剔除时整屏消失。
- `ProjectorBlock` / `ProjectorBlockEntity`：投掷比、焦距、梯形、亮度、灯泡状态。
- `ProjectorOptics`：读取显式配对的屏幕；镜头 view/projection；逐块 UV 投影与裁剪。

- 对焦：mipmap + `GL_TEXTURE_LOD_BIAS`；仅在需要模糊时生成 mipmap。
- 环境光洗白：投影仪保存的 `ambientWash` 参数，可在投影仪 GUI 或命令中手动调整。
- 屏幕没有配对投影仪时保持黑屏，不做 flat fallback。

### 3.3 测试与命令

- 游戏内帮助：`/cinema_help [页码]` / `/cinema help [页码]`
- 客户端投影仪调参：`/cinema_projector_client`
- 服务端投影仪调参：`/cinema_projector`
- 手动模式：`-Dcinema.spike.manual=true`，不退出、不锁定玩家
- 自动模式：`-Dcinema.spike.exit=true`，测试结束后退出
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
| P1-3 | 投影仪 GUI（仅物理模拟/光学/灯泡/wash、配对） | ✅ M1/M2/M3 已验收；线材显式配对与配对诊断已验收；对焦模糊观感暂不处理（用户决定） |
| P1-4 | AV Receiver（解码、媒体、播放状态、视频/音频分发） | ✅ M1/M2/M3 已验收（M4 物理路由见 P1-5） |
| P1-5 | Speaker + Seat（只接收 AV 的音频） | ✅ Speaker 四种箱体/角色限制、接收器↔扬声器线材链接、M4 物理 5.1 路由/每扬声器电平已完成并验收；Seat 可乘坐但不参与音频 |
| P1-6 | 每接收器独立媒体、LAN 同步 | 🟡 每接收器独立播放已验收；LAN 播放状态同步未做 |
| P1-7 | 房间声学/音频计算（直达声、一阶反射、Spinorama 指向性、晚期混响） | ⬜ 规划（P1-5 已完成；见 [AUDIO-ACOUSTICS.md](design/AUDIO-ACOUSTICS.md)） |
| P1-8 | 可变声道布局（mono/stereo/2.1/3.1/5.1/7.1/5.1.2/5.1.4/7.1.2/7.1.4）+ 天空/吸顶音响 | ⬜ 规划（见 [CHANNEL-LAYOUTS.md](design/CHANNEL-LAYOUTS.md)） |
| P2 | 任意投影面、真实光照、HRTF、自动校准 | ⬜ |

## 5. 测试原则

1. **构建和单元级验证**可以自动跑；涉及动态画面、对焦、梯形、环境光、HDR 的最终效果，交给用户在游戏内检查。
2. 自动测试必须固定时间、天气、玩家位置/朝向，并关闭动物生成。
3. 手动测试使用 `cinema.spike.manual=true`，让玩家自由移动和调参。
4. 测试用屏幕/投影仪由玩家放置；不要留下自动生成的测试方块或不可见 barrier。
5. 日志中记录 codec、源/输出分辨率、color metadata、解码 fps、丢帧数；但日志不提交到仓库。
6. 声学效果（早期反射、晚期混响、指向性、遮挡）最终由用户游戏内试听验收；数值/日志只用于定位，不替代听感。

## 6. 已知风险

- 4K60 VP9/AV1 的软件解码仍可能受 CPU 限制；硬件解码取决于 GPU/驱动。
- HDR tone mapping 依赖 gyan.dev full build 的 `zscale`/`tonemap`；不同版本可能有差异。
- 无音轨视频使用墙钟，长时间播放可能存在漂移。
- 每个接收器只向一个配对的投影仪输出视频；多屏/多投影仪路由尚未实现。
- 投影仪和屏幕配对依赖附近已加载区块；线材提供近距显式配对，接收器/投影仪 GUI 的配对菜单可远程选择附近设备完成配对。
- 遥控器扫描面板支持 128 格内 Ping/Access；绑定/解绑仍保留但只影响物品 tooltip，多人并发与视线遮挡规则尚未实现。
- EAXReverb 是参数化感知混响，不是几何声学；其早期反射固定为约 5 m 房间模型，不能直接用于不同尺寸影院，计划只取晚期尾音。
- 一阶镜像源忽略衍射、散射、高阶反射和低频房间模态；LFE 低频驻波/峰谷不会由首版模型正确重现。
- Minecraft 方块没有真实吸声系数；材质吸收首版只能是近似，需要游戏内校准。
- 扬声器指向性计划使用 Spinorama 实测 H/V + 轴上响应；方块是风格化模型，映射到真实型号必然有偏差，且 H/V 两切面不能完全代表 3D 指向性。REST API 需要人工申请 Bearer key，不能匿名访问，key 不得进入仓库/模组/日志。
- Spinorama 测量数据默认 CC BY-NC-SA 4.0（署名、非商业、相同方式共享），与项目 CC0 代码不同；发布前必须做许可/attribution 决策，不能默认打包。
- 声学计算以 M4 已完成的 5.1 物理路由为基线：未配对声道回退虚拟 5.1；听者始终使用玩家实际耳朵/朝向，不做 Seat 皇帝位或声场校正；Seat 仅可乘坐。声学计算不能假设已有房间几何/材质数据。
- 未来可变布局（7.1/5.1.2/5.1.4/7.1.2/7.1.4）需要把固定六声道引擎、`AudioChunk`、扬声器角色和 GUI 一起泛化；FFmpeg layout 名称/顺序、OpenAL source 数、多接收器性能和 LFE 分频都需单独验证。规划见 [CHANNEL-LAYOUTS.md](design/CHANNEL-LAYOUTS.md)。
- EFX 在自定义 OpenAL context、设备切换和不同 OpenAL Soft 版本下的行为需要 spike 验证；不可用时必须有回退。
- 资源重载、设备切换、两小时长片内存尚未完整测试。
- FFmpeg 是 GPL/LGPL 混合构建，发布前需要确认许可和分发方式。

## 7. 仓库约定

- `docs/PLANNING.md`：长期规划、架构、决策。
- `docs/TODO.md`：可执行任务清单。
- `docs/COMMANDS.md`：游戏内指令说明。
- `docs/design/PROJECTOR-GUI.md`：投影仪 GUI 开发大纲。
- `docs/design/AV-RECEIVER.md`：AV Receiver 架构、迁移历史与决策记录。
- `docs/design/AUDIO-ACOUSTICS.md`：房间声学、早期反射、指向性与晚期混响的规划。
- `docs/design/`：当前设计文档与历史验证报告。
- `docs/assets/`：文档截图。
- `spike/`：独立 P0 基准工程，不参与主模组构建。
- 文档和提交中不写入个人绝对路径、真实姓名、邮箱、私人媒体文件名。
