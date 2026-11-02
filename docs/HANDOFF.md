# Cinema 交接清单

> 新会话请按顺序读：本文件 → [PLANNING.md](PLANNING.md) → [TODO.md](TODO.md) → [PROJECTOR-GUI.md](design/PROJECTOR-GUI.md)。
> 当前分支 `main`，最新提交见 `git log -1`；工作区应为干净。

## 1. 当前状态

- Minecraft 1.21.1 + Fabric，Java 21；包名 `com.example.firstmod`，模组 id `firstmod`。
- 主模组构建通过：`.\gradlew build`。
- 最近一局 M2 验收：接收器 GUI、外部媒体库、4K HEVC 播放稳定；不再 1 秒一次重启。
- 用户已验收通过：HDR10/HLG、4K60 VP9、4K60 HEVC/AV1、四角对齐/四个朝向、对焦/梯形、亮度 0.01..1、拆投影仪立即黑屏、相邻方块不透明。
- 剩余验收：无音轨长播、wash 观感（BSL 下某些值画面异常，待定位）。
- 投影仪光学、外部 FFmpeg、临时 wash GUI 已可运行。
- **AV Receiver M1/M2 已完成并验收**：M1 把媒体/播放状态/音画时钟迁入接收器，新增 `AvReceiverControlPayload`（C2S）与服务端校验；客户端 `AvReceiverPlaybackManager` 每 10 tick 同步接收器状态，视频经 `ProjectorOptics` 路由到屏幕，音频由接收器播放代理驱动共享 OpenAL 5.1。M2 新增接收器 GUI（Media/Speakers/Signal/Settings）、默认库 + 外部媒体库、`lib/<id>/...` key、添加/移除/切换库、记住上次库/文件夹，以及长文本截断、tooltip 换行、滑块 pending、ESC 暂停同步、文字图层修复。接收器每 20 tick 自动配对/重连 48 格内最近投影仪；M1 只允许一个接收器处于播放/暂停（单音频引擎）。设计见 `docs/design/AV-RECEIVER.md`。
- 用户已验收通过：M1 选片/播放/暂停/seek/音量/静音、自动配对投影仪、拆接收器/投影仪/屏幕后的停止与恢复、缺文件提示；M2 界面无溢出、截断/tooltip 正常、4K HEVC 不再 1 秒重启、关闭 GUI 后记住库/文件夹。
- 扬声器已拆分为四种箱体：落地式 `speaker`（两格高）、书架式 `bookshelf_speaker`（一格高，FL/FR/BL/BR）、中置 `center_speaker`（一格高，FC）、低音炮 `subwoofer`（一格高，LFE）；空手右键打开该扬声器的 directivity GUI，角色选择也在 GUI 内（Done 提交服务端）。落地式下半格持有 block entity，上半格为无 block entity 的延伸；破坏任一半只掉一个物品。线材/遥控器物品已创建，行为尚未接入。
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
5. 投影仪右键打开 GUI；Done 保存并关闭，Cancel/Esc 丢弃。

## 3. 本会话已完成

- 用户已验收通过投影仪 GUI M1/M2/M3：状态区、光学/wash、Throw/Focus Auto、Power、四个模拟开关、改动即保存；对焦模糊观感一般，列为后续改进。
- 投影仪 GUI M1/M2/M3：
  - `ProjectorConfigScreen` 替换临时 wash 滑块；状态区显示屏幕、灯泡、AV 输入；光学/wash 滑块只改草稿。
  - 新增 C2S `ProjectorSettingsPayload` + `ProjectorSettingsHandler`；服务端校验距离/方块/范围后 `ProjectorBlockEntity.applySettings` 一次写入。
  - 自动/手动投掷比与焦距、Power ON/OFF、Defaults、改动即保存（按钮立即/滑块 debounce）、GUI 不暂停；2 列紧凑布局。
  - M3 物理模拟开关 Focus/Keystone/Wash/Warmup：切换即发送，`ProjectorOptics` 和灯泡 ramp 按开关跳过对应计算，NBT/网络同步。
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
- 音频声学规划：
  - 新增 `docs/design/AUDIO-ACOUSTICS.md`：EAXReverb 只取晚期尾音，早期反射用一阶镜像源 + **Spinorama 实测扬声器指向性**；
    明确只做扬声器指向性/不做原始声源指向性、材质近似、低频模态不建模、Spinorama CC BY-NC-SA 许可、EFX spike 与 M4/Seat 依赖。
  - 当前音频管线基线（FFmpeg → StreamingAudioDecoder → AudioChunk → CinemaAudioEngine → OpenAL，虚拟 5.1，M4 前不绑定实体扬声器）已写入 AUDIO-ACOUSTICS.md §4.1。
  - 已实现 directivity spike：`cinema/speaker_specs/profiles/` 本地 JSON、拖放导入、空手右键扬声器打开 per-speaker GUI（角色选择也在 GUI 内，Done 走 C2S `SpeakerRolePayload`）、`/cinema_directivity` 类型默认调试入口；类型默认 `settings.json`，每扬声器 `assignments.json`（维度+坐标）。measured profile 读取三组 Spinorama 数据：水平指向性、垂直指向性、轴上响应（`onAxis`）；低音炮内置 `sub-omni`/`sub-cardioid`，其他类型为 unmeasured 占位；简单参数 range/SPL gain 可编辑。GUI `isPauseScreen=false`，打开不暂停游戏。尚未把 directivity 应用到实际音频。
  - 同步更新 `PLANNING.md`（P1-7 里程碑/风险/测试原则）、`TODO.md`（Spike + A0–A6/A1d/A1e/A2d 任务）、`AV-RECEIVER.md`（交叉引用）。

## 4. 已知问题 / 风险

- 4K60/HDR 已由用户验收通过；建议后续记录具体 codec/pix_fmt/hwaccel/丢帧数据，便于回归。
- **BSL 下 ambient wash 某些值画面异常（待定位具体表现/数值）**；无音轨墙钟长播未测。
- M2 GUI 测试期间再次出现 `nvoglv64.dll` ACCESS_VIOLATION（NVIDIA OpenGL 驱动层），栈为 `CinemaVideoTexture.upload → glTexSubImage2D`；不是 Java 异常。已改为每个接收器跨 seek 复用视频纹理，并提供 `-Dfirstmod.video.glFinish=true` 诊断开关，待复测。
- AV Receiver M1 已完成；M2 接收器 GUI 已实现，等待游戏内验收。投影仪/扬声器仍使用接收器的共享播放代理。
- **音频声学仍是规划**：见 `docs/design/AUDIO-ACOUSTICS.md`。EAXReverb 只计划用于晚期尾音；早期反射用一阶镜像源 + **Spinorama 实测扬声器指向性**。依赖 M4 扬声器路由/Seat；EFX 可用性、材质吸收、型号映射都需 spike/校准。
- 当前 5.1 是 6 个固定相对位置的虚拟 source，尚未绑定实体扬声器方块；M4 之前不能验收真实房间声学/指向性。
- Spinorama 测量数据默认 CC BY-NC-SA 4.0（非商业/相同方式共享），与项目 CC0 不同；发布前必须做许可/attribution 决策。
- Spinorama REST API 现在需要人工申请 Bearer key（无匿名访问）；key 只能放本地环境变量/转换工具，不能进仓库、模组发布包或日志。API 不提供 Erin's Audio Corner 测量。
- 一阶声学模型不覆盖衍射、散射、高阶反射和低频房间模态；LFE 低频峰谷/驻波不会正确重现。
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
   - 下一步：游戏内验收 M1/M2/M3，然后 M4 配对/诊断。
2. **M3 每接收器独立播放**
   - 独立播放器/纹理/音频引擎；第二接收器不再拒绝
   - 继续观察 NVIDIA 视频上传崩溃与音频设备切换恢复
3. **剩余验收与 wash 修复**
   - 定位 BSL 下 wash 异常
   - 无音轨墙钟长播；记录 4K/HDR codec/hwaccel/丢帧回归数据
4. **媒体能力扩展**：音轨/视频轨/字幕选择与渲染、AV Receiver Video Processing、拖放导入媒体库
5. **M4 扬声器 5.1 路由、Seat、LAN、资源重载、长片测试、GUI 进一步打磨**
6. **音频计算/房间声学（M4 后）**
   - 见 `docs/design/AUDIO-ACOUSTICS.md`；A1d Spinorama 数据 spike、A0 EFX/EAXReverb spike 可在 M2/M3 之后提前做。
   - 只做扬声器指向性，不做原始声源指向性；指向性用 Spinorama 实测水平/垂直指向性 + 轴上响应，映射到四类箱体；API 需要人工申请 key，转换工具只在本地使用。
   - 早期反射用一阶镜像源 + Spinorama 指向性；EAXReverb 只取晚期尾音，关闭其固定 5 m 早期反射。
   - 先做 Spinorama 许可决策（CC BY-NC-SA 4.0 vs 宽松数据/用户本地导入），再决定是否打包数据。
   - 不要为了声学阻塞 M4；EFX 不可用时回退到无混响或小型 FDN。

### 5.1 当前交接状态

1. 读 `docs/HANDOFF.md` → `docs/PLANNING.md` → `docs/TODO.md` → `docs/design/PROJECTOR-GUI.md`。
2. 用 `git log -1` / `git status` 确认最新提交与工作区；当前最新提交以 `git log -1` 为准。
3. 投影仪 GUI M1/M2 已实现，等待游戏内验收：右键投影仪打开，状态区 + 光学/wash 滑块；Done 提交，Cancel 丢弃；GUI 不暂停。
4. 验收重点：GUI scale 1..4、边界值、自动/手动焦距、Done/Cancel、拆投影仪/屏幕、Sodium/Iris 下不崩溃。
5. 验收通过后再做 M3 物理模拟开关（对焦/梯形/wash/预热），不要和 BSL wash 异常排查混在一起。

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
src/client/java/com/example/firstmod/client/gui/ProjectorConfigScreen.java
src/client/java/com/example/firstmod/client/command/ClientHelpCommand.java
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
