# A3-S1 散射 + A3-D1 late tap 材质滤波实施规划

> 状态：**S1a + S1b + A3-D1 + S2a + S2b + 动态 ray 预算 + 频率相关散射 + JSON 材质表 + wet 动态点布局（物理扬声器 + 反射 ghost）已实现（2026-11-21）**，`.\gradlew.bat check`（JDK 26）通过；**游戏内听感验收待做**。
> 关联任务：[TODO.md](../TODO.md) 的 A3-S1 / A3-D1；声学算法背景见 [AUDIO-ACOUSTICS.md](AUDIO-ACOUSTICS.md) §7.3 / §7.5；wet 动态点细节见 [A3-WET-GHOST-LAYOUT.md](A3-WET-GHOST-LAYOUT.md)。
>
> 本文描述做法、顺序、验收和风险。S1a/S1b/D1/S2a/频率相关散射的代码已落地，但最终听感、默认值仍以游戏内 A/B 为准。

## 0. 结论先行

1. **A3-S1 先做“能量分流”**：每个反射点的镜面压力系数乘 `sqrt(1 - scattering)`，被散射掉的能量进入 late bus 的扩散发送；不生成随机 diffuse ray，不增加 per-tap OpenAL source。
2. **A3-S1 分两步**：
   - **S1a**：只改一阶反射，先验证材质散射差别和扩散发送。二阶及以上暂不改，避免“镜面被削弱、散射又没渲染”的能量缺口。
   - **S1b**：把同一模型推广到 order 2/3/4，散射能量合并进同一组扩散 tap。
3. **A3-D1 随后做**：把 late bus 现在的单个 one-pole damping 换成“距离 one-pole + 三小段 PEQ（low shelf / mid peak / high shelf）”。PEQ 复用 `MaterialReflectionCurve` 的低/中/高三带目标，tap 增益继续以中频为基准。
4. **S2a 增加一阶半球 diffuse tap，但不是完整路径追踪**：每个一阶散射 tap 用固定的 cosine-weighted hemisphere 方向生成 2 个 `diffuse:` tap，与 `scatter:` 聚合各占 50% 能量；不做高阶随机射线、不做衍射。**频率相关散射已扩展为 `scatteringLow/Mid/High`**，镜面/散射两侧都用逐频带材料曲线。
5. **已确认：S1a 期间散射只在 `lateReverb=true` 时启用**。`lateReverb=false` 时完全旁路，保持当前镜面反射和默认配置零回归。
6. **已确认：A3-D1 的 PEQ 使用 `lateMaterialEq` A/B 开关，开发/验收期默认关闭**（保持当前 one-pole）；验收通过后再决定是否默认开启。

## 1. 实现前盘点（S1a 之前）

> 下面是 S1a 落地前的状态；S1a 的实际改动见 §3.7。A3-D1 的“late bus 只有 one-pole”仍然是当前状态。

### 1.1 已经有什么

| 位置 | 当前行为 | 与散射/材质滤波的关系 |
|---|---|---|
| `AcousticMaterial` | 已有 `low/mid/high/scattering` 四个值 | `scattering` 目前完全未使用 |
| `AcousticMaterials` | 已扩展为每材质 `scatteringLow/Mid/High`（见 §2.5 表） | 频率相关数据已接入；仍是工程估计，不是 Minecraft 实测 |
| `ImageSourceSolver.reflectionGain()` | `sqrt(1 - alpha_mid) / max(1 m, L) × occlusion` | 只有中频吸收，没有散射 |
| `ReflectionTap` | 保存单次材质、几何、宽带遮挡；`reflectionCoefficient()` 只返回 `sqrt(1 - mid)` | 没有散射分量 |
| `ImageSourcePathSolver.buildTap()` | 每个 bounce 乘 `sqrt(1 - alpha_mid)`，再乘 `1/max(1 m, L)` 和遮挡 | 没有散射；阶数越高越暗，但没有扩散补偿 |
| `ReflectionPathTap` | 保存完整 `materials` 列表；`reflectionCoefficient()` 返回各 bounce 的 `sqrt(1-mid)` 乘积 | 没有散射分量 |
| `CommonAcousticFirDesigner` / `MaterialReflectionCurve` | 一阶和 premium order-2/3 使用 4096-tap FIR，材质形状由低架/峰值/高架三带目标生成 | 只作用在 per-tap FIR；premium 高阶目前只传**第一个** bounce 的材质 |
| `LateReflectionBus` | 每个 tap 保存 `delay/gain/dampingHz/direction/VBAP/key`；运行时分数延迟 + 单个 one-pole 低通 + 增益 | **没有低/中/高三带滤波**；`dampingHz = 12000 - 6000·mean(high) - 150·distance`，材质高频和距离混在一个 one-pole 里 |
| `Vbap` | 零方向会走 diffuse fallback，输出非 LFE 扬声器的等权/近似等权分布 | 可直接用于“无方向扩散发送” |
| `ClientAcousticsCommand` | 输出房间/材质/路径信息；高阶路径文字仍是 “specular only, no scattering” | 需要补散射/滤波诊断 |

### 1.2 结论

- **散射**：数据有，算法没有。
- **late tap 材质低/中/高滤波**：**没有做**。当前 lazy late bus 只有一个 one-pole；low/mid/high 只存在于一阶/premium 的 FIR。
- **premium 高阶材质形状**：只取第一个 bounce，严格说也不完整。A3-D1 顺手对齐为所有 bounce 的合成材质。

## 2. 物理模型

### 2.1 符号

对一次反射：

- `alpha_b`：频带 `b` 的能量吸声系数，`0..1`。
- `s_b`：频带 `b` 的散射能量比例，`0..1`。当前 `b = low/mid/high`，由 `scatteringLow/Mid/High` 提供。
- `L`：路径长度（米）。
- `occ`：该路径的宽带遮挡幅度（`OcclusionResult.linearGain()`）。
- 距离项：`D = 1 / max(1 m, L)`，与现有直达/反射规则一致。

能量在反射点分成三部分：

```text
入射能量        = 1
吸收能量        = alpha
镜面剩余能量    = (1 - alpha) · (1 - s)
散射剩余能量    = (1 - alpha) · s
镜面² + 散射²   = 1 - alpha
```

对应压力幅度：

```text
specular = sqrt((1 - alpha) · (1 - s))
scattered = sqrt((1 - alpha) · s)
```

### 2.2 一阶

```text
gain_specular = sqrt((1 - alpha_mid) · (1 - s)) / max(1 m, L) × occ
gain_scattered = sqrt((1 - alpha_mid) · s)       / max(1 m, L) × occ
```

- `s = 0` 时 `gain_specular` 与现在完全一致，`gain_scattered = 0`。
- `s = 1` 时镜面 tap 消失，全部能量进入扩散发送。
- 能量：`gain_specular² + gain_scattered² = (1 - alpha_mid) / max(1,L)² × occ²`，等于当前吸收后的入射能量。

### 2.3 第 N 阶

对 bounce `i = 1..N`，令：

```text
A       = Π (1 - alpha_i)          // 吸收后剩余能量
P       = Π (1 - s_i)              // 一次都没被散射的概率
spec²   = A · P
scat²   = A · (1 - P)
```

因此压力幅度：

```text
specular  = Π sqrt((1 - alpha_i) · (1 - s_i))
scattered = Π sqrt(1 - alpha_i) × sqrt(1 - Π(1 - s_i))
```

再乘 `1/max(1 m, L)` 和 `occ`。

- `s_i = 0`：`scattered = 0`，`specular` 与当前一致。
- 任一 `s_i = 1`：`specular = 0`，全部进入扩散。
- `spec² + scat² = Π(1 - alpha_i)`，与入射能量守恒（不含遮挡）。

> 这是“把多次散射合并成一个扩散事件”的工程近似，不是逐次随机射线。它的好处是能量守恒、成本极低；缺点是丢失散射的到达方向和时间细节。真正的 diffuse ray 属于后续 S2。

### 2.4 适用范围

- `s` 现在按低/中/高三个八度带分别计算；一阶/高阶的镜面与散射都按频带合成有效材料。
- 吸收仍只用 `mid` 决定 tap 的宽带增益；低/高形状由 A3-D1 的三带 PEQ 处理。
- 不做衍射/边缘绕射；不做随机相位；S2a 只做一阶反射点的余弦半球方向，不做完整的“散射后再命中墙面”的路径追踪。
- 遮挡 `occ` 仍只作用一次，镜面和散射各自乘同一个宽带遮挡；不额外做遮挡低通。

### 2.5 散射系数表

当前 `AcousticMaterials` 使用低/中/高三个八度带的能量散射系数：

| 材质 | low | mid | high |
|---|---:|---:|---:|
| screen | 0.06 | 0.10 | 0.14 |
| fabric | 0.15 | 0.35 | 0.45 |
| carpet | 0.15 | 0.35 | 0.45 |
| wood | 0.06 | 0.15 | 0.24 |
| stone | 0.04 | 0.10 | 0.16 |
| glass | 0.02 | 0.05 | 0.08 |
| vegetation | 0.20 | 0.40 | 0.55 |
| water | 0.00 | 0.00 | 0.01 |
| snow | 0.12 | 0.30 | 0.40 |
| porous | 0.12 | 0.30 | 0.40 |
| metal | 0.02 | 0.05 | 0.08 |
| default | 0.05 | 0.10 | 0.15 |

这些值的定位：

- **不是 Minecraft 方块实测数据**，而是按 ISO 17497-1 的“随机入射散射系数 0..1”语义做的工程估计；
- 光滑刚性表面（glass/metal/water）低散射；
- 粗糙/多孔/纤维表面（stone/wood/fabric/carpet/vegetation/snow/porous）中高频散射更高；
- 高频散射通常高于低频，因为波长更短、表面起伏的相对尺度更大；
- 房间检测仍会把 9×9 材质样本和合并 patch 做平均，所以最终表面系数是采样平均值；
- 当前代码不声称这些值有测量精度。

**外部 JSON（已实现）**：系数表位于：

```text
<game>/config/cinema-acoustic-materials.json
```

首次启动时从内置默认表生成。格式：

```json
{
  "version": 1,
  "materials": {
    "stone": {
      "low": 0.015,
      "mid": 0.05,
      "high": 0.09,
      "scatteringLow": 0.04,
      "scatteringMid": 0.10,
      "scatteringHigh": 0.16
    }
  }
}
```

- 只写需要覆盖的材质；缺失 key 保留内置默认值。
- 非法 JSON/缺失 `materials` 时保留/恢复内置表并记录日志。
- 修改后运行：

```text
/cinema_acoustics reloadmaterials
```

会重新读取 JSON、清空材质 filter 缓存并使 `AcousticScene` 失效；不需要重启游戏。
- 内置表仍保留在 `AcousticMaterials`，供首次生成、测试和 JSON 缺项回退使用。

## 3. A3-S1 具体设计

### 3.1 数据模型

#### 3.1.1 `ReflectionTap`

增加一个分量：

```java
double scatteredGain
```

含义：与 `gain` 同一数字域、同一距离/遮挡规则，但压力系数用 `sqrt((1-alpha_mid)·s)` 而不是 `sqrt((1-alpha_mid)·(1-s))`。

- 保留现有构造器重载，旧构造器把 `scatteredGain` 设为 `0.0`，避免一次性改爆测试和命令。
- `reflectionCoefficient()` 改为返回镜面系数；新增 `scatteredCoefficient()`。
- 新增 `specularMaterial()` / `scatteredMaterial()`：把逐频带散射合成为有效材料，分别给镜面 FIR/PEQ 和扩散 PEQ 使用。
- Javadoc 明确 `gain` 是镜面增益，`scatteredGain` 是扩散发送增益。

#### 3.1.2 `ReflectionPathTap`

同样增加：

```java
double scatteredGain
```

- `reflectionCoefficient()` 返回镜面乘积；新增 `scatteredCoefficient()`。
- 旧构造器默认 `0.0`。

#### 3.1.3 新工具类 `AcousticScattering`

建议放在 `client/media/audio/acoustics/path/AcousticScattering.java`，纯函数、可单测：

```java
public final class AcousticScattering {
    public static double specularPressure(double alpha, double scattering);
    public static double scatteredPressure(double alpha, double scattering);
    public static double specularPressure(List<AcousticMaterial> materials);
    public static double scatteredPressure(List<AcousticMaterial> materials);
}
```

约束：

- 所有输入 clamp 到 `0..1`；非有限值按 0 处理。
- `specularPressure` / `scatteredPressure` 返回压力幅度，不包含距离和遮挡。
- 空列表：`specularPressure = 1.0`、`scatteredPressure = 0.0`（无反射点时既不凭空产生镜面增益，也不产生扩散能量；调用方仍应跳过空路径）。

### 3.2 求解器改动

#### 3.2.1 `ImageSourceSolver`

新增带开关的重载：

```java
public static List<ReflectionTap> solve(..., boolean scatteringEnabled)
```

- `scatteringEnabled=false`：走旧公式，`scatteredGain=0`。
- `scatteringEnabled=true`：

```java
double reflection = AcousticScattering.specularPressure(material.mid(), material.scattering());
double scattered  = AcousticScattering.scatteredPressure(material.mid(), material.scattering());
double distance   = 1.0 / Math.max(1.0, AcousticScale.blocksToMeters(pathLength));
double gain       = reflection * distance * occlusion.linearGain();
double scatter    = scattered  * distance * occlusion.linearGain();
```

- 保留 `reflectionGain(material, pathLength)`（无散射兼容入口），新增 `reflectionGain(..., boolean scatteringEnabled)` 与 `scatteredGain(material, pathLength)`；内部调用 `AcousticScattering`。
- `strongest()` 的镜面 source 排序仍按 `gain`；散射能量不决定方向性 source 的优先级。
- **但 solver 的 per-speaker cap 不能按纯镜面 `gain` 丢 tap**：`s` 很大时镜面 `gain` 可能接近 0，而散射能量仍然很大。`ImageSourceSolver.solve` 的 cap 排序改为 `max(gain, scatteredGain)`（或保留一份 pre-cap 列表给聚合器），确保高散射 tap 不会在聚合前被丢掉；`selectFirstOrderTaps()` 仍只按镜面 `gain` 选方向性 tap。
- `selectFirstOrderTaps()` 之后创建 source 时增加 `tap.gain() > 0.0` 检查，避免 `s=1` 的零增益 tap 占用反射池。

#### 3.2.2 `ImageSourcePathSolver`

- `Session` 增加 `scatteringEnabled` 参数（旧构造器默认 `false`）。
- `buildTap()` 内：

```java
double specular = AcousticScattering.specularPressure(materials);
double scattered = AcousticScattering.scatteredPressure(materials);
double distance = 1.0 / Math.max(1.0, AcousticScale.blocksToMeters(pathLength));
double gain = specular * distance * occlusion.linearGain();
double scatterGain = scattered * distance * occlusion.linearGain();
```

- 路径筛选、延迟预算、`pathKey` 不变。
- `ImageSourcePathSolver.solve(...)` 的公开重载增加开关版本；诊断命令默认关闭。
- path cap 仍按现有路径数/visit 预算；如果 S1b 发现高散射路径被 cap 丢掉，可把 cap 的排序键改为 `max(gain, scatteredGain)`，但不要为此无限放大 visit 预算。

#### 3.2.3 `AvReceiverPlayback` 传参

- 一阶：`scatteringEnabled = config.lateReverb()`（S1a 的推荐策略）。
- 二阶/三阶/四阶：S1a 传 `false`；S1b 改成同一个 `scatteringEnabled`。
- 这样默认配置（`lateReverb=false`）完全保持现有声音。

### 3.3 扩散发送

#### 3.3.1 目标

把当前更新周期内所有被散射的能量，按 5.1 源声道聚合成少量无方向 tap，交给现有 `LateReflectionBus`。不新建 OpenAL source，不新建 FIR。

#### 3.3.2 聚合规则

对每个源声道 `c`（0..5）维护一个累加器：

```text
E_c        = Σ e_i
delay_c    = Σ (e_i · delay_i) / E_c
damping_c  = Σ (e_i · cutoff_i) / E_c
alpha_b_c  = Σ (e_i · alpha_b_i) / E_c     // b = low/mid/high，供 A3-D1 用
```

其中：

```text
e_i = (scatterGain_i × speakerGain_i × overallGain_i)²
```

- `scatterGain_i` 来自 `ReflectionTap` / `ReflectionPathTap`，已含距离和遮挡。
- `speakerGain_i` 与现有反射/late tap 一样，来自扬声器 Level/trim。
- `overallGain_i` 来自 `CommonAcousticFirDesigner.overallDb(profile)`，与 premium/late tap 的增益域一致。
- `cutoff_i` 先用现有 `12000 - 6000·mean(high) - 150·distance`；A3-D1 改成距离 one-pole + PEQ 后，聚合器改为保存三带材质。
- 只接受通过同一 `lateFloor` / `maxDelay` 检查的贡献；被 floor/延迟丢弃的计入诊断计数。
- `E_c = 0` 时不生成 tap。

生成：

```text
Tap(
  channel      = c,
  delay        = delay_c,
  gain         = sqrt(E_c),
  dampingHz    = damping_c,
  direction    = {0, 0, 0},                 // 触发 Vbap diffuse fallback
  output       = Vbap.pan({0,0,0}, physicalSpeakerPositions, physicalSpeakerChannels),
  key          = "scatter:" + c
)
```

- `direction` 必须是零向量；不要伪造一个方向。
- `Vbap.pan` 的 diffuse fallback 会排除 LFE 输出；LFE 作为源声道仍可参与，和现有 late bus 一致。
- 每个接收器最多 **6 个**扩散 tap（每个源声道一个）。如果以后发现时间细节不够，再按延迟分桶，不改变接口。
- `key` 固定为 `scatter:<channel>`，这样 `LateReflectionBus.setTaps` 的稳定 key 机制会保留状态/淡变，不会每 200 ms 重新淡入。
- `gain` 是幅度；`LateReflectionAudibility.audible` 仍用 `gain²` 与 `lateBusFloorPower` 比较。
- 扩散 tap 必须和普通 late tap 一样经过 `engine.setLateReflectionTaps(..., maxDelay, floor)` 的二次边界检查。

#### 3.3.3 合并进每次发布

当前 late bus 有多个发布点：

- `updateReflections()` 首次发布 order-2；
- `finishFastPhase()` 发布 order-2/3 稳态；
- `finishDeepPhase()` 发布 order-2/3/4 稳态；
- reset/seek/停止时清空。

计划：

1. 在 `AvReceiverPlayback` 增加一个 `scatterTaps` 字段（`List<LateReflectionBus.Tap>`），由当前更新周期的散射聚合器生成。
2. 增加 `mergeScatterTaps(List<Tap> base)`：先移除 `base` 中 key 以 `scatter:` 开头的旧项，再追加最新 `scatterTaps`，保证每次发布只有一份。
3. 所有 `currentLateTaps = ...` / `engineTaps(...)` 的发布路径都经过 `mergeScatterTaps`。
4. `lateReverb=false` 时 `scatterTaps=List.of()`，并清空 bus；不保留残响。
5. `seek`/`reset`/`device rebuild` 清空聚合器和 `scatterTaps`，与现有 bus 清空一致。

#### 3.3.4 S1a 与 S1b 的范围

- **S1a（已实现）**：一阶 `scatterGain` 聚合成 `firstOrderScatter`；`lateReverb=false` 或 `scatteringEnabled=false` 时为空。
- **S1b（已实现）**：order-2/3/4 也启用散射。`order2Scatter` 在 order-2 解算后生成，`order3Scatter` 在 fast phase 完成时生成，`order4Scatter` 在 deep phase 逐条路径生成；`rebuildScatterTaps()` 把四组贡献合并成同一组 `scatter:<channel>` 扩散 tap，再由 wet 动态点布局投到物理扬声器。
- **S2a（已实现）**：一阶散射另生成 `diffuse:<tapKey>:<i>`，每个一阶 tap 2 个固定余弦半球方向；方向由 `DiffuseRaySampler` 按 tap key 低差异序列采样，避免每帧随机闪烁。一阶散射能量 50% 给 `scatter:<channel>` 聚合、50% 给这 2 个 `diffuse:` tap；延时用镜面路径延时 + 0/3 ms 微偏移，不做墙体再命中。
- **S2b（已实现）**：order-2/3/4 每声道每阶按 `scatteredGain` 选最强 2 条路径，各生成 1 个 `diffuse:<pathKey>:0` 方向 tap；选中路径的散射能量 50% 给方向 tap、50% 给聚合，其余路径仍全部聚合。每接收器高阶方向 tap 上限约 36（6 声道 × 3 阶 × 2 路径），仍走 `LateReflectionBus`，不增加 OpenAL source。
- fast/deep 的 Session 都传入 effective scattering 标志；`finishFastPhase`/`finishDeepPhase` 在发布前重建 `scatterTaps`。移动/房间/开关变化会通过 `resetLatePathBuild()` 清空高阶贡献并重新求解。

### 3.4 关闭 late bus 时怎么办

现状：`lateReverb=false` 时没有 wet source，散射能量没有渲染出口。

三个选项：

| 选项 | 行为 | 优点 | 缺点 |
|---|---|---|---|
| A（**已确认采用**） | `lateReverb=false` 时散射完全旁路，镜面系数保持旧值 | 默认零回归；不会静默丢能量 | 关掉晚期场时材质散射听不出来 |
| B | 始终削镜面，散射能量丢弃 | 镜面物理一致 | 房间变干、能量不守恒，不推荐 |
| C | 始终削镜面，把散射能量塞进一个轻量扩散 early tap | 能量守恒 | 需要新的渲染路径，超出 S1；容易和 late bus 重复 |

**S1 采用 A（已确认）**。如果以后要在关掉晚期场时也听到散射，应做 C 或 S2 的 diffuse early renderer，而不是 B。

#### 3.3.5 动态方向 ray 预算

S2a/S2b 的 ray 数量不再固定，而是由 `ScatterRayBudget` 前馈控制：

```text
s_eff = E_scatter / (E_specular + E_scatter)
```

- 每个声道/阶维护一个平滑后的 `s_eff`（约 250 ms 时间常数）；
- 低散射：`s_eff < 0.05` 时方向 ray 数为 0，全部进 `scatter:` 聚合；
- 中等散射：逐步增加方向路径数；
- **向上限本身也动态变化**：低散射时每个声道/阶的 `maxRayPaths` 只有 1；随 `s_eff` 升高逐步打开到硬上限（一阶 3 条路径 × 2 ray，高阶每声道每阶 2 条路径 × 1 ray）；
- 方向占比 `D` 从 0.20 平滑升到 0.50；选中路径的散射能量
  `D` 给方向 ray、`1-D` 给聚合，未选中路径 100% 聚合；
- 只调整**散射能量在方向 ray 与聚合之间的分配**和 ray 数量，不改变 `s` 决定的镜面/散射总能量；
- 不使用反馈 AGC，避免响度泵动；镜面 tap 仍由 `sqrt(1-s)` 物理衰减，只有低于 floor 时才自然淘汰。

#### 3.3.6 Wet 动态点布局（ghost）

S2 的 `diffuse:` 方向 tap 和 `scatter:` 聚合最终都进入 `LateReflectionBus`。为了让 VBAP 在这些方向上完整工作，wet 输出不再固定为 6 个逻辑声道，而是：

- 实际非 LFE 物理扬声器各一个点；
- 1/2/3/4 阶 late-bus tap 的到达方向聚类成 ghost 点（每阶最多 6 个，总点上限 24）；
- 每个点一个 wet OpenAL source，`Vbap.Panner` 在“物理 + ghost”点集上求解；
- ghost 方向约 250 ms 平滑、800 ms 保持；`scatter:` 扩散仍只投物理点；
- 预算优先级：direct → 一阶/premium → 物理 wet → ghost。

详细参数、数据流和测试见 [A3-WET-GHOST-LAYOUT.md](A3-WET-GHOST-LAYOUT.md)。

#### 3.4.1 A/B 开关

S1a 增加一个专门的 A/B 开关，不要求改代码或重启：

- `CinemaClientConfig.scatteringEnabled`，默认 `true`，持久化到 `config/cinema.json`；
- 实际生效条件：`lateReverb && scatteringEnabled`；
- Mod Menu `Audio` 页新增 `First-order scattering` 开关；
- `/cinema_acoustics scattering` 直接 toggle，`on` / `off` 显式设置；命令会打印 `effective` 和 `lateReverb` 状态；
- 切换开关会通过 `AvReceiverPlayback.lastScatteringEnabled` 强制下一次反射更新，不需要等 5 Hz 定时器；
- 开关关闭时，第一阶镜面系数回到旧公式，`scatterTaps` 清空；`lateReverb` 关闭时即使开关为 on 也显示 `effective=OFF`。

### 3.5 诊断

`/cinema_acoustics` 增加/修改：

- 材质 dump：`alpha=low/mid/high scattering=...`。
- 一阶 tap：`specularGain`、`scatterGain`、`spec²+scatter²`。
- 高阶 paths：移除 “specular only, no scattering”，改为 `scattering=on/off`、每阶 `specularEnergy` / `scatterEnergy`。
- late bus：`scatterTaps` 数量、每声道 `scatterE`、`scatterDelay`、`scatterCutoff`。
- 关闭 late bus 时明确打印 `scattering bypassed (lateReverb=false)`。

接收器 5 秒日志增加：

```text
... scatterTaps=6 scatterEnergy=... specularLoss=... ...
```

`specularLoss` 是本周期镜面能量相对无散射时的减少量，便于确认 `s` 确实生效。

### 3.6 测试

单元测试：

- `AcousticScatteringTest`
  - `s=0`：镜面系数等于 `sqrt(1-alpha)`，散射 0。
  - `s=1`：镜面 0，散射 `sqrt(1-alpha)`。
  - `s=0.35`：`spec² + scat² = 1-alpha`。
  - 多 bounce：`spec² + scat² = Π(1-alpha)`；任一点 `s=1` 时镜面 0。
  - clamp/NaN/null 安全。
- `ImageSourceSolverTest`
  - 现有 `scattering=0` 数值完全不变。
  - `scattering=0.35` 的 `gain` 和 `scatteredGain` 符合公式；能量和等于旧 `gain²`。
  - `scattering=1` 的镜面 `gain=0`。
- `ImageSourcePathSolverTest`
  - order-2/3 的 `scatteredGain` 乘积公式；`spec²+scat²` 与吸收后能量一致。
  - `pathKey`/延迟/材质列表不变。
- `LateReflectionBusTest`
  - 扩散 tap 的零方向经 `Vbap` 得到 diffuse 权重；`key` 稳定；floor/delay 过滤仍生效。
  - 同 key 重复发布不重启淡入。
- `ClientAcousticsCommand` 没有单测，靠游戏内输出检查。

性能：

- 聚合是 O(路径数)，不新增求解；扩散 tap 最多 +6，远低于 bus 512 cap。
- 预期 CPU 变化可忽略；仍需在 4K/BSL + 多扬声器下确认音频线程无 underrun。

### 3.7 实现顺序

1. **[x] `AcousticScattering` + 单测。**
2. **[x] `ReflectionTap` / `ReflectionPathTap` 增加 `scatteredGain` 和兼容构造器。**
3. **[x] `ImageSourceSolver` 一阶散射开关；`ImageSourcePathSolver` 增加 `scatteringEnabled`（Session/静态 solve）。**
4. **[x] `AvReceiverPlayback` 聚合器、`scatterTaps`、合并发布、诊断。**
5. **[ ] 游戏内 A/B**：`lateReverb=true`，用 Mod Menu `First-order scattering` 或 `/cinema_acoustics scattering` 对比开/关；fabric/carpet/vegetation vs glass/metal/water；`s=0` 材质应与旧版一致。
6. **[x] S1b 高阶启用 + 聚合**：order-2/3/4 的散射贡献分别进入 `order2Scatter/order3Scatter/order4Scatter`，和 first-order 合并成同一组 6 路扩散 tap。
7. **[x] S2a 一阶半球 diffuse tap**：`DiffuseRaySampler` + `diffuse:` tap；一阶散射能量 50/50 分给聚合和方向 tap。
8. **[x] S2b 高阶方向 tap**：`ReflectionPathTap.lastNormal`；每声道每阶 top-2 路径各 1 个方向 tap，其余聚合；fast/deep 发布前更新 `order2/3/4RayTaps`。
9. **[x] 更新 TODO/HANDOFF/AUDIO-ACOUSTICS 状态。**

### 3.8 验收

- `scattering=0` 的材质与当前版本听感/数值一致。
- `lateReverb=true` 时，fabric/carpet/vegetation 的镜面早期反射应比 glass/metal/water 更弱，late bus 扩散能量更强；左右/前后不反转。
- `lateReverb=false` 时完全旁路；`scatteringEnabled=false` 时回到旧镜面系数，两种路径都无回归。
- `/cinema_acoustics scattering` 的 `effective` 与 Mod Menu 开关、`lateReverb` 状态一致。
- 没有新增爆音、NaN、无限值；seek/reset 后无残留扩散。
- `/cinema_acoustics` 的每 tap `specular/scatter` 与材质方向一致；接收器日志的 `scatterTaps/scatterE/scatterDrop*` 正常。
- 多扬声器/多接收器 CPU 正常。
- 最终由你在游戏内 A/B 确认。

## 4. A3-D1 late tap 材质低/中/高滤波

### 4.1 当前问题

- `LateReflectionBus` 每个 tap 只有一个 one-pole；`dampingHz` 同时包含材质平均高频吸收和距离，无法表达低架/峰值/高架。
- 一阶/premium 的 4096-tap FIR 已经用 `MaterialReflectionCurve`，所以当前 late bus 和早期反射的材质音色不一致。
- premium order-2/3 只把第一个 bounce 的材质传给 FIR；高阶路径的材质形状不完整。
- 如果直接在 one-pole 之外再加 high shelf，会把材质高频算两次。

### 4.2 合成材质

对路径上的材质列表 `m1..mN`，按频带计算等效吸声：

```text
alpha_eff(b) = 1 - Π (1 - alpha_i(b))
```

因为：

```text
R_eff(b) = Π sqrt(1 - alpha_i(b))
         = sqrt(1 - alpha_eff(b))
```

- 新增 `AcousticMaterials.combine(List<AcousticMaterial> materials)`：
  - 返回一个 `AcousticMaterial`，名字可以是 `"combined"` 或路径 key；
  - `low/mid/high` 用上式；
  - `scattering` 不参与滤波设计，设为 `0.0`；
  - 空列表返回 flat 材质（`low=mid=high=0`），不要用 `DEFAULT` 的 0.05 吸收冒充无反射。
- 一阶只有一个材质，`combine` 退化为原材质。
- 合成材质只用于**频率形状**；宽带增益仍由 `path.gain()` 的 mid 乘积承担，不能重复乘 `alpha_eff(mid)`。

### 4.3 三带 PEQ

复用 `MaterialReflectionCurve` 的设计：

| 段 | 类型 | 频率 | Q | 目标 |
|---|---|---|---|---|
| low | low shelf | 250 Hz | 0.707 | `20log10(R_low / R_mid)` |
| mid | peaking | `sqrt(500·1000) ≈ 707 Hz` | 1.0 | 0 dB（基准） |
| high | high shelf | `sqrt(2000·4000) ≈ 2828 Hz` | 0.707 | `20log10(R_high / R_mid)` |

- 中频段 0 dB，所以 tap 的 `gain` 继续表示中频幅度。
- 低/高目标由合成材质的 `sqrt(1-alpha)` 相对中频计算，和 FIR 完全同一套语义。
- 新增公开类 `MaterialReflectionFilter`（建议 `acousticfir` 包）：
  - `static MaterialReflectionFilter of(AcousticMaterial material, double sampleRate)`
  - `Biquad[] sections()` 或 `Biquad low/peak/high`
  - `isFlat()`
  - `magnitudeDb(frequencyHz, sampleRate)`
  - 缓存键为 `(material, sampleRate)`；不要重复实现 shelf/peak 数学，内部复用 `MaterialReflectionCurve` 的设计。
- `MaterialReflectionCurve` 仍是设计时曲线；把“求解三段增益”的逻辑抽成共享私有/包内方法，供 FIR 和 PEQ 共用。
- 采样率使用 `LateReflectionBus.sampleRate`，不要硬编码 48 kHz；48 kHz 只是当前引擎的常见值。

### 4.4 Late bus 运行时

#### 4.4.1 `Tap` 扩展

```java
record Tap(..., MaterialReflectionFilter materialFilter)
```

- 旧构造器默认 `MaterialReflectionFilter.FLAT`。
- `fromPath(path, ..., lateMaterialEq)`：
  - 计算 `AcousticMaterials.combine(path.materials())`；
  - `materialFilter = lateMaterialEq ? MaterialReflectionFilter.of(combined, sampleRate) : FLAT`；
  - `dampingHz` 改为**距离 only**：`12000 - 150 × distanceMeters`，下限 2000 Hz；
  - 不再把 `-6000 × mean(high)` 放进 one-pole。
- `Tap.equals/hashCode` 包含 filter（filter 自身按三段系数相等）。
- `key` 不变；材质变化时 key 仍稳定，便于状态保留/淡变。

#### 4.4.2 状态和 process

- 每个 tap 每个 biquad section 需要 2 个状态；3 段共 6 个 `double`。
- 新增 `double[] tapFilterStates`（长度 `tapCount * 6`，按 tap 索引）或等价的扁平数组。
- 处理顺序：

```text
history 分数延迟
  → 距离 one-pole（现有 tapStates）
  → low shelf
  → mid peak
  → high shelf
  → gain × fade × VBAP 权重
```

- flat 段跳过；`MaterialReflectionFilter.FLAT` 直接不进入 biquad 循环。
- 使用 transposed direct form II：

```text
y  = b0·x + s1
s1 = b1·x - a1·y + s2
s2 = b2·x - a2·y
```

- 新增 tap 时 filter 状态清零；同一 key 且 filter 系数不变时保留状态。
- 同一 key 但 filter 系数变化时：
  - 方案 A（推荐，简单）：清零该 tap 的 filter 状态，并把该 tap 的 fade 从 0 重新淡入 30 ms；
  - 方案 B：旧/新 filter 双状态交叉淡化，成本更高。
  - 先做 A，避免系数突变产生 click；如果 A 的 30 ms 音量凹陷在材质切换时可闻，再升级 B。
- `setTaps` 的淡入/淡出、stable key、floor/delay 过滤全部保持不变。

#### 4.4.3 扩散 tap

- S1 的 `scatter:<channel>` tap 在 A3-D1 中也带 `MaterialReflectionFilter`。
- 聚合器保存能量加权的 `alpha_low/mid/high`，build 时生成合成材质并设计 filter。
- 这样扩散声也有材质低/高形状，不会出现“镜面变暗、扩散仍很亮”的音色断裂。

### 4.5 premium / FIR 对齐

- `premiumSource()` 和 `lateTapFromPath()` 构造 `CommonAcousticFirProfile.reflection(...)` 时，材质参数从 `path.materials().get(0)` 改为 `AcousticMaterials.combine(path.materials())`。
- `overallGain` 不依赖材质，所以增益语义不变；FIR 的材质形状现在包含所有 bounce。
- 一阶只有一个材质，行为不变。
- 这是 A3-D1 的一部分，但可以独立提交/验收，避免和 late bus 改动混在一起。

### 4.6 性能与回退

成本估算（48 kHz）：

- 每 tap 每 sample：1 个 one-pole + 最多 3 个 biquad；约 15 次乘加/tap/sample。
- 512 taps：约 370 M 乘加/秒（上限；实际常低于 512，且有 floor 过滤）。
- 现有 dense 418-tap bus 约 19–20× realtime；加三段 PEQ 后需要实测。

计划：

1. 在 `LateReflectionBusTest` 加一个 512-tap 密集基准，记录 `process()` 的 realtime factor。
2. 目标：音频线程无 underrun；目标机器上 bus 至少 **2× realtime** 余量（具体阈值可在游戏内调整）。
3. 如果不足，按顺序回退：
   - `lateMaterialEq=false`（当前 one-pole）；
   - 只保留 high shelf + low shelf（去掉 mid peak；mid 本来就是 0 dB 基准，影响最小）；
   - 把最大 tap 数从 512 降到 256（按 gain 排序，现有 `setTaps` 已做）；
   - 提高 `lateFloorDb`（用户设置，不作为自动行为）。
4. 不做“按帧动态切换滤波器”这种会产生音色跳变的自动降级；降级必须是显式配置/一次性判定。
5. 加 `lateMaterialEq` 配置（Mod Menu Audio，默认 `false`）：关闭时 `Tap.fromPath` 回到当前 `dampingHz` 公式和 FLAT filter，保证可 A/B、可回退。

### 4.7 测试

- `MaterialReflectionFilterTest`
  - 单材质低/中/高相对目标与 `MaterialReflectionCurve` 一致。
  - 多 bounce 合成材质的 `alpha_eff`/`R_eff` 正确。
  - 中频 0 dB；`MaterialReflectionCurve` 与 PEQ 在三个带中心的误差 ≤ 0.1 dB。
  - flat/null/全吸声不产生 NaN。
  - 不同采样率生成有效系数。
- `LateReflectionBusTest`
  - 单 tap 的 PEQ 脉冲响应与 `Biquad` 级联参考一致。
  - 中频增益不因 PEQ 改变。
  - filter 关闭时输出与当前 one-pole 版本一致。
  - 同 key/filter 不重置状态；filter 变化会重新淡入。
  - 512-tap 性能基准。
- `AcousticMaterialsTest`（或现有材质测试）
  - `combine` 的单材质退化、空列表、全吸声、低/高乘积。
- `CommonAcousticFirDesignerTest`
  - premium 多 bounce 合成材质的 FIR 目标与单材质路径的预期相对形状一致（新增用例，不改旧断言）。

### 4.8 验收

- `lateMaterialEq=false` 与当前版本听感一致（回归基线）。
- `lateMaterialEq=true`：
  - 石墙/玻璃/木墙/吸声材质的 late bus 高频和低频比例与一阶/premium 同向；
  - 不再出现 one-pole + high shelf 的双重高频衰减；
  - 材质切换/移动时无 click；
  - 512 taps 下无 underrun、无爆音；
  - 多接收器、4K/BSL 正常。
- `/cinema_acoustics` 输出合成材质、三段 filter 和有效 damping。
- 最终由你在游戏内 A/B 决定默认值。

**D1 实现状态（2026-11-21）**：`MaterialReflectionFilter`、`AcousticMaterials.combine`、`LateReflectionBus` 的三带 PEQ/距离 one-pole、premium 合成材质对齐、扩散 tap 能量加权材质 filter 已接入；`LateReflectionBusTest` 的 impulse 与参考 biquad 对齐、`MaterialReflectionFilterTest` 覆盖材质曲线/合成/flat。`lateMaterialEq` 默认 `false`，配置版本 6；Mod Menu `Audio → Late material EQ` 或 `/cinema_acoustics materialeq [on|off|toggle]` 可 A/B。**游戏内听感/CPU 待验收**。

## 5. 实施顺序与耦合

| 步骤 | 内容 | 依赖 | 可独立验收 |
|---|---|---|---|
| S1a | 一阶散射分流 + 扩散发送 + 诊断 | 无 | **已实现；check 通过，游戏内待验收** |
| S1b | order 2/3/4 散射 + 同一扩散发送 | S1a | **已实现；check 通过，游戏内待验收** |
| S2a | 一阶 cosine-weighted hemisphere diffuse tap（固定低差异序列） | S1a + ReflectionTap normal | **已实现；check 通过，游戏内待验收** |
| S2b | order-2/3/4 每声道每阶 top-2 方向 tap + 其余聚合 | S2a + ReflectionPathTap lastNormal | **已实现；check 通过，游戏内待验收** |
| D1a | 合成材质 + `MaterialReflectionFilter` + premium 高阶材质对齐 | 无 | **已实现；check 通过** |
| D1b | late bus 三带 PEQ + 距离 one-pole + 扩散 tap filter | D1a + S1a | **已实现；check 通过** |
| D1c | 性能基准/回退/默认值 | D1b | 默认 `lateMaterialEq=false`；游戏内 CPU/听感待验收 |

建议实际提交顺序：**S1a → S1b → D1a → D1b → D1c**。不要先把 late bus 改成三带再回头做散射，否则散射能量没有正确的滤波出口，A/B 会混淆。

## 6. 已确认 / 剩余待定

### 6.1 已确认：关闭 late bus 时散射旁路

`lateReverb=false` 时不改镜面系数，散射完全旁路。这样默认配置零回归，也不会静默丢能量。若以后希望关掉晚期场也能听到散射，需要新增轻量 diffuse early 路径（选项 C），不属于 S1。

### 6.2 已确认：扩散 tap 的时间细节

聚合发送每个源声道一个能量加权 tap（最多 6 个），延迟取能量加权平均；S2a 另外为每个一阶 tap 生成 2 个 `diffuse:` 方向 tap（0/3 ms），仍不增加 OpenAL source。若 A/B 觉得方向 tap 太散或太糊，可调 `FIRST_ORDER_RAY_FRACTION`/`DIFFUSE_RAYS_PER_TAP` 或按延迟分桶；这些是听感优化，不阻塞 S1。

### 6.3 已实现：散射分频带

`AcousticMaterial` 现在有 `scatteringLow/Mid/High`；一阶/高阶的镜面和散射分别用 `AcousticScattering.specularMaterial` / `scatteredMaterial` 合成逐频带有效材料，再进入现有 FIR/PEQ。单值构造器仍保留，把所有频带设为同一个值，旧测试/调用不需要改。系数表见 §2.5，仍是工程估计而非 Minecraft 方块实测。

### 6.4 已确认：A3-D1 默认开关

开发/验收期 `lateMaterialEq=false`（保持现状）；你完成 A/B 后再决定是否默认 `true`。如果验收后希望默认开启，再做一次默认配置回归。

### 6.5 待定：PEQ 回退阈值

“bus 至少 2× realtime”是工程目标，不是声学结论。实际阈值和是否降到 256 taps 需要看你的机器/4K/BSL 场景；不要用自动动态降级破坏音色连续性。

## 7. 文件影响清单

### 7.1 A3-S1

- 新增 `src/client/java/com/example/cinema/client/media/audio/acoustics/path/AcousticScattering.java`
- 新增 `src/client/java/com/example/cinema/client/media/audio/acoustics/path/DiffuseRaySampler.java`（S2a）
- 新增 `src/client/java/com/example/cinema/client/media/audio/reflection/ScatterAccumulator.java`
- 新增 `src/client/java/com/example/cinema/client/media/audio/reflection/ScatterRayBudget.java`（动态 ray 预算/平滑）
- 新增 `src/client/java/com/example/cinema/client/media/audio/reflection/WetLayout.java`（物理扬声器 + 反射 ghost 动态点、平滑/保持）
- `src/client/java/com/example/cinema/client/media/audio/reflection/Vbap.java`（`Panner` 凸包三角网 + `panPoints`）
- `src/client/java/com/example/cinema/client/media/audio/reflection/LateReflectionBus.java`（输出点上限 24、`Tap.order`/`withOutput`、动态输出数）
- `src/client/java/com/example/cinema/client/media/audio/playback/CinemaAudioEngine.java`（24 wet source、`setWetLayout`、按点喂 wet buffer、反射池 98）
- `.../acoustics/path/ReflectionTap.java`（S1a 已改）
- `.../acoustics/path/ReflectionPathTap.java`（S1b 已改）
- `.../acoustics/geometry/ImageSourceSolver.java`（S1a 已改）
- `.../acoustics/geometry/ImageSourcePathSolver.java`（S1b 已改）
- `.../media/AvReceiverPlayback.java`（S1a/S1b 已改）
- `.../media/audio/reflection/LateReflectionBus.java`（未改；复用现有 stable key/fade）
- `.../client/command/ClientAcousticsCommand.java`（S1a/S1b 已改）
- `.../client/config/CinemaClientConfig.java` + `.../client/gui/CinemaConfigScreen.java`（`scatteringEnabled` + `lateMaterialEq` A/B 开关，配置版本 6）
- 新增 `.../client/config/AcousticMaterialConfig.java`（`config/cinema-acoustic-materials.json` 加载/生成/回退）
- 测试：`AcousticScatteringTest`、`DiffuseRaySamplerTest`、`ScatterAccumulatorTest`、`ScatterRayBudgetTest`（低/中/高散射、平滑）、`WetLayoutTest`（物理/ghost 点、分阶上限、保持/释放）、`VbapTest`（point index/凸包）、`ImageSourceSolverTest`、`ImageSourcePathSolverTest`（order-2 散射/能量）已加；`AcousticMaterialConfigTest` 覆盖 JSON 覆盖/缺文件生成/非法 JSON 回退；`LateReflectionBusTest` 覆盖 bus 稳定 key/淡变/PEQ/大于 6 的 wet 点索引

### 7.2 A3-D1

- 新增 `.../media/audio/acousticfir/MaterialReflectionFilter.java`
- `.../media/audio/acousticfir/MaterialReflectionCurve.java`（抽出共享设计）
- `.../media/audio/acoustics/room/AcousticMaterials.java`（`combine`）
- `.../media/audio/reflection/LateReflectionBus.java`
- `.../media/AvReceiverPlayback.java`
- `.../client/config/CinemaClientConfig.java` + `.../client/gui/CinemaConfigScreen.java`（`lateMaterialEq`，默认 false）
- 测试：`MaterialReflectionFilterTest`、`LateReflectionBusTest`、`CommonAcousticFirDesignerTest`

## 8. 不做的事

- S2a 只做一阶反射点的 cosine-weighted hemisphere 方向 tap；不实现完整路径追踪（散射后再次命中墙面）、高阶 diffuse ray 或随机相位。
- 不实现衍射/边缘绕射。
- 不实现频率相关 scattering（第一版）。
- 不把声学设置搬进接收器 GUI；A4 已取消，声学继续留在 Mod Menu。
- 不为了散射新增 OpenAL source；扩散只进现有 late bus。
- 不用自动动态降级切换滤波器；回退必须是显式配置。
