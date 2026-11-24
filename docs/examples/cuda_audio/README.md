# CUDA FIR 原型（JNA + CUDA Driver API）

这是 `docs/design/CUDA-AUDIO-BACKEND.md` 的配套可运行原型，不参与模组构建。

它做三件事：

1. 用 JNA 加载 NVIDIA 驱动导出的 `nvcuda.dll`；
2. 用 NVRTC 在运行时把 CUDA C++ 编译成 PTX（不需要安装 CUDA Toolkit）；
3. 用 CUDA Driver API 运行 78 路 × 2048 帧 × 4096 taps 的 FIR 卷积，
   并与 CPU 参考结果比较。

## 实测结果（RTX 5070 Laptop，sm_120）

```text
device:        NVIDIA GeForce RTX 5070 Laptop GPU (sm_120)
nvrtc:         12.8
ptx size:      3314 bytes
kernel:        1.32 ms / block
H2D:           0.52 ms
D2H:           0.26 ms
round trip:    2.10 ms / block
CPU reference: 19.50 ms / block（78 个 4096-tap FIR）
max |GPU-CPU|: 0.000000
```

## 运行

需要：

- NVIDIA 驱动（提供 `nvcuda.dll`）；
- JNA 5.14+；
- 一个 NVRTC DLL。示例默认使用 PyTorch 自带的
  `nvrtc64_120_0.dll`，也可以用 CUDA Toolkit 里的 NVRTC。

PowerShell 示例：

```powershell
$jdk='C:\Users\Whuch\.jdks\openjdk-26.0.2.1'
$jna='C:\Users\Whuch\.gradle\caches\modules-2\files-2.1\net.java.dev.jna\jna\5.14.0\67bf3eaea4f0718cb376a181a629e5f88fa1c9dd\jna-5.14.0.jar'
$lib='C:\Users\Whuch\AppData\Local\Programs\Ultimate Vocal Remover\torch\lib'

$env:PATH="$lib;$env:PATH"

& "$jdk\bin\javac.exe" -cp $jna -d "$env:TEMP\cuda-demo" .\CudaFirDemo.java

& "$jdk\bin\java.exe" --enable-native-access=ALL-UNNAMED `
  -cp "$jna;$env:TEMP\cuda-demo" `
  CudaFirDemo "$lib\nvrtc64_120_0.dll"
```

- `--enable-native-access=ALL-UNNAMED` 是 JDK 24+ 的警告抑制；Java 21 不需要。
- 生产环境不建议在运行时依赖 NVRTC：把编译好的 PTX（本示例约 3.3 KB）
  作为 resource 嵌入，用 `cuModuleLoadData` 加载即可。

## 与模组集成

模组已接入直达 FIR 的 MVP：

- `DirectFirBackend` / `CpuDirectFirBackend` / `CudaDirectFirBackend`
- 默认 CPU；启用 CUDA：

  ```text
  -Dcinema.audio.cuda=true
  ```

- 内嵌 PTX：`src/main/resources/assets/cinema/audio/fir_conv.ptx`（`sm_75`，约 3.3 KB）
- 测试：`CudaDirectFirBackendTest`（无 NVIDIA 驱动时自动跳过）

完整设计见 [CUDA-AUDIO-BACKEND.md](../../design/CUDA-AUDIO-BACKEND.md)。核心结论：

- 不要把每路 `FirProcessor.process` 直接换成一次 CUDA 调用；
- 每 block 批量提交 72–78 路，一次 kernel launch；
- 保留 CPU 回退（AMD/Intel/macOS/旧驱动）；
- CUDA 不依赖 OpenGL 上下文，可在音频线程或独立 `cuda-audio` 线程运行。
