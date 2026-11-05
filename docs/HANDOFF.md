# Cinema 交接清单

> 新会话请按顺序读：本文件 → [PLANNING.md](PLANNING.md) → [TODO.md](TODO.md)。
> 当前分支 `main`；开始工作前用 `git log -1` / `git status` 确认最新提交与工作区状态。

## 1. 当前状态

- Minecraft 1.21.1 + Fabric，Java 21；包名 `com.example.cinema`，模组 id `cinema`；当前版本 **Alpha 0.0.1**。
- 核心信号链已可玩：屏幕 → 投影仪 → AV Receiver → 5.1 扬声器。媒体由客户端本地解码，接收器与服务端保存播放状态和媒体指纹。
- 已通过游戏内验收：投影仪光学与 GUI M1/M2/M3、屏幕渲染与尺寸配置、AV Receiver M1/M2/M3/M4、四种扬声器、物理 5.1 路由、线材配对、设备/扬声器命名、座位乘坐与视角、Mod Menu 设置页。
- 剩余主要问题：64×64 屏幕配对/尺寸显示错误、BSL 下 wash 异常、无音轨长播未测、NVIDIA 纹理上传崩溃待复测。
- 规划中：多屏/多投影仪、LAN 状态同步、遥控器与远程配对、音轨/视频轨/字幕、AV Receiver `Video Processing`、可变声道布局/天空声道、房间声学。
- 已验收功能的细节见 §3；未完成事项统一维护在 [TODO.md](TODO.md)。

## 2. 必须遵守的架构约束

1. **AV Receiver 拥有媒体**
   - 媒体文件、FFmpeg 解码、播放/暂停/seek、音画时钟、播放状态都归接收器。
   - 接收器向投影仪输出视频信号，向扬声器输出 5.1 音频。
2. **投影仪只做物理/画面**
   - 只接收来自 AV 的视频信号。
   - GUI 只放光学、灯泡、wash、配对、物理模拟开关；不出现文件选择、codec、解码 FPS、丢帧、播放控制。
3. **扬声器只做回放**
   - 只接收接收器分配的声道，不直接读文件、不解码。
4. **配对是显式的**
   - 投影仪↔屏幕、接收器↔投影仪、接收器↔扬声器均由影院线材配对；不再自动扫描最近设备。
   - 屏幕组记录唯一 `ownerProjector`，同一屏幕只能被一个投影仪占用。
5. **GUI 改动即保存**
   - 按钮切换立即提交，滑块约 200 ms debounce，Close/Esc 前 flush；没有 Apply/Cancel。
   - 设备 GUI 默认不暂停游戏，可在 Mod Menu 设置页修改。
6. **交互统一**
   - 投影仪、AV 接收器、四种扬声器只有空手右键打开 GUI；手持物品时保留原版交互。
   - 投影仪 GUI 的正式设计见 `docs/design/PROJECTOR-GUI.md`；当前实现为 `ProjectorConfigScreen`。

## 3. 已完成能力（已验收）

### 3.1 投影仪与屏幕

- 投影仪光学：投掷比、自动/手动焦距、垂直/水平梯形、亮度、灯泡预热/冷却、手动 wash。
- 投影仪 GUI M1/M2/M3：状态区、光学/wash 编辑、Power、Throw/Focus Auto、Focus/Keystone/Wash/Warmup 四个物理模拟开关，改动即保存。
- 屏幕：同朝向、同平面矩形扫描；逐格渲染，修复特殊视角下整屏消失；BE 视距 512。
- 屏幕尺寸可配置为 1..64（默认 16×16），保存到 `config/cinema.json`；`-Dcinema.screen.maxSize` 优先。
- 投掷比自动模式按屏幕四角与视频宽高比拟合，保留约 2% 边缘余量，支持斜向安装。
- 显式配对：投影仪↔屏幕；配对距离上限 48 格；解除连接使用潜行右键设备 A、再潜行右键设备 B。
- 对焦模糊当前基于 mipmap/LOD，物理观感一般，列为后续改进（不阻塞）。

### 3.2 AV Receiver 与媒体

- M1：媒体/播放状态/音画时钟迁入接收器；`AvReceiverControlPayload`（C2S）+ 服务端校验；客户端 `AvReceiverPlaybackManager` 每 10 tick 同步状态，视频经 `ProjectorOptics` 路由到屏幕。
- M2：接收器 GUI（Media/Speakers/Signal/Settings）、默认库 + 外部媒体库、`lib/<id>/...` key、添加/移除/切换库、记住上次库/文件夹、缺文件提示、长文本截断/tooltip/滑块 pending/ESC 暂停同步。
- 媒体库扫描/探测结果按“库 + 文件夹”在当前游戏会话内缓存；Refresh 强制刷新，添加/移除库会使对应缓存失效。
- M3：每个接收器持有独立 `CinemaPlayer`、视频纹理和 `CinemaAudioEngine`/OpenAL context；两个接收器可同时播放，播放/暂停/seek/音量/静音互不影响。
- M4：配对扬声器按 FL/FR/FC/LFE/BL/BR 角色映射到六个 source；听者始终使用玩家实际耳朵位置/朝向，不使用皇帝位或声场校正；未配对声道回退虚拟 5.1。
- 立体声/单声道源自动上混 5.1（FL/FR 原样、FC 中置、LFE 120 Hz 低通、BL/BR 差信号）；主音量 dBFS（-60..+6），每只扬声器 Level（-12..+6 dB）。
- 4K60/HDR、HDR10/HLG、4K60 VP9/HEVC/AV1 已由用户游戏内验收；HDR 默认输出 720p、SDR 默认 1080p。

### 3.3 扬声器、座位与音频

- 四种箱体：落地式 `floorstanding_speaker`（两格高）、书架式 `bookshelf_speaker`（一格高）、中置 `center_speaker`（一格高，仅 FC）、低音炮 `subwoofer`（一格高，仅 LFE）。
- 落地式下半格持有 block entity，上半格为无 block entity 的延伸；破坏任一半只掉一个物品。
- 扬声器 GUI 可编辑名称、选择角色、查看/导入 speaker specs；角色选择通过 `SpeakerRolePayload` 提交服务端。
- Speaker Specs spike：`cinema/speaker_specs/profiles/` 本地 JSON、拖放导入、水平/垂直指向性与轴上响应、低音炮内置 `sub-omni`/`sub-cardioid`；尚未应用到实际音频。
- 接收器 Speakers 页：滚动列表、每只扬声器 `Level`、`Ping` 定位音 + 约 8 秒粒子光柱、自定义名称/角色/坐标/距离 tooltip。
- Seat 可右键乘坐；`LocalPlayerSeatMixin` 修复乘坐时视角平滑/滞后；Seat 不参与音频、不定义皇帝位。

### 3.4 配对、命名与设置

- 线材配对：投影仪↔屏幕、接收器↔投影仪、接收器↔扬声器；选择状态 60 秒超时/断线清除，区块卸载不会误清链接。
- 屏幕单投影仪占用：`ScreenBlockEntity.ownerProjector` 同步整个连通屏幕；第二个投影仪配对被拒绝；拆除/解除/改配会释放；旧存档重复链接自动清理。
- 设备命名：投影仪、接收器、屏幕、扬声器名称均服务端持久化/同步，最长 32 字符；显示在状态行、命令诊断和关联设备 GUI。
- Mod Menu：屏幕尺寸 1..64；三项原版行为开关（设备 GUI 暂停、空手右键打开 GUI、spike 修改世界）；Mod Menu 为软依赖。
- AV 接收器、投影仪、Speaker Specs、Mod Menu 设置页统一为“改动即保存 + Close”；投影仪/扬声器/设置页保留 Defaults。

## 4. 已知问题 / 风险

- **64×64 屏幕配对/尺寸显示错误（未修复）**：以 `-Dcinema.screen.maxSize=64` 启动并填充 64×64 屏幕后，投影仪 GUI / `/cinema_projector_client info` 能显示屏幕，但尺寸/坐标错误。定位前不要改相关渲染/配对代码；复现时记录 GUI 整行原文、`/fill` 坐标、投影仪坐标/朝向、屏幕 facing。
- **BSL 下 ambient wash 某些值画面异常**：待确认是白色 overlay 的 bloom/exposure 还是 alpha 曲线；无音轨墙钟长播也未测试。
- **NVIDIA `nvoglv64.dll` ACCESS_VIOLATION**：栈为 `CinemaVideoTexture.upload → glTexSubImage2D`，不是 Java 异常。已改为每个接收器跨 seek 复用视频纹理，并提供 `-Dcinema.video.glFinish=true` 诊断开关，待复测。
- **音频声学仍是规划**：见 `docs/design/AUDIO-ACOUSTICS.md`。EAXReverb 只计划用于晚期尾音；早期反射用一阶镜像源 + Spinorama 实测扬声器指向性。EFX 可用性、材质吸收、型号映射都需 spike/校准。
- Spinorama 数据默认 CC BY-NC-SA 4.0，与项目 CC0 不同；发布前必须做许可/attribution 决策。REST API 需要人工申请 Bearer key，key 只能放本地环境变量/转换工具，不能进仓库、发布包或日志。
- 一阶声学模型不覆盖衍射、散射、高阶反射和低频房间模态；LFE 低频峰谷/驻波不会正确重现。
- 当前物理路由固定 6 声道（5.1）；可变布局/天空声道仍是规划，见 `docs/design/CHANNEL-LAYOUTS.md`。
- 多屏/多投影仪、LAN 播放状态同步、资源重载、音频设备切换、两小时长片内存/队列仍未完成。
- 遥控器仍是占位物品；远程打开 GUI、远程配对、绑定/权限/距离/视线规则未实现。
- 旧 Git 历史仍含旧提交作者信息；工作树已清理。若要彻底隐私，需要重写历史并 force-push，或把仓库设为 private。
- 不要重新引入：test scene、visual spike、barrier 测试方块、遮挡阴影、镜头光晕、过热/通风。

## 5. 下一步优先级

1. **剩余问题修复与复测**
   - 定位并修复 64×64 屏幕配对/尺寸显示错误（先复现并记录，不盲改）。
   - 定位 BSL 下 wash 异常；补做无音轨墙钟长播。
   - 用 `-Dcinema.video.glFinish=true` 复测 NVIDIA 视频上传崩溃。
2. **投影仪配对诊断与远程控制**
   - 配对失败原因（距离/朝向/遮挡）与信号页诊断。
   - 遥控器绑定后从观众席远程打开 GUI、调整参数、发起/解除配对；线材保留为近距物理配对 fallback。
3. **媒体能力**
   - 音轨/视频轨/字幕选择与渲染；拖放导入媒体库；AV Receiver `Video Processing`（输出分辨率/缩放/色彩/HDR/帧率）。
4. **多屏 / 多投影仪 / LAN**
   - 同接收器多视频输出、LAN 播放状态同步、客户端缺文件/指纹校验、资源重载恢复。
5. **可变声道布局 / 天空声道**
   - 按 `CHANNEL-LAYOUTS.md` 分阶段泛化 `CinemaAudioEngine`/`AudioChunk`/`SpeakerRole`，先保持 5.1 零回归。
6. **房间声学**
   - 所有声学模拟原型/实验代码在 `acoustic-simulation` 分支开发，只有通过游戏内听感验收的部分才分段合并回 `main`。
   - 先做 A1d Spinorama 数据 spike、A0 EFX/EAXReverb spike 与 A1e 许可决策；不要为了声学阻塞主线。

## 6. 构建与运行

Gradle 需要 JDK 25+，游戏/编译目标 JDK 21：

```powershell
$env:JAVA_HOME='<JDK-25+>'
.\gradlew build
.\gradlew runClient
```

`build` 包含 Checkstyle 命名检查，配置在 `config/checkstyle/checkstyle.xml`，报告在 `build/reports/checkstyle/`；只跑检查可用 `.\gradlew checkstyleMain checkstyleClient`。

注意：开发客户端运行时不要执行 `clean build`。`clean` 会暂时删除 `build/classes`，正在运行的客户端懒加载 `CinemaConfigScreen$ScreenSizeSlider` 时会报 “config screen is not available ... is missing”；关闭并重启客户端即可。

FFmpeg（必须）：

```text
run/mods/ffmpeg/bin/ffmpeg.exe
run/mods/ffmpeg/bin/ffprobe.exe
```

发布版对应 `.minecraft/mods/ffmpeg/bin/`。也支持 `-Dcinema.ffmpeg.path=...`。

手动测试（不自动退出、不锁定玩家）：

```powershell
$env:JAVA_TOOL_OPTIONS="-Dcinema.spike.manual=true"
.\gradlew runClient --args="--quickPlaySingleplayer=spike"
```

自动测试不再搭场景；屏幕/投影仪需要手动放置。旧布局可参考：

```text
/fill -2 -59 4 1 -57 4 cinema:screen[facing=north]
/setblock 0 -59 -3 cinema:projector[facing=south]
```

然后：

```text
/cinema_play <本地文件绝对路径或媒体库 key>
/cinema_projector_client info
/cinema_projector_client wash 0.2
```

## 7. 关键文件

```text
src/main/java/com/example/cinema/Cinema.java
src/main/java/com/example/cinema/block/entity/ProjectorBlockEntity.java
src/main/java/com/example/cinema/block/entity/ScreenBlockEntity.java
src/main/java/com/example/cinema/block/entity/AvReceiverBlockEntity.java
src/main/java/com/example/cinema/block/entity/SpeakerBlockEntity.java
src/main/java/com/example/cinema/block/entity/SpeakerRole.java
src/main/java/com/example/cinema/block/entity/SpeakerType.java
src/main/java/com/example/cinema/item/CableItem.java
src/main/java/com/example/cinema/item/RemoteControllerItem.java
src/main/java/com/example/cinema/pairing/CinemaPairing.java
src/main/java/com/example/cinema/network/
src/main/java/com/example/cinema/command/ProjectorCommand.java

src/client/java/com/example/cinema/client/CinemaClient.java
src/client/java/com/example/cinema/client/gui/ProjectorConfigScreen.java
src/client/java/com/example/cinema/client/gui/AvReceiverConfigScreen.java
src/client/java/com/example/cinema/client/gui/SpeakerSpecsConfigScreen.java
src/client/java/com/example/cinema/client/gui/CinemaConfigScreen.java
src/client/java/com/example/cinema/client/media/AvReceiverPlayback.java
src/client/java/com/example/cinema/client/media/AvReceiverPlaybackManager.java
src/client/java/com/example/cinema/client/media/CinemaPlayer.java
src/client/java/com/example/cinema/client/media/CinemaAudioEngine.java
src/client/java/com/example/cinema/client/media/MediaLibrary.java
src/client/java/com/example/cinema/client/media/LibraryRegistry.java
src/client/java/com/example/cinema/client/render/ProjectorOptics.java
src/client/java/com/example/cinema/client/render/ScreenBlockEntityRenderer.java
src/client/java/com/example/cinema/client/render/ScreenGeometry.java
src/client/java/com/example/cinema/client/render/ScreenLimits.java
```

## 8. 用户偏好 / 协作规则

- 用中文沟通。
- 复杂视觉效果由用户**在游戏内**验收，不接受只发截图/只看日志判断通过。
- 不重新加入 test scene / visual spike；测试场景由用户手动放置。
- 删除功能前要明确确认。
- 不在仓库文件里写个人绝对路径、姓名、邮箱、私人媒体文件名。
- 测试时先确保 Minecraft 窗口置前。
