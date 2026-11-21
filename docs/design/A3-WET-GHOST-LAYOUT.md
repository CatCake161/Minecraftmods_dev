# A3 Wet 动态点布局（物理扬声器 + 反射 ghost）

状态：**已实现（2026-11-21），`.\gradlew.bat check --no-daemon` 通过；游戏内听感待验收。**

## 1. 问题

旧几何 late bus 固定输出 6 个逻辑 5.1 声道，每个声道只由一个 wet source
播放，位置取“该声道最近的物理扬声器”（anchor）。这带来三个问题：

1. VBAP 只在物理扬声器上求解。反射方向落在扬声器凸包外（高度、后方、
   上方）时，3D VBAP 失败，退化为水平 → 最近 → 扩散，方向丢失。
2. 同一逻辑声道接多只扬声器时，VBAP 权重按声道合并，wet 只从 anchor
   发出，其余扬声器没有 wet。
3. 没有扬声器的声道回退到默认 5.1 虚拟位置，产生 phantom wet；旧实现中
   LFE 输出通道实际上一直为空，却永久占用一个 wet source。现在 LFE 改为
   独立的 low-passed wet 发送（见 3.3.1）。

## 2. 目标

wet 输出点集合 = **实际非 LFE 物理扬声器 + 专用 LFE 点 + 1/2/3/4 阶反射 ghost 点**。

- 每个 wet 点一个 OpenAL source，位置就是该点。
- VBAP 在非 LFE 点上求解；ghost 点保证活跃反射方向落在目标凸包内，
  不再触发 fallback。LFE 点不参与方向性 VBAP，只接收 `scatter:3` 聚合。
- LFE wet 点经过约 120 Hz 低通，避免中高频晚期声进入低音炮。
- 点数量、位置随活跃反射动态变化，带平滑、滞回和短暂保持，避免 5 Hz
  更新造成 source/方向抖动。
- 总能量守恒：每个 tap 的 VBAP 权重仍然能量归一化；`scatter:` 扩散仍只
  投非 LFE 物理扬声器（LFE 源声道的聚合除外）。

## 3. 数据流

```text
first-order scatter rays / order-2..4 bus taps
        │  (direction, gain, order)
        ▼
WetLayout.Builder
  ├─ 非 LFE 物理点：actual speakers
  ├─ LFE 点：单独输出，不进 VBAP
  ├─ ghost：每阶按方向聚类 top-N
  └─ Vbap.Panner（凸包三角网预计算）
        │  (point index, weight)
        ▼
LateReflectionBus.Tap.outputChannels/Weights
        │
        ▼
LateReflectionBus (input = 6 source channels, output = 24 wet points)
        │
        ▼
CinemaAudioEngine: 24 wet OpenAL sources at wet point positions
```

### 3.1 Ghost 生成

- 只使用 **late bus 内的 tap**：一阶散射 ray、非 premium 的 order-2/3/4。
  premium per-tap 路径和一阶 specular 不参与 ghost 聚类，避免重复渲染。
- tap 方向是 listener-relative；先按当前 listener 帧转到**世界坐标**，再
  按 `tap.order()` 分组。每阶对世界到达方向做能量加权贪心聚类；聚类、
  平滑和保持都在世界空间完成，所以听者转头时静态反射不会跟着转。
- 输出给 engine/VBAP 时再转回 listener 空间（`Layout.positions()`）；
  debug 使用世界坐标（`Layout.worldPositions()`）。
- 最小角间距 **20°**；每阶最多 **6** 个 cluster；全局 wet 点上限 **24**。
- 每个阶至少保留 1 个 ghost（若该阶有能量），剩余槽位按能量排序填充。
- ghost 位置 = 单位方向 × 参考半径；参考半径取物理扬声器平均半径，
  限制在 1–8 m。OpenAL 距离模型是 `AL_NONE`，半径不影响响度，只影响
  空间化。
- 稳定 id/槽位：ghost 匹配上一轮同阶、30° 内的轨迹，复用槽位；方向
  按约 250 ms 时间常数平滑；能量低于峰值 -30 dB 的 cluster 仍可在该阶
  没有更强候选时保留，但填充剩余槽位时优先 -30 dB 以上的 cluster；
  能量消失后进入 **800 ms** 保持，保持期内仍作为 VBAP 目标，随后释放。

### 3.1.1 LFE wet 点

- 每个 LFE 物理扬声器单独成为一个 wet 点，**不进入 VBAP panner**；方向性反射
  不会投到低音炮。
- `scatter:3`（LFE 源声道的扩散聚合）在 `engineTaps` 中被单独路由到所有
  LFE 点，按 `1/√N` 等功率分配。
- engine 在上传该 wet source 前做约 **120 Hz 二阶低通**（`Biquad.lowPass`），
  避免中高频晚期声进入低音炮。
- LFE 源声道的方向性 tap（`diffuse:`/高阶路径）仍按正常 VBAP 投到非 LFE
  物理点；LFE wet 点只接收聚合 send。
- 没有 LFE 物理扬声器时，`lfeIndex=-1`，`scatter:3` 不再输出。

### 3.2 VBAP

`Vbap.Panner` 在构建时计算点集凸包的 outward 三角网，之后每个 tap 只在
凸包三角上做 exact 3D VBAP。24 个点的三角网通常约几十个三角形，避免
`C(24,3)=2024` 个 triplet × 每个 tap 的开销。

- 方向性 tap：在“非 LFE 物理 + ghost”点集上 pan。
- 零方向 `scatter:` 聚合：只在非 LFE 物理点集上 diffuse，避免散射能量
  填满 ghost；LFE 聚合例外，见 3.1.1。

### 3.3 源预算与优先级

总预算保持 **128**：

```text
direct + wet points + reflection sources <= 128
```

- wet 点上限 24（含非 LFE 物理扬声器、LFE 点和 ghost），因此典型 5.1 为
  6 direct + 24 wet + 98 reflection；`lateReverb=false` 时不分配 wet source，
  反射池恢复到 116，即 6 direct + 116 reflection（6 个 source 空闲）。
- 优先级（用户确认）：
  1. direct
  2. 一阶 per-tap + premium order-2/3（反射池内 premium 先发布）
  3. 物理扬声器 wet 点
  4. ghost 点
- 24 个 wet 槽位在 `lateReverb=true` 时整体预留；**池内** physical 先于
  ghost。典型 5.1（6 direct）反射池 98 > 一阶+premium 的 88，因此
  premium/一阶不受影响；只有扬声器数很多（约 >16）时反射池才会低于 88，
  此时 premium 仍先拿 slot，先减少一阶/ghost 的可用性。
- 预算不足时先减少 ghost；`AvReceiverPlayback` 按 premium → 一阶的顺序
  发布反射源，使 premium 优先拿到空闲 slot。
- wet source 固定按 24 个槽位懒分配（仅 `lateReverb=true`），ghost 增删
  只改变点位置/权重和有效输出数量，不反复创建/销毁 OpenAL source。

## 4. 参数

| 参数 | 值 | 位置 |
|---|---:|---|
| wet 点上限 | 24 | `WetLayout.MAX_POINTS` |
| 每阶 ghost 上限 | 6 | `WetLayout.MAX_GHOSTS_PER_ORDER` |
| 聚类最小角间距 | 20° | `WetLayout.MIN_SEPARATION_RADIANS` |
| ghost 匹配半径 | 30° | `WetLayout.MATCH_SEPARATION_RADIANS` |
| 方向平滑时间常数 | 250 ms | `WetLayout.SMOOTHING_SECONDS` |
| ghost 保持时间 | 800 ms | `WetLayout.HOLD_MILLIS` |
| ghost 能量门限 | -30 dB re peak | `WetLayout.MIN_ENERGY_RATIO` |
| bus 最大输出点 | 24 | `LateReflectionBus.MAX_OUTPUT_CHANNELS` |

## 5. 测试

- `WetLayoutTest`：物理点/LFE 排除、ghost 方向、分阶、上限、diffuse 只投
  物理点、ghost 保持/释放、无 tap 空布局。
- `VbapTest`：point index 3 不当作 LFE、elevated ghost 精确命中。
- `LateReflectionBusTest`：输出索引 > 5 的 wet 点可正常渲染。
- `SourceBudgetTest`：128 = direct + 24 wet + reflection。

`.\gradlew.bat check --no-daemon` 通过。

## 6. 游戏内 A/B

0. `/cinema_acoustics wetdebug on`：在世界中显示**所有音频 source**。
   蓝色=direct 物理扬声器，红色=一阶/premium 反射 source，绿色=物理
   wet 点，紫色=专用 LFE wet 点，黄/橙/品红/青=1/2/3/4 阶 wet ghost，
   灰色=未激活 wet 槽位。ghost 会从听者位置画一条淡线。激活 ghost 和反射
   source 的标记会沿方向夹到第一面墙前，避免被墙挡住；物理扬声器和 LFE
   保持真实位置。只影响渲染，不改变声音。`off`/`toggle` 关闭或切换。
1. `/cinema_acoustics scattering off` 与 `on` 对比：关闭时 ghost 只来自
   高阶路径；开启时一阶散射 ray 也会进入 ghost 聚类。
2. 在 fabric/carpet/vegetation 与 glass/metal/water 之间切换，听：
   - 上方/后方反射是否不再被压到最近扬声器；
   - 同声道多扬声器时 wet 是否覆盖全部扬声器；
   - 快速转头/移动时 ghost 方向是否平滑，无跳动/梳状；
   - `lateReverb=false` 时是否完全旁路。
3. 观察 `/cinema_acoustics` 的 reflection/source 日志和 CPU；ghost 上限
   24 点应低于原 6 wet + 116 reflection 的 source 压力上限。

## 7. 已知限制

- 一阶 specular 仍走 per-tap OpenAL source + 4096-tap FIR；ghost bus 只
  服务一阶散射和高阶 bus 内容（用户确认）。
- 物理扬声器超过 24 只时，wet 点只保留前 24 只（按当前选择顺序）；此时
  ghost 容量为 0，VBAP 完整性退化，但 direct/premium 预算优先。
- ghost 位置使用到达方向，不使用 image-source 位置（image-source 在墙后，
  会把左右/前后镜像）。
- ghost 数量/位置是 VBAP 目标层的近似，不是完整路径追踪；完整路径/RIR
  仍归 A7。
- 听者转头**不再触发反射路径求解**。`updateChannelLayout` 每 tick 只把现有
  tap/source 从旧 listener frame 旋转到新 frame，并重发布 wet 布局；路径、
  延迟、增益不变。转身本身不会增加反射 solver 的 CPU。
- ghost 的聚类/平滑/保持在世界坐标完成；OpenAL wet source 仍是
  `AL_SOURCE_RELATIVE`，发布时从世界坐标转回 listener 坐标。转身时
  `updateChannelLayout` 以约 20 Hz 旋转现有 source/tap 的 listener 坐标，
  因此最多约 50 ms 的位置滞后，但 ghost 不会随头旋转，也不会触发路径求解。
- LFE wet 只接收 `scatter:3` 聚合，固定约 120 Hz 二阶低通；LFE 源声道的
  方向性 tap 仍投到主扬声器。每个 LFE 物理扬声器占用一个 24 槽 wet 点，
  ghost 容量相应减少。
- 限幅器对 24 个 wet 点使用线性峰值和做保守上界（旧实现按 6 个输出声道
  分配），可能比旧估计更保守；如果游戏内觉得 wet 被压得过多，再改为
  能量和或按实际 VBAP 权重分配。
