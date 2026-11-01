# Cinema 指令使用文档

游戏内随时输入：

```text
/cinema_help
/cinema_help 4
/cinema help
```

## 1. 播放控制

| 指令 | 说明 |
|---|---|
| `/cinema_play <本地文件绝对路径>` | 播放本地视频文件；路径可以带空格 |
| `/cinema_pause` | 暂停 / 继续 |
| `/cinema_seek <秒>` | 跳转；当前实现会在关键帧附近重启解码器 |
| `/cinema_stop` | 停止播放并释放视频纹理 |

```text
/cinema_seek 0
```

视频结束后会停在最后一帧；用上面的命令从头重播。

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
| `/cinema_projector power` | 开关灯泡 |

## 4. 屏幕与配对

- 屏幕由同朝向、同平面的屏幕方块组成，最大 16×16。
- 投影仪自动寻找前方 48 格内最近的屏幕；屏幕正面必须朝向投影仪。
- 拆掉投影仪后屏幕保持黑屏；重新放回投影仪后恢复。
- `/time set midnight` / `/time set noon` 可测试环境光洗白。

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
- 屏幕没有画面：确认屏幕正面朝向投影仪、距离在 48 格内、光路没有不透明方块遮挡。
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
