# Cinema 客户端架构与目录边界

> 本文定义客户端的**目标目录结构**与**线程/数据流边界**。
>
> 状态（2026-11-12）：目标结构（含 `audio` 中度拆分、`fir` → `acousticfir`）已确认，
> **代码已按本文迁移完成**。`client/audio` 已并入 `client/media/audio`，
> `client/media` 根只保留编排类；本次迁移为纯包移动，声学行为与默认值未变。
>
> 相关文档：[项目规划](../PLANNING.md)、[交接清单](../HANDOFF.md)、
> [音频声学设计](AUDIO-ACOUSTICS.md)、[AV Receiver](AV-RECEIVER.md)。

## 1. 分层原则

客户端按“媒体域 → 子域”组织，而不是按“计算 / 编排”组织：

```text
media 是父域
  ├─ audio  声音如何产生
  └─ video  画面如何解码与上传
```

同时遵守三条硬边界：

1. **`media/audio` 负责声音**：PCM、解码、声学几何、FIR、反射、混响、OpenAL 输出。
2. **`media/video` 负责画面**：视频解码、帧、纹理上传、帧缓冲池。
3. **`media` 根负责编排**：同时驱动音频和视频、管理 receiver 生命周期、播放时钟、媒体库。

不允许的依赖方向：

- `media/video` 不得依赖 `media/audio`。
- `media/audio` 不得依赖 `media/video`。
- 两者只通过 `media` 根层的 `CinemaPlayer` / `AvReceiverPlayback` 组合。

## 2. 目标目录树

```text
client/media/
├─ CinemaPlayer.java              // 音视频总编排、解码线程、播放时钟
├─ AvReceiverPlayback.java        // 单个 receiver 的播放生命周期
├─ AvReceiverPlaybackManager.java // receiver 发现/销毁、客户端 tick 入口
├─ CinemaPrewarm.java             // FFmpeg 预热
├─ MediaLibrary.java              // 媒体文件解析
├─ LibraryRegistry.java           // 媒体库注册
│
├─ ffmpeg/
│  └─ Ffmpeg.java                 // 音视频共用的 FFmpeg 定位/进程封装
│
├─ audio/
│  ├─ AudioChunk.java             // 5.1 交错 PCM + PTS
│  ├─ playback/
│  │  ├─ CinemaAudioEngine.java   // 音频线程、DSP 执行、OpenAL source
│  │  ├─ MinecraftOpenAl.java     // OpenAL context/device 接入
│  │  └─ ReflectionSource.java    // premium 反射 tap DTO（暂留，见 2.2）
│  ├─ decode/
│  │  └─ StreamingAudioDecoder.java
│  ├─ dsp/                        // 通用信号处理原语
│  │  ├─ Fft.java
│  │  ├─ Biquad.java
│  │  ├─ FirProcessor.java
│  │  ├─ MinimumPhaseFir.java
│  │  ├─ MinimumPhaseFirDesigner.java
│  │  └─ StereoUpmixer.java
│  ├─ acoustics/
│  │  ├─ room/                    // 房间盒体、材质、表面、检测
│  │  │  ├─ AcousticRoom.java
│  │  │  ├─ RoomDetector.java
│  │  │  ├─ RoomFill.java
│  │  │  ├─ RoomFloodFill.java
│  │  │  ├─ AcousticSurface.java
│  │  │  ├─ AcousticMaterial.java
│  │  │  ├─ AcousticMaterials.java
│  │  │  ├─ AxisAbsorption.java
│  │  │  └─ ReflectionSurfaceMerger.java
│  │  ├─ scene/                   // 不可变场景快照
│  │  │  ├─ AcousticScene.java
│  │  │  ├─ RoomAcousticSnapshot.java
│  │  │  ├─ AcousticListener.java
│  │  │  └─ AcousticSpeaker.java
│  │  ├─ geometry/                // 镜像面与镜像源
│  │  │  ├─ Point3.java
│  │  │  ├─ MirrorPlane.java
│  │  │  ├─ MirrorPlaneDiscovery.java
│  │  │  ├─ ImageSourceSolver.java
│  │  │  ├─ ImageSourcePathSolver.java
│  │  │  └─ ReflectionProbe.java
│  │  ├─ path/                    // tap 数据与延迟预算
│  │  │  ├─ ReflectionTap.java
│  │  │  ├─ ReflectionPathTap.java
│  │  │  ├─ ReflectionDelayLimit.java
│  │  │  └─ ReflectionDelayBudget.java
│  │  └─ propagation/             // 空气吸收与遮挡
│  │     ├─ AirAbsorption.java
│  │     ├─ OcclusionScanner.java
│  │     ├─ OcclusionProfile.java
│  │     └─ OcclusionResult.java
│  ├─ speaker/                    // 设备数据与扬声器/低音炮模型
│  │  ├─ SpeakerDataProfile.java
│  │  ├─ SpeakerDataRegistry.java
│  │  ├─ SpeakerDataAssignments.java
│  │  ├─ SpeakerDirectivityService.java
│  │  ├─ SubwooferAcoustics.java
│  │  └─ SubwooferBand.java
│  ├─ acousticfir/                // 声学 FIR 配方，不是 FIR 实现
│  │  ├─ CommonAcousticFirProfile.java
│  │  ├─ CommonAcousticFirDesigner.java
│  │  ├─ CommonAcousticFirService.java
│  │  └─ MaterialReflectionCurve.java
│  ├─ reflection/                 // 反射渲染与几何晚期总线
│  │  ├─ LateReflectionBus.java
│  │  ├─ LateReflectionSettings.java
│  │  ├─ LateReflectionTarget.java
│  │  └─ Vbap.java
│  └─ debug/
│     ├─ EfxReverbSpike.java
│     └─ InGameAudioSpike.java
│
└─ video/
   ├─ VideoDecoder.java
   ├─ VideoFrame.java
   ├─ CinemaVideoTexture.java
   └─ FrameBufferPool.java
```

### 2.1 为什么是 `acousticfir`

`fir` 是算法术语，容易让人以为这个包放的是 FIR 实现；实际实现（FFT、最小相位设计、
overlap-add 卷积）在 `dsp/`。`acousticfir` 明确表示“声学频率响应配方”：

```text
dsp/         怎么算 FIR：FFT、最小相位、卷积、Biquad
acousticfir/ 算什么 FIR：扬声器 + 角度 + 空气 + 材质 + 遮挡 + 低音炮 → 目标频响
```

### 2.2 `ReflectionSource` 暂留 `playback`

`ReflectionSource` 的构造器直接调用 `CinemaAudioEngine.maxReflectionDelaySeconds()`。
如果把它移到 `reflection/`，会形成 `playback → reflection` 与
`reflection → playback` 的包循环。

第一轮迁移选择零行为改动：**`ReflectionSource` 留在 `playback/`**。
后续可把延迟上限提到中性类（例如 `audio/AudioLimits`），再移到 `reflection/`。

## 3. 迁移映射（原路径 → 现路径）

| 当前 | 目标 |
|---|---|
| `client/media/CinemaAudioEngine` | `client/media/audio/playback/CinemaAudioEngine` |
| `client/media/MinecraftOpenAl` | `client/media/audio/playback/MinecraftOpenAl` |
| `client/media/ReflectionSource` | `client/media/audio/playback/ReflectionSource`（暂留） |
| `client/media/AudioChunk` | `client/media/audio/AudioChunk` |
| `client/media/StreamingAudioDecoder` | `client/media/audio/decode/StreamingAudioDecoder` |
| `client/media/StereoUpmixer` | `client/media/audio/dsp/StereoUpmixer` |
| `client/media/VideoDecoder` | `client/media/video/VideoDecoder` |
| `client/media/VideoFrame` | `client/media/video/VideoFrame` |
| `client/media/CinemaVideoTexture` | `client/media/video/CinemaVideoTexture` |
| `client/media/FrameBufferPool` | `client/media/video/FrameBufferPool` |
| `client/media/Ffmpeg` | `client/media/ffmpeg/Ffmpeg` |
| `client/media/CinemaPlayer` | `client/media/CinemaPlayer`（不变） |
| `client/media/AvReceiverPlayback*` | `client/media/AvReceiverPlayback*`（不变） |
| `client/media/MediaLibrary`、`LibraryRegistry` | `client/media/`（不变） |
| `client/audio/acoustics/AcousticRoom`、`RoomDetector`、`RoomFill`、`RoomFloodFill`、`AcousticSurface`、`AcousticMaterial`、`AcousticMaterials`、`AxisAbsorption`、`ReflectionSurfaceMerger` | `client/media/audio/acoustics/room/` |
| `client/audio/acoustics/AcousticScene`、`RoomAcousticSnapshot`、`AcousticListener`、`AcousticSpeaker` | `client/media/audio/acoustics/scene/` |
| `client/audio/acoustics/Point3`、`MirrorPlane`、`MirrorPlaneDiscovery`、`ImageSourceSolver`、`ImageSourcePathSolver`、`ReflectionProbe` | `client/media/audio/acoustics/geometry/` |
| `client/audio/acoustics/ReflectionTap`、`ReflectionPathTap`、`ReflectionDelayLimit`、`ReflectionDelayBudget` | `client/media/audio/acoustics/path/` |
| `client/audio/acoustics/OcclusionScanner`、`OcclusionProfile`、`OcclusionResult`、`client/audio/environment/AirAbsorption` | `client/media/audio/acoustics/propagation/` |
| `client/audio/directivity/Fft`、`FirProcessor`、`MinimumPhaseFir`、`MinimumPhaseFirDesigner`、`client/audio/filter/Biquad` | `client/media/audio/dsp/` |
| `client/audio/directivity/SpeakerDirectivityService`、`client/audio/speakerdata/*` | `client/media/audio/speaker/` |
| `client/audio/fir/*` | `client/media/audio/acousticfir/*` |
| `client/audio/reverb/LateReflectionBus`、`LateReflectionSettings`、`LateReflectionTarget`、`Vbap` | `client/media/audio/reflection/` |
| `client/audio/EfxReverbSpike`、`InGameAudioSpike` | `client/media/audio/debug/` 或删除 |

`src/main/.../media/MediaKey` 属于服务端/通用侧，不在本次迁移范围。

### 3.1 包私有类的约束

以下类目前是包私有，必须和调用方留在同一个包：

- `Fft` ← `FirProcessor`、`MinimumPhaseFirDesigner` → 一起进 `dsp/`
- `ReflectionSurfaceMerger` ← `RoomDetector` → 一起进 `acoustics/room/`
- `RoomFloodFill` ← `RoomDetector` → 一起进 `acoustics/room/`
- `MaterialReflectionCurve` ← `CommonAcousticFirDesigner` → 一起进 `acousticfir/`

## 4. 线程模型

| 线程 | 主要类 | 职责 |
|---|---|---|
| 客户端主线程 | `AvReceiverPlaybackManager`、`AvReceiverPlayback`、`AcousticScene`、`MirrorPlaneDiscovery`、`ImageSourceSolver`、`ImageSourcePathSolver` | 读 Minecraft 世界、检测房间、算镜像源/路径、发布不可变快照 |
| `cinema-audio-decode` | `CinemaPlayer.decodeAudio`、`StreamingAudioDecoder` | FFmpeg 解码为 5.1 交错 PCM |
| `cinema-audio` | `CinemaAudioEngine.run/pump`、`ReflectionSource`、`LateReflectionBus`、OpenAL | 取 PCM、跑 FIR/反射/晚期总线、上传 buffer |
| `cinema-video` | `VideoDecoder`、`CinemaVideoTexture` | 视频解码与纹理上传，不参与音频 |

关键约束：

- **音频线程不读 Minecraft 世界**。只读 volatile 列表、版本号和 PCM 队列。
- **客户端线程不碰 OpenAL**。只构造 `ReflectionSource` / `LateReflectionBus.Tap` 并发布。
- 两侧通过 `CinemaAudioEngine.setChannelLayout()`、`setReflections()`、
  `setLateReflectionTaps()` 交接。

## 5. 数据流

### 5.1 媒体 PCM

```text
AvReceiverPlaybackManager.tick
  └─ AvReceiverPlayback.sync
       └─ open → openAt → CinemaPlayer.open
            └─ CinemaPlayer.start
                 ├─ audio.start()        // cinema-audio 线程
                 ├─ videoThread.start()  // cinema-video 线程
                 └─ audioThread.start()  // cinema-audio-decode 线程

cinema-audio-decode
  └─ CinemaPlayer.decodeAudio
       └─ StreamingAudioDecoder.decode(file, audio::submit)
            └─ AudioChunk → CinemaAudioEngine.submit → input (BlockingQueue)
```

### 5.2 声学参数

```text
客户端主线程
  └─ AvReceiverPlayback.sync
       ├─ updateChannelLayout
       │    ├─ AcousticScene.snapshot / OcclusionScanner / SpeakerDirectivityService
       │    ├─ CommonAcousticFirProfile.direct
       │    └─ CinemaAudioEngine.setChannelLayout
       ├─ updateReflections
       │    ├─ MirrorPlaneDiscovery.discover
       │    ├─ ImageSourceSolver.solve            // 一阶
       │    ├─ ImageSourcePathSolver.solveWithStats // order 2
       │    ├─ buildPremiumSources                // 强路径 → ReflectionSource
       │    ├─ busTaps                            // 其余 → LateReflectionBus.Tap
       │    └─ CinemaAudioEngine.setReflections
       └─ stepLatePhase
            ├─ stepFastPhase  // order 3，分帧预算
            ├─ stepDeepPhase  // order 4
            └─ CinemaAudioEngine.setLateReflectionTaps
```

发布端只写 volatile 字段并把 `AtomicInteger` 版本号 +1。

### 5.3 音频线程消费

```text
CinemaAudioEngine.run
  └─ pump
       ├─ 版本变化 → applyChannelLayout / applyReflectionTargets
       │              / applyLateReflectionSettings / applyLateReflectionTaps
       ├─ take AudioChunk
       └─ queueBlock
            ├─ writeRawHistory                  // 原始 5.1 先入环
            ├─ 直接声 FIR → 6 个 OpenAL source
            ├─ premium 反射 → ReflectionSource → OpenAL source
            └─ late bus → LateReflectionBus → 6 个 wet source
```

`queueBlock` 是唯一同时接触“PCM”和“声学参数”的位置。

## 6. 类职责

| 类 | 目标包 | 角色 |
|---|---|---|
| `AvReceiverPlaybackManager` | `media` | 客户端 tick 入口、receiver proxy 发现/销毁 |
| `AvReceiverPlayback` | `media` | 单 receiver 的打开/同步/seek/漂移校正/参数发布 |
| `CinemaPlayer` | `media` | 单媒体的解码线程、视频线程、播放时钟 |
| `CinemaAudioEngine` | `media/audio/playback` | 音频线程、DSP 执行、OpenAL 生命周期 |
| `ReflectionSource` | `media/audio/playback` | premium 反射 tap 的不可变 DTO |
| `StreamingAudioDecoder` | `media/audio/decode` | FFmpeg → `AudioChunk` |
| `AudioChunk` | `media/audio` | PCM DTO |
| `Fft` / `Biquad` / `FirProcessor` | `media/audio/dsp` | 通用 DSP 原语 |
| `MinimumPhaseFir` / `MinimumPhaseFirDesigner` | `media/audio/dsp` | 最小相位 FIR 结果与设计 |
| `AcousticRoom` / `RoomDetector` | `media/audio/acoustics/room` | 房间盒体、材质、检测 |
| `AcousticScene` / `RoomAcousticSnapshot` | `media/audio/acoustics/scene` | 世界 → 不可变场景快照 |
| `MirrorPlaneDiscovery` | `media/audio/acoustics/geometry` | 房间 → 镜像面 |
| `ImageSourceSolver` | `media/audio/acoustics/geometry` | 一阶镜像源 |
| `ImageSourcePathSolver` | `media/audio/acoustics/geometry` | 2/3/4 阶路径与延迟预算 |
| `ReflectionTap` / `ReflectionPathTap` | `media/audio/acoustics/path` | 反射 tap 数据 |
| `AirAbsorption` / `OcclusionScanner` | `media/audio/acoustics/propagation` | 空气吸收与遮挡 |
| `SpeakerDataProfile` / `SubwooferAcoustics` | `media/audio/speaker` | 扬声器数据与低音炮模型 |
| `SpeakerDirectivityService` | `media/audio/speaker` | 指向性角度计算 |
| `CommonAcousticFirProfile` | `media/audio/acousticfir` | 声学 FIR 设计参数，不执行 |
| `CommonAcousticFirDesigner` / `CommonAcousticFirService` | `media/audio/acousticfir` | 目标频响设计、缓存与限流 |
| `LateReflectionBus` / `Vbap` | `media/audio/reflection` | 几何晚期反射总线与 VBAP |
| `LateReflectionSettings` / `LateReflectionTarget` | `media/audio/reflection` | 晚期总线设置与房间常数校准 |
| `VideoDecoder` / `VideoFrame` | `media/video` | 视频解码与帧 |
| `CinemaVideoTexture` / `FrameBufferPool` | `media/video` | 纹理上传与帧缓冲复用 |

## 7. 判断新类归属

1. 直接调用 OpenAL、管理 source/buffer/context？
   → `media/audio/playback`
2. 通用信号处理（FFT、Biquad、卷积、最小相位设计）？
   → `media/audio/dsp`
3. 声学频率响应配方（扬声器/角度/空气/材质/遮挡 → FIR 目标）？
   → `media/audio/acousticfir`
4. 只读世界，算房间/场景/几何/路径/传播？
   → `media/audio/acoustics` 下对应子包
5. 只处理视频帧、纹理、帧缓冲？
   → `media/video`
6. 同时驱动音频和视频、或管理 receiver/媒体生命周期？
   → `media` 根

线程间交接的 DTO 应放在生产者和消费者都能接受的中性包，不要反向依赖播放引擎。

## 8. 迁移步骤（已执行）

1. 新增 `client/media/audio`、`client/media/video`、`client/media/ffmpeg`。
2. 先移动纯计算/DSP 子树（`client/audio/*` → `media/audio/*`），只改 `package` / `import`：
   - `acoustics/` 拆成 `room` / `scene` / `geometry` / `path` / `propagation`
   - `directivity/` 与 `filter/` 合并进 `dsp/`
   - `environment/AirAbsorption` 移入 `acoustics/propagation/`
   - `speakerdata/` 与 `SpeakerDirectivityService` 合并进 `speaker/`
   - `fir/` 重命名为 `acousticfir/`
   - `reverb/LateReflectionBus`、`LateReflectionSettings`、`LateReflectionTarget`、`Vbap` 移入 `reflection/`
   - spike 类移入 `debug/`
3. 再移动音频运行时（`AudioChunk`、`CinemaAudioEngine`、`ReflectionSource`、
   `StreamingAudioDecoder`、`StereoUpmixer`、`MinecraftOpenAl`）。
4. 最后移动视频运行时（`VideoDecoder`、`VideoFrame`、`CinemaVideoTexture`、
   `FrameBufferPool`）。
5. 同步 `src/test` 的包名与 import。
6. 独立 commit；不与声学行为改动混合，便于 review 和回退。

迁移不改变任何声学行为、默认值或播放逻辑。

## 9. 日志约定

所有日志通过 `com.example.cinema.CinemaLog` 输出，统一格式：

```text
cinema <area>: <message>
```

- 底层只有一个 logger：`Cinema.LOGGER`（名称 `cinema`）。不要再创建 `cinema-*` 子 logger，也不要写全限定名 `com.example.cinema.Cinema.LOGGER`。
- 使用 `CinemaLog.info/warn/error/debug(area, message, args...)`，`area` 优先使用 `CinemaLog` 中的常量。
- 消息使用 SLF4J `{}` 占位符；需要堆栈时把 `Throwable` 作为最后一个参数，不要把异常拼进字符串。
- `area` 是稳定的子系统名，例如 `audio`、`audio decoder`、`player`、`receiver`、`video`、`media`、`config`、`pairing`、`acoustics`、`fir`、`speaker data`、`prewarm`、`spike`。
- 诊断命令（`/cinema_acoustics` 等）仍通过聊天输出；日志只用于定位，不替代游戏内听感/视觉验收。
- 测试中的基准输出可以继续用 `System.out`，它不属于运行时日志。
