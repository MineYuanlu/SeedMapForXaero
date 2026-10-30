# datapack — 数据包结构集摄取 (路线 A + v2 手动导入)

扫描数据包的 `worldgen/structure_set`，构建 `CustomStructureType` 注入 C 侧
(`xsmSetCustomStructures`) 与 Java 注册表 (`StructureTypes`)。**预测不做
biome tag 校验**（已知假阳性，设计文档 `doc/datapack.md`）。

## Classes

- `DatapackSource` — 数据源抽象：`readAllJson(folder)`（每 id 最高优先级层，
  worldgen 定义用）/ `readJsonStack(id)`（全部包层低→高，tag 合并用）/
  `readJson(id)`（单层）。**id 语义 = vanilla 文件级 id**（path 含目录前缀与
  `.json` 后缀；`readJson` 入参也带 `.json`）。
- `ResourceManagerSource` — 单机实现（集成服务器 ResourceManager）。
- `PathDatapackSource` — v2 手动导入实现：N 个 root（zip/目录）合并，
  root 顺序 = 优先级低→高（后 add 覆盖先 add）；zip 句柄按需开用完关；
  不校验 pack.mcmeta。资源定位 `data/<ns>/<path>`。
- `DatapackImports` — **v2 统一内核（命令与 UI 的唯一入口）**：`entries()` /
  `add(raw)` / `remove(index)`，返回 `Result`（i18n key + 参数）供展示层
  翻译（命令→chat、UI→toast）；变更后自动 flush + reload。校验：未激活世界
  拒绝 / 路径存在 / zip 可打开 / 去重。UI 递进时平移接入此层，勿绕过。
- `DatapackStructures` — 编排器：`reload()`（世界激活钩子，见
  `WorldSwitchMixin`；**数据源选择 = 手动导入优先 → 单机 RM → 清除**，返回
  `ReloadResult` 统计）/ `clear()`（幂等清除）。`scan(source)` 纯函数可 JVM 单测。

## 解析规则（scan）

- placement 仅支持 `minecraft:random_spread`；`frequency` ∈ (0,1] 全支持
  （`frequency_reduction_method` 4 种 vanilla reducer，语义按 MC 26.x 字节码锁定；
  YUNG's Better Mineshafts = `legacy_type_3` + spacing 1 + frequency 0.003）；
  `frequency_reduction_method` 未知值 → WARN + 跳过。
  `exclusion_zone` / 原版+自定义混排 / 同一结构多集合引用 → **WARN + 跳过**
  （预测会错不如不预测）。`locate_offset` 忽略（只影响 /locate 显示）。
- 全 `minecraft:` 结构的原版集合 → 跳过（cubiomes 已预测）；placement 与
  cubiomes 不一致（如 Structurify 改 salt）→ WARN（预测仍走原版）。
  实测 YUNG's **不改**原版 set，而是自带纯自定义 set（见 v1.1 调研）。
- 维度归类：structure JSON 的 `biomes` → tag 递归展开（栈合并，`replace:true`
  清空低层）→ 全 nether 群系 = -1 / 全 end = 1 / 其余 0；缺失定义按主世界。
- id 分配：全部结构按 id 字典序从 `CustomStructureType.MIN_ID`(100) 顺序分配
  —— 重扫描稳定。**id 稳定性只影响 C 表注入与会话内位标志**；持久层
  （marks 分片 / disabledStructures JSON）存稳定 key（完整 id），不依赖 id 分配
  （见 `configs/AGENTS.md`），未注入时未归位 key 走 orphan。
- `c:` 元数据：`c:worldgen/structure_icons.json`（图标物品，M3 用）、
  `c:tags/worldgen/structure/hide_from_map.json`（隐藏——仍注入 C 表保证同集合
  掷骰正确，但不进 `StructureTypes.all()` 即不查询不渲染）。

## C 侧注入契约

sets 每组 9 int：`[salt, spacing, separation, spreadType(0=linear/1=triangular),
dim, firstEntry, entryCount, frequency(float 位型, 0x3F800000=1.0),
freqMethod(0=default/1=legacy_type_1/2=legacy_type_2/3=legacy_type_3)]`；entries 按集合分组连续，每组 2 int：
`[id, weight]`——**集合内条目必须保持 JSON 顺序**（加权掷骰走表依赖顺序）。
字段映射：`regionSize=spacing`、`chunkRange=spacing-separation`。

## 生命周期

- `WorldSwitchMixin`（世界切换/离开）→ `reload()` / `clear()`
- `XaeroSeedMapClient` DISCONNECT → `clear()`
- `/reload` 热更新：`ServerLifecycleEvents.END_DATA_PACK_RELOAD`（成功时）调度回
客户端线程调 `reload()`（`XaeroSeedMapClient` 注册）
- 手动导入 add/remove（命令/UI）→ `DatapackImports` 内核自动 reload
- 注入后 `CacheHelper.invalidateAll()` + `HighlightedStructures.clear()`

## Gotchas

- `warnIfVanillaPlacementChanged` 里 `type.config()` 需 try/catch Throwable ——
  无 native 的纯 JVM 测试（`-PskipNativeBuild`）会因 Xsm 类初始化失败抛
  NoClassDefFoundError。
- 注入顺序：先 C 后 Java 注册表均可（REGIONS 消费端 byId null-check 容忍瞬态）。
