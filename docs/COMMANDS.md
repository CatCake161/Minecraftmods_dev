# Cinema 指令使用文档

游戏内随时输入：

```text
/cinema_help
/cinema_help 2
/cinema help
```

共 7 页，覆盖下面所有指令。

## 0. FFmpeg（必须安装）

从 <https://www.gyan.dev/ffmpeg/builds/> 下载 **full build**，放到：

```text
.minecraft/mods/ffmpeg/bin/ffmpeg.exe
.minecraft/mods/ffmpeg/bin/ffprobe.exe
```

或直接放 `mods/ffmpeg.exe`，或指定：

```text
-Dfirstmod.ffmpeg.path=C:\path\to\ffmpeg.exe
```

默认 4K 会缩到 1080p（`-Dfirstmod.video.maxHeight=0` 保留原生）；硬件解码默认 auto
（`-Dfirstmod.ffmpeg.hwaccel=none` 关闭）。

## 1. 播放控制

| 指令 | 说明 |
|---|---|
| `/cinema_play <本地文件绝对路径>` | 播放本地视频文件；路径可以带空格 |
| `/cinema_pause` | 暂停 / 继续 |
| `/cinema_seek <秒>` | 跳转；当前实现会在关键帧附近重启解码器 |
| `/cinema_stop` | 停止播放并释放视频纹理 |

示例：

```text
/cinema_play <本地文件绝对路径>
/cinema_seek 0
```

视频播放结束后会停在最后一帧；用 `/cinema_seek 0` 从头重播。

## 2. 投影仪（客户端测试场景）

这些指令只修改**离玩家最近的客户端投影仪**，适合 `/cinema_test_scene` 自动搭出来的场景，不会同步到服务端。

| 指令 | 说明 |
|---|---|
| `/cinema_projector_client info` | 查看投掷比、焦距、梯形、亮度、开关、配对屏幕 |
| `/cinema_projector_client throw <0.6-4.0>` | 设置投掷比 |
| `/cinema_projector_client throw auto` | 按当前屏幕自动适配投掷比 |
| `/cinema_projector_client focus <0.25-64>` | 手动焦距（米） |
| `/cinema_projector_client focus auto` | 自动对焦到配对屏幕 |
| `/cinema_projector_client keystone <垂直角度> <水平角度>` | 梯形校正，角度范围 -30..30 |
| `/cinema_projector_client brightness <0.05-2.0>` | 亮度；大于 1 为高亮过驱 |
| `/cinema_projector_client power` | 开关灯泡（会走预热/冷却） |

示例：

```text
/cinema_projector_client info
/cinema_projector_client throw 1.75
/cinema_projector_client focus 2.0
/cinema_projector_client keystone 10 0
```

## 3. 投影仪（服务端 / 真实方块）

这些指令修改**48 格内最近的服务端投影仪**，会把参数同步给 LAN 客户端。真实放置的投影仪方块请用这一组。

| 指令 | 说明 |
|---|---|
| `/cinema_projector info` | 查看参数 |
| `/cinema_projector throw <投掷比>` | 设置投掷比 |
| `/cinema_projector focus auto` | 自动对焦配对屏幕 |
| `/cinema_projector focus <0.25-64>` | 手动焦距（米） |
| `/cinema_projector keystone <垂直角度> <水平角度>` | 梯形校正 |
| `/cinema_projector brightness <0.05-2.0>` | 亮度；大于 1 为高亮过驱 |
| `/cinema_projector power` | 开关灯泡 |

需要作弊权限（单人测试世界已开启）。

## 4. 测试场景

| 指令 | 说明 |
|---|---|
| `/cinema_test_scene` | 按当前视角在玩家前方生成 4×3 屏幕，后方生成投影仪 |
| `/cinema_test_scene clear` | 删除通过测试命令生成的屏幕、投影仪、玻璃遮挡物，并清理附近 64 格内的残留 barrier |
| `/cinema_test_scene remove` | 同上，`clear` 的别名 |

`clear` 会清理本局记录过的测试方块；如果重启后记录丢失，会扫描玩家周围 24 格内的 screen/projector 方块。

## 5. 环境与时间

测试模式默认固定正午、晴天、关闭 `doMobSpawning`，并清理附近动物。

```text
/time set midnight
/time set noon
/gamerule doDaylightCycle true
```

`doDaylightCycle` 默认被测试环境关闭；恢复后时间才会自动流逝。

## 6. 手动测试 / 自动测试

手动模式：自动搭景并播放，但**不截图、不退出、不锁定玩家**。

```powershell
$video=(Resolve-Path 'spike\testmedia\test720p30_51.mp4').Path
$env:JAVA_TOOL_OPTIONS="-Dfirstmod.spike.video=$video -Dfirstmod.spike.blocks=true -Dfirstmod.spike.manual=true"
.\gradlew runClient --args="--quickPlaySingleplayer=spike"
```

自动模式：截图后退出，把 `manual=true` 换成：

```text
-Dfirstmod.spike.exit=true
```

常用附加属性：

```text
-Dfirstmod.spike.projector.throw=1.75
-Dfirstmod.spike.projector.focus=2.0
-Dfirstmod.spike.projector.keystone.vertical=10
-Dfirstmod.spike.projector.keystone.horizontal=0
-Dfirstmod.spike.projector.shadow=true
-Dfirstmod.spike.projector.debug=true
```

## 7. 旧测试命令

| 指令 | 说明 |
|---|---|
| `/cinema_visual_spike on` | 打开 P0.5 世界空间测试画面 |
| `/cinema_visual_spike off` | 关闭测试画面 |
| `/cinema_visual_spike screenshot` | 保存截图到 `run/screenshots/` |
| `/cinema_audio_spike` | 运行 5.1 / OpenAL 测试 |

**注意**：`cinema_visual_spike` 是独立的世界空间 quad，不依赖屏幕或投影仪。测试方块投影时请先 `/cinema_visual_spike off`，否则会看到没有幕布也出现的画面。

## 8. 常见问题

- **视频停在最后一帧**：`/cinema_seek 0` 重播。
- **屏幕没有画面**：确认屏幕正面朝向投影仪、距离在 48 格内、光路没有不透明方块遮挡。
- **`/cinema_projector_client` 没反应**：它是客户端命令，只作用于客户端测试场景；真实方块用 `/cinema_projector`。
- **找不到新命令**：重启客户端加载最新代码；帮助命令本身不会修改世界。
