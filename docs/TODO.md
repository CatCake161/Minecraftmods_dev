# Cinema 待办列表

> 优先级从高到低。长期架构见 [PLANNING.md](PLANNING.md)。

## 现在：用户游戏内验收（剩余）

- [x] 真实 HDR10/HLG 文件：颜色、亮度、高光是否正常
- [x] 4K60 VP9：硬件解码是否启用，移动视角是否仍卡顿
- [x] 4K60 HEVC/AV1：与 VP9 对比
- [ ] 无音轨视频：墙钟播放是否稳定
- [x] 投影画面与屏幕四角是否对齐；四个朝向
- [x] 亮度 0.01..1 的观感
- [ ] wash 0..1 的观感；BSL 下某些值画面异常（待定位）
- [x] 配对工具游戏内验收：投影仪↔屏幕（双向顺序）、接收器↔投影仪、接收器↔扬声器、潜行右键两个设备只解除两者连接、区块卸载/重载后链接保留
- [x] 对焦、梯形的观感；移动时是否还闪烁
- [x] 拆掉投影仪后画面是否立即消失
- [x] 投影仪下方/相邻方块是否不再透明

## P1：投影仪与交互

- [x] M1 投影仪 GUI 只读状态页：屏幕配对/距离、灯泡、AV 信号状态（[PROJECTOR-GUI.md](design/PROJECTOR-GUI.md)）
- [x] M2 投影仪 GUI 光学/wash 编辑：投掷比（自动/手动）、焦距（自动/手动）、梯形、亮度、wash、Power ON/OFF；改动即保存（按钮立即/滑块 debounce，Close/Esc flush）
- [x] GUI 只放光学/灯泡/wash/配对；不放解码、播放、文件选择
- [x] 投影仪 GUI 游戏内验收：scale 1..4、边界值、自动投掷比/焦距、改动即保存/Close、拆投影仪/屏幕、Sodium/Iris
- [x] M3 GUI 内物理模拟独立开关：对焦模糊、梯形、环境光洗白、灯泡预热；block entity 持久化/同步，渲染路径跳过
- [x] M3 验收：四个开关分别关闭后画面效果确实变化；专用服务器同步
- [ ] 改进对焦模糊的物理观感（当前 mipmap/LOD 近似一般；后续可评估多级采样/卷积/景深，不阻塞）
- [x] 验收特殊视角修复：贴近屏幕/大角度/抬头低头时不再整屏消失（每个屏幕方块只绘制自己的格子）
- [ ] 验收屏幕尺寸配置：默认 16×16；Mod Menu 设置或 `-Dcinema.screen.maxSize=64` 时 64×64；超过配置的部分裁剪不绘制（已验收 5/10/17/30 保存后生效；64×64 受下条配对/尺寸显示问题影响）
- [x] 验收 Mod Menu 设置菜单：Cinema 条目可见，屏幕尺寸可保存到 `config/cinema.json`；Defaults/Close 正常、改动即保存；打开时暂停游戏；`-D` 覆盖时控件禁用并显示提示；未安装 Mod Menu 时模组正常启动
- [x] 审计所有修改 Minecraft 原版设置/行为的代码路径：仅设备 GUI 的 `isPauseScreen()`、空手右键 `UseBlockCallback`、spike 的 `prepareSpikeEnvironment()`；均已暴露到 Mod Menu 设置页
- [x] 验收 Mod Menu 原版行为开关：设备 GUI 暂停（默认关）、空手右键打开设备 GUI（默认开）、spike 测试修改世界（默认开）；切换后保存到 `config/cinema.json`，并分别检查 GUI 暂停、空手/持物右键、spike 时间/天气/游戏规则行为
- [x] 空手右键交互验收：投影仪、AV 接收器、四种扬声器只有空手右键打开 GUI；手持物品保留原版交互；AV 接收器手持物品右键不再打印状态到聊天栏
- [x] 验收 GUI 保存/退出统一：投影仪、AV 接收器、Speaker Specs、Mod Menu 设置页均为改动即保存 + Close；不再出现 Done/Cancel；文本框/滑块 Close 前 flush
- [x] 验收 Speaker GUI 配对状态：标题显示 `Receiver: linked @ pos` / `Receiver: not linked` / `Receiver: link missing @ pos`，无绿色高亮
- [ ] 修复 64×64 屏幕配对/尺寸显示错误：`maxSize=64` 时填充 4096 方块后投影仪能配对但尺寸/坐标错误；复现时记录 GUI 整行、fill 坐标、投影仪坐标/朝向与屏幕 facing
- [ ] 修复 BSL 下 ambient wash 某些值导致画面异常：确认是白色 overlay 的 bloom/exposure 问题，还是 alpha 曲线问题
- [ ] 遥控器工具：在观众席远程调整投影仪/接收器
- [x] 配对工具（线材/配对器）：投影仪↔屏幕、接收器↔投影仪、接收器↔扬声器（已通过游戏内验收；自动配对已取消）
- [x] 验收 AV 接收器媒体库缓存：首次打开扫描/探测，关闭再打开不重复扫描；Refresh 强制刷新；添加/移除库后缓存失效
- [ ] 风扇声（随功率变化）
- [ ] 屏幕增益/离轴变暗
- [ ] 穿孔屏幕（中置扬声器后置）后置
- [ ] 多投影仪/多屏幕配对与优先级

> 过热/通风不做。

## P1：AV Receiver / 媒体

- [x] AV Receiver 方块 + block entity（状态字段/持久化/同步骨架）
- [x] M1：解码/播放状态/音画时钟从全局 `CinemaPlayer` 迁入接收器
- [x] M1：接收器向投影仪输出视频信号（投影仪不再直接持有播放器）
- [ ] 接收器向扬声器分配 5.1 声道（扬声器不做解码；M4）
- [x] 接收器 GUI 信息架构与页签规格（媒体/扬声器/信号/设置，见 AV-RECEIVER.md §10）
- [x] M2 接收器 GUI：媒体文件、播放/暂停/seek、音量/静音、信号/设置页
- [ ] 接收器 GUI 延迟/混响设置（M4）
- [ ] 每接收器独立播放器/状态（M3）
- [x] 固定媒体库 `cinema/media/` + 相对 key + SHA-256/时长
- [x] M2 外部媒体库：Settings 添加/移除文件夹；`lib/<id>/...` key；Media 页切换；记住上次库/文件夹
- [x] M2 接收器 GUI 媒体列表选择（双击 / Play file）
- [ ] 拖放导入媒体库
- [ ] 音轨 / 视频轨 / 字幕选择与渲染（多音轨切换、字幕时间轴、字体与样式）
- [ ] AV Receiver `Video Processing` 模块（输出分辨率/高度设置、缩放/宽高比/色彩/HDR tone mapping/帧率与画质后处理；投影仪只做光学显示）
- [x] M2 缺文件 GUI 提示（状态栏 + 列表标红）
- [x] M2 GUI 长文本省略号/tooltip 换行/滑块回弹/ESC 暂停循环/文字图层修复
- [ ] M2 后继续打磨接收器 GUI：小窗口/多库布局、长文本与 tooltip、键盘导航、主题与本地化
- [ ] 无音轨墙钟的长时间漂移测试
- [ ] 4K60 低性能时的自动降级策略（HDR 默认 720p / SDR 1080p，可覆盖）
- [ ] 硬件解码失败时自动回退软件解码
- [ ] `StreamingAudioDecoder` 消费 FFmpeg stderr（drain 线程），避免错误输出填满管道阻塞
- [ ] seek 精确到帧（当前是关键帧附近重启）
- [ ] 两小时长片完整内存/队列测试
- [ ] 资源重载、音频设备切换、世界卸载恢复
- [ ] 调查 NVIDIA `nvoglv64.dll` / `glTexSubImage2D` ACCESS_VIOLATION：纹理已跨 seek 复用，可用 `-Dcinema.video.glFinish=true` 复测

## P1：扬声器 / 座位

- [x] Speaker 四种箱体（落地式/书架式/中置/低音炮）+ 角色限制（右键手动循环；接收器分配未接）
- [x] 落地式两格高、书架式一格高；上下半格联动放置/破坏，只掉一个物品
- [ ] Seat 方块 + 皇帝位坐标
- [x] 接收器-扬声器配对与距离计算（双向链接；实际 5.1 音频路由仍见“接收器向扬声器分配 5.1 声道”）
- [ ] 立体声 upmix / 5.1 downmix 纯函数 + 测试
- [ ] 扬声器遮挡 / 材料 low-pass
- [ ] EFX 房间混响参数化（仅晚期尾音；早期反射见下方“音频计算 / 房间声学”）
- [ ] LFE/低频管理

## P1：音频计算 / 房间声学（M4 后）

> 方案、可行性/声学判断见 [AUDIO-ACOUSTICS.md](design/AUDIO-ACOUSTICS.md)。
> 依赖 M4 扬声器路由/Seat；A0 EFX spike、A1d Spinorama 数据/许可可提前做。

- [x] Spike：`cinema/speaker_specs/profiles/` 本地 JSON 导入/客户端 GUI；低音炮内置 omni + cardioid；空手右键扬声器打开 per-speaker GUI，角色选择也在 GUI（C2S `SpeakerRolePayload`）；简单参数 range/SPL gain 可编辑；`assignments.json` 按维度+坐标保存；measured profile 读取水平/垂直指向性 + 轴上响应三组数据；打开 GUI 不暂停游戏
- [ ] A0 EFX/EAXReverb spike：在自定义 OpenAL context 中检测 `ALC_EXT_EFX`/`AL_EXT_EFX`；创建 aux slot；验证 per-source send、wet return、stereo/5.1/HRTF 设备、reset/设备切换、CPU/延迟
- [ ] A0b EFX 不可用回退：禁用 late reverb 或最小 Java FDN；干声/播放不受影响
- [ ] A0c 决策门：按 spike 结果选择 EAXReverb late tail / FDN / 无混响，并把结论回写 AUDIO-ACOUSTICS.md
- [ ] A1 声学数据模型：房间平面/包围盒、体积/表面积、材质吸收、扬声器位置/朝向/高度、听者/Seat
- [ ] A1b 材质映射：block/tag → 低/中/高频吸收 + 散射；屏幕/羊毛/地毯/木板/石砖/玻璃/树叶/水默认值；数据驱动
- [ ] A1c 房间检测：6 方向 raycast + 凸盒验证；缓存/失效；非矩形/室外 fallback
- [ ] A1d Spinorama 数据管线：申请 API Bearer key（`pierre@spinorama.org`，key 仅本地 env）；实现 Plotly `bdata` 解码 + `SPL Horizontal`/`SPL Vertical`/`On Axis` → `speaker_specs` 转换器；选 4 类代表型号；用轴上响应归一化/1/3 octave 降采样/量化；生成 speaker specs 资源与 metadata（source/version/changes/quality/license）
- [ ] A1e Spinorama 许可决策：CC BY-NC-SA 4.0 非商业/相同方式共享，或改用宽松数据/用户本地导入；加 attribution/THIRD-PARTY
- [ ] A2 直达声：距离/空气吸收/遮挡/指向性；明确 OpenAL 距离衰减与自定义增益分工，避免双重衰减
- [ ] A2b 一阶早期反射：镜像源法（6 平面 × 6 扬声器）、可见性、延迟/增益/频带、VBAP/最近声道路由、最多 24–36 taps、平滑
- [ ] A2c 扬声器指向性：用 Spinorama profile 查表（水平/垂直角插值、1/3 octave）；声学中心/朝向；低音炮 omni；只作用于反射相对增益
- [ ] A2d 指向性渲染：3 频带增益/相位兼容分频；条件允许升级 1/3 octave 多频带或 32–64 tap FIR；A/B 实测
- [ ] A3 晚期混响：EAXReverb 仅 late tail（`REFLECTIONS_GAIN=0`、`ECHO_DEPTH=0`）、RT60 Sabine/Eyring、HF/LF ratio、density/diffusion、modulation、air absorption
- [ ] A3b 自定义 FDN 回退（可选）：8 线 FDN、吸收滤波、6 声道 wet、稳定性限制
- [ ] A4 接收器集成：Speakers/Settings 页声学开关、early/late 电平、房间预设/重扫；服务端字段/网络包
- [ ] A4b Seat/听者策略：Seat 皇帝位优先；未坐下时玩家位置；影院锁定 vs. 自由模式
- [ ] A5 调试/验收：dump 房间/RT60/taps；A/B 开关；矩形房间、材质、旋转、移动、5.1、4K/BSL、设备切换
- [ ] A6 后续：低频模态/多低音炮/模态 EQ、衍射/散射、测量 RIR/卷积、HRTF

## 网络 / LAN

- [ ] 服务端权威播放状态
- [ ] LAN 播放状态同步（播放/暂停/seek/时间）
- [ ] 媒体指纹校验与缺文件提示
- [ ] 主机串流后置评估

## 工程 / 发布

- [ ] 外部 FFmpeg 安装检查 UI（缺少时给用户明确提示）
- [ ] FFmpeg 版本兼容范围与最低版本
- [ ] FFmpeg 许可/GPL 合规说明
- [ ] 原生库不再随模组打包；发布包体积检查
- [ ] Windows 之外的 FFmpeg 路径/二进制支持
- [ ] CI 构建
- [ ] 单元测试：屏幕几何、投影 UV、梯形、5.1 矩阵
- [ ] 单元测试：镜像源几何（延迟/增益）、RT60、指向性、FDN 稳定性


## 最近完成

- [x] 用户验收通过：亮度 0.01..1 的观感
- [x] 投影仪 GUI M1/M2/M3：状态页、光学/wash、Throw/Focus Auto、Power、四个物理模拟开关、改动即保存；用户游戏内验收通过
- [x] M2 接收器 GUI：Media / Speakers / Signal / Settings 四页签、媒体列表、播放控制、音量/静音、诊断页
- [x] M2 外部媒体库：`cinema/libraries.json`、`lib/<id>/...` key、添加/移除/切换库、记住上次库与文件夹
- [x] M2 GUI 修复：长文本省略号、tooltip 自动换行、Settings 布局、库列表滚动、seek/音量滑块回弹、ESC 暂停循环、文字图层
- [x] 用户验收通过：M2 界面无溢出、截断/tooltip 正常、4K HEVC 不再 1 秒重启、关闭 GUI 后记住库/文件夹
- [x] M1 接收器播放管线：媒体库/相对 key/SHA-256、C2S 控制包、客户端播放代理、视频路由、投影仪配对（后改为线材显式）、单音频引擎
- [x] 用户验收通过：M1 选片/播放/暂停/seek/音量/静音、拆接收器/投影仪/屏幕后的停止与恢复、缺文件提示
- [x] 2026-11-04 取消自动配对：投影仪不再扫描最近屏幕，接收器不再扫描最近投影仪；线材显式配对新增接收器↔投影仪
- [x] AV Receiver / Speaker 方块、block entity、模型、掉落表
- [x] 扬声器类型拆分：落地式、书架式、中置、低音炮；按箱体限制声道角色
- [x] 落地式改为两格高（上下半格），书架式保持一格
- [x] 线材（Cable）/遥控器（Remote Controller）物品、模型、中英文名称；遥控器自定义图标
- [x] 用户验收通过：四种扬声器外观/角色限制、两格落地式放置与破坏、书架式原落地模型、手持与放置均无闪烁
- [x] 用户验收通过：HDR10/HLG、4K60 VP9、4K60 HEVC/AV1、四角对齐/四个朝向、对焦/梯形、拆投影仪立即黑屏、相邻方块不透明
- [x] 投影仪方块/模型/光学第一版
- [x] 屏幕配对（现为线材显式）、逐块投影/裁剪
- [x] 对焦 mipmap + LOD bias、按需生成
- [x] 删除遮挡阴影（暂时不做）
- [x] 环境光洗白按亮度影响对比度
- [x] 灯泡预热/冷却、亮度过驱
- [x] 东/西屏幕 master/right 修复
- [x] 删除 `/cinema_test_scene`、`/cinema_visual_spike` 和 `CinemaVisualSpike`
- [x] 手动测试模式、游戏内帮助、测试环境固定
- [x] 外部 FFmpeg 解码、硬件解码、HDR tone mapping
- [x] 帧缓冲池、分辨率上限、无音轨墙钟
- [x] 移除 flat fallback；投影仪 noOcclusion
