# AV Receiver 设计与迁移

> 状态：设计草案；AV Receiver / Speaker 方块、block entity 与线材/遥控器物品已创建，播放管线尚未迁移。当前仍是全局 `CinemaPlayer` 直接驱动屏幕/OpenAL 的过渡架构。
> 相关文档：[项目规划](../PLANNING.md)、[待办](../TODO.md)、[投影仪 GUI](PROJECTOR-GUI.md)、[指令](../COMMANDS.md)。

## 1. 目标

把“媒体”从全局客户端播放器迁入 **AV Receiver 方块**，让影院信号链变成：

```text
AV Receiver（媒体与播放权威）
  ├─ 本地文件 → FFmpeg 解码 → 视频信号 → Projector（只做光学/灯泡/屏幕）
  └─ 本地文件 → FFmpeg 解码 → 5.1 音频 → Speaker（只做回放）
```

第一版范围：

- 接收器方块 + block entity，可放置、可拆除、可持久化。
- 服务端权威的播放状态：媒体标识、播放/暂停/停止、位置、音量。
- 客户端本地解码代理：每个客户端为自己的接收器实例解码本地文件。
- 视频通过接收器 → 投影仪 → 屏幕的显式链路输出。
- 音频先走现有的虚拟 5.1 输出，扬声器方块后续接入。
- 单人 + LAN；服务端不同步媒体文件本体，只同步媒体指纹和播放状态。

非目标（本阶段）：

- 服务端串流媒体、公网媒体服务器。
- 接收器 GUI 的完整文件浏览器（M2 再做）。
- 每接收器完全独立的音频设备/多接收器同时播放（M3）。
- 物理扬声器路由、房间校准、遥控器（后续阶段）。
- 4K/HDR 解码本身的改动；沿用现有 `VideoDecoder`/`StreamingAudioDecoder`。

## 2. 职责边界

| 单元 | 拥有 | 不拥有 |
|---|---|---|
| **AV Receiver** | 媒体标识、播放/暂停/seek、A/V 时钟、音量、视频/音频分发 | 光学、屏幕几何、扬声器物理位置 |
| **Projector** | 投掷比、焦距、梯形、亮度、wash、灯泡、屏幕配对 | 文件、解码、播放状态、codec 信息 |
| **Screen** | 投影面几何、逐块 UV/裁剪 | 播放器、媒体 |
| **Speaker** | 接收器分配的声道回放、位置/朝向/增益 | 文件、解码、播放状态 |
| **Seat** | 皇帝位/校准参考点 | 媒体与渲染 |
| **服务端** | 接收器状态、媒体指纹、权限/范围校验、持久化 | 解码、渲染、读取客户端文件 |
| **客户端** | 本地文件解析、FFmpeg 解码、纹理上传、OpenAL 输出 | 权威播放状态 |

关键澄清：**“接收器拥有媒体”是逻辑所有权，不等于服务端解码。**
服务端保存“哪个接收器、播放哪个媒体指纹、处于什么状态”；真正读取本地文件和解码的是每个客户端上的接收器播放代理。投影仪和扬声器永远不直接读文件。

## 3. 方块与数据模型

### 3.1 方块

```text
id: firstmod:av_receiver
class: AvReceiverBlock / AvReceiverBlockEntity
```

- 水平朝向可选；接收器不参与光学，朝向只影响模型和后续扬声器配对。
- 创造模式物品栏与屏幕/投影仪同组。
- 右键打开接收器 GUI（M2）；M1 先用客户端命令控制。
- 拆除时停止播放、清理配对、通知客户端释放纹理。

### 3.2 `AvReceiverBlockEntity` 字段

```text
mediaKey          String      媒体库内相对路径；空 = 未选择
mediaSize         long        文件大小，用于本地匹配
mediaSha256       String      完整 SHA-256；M1 可为空
durationSeconds   double      ffprobe 得到的时长；0 = 未知
state             enum        STOPPED / PLAYING / PAUSED
positionSeconds   double      服务端权威播放位置
volume            float       0..1
muted             boolean
videoTarget       BlockPos    配对的投影仪；空 = 无视频输出
stateGameTime     long        服务端写入状态时的 game time
```

服务端 tick：

- `PLAYING` 时 `positionSeconds += 1/20`，并按 `durationSeconds` 截断。
- 每 20 tick 或状态变化时 `setChanged()` + `sendBlockUpdated()`，让客户端通过原版 block entity 同步拿到最新状态。
- 到达结尾时停在 `durationSeconds`，保持 `PLAYING`（客户端保留最后一帧）；显式 `stop` 才清空。
- `STOPPED` 时位置归零，但保留 `mediaKey`，方便再次播放。

### 3.3 投影仪增加接收器配对

`ProjectorBlockEntity` 增加：

```text
receiverPos       Optional<BlockPos>
```

- 接收器放置时扫描前方/附近 32 格内最近的投影仪，写入双方的配对。
- 投影仪被拆除或接收器被拆除时清空对方引用。
- M1 用“最近投影仪”自动配对；显式配对工具（线材/对码器）后续替换。
- 屏幕配对保持现状：投影仪自动找前方屏幕。

## 4. 媒体标识与本地库

### 4.1 媒体库

第一版使用固定目录：

```text
<gameDir>/cinema/media/
开发环境：run/cinema/media/
```

- 接收器只保存**相对路径**（例如 `movies/demo.mp4`），不保存绝对路径。
- 服务端校验：非空、无 `..`、非绝对路径、分隔符规范化为 `/`。
- 客户端解析时把相对路径拼到媒体库根目录，并再次校验规范化后仍在根目录内。
- 绝对路径不进入服务端状态，避免 LAN 泄露个人目录，也避免其他客户端无法解析。

### 4.2 媒体指纹

```text
mediaKey + mediaSize + mediaSha256 + durationSeconds
```

- 选择文件时由客户端计算 `size`、`duration`（`ffprobe`）和 SHA-256。
- SHA-256 可能较慢：异步计算，先允许 `mediaSha256` 为空（M1 只校验大小）；M2 完成缓存与强制校验。
- 哈希缓存键：`(path, size, lastModified)`，避免每次播放都重新读整文件。
- 本地匹配失败时：屏幕保持黑屏，接收器 GUI/命令提示“缺少媒体”或“媒体指纹不匹配”，不自动播放同名但内容不同的文件。

### 4.3 选择流程

```text
玩家在客户端选择 cinema/media 下的文件
  → 客户端计算 MediaRef（相对路径/大小/时长/哈希）
  → C2S SelectMedia(pos, MediaRef)
  → 服务端校验范围/路径/数值
  → 写入 block entity，state=PLAYING，position=0
  → block entity 同步给附近客户端
  → 每个客户端解析本地文件并启动自己的解码代理
```

## 5. 播放状态机与时钟

### 5.1 状态

```text
STOPPED ──select/play──→ PLAYING ──pause──→ PAUSED
   ↑                        │  ↑              │
   └────────stop────────────┘  └──resume──────┘
```

- `seek` 在 `PLAYING`/`PAUSED` 都可用；更新 `positionSeconds`，客户端重启解码器到新位置。
- `select` 新文件等价于 stop + 设置 MediaRef + play。
- 音量/静音不改变播放状态。

### 5.2 服务端时钟

- 服务端只维护一个粗粒度权威位置，精度 1 tick。
- `stateGameTime` 用于客户端估算当前应有位置：

```text
expected = positionSeconds
         + (state == PLAYING ? (nowGameTime - stateGameTime) / 20.0 : 0)
```

- 服务端不参与音画同步；每个客户端的音频设备时钟仍是本机 A/V 主时钟。

### 5.3 客户端同步与漂移

- 收到状态后计算 `expected`。
- 本地播放位置与 `expected` 偏差超过阈值（建议 0.75 s）才 seek；小偏差不打断音频。
- 暂停/恢复/seek 后允许一次重新对齐；不要每帧比较。
- 客户端音频时钟继续使用 `CinemaAudioEngine.audiblePtsSeconds()`；视频帧选择逻辑不变。
- 新加入的客户端从 `expected` 开始解码，不从头播放。

## 6. 网络与权威

### 6.1 S2C：先用原版 block entity 同步

M1 不新增 S2C 包：

- 服务端每 20 tick / 状态变化时 `sendBlockUpdated`。
- `getUpdateTag` 包含 3.2 的全部字段；`loadAdditional` 在客户端恢复。
- 客户端每 10 tick 扫描附近已加载的接收器 block entity，把状态喂给播放代理。
- 优点：复用现有投影仪同步方式，LAN/专用服务器一致，不引入新包。
- 风险：接收器很多时 block update 频率高；M2 若测出问题，再换成专用 `ReceiverState` S2C 包（按接收器/距离订阅）。

### 6.2 C2S：控制包

新增 `ReceiverControlPayload`（Fabric `CustomPacketPayload`）：

```text
BlockPos receiverPos
Action action        SELECT_MEDIA / PLAY / PAUSE / STOP / SEEK / SET_VOLUME / LINK_PROJECTOR
String mediaKey
long mediaSize
String mediaSha256
double durationSeconds
double value         seek 秒 / 音量 0..1
```

服务端校验：

- 玩家与接收器距离 ≤ 16 格，维度一致。
- 方块仍是 `av_receiver`，block entity 类型正确。
- `mediaKey` 合法（见 4.1）；`mediaSize >= 0`；`durationSeconds >= 0`；`value` 按动作截断。
- 每玩家每 tick 最多处理 1 个控制包；GUI 拖动滑块只在 Apply 时发送。
- 拒绝时不下发状态；客户端保留/回滚本地草稿。

### 6.3 为什么 M1 不用聊天命令做 GUI 后端

正式 GUI 不应依赖 `sendCommand`。M1 的临时命令只是调试入口，底层仍走同一个 `ReceiverControlPayload` 和服务端 setter；M2 GUI 直接复用。

## 7. 视频路由

```text
AvReceiverBlockEntity.videoTarget
  → ProjectorBlockEntity.receiverPos
  → ProjectorOptics（现有屏幕配对/镜头）
  → ScreenBlockEntityRenderer
```

渲染器改动：

- 不再直接取 `CinemaPlayer.active()`。
- 由 `AvReceiverPlaybackManager.playbackFor(projector)` 返回该投影仪对应接收器的播放实例。
- 没有接收器、没有媒体、文件缺失、接收器停止 → 不绘制，屏幕保持黑屏。
- 每个播放实例有自己的 `ResourceLocation` 纹理；`CinemaPlayer.TEXTURE_ID` 从静态常量改为实例字段。
- `WorldRenderEvents.AFTER_ENTITIES` 从 `presentActiveFrame()` 改为遍历所有活跃播放实例 `presentFrames()`。
- 对焦模糊仍由投影仪把 `blurLod` 传给对应播放实例的纹理，不跨接收器共享。

## 8. 音频路由

M1：

- 仍使用现有 `CinemaAudioEngine` 的六路虚拟 5.1 source。
- 引擎由接收器播放代理持有/调度，而不是全局 `CinemaPlayer` 静态持有。
- 同一时间只允许一个接收器占用音频引擎；第二个接收器播放时给出明确错误（M3 再拆多引擎/多设备）。
- 音量/静音由接收器状态控制引擎总增益。

M2+：

- 接收器按角色把六个声道分配给已配对 Speaker 方块。
- 箱体类型决定可用角色：落地式/书架式用于 FL/FR/BL/BR，中置用于 FC，低音炮用于 LFE；不合法角色回退 NONE。
- 每个 Speaker 一个 mono source，位置/朝向来自方块；接收器只做路由。
- Speaker 不做解码，不直接读文件。
- 立体声 upmix / 5.1 downmix 写成纯函数并测试。

## 9. 客户端播放代理

```text
AvReceiverPlaybackManager
  ├─ Map<ReceiverKey, AvReceiverPlayback>
  ├─ 每 10 tick 从客户端 block entity 同步状态
  ├─ 解析本地媒体库文件
  ├─ 启动/暂停/停止/seek 本地 CinemaPlayer
  └─ 向渲染器提供 textureId/aspect/blur
```

`AvReceiverPlayback` 持有：

```text
receiverPos, mediaKey, state, expectedPosition,
duration, volume, muted, missing, CinemaPlayer player
```

`CinemaPlayer` 需要的改动：

- 纹理 ID 实例化（每播放实例唯一），关闭时释放自己的纹理。
- 增加 `setVolume(float)` / `setMuted(boolean)`。
- 音频引擎从静态 `sharedAudio` 改为由播放管理器传入的共享引擎（M1 单引擎）。
- 保留现有解码线程、有界队列、音频时钟、无音轨墙钟逻辑。

生命周期：

- 世界卸载/断线/客户端停止：关闭所有播放实例，释放纹理。
- 接收器停止/拆除：关闭对应实例。
- 媒体变化：关闭旧实例，按新 MediaRef 新建。
- 资源重载：M1 只保证不崩溃；完整恢复后续处理。

## 10. GUI 规格（M2 起）

定位：**功放前面板 + 设置菜单**，不是文件浏览器。主界面必须一眼回答：当前媒体、音量、视频/音频输出目标、异常状态。投影仪光学设置不进入本 GUI。

### 10.1 信息架构

顶部常驻状态栏，中间四个页签，底部常驻播放条：

```text
┌ Cinema AV Receiver ─────────────────────────────┐
│ [媒体] [扬声器] [信号] [设置]                    │
├─────────────────────────────────────────────────┤
│ ▶ 正在播放  movies/demo.mp4                     │
│   00:12:34 / 01:58:20   1080p · H.264 · AAC 5.1 │
│   视频: 投影仪 @ 12,64,-3 ✓   音频: 虚拟 5.1 ✓  │
├─────────────────────────────────────────────────┤
│ 本机媒体库  cinema/media/              [刷新]   │
│  📁 movies/                                     │
│  🎬 demo.mp4    1080p · 1:58:20 · H.264/AAC     │
│  🎬 test.mp4    4K HDR · 0:10:00 · HEVC/AAC     │
├─────────────────────────────────────────────────┤
│ [▶/⏸] [■]  [−10s] [====●====] [+10s]            │
│ 音量 [======●====] 80%  [静音]                  │
└─────────────────────────────────────────────────┘
```

- **媒体**：选片、媒体库、播放状态。
- **扬声器**：布局、角色、测试音、距离/电平/分频/混响（M4）。
- **信号**：codec、分辨率、丢帧、延迟、指纹等诊断。
- **设置**：媒体库位置、自动配对、默认音量、关于。

### 10.2 常驻状态栏与播放条

状态栏：

- 接收器名称/坐标（多接收器时区分）。
- 状态：停止/播放/暂停。
- 当前媒体相对 key、时间/总时长。
- 三个状态：视频输出（投影仪在线/离线）、音频输出（虚拟 5.1/实体扬声器）、媒体文件（本机可用/缺失/指纹不匹配）。
- 警告横幅：无投影仪、无音频设备、音频引擎被其他接收器占用、LAN 客户端缺文件。

播放条：

- 播放/暂停、停止、±10 秒、可拖动进度条。
- 拖动只改草稿，松开或 Apply 才发送 seek。
- 主音量 0..1 + 静音。
- 无媒体/停止/缺文件时按钮禁用并带 tooltip。

### 10.3 媒体页

- 标题“本机媒体库”；默认库为 `cinema/media/`，可添加外部文件夹库。
- 左上角 `Library` 按钮在默认库与外部库之间切换，只显示库内相对路径。
- 文件夹导航、搜索、刷新。
- 条目显示：分辨率、codec、时长、HDR/SDR、音轨格式；缺文件、指纹不匹配、无法解码的条目标红。
- 选中后显示详情与“播放”。
- SHA-256/时长异步计算，显示“扫描中…”，不阻塞 GUI。
- 不提供系统文件选择器；后续可支持拖入文件复制到媒体库，但首版不做。

### 10.4 扬声器页（M4）

- 5.1 布局图 + 已发现扬声器列表：角色、箱体类型、距离、电平、在线状态。
- 角色分配只显示合法项：中置仅 `FC`，低音炮仅 `LFE`，落地式/书架式仅 `NONE/FL/FR/BL/BR`。
- 单只/全部测试音。
- 每声道电平、距离/延迟、LFE 分频、聆听模式（5.1 直通/立体声下混/上混）。
- 房间混响（EFX 预设、wet/dry）。
- 未配对实体扬声器时明确显示“虚拟 5.1”。
- Seat 完成后提供“以皇帝位重新计算距离/延迟”。

### 10.5 信号/诊断页

- 媒体指纹、文件大小、校验结果。
- 视频：源分辨率、codec、fps、HDR 元数据、解码 fps、丢帧。
- 音频：采样率、声道数、缓冲延迟、音画偏差。
- 状态同步时间、LAN 角色、硬件解码/软件回退状态。
- 只做展示，不把解码参数做成主界面控件。

### 10.6 设置页

- 默认媒体库只读显示 `cinema/media/`。
- **外部媒体库**：输入文件夹绝对路径 → Add library；列表可选中/Remove library（只删注册，不删文件）。
- 自动配对投影仪开关。
- 默认音量/最大音量。
- 重新扫描设备。
- 关于/版本。
- LAN 控制权限（M5）。

### 10.7 明确不放入

- 对焦、梯形、投掷比、亮度、wash、灯泡、风扇（投影仪 GUI）。
- 屏幕配对/屏幕几何（投影仪管理）。
- 绝对路径、系统文件浏览器。
- 与扬声器右键循环重复的“下一角色”按钮；GUI 可分配，右键保留快捷循环。

### 10.8 实现约束

- 媒体库由客户端扫描；服务端不读客户端文件。默认库 key 为相对路径，外部库 key 为 `lib/<id>/<相对路径>`；绝对路径只留在客户端 `cinema/libraries.json`。选片时客户端上传 key、大小、SHA-256、时长；接收器只存元数据。
- GUI 控件走 `ReceiverControlPayload` C2S；服务端校验距离/方块/路径/数值。
- 媒体列表、哈希、时长不得在渲染线程同步执行。
- 支持 GUI scale 1..4、键盘操作、tooltip、禁用态；文本走 lang。
- Done 保存并关闭，Cancel/Esc 丢弃未提交的媒体/音量草稿。

## 11. 分阶段实现

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 | 本文设计、决策确认 | 文档评审通过 |
| M1 | 接收器方块/状态/控制包/客户端代理/视频路由/命令 | 单机放置接收器+投影仪+屏幕，能播放/暂停/seek/停止；拆接收器画面消失 |
| M2 | 接收器 GUI（媒体/扬声器/信号/设置）、媒体库列表、SHA-256/时长、缺文件 UI | GUI 内完成选片与播放；LAN 客户端同文件可播 |
| M3 | 每接收器独立播放实例/纹理/音频引擎 | 两个接收器可独立播放 |
| M4 | Speaker 方块、5.1 路由、Seat 皇帝位 | 六只扬声器按角色出声 |
| M5 | LAN 状态硬化、资源重载、遥控器、长片测试 | 专用服务器/两小时长片通过 |

M1 已完成并通过游戏内验收：

- 固定媒体库 `<game>/cinema/media/` + 相对 key；客户端异步计算 SHA-256/时长。
- `ReceiverControlPayload`（C2S）统一处理选片/播放/暂停/停止/seek/音量/静音/重连投影仪。
- 客户端 `AvReceiverPlaybackManager` 每 10 tick 同步附近接收器；视频经 `ProjectorOptics` 路由到屏幕，音频进入共享 OpenAL 5.1 引擎。
- 接收器每 20 tick 自动配对/重连 48 格内最近投影仪；`/cinema_link` 仅用于强制重扫。
- 单接收器占用音频引擎；第二个接收器在播放/暂停状态下会被拒绝，需先 `/cinema_stop`。
- 缺文件时屏幕黑屏、命令/日志提示，不崩溃；拆接收器/投影仪/屏幕均能停止或恢复。

M2 已完成并通过游戏内验收：

- 接收器 GUI：Media / Speakers / Signal / Settings 四页签；媒体列表、播放控制、音量/静音、诊断与设置。
- 外部媒体库：`cinema/libraries.json` 保存客户端本地库；外部库 key 为 `lib/<id>/<相对路径>`；Settings 可添加/移除/选择库，Media 页 `Library` 按钮切换。
- GUI 修复：长文本省略号、tooltip 自动换行、Settings 紧凑布局与库列表滚动、seek/音量滑块 pending、ESC 暂停同步、文字图层正确。
- 记住上次使用的库与文件夹（当前游戏会话内）。
- 4K HEVC 启动慢时不再被漂移检测反复重启；首帧显示后记录一次启动偏差继续播放。

## 12. 代码迁移步骤（M1）

1. 注册 `av_receiver` 方块/物品/block entity，加入创造模式物品栏。
2. `ProjectorBlockEntity` 增加 `receiverPos` 持久化/同步/清理。
3. 新增 `ReceiverControlPayload`（C2S）及服务端 handler；服务端 setter 统一做范围/路径校验。
4. `CinemaPlayer` 纹理 ID 实例化、音量/静音接口、音频引擎注入。
5. 新增客户端 `AvReceiverPlaybackManager` / `AvReceiverPlayback`，每 10 tick 扫描附近接收器。
6. `ScreenBlockEntityRenderer` 改为从播放管理器取对应接收器的播放实例。
7. `FirstModClient` 的 `/cinema_play` 等命令改为操作最近接收器；保留一个迁移期的兼容入口，M2 后删除。
8. 更新 `docs/COMMANDS.md`、`docs/TODO.md`、`docs/HANDOFF.md`，补充手动测试步骤。

## 13. 测试清单

- 单机：放置接收器/投影仪/屏幕，选择媒体，播放/暂停/恢复/seek/停止。
- 拆接收器：画面立即消失、音频停止；重新放置后可重新选片。
- 拆投影仪：接收器仍在播放，但无视频输出；重新配对后恢复。
- 拆屏幕：无画面；屏幕恢复后重新投影。
- 缺文件：屏幕黑屏，命令/GUI 提示；不崩溃。
- 指纹不匹配：拒绝播放或明确警告，不静默播放错误文件。
- LAN：主机选片，客户端有同文件时同步播放；客户端缺文件时提示。
- 两个接收器：M1 明确拒绝第二个音频占用；M3 再测独立播放。
- 资源重载、世界卸载、断线重连：无纹理/线程泄漏。
- 4K60/HDR：接收器链路不改变现有解码性能；记录丢帧。
- GUI scale 1/2/3/4、键盘操作、Esc/Done/Cancel 语义（M2）。
- 与 Sodium/Iris/BSL 同时运行不崩溃。

## 14. 待确认决策

1. **媒体库位置**：固定 `<gameDir>/cinema/media/`（推荐，简单且不泄露绝对路径），还是可配置目录？
2. **旧 `/cinema_play`**：M1 直接改为“选最近接收器 + 媒体库相对路径”（推荐，架构干净），还是保留绝对路径的临时旁路直到 M2？
3. **接收器↔投影仪配对**：M1 自动配最近投影仪（推荐，和屏幕自动配对一致），还是先做显式 `link` 命令/配对器？
4. **状态同步**：M1 用原版 block entity 更新（推荐，改动小），还是直接上专用 S2C 包？
5. **多接收器**：M1 只允许一个接收器占用音频引擎（推荐），还是从第一版就做每接收器独立引擎？

M1 采用推荐值：默认媒体库、相对 key、自动配对最近投影仪、原版 block entity 状态同步、单音频引擎。`/cinema_play` 已改为操作 64 格内最近接收器；GUI 与每接收器独立引擎留到 M2/M3。

M2 采用：默认库 `cinema/media/` + 客户端外部库；外部库 key 为 `lib/<id>/<相对路径>`，绝对路径仅保存在客户端 `cinema/libraries.json`，不进入方块实体或网络包。LAN 客户端需要配置同名外部库才能解析同一 key。
