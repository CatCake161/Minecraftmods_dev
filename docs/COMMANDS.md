# Cinema 指令使用文档

游戏内随时输入：

```text
/cinema_help
/cinema_help 4
/cinema help
```

## 1. AV 接收器与播放控制

> M1：媒体解码/播放状态/音画时钟归 **AV Receiver**。客户端命令只负责选择本机媒体并发送控制包；投影仪只消费接收器的视频输出，扬声器只消费音频输出。

默认媒体库为 `<game>/cinema/media/`；可在接收器 GUI 的 **Settings → Add library** 添加外部文件夹。默认库使用相对 key，外部库使用 `lib/<id>/<相对路径>`；粘贴已注册库内的绝对路径也会自动转成 key。绝对路径不会写入接收器状态或网络包。媒体列表首次打开时扫描并探测元数据，之后在当前游戏会话内缓存；**Refresh / Refresh library** 强制重新扫描。同一时间只允许一个接收器处于播放/暂停状态。

| 指令 | 说明 |
|---|---|
| `/cinema_play <媒体库 key 或库内路径>` | 选片并播放，例如 `movies/demo.mp4`、`lib/ext1/demo.mp4`；路径可以带空格 |
| `/cinema_pause` | 暂停 / 继续 |
| `/cinema_seek <秒>` | 跳转；当前实现会在关键帧附近重启解码器 |
| `/cinema_stop` | 停止播放并释放视频纹理 |
| `/cinema_volume <0-1>` | 设置接收器主音量 |
| `/cinema_mute` | 切换静音 |
| `/cinema_receiver info` | 查看最近接收器的媒体/状态/音量/投影仪 |

```text
/cinema_play test720p30_51.mp4
/cinema_seek 0
```

命令作用于 64 格内最近的 AV 接收器。视频结束后会停在最后一帧；用 `/cinema_seek 0` 从头重播。

### 外部媒体库

- 接收器 GUI → **Settings** → 输入外部文件夹的绝对路径 → **Add library**。
- 外部库列表可点击选中，**Remove library** 只删除注册，不删除磁盘文件。
- **Media** 页左上角 `Library` 按钮在默认库和外部库之间切换。
- 外部库 key 形如 `lib/<id>/<相对路径>`；id 由库名生成。
- 配置保存在 `<game>/cinema/libraries.json`，仅客户端使用；LAN 客户端需要配置同名库才能播放同一外部库 key。

## 2. 投影仪（客户端）

这些指令只修改**离玩家最近的客户端投影仪**，不会同步到服务端。

| 指令 | 说明 |
|---|---|
| `/cinema_projector_client info` | 查看投掷比、焦距、梯形、亮度、开关、配对屏幕 |
| `/cinema_projector_client throw <0.6-4.0>` | 设置投掷比 |
| `/cinema_projector_client throw auto` | 自动投掷比模式（按配对屏幕） |
| `/cinema_projector_client focus <0.25-64>` | 手动焦距（米） |
| `/cinema_projector_client focus auto` | 自动对焦到配对屏幕 |
| `/cinema_projector_client keystone <垂直角度> <水平角度>` | 梯形校正，-30..30 |
| `/cinema_projector_client brightness <0.01-1.0>` | 亮度 |
| `/cinema_projector_client wash <0-1>` | 环境光洗白强度；0 = 关闭，1 = 全白 |
| `/cinema_projector_client power` | 开关灯泡（走预热/冷却） |

## 3. 投影仪（服务端 / 真实方块）

修改 **48 格内最近的服务端投影仪**，并同步给 LAN 客户端。

| 指令 | 说明 |
|---|---|
| `/cinema_projector info` | 查看参数 |
| `/cinema_projector throw auto` | 自动投掷比（按配对屏幕距离/宽度） |
| `/cinema_projector throw <投掷比>` | 手动投掷比 |
| `/cinema_projector focus auto` | 自动对焦 |
| `/cinema_projector focus <0.25-64>` | 手动焦距（米） |
| `/cinema_projector keystone <垂直角度> <水平角度>` | 梯形校正 |
| `/cinema_projector brightness <0.01-1.0>` | 亮度 |
| `/cinema_projector wash <0-1>` | 环境光洗白强度 |
| `/cinema_projector power` | 开关灯泡 |

## 4. 屏幕与配对

- 屏幕由同朝向、同平面的屏幕方块组成；默认绘制上限 16×16。可在 Mod Menu → Cinema → Config 中调整（1..64，硬上限 64×64），保存到 `config/cinema.json`；启动参数 `-Dcinema.screen.maxSize=N` 仍优先。超过当前上限的部分不绘制。
- 投影仪不再自动寻找屏幕；未配对时保持无画面。
- 显式配对（影院线材）：右键投影仪再右键屏幕，或反向顺序；成功后投影仪保存屏幕链接，GUI 状态显示 `linked`。显式链接允许斜向安装，但仍要求屏幕在投影仪前方且距离不超过 48 格。再次右键同一设备取消配对选择；点击不兼容设备会改为选择新设备；选择状态 60 秒超时，玩家断线时清除。
- 解除连接：潜行右键设备 A，再潜行右键设备 B；只解除 A↔B 这一条连接（支持任意屏幕方块）。同一设备再次潜行右键取消解除选择；两者没有连接时提示。解除选择同样 60 秒超时/断线清除。
- 接收器不再自动配对投影仪。显式配对（影院线材）：右键接收器再右键投影仪，或反向顺序；接收器保存 `videoTarget`，投影仪保存 `receiverPos`。
- 显式配对（影院线材）：右键接收器再右键扬声器，或反向顺序；双方保存双向链接，接收器 Speakers 页以 `LINKED` 标记已连接扬声器。当前链接只用于配对/gui 展示，实际 5.1 音频路由仍属 M4。
- 空手右键投影仪打开正式 GUI：状态区显示屏幕/灯泡/AV 输入；自动/手动投掷比、自动/手动焦距、梯形、亮度、wash、Power ON/OFF、Focus/Keystone/Wash/Warmup 模拟开关都**改动即保存**（按钮立即，滑块约 200ms debounce；Close/Esc 前 flush）。手持物品时保留原版物品/方块交互，不打开 GUI。命令 `/cinema_projector[_client]` 仍可用于调试。
- AV 接收器与四种扬声器同样只有空手右键打开 GUI；手持物品时保留原版交互。AV 接收器不再向聊天栏打印状态，状态可用 `/cinema_receiver` 查看。
- 拆掉投影仪后屏幕保持黑屏，接收器音频继续；重新放回后需要再用线材配对投影仪与接收器/屏幕。
- 拆掉接收器后屏幕立即黑屏、音频停止。
- `/time set midnight` / `/time set noon` 可测试环境光洗白。

影院设备物品：

| 物品 | ID | 当前行为 |
|---|---|---|
| AV 接收器 | `cinema:av_receiver` | 空手右键打开接收器 GUI（媒体/扬声器/信号/设置）；手持物品保留原版交互 |
| 落地式扬声器 | `cinema:floorstanding_speaker` | 两格高；空手右键打开 speaker specs GUI；角色在 GUI 内选择 `NONE/FL/FR/BL/BR` |
| 书架式扬声器 | `cinema:bookshelf_speaker` | 一格高；空手右键打开 speaker specs GUI；角色在 GUI 内选择 `NONE/FL/FR/BL/BR` |
| 中置扬声器 | `cinema:center_speaker` | 一格高；空手右键打开 speaker specs GUI；角色在 GUI 内选择 `NONE/FC` |
| 低音炮 | `cinema:subwoofer` | 一格高；空手右键打开 speaker specs GUI；角色在 GUI 内选择 `NONE/LFE` |
| 影院线材 | `cinema:cable` | 两次右键配对：投影仪↔屏幕、接收器↔投影仪、接收器↔扬声器；潜行右键两个设备解除它们之间的连接 |
| 影院遥控器 | `cinema:remote_controller` | 仅物品与提示，遥控行为未接 |

创造模式位置：接收器/扬声器在 Functional Blocks，线材/遥控器在 Tools & Utilities。

## 5. 手动 / 自动测试

手动模式：不退出、不锁定玩家。

```powershell
$video=(Resolve-Path '<测试视频>').Path
$env:JAVA_TOOL_OPTIONS="-Dcinema.spike.video=$video -Dcinema.spike.manual=true"
.\gradlew runClient --args="--quickPlaySingleplayer=spike"
```

自动模式：把 `manual=true` 换成 `exit=true`。

测试环境会固定时间、天气、玩家位置，并关闭动物生成。屏幕/投影仪需要自行放置。

## 6. 常见问题

- 视频停在最后一帧：`/cinema_seek 0` 重播。
- 屏幕没有画面：确认接收器正在播放、投影仪已用线材配对（`linked`）、屏幕正面朝向投影仪、距离在 48 格内、光路没有不透明方块遮挡。
- `/cinema_play` 提示找不到文件：文件必须放在默认库或 Settings 添加的外部库；参数用库内 key，或粘贴已注册库内的绝对路径。
- 第二个接收器无法播放：M1 只允许一个接收器占用音频引擎；先对前一个执行 `/cinema_stop`。
- `/cinema_projector_client` 没反应：它是客户端命令；真实方块用 `/cinema_projector`。
- 找不到新命令：重启客户端加载最新代码。

## 7. FFmpeg（必须安装）

从 <https://www.gyan.dev/ffmpeg/builds/> 下载 **full build**：

```text
.minecraft/mods/ffmpeg/bin/ffmpeg.exe
.minecraft/mods/ffmpeg/bin/ffprobe.exe
```

或直接放 `mods/ffmpeg.exe`，或指定：

```text
-Dcinema.ffmpeg.path=C:\path\to\ffmpeg.exe
```

默认 HDR 缩到 720p、SDR 缩到 1080p；视频输出高度/缩放归未来 AV Receiver `Video Processing`，暂不放在 Mod Menu。调试可用 `-Dcinema.video.maxHeight=N` 覆盖，`0` 保留原生。
硬件解码默认 `auto`；`-Dcinema.ffmpeg.hwaccel=none` 可关闭。
