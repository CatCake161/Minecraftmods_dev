# 第三方组件与许可

Cinema 本体代码以 **CC0 1.0** 发布（见根目录 `LICENSE`）。下面这些组件**不随模组 jar 打包**，但运行时或本地数据管线会用到；它们的许可与 Cinema 代码分开。

## 1. FFmpeg / FFprobe（外部可执行文件）

- **用途**：视频解码、媒体探测、音频解码、HDR tone mapping。
- **分发方式**：模组只通过 `ProcessBuilder` 调用用户自行安装的 `ffmpeg` / `ffprobe`。仓库和发布 jar 内都不含 FFmpeg 二进制、库或源码。
- **许可**：FFmpeg 核心是 `LGPL-2.1-or-later`；gyan.dev 的 **full build** 启用了 GPL 组件（如 x264/x265），因此该构建整体按 `GPL-3.0-or-later` 分发。用户从 gyan.dev 获取并自行遵守其许可。
- **如果未来改为随模组分发 FFmpeg**：必须重新评估 GPL/LGPL 义务，附上许可文本并提供对应源码获取方式；不要把 gyan.dev full build 直接塞进 jar。
- **兼容范围**：要求 **full build**（HDR 需要 libzimg 提供的 `zscale` / `tonemap`）；最低 **FFmpeg 6.0**。当前实测构建为 gyan.dev `2026-10-15-git-afead44165-full_build`。更低版本、非 full build、非 Windows 二进制尚未验证。`5.1.2/5.1.4/7.1.2/7.1.4` 的 layout 名称与顺序仍待单独验证。
- **参考**：
  - <https://ffmpeg.org/legal.html>
  - <https://www.gyan.dev/ffmpeg/builds/>

## 2. Spinorama / ASR 扬声器测量数据

- **用途**：为扬声器指向性 FIR 提供实测 H/V 响应与 on-axis 曲线。
- **分发方式**：模组只读取用户本地导入的 `speaker data`。转换后的 profile 和原始测量文件都不随模组打包。
- **许可**：测量数据默认 **CC BY-NC-SA 4.0**（署名、非商业、相同方式共享）。它与 Cinema 代码的 CC0 不同；CC0 不覆盖这些数据或派生 profile。
- **建议 attribution 文本**：

  > Speaker measurement data: Spinorama project (<https://www.spinorama.org/>), based on measurements published by Audio Science Review (<https://www.audiosciencereview.com/>), licensed under CC BY-NC-SA 4.0.

- **再分发注意**：如果分享转换后的 profile，必须保留署名、沿用 CC BY-NC-SA 4.0，并说明是否修改过；商业发布前需要单独确认数据许可。
- **参考**：
  - <https://www.spinorama.org/>
  - <https://creativecommons.org/licenses/by-nc-sa/4.0/>

## 3. 原生库与发布包

- 模组不打包 FFmpeg、OpenAL Soft、LWJGL 或任何 `.dll` / `.so` / `.dylib`。
- OpenAL 由 Minecraft / LWJGL 提供；运行时实际版本可在 `/cinema_acoustics` 与日志中查看（当前开发环境为 OpenAL Soft 1.23.1、LWJGL 3.3.3）。
- 当前开发构建（2026-11-14）`cinema-Alpha 0.0.1.jar` 约 **0.7 MB**，sources jar 约 **0.3 MB**。
- 发布前用 `.\gradlew build` 复核，并确认 jar 内没有 `ffmpeg*`、`*.dll`、`*.so`、`*.dylib`；如果体积明显增长，先检查是否误打包了测量数据或原生库。
