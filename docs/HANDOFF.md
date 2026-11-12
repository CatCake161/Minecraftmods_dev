# Cinema 交接清单

> 新会话请按顺序读：本文件 → [PLANNING.md](PLANNING.md) → [TODO.md](TODO.md)。
> 当前分支 `acoustic-simulation`（声学实验分支）；`main` 保持稳定基线。开始工作前用 `git log -1` / `git status` 确认最新提交与工作区状态。

## 1. 当前状态

- Minecraft 1.21.1 + Fabric，Java 21；包名 `com.example.cinema`，模组 id `cinema`；当前版本 **Alpha 0.0.1**。
- 核心信号链已可玩：屏幕 → 投影仪 → AV Receiver → 5.1 扬声器。媒体由客户端本地解码，接收器与服务端保存播放状态和媒体指纹。
- 已通过游戏内验收：投影仪光学与 GUI M1/M2/M3、屏幕渲染与尺寸配置（含 64×64）、AV Receiver M1/M2/M3/M4、四种扬声器、物理 5.1 路由、线材配对、设备/扬声器命名、座位乘坐与视角、Mod Menu 设置页、遥控器扫描/远程访问、设备配对菜单。
- 剩余主要问题：暂无阻塞项。视频上传默认采用 `glFenceSync` + 延迟回收帧缓冲，已由用户游戏内长测确认稳定、seek 速度符合预期；`glFinish` 每帧同步性能差，仅保留为诊断开关。BSL 下 wash 异常与无音轨长播按用户决定暂不处理。
- 规划中：多屏/多投影仪、LAN 状态同步、音轨/视频轨/字幕、AV Receiver `Video Processing`、可变声道布局/天空声道、房间声学。客户端目录重组已完成，目标与日志约定见 [design/ARCHITECTURE.md](design/ARCHITECTURE.md)。
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
- M2：接收器 GUI（Media/Speakers/Signal/Settings）、默认库 + 外部媒体库、`lib/<id>/...` key、添加/移除/切换库、每个接收器分别记住自己的库/文件夹/搜索词、缺文件提示、长文本截断/tooltip/滑块 pending/ESC 暂停同步。选择 >64 MiB 媒体时使用快速采样指纹（`q1:` 头尾各 4 MiB + size），避免播放前全量 SHA-256 阻塞。
- 媒体库探测结果持久化在 `<game>/cinema/media-cache.json`，按媒体 key + 文件大小 + 修改时间校验；打开文件夹时仍会重新列目录，因此新增/删除文件无需 Refresh 即可显示。Refresh 强制重新探测，添加/移除库会使对应缓存失效。
- M3：每个接收器持有独立 `CinemaPlayer`、视频纹理和 `CinemaAudioEngine`/OpenAL context；两个接收器可同时播放，播放/暂停/seek/音量/静音互不影响。seek 当前是完整播放器/解码器重启，但复用视频纹理与 OpenAL device/context/source 池，只 flush 音频队列和时钟，不重建音频后端；首帧与音频调度时刻对齐。
- M4：配对扬声器按 FL/FR/FC/LFE/BL/BR 角色映射到六个 source；听者始终使用玩家实际耳朵位置/朝向，不使用皇帝位或声场校正；**2026-11-12 起缺少物理扬声器的声道不再 fallback 到虚拟 5.1，而是不创建该 direct source**。
- 立体声/单声道源自动上混 5.1（FL/FR 原样、FC 中置、LFE 120 Hz 低通、BL/BR 差信号）；主音量 dBFS（-60..+18），每只扬声器 Level（-60..+12 dB），输出端由 `OutputLimiter` 限制在 -0.3 dBFS 以下。
- 纯音频媒体：媒体库识别 mp3/flac/wav/m4a/aac/ogg/opus/wma 等；播放时跳过视频解码、不要求投影仪，接收器/扬声器链路与视频文件一致（已通过游戏内验收）。
- seek：每次 seek 后 3 秒冷却，GUI 滑块与 ±10s 按钮同时禁用；冷却期内的连续请求合并为最后一次。修复了 grace 窗口内新 seek 被吞的问题，纯音频/视频不再出现“时间显示已跳但仍在播旧内容”。音频引擎复用 source 池；4K 感知延迟主要来自解码器重新打开和关键帧定位。
- 4K60/HDR、HDR10/HLG、4K60 VP9/HEVC/AV1 已由用户游戏内验收；HDR 默认输出 720p、SDR 默认 1080p。
- 视频上传生命周期：默认 `glFenceSync` + 延迟回收帧缓冲（GPU fence 信号后才归还源缓冲），仅在队列压力和关闭时 `glFinish` 兜底；已由用户游戏内长测确认稳定，seek 速度符合预期。每帧 `glFinish` 性能差，Mod Menu 的 `glFinish uploads` 或 `-Dcinema.video.glFinish=true` 仅作诊断开关。

### 3.3 扬声器、座位与音频

- 四种箱体：落地式 `floorstanding_speaker`（两格高）、书架式 `bookshelf_speaker`（一格高）、中置 `center_speaker`（一格高，仅 FC）、低音炮 `subwoofer`（一格高，仅 LFE）。
- 落地式下半格持有 block entity，上半格为无 block entity 的延伸；破坏任一半只掉一个物品。
- 扬声器 GUI 可编辑名称、选择角色、查看/导入 speaker data；角色选择通过 `SpeakerRolePayload` 提交服务端。
- Speaker Data：`cinema/speaker_data/profiles/` 本地 JSON、拖放导入、分页选择器（21 个 ASR 型号全部可选）、H/V 原始 194/200 点 + `-180°` 行、on-axis 全频段能量平均的 `-3 dB` 频率范围（整数 Hz；取网格内最低/最高达标频点，内部凹陷不截断；measured profile 加载时重算并忽略过期 JSON 值）、on-axis 全频段能量平均归一化；非低音炮 measured profile 的 low/high 固定，仅 `SPL gain dB` 可编辑；低音炮有 `sealed`/`ported` × `omni`/`cardioid` 四个内置 profile（默认 `sub-sealed-omni`，旧 id 自动迁移；`bass-reflex`/`bass_reflex`/`reflex` 是 `ported` 的旧别名），Low/High 始终手动可编辑（10–1000 Hz，默认跟随 profile：sealed 30–200 Hz、ported 20–200 Hz）；enclosure 参与实际声学：内置/未测量 sealed 用二阶 Butterworth 高通（内置 30 Hz）、ported 用四阶 Butterworth 高通（内置 20 Hz），measured profile 跳过解析模型，手动 band 用二阶 Butterworth 高通/低通实际带限，低音炮 FIR/源增益参考 `sqrt(low*high)`；profile 只生成到本地 `run/` 目录（不打包、不提交）；A2 起按 H/V 合成 4096-tap 最小相位 FIR 应用到直达声。
- A1 房间声学数据：客户端 `/cinema_acoustics` 输出六面房间盒、体积/表面积、材质吸收、RT60、听者与附近扬声器；6 轴×25 射线扇形（每条最多反射 2 次）、9×9 材质采样（裁剪到平面边界）、Fitzroy RT60（按 x/y/z 轴对面积加权，均匀吸收退化为 Eyring；不设上下限；RT60 仅用于诊断和 FIR 的 `f_s`，FDN 已删除）、1.5 m 平面迟滞、fallback 保留真实面并按缺失轴延伸 16 m/听者 ±8 m；盒内有界 32³/8 ms flood fill 计算有效体积/面积、开口面、轴对吸收与材质加权 α（incomplete 时回退盒值，10 s 重扫）；扬声器 36 档 10° 声学 yaw + Speaker Alignment Tool，模型由 BER 同步旋转；5 Hz 快照缓存/fallback；尚未接入音频，游戏内 dump 验证无明显问题。
- A2 直达声指向性：`SpeakerDirectivityService` 按听者/扬声器 H/V 角设计 4096-tap 最小相位 FIR（cepstrum，48 kHz），目标 = on-axis EQ + H/V 相对指向性，FIR 在 1 kHz 归一为 0 dB，总 1 kHz 偏移乘入 source gain；5° 缓存 + daemon worker 异步设计；`CinemaAudioEngine` 用 overlap-add FFT 卷积，2048-frame 小块/4 缓冲（≈171 ms 前瞻），FIR tail 跨块，滤波器切换时旧/新路径做 1024-frame（≈21 ms）交叉淡化；声学中心改用 `acousticFrontPosition()`；`/cinema_acoustics` 打印 `directivity ... maxErr`。实测 JBL/Genelec/Adam 样本最大误差约 1.1 dB（Genelec 最高）；空气吸收/遮挡/Java 距离衰减已在 A2 接入，A2b 几何/可见性/反射播放已实现，游戏内听感/CPU/多接收器验收待做。
- A2 音频修复（2026-11-07）：整条链路改为 32-bit float —— FFmpeg 输出 `f32le`，`AudioChunk`/`StereoUpmixer`/`FirProcessor` 全程 float，source buffer 使用 `AL_EXT_FLOAT32`，不再在 DSP 阶段硬钳到 16-bit；只有设备最终转换才量化。快速转头撕裂通过在音频线程把 20 Hz listener-relative 位置更新按 30 ms 时间常数、约 200 Hz 做半径 + 球面方向（slerp）插值；视角 yaw 直接取 `getViewYRot()`，俯仰时不回退固定北方。`AL_EXT_FLOAT32` 不可用时回退 `AL_FORMAT_MONO16`。扬声器通道 trim 范围当时改为 -60.0..+6.0 dB（A2g 后上限提高到 +12 dB）。
- A2 直达声环境（2026-11-08）：OpenAL 距离模型改为 `AL_NONE`，`CinemaAudioEngine` 在音频线程按平滑后的渲染半径计算 `1/max(r,1)`，source 位置只负责声像/HRTF。ISO 9613-1 空气吸收（Mod Menu 开关/温度/湿度，默认 20 °C、50 % RH、101.325 kPa；1 m 为 0 dB 参考点，仅对超过 1 m 的路径计算）、on-axis/指向性和遮挡 Butterworth 低通合并为**单个 4096-tap 最小相位 FIR**；`overallGain` = on-axis + H/V 在参考频率的电平（普通 1 kHz，低音炮带内参考），宽带遮挡衰减 30 ms 平滑。遮挡用 `BlockGetter.traverseBlocks` 累计碰撞形状 AABB 的实际穿行长度（AABB 必须从方块局部坐标平移到世界坐标后再与射线求交），按材质表产生每声道上限 40 dB 的宽带衰减 + 低通；屏幕/mod 家具透明；**缺少物理扬声器的声道不再创建虚拟 source，因此也不做距离/空气/遮挡虚拟 fallback**。`/cinema_acoustics` 新增空气配置与每扬声器 distanceGain/air8k/occlusion/cutoff/order/direct fir/taps 输出。
- 声学世界尺度（2026-11-12）：Mod Menu `Audio` 页新增 `World scale`，范围 **0.25–2.00 m/block**，默认 **1.00**，步进 0.05，保存到 `config/cinema.json` 的 `metersPerBlock`（`CONFIG_VERSION = 4`，旧配置迁移后保持 1.00）。方块几何、房间检测、渲染和投影光学仍全部按方块单位；`AcousticScale` 是唯一的“方块→米”换算入口，影响房间米制尺寸/面积/体积、Fitzroy RT60 秒数、Schroeder 频率、ISO 9613-1 空气吸收、距离衰减 `1/max(1 m, r)`、一阶/高阶反射路径延迟与 `1/max(L,1 m)` 增益、遮挡 dB/m 和 late bus damping。几何/反射发现容差（如 0.10 m 平面合并、1.5 m 迟滞）继续按方块单位，不随尺度缩放。尺度变化会调用 `AcousticScene.invalidate()`；`AcousticRoom.reflectionKey()` 纳入当前尺度，使 `MirrorPlaneDiscovery`、反射 FIR 和晚期路径缓存自动重建。`/cinema_acoustics` 输出 `scale=... m/block` 及米制体积/面积/RT60/延迟。`AcousticScaleTest` 覆盖换算、范围钳制、房间米制访问器与延迟预算；完整 `check` 已通过。投影仪焦距/屏幕距离、接收器扬声器距离、遥控扫描/配对距离等设备 GUI 仍以 **blocks** 显示，不经过声学尺度换算。游戏内 `1.00` / `0.50` / `0.33` 听感 A/B 待做。
- 接收器 Speakers 页：滚动列表、每只扬声器 `Level`、`Ping` 定位音 + 约 8 秒粒子光柱、自定义名称/角色/坐标/距离（blocks）tooltip。
- Seat 可右键乘坐；`LocalPlayerSeatMixin` 修复乘坐时视角平滑/滞后；Seat 不参与音频、不定义皇帝位。

### 3.4 配对、命名与设置

- 线材配对：投影仪↔屏幕、接收器↔投影仪、接收器↔扬声器；选择状态 60 秒超时/断线清除，区块卸载不会误清链接。
- 屏幕单投影仪占用：`ScreenBlockEntity.ownerProjector` 同步整个连通屏幕；第二个投影仪配对被拒绝；拆除/解除/改配会释放；旧存档重复链接自动清理。
- 设备命名：投影仪、接收器、屏幕、扬声器名称均服务端持久化/同步，最长 32 字符；显示在状态行、命令诊断和关联设备 GUI。
- Mod Menu（分页：Screen / Audio / Video / Behavior / Advanced）：屏幕尺寸 1..64；声学世界尺度 0.25–2.00 m/block（默认 1.00，步进 0.05，仅影响声学计算）、空气吸收开关/温度/湿度、early gain 调试滑杆、late reflections 开关与 trim；SDR/HDR 最大解码高度（120 px 步进，`0` = Source）、硬件解码；设备 GUI 暂停、空手右键打开 GUI、spike 修改世界；FFmpeg 路径（目录或可执行文件，空 = 自动探测）与 Check、FFmpeg info 日志、glFinish 上传诊断。改动即时保存（滑杆 250 ms 去抖，关闭时 flush），Defaults 重置；JVM override 会禁用对应控件并显示来源。视频项仅控制客户端解码与纹理上传，输出分辨率/处理归 AV Receiver Video Processing。Mod Menu 为软依赖。
- AV 接收器、投影仪、Speaker Data、Mod Menu 设置页统一为“改动即保存 + Close”；投影仪/扬声器/设置页保留 Defaults。

## 4. 已知问题 / 风险

- **原地 seek 仍未启用**：当前 seek 复用音视频资源但会重新打开 FFmpeg 解码器，4K AV1/HEVC 仍可能需要数秒才出首帧；原地 seek 状态机未重新设计前不要直接恢复。
- **BSL 下 ambient wash 某些值画面异常**：待确认是白色 overlay 的 bloom/exposure 还是 alpha 曲线；无音轨墙钟长播也未测试。两项按用户决定暂不处理，不阻塞当前验收。
- **音频声学：A2 直达声、A2b 几何/反射播放已验收，A2c-R 统一直达/反射 FIR 已实现、游戏内 A/B 待做**：见 `docs/design/AUDIO-ACOUSTICS.md`。直达声距离由 Java `1/max(r,1)` 计算（OpenAL `AL_NONE`），ISO 9613-1 空气吸收、on-axis/指向性和遮挡低通合并为**单个 4096-tap 直达 FIR**，遮挡宽带衰减 30 ms 平滑；空气开关/温度/湿度、早反调试 gain 在 Mod Menu。A2 已通过游戏内听感验收（距离/空气/遮挡/配置 UI、快速转头/移动无爆音或削波），遮挡扫描已修正为世界坐标碰撞盒并通过石墙/木板/玻璃/屏幕复测。A2b 几何/可见性已实现：`MirrorPlane`/`MirrorPlaneDiscovery`/`ImageSourceSolver`/`ReflectionProbe`，扬声器 26 方向射线（6 轴 + 12 棱对角 + 8 角对角）+ 房间 `samples>0` 种子 + 近共面容差合并（0.10 m），R 处真实墙面验证与 `S→R`/`R→L` 两段可见性，纯几何 JUnit 11 项 + 方向/合并 6 项通过，`/cinema_acoustics` 输出 planes/taps。A2b 播放已实现：`CinemaAudioEngine` 固定 32 个反射 OpenAL source（活动 tap 上限 24）、8192-frame 原始声道历史环、分数延迟 + 150 ms 平滑、30 ms 淡入/淡出、每 tap 4096-tap 反射 FIR、30 ms 增益/声像平滑；`AvReceiverPlayback` 以接收器实际物理扬声器在 ~5 Hz 规划 taps，反射 source 位置为镜像源，距离由 Java 按 `1/max(L,1)` 计算。A2c-R 已实现 `CommonAcousticFirProfile/Designer`，反射 FIR 含材质/遮挡/空气且保持全频带，`overallGain` 与直达声同为参考频率（普通 1 kHz，低音炮带内参考）；游戏内 A/B 待做。A0 `/cinema_efx_spike` 已通过游戏内验证：EFX/sharedCaps/真实设备 context=OK，max sends=2，5.1 wet 5 声道，HRTF enabled，reset=OK，CPU≈0.8%；真实设备听感/物理切换/硬件延迟留 A3/A4；材质/遮挡调参、型号映射仍需校准。
- **A2c-R 性能修复（2026-11-08）**：日志中发现 3 分钟内 1953 次直达 FIR 设计、平均 34 ms（峰值 15–22 次/秒），设计 worker 饱和。已优化 `overallDb`/材质曲线复用、512 点目标曲线预计算、8192 点 FFT 误差评估（实测约 1–2 ms），并加 **12 次 burst + 20 次/秒**直达设计 token bucket、24 + 30 次/秒反射预算、2048 条缓存按批淘汰。移动时可接近 20 Hz 刷新，位置/增益/遮挡宽带仍 20 Hz。
- **A2b tap 稳定性（2026-11-08）**：`AcousticRoom.reflectionKey()` 用边界/材质/fill 补丁指纹替代每 5 Hz 变化的 snapshot version 做 `MirrorPlaneDiscovery` 缓存键；bounded 反射点 margin 改为 0.10 m；`ImageSourceSolver.strongest` 对已有 `(channel, planeKey)` 增加约 1 dB 迟滞，避免移动时 tap 在 cap 边界反复进出。
- **A3 FDN 已删除（2026-11-12）**：2026-11-08 的 64-line FDN 听感验收不通过（高频“沙沙”、低频不足、整体音色不平衡），且几何 `LateReflectionBus` 已接管晚期场，因此已彻底删除 `LateReverb` / `LateReverbSettings` / `LateReverbTarget`、FDN DSP（输入扩散、调制、RT60 low/mid/high、DC 高通、pre-delay、Hadamard 去相关、mid-band 归一化）及相关测试。`DEFAULT_LATE_REVERB=false`，配置版本 2 仍会把旧配置迁移为 false；启动时不分配 wet source，运行时关闭会释放。晚期场只保留几何 order-2/3/4 bus、VBAP、LFE 参与、`LateReflectionTarget` 的房间常数校准和 `lateTrimDb`。低频模态仍留给 A5。
- **A3 重做：order-2 geometric late bus 已接入（2026-11-08）**：`ImageSourcePathSolver` / `ReflectionPathTap` 提供 2–5 阶几何；`LateReflectionBus`（6 声道、16384 history、512 tap cap、每 tap delay/gain + 一阶低通）已接入 `CinemaAudioEngine`，复用 lazy wet source。`AvReceiverPlayback` 在 5 Hz 反射更新里生成 order-2 路径并发布；`lateReverb` 开关现在驱动几何 bus；默认 `lateReverb=false`；30 ms fade-in、seek/reset 后重新应用 taps。真实房间 2 阶 105 条（约 2 ms），3 阶累计 418 条（约 34–42 ms）；3 阶/418 taps 合成 bus 约 19–20× realtime。**79 项测试通过。首轮游戏内听感：变化较微妙，但方向正确；无其他问题。** order-4 改为两阶段动态调度：**动态稳态**按 listener 移动 EMA 取 150–600 ms；**phase fast** 也使用可恢复 `ImageSourcePathSolver.Session`，每个 client tick 只跑 4 ms，按扬声器逐步完成 order-3；**phase deep** 同样每 tick 4 ms 完成 order-4（默认 visit cap 200k、path cap 512）。两阶段都不再按 200 ms 批量求解。移动 >0.15 m 中止。**premium per-tap**：一阶 24 + 最强 16 条二阶 + 8 条三阶走完整 4096-tap FIR；其余路径进 bus 并从 bus 剔除；反射池 56（历史值；当前 128 source 预算下为 116），bus maxTaps 512（当时 raw history 16384、延迟上限 160 ms，已被 A3-R4 取代）。79 项测试通过。低频模态仍走 A5。
- **A3 LFE 反射修复（2026-11-09）**：移除 `ImageSourceSolver` / `ImageSourcePathSolver` 的 `lfe` 排除参数；`AvReceiverPlayback` 和 `/cinema_acoustics` 不再跳过 LFE。LFE 现在与其他 5 声道一样参与一阶/二阶/三阶/四阶几何路径、距离/材质/遮挡/延迟/增益、premium 与 late bus 分类；6 路 wet source 的 LFE 输出不再为空，且不强制低通、不单独削弱。79 项测试通过。
- **A3-R3 lazy VBAP 已实现（2026-11-09）**：Premium 保留独立 `ReflectionSource`（精确位置、独立延迟/FIR/材质/遮挡）；Lazy 走 `LateReflectionBus` + VBAP。`Vbap.java` 实现精确 3D VBAP → 水平 VBAP → 最近扬声器 → 扩散 fallback，输出排除 LFE，权重能量归一化。`LateReflectionBus.Tap` 保存输入声道、delay、gain、damping、到达方向和输出声道/权重数组，bus 将每个 tap 累加到多个输出声道；`AvReceiverPlayback` 用 **listener → 最后反射点** 的方向（经 `toListenerSpace`）调用 VBAP，未配对声道不作为输出目标，无物理布局时回退到输入声道。LFE 作为源声道参与完整路径与 VBAP；LFE 扬声器不作为普通 VBAP 输出目标，低频发送由 A5 单独处理。Premium/lazy 划分依据路径增益、延迟、方向稳定性和资源预算。95 项测试通过；待游戏内听感验收。
- **A3 稳态 speakerGain 刷新（2026-11-09）**：`AvReceiverPlayback` 每次反射更新前计算 6 声道有效设置指纹（speaker level、profile id、profile SPL gain）；变化时立即作废 fast/deep late path build、清空 premium 2/3 source 和 late bus 稳态标记，并强制 `updateLatePathBus` 重新发布 order-2 taps。修复进入稳态后调 speaker level/profile 只影响一阶反射、不影响 late bus / premium 2/3 taps 的问题。
- **A3 日志诊断修复（2026-11-09）**：`busTaps` 现在报告 bus 实际接受的 tap 数（按 gain/delay 过滤后取 512 cap），并附带 `requested`；deep 日志输出 `requested`/`fastTaps`/`deepTaps`。`finishFastPhase` 在 `lateReverb` 刚开启、`latePathRequests` 尚未由反射更新填充时不再发布空稳态，修复 `busTaps=0` 假日志和 `upgrade=1794214274723 ms`（`latePathBuildStartMillis=0`）。`CommonAcousticFirService` 的 FIR 设计警告按 profile id 聚合：首次立即记录，之后最多每 10 s 一条并附抑制计数/最差误差；反射深零点的大 `maxErr` 属 4096-tap 最小相位 FIR 的已知限制。
- **A3-R4 房间感知几何延迟预算（2026-11-09）**：固定 160 ms 上限在大型房间会截断本来有效的 order-3/4 路径。新增 `ReflectionDelayLimit` / `ReflectionDelayBudget`，逐阶上限 `min(1.30 s, (order + 1) · roomDiagonal / 343, historySafe)`；raw history 与 late bus history 均扩到 **65536 frame**（`historySafe ≈ 1.323 s`，两条历史约 3 MiB/接收器）。一阶、order-2、fast order-3、deep order-4 和 `/cinema_acoustics paths` 统一使用该预算；超限路径丢弃并计入 `delayRejected`。48 m 立方房间 order-1/2/3/4 上限约为 485/727/970/1212 ms；无可用房间盒时回退到 `min(1.30 s, historySafe)`。premium order-2/3 仍限制在 150 ms 内使用完整 FIR，更长路径进入 late bus。`ReflectionDelayBudgetTest`、solver 与 late-bus 测试通过；完整 `check` 已通过；游戏内 48 m 房间验收待做。
- **声学世界尺度（2026-11-12）**：`World scale` 只改变声学米制换算，不改变方块网格、渲染或投影光学。尺度 < 1.00 会缩小房间米制尺寸和 RT60 秒数、降低空气吸收/遮挡累计、缩短反射延迟并抬高 `1/max(1 m, r)` 距离增益；> 1.00 方向相反。反射历史仍是固定 65536 frame（`historySafe ≈ 1.323 s`），尺度 2.00 时由 `ReflectionDelayBudget.historySafeSeconds` 截断，不动态扩容。尺度变化会使 `AcousticScene` 与反射/晚期缓存失效，但已有 OpenAL source 的当前 FIR 会在下一次刷新时替换。游戏内需重点检查 `scale`、房间体积/面积/RT60、`f_s`、反射延迟/`R/rc` 与距离衰减是否随尺度同向变化；1.00/0.50/0.33 A/B 尚未完成。
- **AV 接收器输出上限与限幅（2026-11-12）**：主音量范围改为 `-60..+18 dBFS`，扬声器 Level 改为 `-60..+12 dB`；`CinemaAudioEngine` 把 `AL_MAX_GAIN` 提到 **64**，修复 OpenAL 默认 `1.0` 上限导致正增益被钳的问题。新增 `OutputLimiter`：按 queued block（4 × 2048 frame ≈ 171 ms 前瞻）统计 direct/reflection/wet 每个 source 的峰值，用当前增益求每声道最坏混合峰值（同一输出声道的多只 direct source 会累加），再统一缩放所有 `AL_GAIN`；预测在 `queueBlock` 之后运行，因此刚排队的 block 也纳入前瞻。ceiling **-0.3 dBFS**，瞬时 attack、400 ms release，下限 -60 dB。wet 正 trim 也直接由 `AL_GAIN` 承担，不再预乘样本。它是前馈增益限制，不是后级软削波；OpenAL 仍负责最终混音。接收器状态行和 Signal 页显示 `Limiter -x.x dB`。`OutputLimiterTest` 覆盖 ceiling/attack/release/峰值。远距离听感与多接收器限幅活动验收待做。
- **OpenAL source 预算（2026-11-12 设计约束）**：每引擎最多 **128** 个 source。**每个声道可以接多只扬声器**，所以 direct source 按“已连接的真实扬声器”计数，不是固定 6 个。当前 5.1 每声道一只时为 **6 direct + 6 lazy wet + 116 reflection**；有 12 只扬声器时为 **12 direct + 6 wet + 110 reflection**。未来声道扩充必须先保留每只真实扬声器的 direct source，再用剩余预算动态管理一阶反射、二阶/三阶 premium tap 和 lazy wet；`MAX_DIRECT_SOURCES = 64`，反射池用 `reflectionSourcesFor(directSources)` 计算。`MAX_SOURCES` / `MAX_REFLECTION_SOURCES` 当前按 5.1 的 6 direct 预分配，限幅器峰值统计按实际 source 池遍历，并通过 `directSourceChannels[]` 把同一输出声道的多只 direct source 峰值相加。**运行时已核对**：LWJGL 3.3.3 / OpenAL Soft 1.23.1，默认每 context **256** 个 source（255 mono + 1 stereo）；两个独立 context 各分配 128 个 source 实测成功。`/cinema_acoustics` 会输出 `openal sourceLimit`，若用户通过 `alsoft.ini [general] sources` 把它降到 128 以下会记录警告，并按 `limit - direct - 6 wet` 缩小反射池。**OpenAL context 安全**：音频线程必须通过 `ALC_EXT_thread_local_context` 设置自己的 context；若该扩展不可用而 Minecraft 已拥有全局 context，引擎会拒绝启动而不是把全局 context 改成影院 context。关闭/重置时只清理线程本地 context，并恢复进入前的 `alcGetCurrentContext()`，避免 `SoundEngine` 出现 `Allocate new source: Invalid operation`。**2026-11-12 实机日志确认**：`threadLocal=true`，`OpenAL source limit: 256`，9 只扬声器时为 `9 direct + 113 reflection + 6 wet = 128`，未再出现 `Allocate new source: Invalid operation`。**L2a 已实现**：`AvReceiverPlayback` 不再每声道只取最近一只，每只已连接扬声器获得独立 direct source（位置/trim/指向性 FIR/空气/遮挡），同一声道通过 `directSourceChannels[]` 映射多只 source；`CinemaAudioEngine` 在布局变化时动态分配/释放 direct source，并把反射池收缩/恢复到 `128 - direct - 6 wet`。**反射与 late bus 也已按每只扬声器生成**：一阶反射使用 `sourceKey + channel + plane` 作为 tap key，按每只扬声器独立求解并保留每只扬声器的 top tap；late bus 的 order-2/3/4 路径按每只扬声器求解，`Vbap` 使用“全部物理扬声器 + 实际 5.1 声道映射”生成 wet 输出。一阶 tap 全局上限 **64**，每只扬声器最多先选 **5** 条，再用全局最强 tap 填满；premium order-2/3 仍为全局 16/8。反射/late bus 的 per-speaker 听感、CPU 和多扬声器延迟验收待做。
- **低音炮声学 + 反射 FIR 4096（2026-11-09）**：`CommonAcousticFirProfile.REFLECTION_TAPS` 改为 **4096**（8192 点 FFT），直达/反射同长度。新增 `SubwooferBand` / `SubwooferAcoustics`：内置/未测量低音炮按 enclosure 生成解析响应（sealed 二阶 Butterworth 高通、内置 30 Hz；ported 四阶 Butterworth 高通、内置 20 Hz；measured profile 跳过解析模型），手动 Low/High（10–1000 Hz）用二阶 Butterworth 高通/低通实际带限；低音炮 FIR/源增益参考 `sqrt(low*high)`（无 band 时回退 `2*lowHz`，钳位 20–20000 Hz），`overallGain` / `onAxisDirectivityDb` 包含箱体和手动 band，speaker-settings 指纹包含 low/high；`AcousticSpeaker` 携带 per-speaker settings。已知限制：lazy late bus 没有 per-tap FIR，LFE 的 late-bus 内容不受低音炮带限约束。
- **音频定位模型**：当前保留 `AL_SOURCE_RELATIVE = AL_TRUE`；距离衰减已由 Java 计算（`AL_NONE`），source 位置只负责声像/HRTF。世界坐标源迁移已完成评审，暂不迁移；A2b 反射 source 使用听者相对的镜像源坐标，无需迁移，见 `AUDIO-ACOUSTICS.md` §4.2.1。
- Spinorama 数据默认 CC BY-NC-SA 4.0，与项目 CC0 不同；发布前必须做许可/attribution 决策。REST API 需要人工申请 Bearer key，key 只能放本地环境变量/转换工具，不能进仓库、发布包或日志。
- 几何镜面模型不覆盖衍射、散射和低频房间模态；LFE 虽已参与高阶几何路径，但低频峰谷/驻波仍不会正确重现。低频管理与模态（含多低音炮）排在 A4 之后的 A5。
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
   - A0 EFX/EAXReverb 技术验证已通过，但当前 A3 不依赖 EFX；A1d 数据管线已完成（profile 本地生成）；A1 房间快照已实现，游戏内 `/cinema_acoustics` dump 验证无明显问题；A2 直达声距离/空气吸收/遮挡/指向性已接入（单个 4096-tap 联合 FIR）并通过游戏内听感验收；A2b 几何/可见性/反射播放已实现并验收（32 source 池、24 活动 taps、延迟/淡入淡出、4096-tap 反射 FIR），A2c-R 统一直达/反射 FIR 已实现、游戏内 A/B 待做；A3 已删除 FDN，改为几何 order-2/3/4 `LateReflectionBus` + VBAP + premium per-tap，默认关闭；A1e attribution 仍待补。不要为了声学阻塞主线。

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
src/client/java/com/example/cinema/client/media/audio/playback/CinemaAudioEngine.java
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

