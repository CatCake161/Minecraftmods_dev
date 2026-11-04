# Cinema 交接清单

> 新会话请按顺序读：本文件 → [PLANNING.md](PLANNING.md) → [TODO.md](TODO.md) → [PROJECTOR-GUI.md](design/PROJECTOR-GUI.md)。
> 当前分支 `main`，最新提交见 `git log -1`；工作区应为干净。

## 1. 当前状态

- Minecraft 1.21.1 + Fabric，Java 21；包名 `com.example.cinema`，模组 id `cinema`。
- 主模组构建通过：`.\gradlew build`。
- 最近一局 M2 验收：接收器 GUI、外部媒体库、4K HEVC 播放稳定；不再 1 秒一次重启。
- 用户已验收通过：HDR10/HLG、4K60 VP9、4K60 HEVC/AV1、四角对齐/四个朝向、对焦/梯形、亮度 0.01..1、拆投影仪立即黑屏、相邻方块不透明。
- 剩余验收：无音轨长播、wash 观感（BSL 下某些值画面异常，待定位）。
- 投影仪光学、外部 FFmpeg、临时 wash GUI 已可运行。
- **AV Receiver M1/M2 已完成并验收**：M1 把媒体/播放状态/音画时钟迁入接收器，新增 `AvReceiverControlPayload`（C2S）与服务端校验；客户端 `AvReceiverPlaybackManager` 每 10 tick 同步接收器状态，视频经 `ProjectorOptics` 路由到屏幕；M3 起每个接收器持有独立播放器/纹理/OpenAL 5.1 context，可同时播放（待游戏内验收）。M2 新增接收器 GUI（Media/Speakers/Signal/Settings）、默认库 + 外部媒体库、`lib/<id>/...` key、添加/移除/切换库、记住上次库/文件夹，以及长文本截断、tooltip 换行、滑块 pending、ESC 暂停同步、文字图层修复。媒体库扫描/探测结果按“库 + 文件夹”在当前游戏会话内缓存，重复打开 GUI 不再重扫（已验收）；Refresh 强制刷新，添加/移除库会使对应缓存失效。接收器每 20 tick 校验显式投影仪链接；自动配对已取消（2026-11-04）。设计见 `docs/design/AV-RECEIVER.md`。
- 用户已验收通过：M1 选片/播放/暂停/seek/音量/静音、旧版自动配对投影仪、拆接收器/投影仪/屏幕后的停止与恢复、缺文件提示；M2 界面无溢出、截断/tooltip 正常、4K HEVC 不再 1 秒重启、关闭 GUI 后记住库/文件夹。
- 扬声器已拆分为四种箱体：落地式 `speaker`（两格高）、书架式 `bookshelf_speaker`（一格高，FL/FR/BL/BR）、中置 `center_speaker`（一格高，FC）、低音炮 `subwoofer`（一格高，LFE）；空手右键打开该扬声器的 speaker specs GUI，角色选择在 GUI 内点击即提交服务端。落地式下半格持有 block entity，上半格为无 block entity 的延伸；破坏任一半只掉一个物品。线材已升级为配对工具（投影仪↔屏幕、接收器↔投影仪、接收器↔扬声器，服务端权威、48 格限制；潜行右键两个设备解除两者连接）；遥控器仍是占位。未来优化项：遥控器绑定后从观众席远程打开 GUI/调整参数，并支持远程设备配对；线材保留为近距物理配对 fallback（见 TODO）。
- 用户已验收通过：四种扬声器外观与角色限制、两格落地式放置/破坏、书架式原落地模型、手持与放置均无闪烁。

## 2. 必须遵守的架构约束

1. **AV Receiver 拥有媒体**
   - 媒体文件、FFmpeg 解码、播放/暂停/seek、音画时钟、播放状态都归接收器。
   - 接收器向投影仪输出视频信号，向扬声器输出 5.1 音频。
2. **投影仪只做物理/画面**
   - 只接收来自 AV 的视频信号。
   - GUI 只放光学、灯泡、wash、配对、物理模拟开关。
   - GUI 不出现文件选择、codec、解码 FPS、丢帧、播放控制。
3. **扬声器只做回放**
   - 只接收接收器分配的声道，不直接读文件、不解码。
4. **投影仪 GUI 的正式设计见 `docs/design/PROJECTOR-GUI.md`**；当前 `ProjectorConfigScreen` 只是临时 wash 滑块。
5. 空手右键投影仪打开 GUI；手持物品时保留原版交互。改动即保存，Close/Esc 只关闭。

## 3. 本会话已完成

- 用户已验收通过投影仪 GUI M1/M2/M3：状态区、光学/wash、Throw/Focus Auto、Power、四个模拟开关、改动即保存；对焦模糊观感一般，列为后续改进。
- 投影仪 GUI M1/M2/M3：
  - `ProjectorConfigScreen` 替换临时 wash 滑块；状态区显示屏幕、灯泡、AV 输入；光学/wash 滑块只改草稿。
  - 新增 C2S `ProjectorSettingsPayload` + `ProjectorSettingsHandler`；服务端校验距离/方块/范围后 `ProjectorBlockEntity.applySettings` 一次写入。
  - 自动/手动投掷比与焦距、Power ON/OFF、Defaults、改动即保存（按钮立即/滑块 debounce）、GUI 不暂停；2 列紧凑布局。
  - M3 物理模拟开关 Focus/Keystone/Wash/Warmup：切换即发送，`ProjectorOptics` 和灯泡 ramp 按开关跳过对应计算，NBT/网络同步。
- 投影仪光学第一版：
  - 投掷比、自动/手动焦距、垂直/水平梯形、亮度、灯泡预热/冷却
  - 屏幕配对（现为线材显式）、逐块镜头 UV/裁剪
  - 环境光洗白改为手动 `ambientWash`（0..1）
  - 亮度限制为 `0.01..1.0`
  - 临时 wash GUI + `/cinema_projector[_client] wash <0..1>`
- 外部 FFmpeg 迁移：
  - 移除 JavaCV/JavaCPP
  - 用户必须把 gyan.dev full build 放到 `mods/ffmpeg/bin/`
  - 解码、硬件加速、HDR tone mapping、帧缓冲池、无音轨墙钟
  - HDR 默认输出 720p，SDR 默认 1080p
- 删除：
  - `/cinema_test_scene` 及其方块跟踪/清理逻辑
  - `/cinema_visual_spike` 和 `CinemaVisualSpike` 类
  - 投影仪遮挡阴影 `ProjectorShadow`（暂时不做）
  - 镜头光晕、投影仪过热/通风（规划和文档中已移除）
- 影院设备第一版物品：
  - `av_receiver` 方块 + block entity（媒体/播放/音量/视频输出字段）
  - `speaker` / `bookshelf_speaker` / `center_speaker` / `subwoofer` 四种扬声器方块 + 共享 block entity
  - 落地式为真正的两格高方块（上半格 dummy，无 block entity）；书架式/中置/低音炮保持一格
  - 每种箱体只允许合法声道角色（中置仅 FC，低音炮仅 LFE）
  - `cable` / `remote_controller` 物品（线材/遥控器占位；遥控器使用自定义 16×16 图标）
  - 模型、blockstate、中英文语言、掉落表
- 文档重组：
  - `docs/PLANNING.md`、`docs/TODO.md`、`docs/COMMANDS.md`
  - `docs/design/P0-SPIKE-REPORT.md`、`docs/design/P05-DESIGN.md`
  - `docs/design/PROJECTOR-GUI.md`、`docs/design/AV-RECEIVER.md`
  - 个人绝对路径/姓名/邮箱已从工作树移除
- 音频声学规划：
  - 新增 `docs/design/AUDIO-ACOUSTICS.md`：EAXReverb 只取晚期尾音，早期反射用一阶镜像源 + **Spinorama 实测扬声器指向性**；
    明确只做扬声器指向性/不做原始声源指向性、材质近似、低频模态不建模、Spinorama CC BY-NC-SA 许可、EFX spike 与 M4 扬声器路由依赖。
  - 当前音频管线基线（FFmpeg → StreamingAudioDecoder → AudioChunk → CinemaAudioEngine → OpenAL，虚拟 5.1，M4 前不绑定实体扬声器）已写入 AUDIO-ACOUSTICS.md §4.1。
  - 已实现 speaker specs spike：`cinema/speaker_specs/profiles/` 本地 JSON、拖放导入、空手右键扬声器打开 per-speaker GUI（角色选择也在 GUI 内，点击即通过 C2S `SpeakerRolePayload` 提交）、`/cinema_speaker_specs` 类型默认调试入口；类型默认 `settings.json`，每扬声器 `assignments.json`（维度+坐标）。measured profile 读取三组 Spinorama 数据：水平指向性、垂直指向性、轴上响应（`onAxis`）；低音炮内置 `sub-omni`/`sub-cardioid`，其他类型为 unmeasured 占位；简单参数 range/SPL gain 可编辑。GUI `isPauseScreen=false`，打开不暂停游戏；改动即保存，`Defaults` 恢复默认，`Close`/Esc 关闭；窗口标题显示接收器配对状态（linked / not linked / link missing），不额外占用正文行。尚未把 speaker specs 应用到实际音频。
  - 同步更新 `PLANNING.md`（P1-7 里程碑/风险/测试原则）、`TODO.md`（Spike + A0–A6/A1d/A1e/A2d 任务）、`AV-RECEIVER.md`（交叉引用）。

## 4. 已知问题 / 风险

- 4K60/HDR 已由用户验收通过；建议后续记录具体 codec/pix_fmt/hwaccel/丢帧数据，便于回归。
- **BSL 下 ambient wash 某些值画面异常（待定位具体表现/数值）**；无音轨墙钟长播未测。
- M2 GUI 测试期间再次出现 `nvoglv64.dll` ACCESS_VIOLATION（NVIDIA OpenGL 驱动层），栈为 `CinemaVideoTexture.upload → glTexSubImage2D`；不是 Java 异常。已改为每个接收器跨 seek 复用视频纹理，并提供 `-Dcinema.video.glFinish=true` 诊断开关，待复测。
- AV Receiver M1/M2/M3/M4 已实现；M3/M4 等待游戏内验收。投影仪/扬声器使用接收器自己的播放代理与音频引擎。
- **音频声学仍是规划**：见 `docs/design/AUDIO-ACOUSTICS.md`。EAXReverb 只计划用于晚期尾音；早期反射用一阶镜像源 + **Spinorama 实测扬声器指向性**。依赖 M4 扬声器路由；EFX 可用性、材质吸收、型号映射都需 spike/校准。
- M4 已把配对的实体扬声器按角色绑定到六个 source（听者始终是玩家实际耳朵/朝向，不使用 Seat 或固定声场校正）；未配对声道仍用虚拟 5.1。真实房间声学/指向性/反射尚未接入。
- Spinorama 测量数据默认 CC BY-NC-SA 4.0（非商业/相同方式共享），与项目 CC0 不同；发布前必须做许可/attribution 决策。
- Spinorama REST API 现在需要人工申请 Bearer key（无匿名访问）；key 只能放本地环境变量/转换工具，不能进仓库、模组发布包或日志。API 不提供 Erin's Audio Corner 测量。
- 一阶声学模型不覆盖衍射、散射、高阶反射和低频房间模态；LFE 低频峰谷/驻波不会正确重现。
- **特殊视角下整屏画面消失（已修复并通过游戏内验收）**：旧逻辑只让 master 屏幕方块实体绘制整屏，master 所在 section 被视锥剔除时整屏消失。现改为每个屏幕方块实体只绘制自己那一格，并加逐帧 `ScreenGeometry` 缓存、将 BE 视距提高到 512。屏幕尺寸同时改为可配置：Mod Menu → Cinema → Config 或 `-Dcinema.screen.maxSize=1..64`，默认 16，上限 64×64；超过当前上限的部分不绘制。
- **throw auto 裁切（2026-11-04 已修复）**：旧公式只按屏幕宽度计算投掷比，屏幕宽高比与视频不一致或斜向安装时会裁切。现按屏幕四角的视角范围与视频宽高比拟合，保留约 2% 边缘余量；手动投掷比不受影响。
- **64×64 屏幕配对/尺寸显示错误（2026-11-03，待定位，先登记不改代码）**：以 `-Dcinema.screen.maxSize=64` 启动后填充 4096 个屏幕方块（64×64），投影仪 GUI / `/cinema_projector_client info` 能显示屏幕，但**尺寸/坐标错误**。特殊视角修复本身已通过验收；此问题与逐格渲染、扫描上限还是配对逻辑的关系尚未确认。下次复现需记录：屏幕信息整行原文（尺寸、master 坐标、距离）、`/fill` 坐标范围、投影仪坐标/朝向、屏幕 facing。
- **Mod Menu 设置菜单（2026-11-04 已实现并验收）**：`config/cinema.json` 保存 `screenMaxSize`（1..64，默认 16）；Mod Menu 的 Cinema 条目打开 `CinemaConfigScreen`（`isPauseScreen=true`，打开时暂停游戏）。屏幕尺寸关闭设置页后立即生效。`-Dcinema.screen.maxSize` 仍优先，GUI 会显示 override 并禁用控件。Mod Menu 是软依赖（`modCompileOnly`），未安装时模组照常启动。用户已确认 Cinema 条目可见、设置页可打开、保存生效（5/10/17/30）且打开时暂停。同页新增三项原版行为开关（默认值）：`pauseGameInDeviceGuis=false`（设备 GUI 是否暂停游戏）、`emptyHandOpensDeviceGuis=true`（空手右键是否打开设备 GUI）、`spikeModifiesWorld=true`（spike 测试是否修改时间/天气/游戏规则并清除生物；仅在 `cinema.spike.*` 路径生效）。视频输出高度/缩放已从 Mod Menu 移除，归未来的 AV Receiver `Video Processing` 模块；调试仍保留 `-Dcinema.video.maxHeight` 覆盖。
- **原版设置/行为修改审计（2026-11-04）**：代码中修改 Minecraft 原版行为的位置只有设备 GUI 的 `isPauseScreen()`（投影仪/AV 接收器/扬声器，默认 false）、`CinemaClient` 的空手 `UseBlockCallback`（打开设备 GUI）、以及 `prepareSpikeEnvironment()`（时间/天气/游戏规则/清除生物，仅测试路径）。三项均已暴露到 Mod Menu 设置页；`CinemaConfigScreen` 自身保持 `isPauseScreen=true`（与原版一致）。未发现代码读写 `Minecraft.options` 或 `options.txt`。
- **空手右键交互（2026-11-04 已验收）**：投影仪、AV 接收器、四种扬声器统一为只有空手右键才打开 GUI；手持物品时保留原版物品/方块交互。AV 接收器原先手持物品右键向聊天栏打印 `status()` 的逻辑已移除（`AvReceiverBlock.useWithoutItem` 删除），状态仍可通过 `/cinema_receiver` 查看。
- **配对工具（2026-11-04 已验收）**：`cinema:cable` 两次右键配对，支持投影仪↔屏幕、接收器↔投影仪、接收器↔扬声器。投影仪保存 `screenPos` 显式屏幕链接；接收器保存 `videoTarget`、`speakerTargets`，投影仪/扬声器保存反向 `receiverPos`。解除连接改为潜行右键设备 A，再潜行右键设备 B，只解除两者之间的连接（任意屏幕方块可识别；同一设备再次潜行右键取消）。自动配对已取消：未配对的投影仪不再扫描屏幕，接收器也不再扫描投影仪。选择状态由服务端静态表保存，60 秒超时/断线清除；最大距离 48 格；显式屏幕链接允许斜向安装，但仍要求屏幕在投影仪前方。`setRemoved()` 已区分区块卸载与真实拆除，避免离开区域时误清链接。接收器↔扬声器已参与 M4 物理 5.1 路由；旧存档中的自动接收器↔投影仪链接在加载时清除。
- **GUI 保存/退出统一（2026-11-04 已验收）**：投影仪、AV 接收器、Speaker Specs、Mod Menu 设置页全部改为“改动即保存 + Close”；不再有 Done/Cancel。投影仪/扬声器/设置页保留 Defaults；AV 接收器没有可用的全局默认值，只保留 Close。AV 接收器 Close 位于内容区右下（与 Up/Play file 同一行），设置页库列表为其让出底部空间；文本框/滑块 debounce，Close/Esc 前 flush。Speaker GUI 配对状态显示在窗口标题，不使用绿色高亮。
- **M3 每接收器独立播放（2026-11-04 已实现，待游戏内验收）**：`AvReceiverPlayback` 为每个接收器持有独立 `CinemaPlayer`、视频纹理和 `CinemaAudioEngine`/OpenAL context；`CinemaAudioEngine.close()` 会释放 context。服务端删除“只允许一个接收器播放”的检查，第二个接收器不再被拒绝。音频线程使用 `EXTThreadLocalContext` 设置/清空自己的 context，避免破坏 Minecraft SoundEngine；待验证同时播放/暂停/seek/音量/静音独立，以及停止一个接收器不影响另一个。
- **M4 物理 5.1 路由（2026-11-04 已实现，待游戏内验收）**：`CinemaAudioEngine.setChannelLayout()` 支持每声道位置/增益；`AvReceiverPlayback` 把接收器配对扬声器的 FL/FR/FC/LFE/BL/BR 角色、方块位置、箱体/SPL 增益映射到六个 source。听者始终使用玩家实际耳朵位置和朝向，**已删除皇帝位/Seat 听者参考与声场校正**；玩家走出影院后空间定位仍按真实扬声器方向计算。未配对声道回退虚拟 5.1。接收器 GUI 显示 `physical N/6`；Speakers 页增加每只已连接扬声器的 `Level` 滑块（-12.0..+6.0 dB，服务端同步），超过可视行数时滚轮查看全部（同一角色允许多只扬声器）；主音量改用 dBFS（-60..+6，+6 为最大增益）。Seat 方块保留并改为可右键乘坐的普通座位，不参与音频。立体声源已自动上混为 5.1（FL/FR 原样、FC 中置、LFE 120 Hz 低通、BL/BR 差信号）；5.1 downmix、延迟/混响仍未实现。
- **未来规划：可变声道布局 / 天空声道（2026-11-05，未实现）**：规划 mono、stereo、2.1、3.1、5.1、7.1、5.1.2、5.1.4、7.1.2、7.1.4，以及天空/吸顶音响（同一种吸顶式扬声器，向下发声）。需要泛化固定六声道 `CinemaAudioEngine`/`AudioChunk`/`SpeakerRole`，新增 `SL/SR` 与 `TFL/TFR/TRL/TRR`，并实现布局 AUTO/显式选择、上混/下混、缺失声道虚拟回退。设计见 `docs/design/CHANNEL-LAYOUTS.md`；仍是规划，不改当前 5.1。
- **座椅视角平滑修复（2026-11-04 已实现，待游戏内验收）**：Minecraft `LocalPlayer#getViewYRot` 在乘坐载具时使用插值 head yaw；`LocalPlayerSeatMixin` 仅在乘坐 `SeatEntity` 时改为返回实时 body yaw，使鼠标视角恢复无平滑。其他载具不受影响。
- **屏幕单投影仪占用（2026-11-04 修复，待游戏内验收）**：`ScreenBlockEntity` 保存 `ownerProjector` 并同步到整个连通屏幕；配对第二个投影仪会被拒绝并提示先解除连接。拆除投影仪、解除连接或投影仪改配其他屏幕会释放屏幕；旧存档中的重复链接由投影仪每 20 tick 校验自动清理，只保留一个投影仪。
- **设备命名系统（2026-11-04 已实现，待游戏内验收）**：`DeviceNamePayload` 统一处理投影仪/AV 接收器/屏幕命名。投影仪 GUI 顶部有 `Projector` 和当前配对 `Screen` 名称框；屏幕名会写入所有连通屏幕格。AV 接收器 GUI 顶部有 `Receiver` 名称框；名称持久化/同步，并显示在投影仪 AV/屏幕状态行、接收器视频状态、`/cinema_projector[_client] info` 与 `/cinema_receiver info`。所有名称最长 32 字符，控制字符会被过滤。
- **扬声器命名/定位（2026-11-04 已实现，待游戏内验收）**：`SpeakerBlockEntity` 增加 `name`（最长 32 字符，NBT/网络同步）；Speaker GUI 顶部可直接编辑名称（debounce，Close/Esc flush），通过 `SpeakerNamePayload` C2S 保存。接收器 Speakers 列表优先显示自定义名称，未命名时显示角色/箱体/坐标；每行新增 `Ping` 按钮，在扬声器方块位置播放定位音并生成约 8 秒粒子光柱；悬停行显示完整名称/角色/坐标/距离/Level。列表支持滚轮和滚动条，同一角色允许多只扬声器。
- 多屏/多投影仪、LAN 同步、资源重载、两小时长片仍未完成。
- 旧 Git 历史仍含旧提交作者信息；工作树已清理。若要彻底隐私，需要重写历史并 force-push，或把仓库设为 private。
- 不要重新引入：test scene、visual spike、barrier 测试方块、阴影、镜头光晕、过热。

## 5. 下一步优先级

1. **投影仪 GUI M1/M2/M3（已通过游戏内验收）**
   - `ProjectorConfigScreen` 已替换临时 wash 滑块：M1 状态区显示屏幕尺寸/坐标/距离、灯泡 warming/on/cooling、AV 链接/状态/分辨率；不显示媒体、codec、丢帧或播放控制。
   - M2 编辑自动/手动投掷比、自动/手动焦距、垂直/水平梯形、亮度、wash、Power ON/OFF；**改动即保存**：按钮/Defaults 立即发送，滑块约 200 ms debounce，Close/Esc 前 flush；没有 Cancel/Done。
   - M3 四个物理模拟开关（Focus/Keystone/Wash/Warmup）保存在 block entity，切换即发送，关闭后 `ProjectorOptics`/灯泡路径跳过对应计算。
   - 已知观感问题：对焦模糊当前基于 mipmap/LOD，物理效果一般；后续可评估多级采样/卷积/景深，暂不阻塞。
   - 服务端 `ProjectorSettingsHandler` 校验距离/方块/有限值/范围，`ProjectorBlockEntity.applySettings` 一次性写入并同步；GUI `isPauseScreen=false`。
   - 关键文件：`ProjectorConfigScreen.java`、`ProjectorBlockEntity.java`、`ProjectorSettingsPayload.java`、`ProjectorSettingsHandler.java`、`ProjectorSettingsClient.java`、`ProjectorOptics.java`。
   - 下一步：配对工具游戏内验收，然后 M4 诊断（配对原因/遮挡/信号页）。
2. **M3 每接收器独立播放（已实现，待游戏内验收）**
   - 每个 `AvReceiverPlayback` 独立持有 `CinemaPlayer`、视频纹理、`CinemaAudioEngine`/OpenAL context；第二接收器不再被拒绝。
   - 停止/移除接收器时释放对应音频 context；继续观察 NVIDIA 视频上传崩溃、音频设备切换与多接收器 CPU/内存。
3. **剩余验收与 wash 修复**
   - Mod Menu 设置菜单与投影仪/接收器空手右键交互已通过验收（见 §4）
   - 屏幕特殊视角渲染修复已通过验收；剩余 64×64 配对/尺寸显示错误（见 §4）与默认 16 裁剪验收
   - 定位 BSL 下 wash 异常
   - 无音轨墙钟长播；记录 4K/HDR codec/hwaccel/丢帧回归数据
4. **媒体能力扩展**：音轨/视频轨/字幕选择与渲染、AV Receiver Video Processing、拖放导入媒体库
5. **M4 物理 5.1 路由（已实现，待游戏内验收）**：六只已配对扬声器按角色出声；每只扬声器可在接收器 GUI 调 `Level`；主音量 dBFS；听者使用玩家实际位置/朝向，不做皇帝位或声场校正；Seat 可乘坐但不参与音频。LAN、资源重载、长片测试、持续 GUI 打磨仍待做。
6. **音频计算/房间声学（M4 后）**
   - **分支**：所有声学模拟原型/实验代码在 `acoustic-simulation` 分支开发，避免影响 `main`；只有通过游戏内听感验收的部分才分段合并回 `main`。
   - 见 `docs/design/AUDIO-ACOUSTICS.md`；A1d Spinorama 数据 spike、A0 EFX/EAXReverb spike 可在 M2/M3 之后提前做。
   - 只做扬声器指向性，不做原始声源指向性；指向性用 Spinorama 实测水平/垂直指向性 + 轴上响应，映射到四类箱体；API 需要人工申请 key，转换工具只在本地使用。
   - 早期反射用一阶镜像源 + Spinorama 指向性；EAXReverb 只取晚期尾音，关闭其固定 5 m 早期反射。
   - 先做 Spinorama 许可决策（CC BY-NC-SA 4.0 vs 宽松数据/用户本地导入），再决定是否打包数据。
   - 不要为了声学阻塞 M4；EFX 不可用时回退到无混响或小型 FDN。

### 5.1 当前交接状态

1. 读 `docs/HANDOFF.md` → `docs/PLANNING.md` → `docs/TODO.md` → `docs/design/PROJECTOR-GUI.md`。
2. 用 `git log -1` / `git status` 确认最新提交与工作区；当前版本 `Alpha 0.0.1`，最新提交以 `git log -1` 为准。
3. 投影仪 GUI M1/M2/M3、Mod Menu 设置菜单、投影仪/AV 接收器/扬声器空手右键交互、配对工具（含接收器↔投影仪）均已通过游戏内验收；M3/M4 已实现，等待统一验收。本轮新增待验收：接收器 Speakers 页每扬声器 `Level`（-12..+6 dB）、滚动列表、自定义名称与 `Ping` 定位、主音量 dBFS（-60..+6）、删除皇帝位/声场校正（听者跟随玩家实际耳朵/朝向）、Seat 可右键乘坐。
4. Mod Menu 设置页已完成旧版流程验证，并已改为改动即保存 + Defaults/Close（已验收）。视频高度已移出 Mod Menu，归未来 AV Receiver `Video Processing`。AV 接收器手持物品右键不再打印状态到聊天栏。三项原版行为开关（设备 GUI 暂停、空手右键打开设备 GUI、spike 修改世界）已通过游戏内验收。
5. 64×64 配对/尺寸错误已登记（§4），定位前不要改相关渲染/配对代码。

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
/cinema_play <本地文件绝对路径>
/cinema_projector_client info
/cinema_projector_client wash 0.2
```

## 7. 关键文件

```text
src/main/java/com/example/cinema/Cinema.java
src/main/java/com/example/cinema/block/entity/ProjectorBlockEntity.java
src/main/java/com/example/cinema/block/entity/ScreenBlockEntity.java
src/main/java/com/example/cinema/command/ProjectorCommand.java

src/client/java/com/example/cinema/client/CinemaClient.java
src/client/java/com/example/cinema/client/gui/ProjectorConfigScreen.java
src/client/java/com/example/cinema/client/command/ClientHelpCommand.java
src/client/java/com/example/cinema/client/command/ClientProjectorCommand.java
src/client/java/com/example/cinema/client/media/          # FFmpeg/播放/音频/纹理
src/client/java/com/example/cinema/client/render/ProjectorOptics.java
src/client/java/com/example/cinema/client/render/ScreenBlockEntityRenderer.java
src/client/java/com/example/cinema/client/render/ScreenGeometry.java
```

## 8. 用户偏好 / 协作规则

- 用中文沟通。
- 复杂视觉效果由用户**在游戏内**验收，不接受只发截图/只看日志判断通过。
- 不重新加入 test scene / visual spike；测试场景由用户手动放置。
- 删除功能前要明确确认。
- 不在仓库文件里写个人绝对路径、姓名、邮箱、私人媒体文件名。
- 测试时先确保 Minecraft 窗口置前。
