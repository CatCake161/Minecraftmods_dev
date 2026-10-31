# Cinema P0 技术验证

这是影院系统的独立技术验证工程，不参与模组构建，也不修改 `src/` 下的模组代码。
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

测试素材由本机 FFmpeg CLI 8.1.1 生成：10 秒 H.264 + AAC 48 kHz，
`testsrc2` 动态测试图，`-movflags +faststart`。

## 生成测试素材

```powershell
.\spike\generate-testmedia.ps1
```

输出：

- `spike/testmedia/test720p30.mp4`：1280×720、30 fps、约 4 Mbps、AAC 128 kbps
- `spike/testmedia/test1080p60.mp4`：1920×1080、60 fps、约 8 Mbps、AAC 128 kbps

`spike/testmedia/` 已在 `.gitignore` 中忽略，不会提交。

## 运行

和主工程一样，Gradle 进程需要 JDK 25+：

```powershell
$env:JAVA_HOME='C:\Users\Whuch\.jdks\openjdk-26.0.2.1'
$f = (Resolve-Path 'spike\testmedia\test720p30.mp4').Path

# 元数据
.\gradlew -p spike run --args="info $f"

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

音频：AAC 48 kHz → 立体声 S16，0.5 秒一块，4 个流式缓冲。
实测 10 秒素材：

| 指标 | 结果 |
|---|---|
| 音频解码 | 480256 帧 / 10.005 s，耗时 1.2 s（**8.2× 实时**） |
| 音频时钟 vs 墙钟 | p95 **13.9 ms**，max **14.9 ms** |
| 音画同步 720p30 | 300/300 帧呈现，**0 丢帧**，迟到 p95 13.3 ms，max 66.7 ms |
| 音画同步 1080p60 | 599/600 帧呈现，**1 丢帧**，迟到 p95 13.3 ms，max 86.7 ms |

音频时钟稳定、单调，和墙钟的偏差远小于 50 ms 目标；作为视频母时钟可用。
100 ms 的丢帧阈值在本次测试中只触发了 1 次（1080p60，启动阶段）。

## 结论：GO

**技术路线确定为：FFmpeg（JavaCV）解码 + 单张动态纹理上传 + 独立 OpenAL 流式播放 + 音频时钟主导视频呈现。**

P0 通过标准：

- [x] 720p30 解码吞吐远高于实时
- [x] 720p30 每帧上传 < 2 ms（p50）
- [x] 音画偏差 < 50 ms（实测 p95 约 14 ms）
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

## 未覆盖 / P1 风险

- **OpenAL 与 Minecraft 共存**：spike 是独立进程。P1 必须在 Minecraft 客户端中验证
  第二套 OpenAL context / 设备与 `SoundEngine` 共存，并处理线程本地 context。
- **长片音频内存**：当前为了简化，先整段解码音频再播放。两小时立体声 48 kHz S16
  约 690 MB，正式实现必须改成有界队列的流式解码。
- **原生库体积与许可**：JavaCV/FFmpeg 各平台原生库较大（约 50–100 MB/平台），
  且涉及 LGPL/GPL 与 H.264 专利问题，发布前需要单独评估。
- **跨平台**：目前只测了 Windows x64，natives 也是 hardcode；macOS/Linux 需要在打包阶段处理。
- **屏幕几何**：spike 只画了一个全屏 quad；多方块屏幕的 UV 切分、遮挡和光影兼容性尚未验证。

## 下一步（P1）

1. 在 Minecraft 客户端里验证 OpenAL 独立 context 与 `SoundEngine` 共存。
2. 2×2 屏幕方块 + 投影仪方块，每个屏幕共享一张 `DynamicTexture`。
3. 流式音频解码 + 帧缓冲池 + 播放/暂停/停止/跳转。
4. 本地文件选择与最小播放命令，720p30 默认档位。
