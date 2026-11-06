# 影院音频计算与房间声学设计

> **分支策略（2026-11-04）**：声学模拟的全部原型与实验代码在 `acoustic-simulation` 分支开发；
> `main` 只保留已稳定、已验收的接口与文档。未经游戏内听感验收的 EFX/早期反射/Spinorama
> 实验代码不得直接合并回 `main`。
>
> **协作规则（2026-11-06）**：`acoustic-simulation` 分支下的所有开发、代码实现和细节处理必须如实向用户汇报，
> 由用户判断是否合理；不得自行决定声学行为、默认值或“听起来差不多”的近似。
>
> **已确认决策（2026-11-06）**：
> - 早期反射采用独立 OpenAL source（方案 C）：每个 tap 一个 source，位置为镜像源，OpenAL 负责方向/声像/距离；不按 5.1 声道混入直达声道。
> - 空气吸收同时作用于直达声和反射；温湿度等配置放 Mod Menu（客户端）。
> - 早反 gain 放 Mod Menu，仅调试用，默认 0 dB。
> - 频带精度、指向性计算方法待用户后续确认。
>
> 状态：分析与规划，尚未实现。本文评估 OpenAL EFX / EAXReverb、早期反射与扬声器指向性方案，
> 并给出与当前 Cinema 音频管线（`CinemaAudioEngine` + AV Receiver + Speaker）的集成计划。
> 相关文档：[项目规划](../PLANNING.md)、[待办](../TODO.md)、[AV Receiver](AV-RECEIVER.md)、[指令](../COMMANDS.md)。

## 1. 目标与边界

目标：

- 在现有 5.1 回放上加入**客户端本地**的房间声学层：直达声距离/空气吸收/指向性、一阶早期反射、晚期混响。
- 保持接收器对媒体/播放状态的权威；声学计算不改变播放位置、音画时钟或服务端状态。
- 可开关、可调试、可回退：没有 EFX、没有扬声器方块时仍能正常播放；Seat 不参与声学，只作可乘坐装饰。
- 在 M4 扬声器 5.1 路由之上提供声学数据接口；听者始终是玩家实际耳朵位置/朝向，不做皇帝位或固定监听点。

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
| Spinorama 实测扬声器指向性 | 高：REST API 需人工申请 Bearer key；站点 JSON 可公开读取，含水平/垂直 SPL、轴上响应、3D 变体 | 高：真实测量、频率相关；比手写指向性模型可靠 | **采用为扬声器指向性数据源**；需处理 API key、型号映射、测量轴、2D 近似和 CC BY-NC-SA 许可 |

### 3.1 关键声学判断

1. **EAXReverb 的早期反射不能代表我们的房间。** 它的固定延迟线基于 5 m 房间，15–20 m 影院的第一次侧墙反射应在十几到几十毫秒，而不是 3–10 ms。
   把 EAXReverb 早期反射叠在自定义反射上还会造成双早期反射、梳状滤波和虚假的方向感。
2. **一阶镜像源 + 指向性是性价比最高的几何声学部分。** 对矩形/近似矩形影院，一阶反射是最强、最有方向性的早期能量；
   更高阶反射迅速扩散，用晚期混响统计近似更合理。
3. **晚期混响不需要逐反射计算。** 用 EAXReverb 的 late tail 或小型 FDN 提供扩散能量即可；关键是 RT60、频响和 wet/dry 合理。
4. **指向性同时作用于直达声和一阶反射。** 直达声使用扬声器到听者的辐射角，反射使用扬声器到反射点的辐射角；
   两者都按扬声器局部坐标查同一份 H/V 指向性表。为避免双重处理，所有声学增益必须在同一条“扬声器辐射 → 传播 → 听者”链路中只计算一次；
   早期反射采用独立 source 后，每个 tap 使用绝对物理增益，距离/声像交给 OpenAL，不能再混入直达声道重复衰减。
5. **低频不能用几何声学解释。** LFE/低频的波长与房间尺寸相当，模态、驻波和边界干涉占主导；首版不建模，明确这是限制。
6. **材质数据是近似。** Minecraft 方块没有吸声系数；用 block/tag 映射到低/中/高频吸收，必须允许调试和覆盖。
7. **只做扬声器指向性，不做原始声源指向性。** 对话讨论的是人声/乐器等原始声源的辐射指向性；
   我们的 5.1 bus 已经是混合后的声道，无法从中恢复每个原始声源的方向。因此本项目只建模**扬声器箱体**的辐射指向性，
   数据首选 **Spinorama 的实测水平/垂直 SPL 指向性**，不再手写猜测的指向性曲线。
8. **Spinorama 数据的许可要单独处理。** 测量数据默认是 CC BY-NC-SA 4.0（署名、非商业、相同方式共享），
   项目代码目前是 CC0；两者可以分开授权，但发布前必须做许可决策，不能默认把数据混进 CC0 发布包。

## 4. 当前代码约束

### 4.1 当前实际音频管线

> 本节按当前代码核对；M4 物理 5.1 路由已接入，见下文。

```text
外部 FFmpeg（stdout, s16le, 48 kHz, 5.1）
  → StreamingAudioDecoder（0.5 s 定长 chunk，PTS = start + totalFrames/48k）
  → AudioChunk（交织 FL/FR/FC/LFE/BL/BR）
  → CinemaAudioEngine（解交织为 6 个 mono OpenAL source，4 buffer/source ≈ 2 s）
  → OpenAL device
```

- `StreamingAudioDecoder` 通过 `-map 0:a:0 -ac 6 -ar 48000 -channel_layout 5.1 -f s16le` 调外部 FFmpeg；chunk 为 24,000 帧（0.5 s），`ChunkSink` 遇到引擎满队列会反压 FFmpeg。
- `CinemaAudioEngine` 在当前线程创建自己的 OpenAL context，6 个 source 默认使用虚拟相对位置；M4 已通过 `setChannelLayout()` 换成已配对扬声器相对听者的位置与增益。`SOFTDeviceClock` + `SOFTSourceStartDelay` 对齐启动，`SOFTSourceLatency` 发布 `audiblePts`。
- 4 buffer/source × 0.5 s ≈ 2 s 缓冲；一阶反射的 80 ms 延迟完全放得下，但不能在这条链路上再加额外 lookahead。
- `AvReceiverPlayback` 根据接收器状态开/停本地解码器，按 `MAX_DRIFT_SECONDS` 做漂移校正；M3 起每个接收器持有独立 `CinemaAudioEngine`/OpenAL context，多个接收器可同时播放。
- **M4 已接入物理布局**：`AvReceiverPlayback` 将已配对 `SpeakerBlockEntity` 的角色/位置/电平映射到六个 source；听者始终是玩家实际耳朵位置/朝向，已删除 Seat/皇帝位与声场校正，未配对声道回退虚拟 5.1。声学层仍不能假设房间几何/材质数据。
- 当前没有 EFX、没有房间声学、没有 Spinorama 指向性数据。

### 4.2 当前实现约束

- `CinemaAudioEngine`：6 个 mono OpenAL source，在 Minecraft 的 OpenAL device 上创建自己的 context；48 kHz；默认虚拟相对位置，M4 已按已配对扬声器更新位置/增益；`AL_INVERSE_DISTANCE_CLAMPED`；目前没有 EFX。
- 该 context 目前用默认属性创建，没有请求 `ALC_MAX_AUXILIARY_SENDS`；EFX aux send 数量/默认值需要在 spike 中确认。
- 音频线程优先用 `EXTThreadLocalContext.alcSetThreadContext` 设置/清空自己的 context，回退到 `alcMakeContextCurrent`；并复用 Minecraft 的函数指针。不要在音频线程调用 `alcMakeContextCurrent(NULL)`，否则会清掉 Minecraft 的全局/线程 context，导致 SoundEngine 报 `Allocate new source: Invalid operation`。
- `MinecraftOpenAl`：复用 Minecraft 的 device；设备切换/资源重载尚未完整处理。
- `AvReceiverBlockEntity`：只有媒体、播放、音量(dBFS)/静音、视频目标；没有声学字段。
- `SpeakerBlockEntity`：有 `role`、`levelDb`、`type`；`SpeakerBlock` 有 `FACING`；落地式为两格高，block entity 在下半格。M4 接收器→扬声器路由已接入。
- `Seat` 方块已实现（`SeatBlock`/`SeatBlockEntity`/不可见 `SeatEntity`），可右键乘坐；**不提供皇帝位/听者参考，不参与声学**。
- `AvReceiverPlaybackManager`：M3 每个接收器独立持有音频引擎/OpenAL context；停止或移除接收器时释放对应 context。
- 六个 source 仍是 `AL_SOURCE_RELATIVE = AL_TRUE`；M4 每个 sync 把物理扬声器世界坐标转换为听者相对坐标并通过 `setChannelLayout()` 快照更新，音频线程不读世界/玩家状态。世界坐标源迁移评审见 §4.2.1，当前暂不迁移。
- `StreamingAudioDecoder` 的 FFmpeg stderr 没有单独消费（`redirectErrorStream(false)`，靠 `-loglevel error` 降低输出）；极端情况下仍可能因 stderr 管道填满而阻塞，后续可加 drain 线程。
- `CinemaPlayer` 的 A/V 时钟使用音频设备时钟/PTS；声学处理不得改变 chunk 的 `startPtsSeconds` 或引入不可控 lookahead。
- **声学增益归属（待实现，方案 C）**：直达声仍由 6 个物理扬声器 source 播放，OpenAL 负责距离衰减；Java 负责直达声的空气吸收、指向性和遮挡。每个早期反射 tap 使用独立 OpenAL source，位置为镜像源，OpenAL 负责方向/声像/距离；Java 负责该 source 的空气吸收、材质反射、指向性和散射增益。反射 tap 不再混入 5.1 直达声道。
- **反射 source 池（待实现）**：反射 source 上限建议 24–36/接收器，需要独立的 source/buffer 池和房间快照生命周期；不能复用 6 个直达 source，也不参与 `setChannelLayout()` 的物理扬声器布局。

### 4.2.1 定位模型评审：相对源 vs 世界坐标源（暂不迁移）

> 2026-11 评审；当前代码未改动。

**当前模型（M4）**

- 六个 source 均为 `AL_SOURCE_RELATIVE = AL_TRUE`，OpenAL listener 固定在原点、朝向 -Z。
- `AvReceiverPlayback` 每个 client tick 取玩家眼睛位置和水平朝向，把扬声器世界坐标转成 listener space：
  `x = dot(relative, right)`、`y = relative.y`、`z = -dot(relative, forward)`；未配对声道回退到 `DEFAULT_POSITIONS`。
- 因此方位和 OpenAL 距离衰减都随玩家姿态更新；音频线程只读 float 快照，不访问 Minecraft 世界。
- 这是 M3/M4 为了让音频线程与客户端世界状态解耦而选的结构，不是性能妥协。

**世界坐标模型**

- `AL_SOURCE_RELATIVE = AL_FALSE`；source 保存扬声器世界坐标（`speakerCenter`），listener 每个 tick 更新为玩家眼睛位置与 yaw-only 朝向。
- 未配对声道用 listener pose + 默认偏移生成世界坐标，保持虚拟 5.1 仍围绕听者。
- 理论上与当前模型等价：Minecraft 与 OpenAL 都是右手、Y-up，可以直接使用世界坐标；listener `at` = 水平 look，`up` = (0,1,0)，source = 世界坐标。变换后的方位/距离应与当前 `toListenerSpace` 完全一致。

**主要风险**

1. **坐标与朝向**：必须保持 yaw-only。若把完整 look（含 pitch）传给 listener，抬头/低头会让扬声器在垂直方向移动，改变当前听感。需要单元测试确认前/后/左/右与 Z 符号。
2. **上下文线程**：OpenAL 调用只能在 `cinema-audio` 线程；listener/source 更新必须通过不可变快照，不能在 client tick 直接调用 `alListener*`/`alSource*`。
3. **原子性**：当前 `channelPositions`、`channelGains`、`layoutVersion` 是分开的 volatile 字段；加入 listener 后更容易出现“新位置 + 旧增益/listener”的撕裂。迁移时应改成单个 immutable `LayoutSnapshot`（listener + 六路位置 + 六路增益 + 相对/绝对标志）。
4. **距离衰减**：`AL_INVERSE_DISTANCE_CLAMPED` 目前依赖默认 `AL_REFERENCE_DISTANCE = 1`、`AL_ROLLOFF_FACTOR = 1`、`AL_MAX_DISTANCE = 64`；5 格约 -14 dB。世界坐标模型在坐标正确时衰减完全相同，但应显式设置参考距离，并把 listener 与 source 放在同一快照更新，否则会双重变换或距离错误。未来若在 Java 侧做距离/声学校准，需把 `AL_ROLLOFF_FACTOR` 归零，避免双重衰减。
5. **回退布局**：未配对声道若继续用 relative，会变成混合模型；建议全部 absolute，并用 listener yaw 旋转默认偏移。扬声器区块卸载时也要原子切换。
6. **Doppler/HRTF**：当前没有设置 listener/source velocity，不产生 Doppler；迁移后不要误设 velocity。HRTF 下需 A/B 验证 listener 朝向。
7. **多接收器**：每个 OpenAL context 有自己的 listener，必须全部更新；player 不存在时使用接收器中心 +1.5 的 fallback。

**结论**

- 当前相对模型没有功能性问题，**暂不迁移**。它已经提供正确的方位和距离衰减，并让音频线程完全不接触 Minecraft 状态。
- 等 A2 早期反射/遮挡/房间声学需要稳定的世界坐标 source 时再迁移；届时先做纯数学等价测试、不可变快照、显式 reference distance，并用固定姿态 + 移动/转头做游戏内 A/B。迁移前不要改 `AL_SOURCE_RELATIVE`。

**迁移前验证清单**

1. 纯数学：对随机 listener 位置/yaw 和扬声器世界坐标，比较 `toListenerSpace` 与“世界坐标 + listener 变换”的 x/y/z；覆盖前/后/左/右、yaw=0/90/180/270。
2. 固定姿态：同一影院、同一听者位置，逐声道 Ping，比较相对源与世界坐标源的声像/响度。
3. 移动与转头：沿前后左右移动、连续转头，确认没有左右翻转、Z 轴反向、俯仰导致的垂直漂移。
4. 回退布局：拔掉部分扬声器/让区块卸载，确认未配对声道仍围绕听者，且切换时无爆音。
5. 多接收器/HRTF：两个接收器同时播放；开启/关闭 HRTF，确认 listener 姿态和距离衰减一致。
6. 线程：确认 client tick 只写快照，所有 `alListener*`/`alSource*` 都在 `cinema-audio` 线程执行。

### 4.3 由此得到的顺序

- **A0（EFX 能力 spike）** 可立即开始，不依赖扬声器路由。
- **A2（一阶反射/指向性）** 依赖的 M4 扬声器角色/位置/朝向已完成，可以直接进入原型。
- **A4（接收器 GUI 开关/校准）** 依赖的 M4 Speakers/Settings 页已完成。
- 听者策略已确定：始终跟随玩家实际耳朵/朝向，不做 Seat 皇帝位、固定监听点或声场校正；Seat 仅是可乘坐座位。

### 4.4 未来可变声道布局

当前声学层仍假设 5.1。未来 mono/stereo/2.1/3.1/5.1/7.1/5.1.2/5.1.4/7.1.2/7.1.4 需要把早期反射、遮挡和指向性计算从固定 6 声道泛化到布局声道；天空/吸顶音响是吸顶式、向下发声的扬声器，声学层按天花板安装位置/朝向处理。路由/布局规划见 [CHANNEL-LAYOUTS.md](CHANNEL-LAYOUTS.md)；声学原型仍在 `acoustic-simulation` 分支开发。

## 5. 目标音频图

```text
FFmpeg 5.1 PCM（48 kHz, FL/FR/FC/LFE/BL/BR）
        │
        ▼
[5.1 channel bus]
        │
        ├─► 直达声：距离/空气吸收/指向性/遮挡（听者按玩家实际位置）
        │
        ├─► 一阶早期反射：镜像源 + 材质吸收 + 扬声器指向性
        │        └─ 每个 tap 一个独立 OpenAL source（镜像源位置）
        │
        └─► 晚期混响 send：EAXReverb（仅 late tail）或 FDN 回退
                 └─ wet return
        │
        ▼
六个 mono OpenAL source（M4 实体扬声器位置；未配对声道回退虚拟 5.1）
+ 最多 24–36 个反射 source（镜像源位置，独立 source 池）
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
  facing             36 档声学 yaw（10° 步进，0..350°）
  gain               校准增益
  speakerDataProfileId
  acousticCenter     高音/驱动轴位置（含前 baffle 偏移）

SpeakerDataProfile
  id                 例如 kef-r11-meta / revel-f206 / sub-omni
  name               被测音箱显示名
  type               floorstanding/bookshelf/center/subwoofer
  pattern            measured/omni/cardioid/unmeasured
  source             测量来源（ASR/Spinorama 等）
  license            测量数据的许可证/署名
  frequencyRange     on-axis 相对全频段能量平均的 -10 dB 低/高交点（整数 Hz）
  frequencies        原始测量频率网格（194/200 点，无降采样，max 256）
  onAxisSpl[band]    轴上响应（绝对 SPL，与 H/V 同尺度）
  onAxisReferenceDb  全频段能量平均电平（EQ 归一化锚点）
  horizontalAngles   -180..180（37 行，含 -180/+180）
  verticalAngles     -180..180（37 行，含 -180/+180）
  horizontalSpl      水平指向性原始 SPL
  verticalSpl        垂直指向性原始 SPL

AcousticListener
  position           玩家实际眼睛位置
  facing             玩家实际朝向（或房间前方，仅用于可视调试，不做锁定校正）

ReflectionTap
  sourceChannel      0..5
  sourceSpeaker      原始扬声器角色/位置
  mirrorPosition     镜像源位置（反射 OpenAL source 位置）
  delaySamples       相对直达声的延迟
  gain               物理增益（不含 OpenAL 距离衰减）
  bandGains          材质/空气/指向性频带增益（频带精度待定）
  direction          到达听者的方向（调试/校验）
  filter             频带/滤波参数

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
RT60  ≈ 0.161 · V / (-S · ln(1 - α_avg)) // Eyring
```

A1 只使用 Eyring。Sabine 仅适用于极低吸收/扩散场的粗略估计；用户决定不采用。Fitzroy 作为非扩散/不规则房间的备选公式，列入 TODO，暂不替换 Eyring。

- RT60 不设上下限；Eyring 在低吸收时自然给出长尾音，高吸收时自然给出短尾音。α=0 时理论为无穷，A3 映射到 EAXReverb 时再 clamp。
- 材质吸收是近似值，必须提供调试/覆盖入口。

**A1 初始实现（2026-11-06）**

- 数据模型：`AcousticMaterial`、`AcousticSurface`、`AcousticRoom`、`RoomFill`、`RoomFloodFill`、`AcousticSpeaker`、`AcousticListener`、`RoomAcousticSnapshot`。
- 房间检测：从玩家眼睛沿 ±X/±Y/±Z 各发 **25 条射线扇形**（5×5，约 ±9°），共 150 条；每条射线最多反射 **2 次**，取同轴向、同命中面的中位平面，避免墙角/门洞单条射线失败。跳过扬声器、座椅、投影仪、接收器，屏幕作为墙面；不再因“墙面太近”直接失败，仅保留任一维 <1.5 m 的失败条件。
- 墙面材质：命中点周围 **9×9（半径 4）** 方块采样，但严格裁剪到该平面的 u/v 边界；跳过空气/设备，按样本数平均。`samples` 是方块数量，不是 m²；完整方块平面上不会超过面积（边界取整除外）。屏幕单独映射。
- 凸盒验证：监听者必须在六面内；盒内 1/3、2/3 的 2×2×2 点采样，仅在 **8/8 全为固体** 时判为不可信（避免墙角/家具误判）。
- 失败 fallback：保留所有已命中的**真实平面**；某轴只有一个面命中时，以该真实面为基准向缺失侧延伸 **16 m**；某轴两面都缺失时，该轴使用听者 ±8 m。缺失面用默认材质，`valid=false`，仍保留真实面的材质/面积与 RT60 供 late reverb 使用。
- RT60：只用 **Eyring** `0.161 V / (-S ln(1-α))`，**不设上下限**；α=0 时输出无穷，A3 再映射到 EAXReverb 的合法范围。Sabine 不采用；Fitzroy 作为待办备选。
- 材质表（`AcousticMaterials`，low/mid/high 分别取 125/250、500/1k、2k/4k 的平均）：
  - screen `0.05/0.10/0.15`，scattering `0.10`（声学透声幕布估计）
  - fabric（羊毛/窗帘，14 oz 褶皱帘）`0.19/0.62/0.65`，`0.35`
  - carpet（地毯，heavy carpet on concrete）`0.04/0.255/0.625`，`0.35`
  - wood（3/4" 胶合板护墙）`0.25/0.13/0.105`，`0.15`
  - stone（粗糙混凝土/砖石）`0.015/0.05/0.09`，`0.10`
  - glass（6 mm 玻璃）`0.12/0.035/0.02`，`0.05`
  - vegetation（树叶/草）`0.10/0.20/0.30`，`0.40`（估计）
  - water `0.01/0.01/0.02`，`0.00`
  - snow `0.20/0.40/0.60`，`0.30`（估计）
  - porous（泥土/沙/砂砾）`0.10/0.20/0.30`，`0.30`（估计）
  - metal `0.02/0.03/0.04`，`0.05`（估计）
  - default `0.05/0.05/0.05`，`0.10`
  - 主要来源：CDC/NIOSH《Compendium of Materials for Noise Control》Table 5、`acoustic.ua` absorption coefficients；screen/vegetation/snow/porous/metal 缺少直接对应项，为估计值。
- 扬声器声学中心：落地 **+1.31 m**（上层高音）、书架 **+0.77 m**、中置 **+0.33 m**、低音炮 **+0.48 m**（相对方块 y，来自方块模型）；声学中心再沿朝向向前面板偏移 **0.45 m**。
- 扬声器朝向：新增 **36 档 10° 声学 yaw**（0=N，9=W，18=S，27=E；正方向 = 面向扬声器时玩家的右侧，符合 CTA 角度方向），不再受方块四向 `FACING` 限制；方块模型通过 `SpeakerBlockEntityRenderer` 同步旋转到同一物理朝向。
- 朝向校准物品：**Speaker Alignment Tool**（`cinema:speaker_alignment_tool`，铁锭+指南针合成）；右键 +10°，潜行右键 -10°，action bar 显示当前角度。
- 缓存：移动/转头时约 5 Hz 更新，静止时每 20 tick 更新；断线/退出清空。A1 只生成快照，不改变任何音频。
- 稳定化：新平面坐标与上一有效快照相差 ≤1.5 m 时沿用旧平面，避免不规则天花板/墙角抖动；检测失败时直接使用 fallback（保留真实平面，缺失轴以已知面延伸 16 m；两面都缺失时该轴用听者 ±8 m），不保留旧房间。
- 连通空气 flood fill：在六面盒内从眼睛做有界 **6 邻接 BFS**，最多 **32³ = 32768** 格、单次 **8 ms** 预算；只穿过无碰撞方块或扬声器/座椅/投影仪/接收器，水/岩浆按边界处理；未加载区块、超出上限或超出时间预算时标记 `fill=incomplete`，RT60 回退到几何盒。完整填充时给出 `effectiveVolume`、`effectiveSurfaceArea`、`openArea` 与开口面（`x±/y±/z±`），Eyring 使用有效体积/面积和按边界材质加权的 α；开口按 α=1 计入（近似为完全吸声）。完整填充最多每 **200 tick** 或听者离开旧填充时重扫；incomplete 填充每 200 tick 重试；`/cinema_acoustics` 强制重扫。填充只用于 RT60/调试，不改变 A1 的盒几何。
- 开口按 α=1 只是把逃逸能量视为完全吸收的 A1 近似；门洞通向另一房间的耦合混响尚未建模，留给后续 A3/Fitzroy/portal 处理。
- 调试：`/cinema_acoustics` 强制刷新并输出房间、六个平面、材质、flood fill 体积/面积/开口、RT60、听者和附近扬声器。

### 7.2 直达声

- **距离衰减只做一次。** M4 已把 source 放到真实扬声器位置，由 OpenAL 的 inverse distance 处理直达声距离；Java 声学层不再重复乘距离衰减。
- 若继续使用固定虚拟布局，则在 Java 侧按扬声器到听者的距离计算增益，并关闭/固定 OpenAL 的距离模型，二者只能选一个。
- **空气吸收（直达声 + 反射）**：按路径长度 `x` 和频率计算空气吸收，压力增益记为 `A(x, f)`；可用 ISO 9613-1（默认 20 °C、50% RH）的 `α(f)` dB/m 得到 `A(x,f)=10^(-α(f)·x/20)`，或等效的高频 shelf/一阶低通。温湿度等配置放 Mod Menu（客户端）。直达声必须应用，不允许保持中性；反射 source 单独使用 `A(L,f)`。若 OpenAL/EFX 已应用同一空气吸收，Java 侧必须关闭对应处理，二者只能选一个。
- **指向性（直达声 + 反射）**：见 7.4。直达声按“扬声器 → 听者”的角度查表，反射按“扬声器 → 反射点”的角度查表；直达声不再假设 on-axis，也不使用固定皇帝位。
- **未配对声道 fallback**：回退虚拟 5.1 时没有物理扬声器朝向/型号；首版对该声道使用 omni 指向性（或跳过指向性），空气吸收/距离仍按虚拟 source 位置计算，并在调试信息中标注 fallback。
- **遮挡**：直达声 raycast；被挡住时低通 + 衰减。EFX 可用 `AL_DIRECT_FILTER`；没有 EFX 时在 Java 侧对相应声道滤波。
- **增益归属（方案 C）**：直达声与每个反射 tap 各有独立 OpenAL source；OpenAL 负责方向/声像和距离衰减；Java 只计算空气吸收、指向性、材质反射和散射等非距离增益。反射 tap 不混入 5.1 直达声道，避免重复距离衰减。
- **source 增益**：直达 source 的 Java 增益为 `D_s(θ_listener,f) · A(d,f)`；反射 source 为 `D_s(θ_r,f) · A(L,f) · R_p(f) · S_p(f)`。两者都不含 `1/r` 距离衰减，距离由 OpenAL 按 source 位置处理。

### 7.3 一阶早期反射（镜像源）

对每个扬声器 `s` 和每个有效平面 `p`：

1. 计算镜像源 `s' = mirror(s, p)`。
2. 求线段 `s' → listener` 与平面 `p` 的交点 `r`；若 `r` 不在平面边界内，跳过。
3. 检查 `s → r` 与 `r → listener` 的可见性（可选，但推荐）；被遮挡则跳过或衰减。
4. 路径长度 `L = |s' - listener|`；直达距离 `d = |s - listener|`。
5. 延迟 `Δt = (L - d) / c`，`c ≈ 343 m/s`；过小（< 1 ms）或过大（> 80 ms）可跳过。
6. 反射 source 的非距离增益（按频带计算）：

```text
G_reflection_source(f) = D(θ_s→r, f) · A(L, f) · R_p(f) · S_p(f)
R_p(f) = sqrt(1 - α_p(f))
```

   其中 `α_p(f)` 是能量吸声系数，`R_p(f)` 是压力反射系数；`D` 为按频带查 7.4 表的指向性；
   `A(L,f)` 是反射路径上的空气吸收；`S_p` 为散射/扩散修正（首版可设 1）。
   该增益不含 `1/L` 距离衰减：反射 source 放在镜像源位置，OpenAL 按 `L` 计算距离增益。
   若实测 OpenAL `inverse distance clamped` 不满足物理 `1/L`，可把反射 source 的 rolloff 设为 0，
   在 Java 侧显式乘 `1/L`；该选择在 A2 实测后由用户确认。所有项按频带计算并做数值 clamp，
   clamp 只用于防止贴墙/零距离爆炸，不作为默认衰减。
7. 指向性 `D` 由 7.4 的 Spinorama 表按反射角查得；材质吸收 `R_p`、空气吸收 `A(L)` 已在上式按频带计入，
   不要在这些项之外再叠加一次“材质 HF 吸收”或“空气吸收”。
   频带精度待用户确定（3 频带 / 1/3 octave / 32–64 tap FIR 均可选），确认前不锁定实现。
8. 为每个反射 tap 分配一个独立 OpenAL source，位置设为镜像源 `s'`（相对听者坐标）；
   该 source 播放 `sourceChannel` 的延迟/滤波副本。OpenAL 用 `s'` 到听者的距离 `L` 做距离衰减，
   方向即反射到达方向。**不再按 5.1 布局做 VBAP/最近声道路由，也不把 tap 混入直达声道。**
9. 每个扬声器最多保留若干最强 taps；全局上限建议 24–36/接收器；每个 tap 一个 source，
   需要独立的 source/buffer 池，受 OpenAL source 数量、延迟线和 CPU 限制。

补充：

- 只做一阶；二阶及以上交给晚期混响。
- 镜面反射假设；不做衍射/边缘绕射。柱子、屏幕边框、家具的散射不建模。
- LFE 默认不参与早期反射，避免低频梳状滤波；低频交给独立低频管理（多低音炮/模态 EQ，后续）。
- 不设置“反射比直达声低 6–12 dB”的固定默认值；每个 tap 的增益由距离、空气吸收、材质反射系数、指向性和散射项计算。`earlyGainDb` 放 Mod Menu，仅调试用，默认 0 dB，不作为正式声学校准。
- 反射 source 的信号必须取自**未应用直达声空气吸收/指向性/遮挡的原始 `sourceChannel`**，再单独应用反射路径的 `A(L)`、`D(θ_r)`、`R_p`、`S_p`；不能复制已经处理过的直达声道，否则会双重滤波。
- 参数变化时交叉淡化 100–200 ms；延迟变化用分数延迟/交叉淡化，避免 zipper noise。听者/房间变化导致镜像源位置、`L`、延迟变化时，对 source 位置/延迟/增益做平滑，避免声像跳变。

### 7.4 扬声器指向性（Spinorama 实测数据）

> **计算方法待定（2026-11-06）**：用户稍后说明指向性的具体计算方法；以下 H/V 插值、separable 合成、频带划分均为草案，确认前不作为实现依据。

**范围**

- 只做扬声器箱体指向性，不做原始声源指向性；5.1 bus 已经混合，无法恢复每个原始声源的方向。
- 指向性同时用于：直达声（扬声器 → 听者的辐射角）和一阶反射（扬声器 → 反射点的辐射角）。不用于 EAXReverb 的 `REFLECTIONS_PAN` / `LATE_REVERB_PAN`。

**数据源与格式**

- 用户提供的 ASR/Spinorama 数据：只读取每个型号的 `SPL Horizontal.txt` 与 `SPL Vertical.txt`；**CEA2034、Directivity Index、Early Reflections 等派生文件全部忽略**。
- 从 H/V 取三组数据：**水平指向性**、**垂直指向性**、**轴上响应**（`On-Axis` 列）。
  归一化时用轴上响应作为每个频带的 0 dB 参考；H/V 两切面做 separable 近似，不做 3D 重建。
- 保留原始 194/200 点频率网格，不降采样、不平滑、不量化；源数据 36 个角度 + 补 `-180°` = 37 行。
- 不再使用 `quality` 字段；只记录 `source` 与 `license`。21 个型号均为 ASR `CC BY-NC-SA 4.0`。
- 生成 profile 写到本地 `run/cinema/speaker_data/profiles/`，不打包进 mod；转换器见 `docs/examples/speaker_data/convert_spinorama.py`。

**当前数据覆盖**

| 方块类型 | 型号数 | 说明 |
|---|---|---|
| floorstanding | 4 | JBL A180、KEF R11 Meta、Polk T50、Revel F206 |
| bookshelf | 13 | Adam T5V、Ascilab F6B、Edifier MR4、Genelec 8351B、JBL 308P MkII、Kali LP6、Neumann KH80/KH120 II/KH150/KH310、PreSonus Eris 3.5 Gen 2/E5 XT、Topping MA4 |
| center | 4 | Ascilab C8C、JBL Project Array 880 Center、KEF Reference 4C、Revel C208 |
| subwoofer | 0 | 没有合适的 ASR H/V 数据；继续使用内置 `sub-omni`/`sub-cardioid` |

- 方块是风格化模型，不需要与真实型号完全一致；但要在 tooltip/文档中写明“指向性 profile 来源”，避免误导。
- 低音炮：通带内按全指向；如果找到高质量 sub 测量，再替换。LFE 默认不参与早期反射/混响。

**角度与坐标**

- 扬声器朝向由 **36 档声学 yaw** 定义（对齐工具设置，10° 步进；正方向 = 面向扬声器时玩家右侧）；方块 `FACING` 只保留放置基准，实际模型由 `SpeakerBlockEntityRenderer` 按声学 yaw 旋转。声学中心按模型取高音/驱动轴：落地 +1.31 m、书架 +0.77 m、中置 +0.33 m、低音炮 +0.48 m（相对方块 y），再沿朝向向前面板偏移 0.45 m。
- **角度定义采用 ANSI/CTA-2034-A §5.1 与 Figure 1**：0° 是 reference axis（on-axis）；正水平角在面向扬声器时指向听者右侧（即扬声器自身左侧）；正垂直角向上；+180° 与 -180° 是同一个后方点。源数据只有 +180°，导入时补一行 -180°，SPL 与 +180° 相同；H/V 表均为 -180..180 共 37 行。
- 由世界坐标方向 `d`（扬声器 → 听者或反射点）计算：`f` 为扬声器前方单位向量，`u` 为世界上方，`r = normalize(cross(u, f))`（面向扬声器时的听者右侧）；`θh = atan2(dot(d, r), dot(d, f))`，`θv = asin(clamp(dot(d, u), -1, 1))`。`θh` 归一化到 -180..180，`θv` 到 -90..90；H/V 表做双线性插值。
- 指向性归一化：每个频带减去 `onAxisSpl[band]`，只保留相对指向性。轴上 EQ 另行使用 `onAxisEqGainDb(f) = onAxisSpl(f) - onAxisReferenceDb`；`onAxisReferenceDb` 是**全频段能量平均** `10*log10(mean(10^(SPL/10)))`，对每个 on-axis 采样点等权（原始网格为对数间隔，近似 log-frequency 等权，不是线性频率积分），用来归一化整体响度，不参与 H/V 相对指向性。

**渲染**

- 频带精度待用户确定；当前草案为 3 频带（例如 <300 Hz / 300–2k / >2k）增益 + 可选 HF shelf，用 Linkwitz-Riley 或相位兼容分频，避免分频点染色。3 频带只是候选，不是已确认实现。
- 每个反射 tap 的 `bandGains` 由该反射角查表得到；再乘材质 `sqrt(1-α)`、空气吸收 `A(L)` 和散射修正。距离由 OpenAL 按反射 source 的镜像源位置处理；若后续把反射 source 的 rolloff 设为 0，则在 Java 侧再乘 `1/L`。
- 直达声：按玩家实际听者位置/角度计算，应用同一 profile 的带增益；直达声和反射使用同一份指向性数据，但各自独立计算一次，不假设固定皇帝位。直达声的离轴变化是物理计算的一部分，不是可选项。
- 不建议直接用一个“低通截止频率”概括指向性；实测曲线可能有轴向隆起/分频 lobing，频带精度待定，1/3 octave/短 FIR 更忠实但更贵。
- 频带精度确认后再决定是否升级到 1/3 octave 多频带（例如 10 段）或 32–64 tap FIR；CPU/延迟在 A2d 测量后决定。

**数据管线**

1. 选型号/测量版本，记录 source/license；`quality` 字段已删除。
2. 只读取 `SPL Horizontal.txt` / `SPL Vertical.txt`；不使用 CEA2034、Directivity Index、Early Reflections 等派生文件。
3. 解析原始角度/频率/SPL 与轴上响应；**保留原始 194/200 点频率网格，不做 1/3 octave 降采样**；补 `-180°` 行（= `+180°`）。
4. 用 on-axis 全频段能量平均计算 `-10 dB` 低/高交点，写入 `frequencyRange`（四舍五入到 1 Hz）；保留原始 on-axis 绝对 SPL。
5. 生成 JSON profile 到本地 `<game>/cinema/speaker_data/profiles/`，**不打包进 mod**；转换脚本为 `docs/examples/speaker_data/convert_spinorama.py`。
6. 运行时只读表，不在音频线程解析 JSON；启动/资源重载时加载。
7. 数据文件单独标注许可证；不默认并入 CC0 代码许可。

**许可（重要）**

- Spinorama 测量数据默认 **CC BY-NC-SA 4.0**：署名、非商业、相同方式共享；测量特定条款优先。
- 项目代码目前是 CC0；可以把代码与数据分开授权，但发布包必须包含 attribution/license、标明对数据的修改（changes），且不能把数据用于商业用途。
- 若未来要商业化/闭源分发，必须：取得权利方许可、改用许可宽松的测量、或让用户本地导入数据（不随模组分发）。
- 这不是法律意见；发布前需要确认每个 measurement 的 source/license，并在仓库加 `docs/THIRD-PARTY.md`/数据目录 LICENSE。

#### 7.4.1 Spike 实现（本地 profile / 客户端 GUI）

已落地的第一个 speaker data spike（只做数据/UI/求值，不改变播放）：

- **内置低音炮模式**：`sub-omni`（全指向）和 `sub-cardioid`（心形指向）。
  - 心形按 3D `0.5 * (1 + cos θ)` 计算，0° = 0 dB，90° ≈ -6 dB，180° 截断到 -90 dB。
  - 其他箱体先提供 `unmeasured`（omni 占位）profile，等真实 Spinorama 数据导入。
- **本地 profile**：`<game>/cinema/speaker_data/profiles/*.json`；支持把 `.json` 拖到 GUI 导入，文件大小限制 1 MB。旧 `speaker_specs` 与更早的 `speaker_directivity` 文件夹会**直接删除，不做迁移**。
- **三组原始测量数据**：measured profile 包含水平指向性、垂直指向性、轴上响应（`onAxis`）；三者使用同一 SPL 尺度，原始频率网格不降采样。H/V 表补 `-180°` 行，范围 -180..180。
- **简单参数**：`SPL gain dB` 始终可编辑；**measured profile 的 low/high Hz 固定为 profile 的 `frequencyRange`，GUI 只读**，只有 `unmeasured`/analytic profile 可以编辑 low/high Hz。Profile 按钮打开分页选择器，**所有 profile 直接可选，不再循环**。类型默认保存到 `settings.json`，具体扬声器保存到 `assignments.json`。
- **角色选择**：per-speaker GUI 内直接循环合法角色；点击即通过 C2S `SpeakerRolePayload` 提交到服务端，服务端校验距离/方块/箱体类型后写入 block entity。
- **按扬声器保存**：`assignments.json` 以“维度 + 方块坐标”为 key，保存该扬声器自己的 profile 和参数；没有 assignment 的扬声器回退到类型默认。
- **求值**：`SpeakerDataProfile.gainDb(horizontal, vertical, frequency)`；measured profile 对 H/V 表做频率/角度插值，减去轴上响应，再以 separable（dB 相加）方式合成；结果 clamp 到 **-90..+12 dB**。
- **频率范围**：从 on-axis 的 -10 dB 低/高交点计算；参考电平为 on-axis 全频段能量平均，结果四舍五入到 1 Hz。没有交点时使用测量边界。
- **轴上 EQ 归一化**：`onAxisEqGainDb(f)` 保留原始 on-axis 形状，减去全频段能量平均电平，使不同型号的整体响度可比；当前尚未接入实际音频。
- **入口**：空手右键扬声器方块打开该扬声器的 speaker data GUI；角色选择在 GUI 内。`/cinema_speaker_data` 是类型默认的调试入口；`reload` 重扫文件夹，`info` 打印每类当前 profile，`test <type> <h> <v> <hz>` 打印指定角度/频率的指向性增益和轴上 EQ 增益。
- **示例/转换器**：`docs/examples/speaker_data/`（含 synthetic 测试表、cardioid 简单 profile 和 `convert_spinorama.py`）。
- **边界**：这个 spike 不把 speaker data 应用到 `CinemaAudioEngine` 的实际音频；A2d 才把 profile 应用到实际音频（直达声 + 反射），具体计算方法待用户确认。`SPL gain dB` 目前只是保存/显示，尚未进入增益分配。
- **暂停**：GUI 是否暂停由 Mod Menu 的 `Pause game in device GUIs` 控制，默认 false；单机打开面板不会暂停集成服务器/播放。
- **许可**：内置 profile 和 synthetic 示例是项目自带/CC0；真实 ASR/Spinorama 数据为 CC BY-NC-SA 4.0，profile 只作为本地用户数据存在，GUI 会显示 source/license。

#### 7.4.2 Spinorama API 使用（2026-11 核对）

**访问方式**

- Base URL：`https://api.spinorama.org`
- API 不是匿名公开读取：缺少 key 返回 `401 Unauthorized`（`www-authenticate: Bearer`）。
- 需要向 `pierre@spinorama.org` 发邮件人工申请，主题建议：
  `[AUTH]: API access request - <你的名字/项目>`；说明身份、用途、是否发布/再分发、是否商业、预计请求量。
- 请求头：`Authorization: Bearer <API_KEY>`。key 不能放 URL、公开仓库、浏览器端应用或模组发布包。
- key 有有效期；丢失/泄露要申请吊销和替换。key 数据库不可用时返回 `503`。

**端点**

```text
GET /v1/brands
GET /v1/speakers
GET /v1/speaker/{speaker_name}/metadata
GET /v1/speaker/{speaker_name}/versions
GET /v1/speaker/{speaker_name}/version/{version}/measurements
GET /v1/speaker/{speaker_name}/version/{version}/measurements/{measurement_name}
    ?measurement_format=json   # 默认 json；png/jpg/webp 仅在图存在时
```

- 名称要 URL 编码：`Genelec%208341A`、`SPL%20Horizontal`、`On%20Axis`。
- 先查 `measurements` 列表，不要假设每个测量名都存在；部分测量有 `_unmelted` 变体。
- API 不提供 Erin's Audio Corner 的测量；EAC 数据需要另找来源并遵守其许可。

**返回格式**

站点实际加载的测量 JSON 是 Plotly 格式：

```json
{
  "data": [
    {
      "name": "-170°",
      "x": {"dtype": "f8", "bdata": "..."},
      "y": {"dtype": "f8", "bdata": "..."}
    }
  ],
  "layout": {}
}
```

- `bdata` 是 base64 编码的 little-endian `float64` 数组；`x` 是频率 Hz，`y` 是 SPL dB。Python 解码：

```python
import base64
import numpy as np

def decode_bdata(obj):
    return np.frombuffer(base64.b64decode(obj["bdata"]), dtype="<f8")
```
- `SPL Horizontal` / `SPL Vertical` 的 `data` 包含角度 trace（名字如 `-170°`、`90°`、`180°`）、一个 `ON`（轴上）trace，以及 `Band` / `Trend line` / `Midrange` 辅助 trace。
  - 构建 H/V 行时：角度 trace 用名字去掉 `°`；`ON` 作为 0°；其他名字忽略。
  - 实测 `ON` 与 `On Axis` 在 1 kHz 的值一致，说明两者同一 SPL 尺度。
- `On Axis` 里也有 Band/Midrange 辅助 trace；筛选 `name == "On Axis"` 的 trace。

```python
# H/V 行
rows = []
for t in data:
    name = t["name"]
    if name == "ON":
        angle = 0.0
    elif name.endswith("°"):
        angle = float(name[:-1])
    else:
        continue  # Band / Trend line / Midrange 等
    rows.append((angle, decode_bdata(t["y"]), decode_bdata(t["x"])))
```
- `SPL Horizontal Contour` / `SPL Vertical Contour` 是 `x`/`y`/`z` 网格，`z.shape = [angles, frequencies]`；数据更密但解析更复杂，首版用线切面即可。
- API 的 JSON 结构需要拿到 key 后实际验证；上面的结构来自站点同源静态 JSON。

**转换到本模组格式**

1. 读取 `On Axis`：`frequencies = x`，`onAxis = y`，保留原始频率网格。
2. 读取 `SPL Horizontal` / `SPL Vertical`：每个 trace → `{angle, spl}`；补 `-180°` 行（= `+180°`）。
3. 不重采样、不降采样、不量化；允许 194/200 点原始网格（max 256）。
4. 用 `onAxis` 归一化 H/V（H/V 与 onAxis 必须是同一 SPL 尺度）。
5. 由 on-axis 的 -10 dB 交点计算 `frequencyRange`；参考为全频段能量平均，结果取整到 1 Hz。
6. 写出本模组的 measured profile：
   `frequencies`、`onAxis`、`horizontal`、`vertical`、`frequencyRange`、`source`、`license`；**没有 `quality`**。
7. 当前模组的本地导入器**不接受原始 Plotly JSON**，只接受转换后的 `speaker_data` JSON；转换器见 `docs/examples/speaker_data/convert_spinorama.py`。生成的 profile 只写入本地游戏目录，不打包进 mod。

**静态站点 JSON（API 不可用时的备选）**

- 站点页面本身会加载同目录 JSON，例如：
  `https://www.spinorama.org/speakers/Genelec%208341A/ASR/asr-horizontal/SPL%20Horizontal.json`
  `.../On%20Axis.json`
- 这些文件可公开访问，但仍是测量数据，受 CC BY-NC-SA 4.0 约束；不是官方 API 的稳定接口，页面结构可能变。
- 如果只是本地生成 profile，可以用它们做 A1d 转换测试；正式工具优先用 API + key。

**key 使用建议**

- 只在本地转换工具/脚本中读取环境变量，例如 `SPINORAMA_API_KEY`；不要写进仓库、日志或 GUI。
- 模组运行时不需要 Spinorama key；它只读取本地 `speaker_data` JSON。
- 申请和再分发前确认测量 license；API key 不授予商业使用权，也不覆盖测量许可。

### 7.5 晚期混响

**首选：EAXReverb 仅 late tail**

- 在自定义 OpenAL context 中检测 `ALC_EXT_EFX` / `AL_EXT_EFX`；创建 effect + auxiliary effect slot。
- 每个 source 设置 aux send（发送量按 wet 需求）；effect slot 输出湿声到主输出。
- 参数映射建议：
  - `DECAY_TIME` = clamp(RT60, 0.15, 5.0)。
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
- **A0 probe（已实现）**：`/cinema_efx_spike` 使用 OpenAL Soft loopback 设备离线渲染，检查 shared-caps EFX、`ALC_MAX_AUXILIARY_SENDS`、per-source send、EAXReverb（关闭早期反射/echo）、5.1/HRTF 湿声布局、CPU 和 context reset；同时创建 Minecraft 真实设备上的临时 thread-local context，验证真实设备 EFX/aux slot，但不播放声音、不关闭设备。该命令**不会从扬声器出声**；进入世界后运行，结果会出现在聊天和日志。
- **A0 游戏内结果（2026-11-06，OpenAL Soft）**：EFX/HRTF/loopback/reopen/deviceClock 均为 true；`ALC_MAX_AUXILIARY_SENDS=2`；真实设备临时 context 的 `realEFX=OK`；`sharedCapsEFX=OK`（`AL.setCurrentThread` 复用 Minecraft 函数指针可用）；5.1 wet 覆盖 5 个非 LFE 声道（LFE ≈ -96 dBFS），wetMax ≈ -31.8 dBFS；HRTF enabled，wet L/R ≈ -32.6/-29.0 dBFS；reset/recreate OK；loopback 4 秒 5.1 渲染约 32 ms（约 123× realtime，CPU 约 0.8% 单核，wet/dry 差异低于测量噪声）；探针后原版 UI 音效正常。未测：真实设备听感、物理设备切换、真实硬件延迟。
- **A0c 决策**：EAXReverb late tail 可用，wet return 能覆盖 5.1，`ALC_MAX_AUXILIARY_SENDS=2` 足够一个 send；选择 EAXReverb 作为 late-tail 首选，FDN 仅作为 EFX 不可用/布局不合适的回退。**A0 技术验证通过**；听感、物理设备切换和真实硬件延迟留到 A3/A4。

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

- **不做皇帝位、不做固定监听点、不做声场校正**。Seat 只是可乘坐装饰，不参与音频。
- listener 使用玩家实际耳朵位置和实际朝向；玩家走动或转头时按物理扬声器方向重新计算，因此走出影院后空间定位仍然正确。
- 不把 listener 锁定到屏幕/座位坐标，也不在玩家离开影院时重新居中或旋转 5.1 声场；这些做法会让离场听者的定位明显错误。
- 声学反射/混响也以实际玩家位置为听者；若未来需要房间统计，可使用房间中心作为近似，但不能改变直达声的听者位置。
- 听者策略只影响客户端空间化，不影响服务端。

### 7.8 线程、延迟、性能

- 房间/材质/扬声器/反射 taps 在客户端线程计算；音频线程只读不可变快照。
- 音频线程不做 raycast、不分配对象、不读世界方块。
- 干声路径不引入 lookahead；反射用因果 delay line，播放开始后先填零，前 100 ms 没有反射是可接受的。
- 每个 chunk 处理；delay line 预分配；避免在音频循环中创建 `Vec3`/集合。
- CPU 预算：Java DSP 目标 < 1 ms / 10 ms 音频（约 10% 单核）；EFX 混响 < 1%。
- 指向性与空气吸收：直达声每声道使用同一套频带增益/滤波（频带精度待定），开销可忽略；反射 tap 共享每声道 delay line，每个 tap 读取延迟副本并送入自己的反射 source。
- 反射 source：每接收器最多 24–36 个额外 source + buffer；source/buffer 预分配并随房间快照复用；多接收器、HRTF、设备切换下要实测 OpenAL source 上限和 CPU。
- 1/3 octave 多频带或 32–64 tap FIR 会明显增加成本；频带精度待用户确定，3 频带只是候选。
- 参数平滑：增益/滤波系数一阶平滑；延迟变化交叉淡化。
- EFX 生命周期：context/reset/device switch 时重建 effect/slot/filter；失败则禁用混响，不影响干声。
- M3 已选择每接收器独立引擎/context；声学 effect/slot 也按接收器 context 隔离。后续若要跨接收器共享 aux slot，需要重新评估资源与隔离。

## 8. 参数默认与 UI

建议默认值（可调，最终以试听为准）：

```text
roomAcousticsEnabled   true
earlyReflections       true
earlyGainDb            0                 // Mod Menu, debug only
lateReverb             true
lateGainDb             -12
directivity            true
occlusion              true
airAbsorption          true
airTemperatureC        20                // Mod Menu
airHumidityPercent     50                // Mod Menu
maxReflectionTaps      24
geometryUpdateHz       2–5
rt60Clamp              none               // Eyring; clamp only when mapping to EAX
```

`earlyGainDb` 放 Mod Menu，仅调试用；早期反射的基础电平由 7.3 的物理链路计算，不再默认压低 6–12 dB。`lateGainDb` 仍属接收器/声学设置，最终以试听为准。

UI 归属：

- Mod Menu（客户端，`CinemaClientConfig` / `CinemaConfigScreen`）：空气吸收开关、温度、湿度、`earlyGainDb` 调试滑杆；这些是客户端本地设置，不写入接收器。
- AV Receiver 的 Speakers/Settings 页：声学总开关、late reverb 电平、房间预设、重新扫描房间、调试信息；不放 `earlyGainDb`。
- Speakers 页显示每只扬声器的 speaker data profile（品牌/型号/测量来源/许可证），允许切换 profile 或恢复默认。
- 投影仪 GUI 不放音频设置。
- 调试命令（后续实现）：dump 房间尺寸/RT60/taps 数、指向性表、A/B 开关、强制材质、强制座位。

## 9. 分阶段实现

| 阶段 | 内容 | 依赖 | 验收 |
|---|---|---|---|
| A0 | EFX/EAXReverb 能力 spike：aux send、wet return、设备布局、CPU/延迟、reset | 无；已实现 `/cinema_efx_spike` | 游戏内通过：realEFX/sharedCaps EFX=OK，max sends=2，5.1 wet 5 声道，HRTF enabled，CPU≈0.8%，reset=OK；听感/物理切换留 A3/A4 |
| A1 | 声学数据模型、材质映射、房间检测、扬声器/听者快照、36 档扬声器 yaw | 已实现 `/cinema_acoustics` + Speaker Alignment Tool（A1b 外部数据表待补） | 游戏内 dump 出正确的房间尺寸/RT60/六面材质/扬声器；墙角/门洞不应误判；A1 不改变音频。直达声距离/空气吸收/指向性归 A2 |
| A1d | ASR/Spinorama 数据管线：21 个型号、只读 H/V 原始文件、保留原始频率网格、补 -180°、按全频段能量平均计算 -10 dB 频率范围并取整到 1 Hz、生成**本地** profile（不打包） | 无（已实现转换器） | 21 个 profile 可加载；来源/许可证/修改说明完整；无 `quality` 字段 |
| A1e | 数据许可决策：CC BY-NC-SA 4.0 非商业/相同方式共享；profile 只作为用户本地导入数据，不随模组分发 | A1d | 发布包与仓库的许可证/attribution 明确，不违反 NC/SA |
| A2 | 一阶镜像源早期反射 + 独立反射 OpenAL source（镜像源位置）+ 平滑；反射增益按距离/空气吸收/材质/指向性计算 | M4 已完成 | 每个 tap 的镜像源位置/延迟/方向正确；反射/直达比与物理计算一致；24–36 source 无爆音/掉帧 |
| A2d | 指向性渲染（直达声 + 反射）；频带精度与计算方法待用户确认（3 频带/1/3 octave/FIR 均可选） | A1d；M4 已完成 | 直达声与反射的旋转/离轴变化都与实测曲线趋势一致；无分频染色/爆音 |
| A3 | 遮挡 + 反射可见性 + 直达声/反射综合校准 | A2/A2d | 遮挡可闻；离轴直达声与反射同时变暗/变弱且量级合理 |
| A4 | 晚期混响 EAXReverb late tail + 参数映射 + 回退 | A0/A1 | RT60/材质变化可闻；无早期反射叠加 |
| A5 | 接收器 GUI 开关/校准/预设 + 性能/设备切换验收 | M4/M3 已完成 | 4K/BSL 下无音频掉帧；设备切换可恢复 |
| A6 | 后续：低频模态/多低音炮、衍射/散射、测量 RIR、HRTF | — | 另行设计 |

## 10. 测试清单

- 几何/单元：镜像源位置/延迟/增益、房间体积/表面积、Eyring RT60、空气吸收曲线、指向性插值、FDN 稳定性。
- 直达声：空气吸收随距离/频率变化、指向性随扬声器旋转/听者角度变化；与反射使用同一套系数，确认没有双重衰减。
- 指向性数据：Spinorama 表归一化/角度方向/插值正确；频带精度待定，3 频带/1/3 octave/FIR 做 A/B；profile 来源/许可证记录完整。
- 游戏内：10×6×4 m 与 20×10×6 m 矩形房间；吸声/反射材质切换；扬声器旋转；听者移动（含走出影院）；反射 source 的镜像位置/方向/距离 L 正确；Seat 仅验证可乘坐，不参与声学。
- A/B：关闭声学层（M4 基线）、仅直达声处理、仅早期反射、仅晚期混响、两者；确认没有双早期反射/梳状滤波，也没有直达声双重距离/空气吸收/指向性。
- A/V：播放/暂停/seek 后无漂移；音频时钟/PTS 不变；视频帧选择不受影响。
- 性能：4K HEVC/AV1 + BSL + 声学；无 audio dropout；CPU/内存记录。
- 兼容：EFX 不可用、设备切换、资源重载、世界卸载、M3 多接收器、24–36 个反射 source 的上限。
- 边界：室外/非矩形/无天花板、无扬声器、LFE-only、单声道/立体声源、玩家远离影院/背对屏幕。
- 最终由用户游戏内验收，不以截图/日志代替听感。

## 11. 风险与未决

- **EAXReverb 可用性/语义**：自定义 context、OpenAL Soft 1.23.1、设备切换、aux send 发送量、wet return 声道布局都需要 spike 验证。
- **EAXReverb 早期反射**：固定 5 m 模型不适合不同房间；计划关闭，仅作无几何 fallback。
- **一阶近似的局限**：忽略衍射、散射、高阶反射、房间模态；对低频/LFE 不准确。
- **直达声/反射 source 的双重处理**：直达声与反射各自独立 source；OpenAL 负责距离/方向，Java 只负责非距离增益，避免同一距离/空气吸收/指向性在两边各乘一次。反射 source 位置必须是镜像源 `s'`，不能放在反射点 `r`，否则 OpenAL 距离不是 `L`。
- **反射 source 数量/性能**：每接收器最多 24–36 个额外 source + buffer；多接收器、HRTF、设备切换、OpenAL source 上限和 CPU 都需实测。
- **OpenAL 距离模型**：`inverse distance clamped` 不是纯 `1/L`；若要求严格物理距离，需把反射 source 的 rolloff 设为 0 并由 Java 计算 `1/L`，待 A2 实测确认。
- **空气吸收/指向性校准**：ISO 9613-1 与 Spinorama 数据都是近似；温湿度、扬声器型号映射和 H/V 两切面都会造成偏差，必须提供开关和 A/B。
- **材质数据**：Minecraft 方块没有实测吸声系数；材质吸收仍是近似，需要试听校准。
- **Spinorama 型号映射**：方块是风格化模型，映射到某个真实型号必然有偏差；需要 tooltip/文档标明 profile 来源，并允许替换。
- **Spinorama 数据质量/角度**：不同测量来源、平滑、测量轴、缺失角度/低频数据会影响结果；H/V 两切面不能完全代表 3D 指向性，分频 lobing 可能被简化。
- **Spinorama 许可**：默认 CC BY-NC-SA 4.0（非商业/相同方式共享），与项目 CC0 代码不同；发布前必须做许可决策/attribution，不能默认打包。
- **房间检测**：矩形假设；非矩形/开放空间可能误判；需要 fallback 和调试可视化。
- **听者策略（已决定）**：不做影院锁定/皇帝位/声场校正；listener 跟随玩家实际耳朵和朝向，保证玩家在影院外也有正确的空间定位。
- **5.1 vs. 耳机**：EAXReverb wet return 在 stereo/HRTF/5.1 设备上的表现不同；可能需要 FDN 或多次 send。
- **多接收器（M3）**：已改为每接收器独立 context/effect slot；多接收器同时播放的 CPU/设备性能仍需实测。
- **低频管理**：单低音炮的房间模态未建模；后续需要多低音炮/模态 EQ/测量 RIR。
- **性能**：Java DSP + EFX + 4K 视频 + shader 的叠加需要实测；音频线程不能卡。
- **设置归属**：声学开关/电平建议存接收器 block entity；几何是客户端本地。需确认是否要 LAN 同步。

## 12. 资料可信度说明

- EAXReverb 的结构描述与 OpenAL Soft 经典 `alcReverb.c` 的公开资料一致；但不同版本实现可能变化，实际行为以运行时 EFX API 为准。
- 共享对话中引用的心理声学研究（一阶反射/指向性）未在本仓库独立复核；本文将其作为设计启发式，不当作严格结论。
- Spinorama 测量数据来自第三方，默认 CC BY-NC-SA 4.0；每个 measurement 的 source/license 必须逐条确认，不能只看站点默认。`quality` 字段已删除。
- Minecraft 方块没有真实声学参数；材质吸收仍是工程近似，必须通过游戏内试听校准；扬声器指向性只做“代表性型号映射”，不是方块本身的实测数据。

## 13. 建议的下一步

1. **A1d/A1e 已完成数据侧工作**：21 个 ASR 型号的 H/V 原始数据转换为本地 profile（不打包），并确认 CC BY-NC-SA 4.0；下一步是在游戏内验证 profile 加载和选择器，不改动实际音频。
2. 做 **A0 EFX spike**：确认 EAXReverb 在自定义 context 中可用，验证 aux send/wet return/reset，记录 OpenAL Soft 1.23.1 行为。
3. M4 扬声器路由已完成，接下来做 **A1–A2**：房间检测 + 直达声空气吸收/指向性 + 一阶镜像源 + 独立反射 source。
4. 做 **A2d–A4**：指向性渲染（频带精度/计算方法待用户确认）、遮挡、晚期混响参数映射；用 A/B 试听决定 early/late 默认电平。
5. 如果 EFX spike 失败，改用小型 FDN 或暂时只做早期反射 + 无混响；不要为了混响阻塞主线。
