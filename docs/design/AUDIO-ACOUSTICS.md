# 影院音频计算与房间声学设计

> 状态：分析与规划，尚未实现。本文评估 OpenAL EFX / EAXReverb、早期反射与扬声器指向性方案，
> 并给出与当前 Cinema 音频管线（`CinemaAudioEngine` + AV Receiver + Speaker）的集成计划。
> 相关文档：[项目规划](../PLANNING.md)、[待办](../TODO.md)、[AV Receiver](AV-RECEIVER.md)、[指令](../COMMANDS.md)。

## 1. 目标与边界

目标：

- 在现有 5.1 回放上加入**客户端本地**的房间声学层：直达声校准、一阶早期反射、晚期混响、距离/空气吸收、扬声器指向性。
- 保持接收器对媒体/播放状态的权威；声学计算不改变播放位置、音画时钟或服务端状态。
- 可开关、可调试、可回退：没有 EFX、没有扬声器方块、没有 Seat 时仍能正常播放。
- 为后续 M4 扬声器 5.1 路由和 Seat 皇帝位提供声学数据接口。

非目标：

- 不做完整波动声学、全阶路径追踪、衍射/边缘绕射、HRTF、房间模态求解。
- 不做服务端解码或服务端声学计算。
- 不把音频设置放进投影仪 GUI；声学属于 AV Receiver / Speaker 侧。
- 首版不追求“测量级”还原，只追求方向、距离、衰减和空间感合理。

## 2. 共享对话的要点

参考的 DeepSeek 对话主要讨论 OpenAL EFX 中 EAXReverb 的内部实现，以及声源指向性对混响/早期反射的影响。对话中的关键结论：

### 2.1 EAXReverb 的结构

- **早期反射**：4 条固定延迟线，经典实现长度约 2.99 / 5.46 / 7.69 / 9.71 ms，基于“平均 5 m 房间”的假设；
  使用 Householder/无损散射矩阵和循环反馈，输出两路去相关信号。
- **晚期混响**：4 条全通/延迟线加交叉反馈矩阵；`Diffusion` 决定混合系数，形成扩散尾音。
- **调制**：低频正弦驱动的分数延迟，`MODULATION_TIME` / `MODULATION_DEPTH` 控制，避免尾音过于静态。
- **回声**：独立的 `EAXEcho` 支路，`ECHO_TIME` / `ECHO_DEPTH` 控制。
- **频率塑形**：`GAINHF` / `GAINLF`、`DECAY_HFRATIO` / `DECAY_LFRATIO`、`HFREFERENCE` / `LFREFERENCE`。
- **声像**：`REFLECTIONS_PAN` / `LATE_REVERB_PAN` 是艺术化的 3D 方向控制，不是物理声源指向性。
- **衰减系数**：延迟线每循环的衰减可用 `powf(0.001, length / decayTime)` 表示（目标 -60 dB）。

> 这些代码片段来自 OpenAL Soft 经典 `alcReverb.c` 的资料；不同 OpenAL Soft 版本内部实现可能已重构。
> 我们实际通过 EFX 公共 API 使用 EAXReverb，不依赖其内部源码结构。

### 2.2 指向性与一阶反射

- 真实声源不是全向辐射；指向性会改变各方向反射的能量和频谱。
- 对话引用的心理声学研究认为：**精确处理直达声 + 一阶早期反射的指向性，通常足以支持声源朝向/定位感知**；
  更高阶反射和晚期扩散可以用方向无关的平均滤波近似。
- 这主要适用于“声源朝向/定位”感知，不等同于影院环绕声的全部感知目标（包围感、清晰度、低频感）。
  因此应把它当作工程启发式，而不是普遍定律。

## 3. 方法评估：可行性 vs. 声学合理性

| 方法 | 可行性 | 声学合理性 | 结论 |
|---|---|---|---|
| 整套 EAXReverb 直接使用 | 高：EFX 是 OpenAL 扩展，OpenAL Soft 实现了它；LWJGL 3.3.3 已带 `EXTEfx` 绑定 | 中低：早期反射固定为 ~5 m 房间，不随影院尺寸变化；晚期尾音是参数化/感知模型，不是几何声学 | **不整套使用**；只取晚期尾音，早期反射必须由房间几何决定 |
| EAXReverb 仅作晚期混响 | 高 | 中高：扩散尾音用统计模型是合理的；EAXReverb 的调制/频响/衰减参数够用 | **首选方案**；关闭其早期反射与回声，避免与自定义早期反射叠加 |
| 自定义一阶镜像源早期反射 | 中高：需要房间边界、扬声器位置/朝向、材质；客户端计算量可控 | 高（在几何声学近似内）：一阶反射是强离散反射，携带距离/方向线索；忽略衍射/散射/高阶是已知近似 | **采用**，作为核心声学计算 |
| EAXReverb 的早期反射 | 高 | 低：固定 5 m 房间模型，延迟和方向与我们的任意房间不匹配；容易产生错误的前期反射和梳状滤波 | 默认关闭；仅在完全没有房间几何时作为低电平 fallback |
| 自定义 FDN 晚期混响 | 中：需要 DSP、稳定性、调参；CPU 仍可接受 | 中高：统计混响模型；可做 6 声道湿声 | EFX 不可用或需要 5.1 湿声时的**回退/后续**方案，不作为首版主路径 |
| 用 `REFLECTIONS_PAN` / `LATE_REVERB_PAN` 当声源指向性 | 高 | 低：它们是混响方向的艺术化控制，不描述扬声器辐射随角度的变化 | **不采用**为物理指向性；最多用于晚期尾音方向的艺术调整 |
| 全阶路径追踪 / 实测 RIR 卷积 | 低：几何复杂、CPU/内存高、缺少测量数据 | 高但过度：对游戏内影院没有必要 | 不做；未来可评估离线/测量 RIR |
| 低频房间模态 / 多低音炮 | 中 | 高：小型影院低频模态明显，单低音炮会有峰谷 | 首版不建模；记为后续（模态 EQ、多低音炮、测量） |
| HRTF | 中 | 高（耳机场景） | 5.1 音箱回放首版不做；耳机空间化后续评估 |
| 数据驱动材质吸收 | 中高 | 中：Minecraft 方块没有真实吸声系数，只能近似 | **采用**，但明确是近似值，可调试/覆盖 |
| Spinorama 实测扬声器指向性 | 高：公开 REST API/JSON，含水平/垂直 SPL、CEA2034、3D 变体 | 高：真实测量、频率相关；比手写指向性模型可靠 | **采用为扬声器指向性数据源**；需处理型号映射、测量轴、2D 近似和 CC BY-NC-SA 许可 |

### 3.1 关键声学判断

1. **EAXReverb 的早期反射不能代表我们的房间。** 它的固定延迟线基于 5 m 房间，15–20 m 影院的第一次侧墙反射应在十几到几十毫秒，而不是 3–10 ms。
   把 EAXReverb 早期反射叠在自定义反射上还会造成双早期反射、梳状滤波和虚假的方向感。
2. **一阶镜像源 + 指向性是性价比最高的几何声学部分。** 对矩形/近似矩形影院，一阶反射是最强、最有方向性的早期能量；
   更高阶反射迅速扩散，用晚期混响统计近似更合理。
3. **晚期混响不需要逐反射计算。** 用 EAXReverb 的 late tail 或小型 FDN 提供扩散能量即可；关键是 RT60、频响和 wet/dry 合理。
4. **指向性应作用于“声源到反射点”的辐射角，以及必要时的听者侧角度。** 首版只做前者，并只把指向性用于反射相对增益；
   直达声在皇帝位近似 on-axis，暂不额外滤波，避免双重处理。
5. **低频不能用几何声学解释。** LFE/低频的波长与房间尺寸相当，模态、驻波和边界干涉占主导；首版不建模，明确这是限制。
6. **材质数据是近似。** Minecraft 方块没有吸声系数；用 block/tag 映射到低/中/高频吸收，必须允许调试和覆盖。
7. **只做扬声器指向性，不做原始声源指向性。** 对话讨论的是人声/乐器等原始声源的辐射指向性；
   我们的 5.1 bus 已经是混合后的声道，无法从中恢复每个原始声源的方向。因此本项目只建模**扬声器箱体**的辐射指向性，
   数据首选 **Spinorama 的实测水平/垂直 SPL 指向性**，不再手写猜测的指向性曲线。
8. **Spinorama 数据的许可要单独处理。** 测量数据默认是 CC BY-NC-SA 4.0（署名、非商业、相同方式共享），
   项目代码目前是 CC0；两者可以分开授权，但发布前必须做许可决策，不能默认把数据混进 CC0 发布包。

## 4. 当前代码约束

### 4.1 当前实际音频管线（M1/M2）

> 本节按当前代码核对；另一段共享对话里“物理 5.1 已实现”的早期推断不正确，以代码/本节为准。

```text
外部 FFmpeg（stdout, s16le, 48 kHz, 5.1）
  → StreamingAudioDecoder（0.5 s 定长 chunk，PTS = start + totalFrames/48k）
  → AudioChunk（交织 FL/FR/FC/LFE/BL/BR）
  → CinemaAudioEngine（解交织为 6 个 mono OpenAL source，4 buffer/source ≈ 2 s）
  → OpenAL device
```

- `StreamingAudioDecoder` 通过 `-map 0:a:0 -ac 6 -ar 48000 -channel_layout 5.1 -f s16le` 调外部 FFmpeg；chunk 为 24,000 帧（0.5 s），`ChunkSink` 遇到引擎满队列会反压 FFmpeg。
- `CinemaAudioEngine` 在当前线程创建自己的 OpenAL context，6 个 source 固定相对位置，`SOFTDeviceClock` + `SOFTSourceStartDelay` 对齐启动，`SOFTSourceLatency` 发布 `audiblePts`。
- 4 buffer/source × 0.5 s ≈ 2 s 缓冲；一阶反射的 80 ms 延迟完全放得下，但不能在这条链路上再加额外 lookahead。
- `AvReceiverPlayback` 根据接收器状态开/停本地解码器，按 `MAX_DRIFT_SECONDS` 做漂移校正；`AvReceiverPlaybackManager` 目前共享一个引擎，服务端拒绝第二个接收器同时播放。
- **当前 5.1 是虚拟布局**：6 个 source 不绑定 `SpeakerBlockEntity` 的位置/朝向；实体扬声器 5.1 路由（M4）尚未实现。因此声学层不能假设“已有物理扬声器位置”，M4 是 A2/A2d 的前置。
- 当前没有 EFX、没有房间声学、没有 Spinorama 指向性数据。

### 4.2 当前实现约束

- `CinemaAudioEngine`：6 个 mono OpenAL source，在 Minecraft 的 OpenAL device 上创建自己的 context；48 kHz；固定相对位置；`AL_INVERSE_DISTANCE_CLAMPED`；目前没有 EFX。
- 该 context 目前用默认属性创建，没有请求 `ALC_MAX_AUXILIARY_SENDS`；EFX aux send 数量/默认值需要在 spike 中确认。
- 音频线程通过 `AL.setCurrentThread(previousProcessCaps)` 复用 Minecraft 的函数指针；需要确认这种 setup 下 EFX 函数/扩展位是否可用，必要时改用当前 context 的 capabilities。
- `MinecraftOpenAl`：复用 Minecraft 的 device；设备切换/资源重载尚未完整处理。
- `AvReceiverBlockEntity`：目前只有媒体、播放、音量/静音、视频目标；没有声学字段。
- `SpeakerBlockEntity`：有 `role`、`gain`、`type`；`SpeakerBlock` 有 `FACING`；落地式为两格高，block entity 在下半格。接收器→扬声器路由尚未实现（M4）。
- `Seat` 方块尚未实现。
- `AvReceiverPlaybackManager`：M1/M2 共用一个音频引擎；M3 才拆每接收器独立引擎。
- 六个 source 目前都是 `AL_SOURCE_RELATIVE = AL_TRUE`，位置相对听者固定。M4 实体扬声器布局需要二选一：
  继续用“听者相对坐标”（每帧把世界坐标转成相对坐标）或改为世界坐标 + 显式 listener pose。
  两种方式都必须通过音频线程快照更新，不能在音频线程读世界/玩家状态。
- `StreamingAudioDecoder` 的 FFmpeg stderr 没有单独消费（`redirectErrorStream(false)`，靠 `-loglevel error` 降低输出）；极端情况下仍可能因 stderr 管道填满而阻塞，后续可加 drain 线程。
- `CinemaPlayer` 的 A/V 时钟使用音频设备时钟/PTS；声学处理不得改变 chunk 的 `startPtsSeconds` 或引入不可控 lookahead。

### 4.3 由此得到的顺序

- **A0（EFX 能力 spike）** 可以在 M2/M3 之后提前做，不依赖扬声器路由。
- **A2（一阶反射/指向性）** 依赖 M4 的扬声器角色/位置/朝向；没有 M4 时只能用当前虚拟 6 source 布局做原型，不能验收真实指向性。
- **A4（接收器 GUI 开关/校准）** 依赖 M4 的 Speakers/Settings 页。
- Seat 不是绝对阻塞项，但没有 Seat 时“皇帝位”只能用玩家位置，影院校准不稳定。

## 5. 目标音频图

```text
FFmpeg 5.1 PCM（48 kHz, FL/FR/FC/LFE/BL/BR）
        │
        ▼
[5.1 channel bus]
        │
        ├─► 直达声校准：距离/空气吸收/遮挡/（可选）指向性
        │
        ├─► 一阶早期反射：镜像源 + 材质吸收 + 扬声器指向性
        │        └─ 稀疏 delay/gain/low-pass taps，按 5.1 声道路由
        │
        └─► 晚期混响 send：EAXReverb（仅 late tail）或 FDN 回退
                 └─ wet return
        │
        ▼
六个 mono OpenAL source（虚拟布局或 M4 实体扬声器位置）
        │
        ▼
OpenAL device / Minecraft 音频输出
```

原则：

- **干声路径不被延迟**；反射是额外的延迟抽头，不引入 lookahead。
- 早期反射在 Java 侧按 5.1 声道混合；晚期混响优先交给 OpenAL EFX。
- 声学参数更新与音频渲染解耦；音频线程只消费不可变快照。

## 6. 数据模型

```text
AcousticRoom
  bounds            六个平面 / 包围盒
  volume             m³
  surfaceArea        m²
  absorption         按频段/材质统计
  rt60               秒
  valid              几何是否可信
  updatedAt

AcousticSpeaker
  role               FL/FR/FC/LFE/BL/BR
  type               floorstanding/bookshelf/center/subwoofer
  position           世界坐标（含高度偏移）
  facing             水平朝向
  gain               校准增益
  directivityProfileId
  acousticCenterY    声学中心（高音轴）高度偏移

DirectivityProfile
  id                 例如 kef-r11 / kef-ls50 / kef-r2c / sub-omni
  brand/model        被测音箱
  source             测量来源（ASR/Erin's Audio Corner/厂商等）
  license            测量数据的许可证/署名
  changes            对数据的修改（归一化/降采样/量化等）
  quality            High/Medium/Low
  frequencies        1/3 octave 频带
  onAxisSpl[band]    轴上响应（绝对 SPL，与 H/V 同尺度）
  horizontalAngles   -180..180
  verticalAngles     -90..90
  horizontalSpl      水平指向性原始 SPL
  verticalSpl        垂直指向性原始 SPL
  is3D               是否有完整 3D/球面数据

AcousticListener
  position           座位/玩家眼睛
  facing             房间前方/屏幕方向
  seated             是否使用皇帝位

ReflectionTap
  sourceChannel      0..5
  delaySamples       相对直达声的延迟
  gain               相对直达声的宽带增益
  bandGains          低/中/高（或更多频带）指向性增益
  direction          到达听者的方向
  channelWeights     路由到 5.1 的权重

RoomAcousticSnapshot
  version
  room
  speakers
  reflectionTaps
  reverbParams
  settings
```

- 几何快照在客户端线程计算，音频线程只读；用 `volatile` 引用切换，不在音频线程分配对象。
- 更新频率建议 2–5 Hz；房间方块变化、扬声器变化、玩家/座位移动超过阈值时强制刷新。
- 声学开关/电平建议最终存到接收器 block entity（服务端权威）；几何本身是客户端本地推导，不上传。

## 7. 算法设计

### 7.1 房间检测与材质

**房间边界**

- 从听者/座位向 ±X、±Y、±Z 六个方向做有界 raycast（例如 48 m），取最近碰撞面作为六个平面。
- 验证六个平面是否形成包含听者的近似凸盒；不满足时：
  - 仍可用包围盒做粗略 late reverb；
  - 关闭一阶早期反射，或使用低电平 fallback。
- 扫到的方块决定材质；可在命中点附近采样多个方块，避免单点误差。
- 屏幕/幕墙、地板、天花板、侧墙、后墙分别记录；屏幕可作为低吸收/中等吸收材质处理，避免前墙强反射造成梳状滤波。

**材质吸收**

- 建议数据驱动：block/tag → `{absorptionLow, absorptionMid, absorptionHigh, scattering}`。
- 首版默认分类（仅示意，最终以试听校准）：
  - 羊毛/地毯：高吸收，HF 更高；
  - 木板：中低吸收；
  - 石砖/混凝土：低吸收；
  - 玻璃：低吸收但可透声/需要特殊处理；
  - 树叶/雪：中高吸收；
  - 水：低吸收、强反射；
  - 屏幕：低到中等吸收，避免强前墙反射。
- 面积加权平均吸收：

```text
α_avg = Σ(S_i · α_i) / ΣS_i
RT60  ≈ 0.161 · V / (S · α_avg)          // Sabine，低吸收
RT60  ≈ 0.161 · V / (-S · ln(1 - α_avg)) // Eyring，高吸收
```

- RT60 建议 clamp 到 0.3–2.5 s；过长的尾音在游戏内会掩盖对白。
- 材质吸收是近似值，必须提供调试/覆盖入口。

### 7.2 直达声

- **距离衰减只做一次。** 推荐 M4 后把 source 放到真实扬声器位置，由 OpenAL 的 inverse distance 处理直达声距离；自定义 DSP 只计算反射/混响的相对增益，避免双重衰减。
- 若继续使用固定虚拟布局，则在 Java 侧按扬声器到听者的距离计算增益，并关闭/固定 OpenAL 的距离模型，二者只能选一个。
- **空气吸收**：高频随距离衰减；可用一阶低通/高频 shelf 近似，或 EFX 的 air absorption 参数。首版可先只对反射/混响做，直达声保持中性。
- **遮挡**：直达声 raycast；被挡住时低通 + 衰减。EFX 可用 `AL_DIRECT_FILTER`；没有 EFX 时在 Java 侧对相应声道滤波。
- **指向性**：见 7.4。首版只用于反射相对增益；皇帝位直达声近似 on-axis。

### 7.3 一阶早期反射（镜像源）

对每个扬声器 `s` 和每个有效平面 `p`：

1. 计算镜像源 `s' = mirror(s, p)`。
2. 求线段 `s' → listener` 与平面 `p` 的交点 `r`；若 `r` 不在平面边界内，跳过。
3. 检查 `s → r` 与 `r → listener` 的可见性（可选，但推荐）；被遮挡则跳过或衰减。
4. 路径长度 `L = |s' - listener|`；直达距离 `d = |s - listener|`。
5. 延迟 `Δt = (L - d) / c`，`c ≈ 343 m/s`；过小（< 1 ms）或过大（> 80 ms）可跳过。
6. 相对增益：

```text
G = (d / L) · R_p · D(θ_source→r) / D(θ_source→listener) · S_p
R_p = sqrt(1 - α_p)
```

   其中 `α_p` 是能量吸声系数，`R_p` 是压力反射系数；`D` 为按频带查 7.4 表的指向性，
   `S_p` 为散射/扩散修正（首版可设 1）。`G` 按频带计算并 clamp，避免贴墙时爆炸。
7. 按 7.4 的 Spinorama 指向性表，计算该反射角在各频带的增益；再叠加材质 HF 吸收和空气吸收。
   首版至少用低/中/高 3 个频带（或 3 段 Linkwitz-Riley 分频），条件允许再用 1/3 octave 多频带/短 FIR。
8. 到达方向 `dir = normalize(listener - r)`；按 5.1 布局做 VBAP/最近声道路由，得到 `channelWeights`。
9. 每个扬声器最多保留若干最强 taps；全局上限建议 24–36，避免延迟线/CPU 膨胀。

补充：

- 只做一阶；二阶及以上交给晚期混响。
- 镜面反射假设；不做衍射/边缘绕射。柱子、屏幕边框、家具的散射不建模。
- LFE 默认不参与早期反射，避免低频梳状滤波；低频交给独立低频管理（多低音炮/模态 EQ，后续）。
- 反射增益默认比直达声低 6–12 dB；提供总 early reflection 电平。
- 参数变化时交叉淡化 100–200 ms；延迟变化用分数延迟/交叉淡化，避免 zipper noise。

### 7.4 扬声器指向性（Spinorama 实测数据）

**范围**

- 只做扬声器箱体指向性，不做原始声源指向性；5.1 bus 已经混合，无法恢复每个原始声源的方向。
- 指向性用于：一阶反射的源侧辐射角；可选地用于离轴直达声。不用于 EAXReverb 的 `REFLECTIONS_PAN` / `LATE_REVERB_PAN`。

**数据源与格式**

- Spinorama 提供站点/API/测量 JSON；可用测量名包括 `SPL Horizontal`、`SPL Vertical`、`SPL Horizontal Contour`、`SPL Vertical Contour`、3D 变体、`CEA2034`、`Early Reflections` 等。API 不可用时用站点导出或仓库数据。
- 从 Spinorama 取三组数据：**水平指向性**（`SPL Horizontal`）、**垂直指向性**（`SPL Vertical`）、**轴上响应**（on-axis SPL）。
  归一化时用轴上响应作为每个频带的 0 dB 参考；如果有 3D/球面数据，优先用 3D，否则用 H/V 两切面做 separable 近似。
- 选 High 质量（Klippel NFS/消声室）、尽量同一测量来源/版本；记录 measurement source、版本、日期和质量。
- 数据做 1/3 octave 降采样、归一化、量化（例如 0.1–0.5 dB）；角度按原始或 5°/10° 网格保留，不足则插值；生成紧凑资源，不要把整站原始数据打包。

**四类箱体的代表型号（候选，最终以数据可用性/试听决定）**

| 方块类型 | 候选 Spinorama 型号（示例） | 说明 |
|---|---|---|
| floorstanding | KEF R11 / Revel F328Be | 塔式、较宽水平扩散 |
| bookshelf | KEF LS50 Meta / Genelec 8030C | 书架/监听 |
| center | KEF R2c / Revel C208 | MTM 中置，注意垂直 lobing |
| subwoofer | 无合适数据时用 omni 模型 | Spinorama 以全频音箱为主；低音炮通带内近似全指向 |

- 这些只是候选，不代表已经确认数据版本/许可；A1d 数据管线要逐个确认。
- 方块是风格化模型，不需要与真实型号完全一致；但要在 tooltip/文档中写明“指向性 profile 来源”，避免误导。
- 低音炮：通带内按全指向；如果找到高质量 sub 测量，再替换。LFE 默认不参与早期反射/混响。

**角度与坐标**

- 扬声器朝向由 `SpeakerBlock.FACING` 定义；声学中心取高音轴高度偏移：落地式约 0.9–1.0 m，书架式约 0.6–0.8 m，中置约 0.6 m，低音炮约 0.3 m（按模型再校准）。
- 对反射点 `r`，计算从扬声器到 `r` 的方向，投影到扬声器局部坐标得到水平角 `θh` 和垂直角 `θv`。
- `θh` 归一化到 -180..180，`θv` 到 -90..90；H/V 表做双线性插值；3D 表做三线性/最近邻插值。
- 归一化：每个频带减去 `onAxisSpl[band]`；只保留相对指向性，扬声器声压由 GUI 的 `SPL gain dB` 调整。

**渲染**

- 首版建议 3 频带（例如 <300 Hz / 300–2k / >2k）增益 + 可选 HF shelf；用 Linkwitz-Riley 或相位兼容分频，避免分频点染色。
- 每个反射 tap 的 `bandGains` 由该反射角查表得到；再乘材质 `sqrt(1-α)`、距离 `d/L` 和散射修正。
- 直达声：皇帝位通常 on-axis，暂不额外滤波；如果做离轴听者，再用同一 profile 的带增益（可选）。
- 不建议直接用一个“低通截止频率”概括指向性；实测曲线可能有轴向隆起/分频 lobing，3 频带是最低限度，1/3 octave/短 FIR 更忠实但更贵。
- 如果 3 频带不够，升级到 1/3 octave 多频带（例如 10 段）或 32–64 tap FIR；CPU/延迟在 A2d 测量后决定。

**数据管线**

1. 选型号/测量版本（High quality，记录 source/license）。
2. 通过 API/JSON 获取 `SPL Horizontal` / `SPL Vertical`（或 3D）。
3. 解析角度/频率/SPL 与轴上响应；用 on-axis 数据归一化 H/V；1/3 octave 重采样；缺失角度插值；量化。
4. 生成 `assets/firstmod/directivity/<id>.json`（或二进制）；带 metadata：brand/model/source/license/changes/quality/date。
5. 运行时只读表，不在音频线程解析 JSON；启动/资源重载时加载。
6. 数据文件单独标注许可证；不默认并入 CC0 代码许可。

**许可（重要）**

- Spinorama 测量数据默认 **CC BY-NC-SA 4.0**：署名、非商业、相同方式共享；测量特定条款优先。
- 项目代码目前是 CC0；可以把代码与数据分开授权，但发布包必须包含 attribution/license、标明对数据的修改（changes），且不能把数据用于商业用途。
- 若未来要商业化/闭源分发，必须：取得权利方许可、改用许可宽松的测量、或让用户本地导入数据（不随模组分发）。
- 这不是法律意见；发布前需要确认每个 measurement 的 source/license，并在仓库加 `docs/THIRD-PARTY.md`/数据目录 LICENSE。

#### 7.4.1 Spike 实现（本地 profile / 客户端 GUI）

已落地的第一个 directivity spike（只做数据/UI/求值，不改变播放）：

- **内置低音炮模式**：`sub-omni`（全指向）和 `sub-cardioid`（心形指向）。
  - 心形按 3D `0.5 * (1 + cos θ)` 计算，0° = 0 dB，90° ≈ -6 dB，180° 截断到 -60 dB。
  - 其他箱体先提供 `unmeasured`（omni 占位）profile，等真实 Spinorama 数据导入。
- **本地 profile**：`<game>/cinema/speaker_specs/profiles/*.json`；支持把 `.json` 拖到 GUI 导入，文件大小限制 1 MB。旧 `speaker_directivity` 文件夹会尝试一次性迁移。
- **三组测量数据**：measured profile 包含水平指向性、垂直指向性、轴上响应（`onAxis`）；H/V 用轴上响应归一化，三者必须使用同一 SPL 尺度。
- **简单参数**：客户端 GUI 可编辑频响范围 low/high Hz、`SPL gain dB`，并循环选择 profile；类型默认保存到 `settings.json`，具体扬声器保存到 `assignments.json`。
- **角色选择**：per-speaker GUI 内直接循环合法角色；Done 通过 C2S `SpeakerRolePayload` 提交到服务端，服务端校验距离/方块/箱体类型后写入 block entity。
- **按扬声器保存**：`assignments.json` 以“维度 + 方块坐标”为 key，保存该扬声器自己的 profile 和参数；没有 assignment 的扬声器回退到类型默认。
- **求值**：`SpeakerDirectivityProfile.gainDb(horizontal, vertical, frequency)`；measured profile 对 H/V 表做频率/角度插值，减去轴上响应，再以 separable（dB 相加）方式合成；结果 clamp 到 -60..+12 dB。
- **入口**：空手右键扬声器方块打开该扬声器的 directivity GUI；角色选择在 GUI 内。`/cinema_directivity` 是类型默认的调试入口；`reload` 重扫文件夹，`info` 打印每类当前 profile，`test <type> <h> <v> <hz>` 打印指定角度/频率的增益。
- **示例**：`docs/examples/speaker_specs/`（含 synthetic 测试表和 cardioid 简单 profile）。
- **边界**：这个 spike 不把 directivity 应用到 `CinemaAudioEngine` 的实际音频；A2d 才做反射/离轴渲染。`SPL gain dB` 目前只是保存/显示，尚未进入增益分配。
- **暂停**：GUI 的 `isPauseScreen()` 为 false，单机打开面板不会暂停集成服务器/播放。
- **许可**：内置 profile 和 synthetic 示例是项目自带/CC0；用户导入的真实 Spinorama 数据仍受其原始 license 约束，GUI 会显示 source/license。

### 7.5 晚期混响

**首选：EAXReverb 仅 late tail**

- 在自定义 OpenAL context 中检测 `ALC_EXT_EFX` / `AL_EXT_EFX`；创建 effect + auxiliary effect slot。
- 每个 source 设置 aux send（发送量按 wet 需求）；effect slot 输出湿声到主输出。
- 参数映射建议：
  - `DECAY_TIME` = clamp(RT60, 0.3, 2.5)。
  - `DECAY_HFRATIO` / `DECAY_LFRATIO`：由材质高频/低频吸收决定；HF 吸收强则 HF ratio < 1。
  - `GAINHF` / `GAINLF`：由材质频响决定。
  - `DENSITY` / `DIFFUSION`：影院建议较高密度/扩散；具体值试听。
  - `REFLECTIONS_GAIN = 0`：关闭 EAXReverb 自带早期反射，避免与自定义反射叠加。
  - `LATE_REVERB_GAIN`：湿声电平；`GAIN` 总 wet。
  - `ECHO_DEPTH = 0`：关闭离散回声。
  - `MODULATION_TIME` / `MODULATION_DEPTH`：轻微调制，避免金属感；不要过大。
  - `AIR_ABSORPTION_GAINHF` / `ROOM_ROLLOFF_FACTOR`：按房间距离/空气吸收设置；固定影院可把 room rolloff 设小。
  - `HFREFERENCE` / `LFREFERENCE`：按需设 4–5 kHz / 200–250 Hz。
- **必须用 spike 验证**：
  - 自定义 context 中 EFX 是否可用；`AL.setCurrentThread` 复用 Minecraft 函数指针时 EFX 扩展位/函数是否仍有效；
  - context 默认属性下的 `ALC_MAX_AUXILIARY_SENDS` 是否足够，是否需要重建 context；
  - per-source aux send 的实际发送量语义；
  - effect slot 在 stereo / 5.1 / HRTF 设备上的输出布局；
  - 与 `AL_SOFT_source_latency`、device clock、reset 的交互；
  - EFX 不可用/设备切换时的回退。
- 如果 EAXReverb 的早期反射无法可靠关闭，或 wet return 只有 front L/R、不能满足 5.1 包围感，则改用/补充 FDN。

**回退：小型 FDN**

- 8 条延迟线（互质长度，约 20–80 ms），Householder/正交反馈矩阵，每条线一个吸收低通。
- 反馈增益 `g_i = 10^(-3 · L_i / RT60)`；clamp < 0.999，防自激。
- 输入为 5.1 的 mono/加权和；输出 6 声道 decorrelated wet，按需混入 5.1 bus。
- 可选轻微调制；CPU 估算：8×8 矩阵/样本 + 滤波，48 kHz 下约数 M MAC/s，可接受。
- 首版不优先实现；等 EFX spike 结果再决定。

**共同原则**

- 不要同时启用 EAXReverb 早期反射和自定义早期反射。
- LFE 默认不送 late reverb（或极低电平），避免低频浑浊；低频由独立低频管理处理。
- wet/dry 默认建议：early reflection 总电平 -9 dB，late reverb -12 dB；最终以试听为准。
- 对白清晰度优先：RT60 过长或 wet 过强会糊；提供“电影/音乐/干燥”预设。

### 7.6 遮挡与衍射

- 直达声遮挡：raycast + 低通/衰减；EFX direct filter 或 Java 滤波。
- 一阶反射可见性：`s→r` 和 `r→listener` 各做一次 raycast；被挡则跳过。
- 不做边缘衍射/绕射；被墙挡住的扬声器可能显得过闷，晚期混响可部分补偿。
- 遮挡更新频率与房间快照一致，不在音频线程 raycast。

### 7.7 听者 / 座位

- 有 Seat 时优先使用 Seat 的皇帝位（位置 + 朝向屏幕）。
- 没有 Seat 时用玩家眼睛位置；玩家移动时更新，但建议房间声学仍以屏幕前方为参考，避免 5.1 声场随头部转动。
- OpenAL listener 策略需明确：
  - 影院模式：listener 固定在皇帝位/房间坐标，朝向屏幕，source 固定在扬声器位置，声场稳定；
  - 自由模式：listener 跟随玩家位置，但朝向仍以屏幕前方为基准，避免 5.1 声场随头部转动；适合走动。
- 首版建议：有 Seat 时锁定皇帝位；没有 Seat 时跟随玩家位置、朝向屏幕。两种模式都只影响客户端空间化，不影响服务端。

### 7.8 线程、延迟、性能

- 房间/材质/扬声器/反射 taps 在客户端线程计算；音频线程只读不可变快照。
- 音频线程不做 raycast、不分配对象、不读世界方块。
- 干声路径不引入 lookahead；反射用因果 delay line，播放开始后先填零，前 100 ms 没有反射是可接受的。
- 每个 chunk 处理；delay line 预分配；避免在音频循环中创建 `Vec3`/集合。
- CPU 预算：Java DSP 目标 < 1 ms / 10 ms 音频（约 10% 单核）；EFX 混响 < 1%。
- 指向性渲染：3 频带/反射 tap 很便宜；可每声道先分 3 频带、共享 delay line，每个 tap 只做延迟读取 + 带增益 + 路由。
- 1/3 octave 多频带或 32–64 tap FIR 会明显增加成本，先做 3 频带并实测。
- 参数平滑：增益/滤波系数一阶平滑；延迟变化交叉淡化。
- EFX 生命周期：context/reset/device switch 时重建 effect/slot/filter；失败则禁用混响，不影响干声。
- M3 每接收器独立引擎后，需要决定共享 context + 每接收器 slot，还是每引擎独立 context；首版先按单引擎设计。

## 8. 参数默认与 UI

建议默认值（可调，最终以试听为准）：

```text
roomAcousticsEnabled   true
earlyReflections       true
earlyGainDb            -9
lateReverb             true
lateGainDb             -12
directivity            true
occlusion              true
airAbsorption          true
maxReflectionTaps      24
geometryUpdateHz       2–5
rt60Clamp              0.3–2.5 s
```

UI 归属：

- AV Receiver 的 Speakers/Settings 页：声学开关、early/late 电平、房间预设、重新扫描房间、调试信息。
- Speakers 页显示每只扬声器的 directivity profile（品牌/型号/测量来源/许可证），允许切换 profile 或恢复默认。
- 投影仪 GUI 不放音频设置。
- 调试命令（后续实现）：dump 房间尺寸/RT60/taps 数、指向性表、A/B 开关、强制材质、强制座位。

## 9. 分阶段实现

| 阶段 | 内容 | 依赖 | 验收 |
|---|---|---|---|
| A0 | EFX/EAXReverb 能力 spike：aux send、wet return、设备布局、CPU/延迟、reset | 无（可在 M2/M3 后提前） | 能听到/关闭混响，无 OpenAL 错误，干声不受影响 |
| A1 | 声学数据模型、材质映射、房间检测、直达声距离/空气吸收 | M4 扬声器位置/Seat 更佳 | dump 出正确的房间尺寸/RT60；干声电平一致 |
| A1d | Spinorama 数据管线：选 4 类代表型号、抓取 H/V（或 3D）、归一化/降采样、生成资源和 metadata | 无（可提前） | 每类有一张可追溯的 directivity 表；来源/许可证/质量记录完整 |
| A1e | 数据许可决策：CC BY-NC-SA 4.0 非商业/相同方式共享，或改宽松数据/用户本地导入 | A1d | 发布包与仓库的许可证/attribution 明确，不违反 NC/SA |
| A2 | 一阶镜像源早期反射 + 5.1 路由 + 平滑 | M4 | 矩形房间反射延迟/方向合理；开关可辨；无爆音 |
| A2d | 指向性渲染：角度插值 + 3 频带/短 FIR + 声学中心高度 | A1d/M4 | 旋转/离轴变化与实测曲线趋势一致；无分频染色/爆音 |
| A3 | 遮挡 + 空气吸收 + 指向性/反射整合 | A2/A2d | 遮挡可闻；离轴反射变暗/变弱合理 |
| A4 | 晚期混响 EAXReverb late tail + 参数映射 + 回退 | A0/A1 | RT60/材质变化可闻；无早期反射叠加 |
| A5 | 接收器 GUI 开关/校准/预设 + 性能/设备切换验收 | M4/M3 | 4K/BSL 下无音频掉帧；设备切换可恢复 |
| A6 | 后续：低频模态/多低音炮、衍射/散射、测量 RIR、HRTF | — | 另行设计 |

## 10. 测试清单

- 几何/单元：镜像源延迟与增益、房间体积/表面积、Sabine/Eyring、指向性插值、FDN 稳定性。
- 指向性数据：Spinorama 表归一化/角度方向/插值正确；3 频带近似与 1/3 octave/FIR 的 A/B；profile 来源/许可证记录完整。
- 游戏内：10×6×4 m 与 20×10×6 m 矩形房间；吸声/反射材质切换；扬声器旋转；听者移动；Seat 皇帝位。
- A/B：干声、仅早期反射、仅晚期混响、两者；确认没有双早期反射/梳状滤波。
- A/V：播放/暂停/seek 后无漂移；音频时钟/PTS 不变；视频帧选择不受影响。
- 性能：4K HEVC/AV1 + BSL + 声学；无 audio dropout；CPU/内存记录。
- 兼容：EFX 不可用、设备切换、资源重载、世界卸载、M3 多接收器。
- 边界：室外/非矩形/无天花板、无扬声器、LFE-only、单声道/立体声源、玩家远离皇帝位。
- 最终由用户游戏内验收，不以截图/日志代替听感。

## 11. 风险与未决

- **EAXReverb 可用性/语义**：自定义 context、OpenAL Soft 1.23.1、设备切换、aux send 发送量、wet return 声道布局都需要 spike 验证。
- **EAXReverb 早期反射**：固定 5 m 模型不适合不同房间；计划关闭，仅作无几何 fallback。
- **一阶近似的局限**：忽略衍射、散射、高阶反射、房间模态；对低频/LFE 不准确。
- **材质数据**：Minecraft 方块没有实测吸声系数；材质吸收仍是近似，需要试听校准。
- **Spinorama 型号映射**：方块是风格化模型，映射到某个真实型号必然有偏差；需要 tooltip/文档标明 profile 来源，并允许替换。
- **Spinorama 数据质量/角度**：不同测量来源、平滑、测量轴、缺失角度/低频数据会影响结果；H/V 两切面不能完全代表 3D 指向性，分频 lobing 可能被简化。
- **Spinorama 许可**：默认 CC BY-NC-SA 4.0（非商业/相同方式共享），与项目 CC0 代码不同；发布前必须做许可决策/attribution，不能默认打包。
- **房间检测**：矩形假设；非矩形/开放空间可能误判；需要 fallback 和调试可视化。
- **听者策略**：影院锁定皇帝位 vs. 玩家自由移动；需要产品决策。
- **5.1 vs. 耳机**：EAXReverb wet return 在 stereo/HRTF/5.1 设备上的表现不同；可能需要 FDN 或多次 send。
- **多接收器（M3）**：共享 context/effect slot 还是每引擎独立；影响资源与声学隔离。
- **低频管理**：单低音炮的房间模态未建模；后续需要多低音炮/模态 EQ/测量 RIR。
- **性能**：Java DSP + EFX + 4K 视频 + shader 的叠加需要实测；音频线程不能卡。
- **设置归属**：声学开关/电平建议存接收器 block entity；几何是客户端本地。需确认是否要 LAN 同步。

## 12. 资料可信度说明

- EAXReverb 的结构描述与 OpenAL Soft 经典 `alcReverb.c` 的公开资料一致；但不同版本实现可能变化，实际行为以运行时 EFX API 为准。
- 共享对话中引用的心理声学研究（一阶反射/指向性）未在本仓库独立复核；本文将其作为设计启发式，不当作严格结论。
- Spinorama 测量数据来自第三方，默认 CC BY-NC-SA 4.0；每个 measurement 的 source/license/quality 必须逐条确认，不能只看站点默认。
- Minecraft 方块没有真实声学参数；材质吸收仍是工程近似，必须通过游戏内试听校准；扬声器指向性只做“代表性型号映射”，不是方块本身的实测数据。

## 13. 建议的下一步

1. 先做 **A1d Spinorama 数据 spike**（不依赖 M4）：选 4 类代表型号，抓取 H/V（或 3D）数据，归一化/降采样，确认格式、质量、source/license；同时做 **A1e 许可决策**。
2. 做 **A0 EFX spike**（不依赖 M4）：确认 EAXReverb 在自定义 context 中可用，验证 aux send/wet return/reset，记录 OpenAL Soft 1.23.1 行为。
3. M4 扬声器路由/Seat 完成后，再做 **A1–A2**：房间检测 + 一阶镜像源 + 5.1 路由。
4. 做 **A2d–A4**：Spinorama 指向性渲染、遮挡、晚期混响参数映射；用 A/B 试听决定 early/late 默认电平。
5. 如果 EFX spike 失败，改用小型 FDN 或暂时只做早期反射 + 无混响；不要为了混响阻塞 M4。
