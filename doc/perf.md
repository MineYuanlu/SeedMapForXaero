# 性能设计与优化说明

本文档记录 Seed Map for Xaero 的生成管线性能设计：内存模型、调度策略、
prefetch/pregen 的 scale 选择依据，以及**磁盘缓存的设计规范**（含预留的
key 演进空间；实现未落地，配置项已先行）。

## 配置（perf_config.json）

全局配置 `gameDir/xaero/seed-map-for-xaero/global/perf_config.json`
（JVM 级语义，不随服务器/世界切换），由 `configs/perf/PerfConfig` 管理：

| key | 默认 | 说明 |
| --- | ----- | ---- |
| `generationThreads` | 0 | 生成线程数；0=自动 `max(1, min(4, cores/4))`；手动钳制 1..cores |
| `cacheCapacityMB` | 128 | CellCache 全局 LRU 预算 64..512（1MB = 64 条 ≈ 16KB/条） |
| `prefetchEnabled` | true | 视野外圈预取（最低优先级） |
| `pregenEnabled` | false | 后台预生成（游玩中，地图关闭时） |
| `pregenRadiusBlocks` | 8192 | 预生成半径 1024..65536 |
| `diskCacheEnabled` | false | 磁盘缓存开关（**未实现**，见下文规范） |
| `diskCacheMaxMB` | 256 | 磁盘缓存体积上限 |
| `debugOverlay` | false | 性能统计 HUD（tiles/s、ms/tile、pending、缓存占用、帧耗时） |

## 内存模型

**一屏 cell 数与分辨率无关**：Xaero 的 `scale = userScale × scaleMultiplier`，
`scaleMultiplier = max(1, 屏幕短边/1080)`（GuiMap.java），任意 16:9 屏幕满屏
可视世界恒为 `1920/userScale × 1080/userScale` 方块。tier 边界处一屏 cell 上限
≈ 2040（60×34），加 super 兜底层（/16）+ sub 叠加层 ≈ 2200 条工作集。

- 每条成本：CPU 像素 `int[4096]` = 16KB（pending 期间）≈ GPU 纹理 16KB + 驱动开销
- 一屏峰值：CPU 像素 ≈ 32MB（瞬时）、GPU 常驻 ≈ 40-64MB
- 全局 LRU 预算默认 128MB（8192 条）≈ 4 屏 + 兜底层；最小安全值 ≈ 32MB（一屏工作集
  ~2170 条），配置钳到 64MB（4096 条）起步以留余量并避免抖动
- 拖拽内存尖峰由 pending backpressure（512 条 = 8MB）封顶
- GPU 纹理被驱逐时延迟到 `CellCache.endFrame()`（draw 之后）再关闭，
  避免同帧 blit 引用已释放纹理

## 生成调度（GenScheduler）

单一调度器统一瓦片生成与即时任务（结构查询等），生成线程从调度器取件：

1. **即时 job 优先**（结构区域查询、要塞分环、chunk 高度、访问检测——便宜且交互相关）
2. **瓦片按优先级**：**kind 升序**（`VISIBLE` 可见请求 < `BACKGROUND` 视口粗层背景 <
   `PREFETCH` 外圈环/预生成）→ **scale 粗者优先** → 离相机近优先 → FIFO。
   相机坐标每帧更新，优先级实时重排（O(n) 扫描，n ≤ 512）。
   - kind 主键保证：预生成积压（PREFETCH 粗瓦片）不会饿死可见请求；
     视口粗层背景（BACKGROUND）排在可见之后、其他 prefetch 之前
   - 同档内 scale 粗者优先：zoom 过渡期先铺粗层

线程数 = `generationThreads` 配置（自动 `max(1, min(4, cores/4))`），
线程优先级 `NORM-2`（Windows 映射 OS 线程优先级；Linux 受 ThreadPriorityPolicy
限制效果有限——**主杠杆是线程数上限**）。运行时改配置即重建线程池（旧代线程
完成手头瓦片后退出）。

## Prefetch 与预生成的 scale 选择论证

原则：**只预生成"能被 fallback 链消费"的层；单位面积成本随 scale 平方下降**。

- SuperScale fallback 沿 ×4 逐级上溯（`SeedMapMixin.xsm$renderCellTexture` /
  `xsm$fillCellGaps`），只消费直接父层。
- **prefetch（默认开）** 两部分：
  1. 视口(+半视口环)的**粗层背景链（BACKGROUND 档）**：从 ×4 逐级铺到维度最大
     scale（如 scale-16 视口 → 64 + 256 两级），每级瓦片数 16× 递减（合计仅几十张），
     经多级 SuperScale fallback 直接成为任意细度缺失 cell 的兜底——冷缓存打开地图
     ~1-2s（单线程）先出全屏粗背景，细瓦片再由近及远填充；
  2. curScale **外扩半视口环（PREFETCH 档**，不含视口——可见 cell 走正常请求）：
     拖拽进入即命中。
- **后台预生成（默认关，PREFETCH 档）**：coarse 3 层 `{维度最大档, /4, /16}`
  （主世界 256/64/16），玩家周边圆形半径 `pregenRadiusBlocks`。半径 8192 时
  ≈ 200+13+1 张；每 40 tick 扫描一次（纯 containsKey，微秒级），重复扫描天然回填
  LRU 驱逐。PREFETCH 档保证积压时不与可见请求抢线程。
- **细层（tier 4/1）永不 prefetch/pregen**：面积成本平方上涨（半径 8192 的 tier 4
  ≈ 3400 张），且粗层兜底已消除缺卡感知；用户真看到时正常请求生成。
- ms/tile 实测参考（xsmsandbox test5，含光照，桌面 8 核）：
  scale 1≈5ms / 4≈30ms / 16≈30ms / 64≈39ms / 256≈47ms——
  scale≥4 的成本大头是 66×66 光照高度场（`getSurfaceHeight` 全气候噪声求值），
  后续优化方向：高度场降采样至 22×22 双线性（`fillHeightsInterp` 已有现成实现）。

## 磁盘缓存设计规范（未实现，配置已预留）

**收益场景仅限跨进程重启**（会话内内存 LRU 已全覆盖）；数据包适配落地时
（Java 侧群系生成比 C 慢一个量级）磁盘缓存升级为必需品。实现时遵循：

### Key 规范（预留数据包演进）

```
<baseDir>/xaero/seed-map-for-xaero/disk-cache/
└── v1/<seedHex>/d<dim>/s<scale>/c<cX>_<cZ>.png
```

- `seedHex`：种子有符号十进制或 64-bit hex（目录名安全）。
- **后续数据包支持 = key 追加数据包标识段**：`v1/<seedHex>__<packHash>/…`
  （`packHash` = 生成相关 datapack 内容哈希），或 seed 段升级为
  `<seed>-<packHash>` 复合键。目录方案整体迁移/作废最容易，与配置系统 v2
  的"稳定 key + orphan 保留"思路对齐。
- 失效维度：mcVersion、群系颜色表哈希、biome-disabled 集合哈希——
  任一变化 → 目录级作废（写入目录内 `meta.json` 记录全部 key 成分，
  读取时校验，不匹配即整目录作废重生成）。

### 文件格式

- 64×64 索引色 PNG（2-4KB/张）：像素 = 调色板索引，调色板 = 群系颜色表快照
  （随 meta.json 存一份）；光照信息并入调色板或存第二张灰度层（实现时定）。
- C 生成结果为确定性纯函数（seed+dim+version+cell → 像素），跨会话可复现。

### 容量与清理策略

- 上限 `diskCacheMaxMB`（默认 256MB）；**默认关闭**（`diskCacheEnabled=false`）。
- 写入时机：仅落盘"实际渲染过"（曾被 `getGpuTex` 返回）的瓦片——pending/
  prefetch 未消费的不写，防拖拽制造垃圾。
- 清理：LRU（文件 mtime），后台低优先级线程批量删除；目录级删除
  （seed 作废时整目录）。
- 读取：命中即跳过 C 生成（含pending 去重——磁盘 IO 异步，解码 ≈0.1-0.3ms/张，
  对比渣机重生成 5-20s/屏）。

## 验证手段

- 模拟弱机：`-XX:ActiveProcessorCount=4`（池尺寸逻辑）、`taskset -c 0-3` +
  `cpupower frequency-set -u 2.5GHz`（真渣机：核数 × 低持续频率）、`-Xmx2048m`（低端/FCL）
- 验收线：4 核模拟 scale=16 首屏 <3s；地图打开期间主线程帧时间 p99 增量 <2ms；
  pending 像素峰值 ≤8MB；同 seed 重开地图 0 重生成
- 真机数据：QQ 群收集 spark profiler + debugOverlay HUD 截图
- C 基准：`./build-test/xsmsandbox`（test5 输出 ms/tile × scale 表，可跨机器对比）
