# Cinema 交接清单

> 新会话请按顺序读：本文件 → [PLANNING.md](PLANNING.md) → [TODO.md](TODO.md) → [PROJECTOR-GUI.md](design/PROJECTOR-GUI.md)。
> 当前分支 `main`，最新提交见 `git log -1`；工作区应为干净。

## 1. 当前状态

- Minecraft 1.21.1 + Fabric，Java 21；包名 `com.example.firstmod`，模组 id `firstmod`。
- 主模组构建通过：`.\gradlew build`。
- 最近一局 M2 验收：接收器 GUI、外部媒体库、4K HEVC 播放稳定；不再 1 秒一次重启。
- 用户已验收通过：HDR10/HLG、4K60 VP9、4K60 HEVC/AV1、四角对齐/四个朝向、对焦/梯形、拆投影仪立即黑屏、相邻方块不透明。
- 剩余验收：无音轨长播、亮度 0.01..1、wash 观感（BSL 下某些值画面异常，待定位）。
- 投影仪光学、外部 FFmpeg、临时 wash GUI 已可运行。
- **AV Receiver M1/M2 已完成并验收**：M1 把媒体/播放状态/音画时钟迁入接收器，新增 `ReceiverControlPayload`（C2S）与服务端校验；客户端 `AvReceiverPlaybackManager` 每 10 tick 同步接收器状态，视频经 `ProjectorOptics` 路由到屏幕，音频由接收器播放代理驱动共享 OpenAL 5.1。M2 新增接收器 GUI（Media/Speakers/Signal/Settings）、默认库 + 外部媒体库、`lib/<id>/...` key、添加/移除/切换库、记住上次库/文件夹，以及长文本截断、tooltip 换行、滑块 pending、ESC 暂停同步、文字图层修复。接收器每 20 tick 自动配对/重连 48 格内最近投影仪；M1 只允许一个接收器处于播放/暂停（单音频引擎）。设计见 `docs/design/AV-RECEIVER.md`。
- 用户已验收通过：M1 选片/播放/暂停/seek/音量/静音、自动配对投影仪、拆接收器/投影仪/屏幕后的停止与恢复、缺文件提示；M2 界面无溢出、截断/tooltip 正常、4K HEVC 不再 1 秒重启、关闭 GUI 后记住库/文件夹。
- 扬声器已拆分为四种箱体：落地式 `speaker`（两格高）、书架式 `bookshelf_speaker`（一格高，FL/FR/BL/BR）、中置 `center_speaker`（一格高，FC）、低音炮 `subwoofer`（一格高，LFE）；空手右键只在各自合法角色内循环。落地式下半格持有 block entity，上半格为无 block entity 的延伸；破坏任一半只掉一个物品。线材/遥控器物品已创建，行为尚未接入。
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
4. **投影仪 GUI 的正式设计见 `docs/design/PROJECTOR-GUI.md`**；当前 `ProjectorScreen` 只是临时 wash 滑块。
5. 投影仪右键打开 GUI；Done 保存并关闭，Cancel/Esc 丢弃。

## 3. 本会话已完成

- 投影仪光学第一版：
  - 投掷比、自动/手动焦距、垂直/水平梯形、亮度、灯泡预热/冷却
  - 屏幕自动配对、逐块镜头 UV/裁剪
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

## 4. 已知问题 / 风险

- 4K60/HDR 已由用户验收通过；建议后续记录具体 codec/pix_fmt/hwaccel/丢帧数据，便于回归。
- **BSL 下 ambient wash 某些值画面异常（待定位具体表现/数值）**；brightness 0.01..1 尚未验收；无音轨墙钟长播未测。
- M2 GUI 测试期间再次出现 `nvoglv64.dll` ACCESS_VIOLATION（NVIDIA OpenGL 驱动层），栈为 `CinemaVideoTexture.upload → glTexSubImage2D`；不是 Java 异常。已改为每个接收器跨 seek 复用视频纹理，并提供 `-Dfirstmod.video.glFinish=true` 诊断开关，待复测。
- AV Receiver M1 已完成；M2 接收器 GUI 已实现，等待游戏内验收。投影仪/扬声器仍使用接收器的共享播放代理。
- 多屏/多投影仪、LAN 同步、资源重载、两小时长片仍未完成。
- 旧 Git 历史仍含旧提交作者信息；工作树已清理。若要彻底隐私，需要重写历史并 force-push，或把仓库设为 private。
- 不要重新引入：test scene、visual spike、barrier 测试方块、阴影、镜头光晕、过热。

## 5. 下一步优先级

1. **正式投影仪 GUI（下一会话从这开始）**
   - 按 `docs/design/PROJECTOR-GUI.md` 实现；当前 `ProjectorScreen` 只有临时 wash 滑块。
   - 先做 M1 只读状态页，再做 M2 光学/wash 编辑，之后 M3 物理模拟开关、M4 配对/诊断。
   - 投影仪 GUI 只放光学、灯泡、wash、配对、物理模拟开关；不出现文件、codec、丢帧、播放控制（这些属于 AV Receiver）。
   - 设置走 C2S `UpdateProjectorSettings`（或等价 payload），服务端校验范围/距离/方块；Done 提交并关闭，Cancel 丢弃草稿。
   - 可复用 M2 `AvReceiverScreen` 的成熟模式：`renderBackground` 中画面板/文字、tooltip 自动换行、滑块 pending、可滚动列表、紧凑布局。
   - 关键文件：`ProjectorBlockEntity.java`、`ProjectorScreen.java`、`ProjectorOptics.java`、`ProjectorCommand.java`、`FirstModClient.java`、`AvReceiverScreen.java`。
2. **M3 每接收器独立播放**
   - 独立播放器/纹理/音频引擎；第二接收器不再拒绝
   - 继续观察 NVIDIA 视频上传崩溃与音频设备切换恢复
3. **剩余验收与 wash 修复**
   - 定位 BSL 下 wash 异常；补测 brightness 0.01..1
   - 无音轨墙钟长播；记录 4K/HDR codec/hwaccel/丢帧回归数据
4. **媒体能力扩展**：音轨/视频轨/字幕选择与渲染、AV Receiver Video Processing、拖放导入媒体库
5. 之后：M4 扬声器 5.1 路由、LAN、资源重载、长片测试、GUI 进一步打磨

### 5.1 新会话第一步（投影仪 GUI）

1. 读 `docs/HANDOFF.md` → `docs/PLANNING.md` → `docs/TODO.md` → `docs/design/PROJECTOR-GUI.md`。
2. 用 `git log -1` / `git status` 确认最新提交与工作区；当前最新 M2 提交 `008a864`。
3. 先实现 M1 只读状态页：右键投影仪打开正式 GUI，显示屏幕配对、距离、灯泡、AV 输入状态；不影响现有 wash 临时入口。
4. 再实现 M2 编辑：投掷比、焦距、梯形、亮度、wash；滑块只改草稿，Done 提交，Cancel 丢弃。
5. 构建并在 `spike` 存档验收：GUI scale 1..4、边界值、Done/Cancel、拆投影仪/屏幕、Sodium/Iris 下不崩溃。
6. BSL 下 wash 画面异常仍单独排查，不要和 GUI 布局混在一起。

## 6. 构建与运行

Gradle 需要 JDK 25+，游戏/编译目标 JDK 21：

```powershell
$env:JAVA_HOME='<JDK-25+>'
.\gradlew build
.\gradlew runClient
```

FFmpeg（必须）：

```text
run/mods/ffmpeg/bin/ffmpeg.exe
run/mods/ffmpeg/bin/ffprobe.exe
```

发布版对应 `.minecraft/mods/ffmpeg/bin/`。也支持 `-Dfirstmod.ffmpeg.path=...`。

手动测试（不自动退出、不锁定玩家）：

```powershell
$env:JAVA_TOOL_OPTIONS="-Dfirstmod.spike.manual=true"
.\gradlew runClient --args="--quickPlaySingleplayer=spike"
```

自动测试不再搭场景；屏幕/投影仪需要手动放置。旧布局可参考：

```text
/fill -2 -59 4 1 -57 4 firstmod:screen[facing=north]
/setblock 0 -59 -3 firstmod:projector[facing=south]
```

然后：

```text
/cinema_play <本地文件绝对路径>
/cinema_projector_client info
/cinema_projector_client wash 0.2
```

## 7. 关键文件

```text
src/main/java/com/example/firstmod/FirstMod.java
src/main/java/com/example/firstmod/block/entity/ProjectorBlockEntity.java
src/main/java/com/example/firstmod/block/entity/ScreenBlockEntity.java
src/main/java/com/example/firstmod/command/ProjectorCommand.java

src/client/java/com/example/firstmod/client/FirstModClient.java
src/client/java/com/example/firstmod/client/gui/ProjectorScreen.java
src/client/java/com/example/firstmod/client/command/CinemaHelpCommand.java
src/client/java/com/example/firstmod/client/command/ClientProjectorCommand.java
src/client/java/com/example/firstmod/client/media/          # FFmpeg/播放/音频/纹理
src/client/java/com/example/firstmod/client/render/ProjectorOptics.java
src/client/java/com/example/firstmod/client/render/ScreenBlockEntityRenderer.java
src/client/java/com/example/firstmod/client/render/ScreenGeometry.java
```

## 8. 用户偏好 / 协作规则

- 用中文沟通。
- 复杂视觉效果由用户**在游戏内**验收，不接受只发截图/只看日志判断通过。
- 不重新加入 test scene / visual spike；测试场景由用户手动放置。
- 删除功能前要明确确认。
- 不在仓库文件里写个人绝对路径、姓名、邮箱、私人媒体文件名。
- 测试时先确保 Minecraft 窗口置前。
