# Cinema 项目交接（2026-10-31）

> 新会话请先读这个文件，再读 `spike/P05-DESIGN.md` 和 `spike/README.md`。
> 当前 `main` 包含此交接文件；上一功能提交为 `c0e24de`，工作区应为干净。

## 1. 项目目标与已锁定决策

项目：**Minecraft 1.21.1 + Fabric + Java 21** 的影院系统，包名 `com.example.firstmod`，模组 id `firstmod`。

已锁定：

- 第一版只支持**本地媒体文件**，单机 + LAN；不做公网媒体分发。
- 扬声器：**5.1 架构**（FL/FR/FC/LFE/BL/BR），保留立体声回退。
- 投影面：**专用屏幕方块**；任意地形/共面墙后置。
- 投影仪：投掷比/变焦、对焦、梯形、遮挡阴影、环境光洗白、灯泡预热/风扇声。
- **可见光束已删除**：不做光锥/粒子束；投影仪只投画面到屏幕。
- 新增 **AV 接收器方块**，主座位定义“皇帝位”/校准参考点。
- 投影仪镜头应在**观众后方/后墙**，不是在屏幕正上方；测试原型放在玩家后方 4 格、同高。
- 第一版编码：1080p H.264/AAC 默认；4K/HEVC/AV1 后置。

## 2. 当前进度

### 已完成

- P0：FFmpeg/JavaCV 解码、纹理上传、音频时钟、A/V 同步；JCodec 已排除。
- P0.5-A：Minecraft 内 5.1 六路 mono source，同一设备时钟启动，0 sample skew。
- P0.5-B：世界空间屏幕、投影 UV、镜头在玩家后方 4 格、Sodium/Iris + BSL 屏幕显示正常。
- P0.5-C：
  - 真实 720p30 + 5.1 播放（126/291 帧测试，0 丢帧）
  - `/cinema_play`、`/cinema_pause`、`/cinema_seek`、`/cinema_stop`
  - JavaCV 启动后台预热（约 7–8 s；预热后解码器启动约 0.1 s）
  - 有界队列：视频 8 帧、音频 32 块；10 分钟 5.1 素材背压烟测通过
  - OpenAL 独立 device/caps 问题已修：现在**共享 Minecraft 的 device + AL caps**，context 进程级单例
- P1 第一刀：
  - `firstmod:screen` 屏幕方块 + block entity + renderer
  - 4×3 多方块屏幕扫描（`ScreenGeometry`，最大 16×16）
  - master 方块绘制整张视频面，避免接缝；视频 contain 比例加黑边
  - `firstmod:projector` 方块/朝向/模型（目前仅装饰，没有镜头几何）
  - 物品模型、blockstate、英文名、创造模式物品栏
  - 真实视频在 4×3 方块屏幕上显示通过（见 `spike/screenshots/p1-screen-block.png`）

### 尚未完成

- 投影仪镜头位置、投掷比、焦距/对焦、梯形、遮挡阴影、环境光洗白
- AV Receiver / Speaker / Seat 方块
- 投影仪-屏幕配对、接收器-扬声器配对、GUI
- 每个屏幕独立媒体/播放状态；目前所有屏幕共用全局 `CinemaPlayer`
- 服务端权威播放状态、LAN 同步、本地文件选择/指纹
- 两小时长片完整内存测试
- 资源重载/设备切换后的恢复
- 正式原生库打包、许可、跨平台

## 3. 关键架构

### 媒体播放

- `client/media/CinemaPlayer.java`：全局单播放器；视频/音频解码线程、8 帧视频队列、`CinemaVideoTexture`、A/V 同步。
- `VideoDecoder`：JavaCV `FFmpegFrameGrabber` → RGBA direct buffers。
- `StreamingAudioDecoder`：5.1 S16，0.5 s 块，写入 `CinemaAudioEngine` 的有界队列。
- `CinemaAudioEngine`：**共享 Minecraft 的 OpenAL device**，自己创建一个 context；六路 mono source；同一设备时钟 `alSourcePlayAtTimeSOFT`；`AL_SEC_OFFSET_LATENCY_SOFT` 作为可听时钟。
- `MinecraftOpenAl` + client mixin accessors（`LibraryAccessor` / `SoundEngineAccessor` / `SoundManagerAccessor`）读取 Minecraft 的 OpenAL device。
- `CinemaPrewarm`：client init 时后台加载 JavaCV/FFmpeg。

**不要**改回独立 device，也不要调用 `AL.createCapabilities()`；否则会重新出现 Minecraft `SoundEngine` 的 OpenAL 错误。

### 屏幕渲染

- `ScreenBlock`：`FACING` 水平朝向，`ScreenBlockEntity`。
- `ScreenBlockEntityRenderer`：只在 master 方块绘制；视频四边 contain 到 4×3 屏幕内，外层黑边由黑色 block model 提供。
- `ScreenGeometry`：扫描同 facing、同平面的连续屏幕矩形，最大 16×16；master 为 viewer 左/下角。
- 全局 `WorldRenderEvents.AFTER_ENTITIES` 先 `CinemaPlayer.presentActiveFrame()` 上传帧，再由 block entity renderer 绘制纹理。
- `CinemaVisualSpike` 是 P0.5 的测试世界 quad，仍保留用于诊断；与方块屏幕共用纹理。

### 测试辅助

- `FirstModClient` 中的 `/cinema_*` 命令。
- 自动测试属性：
  - `firstmod.spike.audio=independent`
  - `firstmod.spike.visual=true`
  - `firstmod.spike.video=<absolute path>`
  - `firstmod.spike.video.seek=<seconds>`
  - `firstmod.spike.blocks=true`（自动生成 4×3 屏幕 + 后方投影仪）
  - `firstmod.spike.exit=true`（自动截图后退出）
- 自动测试会在启动后多次尝试把 Minecraft 窗口置前。

## 4. 构建与运行

Gradle 进程需要 **JDK 25+**；游戏/编译目标是 **JDK 21**。

```powershell
$env:JAVA_HOME='C:\Users\Whuch\.jdks\openjdk-26.0.2.1'
.\gradlew build
.\gradlew runClient
```

测试世界（`run/saves/spike`）是之前用 `runServer` 生成的 superflat 世界；`run/` 不进 git。
测试媒体在 `spike/testmedia/`（被 `.gitignore` 忽略）：

- `test720p30_51.mp4`：1280×720、30fps、AAC 5.1，10 秒
- `test10min_51.mp4`：320×180、10fps、5.1，10 分钟
- 生成脚本：`spike/generate-testmedia.ps1`

典型真实视频测试：

```powershell
$video=(Resolve-Path 'spike\testmedia\test720p30_51.mp4').Path
$env:JAVA_TOOL_OPTIONS="-Dfirstmod.spike.video=$video -Dfirstmod.spike.blocks=true -Dfirstmod.spike.exit=true"
.\gradlew runClient --args="--quickPlaySingleplayer=spike"
```

Sodium/Iris/BSL 只放在 `run/mods`、`run/shaderpacks`，不是模组依赖：

- Sodium 0.6.13+mc1.21.1
- Iris 1.8.7+1.21.1
- BSL 10.1.8
- `run/config/iris.properties` 当前指向 `BSL_v10.1.8.zip`

## 5. 重要坑/注意事项

1. **JavaCV 传递依赖**会污染 Fabric 开发 classpath，导致 Mixin/Loader 类加载器错误。必须保持：
   ```gradle
   clientImplementation('org.bytedeco:javacv:1.5.14') { transitive = false }
   clientImplementation('org.bytedeco:ffmpeg:8.1.2-1.5.14') { transitive = false }
   clientImplementation('org.bytedeco:javacpp:1.5.14') { transitive = false }
   ```
   不要恢复普通传递依赖。
2. **OpenAL**：共享 Minecraft device/caps；不要开第二 device；不要 `AL.createCapabilities()`；context 单例；shutdown hook 只关闭影院 context。
3. **可见光束已删除**，不要再加回自定义 RenderType 光束；Iris/BSL 下自定义 RenderType 兼容性差。
4. **投影仪位置**：镜头在观众后方/后墙；之前“屏幕正上方”的测试位置已明确否定。
5. **测试图案扫描线**已删除；不要再加运动对角线标记。
6. 屏幕方块当前是 **4×3**；master 渲染、contain 黑边；不是每块独立 UV 拼接。
7. 所有屏幕共用一个全局播放器；多屏幕/每屏独立媒体尚未实现。
8. 音频播放/seek 使用重启解码器实现 seek，不是逐帧精确 seek。
9. JavaCV 首次加载很慢，必须保留 `CinemaPrewarm`。
10. `run/` 中的测试世界、mods、shaderpacks、testmedia 都不进 git；新机器需要重新生成。

## 6. 建议的下一步

### 下一刀：投影仪光学

1. `ProjectorBlockEntity`：镜头位置、朝向、投掷比/变焦、焦距、梯形、亮度、灯泡状态。
2. 屏幕配对：投影仪找到面向的屏幕/屏幕控制器；用镜头空间计算屏幕四角 UV。
3. 对焦：按屏幕距离与焦平面差做降采样/多 tap blur。
4. 梯形校正：投影 UV 预畸变。
5. 遮挡阴影：镜头→屏幕 raycast shadow mask（已有 576 rays ≈ 0.5–1.1 ms 基线）。
6. 环境光洗白：屏幕位置 lightmap/时间。
7. 灯泡预热/风扇声：状态机 + 音频。

### 然后

1. AV Receiver + Speaker + Seat 方块与 block entity。
2. 接收器 GUI：扬声器角色/音量/延迟/混响；投影仪 GUI：焦距/梯形/灯泡。
3. 每个屏幕独立 `CinemaPlayer` 或屏幕-播放器绑定。
4. 服务端权威播放状态 + LAN 同步 + 本地文件指纹。
5. 资源重载/设备切换恢复、两小时长片、原生库打包/许可。

## 7. 关键文件

```text
src/main/java/com/example/firstmod/FirstMod.java              # 方块/物品/BE 注册、创造模式物品栏
src/main/java/com/example/firstmod/block/ScreenBlock.java
src/main/java/com/example/firstmod/block/ProjectorBlock.java
src/main/java/com/example/firstmod/block/entity/ScreenBlockEntity.java
src/client/java/com/example/firstmod/client/FirstModClient.java   # 命令、自动测试、渲染注册
src/client/java/com/example/firstmod/client/media/                # 播放器/解码/音频/纹理/预热
src/client/java/com/example/firstmod/client/render/CinemaVisualSpike.java
src/client/java/com/example/firstmod/client/render/ScreenGeometry.java
src/client/java/com/example/firstmod/client/render/ScreenBlockEntityRenderer.java
src/client/java/com/example/firstmod/client/mixin/                # Minecraft OpenAL device accessors
src/main/resources/assets/firstmod/                               # 方块模型/blockstate/语言
spike/README.md                                                   # P0/P0.5 实测数据、命令、坑
spike/P05-DESIGN.md                                               # 设计/验证矩阵/决策
spike/screenshots/                                                # P0.5 屏幕、真实视频、P1 4×3 屏幕截图
```

## 8. 用户偏好

- 用中文沟通。
- 测试游戏时**先把 Minecraft 窗口置前**；自动测试已包含多次 focus 重试。
- 如果动态画面/视觉效果不好判断，可以直接问用户；用户愿意提供帮助。
- 删除功能要明确确认；当前已删除：可见光束、测试图案扫描线、投影仪“屏幕正上方”位置。
