# Cinema 指令使用文档

游戏内随时输入：

```text
/cinema_help
/cinema_help 4
/cinema help
```

## 1. AV 接收器与播放控制

> M1：媒体解码/播放状态/音画时钟归 **AV Receiver**。客户端命令只负责选择本机媒体并发送控制包；投影仪只消费接收器的视频输出，扬声器只消费音频输出。

默认媒体库为 `<game>/cinema/media/`；可在接收器 GUI 的 **Settings → Add library** 添加外部文件夹。默认库使用相对 key，外部库使用 `lib/<id>/<相对路径>`；粘贴已注册库内的绝对路径也会自动转成 key。绝对路径不会写入接收器状态或网络包。同一时间只允许一个接收器处于播放/暂停状态。

| 指令 | 说明 |
|---|---|
| `/cinema_play <媒体库 key 或库内路径>` | 选片并播放，例如 `movies/demo.mp4`、`lib/ext1/demo.mp4`；路径可以带空格 |
| `/cinema_pause` | 暂停 / 继续 |
| `/cinema_seek <秒>` | 跳转；当前实现会在关键帧附近重启解码器 |
| `/cinema_stop` | 停止播放并释放视频纹理 |
| `/cinema_volume <0-1>` | 设置接收器主音量 |
| `/cinema_mute` | 切换静音 |
| `/cinema_link` | 重新扫描并配对 48 格内最近的投影仪 |
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
| `/cinema_projector_client throw auto` | 按当前屏幕自动适配投掷比 |
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
| `/cinema_projector throw <投掷比>` | 设置投掷比 |
| `/cinema_projector focus auto` | 自动对焦 |
| `/cinema_projector focus <0.25-64>` | 手动焦距（米） |
| `/cinema_projector keystone <垂直角度> <水平角度>` | 梯形校正 |
| `/cinema_projector brightness <0.01-1.0>` | 亮度 |
| `/cinema_projector wash <0-1>` | 环境光洗白强度 |
| `/cinema_projector power` | 开关灯泡 |

## 4. 屏幕与配对

- 屏幕由同朝向、同平面的屏幕方块组成，最大 16×16。
- 投影仪自动寻找前方 48 格内最近的屏幕；屏幕正面必须朝向投影仪。
- 接收器每 20 tick 自动配对/重连 48 格内最近的投影仪；投影仪上的 `receiverPos` 会随方块实体同步。`/cinema_link` 仅用于立即强制重扫，正常流程不需要。
- 右键投影仪打开 GUI，可直接调整环境光洗白；命令 `/cinema_projector[_client] wash <0-1>` 同步。
- 拆掉投影仪后屏幕保持黑屏，接收器音频继续；重新放回后等待自动重连或执行 `/cinema_link`。
- 拆掉接收器后屏幕立即黑屏、音频停止。
- `/time set midnight` / `/time set noon` 可测试环境光洗白。

影院设备物品（第一版占位）：

| 物品 | ID | 当前行为 |
|---|---|---|
| AV 接收器 | `firstmod:av_receiver` | 空手右键显示媒体/状态/音量/视频输出 |
| 落地式扬声器 | `firstmod:speaker` | 两格高；空手右键循环 `NONE/FL/FR/BL/BR` |
| 书架式扬声器 | `firstmod:bookshelf_speaker` | 一格高；空手右键循环 `NONE/FL/FR/BL/BR` |
| 中置扬声器 | `firstmod:center_speaker` | 一格高；空手右键循环 `NONE/FC` |
| 低音炮 | `firstmod:subwoofer` | 一格高；空手右键循环 `NONE/LFE` |
| 影院线材 | `firstmod:cable` | 仅物品与提示，连接行为未接 |
| 影院遥控器 | `firstmod:remote_controller` | 仅物品与提示，遥控行为未接 |

创造模式位置：接收器/扬声器在 Functional Blocks，线材/遥控器在 Tools & Utilities。

## 5. 手动 / 自动测试

手动模式：不退出、不锁定玩家。

```powershell
$video=(Resolve-Path '<测试视频>').Path
$env:JAVA_TOOL_OPTIONS="-Dfirstmod.spike.video=$video -Dfirstmod.spike.manual=true"
.\gradlew runClient --args="--quickPlaySingleplayer=spike"
```

自动模式：把 `manual=true` 换成 `exit=true`。

测试环境会固定时间、天气、玩家位置，并关闭动物生成。屏幕/投影仪需要自行放置。

## 6. 常见问题

- 视频停在最后一帧：`/cinema_seek 0` 重播。
- 屏幕没有画面：确认接收器正在播放、投影仪已配对、屏幕正面朝向投影仪、距离在 48 格内、光路没有不透明方块遮挡。
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
-Dfirstmod.ffmpeg.path=C:\path\to\ffmpeg.exe
```

默认 HDR 缩到 720p、SDR 缩到 1080p；`-Dfirstmod.video.maxHeight=0` 保留原生。
硬件解码默认 `auto`；`-Dfirstmod.ffmpeg.hwaccel=none` 可关闭。
