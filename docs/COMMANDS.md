# Cinema 指令使用文档

游戏内随时输入：

```text
/cinema_help
/cinema_help 4
/cinema help
```

## 1. AV 接收器与播放控制

> 媒体解码/播放状态/音画时钟归 **AV Receiver**。客户端命令只负责选择本机媒体并发送控制包；投影仪只消费接收器的视频输出，扬声器只消费音频输出。

默认媒体库为 `<game>/cinema/media/`；可在接收器 GUI 的 **Settings → Add library** 添加外部文件夹。默认库使用相对 key，外部库使用 `lib/<id>/<相对路径>`；粘贴已注册库内的绝对路径也会自动转成 key。绝对路径不会写入接收器状态或网络包。媒体列表首次打开时扫描并探测元数据，之后在当前游戏会话内缓存；**Refresh / Refresh library** 强制重新扫描。每个接收器独立播放，可同时播放多个接收器。

| 指令 | 说明 |
|---|---|
| `/cinema_play <媒体库 key 或库内路径>` | 选择视频或音乐并播放，例如 `movies/demo.mp4`、`music/song.flac`、`lib/ext1/demo.mp4`；路径可以带空格 |
| `/cinema_pause` | 暂停 / 继续 |
| `/cinema_seek <秒>` | 跳转；当前实现会在关键帧附近重启解码器 |
| `/cinema_stop` | 停止播放并释放视频纹理 |
| `/cinema_volume <-60..+6>` | 设置接收器主音量，单位为 dBFS（0 = 数字满刻度，+6 = 最大增益，-60 = 静音） |
| `/cinema_mute` | 切换静音 |
| `/cinema_receiver info` | 查看最近接收器的媒体/状态/音量（dBFS）/投影仪 |
| `/cinema_receiver_info` | 同上，`/cinema_receiver` 的兼容别名 |

```text
/cinema_play test720p30_51.mp4
/cinema_seek 0
```

命令作用于最近的 AV 接收器；服务端要求玩家距离接收器不超过 16 格。视频结束后会停在最后一帧；用 `/cinema_seek 0` 从头重播。

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
| `/cinema_projector_client throw auto` | 自动投掷比模式（按配对屏幕完整宽高/视频宽高比拟合） |
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
| `/cinema_projector throw auto` | 自动投掷比（按配对屏幕完整宽高/视频宽高比拟合，不裁切画面） |
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
- 显式配对（影院线材）：右键投影仪再右键屏幕，或反向顺序；成功后投影仪保存屏幕链接，GUI 状态显示 `linked`。显式链接允许斜向安装，但仍要求屏幕在投影仪前方且距离不超过 128 格。再次右键同一设备取消配对选择；点击不兼容设备会改为选择新设备；选择状态 60 秒超时，玩家断线时清除。
- 一块屏幕同一时间只能被一个投影仪占用；第二个投影仪尝试配对同一屏幕会被拒绝并提示先解除原连接。拆除投影仪、解除连接或投影仪改配其他屏幕时会释放屏幕；旧存档中的重复占用会在加载后自动清理，只保留一个投影仪。
- 解除连接：潜行右键设备 A，再潜行右键设备 B；只解除 A↔B 这一条连接（支持任意屏幕方块）。同一设备再次潜行右键取消解除选择；两者没有连接时提示。解除选择同样 60 秒超时/断线清除。
- 接收器不再自动配对投影仪。显式配对（影院线材）：右键接收器再右键投影仪，或反向顺序；接收器保存 `videoTarget`，投影仪保存 `receiverPos`。
- 显式配对（影院线材）：右键接收器再右键扬声器，或反向顺序；双方保存双向链接。接收器 Speakers 页显示已连接扬声器的声道角色和 `Level` 滑块，可直接调整每只扬声器的电平（-60.0..+6.0 dB），改动即保存；列表超过可视行数时用滚轮查看，右侧显示滚动条。未连接对应声道的扬声器时该声道回退虚拟 5.1。
- 空手右键投影仪打开正式 GUI：状态区显示屏幕/灯泡/AV 输入；自动/手动投掷比、自动/手动焦距、梯形、亮度、wash、Power ON/OFF、Focus/Keystone/Wash/Warmup 模拟开关都**改动即保存**（按钮立即，滑块约 200ms debounce；Close/Esc 前 flush）。手持物品时保留原版物品/方块交互，不打开 GUI。命令 `/cinema_projector[_client]` 仍可用于调试。
- AV 接收器与四种扬声器同样只有空手右键打开 GUI；手持物品时保留原版交互。AV 接收器不再向聊天栏打印状态，状态可用 `/cinema_receiver` 查看。
- 空手右键扬声器打开 Speaker Data，可在顶部编辑自定义名称（最长 32 字符，服务端同步）；接收器 Speakers 列表优先显示该名称。未命名时显示角色/箱体/坐标。列表每行还有 `Ping` 按钮，播放定位音并在方块位置生成约 8 秒粒子光柱。
- 设备命名：投影仪 GUI 顶部有 `Projector` 名称框，并可在同一行编辑当前配对屏幕的 `Screen` 名称（会写入所有连通屏幕格）；AV 接收器 GUI 顶部有 `Receiver` 名称框；扬声器名称在 Speaker Data 顶部编辑。命名用于状态行、命令诊断和跨设备识别。
- 拆掉投影仪后屏幕保持黑屏，接收器音频继续；重新放回后需要再用线材配对投影仪与接收器/屏幕。
- 拆掉接收器后屏幕立即黑屏、音频停止。
- `/time set midnight` / `/time set noon` 可测试环境光洗白。

影院设备物品：

| 物品 | ID | 当前行为 |
|---|---|---|
| AV 接收器 | `cinema:av_receiver` | 空手右键打开接收器 GUI（媒体/扬声器/信号/设置）；手持物品保留原版交互 |
| 影院座位 | `cinema:seat` | 可空手右键乘坐（潜行/下车键离开）；仅作装饰和座位，不参与声场校准或听者定位 |
| 落地式扬声器 | `cinema:floorstanding_speaker` | 两格高；空手右键打开 speaker data GUI；角色在 GUI 内选择 `NONE/FL/FR/BL/BR` |
| 书架式扬声器 | `cinema:bookshelf_speaker` | 一格高；空手右键打开 speaker data GUI；角色在 GUI 内选择 `NONE/FL/FR/BL/BR` |
| 中置扬声器 | `cinema:center_speaker` | 一格高；空手右键打开 speaker data GUI；角色在 GUI 内选择 `NONE/FC` |
| 低音炮 | `cinema:subwoofer` | 一格高；空手右键打开 speaker data GUI；角色在 GUI 内选择 `NONE/LFE` |
| 影院线材 | `cinema:cable` | 两次右键配对：投影仪↔屏幕、接收器↔投影仪、接收器↔扬声器；潜行右键两个设备解除它们之间的连接 |
| 影院遥控器 | `cinema:remote_controller` | 右键打开三栏扫描面板（AV 接收器/投影仪/扬声器），Ping 定位、Access 打开 GUI；128 格内生效；配对在接收器/投影仪 GUI 或线材中完成 |
| 扬声器朝向校准器 | `cinema:speaker_alignment_tool` | 右键扬声器 +10° 声学 yaw（面向扬声器时向玩家右侧），潜行右键 -10°；方块模型同步旋转到相同物理朝向 |

创造模式位置：接收器/座位/扬声器在 Functional Blocks，线材/遥控器/朝向校准器在 Tools & Utilities。

5.1 音频路由：接收器用线材连接的扬声器会按 FL/FR/FC/LFE/BL/BR 角色映射到实际位置，每只扬声器可在接收器 Speakers 页调整 `Level`（-60.0..+6.0 dB）；未连接对应声道的扬声器时，该声道回退虚拟 5.1。双声道/单声道媒体会自动上混为 5.1：FL/FR 保持原样，FC 取中置，LFE 取 120 Hz 低通中置，BL/BR 取左右差信号。听者始终使用玩家实际耳朵位置和朝向，不做 Seat/皇帝位或屏幕锁定的声场校正，因此玩家走出影院后空间定位仍然正确。主音量使用 dBFS（-60..+6，+6 为最大增益），静音独立。A2 起直达声距离（`1/max(r,1)`）、ISO 9613-1 空气吸收和遮挡由客户端 Java 计算，OpenAL 距离模型为 `AL_NONE`；空气吸收开关/温度/湿度在 Mod Menu。

### 调试命令

| 指令 | 说明 |
|---|---|
| `/cinema_speaker_data` | 打开 Speaker Data 的类型默认编辑器；`reload` 重新读取 profiles，`info` 打印类型默认与 on-axis 参考电平，`test <type> <h> <v> <hz>` 打印指向性采样与 on-axis EQ 增益 |
| `/cinema_audio_spike` | 运行 5.1/OpenAL 音频测试，结果写入游戏日志 |
| `/cinema_efx_spike` | A0 EFX/EAXReverb 能力测试：loopback 5.1/HRTF 湿声布局、aux send、CPU、reset；结果到聊天和日志 |
| `/cinema_acoustics` | A1/A2 调试：输出当前房间平面/尺寸/体积/表面积、材质吸收、RT60、听者和附近扬声器；另输出空气吸收配置（开关/温度/湿度/压力/earlyGain）、每扬声器距离增益、8 kHz 空气损失、遮挡 dB/截止/阶数、环境 FIR 状态/taps |

## 5. 手动 / 自动测试

手动模式：不退出、不锁定玩家。

```powershell
$video=(Resolve-Path '<测试视频>').Path
$env:JAVA_TOOL_OPTIONS="-Dcinema.spike.video=$video -Dcinema.spike.manual=true"
.\gradlew runClient --args="--quickPlaySingleplayer=spike"
```

自动模式：把 `manual=true` 换成 `exit=true`。

测试环境默认会固定时间、天气、玩家位置，并关闭动物生成；可在 Mod Menu 关闭 `spike 修改世界`。屏幕/投影仪需要自行放置。

## 6. 常见问题

- 视频停在最后一帧：`/cinema_seek 0` 重播。
- 屏幕没有画面：确认接收器正在播放、投影仪已用线材配对（`linked`）、屏幕正面朝向投影仪、距离在 128 格内、光路没有不透明方块遮挡。
- `/cinema_play` 提示找不到文件：文件必须放在默认库或 Settings 添加的外部库；参数用库内 key，或粘贴已注册库内的绝对路径。
- 每个接收器独立播放，使用独立播放器/纹理/OpenAL context；两个接收器可同时播放。若仍失败，查看日志中的 `cinema audio engine failed` 或 `cinema receiver ...`。
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
