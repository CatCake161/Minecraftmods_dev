# Cinema 项目交接（2026-10-31）

> 新会话请先读这个文件，再读 `spike/P05-DESIGN.md` 和 `spike/README.md`。
> 当前 `main` 包含此交接文件；工作区应干净。

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

- P0：FFmpeg 解码、纹理上传、音频时钟、A/V 同步；JCodec 已排除。当前解码器已从 JavaCV 迁移到**外部 FFmpeg 进程**。
- P0.5-A：Minecraft 内 5.1 六路 mono source，同一设备时钟启动，0 sample skew。
- P0.5-B：世界空间屏幕、投影 UV、镜头在玩家后方 4 格、Sodium/Iris + BSL 屏幕显示正常。
- P0.5-C：
  - 真实 720p30 + 5.1 播放（126/291 帧测试，0 丢帧）
  - `/cinema_play`、`/cinema_pause`、`/cinema_seek`、`/cinema_stop`
  - 有界队列：视频 8 帧、音频 32 块；10 分钟 5.1 素材背压烟测通过
  - 视频帧缓冲池（避免 4K60 每帧 33 MB direct buffer 分配）
  - OpenAL 独立 device/caps 问题已修：现在**共享 Minecraft 的 device + AL caps**，context 进程级单例
- P1 第一刀：
  - `firstmod:screen` 屏幕方块 + block entity + renderer
  - 4×3 多方块屏幕扫描（`ScreenGeometry`，最大 16×16）
  - master 方块绘制整张视频面，避免接缝；视频 contain 比例加黑边
  - `firstmod:projector` 方块/朝向/模型（目前仅装饰，没有镜头几何）
  - 物品模型、blockstate、英文名、创造模式物品栏
  - 真实视频在 4×3 方块屏幕上显示通过（见 `spike/screenshots/p1-screen-block.png`）
- P1 第二刀（投影仪光学）：
  - `ProjectorBlockEntity`：投掷比、焦距/自动对焦、垂直/水平梯形校正、亮度、灯泡开关、4 秒预热/2 秒冷却
  - 屏幕自动配对（投影仪朝向前方最近的有效屏幕），屏幕四角按镜头 view/projection 计算 UV
  - 每个屏幕方块单独投影/裁剪，画面超出屏幕时裁掉、不足时保留黑边
  - 对焦：视频纹理 mipmap + `GL_TEXTURE_LOD_BIAS`；覆盖 `entityCutoutNoCull` 每次绘制强制最近邻的行为
  - 遮挡阴影：32×18 = 576 条镜头→屏幕射线，barrier 遮挡测试通过（96 格被遮挡，约 2–3 ms/次）
  - 环境光洗白：屏幕位置天空光/方块光 + 时间；固定正午测试时为约 0.26 alpha
  - `/cinema_projector` 服务端调参命令；`/cinema_projector_client` 客户端调参命令（手动测试用）
  - 修复 `ScreenGeometry` 东/西朝向 master/right 计算反了导致的画面错位/镜像
  - 自动测试环境：固定出生点/朝南、固定正午、晴天、关闭 mob 生成并清理动物、窗口焦点重试
  - 手动测试模式 `-Dfirstmod.spike.manual=true`：自动搭景+播放，但不截图、不退出、不锁定玩家
- 外部 FFmpeg 迁移：
  - 移除 JavaCV/JavaCPP 依赖；FFmpeg 成为**必须由用户安装的运行组件**
  - 从 gyan.dev 下载 full build，把 `ffmpeg.exe` / `ffprobe.exe` 放到 `<game>/mods/ffmpeg/bin/`（或 `mods/ffmpeg.exe`；也可用 `-Dfirstmod.ffmpeg.path=...`）
  - 视频解码：FFmpeg 子进程 → rawvideo RGBA；`showinfo` 解析每帧 PTS；帧缓冲池
  - 音频解码：FFmpeg 子进程 → S16LE 5.1 48000 Hz
  - 默认 `-Dfirstmod.video.maxHeight=1080`（4K 源自动降到 1080p；设为 0 保留原生，2160 允许 4K）
  - 默认 `-Dfirstmod.ffmpeg.hwaccel=auto`（使用 D3D11VA/DXVA2 硬件解码；设 `none` 关闭）
  - HDR10/HLG：检测 `color_transfer`，通过 `zscale + tonemap + BT.709` 转 SDR
  - 解码日志包含 codec、源/输出尺寸、pix_fmt、color_trc/primaries/space、fps

### 尚未完成

- 投影仪 GUI、风扇声、镜头光晕、过热/通风
- AV Receiver / Speaker / Seat 方块
- 接收器-扬声器配对、GUI
- 每个屏幕独立媒体/播放状态；目前所有屏幕共用全局 `CinemaPlayer`
- 服务端权威播放状态、LAN 同步、本地文件选择/指纹
- 两小时长片完整内存测试
- 资源重载/设备切换后的恢复
- 正式原生库打包、许可、跨平台

## 3. 关键架构

### 媒体播放

- `client/media/CinemaPlayer.java`：全局单播放器；视频/音频解码线程、8 帧视频队列、`CinemaVideoTexture`、A/V 同步。
- `Ffmpeg`：定位外部 ffmpeg/ffprobe（`mods/ffmpeg/bin`、`mods/ffmpeg.exe` 或 `-Dfirstmod.ffmpeg.path`），ffprobe 读取 codec/尺寸/帧率/色彩元数据。
- `VideoDecoder`：外部 FFmpeg → rawvideo RGBA；`showinfo` 的 `pts_time` 作为视频 PTS；支持硬件解码、HDR tonemap、按 `maxHeight` 缩放；帧缓冲池。
- `StreamingAudioDecoder`：外部 FFmpeg → S16LE 5.1 48 kHz，0.5 s 块，写入 `CinemaAudioEngine` 的有界队列。
- `CinemaAudioEngine`：**共享 Minecraft 的 OpenAL device**，自己创建一个 context；六路 mono source；同一设备时钟 `alSourcePlayAtTimeSOFT`；`AL_SEC_OFFSET_LATENCY_SOFT` 作为可听时钟。
- `MinecraftOpenAl` + client mixin accessors（`LibraryAccessor` / `SoundEngineAccessor` / `SoundManagerAccessor`）读取 Minecraft 的 OpenAL device。
- `CinemaPrewarm`：client init 时后台检查外部 FFmpeg 版本，缺失时在日志中打印安装说明。

**FFmpeg 是运行时必须项**：缺少 `mods/ffmpeg/bin/ffmpeg.exe` 或 `ffprobe.exe` 时播放会失败并提示 gyan.dev 下载地址。

**不要**改回独立 device，也不要调用 `AL.createCapabilities()`；否则会重新出现 Minecraft `SoundEngine` 的 OpenAL 错误。

### 屏幕渲染

- `ScreenBlock`：`FACING` 水平朝向，`ScreenBlockEntity`。
- `ScreenBlockEntityRenderer`：只在 master 方块绘制；有配对投影仪时按镜头 UV 每块投影/裁剪，没有投影仪时保持黑屏。
- `ScreenGeometry`：扫描同 facing、同平面的连续屏幕矩形，最大 16×16；master 为 viewer 左/下角。
- 全局 `WorldRenderEvents.AFTER_ENTITIES` 先 `CinemaPlayer.presentActiveFrame()` 上传帧，再由 block entity renderer 绘制纹理。
- `CinemaVisualSpike` 是 P0.5 的测试世界 quad，仍保留用于诊断；与方块屏幕共用纹理。

### 投影仪光学

- `ProjectorBlockEntity`（`src/main/.../block/entity/`）：保存 throw ratio、focus distance（-1 = 自动）、keystone 垂直/水平角度、brightness、powered；服务端改参数后用 `getUpdatePacket`/`getUpdateTag` 同步到客户端。
- `ProjectorOptics`（client/render）：镜头位置 = 方块中心 + 朝向面外 0.52 格、y+0.42；自动找朝向前方最近屏幕；用 `lookAt + perspective` 得到镜头 view/projection，水平 FOV 由 `throwRatio` 决定。
- 屏幕 UV 不是简单铺满：每个屏幕方块的四角投到镜头空间，再按梯形参数做 UV 预畸变，最后裁剪到 0..1。
- `ScreenGeometry` 的 master/right：master 是观众左/下角；东/西朝向必须使用 `right = +z / -z`，并且 negative-right 时 surface origin 要加一格，否则画面会错位一格/镜像。
- 对焦：`CinemaVideoTexture` 每帧生成 mipmap；`setFilter` 被覆盖为始终 linear-mipmap；renderer 按 `|screen distance - focus|` 设置 `GL_TEXTURE_LOD_BIAS`。`entityCutoutNoCull` 每次绘制会调用 `setFilter(false,false)`，所以不能只在上传时设置过滤。
- 遮挡阴影：`ProjectorShadow` 32×18 网格，从镜头向屏幕点 raycast；只检测方块碰撞（实体阴影后置）。手工测试遮挡物用玻璃块，不再使用不可见的 barrier，避免留下幽灵方块。
- 环境光洗白：屏幕中心的 sky/block light + `getSkyDarken()`，以半透明白色 overlay 叠加；灯泡预热时同时降低视频顶点颜色。
- 亮度：`brightness` 0.05–2.0。≤1 用顶点颜色压暗；>1 用 shader `ColorModulator`（>1 的 float 增益）并在视频批次后立即 `endBatch`，避免影响其他绘制。顶点颜色是 0–255，不能直接表示过驱。
- 测试环境：自动测试固定在世界出生点 `(0.5, -60, 0.5)`、朝南、正午 6000、晴天、`doMobSpawning=false` 并清理 96 格内 Mob；手动模式不锁定玩家。

### 测试辅助

- `FirstModClient` 中的 `/cinema_*` 命令。
- 游戏内帮助：`/cinema_help [页码]` 或 `/cinema help [页码]`（共 6 页；文档源文件 `docs/CINEMA_COMMANDS.md`）。
- 手动测试（推荐复杂视觉效果）：`-Dfirstmod.spike.manual=true` 自动搭景并播放，但不截图、不退出、不锁定玩家；聊天栏会提示可用命令。
- 手动调参：`/cinema_projector_client info|throw [auto|ratio]|focus [auto|meters]|keystone <v> <h>|brightness <value>|power`
- 重新搭景：`/cinema_test_scene`（以当前视角重新生成 4×3 屏幕 + 后方投影仪）
- 删除测试场景：`/cinema_test_scene clear`（清理测试方块和附近遗留 barrier；当前客户端需重启后生效）
- 自动测试属性：
  - `firstmod.spike.audio=independent`
  - `firstmod.spike.visual=true`
  - `firstmod.spike.video=<absolute path>`
  - `firstmod.spike.video.seek=<seconds>`
  - `firstmod.spike.blocks=true`（自动生成 4×3 屏幕 + 后方投影仪）
  - `firstmod.spike.manual=true`（手动测试模式，优先于 `exit`）
  - `firstmod.spike.exit=true`（自动截图后退出）
  - 投影仪测试：`firstmod.spike.projector.throw`、`.focus`、`.keystone.vertical`、`.keystone.horizontal`、`.shadow`、`.debug`
- 自动测试会固定时间/天气/玩家位置，并把 Minecraft 窗口置前；手动测试只在首次成功置前后停止抢焦点。

## 4. 构建与运行

Gradle 进程需要 **JDK 25+**；游戏/编译目标是 **JDK 21**。

```powershell
$env:JAVA_HOME='C:\Users\Whuch\.jdks\openjdk-26.0.2.1'
.\gradlew build
.\gradlew runClient
```

测试世界（`run/saves/spike`）是之前用 `runServer` 生成的 superflat 世界；`run/` 不进 git。
**运行前必须安装外部 FFmpeg**（full build，来自 gyan.dev）：

```text
run/mods/ffmpeg/bin/ffmpeg.exe
run/mods/ffmpeg/bin/ffprobe.exe
```

也可以直接放 `run/mods/ffmpeg.exe`，或用 `-Dfirstmod.ffmpeg.path=C:\path\ffmpeg.exe`。
发布版对应 `.minecraft/mods/ffmpeg/bin/`。

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

手动测试（不自动退出，玩家可自由移动/转向）：

```powershell
$video=(Resolve-Path 'spike\testmedia\test720p30_51.mp4').Path
$env:JAVA_TOOL_OPTIONS="-Dfirstmod.spike.video=$video -Dfirstmod.spike.blocks=true -Dfirstmod.spike.manual=true"
.\gradlew runClient --args="--quickPlaySingleplayer=spike"
```

Sodium/Iris/BSL 只放在 `run/mods`、`run/shaderpacks`，不是模组依赖：

- Sodium 0.6.13+mc1.21.1
- Iris 1.8.7+1.21.1
- BSL 10.1.8
- `run/config/iris.properties` 当前指向 `BSL_v10.1.8.zip`

## 5. 重要坑/注意事项

1. **FFmpeg 是外部必须项**：不要把 JavaCV/FFmpeg natives 加回模组依赖。缺少 `mods/ffmpeg/bin/ffmpeg.exe`/`ffprobe.exe` 时 `CinemaPrewarm` 会打印 gyan.dev 安装说明。
2. **OpenAL**：共享 Minecraft device/caps；不要开第二 device；不要 `AL.createCapabilities()`；context 单例；shutdown hook 只关闭影院 context。
3. **可见光束已删除**，不要再加回自定义 RenderType 光束；Iris/BSL 下自定义 RenderType 兼容性差。
4. **投影仪位置**：镜头在观众后方/后墙；之前“屏幕正上方”的测试位置已明确否定。
5. **测试图案扫描线**已删除；不要再加运动对角线标记。
6. 屏幕方块当前是 **4×3**；配对投影仪时按镜头逐块投影，未配对时保持黑屏；黑边由黑色 block model 提供。
7. 所有屏幕共用一个全局播放器；多屏幕/每屏独立媒体尚未实现。
8. 音频播放/seek 使用重启解码器实现 seek，不是逐帧精确 seek。
9. FFmpeg 首次启动约几十毫秒；`CinemaPrewarm` 只检查外部二进制版本，不再加载 JavaCV。
10. `run/` 中的测试世界、mods、shaderpacks、testmedia 都不进 git；新机器需要重新生成。
11. **投影仪方块必须 `noOcclusion()`**：模型不是完整方块，如果按满方块遮挡处理，会把它下方/相邻方块的面裁掉，看起来像透明/幽灵方块。
12. **不要再用 barrier 做测试遮挡物**：barrier 不可见但有碰撞，测试场景切换后容易留下“幽灵方块”；现在用玻璃。`/cinema_test_scene clear` 会扫描玩家 64 格内已加载区块并清理残留 barrier。
13. 测试场景是**客户端方块**，不会写进服务端世界；重启客户端会消失。`CinemaVisualSpike` 是独立的世界空间 quad，不依赖屏幕/投影仪，测试方块投影前用 `/cinema_visual_spike off`。
14. **4K/60 性能**：默认 `-Dfirstmod.video.maxHeight=1080`，4K 源会缩到 1080p；`-Dfirstmod.ffmpeg.hwaccel=auto` 默认尝试 D3D11VA/DXVA2。HDR 通过 `zscale + tonemap` 转 BT.709；日志会打印源 codec/pix_fmt/color metadata。

## 6. 建议的下一步

### 投影仪光学（第一版已完成，待用户游戏内验收）

1. ✅ `ProjectorBlockEntity`：镜头位置、投掷比/变焦、焦距、梯形、亮度、灯泡预热。
2. ✅ 屏幕自动配对 + 镜头空间四角 UV + 逐块裁剪。
3. ✅ 对焦：mipmap + LOD bias。
4. ✅ 梯形校正 UV 预畸变。
5. ✅ 遮挡阴影 raycast mask（32×18）。
6. ✅ 环境光洗白。
7. ⬜ 风扇声、镜头光晕、过热/通风。
8. ⬜ 投影仪 GUI（当前用 `/cinema_projector` / `/cinema_projector_client`）。

### 然后

1. AV Receiver + Speaker + Seat 方块与 block entity。
2. 接收器 GUI：扬声器角色/音量/延迟/混响。
3. 每个屏幕独立 `CinemaPlayer` 或屏幕-播放器绑定。
4. 服务端权威播放状态 + LAN 同步 + 本地文件指纹。
5. 资源重载/设备切换恢复、两小时长片、原生库打包/许可。

## 7. 关键文件

```text
src/main/java/com/example/firstmod/FirstMod.java              # 方块/物品/BE 注册、创造模式物品栏
src/main/java/com/example/firstmod/block/ScreenBlock.java
src/main/java/com/example/firstmod/block/ProjectorBlock.java
src/main/java/com/example/firstmod/block/entity/ScreenBlockEntity.java
src/main/java/com/example/firstmod/block/entity/ProjectorBlockEntity.java
src/main/java/com/example/firstmod/command/ProjectorCommand.java
src/client/java/com/example/firstmod/client/FirstModClient.java   # 命令、自动/手动测试、渲染注册
src/client/java/com/example/firstmod/client/command/ClientProjectorCommand.java
src/client/java/com/example/firstmod/client/media/                # 播放器/解码/音频/纹理/预热
src/client/java/com/example/firstmod/client/render/CinemaVisualSpike.java
src/client/java/com/example/firstmod/client/render/ProjectorOptics.java
src/client/java/com/example/firstmod/client/render/ProjectorShadow.java
src/client/java/com/example/firstmod/client/render/ScreenGeometry.java
src/client/java/com/example/firstmod/client/render/ScreenBlockEntityRenderer.java
src/client/java/com/example/firstmod/client/mixin/                # Minecraft OpenAL device accessors
src/main/resources/assets/firstmod/                               # 方块模型/blockstate/语言
spike/README.md                                                   # P0/P0.5 实测数据、命令、坑
spike/P05-DESIGN.md                                               # 设计/验证矩阵/决策
spike/screenshots/                                                # P0.5 屏幕、真实视频、P1 4×3 屏幕截图
docs/CINEMA_COMMANDS.md                                           # 游戏内 /cinema_help 的完整指令文档
```

## 8. 用户偏好

- 用中文沟通。
- 测试游戏时**先把 Minecraft 窗口置前**；自动测试已包含多次 focus 重试。
- 如果动态画面/视觉效果不好判断，可以直接问用户；用户愿意提供帮助。
- **复杂视觉测试交给用户自己在游戏里检查**；不要只发截图或只看日志判断通过。需要时提供 `firstmod.spike.manual=true` 场景或让用户自行放置方块。
- 删除功能要明确确认；当前已删除：可见光束、测试图案扫描线、投影仪“屏幕正上方”位置。
