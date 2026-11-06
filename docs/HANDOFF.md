# Cinema 交接清单

> 新会话请按顺序读：本文件 → [PLANNING.md](PLANNING.md) → [TODO.md](TODO.md)。
> 当前分支 `acoustic-simulation`（声学实验分支）；`main` 保持稳定基线。开始工作前用 `git log -1` / `git status` 确认最新提交与工作区状态。

## 1. 当前状态

- Minecraft 1.21.1 + Fabric，Java 21；包名 `com.example.cinema`，模组 id `cinema`；当前版本 **Alpha 0.0.1**。
- 核心信号链已可玩：屏幕 → 投影仪 → AV Receiver → 5.1 扬声器。媒体由客户端本地解码，接收器与服务端保存播放状态和媒体指纹。
- 已通过游戏内验收：投影仪光学与 GUI M1/M2/M3、屏幕渲染与尺寸配置（含 64×64）、AV Receiver M1/M2/M3/M4、四种扬声器、物理 5.1 路由、线材配对、设备/扬声器命名、座位乘坐与视角、Mod Menu 设置页、遥控器扫描/远程访问、设备配对菜单。
- 剩余主要问题：暂无阻塞项。视频上传默认采用 `glFenceSync` + 延迟回收帧缓冲，已由用户游戏内长测确认稳定、seek 速度符合预期；`glFinish` 每帧同步性能差，仅保留为诊断开关。BSL 下 wash 异常与无音轨长播按用户决定暂不处理。
- 规划中：多屏/多投影仪、LAN 状态同步、音轨/视频轨/字幕、AV Receiver `Video Processing`、可变声道布局/天空声道、房间声学。
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
- 显式配对：投影仪↔屏幕；配对距离上限 128 格；解除连接使用潜行右键设备 A、再潜行右键设备 B。
- 64×64 屏幕已验证：完整矩形下 GUI/命令的尺寸与 master 坐标正确；配对与光学解析范围均为 128 格。
- 配对诊断：超过 128 格、投影仪/屏幕朝向不对、屏幕已被占用等失败原因通过聊天提示；投影仪 GUI 状态区显示屏幕链接与 AV 信号状态（已通过游戏内验收）。
- 对焦模糊当前基于 mipmap/LOD，物理观感一般；按用户决定暂不处理（不阻塞）。

### 3.2 AV Receiver 与媒体

- M1：媒体/播放状态/音画时钟迁入接收器；`AvReceiverControlPayload`（C2S）+ 服务端校验；客户端 `AvReceiverPlaybackManager` 每 10 tick 同步状态，视频经 `ProjectorOptics` 路由到屏幕。
- M2：接收器 GUI（Media/Speakers/Signal/Settings）、默认库 + 外部媒体库、`lib/<id>/...` key、添加/移除/切换库、记住上次库/文件夹/搜索词、缺文件提示、长文本截断/tooltip/滑块 pending/ESC 暂停同步。选择 >64 MiB 媒体时使用快速采样指纹（`q1:` 头尾各 4 MiB + size），避免播放前全量 SHA-256 阻塞。
- 媒体库扫描/探测结果按“库 + 文件夹”在当前游戏会话内缓存；Refresh 强制刷新，添加/移除库会使对应缓存失效。
- M3：每个接收器持有独立 `CinemaPlayer`、视频纹理和 `CinemaAudioEngine`/OpenAL context；两个接收器可同时播放，播放/暂停/seek/音量/静音互不影响。seek 当前是完整播放器/解码器重启，但复用视频纹理与 OpenAL device/context/source 池，只 flush 音频队列和时钟，不重建音频后端；首帧与音频调度时刻对齐。
- M4：配对扬声器按 FL/FR/FC/LFE/BL/BR 角色映射到六个 source；听者始终使用玩家实际耳朵位置/朝向，不使用皇帝位或声场校正；未配对声道回退虚拟 5.1。
- 立体声/单声道源自动上混 5.1（FL/FR 原样、FC 中置、LFE 120 Hz 低通、BL/BR 差信号）；主音量 dBFS（-60..+6），每只扬声器 Level（-12..+6 dB）。
- 纯音频媒体：媒体库识别 mp3/flac/wav/m4a/aac/ogg/opus/wma 等；播放时跳过视频解码、不要求投影仪，接收器/扬声器链路与视频文件一致（已通过游戏内验收）。
- seek：每次 seek 后 3 秒冷却，GUI 滑块与 ±10s 按钮同时禁用；冷却期内的连续请求合并为最后一次。修复了 grace 窗口内新 seek 被吞的问题，纯音频/视频不再出现“时间显示已跳但仍在播旧内容”。音频引擎复用 source 池；4K 感知延迟主要来自解码器重新打开和关键帧定位。
- 4K60/HDR、HDR10/HLG、4K60 VP9/HEVC/AV1 已由用户游戏内验收；HDR 默认输出 720p、SDR 默认 1080p。
- 视频上传生命周期：默认 `glFenceSync` + 延迟回收帧缓冲（GPU fence 信号后才归还源缓冲），仅在队列压力和关闭时 `glFinish` 兜底；已由用户游戏内长测确认稳定，seek 速度符合预期。每帧 `glFinish` 性能差，`-Dcinema.video.glFinish=true` 仅作诊断开关。

### 3.3 扬声器、座位与音频

- 四种箱体：落地式 `floorstanding_speaker`（两格高）、书架式 `bookshelf_speaker`（一格高）、中置 `center_speaker`（一格高，仅 FC）、低音炮 `subwoofer`（一格高，仅 LFE）。
- 落地式下半格持有 block entity，上半格为无 block entity 的延伸；破坏任一半只掉一个物品。
- 扬声器 GUI 可编辑名称、选择角色、查看/导入 speaker data；角色选择通过 `SpeakerRolePayload` 提交服务端。
- Speaker Data：`cinema/speaker_data/profiles/` 本地 JSON、拖放导入、分页选择器（21 个 ASR 型号全部可选）、H/V 原始 194/200 点 + `-180°` 行、`-10 dB` 频率范围（整数 Hz）、on-axis 全频段能量平均归一化；measured profile 的 low/high 固定，仅 `SPL gain dB` 可编辑；profile 只生成到本地 `run/` 目录（不打包、不提交），尚未应用到实际音频。
- A1 房间声学数据：客户端 `/cinema_acoustics` 输出六面房间盒、体积/表面积、材质吸收、RT60、听者与附近扬声器；6 轴×25 射线扇形（每条最多反射 2 次）、9×9 材质采样（裁剪到平面边界）、Eyring RT60（不设上下限，A3 再 clamp EAX）、1.5 m 平面迟滞、fallback 保留真实面并按缺失轴延伸 16 m/听者 ±8 m；盒内有界 32³/8 ms flood fill 计算有效体积/面积、开口面和材质加权 α（incomplete 时回退盒值，10 s 重扫）；扬声器 36 档 10° 声学 yaw + Speaker Alignment Tool，模型由 BER 同步旋转；5 Hz 快照缓存/fallback；尚未接入音频，游戏内 dump 验证无明显问题。
- 接收器 Speakers 页：滚动列表、每只扬声器 `Level`、`Ping` 定位音 + 约 8 秒粒子光柱、自定义名称/角色/坐标/距离 tooltip。
- Seat 可右键乘坐；`LocalPlayerSeatMixin` 修复乘坐时视角平滑/滞后；Seat 不参与音频、不定义皇帝位。

### 3.4 配对、命名与设置

- 线材配对：投影仪↔屏幕、接收器↔投影仪、接收器↔扬声器；选择状态 60 秒超时/断线清除，区块卸载不会误清链接。
- 屏幕单投影仪占用：`ScreenBlockEntity.ownerProjector` 同步整个连通屏幕；第二个投影仪配对被拒绝；拆除/解除/改配会释放；旧存档重复链接自动清理。
- 设备命名：投影仪、接收器、屏幕、扬声器名称均服务端持久化/同步，最长 32 字符；显示在状态行、命令诊断和关联设备 GUI。
- Mod Menu：屏幕尺寸 1..64；三项原版行为开关（设备 GUI 暂停、空手右键打开 GUI、spike 修改世界）；Mod Menu 为软依赖。
- AV 接收器、投影仪、Speaker Data、Mod Menu 设置页统一为“改动即保存 + Close”；投影仪/扬声器/设置页保留 Defaults。

## 4. 已知问题 / 风险

- **原地 seek 仍未启用**：当前 seek 复用音视频资源但会重新打开 FFmpeg 解码器，4K AV1/HEVC 仍可能需要数秒才出首帧；原地 seek 状态机未重新设计前不要直接恢复。
- **BSL 下 ambient wash 某些值画面异常**：待确认是白色 overlay 的 bloom/exposure 还是 alpha 曲线；无音轨墙钟长播也未测试。两项按用户决定暂不处理，不阻塞当前验收。
- **音频声学仍是规划**：见 `docs/design/AUDIO-ACOUSTICS.md`。EAXReverb 只计划用于晚期尾音；直达声与早期反射都应用距离、空气吸收和 Spinorama 实测扬声器指向性，反射增益按物理链路计算。早期反射采用独立 OpenAL source（镜像源位置，每 tap 一个，上限 24–36/接收器）。空气吸收温湿度、早反调试 gain 放 Mod Menu；频带精度与指向性计算方法待用户确认。A0 `/cinema_efx_spike` 已通过游戏内验证：EFX/sharedCaps/真实设备 context=OK，max sends=2，5.1 wet 5 声道，HRTF enabled，reset=OK，CPU≈0.8%；真实设备听感/物理切换/硬件延迟留 A3/A4；材质吸收、型号映射仍需校准。
- **音频定位模型**：当前保留 `AL_SOURCE_RELATIVE = AL_TRUE`；世界坐标源迁移已完成评审，暂不迁移，需与 A2 声学一起做，见 `AUDIO-ACOUSTICS.md` §4.2.1。
- Spinorama 数据默认 CC BY-NC-SA 4.0，与项目 CC0 不同；发布前必须做许可/attribution 决策。REST API 需要人工申请 Bearer key，key 只能放本地环境变量/转换工具，不能进仓库、发布包或日志。
- 一阶声学模型不覆盖衍射、散射、高阶反射和低频房间模态；LFE 低频峰谷/驻波不会正确重现。
- 当前物理路由固定 6 声道（5.1）；可变布局/天空声道仍是规划，见 `docs/design/CHANNEL-LAYOUTS.md`。
- 多屏/多投影仪、LAN 播放状态同步、资源重载、音频设备切换、两小时长片内存/队列仍未完成。
- 遥控器扫描面板只做 Ping/Access；配对在接收器/投影仪配对菜单中完成。绑定/解绑仍保留但只影响物品 tooltip，HUD 已移除；多人并发与视线遮挡规则未实现。
- 旧 Git 历史仍含旧提交作者信息；工作树已清理。若要彻底隐私，需要重写历史并 force-push，或把仓库设为 private。
- 不要重新引入：test scene、visual spike、barrier 测试方块、遮挡阴影、镜头光晕、过热/通风。

## 5. 下一步优先级

1. **媒体能力**
   - 音轨/视频轨/字幕选择与渲染；拖放导入媒体库；AV Receiver `Video Processing`（输出分辨率/缩放/色彩/HDR/帧率）。
2. **多屏 / 多投影仪 / LAN**
   - 同接收器多视频输出、LAN 播放状态同步、客户端缺文件/指纹校验、资源重载恢复。
3. **可变声道布局 / 天空声道**
   - 按 `CHANNEL-LAYOUTS.md` 分阶段泛化 `CinemaAudioEngine`/`AudioChunk`/`SpeakerRole`，先保持 5.1 零回归。
4. **房间声学**
   - 所有声学模拟原型/实验代码在 `acoustic-simulation` 分支开发，只有通过游戏内听感验收的部分才分段合并回 `main`。
   - A0 EFX/EAXReverb 技术验证已通过（EAXReverb late tail 可用，FDN 仅回退）；A1d 数据管线已完成（profile 本地生成）；A1 房间快照已实现，游戏内 `/cinema_acoustics` dump 验证无明显问题，下一步进入 A2；A1e attribution 仍待补。不要为了声学阻塞主线。

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
src/client/java/com/example/cinema/client/gui/SpeakerDataConfigScreen.java
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
- `acoustic-simulation` 分支下的所有开发、代码实现和细节处理必须如实向用户汇报，由用户判断是否合理；不得自行决定声学行为、默认值或近似。
