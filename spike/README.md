# Cinema P0 技术验证

这是影院系统的独立技术验证工程，不参与模组构建，也不修改 `src/` 下的模组代码。
扬声器/投影仪的完整设计取舍和 P0.5 验证矩阵见 [P05-DESIGN.md](P05-DESIGN.md)。
目标是在写方块和投影仪之前，先回答三个问题：

1. **解码**：FFmpeg/JavaCV 能否稳定解 720p30（以及 1080p60）？
2. **上传**：每帧 BGR24 → OpenGL 纹理的耗时是否可接受？
3. **同步**：独立 OpenAL 播放器的音频时钟是否稳定，能否作为视频母时钟？

## 环境

| 项目 | 版本 |
|---|---|
| OS | Windows 11 x64 |
| CPU | AMD Ryzen 9 8945HX（32 逻辑核心） |
| GPU | NVIDIA RTX 5070 Laptop |
| JDK（运行测试） | Temurin 21.0.13 |
| JDK（运行 Gradle） | 25+（本机 26） |
| LWJGL | 3.3.3（GLFW / OpenGL / OpenAL，Windows natives） |
| JavaCV | 1.5.14 + FFmpeg 8.1.2 |
| JCodec | 0.2.5 |

测试素材由本机 FFmpeg CLI 8.1.1 生成：10 秒 H.264 + AAC 48 kHz，立体声（左右声道不同频率），
`testsrc2` 动态测试图，`-movflags +faststart`。另有一个 5 秒 5.1 素材，每个声道使用不同频率。

## 生成测试素材

```powershell
.\spike\generate-testmedia.ps1
```

输出：

- `spike/testmedia/test720p30.mp4`：1280×720、30 fps、约 4 Mbps、AAC 192 kbps 立体声
- `spike/testmedia/test1080p60.mp4`：1920×1080、60 fps、约 8 Mbps、AAC 192 kbps 立体声
- `spike/testmedia/test51.mp4`：5.1 声道映射测试，FL/FR/FC/LFE/BL/BR = 440/554/659/80/880/988 Hz，AAC 384 kbps

`spike/testmedia/` 已在 `.gitignore` 中忽略，不会提交。

## 运行

和主工程一样，Gradle 进程需要 JDK 25+：

```powershell
$env:JAVA_HOME='C:\Users\Whuch\.jdks\openjdk-26.0.2.1'
$f = (Resolve-Path 'spike\testmedia\test720p30.mp4').Path

# 元数据
.\gradlew -p spike run --args="info $f"

# OpenAL 能力探测（含双设备/双上下文共存）
.\gradlew -p spike run --args="caps"

# 流式 source 的 AL_SEC_OFFSET 语义诊断
.\gradlew -p spike run --args="offset"

# 六只扬声器同 context、同一设备时钟启动，测声道间采样对齐
.\gradlew -p spike run --args="speakers"

# 5.1 解码与声道顺序验证
.\gradlew -p spike run --args="surround $(Resolve-Path 'spike\testmedia\test51.mp4').Path"

# 解码吞吐（FFmpeg vs JCodec）
.\gradlew -p spike run --args="decode $f"

# 解码 + 纹理上传 + 绘制（隐藏窗口）
.\gradlew -p spike run --args="upload $f"

# OpenAL 流式播放 + 音频时钟漂移（实际出声 10 秒）
.\gradlew -p spike run --args="audio $f"

# 端到端音画同步（可见 1280×720 窗口播放 10 秒）
.\gradlew -p spike run --args="sync $f"
```

`sync` 的窗口可以随时关闭以提前停止。

## 实测结果

### 1. 视频解码

同一进程先跑一遍预热，再统计第二遍。

| 素材 | FFmpeg（BGR24） | JCodec（YUV） |
|---|---|---|
| 720p30，300 帧 | **2585 fps**，耗时 0.116 s | 70.1 fps，耗时 4.28 s |
| 1080p60，600 帧 | **1156 fps**，耗时 0.519 s | 30.1 fps，耗时 19.96 s |

结论：FFmpeg 解码余量超过 30 倍；JCodec 在 1080p60 上只有 **0.5× 实时**，
720p30 也只有约 2.3× 实时，性能不足且没有为低端 CPU 留余量。

### 2. 纹理上传

`glTexSubImage2D` + `glFinish` + 绘制到 1280×720 窗口，统计每帧耗时。

| 素材 | 上传 p50 | 上传 p95 | 端到端吞吐 |
|---|---|---|---|
| 720p30 | **1.57 ms** | 2.12 ms | 415.8 fps |
| 1080p60 | **2.63 ms** | 4.80 ms | 242.8 fps |

首帧有一次纹理分配尖峰（约 85 ms），后续稳定。720p30 每帧上传不到 2 ms，
相对 33 ms 的帧预算可以忽略；**MVP 不需要 PBO / YUV shader**。

### 3. 音频与音画同步

音频：AAC 48 kHz → 真实立体声 S16（左右声道 440/554 Hz），0.5 秒一块，4 个流式缓冲。
实测 10 秒素材：

| 指标 | 结果 |
|---|---|
| 音频解码 | 480256 帧 / 10.005 s，耗时 1.2 s（**8.3× 实时**） |
| 混音器时钟 vs 墙钟 | p95 **10.6 ms**，max **11.5 ms** |
| 设备输出延迟 | **50–60 ms**（OpenAL Soft WASAPI） |
| 音画同步 720p30 | 299/300 帧呈现，**0 丢帧**，迟到 p95 3.3 ms |
| 音画同步 1080p60 | 598/600 帧呈现，**0 丢帧**，迟到 p95 3.3 ms |

**重要修正**：`AL_SEC_OFFSET` 是混音器位置，不是用户实际听到的位置。P0 最初把它
直接当作音频时钟，视频会比声音早约 50–60 ms。现在改用
`AL_SEC_OFFSET_LATENCY_SOFT`，并从音频 PTS 中减去设备延迟，视频目标改为
“正在到达扬声器的采样”的 PTS；上表的同步结果已包含这一修正。

`AL_SEC_OFFSET` 还是**相对当前队首 buffer** 的偏移，换 buffer 时会归零；
媒体时间必须用“队首 buffer 起点 + offset”计算。跨 buffer 减延迟时不能把结果
钳到队首起点，否则每个 buffer 开头都会产生约一个延迟长度的跳变。

## P0.5：扬声器与投影仪前置验证（进行中）

新增命令：`caps`、`offset`、`speakers`、`surround`。

### Minecraft 内测试

独立 Gradle 工程之外，模组里也有两个客户端测试入口（仅用于 P0.5）：

```powershell
# 先生成一个 superflat 测试世界（只需一次；也可以用现有世界）
$env:JAVA_HOME='C:\Users\Whuch\.jdks\openjdk-26.0.2.1'
# runServer 生成 run/spike 后，复制到 run/saves/spike

# 5.1 扬声器：独立 OpenAL context + 6 个 mono source
$env:JAVA_TOOL_OPTIONS='-Dfirstmod.spike.audio=independent -Dfirstmod.spike.exit=true'
.\gradlew runClient --args="--quickPlaySingleplayer=spike"

# 屏幕 + 投影 UV：自动启用、截图、退出
$env:JAVA_TOOL_OPTIONS='-Dfirstmod.spike.visual=true -Dfirstmod.spike.exit=true'
.\gradlew runClient --args="--quickPlaySingleplayer=spike"

# 真实 720p30 + 5.1：自动播放、截图、退出
$video=(Resolve-Path 'spike\testmedia\test720p30_51.mp4').Path
$env:JAVA_TOOL_OPTIONS="-Dfirstmod.spike.video=$video -Dfirstmod.spike.exit=true"
.\gradlew runClient --args="--quickPlaySingleplayer=spike"
```

也可以在游戏内使用 `/cinema_audio_spike`、`/cinema_visual_spike`、
`/cinema_play <path>`、`/cinema_pause`、`/cinema_stop`；
`/cinema_visual_spike screenshot` 手动截图。截图输出到 `run/screenshots/`。
带 `firstmod.spike.*` 属性的自动测试会在启动后把游戏窗口置前，便于观察。

Sodium/Iris/BSL 只作为测试环境放入 `run/mods`、`run/shaderpacks`，不进入模组依赖。

### 4. OpenAL 能力与双上下文

本机 LWJGL 3.3.3 自带 OpenAL Soft **1.23.1**：

| 能力 | 结果 |
|---|---|
| EFX | 1.0，最多 **2 个 auxiliary send** |
| HRTF | 支持（`ALC_SOFT_HRTF`） |
| 扬声器半径 | 支持（`AL_EXT_SOURCE_RADIUS`） |
| 真实输出延迟 | 支持（`AL_SOFT_source_latency`） |
| 设备时钟 | 支持（`ALC_SOFT_device_clock`） |
| 采样级定时启动 | 支持（`AL_SOFT_source_start_delay`） |
| 线程本地 context | 支持（`ALC_EXT_thread_local_context`） |
| B-format / 空间化 | 支持 |
| 双设备 + 双 context 同进程 | **通过**，切换回来后 `alGetError=0` |

双设备测试只证明“能开”，不等于 Minecraft 内与 `SoundEngine` 共存没有问题；
这仍是 P0.5 的必测项。

### 5. 5.1 解码与声道顺序

`test51.mp4` 六个声道使用不同频率。JavaCV/FFmpeg 请求 6 声道后实际得到 6 声道，
没有静默 downmix；主导频率矩阵为对角线：

| 声道 | 主导频率 |
|---|---|
| FL | 440 Hz |
| FR | 554 Hz |
| FC | 659 Hz |
| LFE | 80 Hz |
| BL | 880 Hz |
| BR | 988 Hz |

结论：**5.1 可以直接解码为 FL/FR/FC/LFE/BL/BR 六路单声道**，不需要额外 splitter。

### 6. 多扬声器同步、EFX、指向性

`speakers` 在同一个 context 中创建 6 个 mono source，按 5.1 位置摆放，
用 `alSourcePlayAtTimeSOFT` 安排到**同一个设备时钟时刻**启动：

- 六路 sample offset 完全一致：**0 samples / 0.000 ms skew**
- EFX EAX reverb send：创建、绑定成功
- 每 source 的 low-pass direct filter：绑定成功
- `AL_SOURCE_RADIUS`、`AL_CONE_INNER/OUTER_ANGLE`、`AL_CONE_OUTER_GAIN`：设置成功

这说明“每只扬声器一个 source、同一 context 内采样级同步”的路线可行。
尚未验证的是：真实长片流式播放、暂停/跳转/欠载后的重新对齐。

### 7. Minecraft 客户端内的独立 context

客户端命令/自动测试 `cinema_audio_spike` 在真实 Minecraft 中打开独立 device/context：

- 六路 sample offset 完全一致：**0 samples / 0.000 ms skew**
- EFX、device clock、start delay 均可用
- 关闭独立 context 后，Minecraft `SoundEngine` 仍能正常播放 UI 音效
- 客户端正常退出，没有 crash
- 不调用 `ALC.destroy()`，避免破坏 Minecraft 共享的 OpenAL 函数指针

结论：第一版采用**独立 context + 影院输出设备**配置；共享 Minecraft context
不再作为必经路线。

### 8. Minecraft 客户端内的屏幕与投影 UV

客户端命令/自动测试 `cinema_visual_spike` 渲染 4.8×2.7 的世界空间屏幕：

![P0.5 screen](screenshots/p05-screen.png)

- `DynamicTexture` 测试图每 3 帧上传，颜色条/灰度梯正确
- 投影仪 UV 由真实 view/projection 矩阵计算，镜头位于玩家后方 4 格，屏幕覆盖正确
- 32×18 = 576 条遮挡射线，单次约 **0.5–1.1 ms**
- 截图由 `Screenshot.grab` 自动保存到 `run/screenshots/`
- **可见光束已按决定删除**；不再渲染光锥/粒子束，也不再需要自定义 RenderType

关键渲染坑：

1. `AFTER_ENTITIES` 的 `matrixStack` 是 identity；要 `translate(origin - camera)`
   后提交局部坐标，不能直接提交 camera-relative 世界坐标。
2. 投影 UV 的 `lookAt` 必须用世界坐标；再减一次 lens 会让 `clip.w` 变负。
3. 屏幕顶点 light 用 `0xF000F0`；正式实现要按环境光做洗白，而不是永远 fullbright。
4. 正式屏幕应优先用 block entity renderer，而不是全局 `WorldRenderEvents` 临时 quad；
   当前 spike 只是为了先验证纹理/UV/矩阵。

尚未验证：多方块屏幕 UV、真实视频帧、遮挡阴影视觉效果、对焦/梯形、
资源重载/世界卸载。

### 9. Sodium / Iris 兼容性

| 环境 | 屏幕 |
|---|---|
| 原版 | ✅ |
| Sodium 0.6.13 | ✅ |
| Sodium + Iris 1.8.7（无光影包） | ✅ |
| Sodium + Iris + BSL 10.1.8 | ✅ |

![P0.5 screen under BSL](screenshots/p05-screen.png)

屏幕使用 `entitySolid`，Iris/BSL 能正常合成；可见光束删除后，之前自定义
RenderType 在 BSL 下不可见的问题也随之消失。

### 10. 真实 720p30 + 5.1 播放（P0.5-C）

客户端播放 `test720p30_51.mp4`（1280×720、30 fps、AAC 5.1）：

![P0.5 real video](screenshots/p05-video.png)

- 视频解码线程 → 8 帧有界队列 → `CinemaVideoTexture`
- 音频解码线程 → 32 块有界队列 → 六路 mono OpenAL source
- 本次自动测试：**126 帧呈现、0 丢帧**；六路 source 在同一设备时钟启动
- 播放/暂停/停止命令：`/cinema_play <path>`、`/cinema_pause`、`/cinema_stop`

**JavaCV + Fabric 的坑**：`javacv` 的传递依赖会污染 Fabric 开发环境 classpath，
导致 Fabric Loader/Mixin 的 preLaunch/plugin 类在两个 classloader 之间 cast 失败，
Sodium/Iris 直接崩溃。只保留 `javacv`/`ffmpeg`/`javacpp` 三件套并设置
`transitive = false` 后解决。

**剩余问题**：

1. JavaCV 首次加载约 **5.5–6 s**；正式播放前需要预热。
2. 独立 OpenAL context 停止后，Minecraft `SoundEngine` 退出时会打印
   `Deleting stream buffers: Invalid name parameter`（非致命）；后续可改共享
   context 或进程级单例。
3. seek 尚未实现；两小时长片仍需内存/背压测试。

## 结论：GO

**技术路线确定为：FFmpeg（JavaCV）解码 + 单张动态纹理上传 + 独立 OpenAL 流式播放 + 音频时钟主导视频呈现。**

P0 通过标准：

- [x] 720p30 解码吞吐远高于实时
- [x] 720p30 每帧上传 < 2 ms（p50）
- [x] 混音器时钟与墙钟偏差 < 20 ms（实测 p95 约 11 ms）
- [x] 设备延迟已测并可补偿（50–60 ms）
- [x] 经过延迟补偿后音画目标偏差 < 10 ms
- [x] 5.1 六声道可正确解码
- [x] 六只扬声器可在同一设备时钟上采样级同步启动
- [x] 1080p60 也可以作为可选档位运行

已排除 JCodec：纯 Java、无原生库的优势不足以抵消性能差距。

## 实现中验证到的关键点

1. **JavaCV 复用帧缓冲**：`Frame.image[0]` 在下次 `grabImage()` 后会被覆盖，
   跨线程传递必须拷贝。本 spike 每帧 `allocateDirect`；正式实现应使用帧缓冲池，
   避免 720p 每秒约 83 MB、1080p60 每秒约 373 MB 的分配压力。
2. **OpenAL 只接受直接内存**：`ShortBuffer.wrap(short[])` 会导致
   `EXCEPTION_ACCESS_VIOLATION`；必须用 `BufferUtils.createShortBuffer`。
3. **`alSourceUnqueueBuffers` 不推进 Java Buffer 的 position**：
   回收已播放缓冲时要按 `AL_BUFFERS_PROCESSED` 数量下标读取，不能 `flip()` 后遍历。
4. **纹理上传必须在 GL 线程**：解码线程只产出帧，上传和绘制留在渲染线程。
5. **启动预热**：首帧纹理解码/分配有约 85 ms 尖峰，正式实现应在播放前预分配纹理。
6. **输出延迟必须单独补偿**：`AL_SEC_OFFSET` 只到混音器；真实设备延迟约 50–60 ms。
   用 `AL_SEC_OFFSET_LATENCY_SOFT` 的第二个返回值修正视频目标。
7. **多扬声器不要各开一个 context**：同一 context 内六个 source 可以由同一个
   mixer callback 采样级同步；用 `alSourcePlayAtTimeSOFT` 安排同一设备时钟启动。

## 未覆盖 / P1 风险

- **OpenAL 与 Minecraft 共存**：spike 是独立进程。P1 必须在 Minecraft 客户端中验证
  独立 context / 设备与 `SoundEngine` 共存，比较“独立 context”和“共享 Minecraft
  context”两条路线，处理线程本地 context、设备切换和资源重载。
- **长片音频内存**：当前为了简化，先整段解码音频再播放。两小时立体声 48 kHz S16
  约 690 MB，5.1 约 2.1 GB；正式实现必须改成有界队列的流式解码和每声道 ring buffer。
- **原生库体积与许可**：JavaCV/FFmpeg 各平台原生库较大（约 50–100 MB/平台），
  且涉及 LGPL/GPL 与 H.264 专利问题，发布前需要单独评估。当前 spike 的
  `org.bytedeco:ffmpeg` 二进制来源和启用模块也要核对，不能默认按 LGPL 发布。
- **跨平台**：目前只测了 Windows x64，natives 也是 hardcode；macOS/Linux 需要在打包阶段处理。
  macOS 的 OpenAL/EFX 能力也需要单独探测。
- **屏幕几何**：spike 只画了一个全屏 quad；多方块屏幕的 UV 切分、遮挡和光影兼容性尚未验证。
- **投影仪**：投射几何/遮挡阴影/环境光洗白已经做了基础验证；对焦/梯形、
  block entity renderer、资源重载仍待验证。可见光束已删除。
- **LAN 同步**：媒体文件只在客户端本地，服务端只能同步播放状态和文件指纹；
  缺少文件的玩家需要明确的降级 UI。

## 下一步（P0.5 → P1）

1. **Minecraft 内 OpenAL 生命周期**：资源重载、音频设备切换、暂停/恢复。
2. **Minecraft 内扬声器原型**：六只 speaker block + receiver 的最小链路，
   播放 5.1 测试片，验证每声道定位、同步、遮挡 low-pass 和 reverb。
3. **投影仪视觉原型**：4×3 屏幕 + 投影仪方块，验证 dynamic texture 生命周期、
   多方块 UV、投掷比/梯形校正/对焦、射线遮挡阴影和 Sodium/Iris。
4. **流式管线**：有界音频 ring buffer + 视频帧池 + 播放/暂停/停止/跳转。
5. **本地文件选择与最小播放命令**，720p30 默认档位。
