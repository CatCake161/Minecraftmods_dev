# CUDA 音频卷积后端草案

> 状态：设计草案，未接入模组。  
> 目标：把现有 `FirProcessor` 的 72–78 路 4096-tap FIR 从 Java 音频线程搬到 NVIDIA GPU，
> 保持现有调用语义，并保留 CPU 回退。  
> 实测原型：`docs/examples/cuda_audio/CudaFirDemo.java`  
> 测试机：RTX 5070 Laptop（sm_120）/ CUDA 13.4 驱动；实测 GPU 端到端约 2.1 ms/block，
> CPU 约 19.5 ms/block。

## 1. 现有调用链

`CinemaAudioEngine` 当前每路音频持有独立的 `FirProcessor`：

- 直达：`firProcessors[source]`，最多 `MAX_DIRECT_SOURCES = 64`。
- 反射：每个 `ReflectionSlot` 一个 `slot.processor`，最多 `MAX_REFLECTION_SOURCES = 116`。
- 设置滤波器：
  - `setChannelLayout()` → `firProcessors[i].setFilter(filter)`。
  - `setReflections()` / `allocateReflectionSlot()` → `slot.processor.setFilter(target.filter())`。
- 每个 2048-frame block：
  - 直达：填 `directInputScratch`，`process(...)`，立即 peak/OpenAL 上传。
  - 反射：填 `reflectionInputScratch`，`process(...)`，立即包络/peak/OpenAL 上传。
- `reset()`：seek/close 时清空卷积 tail。

问题在于：**每路一个 `process` 调用**。如果在 CUDA 上照搬，每个 block 会产生
72–78 次 kernel launch 和 100+ 次小拷贝，驱动开销会吃掉大部分收益。

## 2. 建议接口

保持“每路一个 handle”的写法，但把真正执行改成 per-block 批量：

```java
/** One backend per audio engine. */
public interface FirBackend extends AutoCloseable {
    /** Stable handle for one direct source or reflection slot. */
    FirStream stream(int streamId);

    /** Runs every submitted stream for this block. */
    void processBlock(int frames);

    void reset();

    @Override
    void close();
}

/** One logical FIR stream. */
public interface FirStream {
    void setFilter(MinimumPhaseFir filter);

    /** Copies/queues one block of mono input; output is filled by processBlock. */
    void submit(float[] input, int inputOffset, int frames,
                float[] output, int outputOffset);

    void reset();
}
```

- `CpuFirBackend`：`submit` 直接调用现有 `FirProcessor.process`，`processBlock` 为空。
- `CudaFirBackend`：`submit` 把输入复制到 pinned staging 并记录 job；
  `processBlock` 一次上传全部输入、一次 kernel launch、一次回读、再 scatter 到各 `output`。
- 这样 `setFilter` / `reset` / 每个 source 一个 handle 的语义都不变。

## 3. Stream ID 映射

| 范围 | 用途 | 数量 |
|---|---|---|
| `0 .. 63` | direct source | 64 |
| `64 .. 179` | reflection slot | 116 |
| 合计 | 后端容量 | 180 |

每 block 实际活跃的通常为 **72–78 路**（6 direct + 66–72 reflection）。
未提交的 stream 不参与本次 kernel。

## 4. CUDA 实现

### 4.1 运行时与线程

- JNA + CUDA Driver API（`nvcuda.dll` / `libcuda.so.1`），不依赖 CUDA Toolkit。
- 启动时 `cuInit`、选设备、`cuCtxCreate_v2`、`cuModuleLoadData`（内嵌 PTX）。
- PTX 在构建时用 CUDA 13.x 编译；也可保留 `compute_90` PTX 由新驱动 JIT。
- **CUDA 不依赖 OpenGL 上下文**：可以在音频线程或独立的 `cuda-audio` 线程调用，
  不需要渲染线程参与。一个 context 只在一个线程上使用；不要从多个线程同时调用。
- 首次 CUDA 调用会唤醒 dGPU，建议在客户端启动/进入世界时预热，避免第一次播放卡顿。

### 4.2 Device 缓冲

按后端容量预分配，避免每 block 重新分配：

| 缓冲 | 大小（180 路） | 说明 |
|---|---|---|
| `dInput` | 180 × (2048 + 4096 − 1) × 4 B ≈ **4.4 MB** | 每路带历史长度 |
| `dTaps` | 180 × 4096 × 4 B ≈ **2.95 MB** | 当前滤波器 |
| `dTapsOld` | 同上 ≈ 2.95 MB | 交叉淡化用 |
| `dOutput` | 180 × 2048 × 4 B ≈ **1.47 MB** | 每路输出 |
| pinned staging | 输入+输出约 6 MB | `cuMemHostAlloc` |

### 4.3 Kernel

第一版直接卷积即可：

```cuda
extern "C" __global__ void fir_conv(
    const float* __restrict__ input,
    const float* __restrict__ taps,
    float* __restrict__ output,
    int frames, int tapCount)
{
    int stream = blockIdx.x;
    int n = blockIdx.y * blockDim.x + threadIdx.x;
    if (n >= frames) return;

    int inputLength = frames + tapCount - 1;
    const float* x = input + (long)stream * inputLength + (tapCount - 1);
    const float* h = taps + (long)stream * tapCount;
    float* y = output + (long)stream * frames;

    float acc = 0.0f;
    #pragma unroll 4
    for (int k = 0; k < tapCount; k++) {
        acc = fmaf(x[n - k], h[k], acc);
    }
    y[n] = acc;
}
```

- grid：`(activeStreams, ceil(frames / 256))`。
- 如果 active stream id 不连续，额外上传一个 `int* activeIds`，kernel 用
  `int stream = activeIds[blockIdx.x];`，grid 只覆盖本次实际提交的 stream。
- 进一步优化：shared memory 缓存 `h`、每线程 4 个输出复用 `h[k]`、或 FFT 卷积。
- 实测朴素版本 kernel 约 **1.3 ms/block**；GPU 仍只用了约 5% FP32。

### 4.4 滤波器上传

- `MinimumPhaseFir` 对象由 `CommonAcousticFirService` 缓存复用，identity 稳定。
- 用 `IdentityHashMap<MinimumPhaseFir, Integer>` 分配 filter slot；新对象才上传 taps。
- `setFilter` 只标记 dirty；`processBlock` 开始时统一上传 dirty taps。
- 交叉淡化：保留 `dTapsOld` + `fadeRemaining`。kernel 在 fade 窗口内同时算新旧两路并线性混合；
  21 ms 的额外计算对 GPU 可忽略。

### 4.5 异步管线

v1 可以同步：

1. 上传所有 active stream 的输入（`cuMemcpyHtoD_v2`）。
2. `cuLaunchKernel`。
3. `cuCtxSynchronize` / event wait。
4. `cuMemcpyDtoH_v2` 下载输出。
5. scatter 到各 `float[] output`。

实测 round trip **2.1 ms/block**，已足够。

v2 用双缓冲：

- staging A/B + 两条 stream 或一条 stream + event。
- `processBlock(N)` 上传 A、启动 kernel A；同时等待/回读上一块 B。
- 目标吞吐 **1.3–1.6 ms/block**。
- 生产路径不要在音频线程 `cuCtxSynchronize` 等整卡；只等自己的 event。
  v1 的同步 `cuCtxSynchronize` 只用于原型/回退验证。

## 5. 引擎集成 diff 草案

### 5.1 字段

```java
// 现在
private final FirProcessor[] firProcessors = new FirProcessor[MAX_DIRECT_SOURCES];

// 改为
private final FirBackend firBackend;
private final FirStream[] directStreams = new FirStream[MAX_DIRECT_SOURCES];
private final FirStream[] reflectionStreams = new FirStream[MAX_REFLECTION_SOURCES];
private final float[][] directOutputScratch =
    new float[MAX_DIRECT_SOURCES][BLOCK_FRAMES];
```

`FirBackend` 由 `CinemaAudioEngine` 构造时创建；`CudaFirBackend` 不可用时返回 `CpuFirBackend`。

### 5.2 设置滤波器

```java
// setChannelLayout
directStreams[i].setFilter(filter == null ? MinimumPhaseFir.FLAT : filter);

// allocateReflectionSlot
slot.stream.setFilter(target.filter());
```

### 5.3 queueBlock 改成三阶段

```java
// Phase A: direct inputs
for (int source = 0; source < directSourceCount; source++) {
    fillDirectInput(source, frames); // 写入 directInputScratch
    directStreams[source].submit(directInputScratch, 0, frames,
        directOutputScratch[source], 0);
}

// Phase B: reflection inputs
for (ReflectionSlot slot : reflectionSlots) {
    if (slot != null) {
        fillReflectionInput(slot, frames);
        slot.stream.submit(reflectionInputScratch, 0, frames,
            slot.outputScratch, 0);
    }
}

// Phase C: one batch
firBackend.processBlock(frames);

// Phase D: peak + OpenAL upload
for (int source = 0; source < directSourceCount; source++) {
    float[] out = directOutputScratch[source];
    directPeakHistory[source][directPeakIndex] =
        OutputLimiter.blockPeak(out, frames, !float32Buffers);
    uploadAndQueue(directSources[source], out, frames);
}
for (ReflectionSlot slot : reflectionSlots) {
    if (slot != null) {
        applyReflectionEnvelope(slot, slot.outputScratch, frames);
        slot.pushPeak(...);
        uploadAndQueue(slot.source, slot.outputScratch, frames);
    }
}
```

关键点：`submit` 只记录 job，不保证 `output` 立即可读；所有读取必须放在 `processBlock` 之后。
因此每个 source/slot 需要自己的输出 scratch，不能共用 `blockScratch`。

### 5.4 reset / close

- `reset()`：`firBackend.reset()`（清空所有 tail、fade 状态）。
- `close()`：释放 device/pinned 缓冲、`cuCtxDestroy_v2`。
- 设备丢失/驱动错误：标记 `available=false`，下一次 `processBlock` 走 `CpuFirBackend`。

## 6. 最小可用版本（MVP）

建议分四步落地，避免一次性改太大：

1. **只做直达 FIR**：6 路，固定 2048 frames / 4096 taps，同步 round-trip。
   - 保留 `CpuFirBackend` 作为默认；用配置项强制打开 CUDA 做 A/B。
   - 先验证输出与 CPU 完全一致、无 click。
2. **加入反射 FIR**：66–72 路，批量 kernel，per-stream history。
3. **加入 filter 切换/交叉淡化**：CPU 端先只换 filter、GPU 端双 taps 混合。
4. **最后做异步双缓冲**：把 round-trip 从 2.1 ms 压到 1.3–1.6 ms/block。

late bus、HRTF 混音不放进 MVP；它们可以后续单独评估。

## 7. 回退策略

```java
FirBackend chooseBackend() {
    if (!config.cudaAudioEnabled()) return new CpuFirBackend();
    try {
        CudaFirBackend cuda = CudaFirBackend.create();
        return cuda != null ? cuda : new CpuFirBackend();
    } catch (Throwable t) {
        CinemaLog.warn(CinemaLog.AUDIO, "CUDA backend unavailable: {}", t.toString());
        return new CpuFirBackend();
    }
}
```

必须覆盖：

- 非 NVIDIA / 无 `nvcuda.dll`。
- `cuInit` 失败、设备不支持、PTX 加载失败。
- kernel launch / memcpy 超时。
- 多接收器时 device 内存不足（可降级到 CPU 或共享一个 backend）。

## 8. 性能预算

| 项目 | 当前 CPU | CUDA v1（同步） | CUDA v2（异步） |
|---|---:|---:|---:|
| 72–78 × 4096-tap FIR | 18–19.5 ms/block | 1.3 ms kernel | 1.3 ms kernel |
| H2D + D2H | — | 0.8 ms | 与 kernel 重叠 |
| 端到端 | 18–19.5 ms | **2.1 ms** | **约 1.5 ms** |
| 节省单核 | — | ~0.4 核 | ~0.45 核 |
| GPU 占用 | — | 1–3% | 1–3% |

## 9. 测试计划

- 数值：impulse、silence、随机输入、滤波切换、reset 后 tail 清零。
- 批量：1 / 6 / 78 路；frames = 1 / 2048 / 4096。
- 交叉淡化：切换前后无 click，输出与 CPU 版本误差 < -80 dBFS。
- 真实播放：5.1 + 反射 + late bus + 1080p 视频；Spark 看 `cinema-audio` 和 GPU。
- 回退：强制 `nvcuda` 不可用，确认 CPU 路径输出一致。
- 双接收器：两个 backend/context，device 内存和 source 预算正常。

## 10. MVP 接入状态（2026-11-23）

已接入直达 FIR 的最小可用版本：

- 新增 `DirectFirBackend` / `CpuDirectFirBackend` / `CudaDirectFirBackend`。
- `CinemaAudioEngine` 的 6 路直达 FIR 已改成“提交 → `endBlock()` → 读取输出”的批量流程；
  反射 FIR、late bus 不变。
- 默认仍是 CPU。启用 CUDA：

  ```text
  -Dcinema.audio.cuda=true
  ```

  没有 NVIDIA 驱动、PTX 加载失败或 `cuInit` 失败时自动回退 CPU。
- **共享 context（2026-11-23）**：`CudaContext` 进程级单例保存 context/module/function，
  客户端启动时由 `cinema-cuda-prewarm` 后台线程创建一次；每个 `CudaFirBackend` 只分配自己的
  device 缓冲区。play/stop 不再重建 context（实测缓冲区分配 0–1 ms/次）。GPU 调用由全局锁串行化，
  多个接收器可同时播放。
- 内嵌 PTX：`src/main/resources/assets/cinema/audio/fir_conv.ptx`（`sm_75`，约 5.8 KB，
  含 1024 帧交叉淡化）。
- 测试：`CudaFirBackendTest` 验证首个 block、第二个 block（含卷积 tail）、12 路批量和
  交叉淡化；无 CUDA 时自动跳过。
- 已知限制：
  - 共享 context 已实现；play/stop 不再重建 context。
  - 交叉淡化已实现（1024 帧，与 CPU `FirProcessor` 一致）。
  - 直达 + 反射 FIR 均已批量到一次 kernel launch。
  - 每个引擎仍分配自己的 device 缓冲区（约 12 MB），关闭时释放。
  - 高阶路径求解仍在 render thread；`latePathStepBudgetMillis=4` 时每 tick 可能有 4 ms 峰值。
  - 后续 order-3/4 late-field ray tracing 计划使用 **CUDA 硬件加速**（更高阶叠加、
    更多 rays/bounces、时间累积），order-1/2 早期反射仍用 image solver。详见
    [LATE-PATH-SOLVER-PERFORMANCE.md](LATE-PATH-SOLVER-PERFORMANCE.md)。

## 11. 未决问题

1. 交叉淡化放 GPU 还是保留 CPU 短淡化？
2. 异步管线深度：1 块还是 2 块在飞？音频/视频同步是否要补偿。
3. 多 GPU / Optimus：固定选 device 0，还是按 `cuDeviceGetAttribute` 选独显。
4. 省电策略：仅插电启用，还是由用户显式开启。
5. 是否同时把 late bus 搬到 CUDA（收益约 0.1 核，kernel 更复杂）。
6. 公开模组是否只做 NVIDIA 可选后端，还是优先 OpenGL compute / OpenCL 跨厂商路径。
