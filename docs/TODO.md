# Cinema 待办列表

> 主体只列未完成事项，末尾保留最近完成摘要；完整验收记录见 [HANDOFF.md](HANDOFF.md)。
> 优先级从高到低。长期架构见 [PLANNING.md](PLANNING.md)。

## 现在：剩余验收 / 已知问题

- 暂无阻塞项。视频上传默认策略已定为 `glFenceSync` + 延迟回收，并已通过用户游戏内长测。

## P1：投影仪 / 配对 / 遥控

- [ ] 风扇声：随灯泡功率变化。
- [ ] 屏幕增益 / 离轴变暗。
- [ ] 穿孔屏幕（中置扬声器后置）。
- [ ] 多投影仪 / 多屏幕配对与优先级。

## P1：AV Receiver / 媒体

- [ ] 接收器 GUI 延迟 / 混响设置。
- [ ] 拖放导入媒体库。
- [x] 媒体库探测缓存持久化：FFmpeg 探测结果写入 `<game>/cinema/media-cache.json`，按媒体 key + 大小 + 修改时间校验；每个接收器分别记住自己的库/文件夹/搜索词。
- [ ] 音轨 / 视频轨 / 字幕选择与渲染（多音轨切换、字幕时间轴、字体与样式）。
- [ ] AV Receiver `Video Processing` 模块：输出分辨率/高度、缩放/宽高比/色彩/HDR tone mapping/帧率与画质后处理；投影仪只做光学显示。
- [ ] 接收器 GUI 后续打磨：小窗口/多库布局、长文本与 tooltip、键盘导航、主题与本地化。
- [ ] 4K60 低性能时的自动降级策略（HDR 默认 720p / SDR 1080p，可覆盖）。
- [ ] 硬件解码失败时自动回退软件解码。
- [ ] 原地 seek 状态机重做：当前 seek 仍是完整播放器/解码器重启，但复用视频纹理和 OpenAL device/context/source 池，只 flush 队列和时钟；3 秒冷却 + pending 合并已通过游戏内验收。4K 感知延迟主要来自解码器重新打开/关键帧定位。原地 seek 在未重新设计前不启用。
- [ ] `StreamingAudioDecoder` 消费 FFmpeg stderr（drain 线程），避免错误输出填满管道阻塞。
- [ ] seek 精确到帧（当前在关键帧附近重启）。
- [ ] 大文件快速指纹验收：>64 MiB 文件使用 `q1:` 采样指纹（头 4 MiB + 尾 4 MiB + 文件大小），≤64 MiB 仍用完整 SHA-256；验证选择大文件不再因全量哈希等待。
- [ ] 媒体指纹校验：客户端播放前校验本地文件大小/指纹与接收器记录一致；缺失/不匹配时明确提示。
- [ ] 两小时长片完整内存/队列测试。
- [ ] 资源重载、音频设备切换、世界卸载恢复。

## P1：扬声器 / 音频

- [ ] 5.1 downmix 纯函数 + 单元测试。
- [x] 扬声器遮挡 / 材料 low-pass（A2 直达声：碰撞形状路径扫描 + 材质表 + 单个 4096-tap 联合 FIR）。
- [ ] EFX 房间混响参数化（仅晚期尾音；早期反射见下方“音频计算 / 房间声学”）。
- [ ] LFE / 低频管理（分频、低音炮管理，见 A5）。
- [ ] 每扬声器距离/延迟校准（接收器 GUI）。
- [ ] 世界坐标音频源迁移（若后续需要稳定世界坐标时）：`AL_SOURCE_RELATIVE=false` + listener 世界姿态 + 不可变快照；先做坐标等价测试与游戏内 A/B。评审见 `AUDIO-ACOUSTICS.md` §4.2.1，当前仍不迁移。

## P1：音频计算 / 房间声学（A2 直达声、A2b 反射几何/播放已通过游戏内验收，A2c-R 游戏内 A/B 待做，A3 已实现但听感验收未通过，默认关闭/降级已接入）

> 方案、可行性/声学判断见 [AUDIO-ACOUSTICS.md](design/AUDIO-ACOUSTICS.md)。
> M4 扬声器路由已完成；A0 EFX spike、A1d 数据管线已通过；A2 直达声已通过游戏内听感验收（距离/空气/遮挡/配置 UI）。

- [x] A0 EFX/EAXReverb spike：`/cinema_efx_spike` 游戏内通过。OpenAL Soft；EFX/HRTF/loopback/reopen/deviceClock=true；`ALC_MAX_AUXILIARY_SENDS=2`；shared-caps EFX call=OK；真实设备临时 context EFX=OK；5.1 wet 出现在 5 个非 LFE 声道；HRTF enabled；loopback CPU≈0.8%、≈123× realtime；reset=OK；探针后 UI 音效正常。未测：真实设备听感、物理设备切换、真实硬件延迟（听感留 A3/A4）
- [x] A0b EFX 不可用回退：A3 改为几何晚期总线，不依赖 EFX；EFX 不可用时直达/反射/晚期场均不受影响
- [x] A0c 决策门：A0 验证 EAXReverb late tail 可用；A3 最终选择几何 order-2/3/4 `LateReflectionBus`（FDN 已删除），EAXReverb 仅保留为后续可选 fallback。结论已回写 AUDIO-ACOUSTICS.md
- [x] A1 声学数据模型：房间平面/包围盒、体积/表面积、材质吸收、扬声器位置/朝向/高度、玩家听者位置/朝向。已实现 `/cinema_acoustics` 快照；游戏内 dump 验证无明显问题
- [ ] A1b 材质映射：已改为现实吸声系数的 3 频带估算（CDC/NIOSH、acoustic.ua；screen/植被/雪/多孔/金属为估计）；外部 JSON/数据驱动覆盖和试听校准待补
- [x] A1c 房间检测：6 轴 × 25 射线扇形（可反射 2 次）+ 中位平面 + 9×9 材质采样（裁剪到平面边界） + 宽松内部空气验证 + 5 Hz 缓存 + fallback 保留真实面、缺失轴以已知面延伸 16 m/两面缺失时听者 ±8 m + 1.5 m 平面迟滞 + 有界 32³/8 ms flood fill（有效体积/面积、开口面、10 s 重扫、incomplete 回退盒值）；Fitzroy RT60（x/y/z 轴对面积加权）不设上下限（α_i=0 输出无穷；RT60 仅用于诊断和 FIR 的 `f_s`，FDN 已删除）；游戏内验证无明显问题
- [x] A1-RT60：Fitzroy 公式 `0.161 V/S² · Σ S_i/(-ln(1-α_i))`，按 flood-fill/盒面统计的 x/y/z 轴对面积与平均吸收计算；均匀吸收时退化为 Eyring；不设上下限
- [x] A1 扬声器朝向：36 档 10° 声学 yaw + Speaker Alignment Tool（右键 +10°，潜行 -10°）；方块模型由 `SpeakerBlockEntityRenderer` 同步物理旋转；游戏内验证无明显问题
- [x] A1d ASR/Spinorama 数据管线：只读 `SPL Horizontal.txt`/`SPL Vertical.txt`；保留原始 194/200 点频率网格、不降采样；补 `-180°`；按全频段能量平均计算 -3 dB 频率范围（取网格内最低/最高达标频点，内部凹陷不截断）并取整到 1 Hz；删除 `quality`；21 个型号生成到本地 `run/cinema/speaker_data/profiles/`，**不打包**。转换器：`docs/examples/speaker_data/convert_spinorama.py`。待游戏内加载验证
- [ ] A1e Spinorama 许可决策：已确认 CC BY-NC-SA 4.0；profile 仅作为用户本地导入数据，不随模组分发；仍需在发布文档/THIRD-PARTY 写 attribution
- [x] A2 直达声：Java 距离衰减 `1/max(r,1)`（OpenAL `AL_NONE`，避免双重衰减）、ISO 9613-1 空气吸收（1 m/20 °C/50 % RH/101.325 kPa 为 0 dB 参考点，仅计算超过 1 m 的路径）、遮挡路径扫描 + 宽带衰减/低通、单个 **4096-tap 联合 FIR**（on-axis/指向性 + 空气 + 遮挡低通）、Mod Menu 空气开关/温度/湿度；`/cinema_acoustics` 输出 distanceGain/air8k/occlusion/cutoff/order/fir/taps；数值 harness 已验证 ISO α 与 FIR 幅度，游戏内听感验收通过（距离/空气/遮挡/配置持久化）
- [x] A2b 几何/可见性：`MirrorPlane`/`MirrorPlaneDiscovery`/`ImageSourceSolver`/`ReflectionProbe`；扬声器 26 方向射线（6 轴 + 12 棱对角 + 8 角对角，方向集合可扩展）+ 房间 `samples>0` / flood-fill 补丁种子 + 近共面容差合并（0.10 m）；R 处真实墙面/法线验证、`S→R`/`R→L` 两段遮挡扫描；纯几何 JUnit + 方向/合并测试通过；`/cinema_acoustics` 输出 planes/taps
- [x] A2b 播放：每个 tap 一个独立 OpenAL source（镜像源位置，固定 32 source 池、最多 24 个活动 tap + 8 个淡入/淡出余量）、播放未处理 `sourceChannel` 的延迟副本（8192-frame 原始历史环、分数延迟 + 150 ms 平滑、30 ms 淡入/淡出）、每 tap 4096-tap 反射 FIR；反射 source 由 OpenAL 负责方向/声像，距离由 Java 按 `1/max(L,1)` 计算，不按 5.1 声道混入直达；增益按距离/反射系数/宽带遮挡一次计算，不设固定 -6..-12 dB 默认；`/cinema_acoustics` 输出几何 taps，播放日志输出活动 tap 数；**游戏内听感/CPU/多接收器验收已通过**
- [x] A2c 扬声器指向性（直达）：目标幅度 = on-axis EQ + Spinorama H/V 相对指向性 + 空气 + 遮挡低通；单个 4096-tap 最小相位 FIR @48k（cepstrum），`overallGain` = on-axis + H/V 在参考频率的电平（普通 1 kHz，低音炮带内参考）；5° 角度缓存、异步设计、overlap-add 卷积；实测样本最大误差约 1.1 dB（JBL A180 0.69、Genelec 8351B 1.09、Adam T5V 0.50）；声学中心/朝向/低音炮 omni 已接入
- [x] A2c-R 统一直达/反射 FIR：`CommonAcousticFirProfile` / `CommonAcousticFirDesigner`；直达 4096 taps，反射 **4096-tap**（8192 点 FFT，角度 = 扬声器→反射点）；反射 FIR 目标 = on-axis + H/V（相对 1 kHz）− 空气 + 材质相对形状 + 遮挡低通；`f_s = 2000·sqrt(RT60_mid/V_eff)`（钳位 50–2000 Hz，无效回退 1000 Hz），一阶反射保持全频带，不做 `f_s` 高通/硬切；`overallGain` 与直达声同为参考频率的 on-axis/H/V（普通 1 kHz，低音炮带内参考）；`tap.gain()` 只保留 `sqrt(1-α_mid)/max(L,1)` × 遮挡宽带；材质/遮挡不再使用 biquad，删除 `trimGain()`；两段遮挡合并（衰减相加/较低截止/较高阶），被挡 tap 保留并衰减；flood-fill 补丁种子；**第一版不实现散射，散射见 A3-S1**；单元测试通过，游戏内 A/B 验收待做
- [x] A2c-R 性能修复（2026-11-08）：日志显示 3 分钟内 1953 次直达 FIR 设计、平均 34 ms（峰值 15–22 次/秒），设计 worker 饱和。优化 `overallDb`/材质曲线复用、512 点目标曲线预计算、8192 点 FFT 误差评估后，实测单次设计约 **1–2 ms**；直达设计改为 **12 次 burst + 20 次/秒** token bucket，反射 24 + 30 次/秒；缓存上限 2048，超限按批淘汰而不是全清；移动时可接近 20 Hz 刷新，增益/位置/遮挡宽带仍 20 Hz 更新
- [x] A2b tap 稳定性（2026-11-08）：`AcousticRoom.reflectionKey()` 只包含边界/材质/fill 补丁指纹，`MirrorPlaneDiscovery` 不再用每 5 Hz 变化的 snapshot version 做缓存键；bounded 反射点 margin 提高到 0.10 m；`ImageSourceSolver.strongest` 对已有 `(channel, planeKey)` 加约 1 dB 迟滞，新 tap 需明显更强才替换；减少移动时 tap 闪烁/重排
- [x] A2d 直达声 A/B/CPU：单个 4096-tap 联合 FIR 的听感、移动/转头更新、约 ±1 dB 实测确认；32-bit float source buffer（`AL_EXT_FLOAT32`）、30 ms 半径 + 球面方向（slerp）声像平滑、30 ms 遮挡增益平滑已接入；游戏内复测通过：快速转头/移动无爆音或削波，遮挡切换正常，距离/空气/遮挡/指向性各只应用一次
- [x] A2e Mod Menu 声学设置：空气吸收开关/温度/湿度、`earlyGainDb` 调试滑杆（仅调试，默认 0 dB，现已作用于反射 source 电平）；已持久化到 `config/cinema.json`
- [x] A2f 声学世界尺度（2026-11-12）：Mod Menu `Audio` 页 `World scale` **0.25–2.00 m/block**（默认 1.00，步进 0.05，`metersPerBlock`，`CONFIG_VERSION = 4`）；新增 `AcousticScale` 统一方块↔米换算，接入房间米制尺寸/面积/体积/RT60/Schroeder、ISO 9613-1 空气吸收、距离衰减 `1/max(1 m, r)`、一阶/高阶反射延迟与 `1/max(L,1 m)` 增益、遮挡 dB/m、late bus damping；方块几何/渲染/投影与反射发现容差保持方块单位；尺度变化 `AcousticScene.invalidate()`，`reflectionKey()` 纳入尺度使反射/晚期缓存重建；`/cinema_acoustics` 输出 `scale` 与米制值；`AcousticScaleTest` + 完整 `check` 通过；游戏内 1.00/0.50/0.33 听感 A/B 待做
- [x] A2g 输出上限与限幅（2026-11-12）：主音量 `-60..+18 dBFS`、扬声器 Level `-60..+12 dB`；`CinemaAudioEngine` 把 `AL_MAX_GAIN` 提到 **64**，修复 OpenAL 默认 1.0 上限导致正增益被钳；新增 `OutputLimiter`，按 4 × 2048 frame 队列（≈171 ms 前瞻）统计 direct/reflection/wet 峰值，按当前增益求每声道最坏混合峰值并统一缩放所有 `AL_GAIN`（预测在 `queueBlock` 之后运行）；ceiling **-0.3 dBFS**、瞬时 attack、400 ms release；wet 正 trim 直接走 `AL_GAIN`，不预乘样本；接收器状态行/Signal 页显示 `Limiter -x.x dB`；`OutputLimiterTest` + 完整 `check` 通过；远距离听感与多接收器限幅活动验收待做
- [x] A3 FDN 已删除（2026-11-12）：2026-11-08 的 Java 64-line FDN 听感验收不通过（高频“沙沙”、低频不足、整体音色不平衡），且几何 `LateReflectionBus` 已接管晚期场。已删除 `LateReverb` / `LateReverbSettings` / `LateReverbTarget`、FDN DSP（输入扩散、调制、RT60 low/mid/high、DC 高通、pre-delay、Hadamard 去相关、mid-band 归一化）及相关测试；不再保留 inactive 代码。
- [x] A3 默认关闭（2026-11-08，方案 B）：`DEFAULT_LATE_REVERB=false`；`CinemaAudioEngine` 在启用时才创建 6 个 wet source，禁用时释放 source/buffer 并跳过 late send。配置版本 2，旧配置迁移一次并把 `lateReverb` 置为 false，仍可在 Mod Menu 重新开启。默认路径只保留直达 4096-tap FIR + 一阶反射 4096-tap FIR。低频模态/SBIR 仍归 A5。
- [x] A3-R1 高阶反射几何 spike（2026-11-08）：新增 `ImageSourcePathSolver` / `ReflectionPathTap`，支持 2–5 阶镜像源、完整 plane 序列、逐点 `wallAt` 和分段遮挡；6 面矩形房间单扬声器累计有效路径 **18 / 56 / 122 / 224**（2/3/4/5 阶），JVM 热身后纯几何约 **2–3 ms/扬声器**（不含真实 `ReflectionProbe`）。新增 `/cinema_acoustics paths [order]` 诊断命令，用真实世界统计路径数/延迟/耗时，默认 order 3、每扬声器 cap 256；spike 本身不接入音频。
- [x] A3-R2a order-2 geometric late bus 接入（2026-11-08）：`LateReflectionBus` 已接入 `CinemaAudioEngine`，复用 lazy wet source；`AvReceiverPlayback` 在 5 Hz 反射更新里生成 order-2 路径并发布；`lateReverb` 开关现在驱动几何 bus；默认 `lateReverb=false`；30 ms fade-in、seek/reset 后重新应用 taps；78 项测试通过。**首轮游戏内听感：变化较微妙，但方向正确；无其他问题。**
- [x] A3-R2b 动态规划 order-4 增量接入 + premium per-tap（2026-11-08）：真实世界路径探测只能留在客户端线程。**动态稳态**按 listener 移动 EMA 取 150–600 ms；**phase fast** 也使用可恢复 `ImageSourcePathSolver.Session`，每个 client tick 只跑 **4 ms**，按扬声器逐步完成 order-3；**phase deep** 同样每 tick 4 ms 完成 order-4（默认 visit cap 200k、path cap 512）。两阶段都不再按 200 ms 批量求解，避免单帧尖峰。移动 >0.15 m 会中止并回到 order-2。**premium per-tap：一阶 24 + 最强 16 条二阶 + 8 条三阶走完整 4096-tap FIR；其余二阶/三阶/四阶进 bus 并从 bus 剔除 premium；反射池 56，bus maxTaps 512（当时 raw history 16384、延迟上限 160 ms，已被 A3-R4 取代）。** 79 项测试通过。低频模态仍走 A5。
- [x] A3-R2c LFE 反射接入（2026-11-09）：移除 `ImageSourceSolver` / `ImageSourcePathSolver` 的 `lfe` 排除参数；`AvReceiverPlayback` 和 `/cinema_acoustics` 不再跳过 LFE。LFE 现在与其他 5 声道一样参与一阶/二阶/三阶/四阶几何路径、距离/材质/遮挡/延迟/增益、premium 与 late bus 分类；6 路 wet source 的 LFE 输出不再为空，且不强制低通、不单独削弱。79 项测试通过。
- [x] A3-R3 lazy VBAP（2026-11-09）：`Vbap.java` 实现精确 3D VBAP → 水平 VBAP → 最近扬声器 → 扩散 fallback；输出排除 LFE，权重能量归一化。`LateReflectionBus.Tap` 现在保存输入声道、delay、gain、damping、到达方向以及输出声道/权重数组，bus 把每个 tap 累加到多个输出声道；`AvReceiverPlayback` 用 **listener → 最后反射点** 的方向（经 `toListenerSpace`）调用 VBAP，未配对声道不作为输出目标，无物理布局时回退到输入声道。LFE 作为源声道继续参与完整路径和 VBAP；LFE 扬声器不是普通 VBAP 输出目标。95 项测试通过；待游戏内听感验收。
- [x] 低音炮 speaker data（2026-11-09）：profile 新增 `enclosure`（`sealed`/`ported`/`unknown`；`bass-reflex`、`bass_reflex`、`reflex` 作为旧别名继续可加载），内置 `sub-sealed-omni`（默认）/`sub-sealed-cardioid`/`sub-ported-omni`/`sub-ported-cardioid`，旧 id（`sub-omni`/`sub-cardioid`、`sub-reflex-*`）自动迁移；`pattern` 继续表示 `omni`/`cardioid` 指向性。频率范围阈值改为 on-axis 全频段能量平均的 **-3 dB**，取网格内最低/最高达标频点，内部凹陷不截断；measured profile 加载时重算并忽略过期 JSON `frequencyRange`，但求值/FIR 仍使用完整测量网格保留滚降。低音炮 Low/High 始终手动可编辑（10–1000 Hz，默认跟随 profile），非低音炮 measured profile 仍只读。GUI/`info` 显示箱体、指向性、profile -3 dB 与有效范围，低音炮预览用 80 Hz。2026-11-09 起 enclosure 参与实际声学：内置/未测量 profile 的 sealed 用二阶 Butterworth 高通（内置 30 Hz），ported 用四阶 Butterworth 高通（内置 20 Hz），measured profile 跳过解析模型；手动 Low/High 用二阶 Butterworth 高通/低通实际带限，默认跟随 profile（sealed 30–200 Hz、ported 20–200 Hz），低音炮 FIR/源增益参考 `sqrt(low*high)`。注意：默认 band 低边与 profile -3 dB 低边相同，band 高通会叠加在箱体/实测低边之上，内置 sealed 合成 -3 dB 约 37 Hz、ported 约 23 Hz；如需保持箱体本身的 -3 dB 点，需把 manual low 调低或后续解耦默认值。测试 + Checkstyle 通过；待游戏内验证。
- [x] A3 稳态 speakerGain 刷新（2026-11-09）：`AvReceiverPlayback` 每次反射更新前计算 6 声道有效设置指纹（speaker level、profile id、profile SPL gain）；变化时立即作废 fast/deep late path build、清空 premium 2/3 source 和 late bus 稳态标记，并强制 `updateLatePathBus` 重新发布 order-2 taps。修复进入稳态后调 speaker level/profile 只影响一阶反射、不影响 late bus / premium 2/3 taps 的问题。
- [x] A3 日志诊断修复（2026-11-09）：`busTaps` 改为 bus 实际接受的 tap 数（按 gain/delay 过滤后再取 512 cap），并输出 `requested` 原始发布数；deep 日志同样输出 `requested`/`fastTaps`/`deepTaps`，不再把被 cap 掉的 deep taps 算作有效。`finishFastPhase` 在 `lateReverb` 刚开启、反射更新尚未填充 `latePathRequests` 时直接返回，不再发布空稳态或把 `latePathBuildStartMillis=0` 算成 epoch 级 `upgrade`。FIR 设计警告按 profile id 聚合：首次立即记录，之后最多每 10 s 一条，并附抑制计数与最差误差；反射深零点的大 `maxErr` 在 Javadoc 中说明。
- [x] A3-R4 房间感知几何延迟预算（2026-11-09）：固定 160 ms 上限在大型房间会截断本来有效的 order-3/4 路径。新增 `ReflectionDelayLimit` / `ReflectionDelayBudget`，逐阶上限 `min(1.30 s, (order + 1) · roomDiagonal / 343, historySafe)`；raw history 与 late bus history 均扩到 **65536 frame**（`historySafe ≈ 1.323 s`，两条历史约 3 MiB/接收器）。一阶、order-2、fast order-3、deep order-4 和 `/cinema_acoustics paths` 统一使用该预算；超限路径丢弃并计入 `delayRejected`。48 m 立方房间 order-1/2/3/4 上限约为 485/727/970/1212 ms；无可用房间盒时回退到 `min(1.30 s, historySafe)`。premium order-2/3 仍限制在 150 ms 内使用完整 FIR，更长路径进入 late bus。`ReflectionDelayBudgetTest`、solver 与 late-bus 测试通过；完整 `check` 已通过；游戏内 48 m 房间验收待做。
- [x] A3 物理 wet/dry 目标（2026-11-12）：`LateReflectionTarget` 用 `R = S·α/(1-α)`、`r_c = √(R/16π)`、source power 和实测指向性功率积分计算 `T = √(4ΣP/(RΣD))`；`sourceGainDb = 20log10(2/√R)` 驱动几何 late bus 校准；`lateTrimDb`（默认 0 dB，范围 -24..+6）叠加在物理 gain 上；`/cinema_acoustics` 输出 R/r_c/target/sourceGain/trim/effective；旧 `lateGainDb` 键忽略。`LateReflectionTargetTest`、`SpeakerDataProfileTest` 覆盖公式/指向性/积分/参数
- [ ] A3b EAXReverb fallback（可选，待决策）：是否把 A0 的 EAXReverb late tail 接入为可控回退仍未定。当前几何晚期场不依赖 EFX，且默认关闭（`lateReverb=false`）
- [ ] A3-N1 late bus 噪波阈值 / 自适应深度：当前 fast/deep 固定到 order-3/4 就停止。改为按“新增阶次的路径能量”自适应继续 order-5/6…，直到新增阶次的能量低于噪波阈值或达到安全上限。建议同时提供：
  - **相对阈值**：新阶次能量相对当前 late bus 总能量低于 `lateDepthFloorDb`（默认约 -40 dB，音量无关）；
  - **绝对阈值**：可选 `lateNoiseFloorDb`（dBFS），用于接收器音量/房间底噪已知时提前停止；
  - 每阶能量用 `Σ path.gain²`（必要时按频带/材质加权）估算，阈值必须在当前 `lateTrimDb`、master/channel gain 之后的数字域定义；
  - 安全上限：`maxLateOrder`（建议 6）、每 tick 4 ms 预算、visit cap、path/tap cap；移动/房间变化后从 order-2 重新增量；
  - 记录每阶 `orderEnergyDb`、停止原因（threshold/maxOrder/cap）、实际求解耗时，供 `/cinema_acoustics` 和日志诊断；
  - 测试：合成房间的路径能量衰减、threshold=0 时跑到 maxOrder、默认配置仍以 order-4 为稳态
- [ ] A3-S1 散射/扩散实现：`AcousticMaterial.scattering` 已存在但当前完全未参与求解和增益。建议分两步：
  - **第一步（低成本、推荐）**：能量分流。每 bounce 的镜面压力系数乘 `sqrt(1 - scattering)`；被散射的能量 `scattering` 不生成镜面 tap，而是进入 late bus 的 diffuse send。这样 `scattering=0` 保持现有行为，`scattering=1` 完全去除镜面早期反射；保持能量守恒（镜面² + 散射² ≤ 入射²）。
  - **第二步（可选）**：在反射点按 cosine-weighted hemisphere 采样少量 diffuse ray（固定低差异序列 + 时间平滑 + CPU/路径预算），生成真正的非镜面早期 tap；或把 diffuse 能量集中到 `LateReflectionBus` 的随机/扩散 tap。
  - 频率相关散射（low/mid/high）需要扩展 `AcousticMaterial` 与材质表；第一版可先用单值 scattering。
  - 测试：`scattering=0` 与现有 tap 完全一致、`scattering=1` 无镜面 tap、能量不超入射、无 NaN；`/cinema_acoustics` 输出每 tap 的 specular/scatter 分量和每材质散射系数；游戏内 A/B（fabric/carpet/vegetation vs glass/metal/water）
- [ ] A4 接收器集成：Speakers/Settings 页声学总开关、late reflections 电平、房间预设/重扫；服务端字段/网络包；`earlyGainDb` 放 Mod Menu，不进接收器 GUI
- [ ] A4b 听者策略（决策已定，待 A4 接入）：始终使用玩家实际耳朵位置/朝向；不做 Seat 皇帝位、固定监听点或声场校正
- [ ] A5 低频管理与模态（原 A6 提前，排在 A4 之后）：LFE 分频/低频管理、多低音炮路由与校准（增益/延迟）、低频模态计算与模态 EQ、测量；依赖 A4 的接收器设置与同步
- [ ] A6 调试/验收：dump 房间/RT60/taps/late 参数；A/B 开关；矩形房间、材质、旋转、移动、5.1、4K/BSL、设备切换；A2c-R 直达单 FIR/反射 4096-tap 全频带 FIR（材质/遮挡/空气）/flood-fill 补丁种子/多接收器 CPU，部分墙/柱子/开放空间/玻璃/屏幕/门洞；A3 几何晚期总线：order-2/3/4 增量、premium per-tap、LFE 反射、VBAP 方向/权重、`lateTrimDb` 量级、seek/reset 清空、多接收器 CPU；声学世界尺度 0.25/0.50/1.00/2.00 的 RT60/`f_s`/反射延迟/距离衰减与听感 A/B；输出限幅 -0.3 dBFS 的远距离电平、限幅指示、多接收器同时播放与 16-bit 回退路径；同一声道接多只扬声器时的直达声、限幅峰值累加、反射/late bus 行为，以及 L2a direct source 动态增减、反射池收缩/恢复、GUI/Signal 源计数
- [ ] A7 后续：衍射/散射、测量 RIR/卷积、HRTF

## P2：可变声道布局 / 天空声道（规划）

> 详细方案见 [CHANNEL-LAYOUTS.md](design/CHANNEL-LAYOUTS.md)。本阶段不改当前固定 5.1。

- [ ] L1 `AudioLayout` / 声道枚举 / 上混下混矩阵纯函数 + 单元测试（含 LFE 低通、7.1 侧/后区分）
- [x] OpenAL source 上限核对（2026-11-12）：LWJGL 3.3.3 / OpenAL Soft **1.23.1**，默认每 context **256** 个 source（255 mono + 1 stereo）；两个独立 context 各创建 128 个 source 实测成功；`alsoft.ini [general] sources` 可覆盖且最小 16。`/cinema_acoustics` 输出 `openal sourceLimit`，低于 128 时记录警告并按 `limit - direct - 6 wet` 缩小反射池
- [x] OpenAL context 隔离回归（2026-11-12）：`ALC_EXT_thread_local_context` 必须可用；音频线程只使用 thread-local context，禁止在 Minecraft 拥有全局 context 时抢占它；关闭时恢复进入前的全局 context。实机 `latest.log` 确认 `threadLocal=true`、`OpenAL source limit: 256`、9 只扬声器时 `9 direct + 113 reflection + 6 wet = 128`，且不再出现 `SoundEngine ... Allocate new source: Invalid operation`
- [ ] L2 `AudioChunk` 携带布局和动态声道数；`CinemaAudioEngine` 的**动态 source 分配**已在 L2a 完成（direct/reflection 池按 128 预算重分配）。现有 5.1 零回归。每引擎 source 预算上限 **128**：direct source 按**已连接的真实扬声器**计数（每个声道可以接多只扬声器，不是固定 6），优先保留每只扬声器的 source，剩余预算动态管理一阶反射、二阶/三阶 premium tap 与 lazy wet；5.1 每声道一只时为 6 direct + 116 reflection + 6 lazy wet，12 只扬声器时为 12 + 110 + 6
- [x] L2a 多扬声器直达路由（2026-11-12）：`AvReceiverPlayback` 不再用 `bestDistance` 每声道只取最近一只；每只已连接扬声器各分配一个 direct source，拥有独立位置、trim/指向性 FIR、空气吸收和遮挡；同一输出声道的多只 direct source 通过 `directSourceChannels[]` 映射，限幅峰值按该声道累加；`CinemaAudioEngine` 按布局动态分配/释放 direct source，并把反射池收缩/恢复到 `128 - direct - 6 wet`；**反射与 late bus 已按每只扬声器生成**：一阶 tap key 加入 stable `sourceKey`，每只扬声器独立求解并保证 top 5 条，全局上限 64；late bus 的 order-2/3/4 路径按每只扬声器求解，VBAP 使用全部物理扬声器 + 实际 5.1 声道映射；`check` 通过，游戏内多扬声器反射/late bus 听感、移动、延迟和 CPU 验收待做
- [ ] L3 接收器布局选择（AUTO/显式）+ 7.1 `SL/SR` 角色 + 缺失声道 fallback/状态显示
- [ ] L4 天空声道 `TFL/TFR/TRL/TRR` + 虚拟顶部位置 + 5.1.2/5.1.4/7.1.2/7.1.4
- [ ] L5 天空/吸顶音响（`ceiling_speaker`，吸顶向下发声）：方块/模型/放置/角色限制/GUI
- [ ] L6 布局 GUI 打磨、自动角色建议、多接收器/4K/BSL 下的 7.1.4 性能与听感验收
- [ ] L7 声学模拟接入：高度声道/指向性/反射加入 `acoustic-simulation` 分支，听感验收后再合并
- [ ] 验证 FFmpeg `5.1.2/5.1.4/7.1.2/7.1.4` 的 channel layout 名称、输出顺序与可用性；不可用时准备 `pan`/自定义映射

## 网络 / LAN

- [ ] LAN 播放状态同步（播放/暂停/seek/时间）
- [ ] LAN 客户端媒体可用性提示（缺文件/未配置同名外部库）
- [ ] 主机串流后置评估

## 工程 / 发布

- [x] 客户端目录重组：按 [design/ARCHITECTURE.md](design/ARCHITECTURE.md) 把 `client/audio` 移入 `client/media/audio`，`acoustics` 拆成 `room`/`scene`/`geometry`/`path`/`propagation`，`directivity` + `filter` 合并进 `dsp`，`fir` 改名 `acousticfir`；纯包移动，行为不变
- [x] 统一日志入口：新增 `CinemaLog`，所有运行时日志走 `cinema <area>: <message>` 格式；移除 `cinema-acoustics`/`cinema-efx-spike` 子 logger 与全限定 `Cinema.LOGGER` 引用
- [ ] 外部 FFmpeg 安装检查 UI：Mod Menu 已有路径 + Check，仍缺启动/打开媒体库时的缺失提示与安装引导
- [ ] FFmpeg 版本兼容范围与最低版本
- [ ] FFmpeg 许可/GPL 合规说明
- [ ] 确认原生库不随模组打包；检查发布包体积
- [ ] Windows 之外的 FFmpeg 路径/二进制支持
- [ ] CI 构建
- [ ] 单元测试：屏幕几何、投影 UV、梯形、5.1 矩阵
- [ ] 单元测试：镜像源几何（延迟/增益）、RT60、指向性、VBAP/late bus 稳定性

## 暂不处理（用户决定，不阻塞）

- BSL 下 ambient wash 异常：已记录现象，暂不定位。
- 无音轨视频墙钟长播：暂不测试。
- 对焦模糊物理观感：当前 mipmap/LOD 近似，暂不改进。

## 最近完成

- [x] Mod Menu 设置整理（2026-11-12）：设置页改为 `Screen` / `Audio` / `Video` / `Behavior` / `Advanced` 分页；新增 SDR/HDR 最大解码高度（120 px 步进，`0` = Source）、硬件解码开关、FFmpeg 路径 + Check、FFmpeg info 日志、glFinish 上传诊断；配置版本升到 3，旧配置自动迁移。保留即时保存、250 ms 滑杆去抖、关闭 flush、Defaults 与 tooltip；JVM override 会禁用控件并显示来源。视频项只控制客户端解码/纹理上传，输出处理仍归 AV Receiver Video Processing。

- [x] A2 直达声游戏内验收通过：接收器 `Level`/`Volume` 0.5 dB 步进、0 dB 吸附与方向键步进；石墙/木板/玻璃/屏幕遮挡及屏幕/模组家具透明；距离 `1/max(r,1)`、空气吸收开关/温湿度、快速转头/移动无爆音或削波、Mod Menu 配置持久化；FIR 误差日志仅 >2.0 dB 告警
- [x] 视频上传默认策略定稿：`glFenceSync` + 延迟回收帧缓冲（GPU fence 信号后才归还源缓冲，队列压力/关闭时 `glFinish` 兜底）已通过用户游戏内长测，稳定且 seek 速度符合预期；每帧 `glFinish` 性能差，`-Dcinema.video.glFinish=true` 仅保留为诊断开关
- [x] 遥控器/远程访问：右键打开三栏扫描面板（AV 接收器/投影仪/扬声器），每行 Ping/Access；设备 GUI Close 返回扫描面板；配对保留在接收器/投影仪的配对菜单和线材；遥控器面板不再承载配对，HUD 已移除
- [x] 音视频 seek 稳定性：切换/重启不再残留旧音频；seek 后 3 秒冷却，冷却期内连续点击合并为最后一次；修复 3–4 秒 grace 窗口内新 seek 被吞导致“时间已跳但仍在播旧内容”；音频 source 池复用，用户游戏内验收通过
- [x] 媒体结尾（EOF）停止：音频/视频 seek 或自然播放到 duration 时直接 stop，状态回到 `STOPPED` 且位置归零，不再每 5 秒重试；`audioOnly` 持久化
- [x] 64×64 屏幕：已通过游戏内验收；配对/光学范围上限 128 格，完整矩形下尺寸与 master 坐标正确
- [x] NVIDIA 视频上传历史验证：`-Dcinema.video.glFinish=true` 下，4K VP9 与 4K AV1 HDR 连续播放/seek 未再出现 `nvoglv64.dll` ACCESS_VIOLATION（该模式性能不佳，最终默认策略已改为 fence + 延迟回收）
- [x] 纯音频媒体播放：已实现并通过游戏内验收（mp3/flac/wav/m4a/aac/ogg/opus/wma 等；选择/播放/暂停/seek/停止/音量/5.1 上混；无视频时不要求投影仪）
- [x] 投影仪配对诊断：已通过游戏内验收（过远/朝向/屏幕占用等失败原因有提示；投影仪 GUI 显示屏幕链接与 AV 信号状态）
- [x] 完成音频定位模型评审（相对源 vs 世界坐标源），当前保留相对源，迁移计划挂到 A2 声学
- [x] 用户验收通过：M3 两个接收器独立播放/暂停/seek/音量/静音互不影响
- [x] 用户验收通过：M4 物理 5.1 路由、每扬声器 Level、主音量 dBFS、玩家实际听者定位；Speakers 页滚动/命名/Ping、Seat 乘坐与视角平滑、屏幕单投影仪占用
- [x] 用户验收通过：投影仪 GUI M1/M2/M3、Mod Menu 设置、空手右键交互、线材配对、设备命名、对焦/梯形、HDR/4K 播放

