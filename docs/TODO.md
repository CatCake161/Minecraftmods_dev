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

## P1：音频计算 / 房间声学（A2 直达声、A2b 反射几何/播放已通过游戏内验收，A2c-R 已实现、游戏内 A/B 验收待做）

> 方案、可行性/声学判断见 [AUDIO-ACOUSTICS.md](design/AUDIO-ACOUSTICS.md)。
> M4 扬声器路由已完成；A0 EFX spike、A1d 数据管线已通过；A2 直达声已通过游戏内听感验收（距离/空气/遮挡/配置 UI）。

- [x] A0 EFX/EAXReverb spike：`/cinema_efx_spike` 游戏内通过。OpenAL Soft；EFX/HRTF/loopback/reopen/deviceClock=true；`ALC_MAX_AUXILIARY_SENDS=2`；shared-caps EFX call=OK；真实设备临时 context EFX=OK；5.1 wet 出现在 5 个非 LFE 声道；HRTF enabled；loopback CPU≈0.8%、≈123× realtime；reset=OK；探针后 UI 音效正常。未测：真实设备听感、物理设备切换、真实硬件延迟（听感留 A3/A4）
- [ ] A0b EFX 不可用回退：禁用 late reverb 或最小 Java FDN；干声/播放不受影响
- [x] A0c 决策门：EAXReverb late tail 可用且 wet 能覆盖 5.1；选择 EAXReverb 作为 late-tail 首选，FDN 仅作为 EFX 不可用/布局不合适的回退。结论已回写 AUDIO-ACOUSTICS.md
- [x] A1 声学数据模型：房间平面/包围盒、体积/表面积、材质吸收、扬声器位置/朝向/高度、玩家听者位置/朝向。已实现 `/cinema_acoustics` 快照；游戏内 dump 验证无明显问题
- [ ] A1b 材质映射：已改为现实吸声系数的 3 频带估算（CDC/NIOSH、acoustic.ua；screen/植被/雪/多孔/金属为估计）；外部 JSON/数据驱动覆盖和试听校准待补
- [x] A1c 房间检测：6 轴 × 25 射线扇形（可反射 2 次）+ 中位平面 + 9×9 材质采样（裁剪到平面边界） + 宽松内部空气验证 + 5 Hz 缓存 + fallback 保留真实面、缺失轴以已知面延伸 16 m/两面缺失时听者 ±8 m + 1.5 m 平面迟滞 + 有界 32³/8 ms flood fill（有效体积/面积、开口面、10 s 重扫、incomplete 回退盒值）；Fitzroy RT60（x/y/z 轴对面积加权）不设上下限（α_i=0 输出无穷，A3 再 clamp EAX 参数）；游戏内验证无明显问题
- [x] A1-RT60：Fitzroy 公式 `0.161 V/S² · Σ S_i/(-ln(1-α_i))`，按 flood-fill/盒面统计的 x/y/z 轴对面积与平均吸收计算；均匀吸收时退化为 Eyring；不设上下限
- [x] A1 扬声器朝向：36 档 10° 声学 yaw + Speaker Alignment Tool（右键 +10°，潜行 -10°）；方块模型由 `SpeakerBlockEntityRenderer` 同步物理旋转；游戏内验证无明显问题
- [x] A1d ASR/Spinorama 数据管线：只读 `SPL Horizontal.txt`/`SPL Vertical.txt`；保留原始 194/200 点频率网格、不降采样；补 `-180°`；按全频段能量平均计算 -10 dB 频率范围并取整到 1 Hz；删除 `quality`；21 个型号生成到本地 `run/cinema/speaker_data/profiles/`，**不打包**。转换器：`docs/examples/speaker_data/convert_spinorama.py`。待游戏内加载验证
- [ ] A1e Spinorama 许可决策：已确认 CC BY-NC-SA 4.0；profile 仅作为用户本地导入数据，不随模组分发；仍需在发布文档/THIRD-PARTY 写 attribution
- [x] A2 直达声：Java 距离衰减 `1/max(r,1)`（OpenAL `AL_NONE`，避免双重衰减）、ISO 9613-1 空气吸收（1 m/20 °C/50 % RH/101.325 kPa 为 0 dB 参考点，仅计算超过 1 m 的路径）、遮挡路径扫描 + 宽带衰减/低通、单个 **4096-tap 联合 FIR**（on-axis/指向性 + 空气 + 遮挡低通）、Mod Menu 空气开关/温度/湿度；`/cinema_acoustics` 输出 distanceGain/air8k/occlusion/cutoff/order/fir/taps；数值 harness 已验证 ISO α 与 FIR 幅度，游戏内听感验收通过（距离/空气/遮挡/配置持久化）
- [x] A2b 几何/可见性：`MirrorPlane`/`MirrorPlaneDiscovery`/`ImageSourceSolver`/`ReflectionProbe`；扬声器 26 方向射线（6 轴 + 12 棱对角 + 8 角对角，方向集合可扩展）+ 房间 `samples>0` / flood-fill 补丁种子 + 近共面容差合并（0.10 m）；R 处真实墙面/法线验证、`S→R`/`R→L` 两段遮挡扫描；纯几何 JUnit + 方向/合并测试通过；`/cinema_acoustics` 输出 planes/taps
- [x] A2b 播放：每个 tap 一个独立 OpenAL source（镜像源位置，固定 32 source 池、最多 24 个活动 tap + 8 个淡入/淡出余量）、播放未处理 `sourceChannel` 的延迟副本（8192-frame 原始历史环、分数延迟 + 150 ms 平滑、30 ms 淡入/淡出）、每 tap 2048-tap 反射 FIR；反射 source 由 OpenAL 负责方向/声像，距离由 Java 按 `1/max(L,1)` 计算，不按 5.1 声道混入直达；增益按距离/反射系数/宽带遮挡一次计算，不设固定 -6..-12 dB 默认；`/cinema_acoustics` 输出几何 taps，播放日志输出活动 tap 数；**游戏内听感/CPU/多接收器验收已通过**
- [x] A2c 扬声器指向性（直达）：目标幅度 = on-axis EQ + Spinorama H/V 相对指向性 + 空气 + 遮挡低通；单个 4096-tap 最小相位 FIR @48k（cepstrum），`overallGain` = on-axis + H/V 在 1 kHz 的电平；5° 角度缓存、异步设计、overlap-add 卷积；实测样本最大误差约 1.1 dB（JBL A180 0.69、Genelec 8351B 1.09、Adam T5V 0.50）；声学中心/朝向/低音炮 omni 已接入
- [x] A2c-R 统一直达/反射 FIR：`CommonAcousticFirProfile` / `CommonAcousticFirDesigner`；直达 4096 taps，反射 **2048-tap**（8192 点 FFT，角度 = 扬声器→反射点）；反射 FIR 目标 = on-axis + H/V（相对 1 kHz）− 空气 + 材质相对形状 + 遮挡低通；`f_s = 2000·sqrt(RT60_mid/V_eff)`（钳位 50–2000 Hz，无效回退 1000 Hz），`f <= f_s` 硬切为 -96 dB；`overallGain` 与直达声同为 1 kHz 的 on-axis/H/V；`tap.gain()` 只保留 `sqrt(1-α_mid)/max(L,1)` × 遮挡宽带；材质/遮挡不再使用 biquad，删除 `trimGain()`；两段遮挡合并（衰减相加/较低截止/较高阶），被挡 tap 保留并衰减；flood-fill 补丁种子；**不实现散射**；单元测试通过，游戏内 A/B 验收待做
- [x] A2c-R 性能修复（2026-11-08）：日志显示 3 分钟内 1953 次直达 FIR 设计、平均 34 ms（峰值 15–22 次/秒），设计 worker 饱和。优化 `overallDb`/材质曲线复用、512 点目标曲线预计算、8192 点 FFT 误差评估后，实测单次设计约 **1–2 ms**；直达设计改为 **12 次 burst + 20 次/秒** token bucket，反射 24 + 30 次/秒；缓存上限 2048，超限按批淘汰而不是全清；移动时可接近 20 Hz 刷新，增益/位置/遮挡宽带仍 20 Hz 更新
- [x] A2d 直达声 A/B/CPU：单个 4096-tap 联合 FIR 的听感、移动/转头更新、约 ±1 dB 实测确认；32-bit float source buffer（`AL_EXT_FLOAT32`）、30 ms 半径 + 球面方向（slerp）声像平滑、30 ms 遮挡增益平滑已接入；游戏内复测通过：快速转头/移动无爆音或削波，遮挡切换正常，距离/空气/遮挡/指向性各只应用一次
- [x] A2e Mod Menu 声学设置：空气吸收开关/温度/湿度、`earlyGainDb` 调试滑杆（仅调试，默认 0 dB，现已作用于反射 source 电平）；已持久化到 `config/cinema.json`
- [ ] A3 晚期混响：EAXReverb 仅 late tail（`REFLECTIONS_GAIN=0`、`ECHO_DEPTH=0`）、RT60 Fitzroy、HF/LF ratio、density/diffusion、modulation、air absorption
- [ ] A3b 自定义 FDN 回退（可选）：8 线 FDN、吸收滤波、6 声道 wet、稳定性限制
- [ ] A4 接收器集成：Speakers/Settings 页声学总开关、late reverb 电平、房间预设/重扫；服务端字段/网络包；`earlyGainDb` 放 Mod Menu，不进接收器 GUI
- [ ] A4b 听者策略（决策已定，待 A4 接入）：始终使用玩家实际耳朵位置/朝向；不做 Seat 皇帝位、固定监听点或声场校正
- [ ] A5 低频管理与模态（原 A6 提前，排在 A4 之后）：LFE 分频/低频管理、多低音炮路由与校准（增益/延迟）、低频模态计算与模态 EQ、测量；依赖 A4 的接收器设置与同步
- [ ] A6 调试/验收：dump 房间/RT60/taps；A/B 开关；矩形房间、材质、旋转、移动、5.1、4K/BSL、设备切换；A2c-R 直达单 FIR/反射 2048-tap FIR（材质/遮挡/空气/f_s 硬切）/flood-fill 补丁种子/多接收器 CPU，部分墙/柱子/开放空间/玻璃/屏幕/门洞
- [ ] A7 后续：衍射/散射、测量 RIR/卷积、HRTF

## P2：可变声道布局 / 天空声道（规划）

> 详细方案见 [CHANNEL-LAYOUTS.md](design/CHANNEL-LAYOUTS.md)。本阶段不改当前固定 5.1。

- [ ] L1 `AudioLayout` / 声道枚举 / 上混下混矩阵纯函数 + 单元测试（含 LFE 低通、7.1 侧/后区分）
- [ ] L2 `CinemaAudioEngine` 动态 source 数、`AudioChunk` 携带布局；现有 5.1 零回归
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

- [ ] 外部 FFmpeg 安装检查 UI（缺少时给用户明确提示）
- [ ] FFmpeg 版本兼容范围与最低版本
- [ ] FFmpeg 许可/GPL 合规说明
- [ ] 确认原生库不随模组打包；检查发布包体积
- [ ] Windows 之外的 FFmpeg 路径/二进制支持
- [ ] CI 构建
- [ ] 单元测试：屏幕几何、投影 UV、梯形、5.1 矩阵
- [ ] 单元测试：镜像源几何（延迟/增益）、RT60、指向性、FDN 稳定性

## 暂不处理（用户决定，不阻塞）

- BSL 下 ambient wash 异常：已记录现象，暂不定位。
- 无音轨视频墙钟长播：暂不测试。
- 对焦模糊物理观感：当前 mipmap/LOD 近似，暂不改进。

## 最近完成

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

