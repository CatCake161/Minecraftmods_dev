# AV Receiver GUI 重构规划

> 状态：**规划，未实现**（2026-11-21）。
> 目标：把当前 420×300 的“四页签 + 文件浏览器”重构成接近现实 AV 接收机的**前面板 + 设置菜单**，并以 Home/Now Playing 作为默认页。
>
> 本文只写规划。重构期间**不改** `AvReceiverControlPayload`、服务端字段、媒体协议、声学算法和投影仪光学；先做纯 GUI 结构迁移，再调信息架构和视觉。

## 0. 结论先行

1. **默认页改为 Home / Now Playing**，不再一打开就是文件列表。Home 一眼回答：在放什么、音量、视频/音频输出是否正常、有没有警告。
2. **左侧竖向导航**（宽屏）→ 6 个一级页面：
   `Home` / `Media` / `Speakers` / `Sound` / `Information` / `Setup`。
   窄屏/小窗口退化为顶部页签或下拉，不强行保持左栏。
3. **常驻 header + 常驻 footer**：
   - header：接收器名称、输入源、播放状态、视频/音频/限幅状态、Close；
   - footer：seek、播放/暂停/停止/±10s、主音量、静音。
4. **Media 只负责选片**；媒体库管理（外部路径、Add/Remove）移到 Setup。
5. **Speakers 只负责“有哪些扬声器、角色、在线、Ping、打开扬声器 GUI”**；**Sound 负责“每声道 Level、输出模式、测试音（后续）”**。这是现实 AVR 的 Speaker Setup / Channel Level 分工。
6. **Information** 放当前 Signal 页的诊断；**Setup** 放媒体库、配对、默认值、About。
7. **声学设置不进入本 GUI**：A4 已取消，房间/反射/晚期/限幅继续在 Mod Menu。这里不出现 acoustic、early/late gain、房间预设、重扫。
8. **纯 GUI 重构**：不改任何 C2S payload、服务端校验、播放状态机、音频/视频路由。所有控件继续调用现有 client 方法。
9. **分阶段**：G1 只拆壳和页面（保持现有四页签，行为不变）→ G2 换 IA（Home + 六页）→ G3 响应式/滚动/键盘 → G4 视觉打磨。
10. 迁移期间保留 `AvReceiverConfigScreen(AvReceiverBlockEntity)` 和 `(receiver, parent)` 两个构造器，避免 `CinemaClient` / `RemotePairingScreen` / `RemoteDeviceScreen` 的调用点大改。
11. **现实 AVR 的 Setup Assistant、Quick Select/Scenes、OSD 偏好属于后续功能**：本次 G1–G4 只做结构/布局/现有功能迁移，并预留入口；不伪造没有后端的功能。详见 §2.1 和 §7。

## 1. 当前状态与问题

### 1.1 当前实现

`src/client/java/com/example/cinema/client/gui/AvReceiverConfigScreen.java`：

- 单文件约 1750 行，`Screen` 直接持有所有状态和控件。
- 固定面板 `min(width-12, 420) × min(height-12, 300)`。
- 顶部：接收器名称输入框 + 两行状态（状态/媒体/时间；视频/音频/限幅）。
- 中间：`Media / Speakers / Signal / Settings` 四个页签。
- 底部：常驻 seek + 播放/暂停/停止/±10s + 音量/静音 + Close。
- 每页内容用 `drawXxxTab()` + 在 `init()` 里按 `tab` 分支建控件；`rebuildWidgets()` 重建全部。
- Speakers 页同时包含：扬声器列表、Ping、Access、Level 滑块、滚动、tooltip。
- Settings 页同时包含：外部媒体库路径/Add/Remove、配对入口、库列表。
- Media 页占据内容区，是默认页；Home/Now Playing 不存在。

### 1.2 问题

| 问题 | 影响 |
|---|---|
| 默认页是文件浏览器 | 不符合功放“前面板”定位；播放时看不到关键状态 |
| 4 个页签职责混杂 | Media 既选片又管库；Speakers 既设备又电平；Settings 既库又配对 |
| 单文件 1750 行 | 改一个页面容易碰坏另一个；难以做响应式 |
| 固定 420×300 | 大屏浪费空间；小屏/GUI scale 4 容易拥挤；没有滚动容器抽象 |
| 状态信息只有两行纯文本 | 视频/音频/限幅异常不够醒目 |
| 没有 Home | 无法一眼判断“现在到底在放什么、输出到哪里” |
| 没有页面级滚动 | 长列表/小窗口只能压行高 |
| 文本硬编码 | 本地化/主题/键盘导航难做 |

### 1.3 不能破坏的现有行为

- 改动即保存；没有 Apply/Cancel；Close/Esc flush 正在拖动的 seek/volume/name。
- 媒体库扫描、哈希、探测不在渲染线程同步执行。
- 每个接收器分别记住库/文件夹/搜索词；多接收器互不影响。
- 扬声器列表以接收器链接表为准；未加载区块显示 `Unloaded` 并禁用编辑。
- `Ping` / `Access` 使用 `RemoteControlAccess` 规则。
- 音量 -60..+18 dBFS、Level -60..+12 dB、0.5 dB 步进、0 dB 吸附。
- 状态行/Signal 页的 limiter 指示与 `CinemaClientConfig.limiterEnabled()` 一致。
- 投影仪光学/屏幕配对不进入本 GUI。

## 2. 参考的现实 AVR 结构

现实 AV 接收机通常有三层：

1. **前面板/显示屏**：输入源、音量、声音模式、状态灯；不要求进菜单。
2. **Home / Now Playing**：当前输入、媒体、格式、输出、快速操作。
3. **Setup 菜单**：Speaker Setup、Inputs、Network、General、Information。

对应到本项目：

| 现实 AVR | 本项目 |
|---|---|
| Input selector（BD/DVD/Game/Media Player） | `Media` 页的媒体库/文件输入 |
| Now Playing / Status | `Home` |
| Speaker Setup（布局、距离、电平、测试音） | `Speakers`（设备/角色）+ `Sound`（电平/输出） |
| Information（信号格式、采样率、视频信息） | `Information` |
| Setup / General（网络、系统、语言、恢复） | `Setup` |
| Volume knob / Mute | 常驻 footer |
| Setup 里的 Audyssey/Dirac/房间校正 | **不放入**：本项目声学在 Mod Menu，A4 已取消 |

设计原则：

- 一级导航只放“用户会频繁切换的意图”，不按代码模块分。
- Home 只读 + 快捷操作；不在 Home 做文件浏览。
- 设置类操作集中在 Setup；设备类操作集中在 Speakers；电平类操作集中在 Sound。
- 常驻控件不要在每个页面重复实现。

### 2.1 现实菜单树与本项目映射

现实 AVR（Denon/Marantz/Yamaha 等）的典型结构是：

```text
Setup Assistant
Settings
  Audio
  Video
  Inputs
  Speakers
  Network
  General
Quick Select / Scenes
OSD 偏好
```

本项目没有 HDMI 输入、Audyssey/Dirac、网络流媒体、固件更新等后端，所以**不能照搬菜单项**；只照搬“分层 + 分类”的信息架构。映射如下：

| 现实 AVR | 本项目位置 | 本次 G1–G4 | 后续 |
|---|---|---|---|
| Setup Assistant（首次开机向导） | 首次打开接收器时可选的向导：语言、配对投影仪/扬声器、媒体库、基本音量 | 不做，只预留入口/文档 | G5：需要向导状态、步骤校验、可跳过；不伪造网络/HDMI |
| Settings > Audio | `Sound` 页（日常）+ `Setup > Audio`（输出模式、limiter 只读、声道电平入口） | 迁移现有 Level/输出状态 | 听音模式/测试音另立任务；**不放入房间声学** |
| Settings > Video | `Setup > Video`（只读投影仪链接；未来 Video Processing） | 只显示现有投影仪/视频状态 | `Video Processing` 是独立任务；投影仪光学仍不进本 GUI |
| Settings > Inputs | `Setup > Inputs`（媒体库、默认库、外部库；未来输入重命名/隐藏） | 迁移现有库管理 | 重命名/隐藏/输入电平需要新 UI 状态，另立任务 |
| Settings > Speakers | `Speakers` 页（设备/角色/Ping/Access）+ `Setup > Speakers`（距离/延迟/分频，后续） | 迁移设备/角色；Level 移到 Sound | 距离/延迟、LFE 分频、测试音需要音频后端，另立任务 |
| Settings > Network | `Setup > Network`（LAN/远程控制状态，只读） | 不放，避免空菜单 | LAN 状态同步/权限模型另立任务 |
| Settings > General | `Setup > General`（接收器名称、语言、About、OSD 偏好、Quick Select） | 名称/About 可迁移；语言/OSD/Quick Select 预留 | 语言需要 lang key；OSD 偏好影响 HUD/overlay，不属于本 GUI；Quick Select 见下 |
| Quick Select / Scenes | `Setup > General` 或 Home 快捷区 | 不做 | 需要保存“输入 + 聆听模式 + 音量”组合；新增字段/payload 后另立任务 |
| OSD 偏好 | 不由接收器 GUI 落地 | 不做 | 属于客户端 HUD/overlay 偏好，建议放 Mod Menu 或独立设置；不要伪造成接收器字段 |

原则：

- **有后端才做控件**。没有字段/网络包的功能只写进后续清单，不画一个点不动的按钮。
- **Setup 用 master-detail**：左侧/上方分类（Audio/Video/Inputs/Speakers/Network/General），右侧/下方是当前分类内容；不要把所有设置堆成一个长列表。
- **日常页是快捷入口，Setup 是深度配置**。例如 Sound 页调 Level，Setup > Speakers 放距离/延迟（未来）；Speakers 页管设备和角色。
- **Quick Select/Scenes 不是纯 GUI**：它要保存组合状态、同步、冲突处理；必须先有数据模型，不能只做按钮。

## 3. 目标信息架构

### 3.1 一级页面

| 页面 | 回答的问题 | 主要控件 | 不放什么 |
|---|---|---|---|
| **Home** | 现在在放什么？输出正常吗？ | 媒体名/状态/时间、视频/音频/媒体/限幅状态、Play/Pause/Stop、Open Media、Mute、警告 | 文件列表、库路径、扬声器 Level、声学参数 |
| **Media** | 我要放哪个文件？ | 库切换、面包屑、搜索、刷新、文件列表、详情、Play | 外部库 Add/Remove、配对、系统设置 |
| **Speakers** | 我有哪些扬声器？角色对吗？ | 已连接列表、角色/类型/坐标/在线、Ping、Access（打开扬声器 GUI）、布局摘要 | 每声道 Level、测试音、声学参数 |
| **Sound** | 每个声道电平对吗？输出是什么模式？ | 每扬声器 Level 滑块、输出模式（物理 5.1 / 立体声下混）、路由摘要、测试音（后续） | 扬声器角色分配、Ping、库、声学参数 |
| **Information** | 信号/文件/同步有什么问题？ | 媒体指纹/大小、视频 codec/分辨率/丢帧、音频格式、limiter、source 计数、投影仪/同步 | 可编辑设置、解码参数主控件 |
| **Setup** | 库、配对、默认值、关于在哪里？ | master-detail 分类：Inputs / Audio / Video / Speakers / General（Network 仅后续）；现有库管理/配对/About 先落在 Inputs/General | 文件播放、扬声器 Level 主控件、声学参数 |

### 3.2 页面切换与默认值

- 首次打开默认 `Home`。
- 上次打开的页面**不跨会话记忆**；每个接收器只记媒体浏览状态（库/文件夹/搜索/滚动）。
- 从 Home 的 `Open Media` 跳到 `Media`；从 Media 播放后停留/或回到 Home？建议播放成功后自动回 Home（可选，需你确认）。默认先**不自动跳**，避免打断连续选片。
- 从 Speakers 的 `Access` 打开扬声器 GUI，返回时回到 Speakers。
- 从 Setup 的 `Pair devices...` 打开 `RemotePairingScreen`，返回时回到 Setup。
- Close/Esc 行为与当前一致。

### 3.3 页面内的层级

- 一级导航始终可见（宽屏左栏/窄屏顶部）。
- 日常页（Home/Media/Speakers/Sound/Information）用标题 + 分组 + 列表/滚动，不再套第二套页签。
- **Setup 用 master-detail**：左侧/上方分类列表，右侧/下方是当前分类内容。分类为 Inputs / Audio / Video / Speakers / General；Network 只在有 LAN 状态后出现，避免空菜单。
- Sound 的“Level / 输出模式”用分组标题；测试音后续再加。
- Quick Select / Scenes 只作为 Home 或 Setup > General 的后续入口，不在 G1–G4 伪造按钮。

### 3.4 Setup 分类

| 分类 | 本次 G1–G4 放什么 | 后续 |
|---|---|---|
| **Inputs** | 默认库只读、外部库路径/Add/Remove/列表、当前库 | 输入重命名/隐藏/输入电平 |
| **Audio** | 输出模式摘要、limiter 只读、Sound 页快捷入口 | 聆听模式、测试音、分频（需后端） |
| **Video** | 投影仪链接/在线状态只读 | `Video Processing`（独立任务）；投影仪光学仍在投影仪 GUI |
| **Speakers** | 当前布局/链接摘要、Speakers 页快捷入口 | 距离/延迟、LFE 分频、测试音 |
| **General** | 接收器名称、配对入口、About/版本、诊断入口 | 语言、OSD 偏好、Quick Select/Scenes、固件（无后端） |
| **Network** | 暂不显示 | LAN 状态/权限/远程控制状态；有数据后再加 |

- Setup 只负责“深度配置/系统项”，不重复 Home 的播放控制。
- `Pair devices...` 从 Setup > General 或 Devices 分组打开，返回时回 Setup。
- 媒体库的 Add/Remove 固定在 Setup > Inputs；Media 页只切换库和选片。
- 声学设置不进入 Setup；A4 已取消，继续在 Mod Menu。

## 4. 布局与响应式

### 4.1 目标尺寸

- 正常：`panelWidth = min(screenWidth - 12, 720)`，`panelHeight = min(screenHeight - 12, 460)`。
- 最小：约 `308 × 168`（GUI scale 4 + 1280×720），必须可用，允许滚动。
- 不强制固定 720×460；窗口小于目标时按可用空间收缩。

### 4.2 断点

以面板实际像素（GUI scaled）判断：

```text
compact = panelWidth < 560 || panelHeight < 320
```

| 区域 | 正常 | compact |
|---|---|---|
| 导航 | 左侧竖栏 128 px | 顶部横向页签（可滚动/缩写） |
| header | 44 px，两行状态 | 30–34 px，一行 + tooltip |
| footer | 58 px，两行（seek / transport+volume） | 46 px，两行紧凑或 seek 与 transport 合并 |
| 内容 | 左栏右侧，页面内滚动 | 全宽，页面内滚动 |
| 字号/行高 | 默认 | 不缩小字体，减少行数 + 滚动 |

### 4.3 常驻 header

```text
┌──────────────────────────────────────────────────────────────┐
│ Cinema AV Receiver     [Receiver name............]   [Close] │
│ INPUT Media · ● Playing  movies/demo.mp4  00:12:34/01:58:20  │
│ Video: Projector @ 12,64,-3 ✓   Audio: 5.1 5/6 ✓   Limiter — │
└──────────────────────────────────────────────────────────────┘
```

- 第一行：标题、名称输入框、Close。
- 第二/三行：输入源、状态、媒体名、时间；视频/音频/媒体/限幅状态。
- 异常用颜色/短标签：`No projector`、`No speakers`、`Media missing`、`Limiter bypass/-x.x dB`。
- compact：只保留“状态 + 媒体名 + 一个聚合状态图标/文字”，其余进 tooltip。
- 名称输入框仍 debounce 保存；不要因为重构丢失 `flushReceiverName()`。

### 4.4 常驻 footer

```text
┌──────────────────────────────────────────────────────────────┐
│ [============== seek ==============]  00:12:34 / 01:58:20    │
│ [▶/⏸] [■] [-10s] [+10s]   音量 [======●======] -12.0 dBFS [Mute] │
└──────────────────────────────────────────────────────────────┘
```

- seek 拖动结束 debounce；Close/Esc flush。
- 播放/暂停/停止/±10s 禁用态与 tooltip 保留。
- 音量 0.5 dB 步进、0 dB 吸附、方向键调整。
- compact：seek 一行；transport 与音量分两行或音量进弹出层（第一版先分两行，避免新交互）。
- Mute 状态与音量文本一致。

### 4.5 宽屏 wireframe

```text
┌ Cinema AV Receiver ────────────────────────────────────────── [Close] ┐
│ Name [Living Room Receiver......]  INPUT Media  ● Playing  Limiter —  │
│ movies/demo.mp4   00:12:34 / 01:58:20   1080p · H.264 · AAC 5.1      │
│ Video: Projector ✓   Audio: 5.1 physical 5/6 ✓   Media: present ✓     │
├──────────┬────────────────────────────────────────────────────────────┤
│ Home   ● │  NOW PLAYING                                               │
│ Media    │  movies/demo.mp4                                           │
│ Speakers │  00:12:34 / 01:58:20                                       │
│ Sound    │  1080p · H.264 · AAC 5.1                                   │
│ Info     │                                                            │
│ Setup    │  Video  Projector @ 12,64,-3  online                        │
│          │  Audio  5.1 physical 5/6 (6 speakers)                      │
│          │  Media  present · fingerprint q1:...                       │
│          │                                                            │
│          │  [Play/Pause] [Stop] [Open Media] [Mute]                   │
│          │                                                            │
│          │  Warnings: (none)                                          │
├──────────┴────────────────────────────────────────────────────────────┤
│ [================ seek =================]  00:12:34 / 01:58:20       │
│ [▶/⏸] [■] [-10s] [+10s]   音量 [======●======] -12.0 dBFS [Mute]     │
└───────────────────────────────────────────────────────────────────────┘
```

### 4.6 compact wireframe

```text
┌ Cinema AV Receiver ─────────────────────── [Close] ┐
│ ● Playing · movies/demo.mp4 · 00:12:34 / 01:58:20  │
│ Video ✓  Audio 5/6 ✓  Limiter —                    │
├────────────────────────────────────────────────────┤
│ [Home] [Media] [Speakers] [Sound] [Info] [Setup]   │
├────────────────────────────────────────────────────┤
│ NOW PLAYING                                        │
│ movies/demo.mp4                                    │
│ 1080p · H.264 · AAC 5.1                            │
│                                                    │
│ [Play/Pause] [Stop] [Open Media]                   │
│                                                    │
│ (page scroll)                                      │
├────────────────────────────────────────────────────┤
│ [============ seek ============]                   │
│ [▶][■][-10][+10] 音量 [====●====] [Mute]           │
└────────────────────────────────────────────────────┘
```

## 5. 组件与代码结构

### 5.1 目标包结构

```text
client/gui/AvReceiverConfigScreen.java        // 壳：构造器、生命周期、widget 注册、渲染顺序
client/gui/receiver/
  ReceiverLayout.java                         // 面板/header/nav/content/footer 尺寸与断点
  ReceiverPage.java                           // 页面接口
  ReceiverPageContext.java                    // 页面可用的 shell 能力（add/remove widget、layout、receiver）
  ReceiverGuiState.java                       // 每接收器 UI 状态（当前页、媒体浏览、滚动、pending）
  ReceiverHeader.java                         // 名称、状态、警告
  ReceiverNav.java                            // 左栏/顶部页签
  ReceiverTransport.java                      // seek/transport/volume/mute
  pages/
    HomePage.java
    MediaPage.java
    SpeakersPage.java
    SoundPage.java
    InformationPage.java
    SetupPage.java
  widgets/
    ScrollView.java
    LevelSlider.java
    StatusChip.java
    TruncatedLabel.java
```

说明：

- `AvReceiverConfigScreen` 保留在 `client.gui`，避免改 `CinemaClient` / `RemotePairingScreen` / `RemoteDeviceScreen` 的 import；页面/组件放子包。
- `ReceiverPage` 不继承 `Screen`；它只负责页面内容，shell 负责 `Screen` 生命周期。
- 所有网络/媒体/扬声器操作继续调用现有 client 类；页面不直接发包。

### 5.2 `ReceiverPage` 接口

```java
interface ReceiverPage {
    String id();
    Component title();

    void init(ReceiverPageContext context);
    void tick();
    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);
    void renderOverlay(GuiGraphics graphics, int mouseX, int mouseY);
    default void onClose() {}
    default boolean mouseScrolled(double mouseX, double mouseY,
                                  double scrollX, double scrollY) {
        return false;
    }
}
```

- `init` 通过 `context.addWidget(...)` 注册页面控件；`context` 记录这些控件，切页时统一 `removeWidget`。
- `tick` 只做轻量同步（扬声器行、状态文本），不在 render 里扫描区块/读文件。
- `render` 画页面背景/列表/文本；`renderOverlay` 画 tooltip，保证在控件之后。
- `mouseScrolled` 用于页面滚动；shell 先给页面，再给全局。

### 5.3 `ReceiverPageContext`

至少提供：

```java
AvReceiverBlockEntity receiver();
@Nullable AvReceiverPlayback playback();
Minecraft minecraft();
Font font();
ReceiverLayout layout();
ReceiverGuiState state();
void addWidget(AbstractWidget widget);
void removeWidget(AbstractWidget widget);
void setStatus(String message);      // 复用现有 status 行
void openMediaPage();
void openSpeakersPage();
```

页面不直接操作 `Screen` 的 widget 列表，避免切页泄漏。

### 5.4 `ReceiverGuiState`

每个接收器一份（沿用当前 `rememberedMedia` 的思路）：

- `Page currentPage`（默认 Home）
- `MediaState media`（libraryId/folder/search/scroll/selectedKey）
- `int speakerScroll`
- `int libraryScroll`
- `String libraryPathDraft`
- pending seek/volume/name 状态仍留在 shell/transport（跨页面常驻）
- 不把 `receiver` 本体复制进 state；通过 context 读取最新 block entity。

### 5.5 控件生命周期

当前 `init()`/`rebuildWidgets()` 会重建全部控件；重构后：

1. `AvReceiverConfigScreen.init()`：
   - 建 `ReceiverLayout`；
   - 建 shell 控件（header/nav/footer）；
   - 调 `currentPage.init(context)`。
2. 切页：
   - `page.onClose()`；
   - `context.removePageWidgets()`；
   - 更新 `state.currentPage`；
   - `page.init(context)`；
   - 不重建 header/footer，除非布局变化。
3. `rebuildWidgets()`（resize/GUI scale 变化）：
   - `clearWidgets()`；
   - 重加 shell + 当前页；
   - 保留 state。
4. 页面内部列表变化（例如扬声器上线/下线）：
   - 只有行 key 变化时才重建该页控件；拖动 Level 时不重建（沿用当前 `hasDraggingSpeakerLevel()` 思路）。

### 5.6 渲染顺序

```text
renderBackground:
  - 半透明变暗
  - 面板边框/背景
  - header 背景/文本
  - nav 背景/选中态
  - 页面背景/列表/滚动条

render:
  - super.render(...)（控件）
  - nav 选中强调线
  - 页面 overlay/tooltip
```

- 面板/文本不要画在 `render` 里再被控件盖住；沿用当前“背景在 renderBackground”的做法。
- 滚动列表用 scissor，滚动条单独画。
- tooltip 最后画，避免被控件遮住。

## 6. 页面内容细节

### 6.1 Home

布局：

```text
NOW PLAYING
<media name or "No media selected">
<state> · <time / duration>
<metadata line: resolution · codec · audio>

[Play/Pause] [Stop] [Open Media] [Mute]

OUTPUT
Video   Projector @ ...  online/offline/audio only
Audio   5.1 physical 5/6 (N speakers) / no physical speakers / idle
Media   present / missing / no file
Limiter enabled -x.x dB / bypass

WARNINGS
- none
- / list of warnings
```

- 只读状态 + 快捷操作。
- `Open Media` 切到 Media 页。
- 媒体缺失/无投影仪/无扬声器/引擎失败显示警告。
- 不在 Home 放文件列表、库路径、Level 滑块。
- 进度条可复用 footer 的 seek 只读显示，或 Home 只显示时间文本；避免两个可拖动 seek。第一版 Home 只显示时间文本。

### 6.2 Media

保留当前 Media 页的功能，但重新分组：

```text
INPUT · MEDIA LIBRARY
[Library: Default ▼]  /movies/            [Refresh]
[Search........................]           [Up]

[DIR]  ...
demo.mp4   1080p · 1:58:20 · H.264/AAC
...

<selected detail>
[Play file]
```

- `Library` 按钮继续在默认库/外部库之间切换。
- 外部库的 Add/Remove 移到 Setup；Media 只选择和浏览。
- 文件夹导航、搜索、刷新、滚动、缺文件红色、扫描中提示保留。
- 选中后详情 + Play；`Play file` 禁用逻辑保留。
- 播放后是否自动回 Home，待确认；默认不跳。

### 6.3 Speakers

```text
SPEAKERS
Linked 6 · routing physical 5/6 (6 spk) · detected 7

<layout summary or simple 5.1 diagram (optional phase G4)>

Name / Role / Type / Position / Status     [Ping] [Access]
...
```

- 列表直接来自 `receiver.speakerTargets()`；未加载显示 `Unloaded` 并禁用。
- `Ping` 播放定位音 + 粒子光柱。
- `Access` 打开 `SpeakerDataConfigScreen`，用于名称/角色/speaker data。
- 不在本页放 Level 滑块（移到 Sound）。
- 行 tooltip 保留：名称、角色、箱体、坐标、距离、当前 Level。
- 滚动条/滚轮保留。
- 布局图是可选的 G4；第一版先用文本摘要，避免拖慢重构。

### 6.4 Sound

```text
SOUND
Output: 5.1 physical 5/6 (6 speakers) / stereo downmix / no speakers
Source: AAC 5.1 · 48 kHz

CHANNEL LEVELS
FL  Speaker name        [====●====] -2.0 dB
FR  ...
...
```

- 每只已加载扬声器一个 `Level` 滑块；同一声道多只扬声器各自一行。
- 范围 -60..+12 dB、0.5 dB 步进、0 dB 吸附、方向键调整、立即保存/debounce 与当前一致。
- 未加载行禁用。
- 输出模式只读：物理 5.1 / 立体声下混（若播放引擎报告）/ no physical speakers。
- 测试音（单只/全部）是后续功能；G4 可加按钮但必须明确是后续，不要伪造功能。第一版可以不放按钮。
- **不**放房间声学、反射阶数、late floor、limiter、投影仪设置。
- 如果用户希望 Level 继续留在 Speakers 页，也可以保留一份快捷入口；但建议单一归属，避免两处不同步。

### 6.5 Information

保留当前 Signal 页内容，按组排版：

```text
INFORMATION
MEDIA       key, duration, size, fingerprint
VIDEO       source resolution, codec, fps, decoded fps, dropped
AUDIO       sample rate, channels, buffer latency, A/V offset
OUTPUT      limiter, direct/reflection sources, projector link
SYNC        receiver state, client poll, LAN role
```

- 只读。
- 长 key/指纹截断 + tooltip。
- 缺文件/指纹不匹配用红色。
- 不把解码参数做成主界面控件。

### 6.6 Setup（master-detail）

```text
SETUP
[Inputs] [Audio] [Video] [Speakers] [General]     <- 分类列表
┌─────────────────────────────────────────────────┐
│ Inputs                                          │
│ Default: cinema/media/ (read-only)              │
│ External folder [......................] [Add]  │
│ [Remove library]                                │
│ Libraries:                                      │
│   > Default [default]                           │
│     My Movies [external]                        │
└─────────────────────────────────────────────────┘
```

分类内容：

- **Inputs**：当前 Settings 页的库管理整体搬到这里；Media 页只切换库和选片。
- **Audio**：输出模式摘要、limiter 只读、Sound 页快捷入口；不出现房间声学/反射/floor。
- **Video**：投影仪链接/在线状态只读；`Video Processing` 独立任务，投影仪光学仍不进本 GUI。
- **Speakers**：当前布局/链接摘要、Speakers 页快捷入口；距离/延迟/分频后续，需要音频后端。
- **General**：接收器名称、配对状态 + `Pair devices...`、About/版本、诊断入口；默认音量目前没有后端，先只显示当前值或留空。
- **Network**：暂不显示；等 LAN 状态/权限模型有数据后再加，避免空菜单。

交互：

- 分类列表在宽屏放左侧，窄屏放顶部横向；右侧内容可滚动。
- 切换分类不改变接收器状态；`Pair devices...` 返回时回到 Setup 的 General。
- Setup 不重复 Home 的播放控制，也不放扬声器 Level 主控件。
- 不把投影仪光学设置放进来。

### 6.7 Quick Select / Scenes（后续）

现实 AVR 的 Quick Select/Scene 会把“输入 + 聆听模式 + 音量”等组合保存为一键调用。本项目要做，至少需要：

- 接收器侧持久化字段：场景名、输入/媒体库、目标音量、可选监听模式；
- C2S 设置包/服务端校验，或明确仅客户端本地场景；
- 场景与当前播放/配对状态冲突时的规则（例如目标媒体缺失、扬声器离线、投影仪未配对）；
- GUI：Home 快捷区 + Setup > General 管理；不能只做“点了没反应”的按钮。

本次 G1–G4 不做；需要另立任务，先定数据归属（接收器还是客户端）。

### 6.8 Setup Assistant / OSD（后续）

- **Setup Assistant**：首次打开时的可选向导，步骤建议为“语言 → 配对投影仪/屏幕 → 配对扬声器 → 选择媒体库 → 测试播放”。需要向导状态、可跳过、断点恢复；没有网络/HDMI 步骤。
- **OSD 偏好**：控制音量变化/输入切换等提示是否短暂显示、是否遮挡画面。它影响 HUD/overlay，不属于接收器 GUI 内部布局；建议放 Mod Menu 或独立客户端设置，不要伪造成接收器字段。
- 两者都只预留入口/文档，不在 G1–G4 实现。

## 7. 分阶段实施

### G1：壳与页面拆分（行为不变）

目标：把 1750 行拆成 shell + 4 个现有页面，**保持当前四页签、420×300、控件位置和行为**。

- 新增 `ReceiverLayout`、`ReceiverPage`、`ReceiverPageContext`、`ReceiverGuiState`。
- `AvReceiverConfigScreen` 只保留：
  - 构造器/字段/`init`/`tick`/`renderBackground`/`render`/`onClose`/`resize`；
  - 名称保存、warning、status、playback 访问；
  - 当前页调度。
- 把 `Media / Speakers / Signal / Settings` 的内容移到 `MediaPage` / `SpeakersPage` / `InformationPage` / `SetupPage`（G1 可先叫 Signal/Settings，G2 改名）。
- 所有 C2S/客户端调用原样保留。
- 验收：与重构前逐项功能一致；`check` 通过；无控件丢失/重复。

> G1 不做视觉大改，便于定位回归。G1 完成后可以单独提交/回滚。

### G2：信息架构切换

- 新增 `HomePage`，默认页改为 Home。
- 一级导航改为 6 页：Home / Media / Speakers / Sound / Information / Setup。
- 将 Level 滑块从 Speakers 移到 Sound；Speakers 保留设备/Ping/Access。
- 将外部库 Add/Remove 从 Media 移到 Setup；Media 保留库切换/浏览/Play。
- 把当前 Signal 改名为 Information；Settings 改名为 Setup。
- 更新标题/标签；暂不做大视觉。
- 验收：所有旧功能仍可达；默认 Home；导航/返回路径正确。

### G3：响应式与可用性

- `ReceiverLayout` 实现 normal/compact 断点、左栏/顶部页签、页面滚动。
- header/footer 常驻；小窗口下内容可滚动，不压行高。
- `ScrollView` 统一列表/页面滚动；鼠标滚轮、滚动条、PageUp/PageDown。
- 键盘：Tab 焦点、Enter 激活、Esc/Close；滑块方向键。
- GUI scale 1..4 回归。
- 文本截断 + tooltip；长名称/路径/指纹。
- 验收：1280×720 scale 4 最小面板可用；1920×1080 scale 2/3 不空旷；无重叠/裁剪。

### G4：视觉打磨（可选/后续）

- 状态 chip/颜色：视频、音频、媒体、limiter、警告。
- Home 的 Now Playing 卡片/输出卡片。
- Speakers 简单 5.1 布局图。
- Sound 测试音（单只/全部）——需要音频引擎支持，不要只做按钮。
- 最近播放/收藏（需要持久化，另立任务）。
- 本地化 key（`en_us`/`zh_cn`）与主题。
- 动画/过渡（谨慎，Minecraft GUI 不需要复杂动画）。

### G5：Setup Assistant（后续，独立任务）

- 首次打开可选向导：语言 → 投影仪/屏幕配对 → 扬声器配对 → 媒体库 → 测试播放。
- 可跳过、可断点恢复；步骤完成状态需要持久化。
- 本项目没有 HDMI/网络流媒体，不照搬这些步骤。
- 不阻塞 G1–G4；没有向导状态机前不要做假向导。

### G6：Quick Select / Scenes + OSD（后续，独立任务）

- 场景保存“输入/媒体库 + 目标音量 + 可选聆听模式”；需要接收器字段/C2S 包或明确客户端本地。
- OSD 偏好（音量/输入提示短暂或常驻）影响 HUD/overlay，建议放 Mod Menu 或独立客户端设置。
- 先定数据归属和冲突规则，再画 UI；不在 G1–G4 做空按钮。

## 8. 迁移步骤

1. **冻结行为基线**：记录当前 GUI 的功能清单和手动验收步骤（见 §10）。
2. **G1 拆分**：
   - 新增 `ReceiverLayout`/`ReceiverPage`/`ReceiverPageContext`/`ReceiverGuiState`；
   - 把四个 `drawXxxTab` + 对应的 `init` 分支移到页面类；
   - shell 只负责布局、导航、footer、名称、状态；
   - 保留 `Tab` 枚举或替换为 `PageId`，但 G1 不改默认页。
3. **G1 回归**：所有控件、tooltip、滚动、debounce、Close flush、多接收器。
4. **G2 IA**：
   - 新增 Home；
   - 六页导航；
   - Setup 改成 master-detail 分类（Inputs/Audio/Video/Speakers/General）；
   - 移动 Level 和库管理；
   - 默认 Home；
   - 更新构造器/父屏幕返回逻辑。
5. **G3 响应式**：布局断点、滚动容器、键盘、scale 1..4。
6. **G4 打磨**：状态 chip、布局图、测试音、本地化。
7. **G5/G6 独立任务**：Setup Assistant、Quick Select/Scenes、OSD；先定数据模型，不混入 G1–G4。
8. 每个阶段更新 `docs/design/AV-RECEIVER.md` §10、`TODO.md`、`HANDOFF.md` 的相关描述。

### 8.1 代码迁移注意

- `AvReceiverConfigScreen` 的两个构造器保留：
  - `AvReceiverConfigScreen(AvReceiverBlockEntity receiver)`
  - `AvReceiverConfigScreen(AvReceiverBlockEntity receiver, Screen parent)`
- `RemotePairingScreen` / `RemoteDeviceScreen` 返回父屏幕的逻辑不变。
- `CinemaClient` 的右键打开入口不变。
- `init()` 里当前 `if (tab == Tab.MEDIA) ... else if ...` 的分支全部移到页面 `init`。
- 当前 `syncWidgets()` 同时更新 seek/volume/name/扬声器/播放按钮；拆成：
  - shell `syncPersistentWidgets()`：seek/volume/name/mute/transport；
  - 页面 `tick()`：页面自己的状态。
- 当前 `refreshSpeakers()`/`addSpeakerLevelWidgets()` 拆成：
  - Speakers 页：`refreshSpeakerRows()` + Ping/Access；
  - Sound 页：`refreshLevelRows()` + Level 滑块。
  - 两页都读同一个 `receiver.speakerTargets()`，不要各自维护不同步的副本。
- `drawStatus()` 拆成 `ReceiverHeader`，但状态文案/limiter 逻辑保持。
- `drawHoverTooltip()` 按页面分派；Shell 只负责 header/nav/footer 的 tooltip。
- 不要在页面 `render` 里做区块扫描、文件 IO、哈希、FFmpeg 探测。
- 页面切换时释放页面控件，避免 `Screen` 的 `renderables` 泄漏/重复响应。

## 9. 测试与验收

### 9.1 功能回归矩阵

| 功能 | G1 | G2 | G3 |
|---|---|---|---|
| 右键打开接收器 GUI | ✓ | ✓ | ✓ |
| 名称编辑/debounce/Close flush | ✓ | ✓ | ✓ |
| 媒体库切换/文件夹/搜索/刷新 | ✓ | ✓ | ✓ |
| 选中/详情/Play/缺文件提示 | ✓ | ✓ | ✓ |
| 外部库 Add/Remove/列表滚动 | ✓ | 移到 Setup | ✓ |
| 播放/暂停/停止/±10s/seek | ✓ | ✓ | ✓ |
| 音量/静音/0.5 dB/吸附 | ✓ | ✓ | ✓ |
| 扬声器列表/Ping/Access | ✓ | ✓ | ✓ |
| 每扬声器 Level | ✓ | 移到 Sound | ✓ |
| Limiter 状态/ceiling/bypass | ✓ | ✓ | ✓ |
| Signal/Information 诊断 | ✓ | 改名 | ✓ |
| 配对入口/返回 | ✓ | ✓ | ✓ |
| 多接收器独立状态 | ✓ | ✓ | ✓ |
| 媒体浏览状态按接收器记忆 | ✓ | ✓ | ✓ |

### 9.2 布局/可用性

- GUI scale 1 / 2 / 3 / 4：
  - 1280×720 scale 4（约 320×180）可打开、可滚动、可操作；
  - 1920×1080 scale 2/3 不出现大片空白或控件越界；
  - 2560×1440 正常。
- 长接收器名、长媒体 key、长库路径、长扬声器名：截断 + tooltip。
- 中文/英文文本不溢出按钮；如果做本地化，两种语言都测。
- 键盘：Tab 顺序、Enter、Esc、方向键；不要与 Minecraft 默认按键冲突。
- tooltip 不遮住正在拖动的滑块。

### 9.3 性能

- 打开/切页/滚动无卡顿；不在渲染线程扫描区块/文件。
- 4K/BSL 下 GUI 不增加明显帧耗时。
- 页面控件数量受控；列表用可见行，不为全部条目建 widget。
- 关闭 GUI 后无 widget/纹理/线程泄漏。

### 9.4 构建

- `.\gradlew.bat check`（JDK 26）通过。
- 如果新增 lang key，确认资源路径/编码正确。
- 手动游戏内验收由你完成；不以截图/日志代替。

## 10. 风险与待确认

### 10.1 待你拍板

1. **按现实菜单树落地**：建议保留六个日常一级页，同时把 `Setup` 做成 master-detail 分类（Inputs/Audio/Video/Speakers/Network/General），不再做成长列表。这比把 Speakers/Sound 全部塞进 Setup 更适合日常操作；如果你希望完全按 Denon/Yamaha 的“Settings 单入口”，也可以把 Speakers/Sound 降为 Setup 子项，但需要多一次点击。
2. **Level 归属**：推荐从 Speakers 移到 Sound；如果你习惯在 Speakers 调 Level，也可以保留入口，但必须单一数据源。
3. **默认页**：推荐 Home。是否希望播放成功后自动回 Home？
4. **面板尺寸/断点**：推荐 720×460 目标、560×320 compact 断点；如果你常用 GUI scale 4，需要优先保证 320×180。
5. **本地化**：G3/G4 再做，还是 G1 就把文本抽 key？G1 抽 key 会增加 diff，但后续省事。
6. **Quick Select/Scenes**：数据放接收器（服务端同步）还是客户端本地？我建议先做客户端本地场景，避免新网络包；但多人影院可能希望服务端统一。
7. **Setup Assistant**：是否本次就做首启向导，还是等 G1–G4 稳定后独立做？推荐独立，避免把“首次引导”与布局重构混在一起。
8. **测试音**：是否属于本次 G4，还是另立任务？需要音频引擎支持，不建议只做 UI 按钮。
9. **A4 已取消**：确认接收器 GUI 不出现任何声学/房间/反射/限幅设置；这些继续在 Mod Menu。

### 10.2 风险

- **布局回归**：420×300 固定布局的很多坐标是硬编码；拆页时容易错位。G1 先保持原尺寸/位置，G3 再响应式。
- **widget 生命周期**：`Screen` 的 `renderables` 容易重复注册；必须用页面控件列表统一移除。
- **状态同步**：当前 `syncWidgets()` 每 tick 更新很多东西；拆分后注意不要两个页面同时更新同一个控件。
- **媒体状态**：每个接收器的库/文件夹/搜索必须继续隔离；不要因为页面单例丢状态。
- **父屏幕返回**：`RemotePairingScreen`/`RemoteDeviceScreen` 的 parent 链要保留。
- **文本/翻译**：硬编码文本多，抽 key 时容易漏；可以先保持英文，再统一迁移。
- **性能**：Home 若加最近播放/布局图，可能引入额外扫描；第一版保持只读现有数据。
- **范围蔓延**：不要在本重构里改音频路由、声学、投影仪、媒体协议；先 GUI。

## 11. 明确不做

- 不实现 A4 接收器声学集成；不把 Mod Menu 的声学开关/房间预设/重扫搬进接收器。
- 不实现投影仪光学/屏幕配对设置。
- 不实现音轨/字幕/视频处理（`Video Processing` 是另一个任务）。
- 不实现新的 LAN/权限模型；控制包和服务端校验不变。
- 不实现测试音/最近播放/收藏，除非另立任务并补齐后端。
- 不为了“像现实 AVR”而伪造不存在的功能；页面只展示已有数据和控件。

## 12. 验收清单（最终）

- [ ] 默认打开 Home，能一眼看到媒体、状态、时间、视频/音频/媒体/limiter。
- [ ] 六页导航在宽屏/窄屏都可用；切页不丢媒体浏览状态。
- [ ] Media 能完成选片/播放；Setup > Inputs 能管理外部库；两者不重复。
- [ ] Setup 是 master-detail 分类，Inputs/Audio/Video/Speakers/General 可达；Network 在没有数据时不显示空菜单。
- [ ] Speakers 能 Ping/Access；Sound 能调每只扬声器 Level；两页数据一致。
- [ ] Information 展示现有 Signal 诊断；Setup 展示库/配对/About。
- [ ] 名称、seek、音量、Level 的 debounce/Close flush 与当前一致。
- [ ] 多接收器互不影响。
- [ ] GUI scale 1..4、1280×720 到 2560×1440 无重叠/裁剪；页面可滚动。
- [ ] 键盘/Tab/Enter/Esc、tooltip、禁用态正常。
- [ ] 4K/BSL 下无 GUI 卡顿；`check` 通过。
- [ ] 没有声学/投影仪设置混入；A4 保持取消。
- [ ] 游戏内由你确认布局/操作习惯。
