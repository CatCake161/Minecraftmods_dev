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
- [ ] 扬声器遮挡 / 材料 low-pass。
- [ ] EFX 房间混响参数化（仅晚期尾音；早期反射见下方“音频计算 / 房间声学”）。
- [ ] LFE / 低频管理（分频、低音炮管理）。
- [ ] 每扬声器距离/延迟校准（接收器 GUI）。
- [ ] 世界坐标音频源迁移（与 A2 声学一起）：`AL_SOURCE_RELATIVE=false` + listener 世界姿态 + 不可变快照；先做坐标等价测试与游戏内 A/B。评审见 `AUDIO-ACOUSTICS.md` §4.2.1，当前不迁移。

## P1：音频计算 / 房间声学（M4 已完成，规划/原型阶段）

> 方案、可行性/声学判断见 [AUDIO-ACOUSTICS.md](design/AUDIO-ACOUSTICS.md)。
> M4 扬声器路由已完成；A0 EFX spike、A1d Spinorama 数据/许可可提前做。

- [ ] A0 EFX/EAXReverb spike：在自定义 OpenAL context 中检测 `ALC_EXT_EFX`/`AL_EXT_EFX`；创建 aux slot；验证 per-source send、wet return、stereo/5.1/HRTF 设备、reset/设备切换、CPU/延迟
- [ ] A0b EFX 不可用回退：禁用 late reverb 或最小 Java FDN；干声/播放不受影响
- [ ] A0c 决策门：按 spike 结果选择 EAXReverb late tail / FDN / 无混响，并把结论回写 AUDIO-ACOUSTICS.md
- [ ] A1 声学数据模型：房间平面/包围盒、体积/表面积、材质吸收、扬声器位置/朝向/高度、玩家听者位置/朝向
- [ ] A1b 材质映射：block/tag → 低/中/高频吸收 + 散射；屏幕/羊毛/地毯/木板/石砖/玻璃/树叶/水默认值；数据驱动
- [ ] A1c 房间检测：6 方向 raycast + 凸盒验证；缓存/失效；非矩形/室外 fallback
- [ ] A1d Spinorama 数据管线：申请 API Bearer key（key 仅本地 env）；实现 Plotly `bdata` 解码 + `SPL Horizontal`/`SPL Vertical`/`On Axis` → `speaker_specs` 转换器；选 4 类代表型号；用轴上响应归一化/1/3 octave 降采样/量化；生成 speaker specs 资源与 metadata（source/version/changes/quality/license）
- [ ] A1e Spinorama 许可决策：CC BY-NC-SA 4.0 非商业/相同方式共享，或改用宽松数据/用户本地导入；加 attribution/THIRD-PARTY
- [ ] A2 直达声：距离/空气吸收/遮挡/指向性；明确 OpenAL 距离衰减与自定义增益分工，避免双重衰减
- [ ] A2b 一阶早期反射：镜像源法（6 平面 × 6 扬声器）、可见性、延迟/增益/频带、VBAP/最近声道路由、最多 24–36 taps、平滑
- [ ] A2c 扬声器指向性：用 Spinorama profile 查表（水平/垂直角插值、1/3 octave）；声学中心/朝向；低音炮 omni；只作用于反射相对增益
- [ ] A2d 指向性渲染：3 频带增益/相位兼容分频；条件允许升级 1/3 octave 多频带或 32–64 tap FIR；A/B 实测
- [ ] A3 晚期混响：EAXReverb 仅 late tail（`REFLECTIONS_GAIN=0`、`ECHO_DEPTH=0`）、RT60 Sabine/Eyring、HF/LF ratio、density/diffusion、modulation、air absorption
- [ ] A3b 自定义 FDN 回退（可选）：8 线 FDN、吸收滤波、6 声道 wet、稳定性限制
- [ ] A4 接收器集成：Speakers/Settings 页声学开关、early/late 电平、房间预设/重扫；服务端字段/网络包
- [ ] A4b 听者策略（决策已定，待 A4 接入）：始终使用玩家实际耳朵位置/朝向；不做 Seat 皇帝位、固定监听点或声场校正
- [ ] A5 调试/验收：dump 房间/RT60/taps；A/B 开关；矩形房间、材质、旋转、移动、5.1、4K/BSL、设备切换
- [ ] A6 后续：低频模态/多低音炮/模态 EQ、衍射/散射、测量 RIR/卷积、HRTF

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
