# datapack — 数据包结构集摄取 (路线 A)

单机世界切换时扫描集成服务器数据包的 `worldgen/structure_set`，构建
`CustomStructureType` 注入 C 侧 (`xsmSetCustomStructures`) 与 Java 注册表
(`StructureTypes`)。**预测不做 biome tag 校验**（已知假阳性，设计文档
`doc/datapak.md`）。

## Classes

- `DatapackSource` — 数据源抽象：`readAllJson(folder)`（每 id 最高优先级层，
  worldgen 定义用）/ `readJsonStack(id)`（全部包层低→高，tag 合并用）/
  `readJson(id)`（单层）。v1 仅 `ResourceManagerSource`（集成服务器 RM），
  多人 zip/目录导入留 v2。
- `DatapackStructures` — 编排器：`reload()`（世界激活钩子，见
  `WorldSwitchMixin`）/ `clear()`（幂等清除）。`scan(source)` 纯函数可 JVM 单测。

## 解析规则（scan）

- placement 仅支持 `minecraft:random_spread`；`frequency < 1` /
  `exclusion_zone` / 原版+自定义混排 / 同一结构多集合引用 → **WARN + 跳过**
  （预测会错不如不预测）。`locate_offset` 忽略（只影响 /locate 显示）。
- 全 `minecraft:` 结构的原版集合 → 跳过（cubiomes 已预测）；placement 与
  cubiomes 不一致（如 YUNG's 改 salt）→ WARN（预测仍走原版，v1.1 再做抑制）。
- 维度归类：structure JSON 的 `biomes` → tag 递归展开（栈合并，`replace:true`
  清空低层）→ 全 nether 群系 = -1 / 全 end = 1 / 其余 0；缺失定义按主世界。
- id 分配：全部结构按 id 字典序从 `CustomStructureType.MIN_ID`(100) 顺序分配
  —— 重扫描稳定，structure_data 持久标记跨会话可复现。
- `c:` 元数据：`c:worldgen/structure_icons.json`（图标物品，M3 用）、
  `c:tags/worldgen/structure/hide_from_map.json`（隐藏——仍注入 C 表保证同集合
  掷骰正确，但不进 `StructureTypes.all()` 即不查询不渲染）。

## C 侧注入契约

sets 每组 7 int：`[salt, spacing, separation, spreadType(0=linear/1=triangular),
dim, firstEntry, entryCount]`；entries 按集合分组连续，每组 2 int：
`[id, weight]`——**集合内条目必须保持 JSON 顺序**（加权掷骰走表依赖顺序）。
字段映射：`regionSize=spacing`、`chunkRange=spacing-separation`。

## 生命周期

- `WorldSwitchMixin`（世界切换/离开）→ `reload()` / `clear()`
- `XaeroSeedMapClient` DISCONNECT → `clear()`
- `/reload` **不热更新**（重进世界生效）
- 注入后 `CacheHelper.invalidateAll()` + `HighlightedStructures.clear()`

## Gotchas

- `warnIfVanillaPlacementChanged` 里 `type.config()` 需 try/catch Throwable ——
  无 native 的纯 JVM 测试（`-PskipNativeBuild`）会因 Xsm 类初始化失败抛
  NoClassDefFoundError。
- 注入顺序：先 C 后 Java 注册表均可（REGIONS 消费端 byId null-check 容忍瞬态）。
