# 高阶路径求解性能方案

> 状态：方案草案。  
> 问题：`lateReverb` 的 order-3/order-4 路径求解在 render thread 上增量执行，
> `latePathStepBudgetMillis=4` 只能把总耗时摊到更多 tick，不能消除单帧峰值。  
> 实测日志：fast order=3 累计 50–105 ms / 1–1.5 s；deep order=4 累计
> 170–511 ms / 3–8 s；每 tick 仍可能出现 4 ms 级峰值。

## 1. 为什么调预算不解决

预算只是“每次 tick 最多做多少”。总工作量不变：

- order-3/4 的镜像源组合数随阶数近似指数增长；
- 每次更新仍要访问 `ClientLevel` 做 `clip`/`traverseBlocks`（`ReflectionProbe`、
  `OcclusionScanner`、`MirrorPlaneDiscovery`），随机内存访问 + 大量临时对象；
- **听众位置**移动会重置 fast/deep session，导致反复从头求解
  （转头不会，见 §3.0）；
- **转头**虽然不重解，但每 tick 走一遍完整的“旋转 + 重建发布”流水线（热点 B，见 §8.2）；
- 降到 2 ms 只是把 4 ms 峰值变成 2 ms，并把升级时间翻倍。

要根本解决，需要 **降低总工作量**，并把 **不需要世界的计算搬离 render thread**。

## 2. 方案对比

| 方案 | 单帧峰值 | 总工作量 | 工程量 | 是否根本 |
|---|---|---|---|---|
| 继续调 step budget | 变小但仍在 | 不变 | 极低 | 否 |
| 静止门控 / 位置量化 / 降阶 | 峰值变小 | 减少 | 低 | **不采用：牺牲实时响应** |
| **转头发布零重建（世界空间 taps）** | **消除一大类峰值** | 大幅减少 | 中 | **是（且无延迟代价）** |
| **发布阶段搬 worker（纯数据）** | **≈0** | 不变 | 中 | **是（不碰世界，无延迟）** |
| **exact solve 加速（缓存/零分配/剪枝）** | 更稳定 | **减少 2–4×** | 中低 | 部分（同输出） |
| 不可变世界快照 + worker | ≈0 | 不变 | 中高 | **不采用：实时性下降** |
| beam / stochastic ray tracing | 固定预算 | 大幅减少 | 中 | 是（换算法） |
| 预计算路径图 / 逐格缓存 | ≈0 | 一次性 | 高 | 不采用：需要静态房间假设 |
| GPU 路径追踪 | ≈0 | 低 | 很高 | 过度 |

## 3. 推荐路线（保持实时世界访问，不做快照）

硬约束：

- **不降低实时性**：求解继续读当前 `ClientLevel`，不做需要失效/重建的世界快照；
- **不牺牲功能**：不做“静止才更新 / 位置量化 / 降阶 / 关闭 order-4”这类降低响应或精度
  的门控；
- 仍要消除 render thread 峰值。

在这三条约束下，只剩两类可动的杠杆：

1. **同样的结果，做更少的活**（精确加速：缓存、零分配、剪枝、更细的预算粒度）；
2. **把不需要世界的活搬到别的线程**（发布、FIR 设计、后续的 CUDA RT）。

### 3.0 事实修正：转头不重解

- `updateLatePathBus` 的重建条件只有 `listener.distanceToSqr(...)` 与 `roomVersion`，
  与朝向无关 → 转头**不会**重置 fast/deep session。
- 转头走 `updateReflectionOrientation`：把已有 taps / 反射源按新旧 listener frame 旋转，
  不跑 solver。

### 3.1 第一优先：转头不再重建发布（当前最大的非求解峰值）

`updateReflectionOrientation` 在朝向变化时**每 tick** 执行：

- `rotateTaps`：500–900 个 `Tap` 重新分配（每个带 `float[]`）；
- `rotateSources`：premium ≤24 + 普通反射 ~86 个；
- `audio.setReflections(...)`：~86 个 source 重新发布；
- `engineTaps(...)`：`WetLayout.build`（排序 + 聚类 + 每 tap VBAP）+ `setWetLayout`
  + `setLateReflectionTaps`（`List.copyOf` ~900 条 + 深比较）。

这就是**热点 B 在转头时每帧跑一次**，与 solver 无关，而且这正是“转头卡”的直接来源。

修复方向（不改变声像结果）：

1. taps 改为保存**世界空间**方向，发布时附带一个 listener frame（已实现）；
2. 反射源仍按 frame 旋转（只有 ~86 个），wet 点按新 frame 重新定位，不再触碰 tap 列表；
3. `WetLayout` 的 VBAP 权重在刚性旋转下不变，因此转头直接复用已发布权重（已实现）；
4. 若以后要彻底去掉旋转，可把 OpenAL source 改世界坐标（`AL_SOURCE_RELATIVE=false`）。

**已实现**：taps 改为世界空间；`updateReflectionOrientation` 不再 `rotateTaps`
（900 个对象）也不再重跑 `engineTaps`（WetLayout + 900 次 pan + copyOf + 深比较），
只做 ~86 个反射源旋转 + ~24 个 wet 点重定位 + 一次 `setWetLayout`。

### 3.2 第二优先：把“发布”切片（纯数据，不碰世界）

`finishFastPhase` / `finishDeepPhase` 的后续工作只依赖已经算出的 `ReflectionPathTap`
与扬声器世界坐标，**不访问 `ClientLevel`**：

- `selectPremium`、`buildPremiumSources`（FIR 设计本来就是异步 worker）；
- `busTaps`、`buildPathScatter`、`rebuildScatterTaps`、`withScatter`；
- `WetLayout.build`、`setWetLayout`、`setLateReflectionTaps`。

原计划是整体放到 `cinema-path-publish` worker。**当前实现改为按 tick 切片**
（`FastPublish` / `DeepPublish`，每 tick 1.5 ms）：

- 原因：这条流水线仍在读 `SpeakerBlockEntity`（`acousticFrontPosition()`、
  `settingsFor()`、`levelDb()`），直接把 BlockEntity 交给后台线程不安全；
  要上 worker，必须先引入不可变的 `SpeakerAcoustic` 快照并让 worker 独占
  `wetLayoutBuilder` / `premiumReflectionFilters` / `scatterRayBudget`。
- 切片版本没有线程和状态所有权问题，行为不变，旧 taps 继续播放（30 ms 淡变），
  发布总延迟 2–4 tick。
- 若后续仍要 worker：先把候选里的 `SpeakerBlockEntity` 换成
  `SpeakerAcoustic(key, channel, front, forward, profile, subwoofer, gain, delay)`，
  再把 `engineTaps` 的调用方全部改成“提交 job + 下一 tick 应用结果”。

**2026-11-24 实测后的第二刀**：

- `updateReflections` 里的同步 order-2 求解与 fast 阶段完全重复（fast 阶段本来就
  从 order 2 开始增量求解），走动时每 200 ms 触发一次 8–15 ms 峰值。现在只在
  “late bus 为空且没有在跑构建”时做一次即时 order-2 发布，之后交给 fast 阶段。
- `updateLatePathBus` 在没有新的 order-2 taps 时**不再清空** late bus，直接沿用
  上一次发布的 taps，等增量构建完成后整体替换。
- `refreshScatterTaps` 原来每次反射更新（5–10 Hz）都会因为 diffuse bed 变化而
  重发整个 ~900 tap 的 bus（重建 WetLayout + 重新 pan + 深比较）。现在限制到
  750 ms 一次；specular 更新仍按构建完成时机发布。
- 日志改为 `publishWorkMs=`（真正的工作时间，按 tick 累加）与 `publishWallMs=`
  （跨 tick 的墙钟时间），避免把切片总时长误读成单帧峰值。

### 3.3 第三优先：同线程把 solve 做便宜（输出不变）

均为精确优化，tap 数量与数值不变（已实现标 ★）：

- `ReflectionProbe.wallAt`：每次调用新建 2 个 `Vec3` + 1 个 `ClipContext`，
  每条路径每个 bounce 一次 → 可复用探针 + 内联 `traverseBlocks`；
- ★ `OcclusionScanner`：`clipAxis/clipBox` 不再分配 `double[]`，`box.move()` 的 `AABB`
  改为“把射线原点平移到方块局部空间”，复用 `double[2]`；
- ★ `AcousticMaterials.occlusionForState(state)`：用 `ConcurrentHashMap<BlockState,
  OcclusionProfile>` 缓存，避免每方块重复跑 `BlockTags` 查询；
- `wallAt` 结果在单次 solve 内按 `(planeKey, blockPos)` memo（一次 solve 内世界不变）；
- 枚举时先用镜像位置算 `pathLength`/delay 上界，低于 audibility floor 的组合直接跳过，
  不做任何世界查询（这些组合现在也会被 floor 丢掉）；
- `maxPaths` 截断时按平面强度排序枚举，保留的仍是强路径，同时更早结束；
- ★ 预算检查从 `(visits & 63)` 改为每 8 次访问，把 4 ms 的越界收紧。

未做（会改变输出）：`maxPaths` 重排序、solver 层 floor 剪枝。

预期：相同输出下 solve 总量降 2–4×，4 ms 切片更稳定，deep 升级时间明显缩短。

### 3.4 第四优先：CUDA RT 替换 order-3/4（见 §5）

RT 需要几何数据在 GPU 上，但**不需要全量快照重建**：按 chunk/region 做
**增量 dirty 上传**（只传变动的房间区域），延迟约 1 tick，且替换的是组合爆炸的
order-3/4 枚举，不是精确早期反射。

## 附录 A：快照 + worker（评估后不采用）

> 不采用原因：快照需要失效/重建策略；门、活塞、红石、爆炸、未加载区块都会让
> 快照与真实房间不一致，实时性下降；同时增加内存、延迟和“声音与房间不同步”的风险。
> 保留在此仅作技术记录。

核心思路：worker 只读 **不可变世界快照**，绝不访问 `ClientLevel`。

### A.1 快照

```java
final class AcousticWorldSnapshot {
    // 房间包围盒 + 1 block margin
    final int minX, minY, minZ;
    final int sizeX, sizeY, sizeZ;
    // 每个 cell：solid flag、材质、碰撞 AABB 列表
    final boolean[] solid;
    final AcousticMaterial[] materials;
    final List<AABB>[] boxes;
    final long roomVersion;
}
```

- 在 render thread 构建（或复用 `RoomDetector` 的扫描结果）：
  - `level.getBlockState(pos)`；
  - `state.getCollisionShape(level, pos).toAabbs()`；
  - 材质映射 `AcousticMaterial`；
  - 只保留房间 + margin 内的非空气方块。
- 构建成本：16×5×13 房间约 1–5 ms；48 m 大房间可能几十 ms。
  - 房间变化时构建；可用后台构建 + `AtomicReference` 发布。
  - 门/活塞等动态方块：监听 block update，标记快照失效。
- 内存：1 block 网格 + AABB 列表，通常 < 1 MB。

### A.2 `SnapshotWorld`

实现现有 `ImageSourceSolver.World`：

- `wallAt(plane, point)`：查快照材质，返回 `AcousticMaterial`；
- `occlusion(from, to)`：DDA 遍历快照网格 + 射线-AABB 相交，返回
  `OcclusionResult`；
- 不再调用 `BlockGetter.clip` / `traverseBlocks`。

`MirrorPlaneDiscovery` 也可以改成基于快照的 `castPlane`，或者在房间变化时
在 render thread 只跑一次并缓存 `List<MirrorPlane>`（它依赖房间几何，不依赖 listener）。

### A.3 worker

```text
cinema-path-solver (daemon, low priority)
  job = snapshot + planesBySpeaker + listener + speakerPositions + config + generation
  loop: session.step(budgetNanos)  // 可被新 generation 取消
  publish PathSolution via AtomicReference
```

- `ImageSourcePathSolver.Session` 已经支持 `step()`，可直接复用。
- worker 可以连续跑（不占 render frame）；用 `Thread.MIN_PRIORITY` 或分片 + `yield`。
- 取消：listener/room/speaker 变化时 generation++，旧 job 尽快退出。
- 发布：`PathSolution` 不可变（taps、stats、generation）；render thread 每 tick
  只检查 `AtomicReference`，匹配则应用，不做射线求解。

### A.4 render thread 只做什么

- 更新 listener/speaker 位置、generation；
- 如果 worker 有新结果：构建 `LateReflectionBus.Tap`、premium 源列表、发布到 engine；
- 继续用旧 taps，直到新结果就绪；30 ms 淡变。
- first-order taps 也可以走 worker；render thread 连一阶 `ImageSourceSolver.solve`
  都不再调用。

### A.5 调度策略

- render thread 检测 listener 静止 ≥150 ms 且位置跨过 0.25 m 网格 → 提交 job。
- 移动中不提交；旧 taps 继续使用。
- 房间变化/扬声器布局变化 → 立即重建快照并提交 job。
- worker 完成前，如有新的移动，取消旧 job，不发布旧结果。

## 4. 备选算法

### 4.1 Beam / stochastic ray tracing

这里的 ray tracing 指 **CPU 端、房间尺度的 voxel DDA / AABB BVH**，不是 GPU RTX，
也不是逐像素 path tracing。成本由 ray 数和 bounce 数决定，不随镜像源组合数爆炸。

粗略量级（6 只扬声器）：

- 每扬声器 128–256 条 ray，3 次 bounce；
- 每条 ray segment 用 1-block voxel DDA 走 10–30 格，或 BVH 遍历 ~log N；
- 总计约 2.3k–4.6k 个 segment、3–7 万次 voxel/AABB 测试。

对比当前精确枚举：日志中 order-2/3 约 1290 条路径，deep order-4 再增加数百到上千条，
每条路径还要做多段遮挡/材质查询，累计 solve 170–511 ms。  
因此 **固定 128–256 ray 的 CPU ray tracing 通常比当前 order-3/4 枚举更便宜**，
而且成本可预测、可放到 worker。

代价是方差/噪声：需要低差异采样 + 8–16 次时间累积；late bus 本来就是扩散场，
比早期反射更适合这种近似。建议保留精确一阶/二阶早期反射，只用 ray tracing 生成
order-3/4 的扩散 late taps。

- 每帧/tick 成本固定，无组合爆炸；可时间累积平滑。
- 适合 late bus 的扩散场；不需要精确镜像源枚举。
- 可直接放进 CUDA RT worker（§5），不需要快照失效机制。

**tap 密度问题**：单次 128–256 ray 的命中数确实少于精确枚举的 1290 条候选路径。
但当前 `LateReflectionBus` 本身只保留最强 **512 taps**，并按 floor/delay 过滤；
精确枚举的 1290 条候选并不是 1290 个可用 tap。ray tracing 需要：

1. **时间累积**：每 tick 只投 64–128 条 ray，累积 8–16 次，形成上千个 hit；
2. **按 (delay, direction) 分桶/聚类**：每个 bin 累加能量，映射回 512 个 bus taps；
3. **低差异序列/重要性采样**：优先覆盖听者方向、墙角、反射面；
4. 保留精确一阶/二阶早期反射，ray tracing 只负责 order-3/4 扩散尾。

这样 bus 层面的有效 tap 密度和能量分布可以保持，而不是“单帧 ray 少 → tap 少”。

如果目标就是 **完全不减少 tap 且完全精确**，现在的结论是：**不要快照**，而是按
§3.3 在 live world 上做精确加速 —— 输出不变，只是更快。

### 4.2 预计算路径图

- 房间加载时构建 mirror-plane 可见性图；listener 变化时只做图遍历/权重更新。
- 一次性构建成本较高，但运行时几乎为 0。
- 适合固定影院房间；**和快照一样有失效问题，当前不采用**。

### 4.3 逐格路径缓存

- 以 1 m 网格预计算 listener 位置的 top-N taps；运行时取最近格并插值。
- 内存约 1–2 MB（top 128 taps/格）。
- 适合“坐下看电影”的主场景；**移动时需要回退或插值，属于精度/响应折中，当前不采用**。

## 5. CUDA ray tracing（后续高阶叠加方向，已记录）

**决策**：order-3/4 的 late-field ray tracing 后续使用 **CUDA 硬件加速**；order-1/2
早期反射继续用现有 image solver（精确、稀疏、成本低）。CPU ray tracing 保留为
NVIDIA 不可用时的 fallback。

选择 CUDA 的原因：

- 后续要做 **更高阶叠加**（更多 bounce、更多 ray、更高时间累积），CPU 成本线性增长，
  GPU 可以低成本扩展；
- RTX 5070 上 512–4096 rays × 4–8 bounces 只需很小预算，10k–100k rays 的
  高阶累积也可行；
- ray tracing 天然并行，适合 compute kernel；与现有 JNA + CUDA Driver API 复用。

### 5.1 数据与 kernel

**已实现（2026-11-24，RT 核心，未接入音频路径）**：

- 内核：`docs/examples/cuda_audio/rt_trace.cu`，内嵌 PTX
  `src/main/resources/assets/cinema/audio/rt_trace.ptx`（`compute_75`，约 35 KB）；
- 生成工具：`docs/examples/cuda_audio/GenRtPtx.java`（NVRTC，离线，运行时不依赖）；
- 几何：`AcousticVoxelGrid`（1 block/格，solid + 反射幅度），客户端线程构建；
- 宿主：`CudaRayTracer`（网格上传、Fibonacci 半球/全球 ray、`rays × bounces` 记录读回，
  支持 `bounces ≤ 4` 及更高；共享 `CudaContext`，独立 module）；
- 测试：`CudaRayTracerTest`（封闭盒 4 次 bounce、能量单调、方向归一化、遮挡空网格），
  无 NVIDIA 驱动时自动跳过。**已在 RTX 5070 上通过**。

**待接入**：把 hit 转成 `LateReflectionBus.Tap`、按 `(delay, direction)` 分桶、时间累积、
以及 `cinema.audio.cudaRT` 开关与 A/B。

**2026-11-24 接入（v1）**：`-Dcinema.audio.cudaRT=true` 时，deep 阶段用 RT 替换
order-4 image solve：

- 保留 premium2/premium3 与精确 order-2/3（fast 阶段不变）；
- 每次撞墙做 NEE：对每只扬声器做 DDA 可见性 + `cos/(πr²)` 加权，只取漫反射分量
  （`reflect × scatter`），与精确镜面分账；
- RT tap 与 premium 按 `(channel, |Δdelay| < 10 ms, 夹角 < 15°)` 去重；
- RT tap 只进 late bus，不提升为独立 `ReflectionSource`；
- 网格按 room version 重建（listener + speakers 包围盒 + 8 格 margin），
  上限 400 万格；失败自动回退 image solver；
- 日志：`solver=raytrace order>=3 rays=… bounces=… connected=… taps=… maxGain=… powerDb=… grid=XxYxZ gridMs=… traceMs=… convertMs=… totalMs=…`。

**下一步**：多 pass 时间累积、独立 stream、geometry 增量上传、DSP taps 上 CUDA。

- **几何数据（增量上传，不做全量快照）**：
  - 1-block voxel DDA 网格（solid/material）或 AABB BVH；
  - 房间 + margin，只上传**变动的 chunk/region**（dirty 标记），变化下一 tick 生效；
  - 材质表、扬声器/听者位置、能量/延迟预算。
- **kernel**：
  - ray 生成（听者中心，低差异序列/重要性采样）；
  - DDA/BVH 遍历；
  - hit 着色（材质/距离/方向/遮挡）；
  - 多 bounce：kernel 内循环或每 bounce 一次 launch；
  - tap 累加：按 `(delay, direction)` 分桶，`atomicAdd` 到 512（可配置）个 bin。
- **高阶叠加**：
  - 设备端持久 bin buffer，指数衰减 + ping-pong；
  - 多 pass 累积（例如每 tick 1–2 pass，8–32 次累积）；
  - 后续可扩展到更多 bounce/更高 ray 数，不需要改 render thread。
- **输出**：
  - 读取 bin 能量/方向/延迟到 CPU，构建 `LateReflectionBus.Tap`；
  - 或直接写 device tap buffer，由音频侧读取（需要额外的同步/格式约定）。
  - double-buffer + generation，旧结果继续使用直到新结果就绪。

### 5.2 线程与 context

- 新线程 `cinema-cuda-rt`，不占 render thread。
- 与 FIR 后端共享 `CudaContext` 时，**不要用同一个全局锁把长 RT kernel 和音频 FIR 串行化**：
  - 方案 A：独立 CUDA context + 独立 stream（推荐，隔离性最好）；
  - 方案 B：共享 context，但 FIR 用高优先级 stream，RT 用低优先级 stream，只对
    device buffer 生命周期加轻量同步。
- RT 结果通过 `AtomicReference`/双缓冲发布；render thread 只应用 tap。
- geometry 更新/取消用 generation；旧 kernel 结果丢弃。

### 5.3 预算与风险

- RTX 5070：512–4096 rays × 4 bounces 预计 <1–3 ms；高阶 10k–100k rays 仍在几 ms 级。
- 主要成本是 chunk 上传、readback、GPU 与视频/渲染/FIR 竞争。
- 风险：
  - NVIDIA-only；AMD/Intel/macOS 必须回退 CPU RT 或 image solver；
  - PTX 寄存器/占用随更高阶 kernel 增长，需要多 target/调参；
  - 结果延迟、噪声/累积、上传与方块更新不同步（需 dirty 队列 + 版本号）；
  - GPU 抢占可能影响帧率，需要优先级 stream 和 ray/bounce 上限。
- 不在 CUDA RT 里做精确早期反射；它只服务 order-3/4 扩散尾。

### 5.4 日志与命名重构（已实现，2026-11-24）

重构前日志和内部命名混杂了三套历史概念：

- **阶段**：`fast` / `deep`（fast lazy tap / late lazy tap）；
- **层级**：`premium2` / `premium3`、`order2Dropped`、`first=`；
- **代数**：`upgrade`、`deepUpgrade`、`stable`。

接入 CUDA RT 后，order-3/4 不再是“阶段”或“premium 槽位”，而只是
**order = n 的某个迭代累积层**。日志现在统一成 `order=n` 口径：

1. 汇总行用 `first=…`（一阶反射数量）加 `premium(order=2)=…` /
   `premium(order=3)=…`，丢弃计数用 `dropped(order=2)=…`，不再出现
   `premium2=` / `premium3=` / `order2Dropped=`；
2. 发布行用 `publishedOrder=2→3` / `publishedOrder=2→4` 加 `solver=mirror`
   表达进度，不再出现 `fast` / `deep` / `lazy` / `stable` 阶段词；
3. RT 行：
   `solver=raytrace order>=3 rays=… bounces=… connected=… taps=… maxGain=… powerDb=… grid=XxYxZ gridMs=… traceMs=… convertMs=… totalMs=…`；
4. 计时字段统一为 `solveMs` / `publishMs`（实现上是 `publishWorkMs` /
   `publishWallMs`），另有 `buildWallMs`（从构建开始到发布完成的墙钟时间）、
   `sinceMoveMs`（听众位置上次变化至今）、`updateMs`、`gridMs` / `traceMs` /
   `convertMs` / `totalMs`；不再出现 `stable`、`upgrade`、`deepUpgrade`。
   GPU 上传目前未单独计时，含在 RT pass 的 `totalMs` 内；
5. `premium2/premium3` 配置与内部 source 列表保留，但日志只写
   `premium(order=n)`。

这是一次纯日志/术语重构，不改变声学行为；内部类名
`FastPublish` / `DeepPublish` 也保留，避免与本次改动混在一起。

## 6. 建议实施顺序

1. **转头发布零重建**：taps/反射源改世界空间 + listener frame + VBAP 桶缓存（§3.1）。
2. **发布 worker**：`cinema-path-publish` 接管 tap 构建 / scatter / WetLayout / 发布（§3.2）。
3. **exact solve 加速**：零分配遮挡、材质/墙点缓存、floor 剪枝、预算粒度（§3.3）。
4. **CUDA RT 替换 order-3/4**：增量 dirty 上传 + 时间累积 + tap 分桶（§5）。

不做：静态门控、位置量化、order-4 降阶、世界快照、逐格缓存。

## 7. 验收指标

- 转头/移动时 render thread 无 >1 ms 的路径求解或发布峰值；
- 发布延迟 ≤ 2 tick，且旧 taps 淡变无跳变；
- 与现版本 A/B：tap 数量、能量、方向、RT60 感知一致（exact 加速必须逐 tap 对齐）；
- deep order-4 升级总时间缩短（目标 ≥ 2×）；
- Spark：render thread 无 `solve`/`publish` 段，worker CPU 使用有界。

## 8. 两个真正的性能热点（2026-11-24 定位）

日志只能看到 `solveMs=` 和 `buildWallMs=`，但实际产生帧峰值的是两个不同的阶段，
两者的修复方式也完全不同。

### 8.1 热点 A：高阶 mirror solve（组合爆炸）

- 入口：`AvReceiverPlayback.updateLatePath()` → `ImageSourcePathSolver.Session.step()`。
- 每个扬声器各跑一遍：fast（order≤3，`MAX_FAST_REFLECTION_PATHS=512`）与
  deep（order=4，`MAX_DEEP_REFLECTION_PATHS=512`）。
- 候选路径数随“平面数 × 阶数”近似指数增长（日志 `paths=896`，order2=277 /
  order3=619，deep 再叠加数百条）。
- 每条路径除了镜像展开，还要通过 `ReflectionProbe.of(level)` 访问真实 `ClientLevel`
  做 `clip`/`traverseBlocks`/碰撞形状查询；这部分是随机内存访问，cache 命中差。
- 成本：fast 累计 50–105 ms，deep 累计 170–511 ms；`latePathStepBudgetMillis=4`
  只是把它切成 4 ms 的片，峰值仍在 render thread 上。
- 结构性问题：**听众位置**移动会重建 session，从头再来一遍（朝向变化不会）。

### 8.2 热点 B：把结果“升级到扬声器”（发布阶段）

这部分工作量并不小，而且有两个触发源：

- **结果发布**：`finishFastPhase` / `finishDeepPhase` 完成的那一 tick；
- **转头**：`updateReflectionOrientation` 每 tick 触发一次（无 solver，但同样跑完整流水线）。

顺序（`finishFastPhase` / `finishDeepPhase`）：

1. `selectPremium`：对全部候选排序 + 按 order 选 top-N。
2. `buildPremiumSources`：≤ 16(order2) + 8(order3) 个 `ReflectionSource`，
   每个都要 `CommonAcousticFirProfile` + 缓存查找（FIR 设计本身是异步的，
   但 profile 构造和 map 操作仍在 render thread）。
3. `busTaps`：把 500–900 条候选构造成 `LateReflectionBus.Tap`（每个 tap 带
   `float[]` 权重/方向数组）→ 大量小对象分配。
4. `buildPathScatter`（order2/3/4 各一次）+ `rebuildScatterTaps`：逐 tap 做
   材质 EQ / 散射能量合并。
5. `withScatter`：整表复制。
6. `engineTaps`：`WetLayout.build` 对全部 tap 排序、按 order 聚类
   （`nearestCluster` 两两比较）、每个 tap 做一次 VBAP pan、生成 ghost 轨道、
   分配 layout 数组。
7. `setWetLayout`：逐点复制位置数组。
8. `setLateReflectionTaps`：`List.copyOf` 900 条，再做一次**深比较**
   `next.equals(lateReflectionTargets)`（含数组内容比较）。
9. 音频线程：`lateBus.setTaps` 再排序/裁剪到 512 taps 并重建延迟线索引 ——
   这是音频线程上的峰值，可能直接造成 underrun。

**转头路径**额外多一步：`rotateTaps`（500–900 个 Tap 重新分配）+ `rotateSources`
（premium + ~86 个反射源）+ `audio.setReflections(...)`，然后再走第 6–8 步。
所以转头时是**每帧**一次完整发布。

结论：hotspot B 是“一次发布做的活太多”，不是求解算力问题；
即使 solver 留在 render thread，**发布仍然会尖峰**；反之，即使 solver 不变，
把发布改为世界空间 / 搬到 worker 就能立刻削掉这一类峰值。

### 8.3 对应的修复优先级

| 热点 | 首要修复 | 后续 |
|---|---|---|
| A 高阶 solve | exact 加速：零分配遮挡、BlockState/墙点缓存、floor 剪枝、预算粒度 | CUDA RT 替换 order-3/4（增量上传，不做全量快照） |
| B 升级/发布 | 转头零重建（世界空间 taps + listener frame + VBAP 桶缓存） | 发布搬 `cinema-path-publish` worker；结构化 tap（primitive array） |

不采用：静止门控、位置量化、order-4 降阶、世界快照、逐格缓存。

## 9. 小结：CUDA + 更高阶迭代（与第 5 节对应）

已确定的路线：

1. **order-1/2 早期反射继续用 image solver**：路径少、方向性强、精确性重要，成本低。
2. **order-3/4 late field 改用听者中心 ray tracing**：成本 ≈ `rays × bounces`，
   与镜像组合数无关；同一组 ray 可服务所有扬声器。
3. **ray tracing 用 CUDA 实现**：后期要做更高阶叠加（更多 bounce、更多 ray、
   更长时间累积），GPU 的扩展成本接近线性，CPU 会线性变贵。
4. **“更高阶”不再靠枚举**：迭代式累积——每 tick 追加 1–2 个 pass，设备端持久
   `(delay, direction)` bin buffer 做指数衰减 + ping-pong，高阶能量通过累积收敛，
   而不是把平面组合再乘一遍。
5. **tap 密度不降**：单帧 64–128 rays，时间累积 8–32 次，按
   `(delay, direction)`聚类回 512 个 bus tap；单帧 ray 少 ≠ 最终 tap 少。
6. **并发隔离**：`cinema-cuda-rt` 独立线程 + 独立 CUDA context/stream
   （或高/低优先级 stream），避免长 RT kernel 与音频 FIR 共用
   `CudaContext` 的全局锁；generation 取消 + double buffer 发布。
7. **几何上传而非快照**：只把 RT 需要的房间体素/材质按 chunk **增量 dirty 上传**
   到设备端，变化的下一个 tick 就生效；不走“全量快照 + 失效重建”的路线。
   精确 solver 的早期反射仍然直接读实时 `ClientLevel`。
8. **回退**：AMD/Intel/macOS → CPU RT 或现有 image solver；精度敏感场景仍可
   用精确 solver 校验。

预算量级（RTX 5070 Laptop，sm_120）：

- 精确 mirror solve（现状）：fast 50–105 ms + deep 170–511 ms，每次移动可能重来；
- CUDA RT：512–4096 rays × 4–8 bounces 预计 <1–3 ms/次；
  10k–100k rays 的高阶累积仍在几 ms 级。

实施顺序：**转头发布零重建 → 发布 worker → exact solve 加速 → CUDA RT 替换
order-3/4 → 更高阶累积**。
