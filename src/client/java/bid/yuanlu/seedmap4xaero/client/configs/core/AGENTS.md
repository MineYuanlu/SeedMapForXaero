# core — 通用配置文件 IO（类型无关、无 MC 依赖）

## 现行格式：JSON（`JsonCodec` + `JsonConfigFile`）

- `JsonCodec<T>` — 一个 JSON 配置文档的编解码器：`write(data) → JsonObject` /
  `read(JsonObject) → T`。基于 Gson 树模型手工读写（**不用反射映射**）：
  读端缺失字段落默认值、未知字段忽略 → 双向前向兼容，文档内部无需版本分支
  （`version` 字段仅作记录）。实现只写文档体，文件级布局由 JsonConfigFile 处理；
  实现内部把 RuntimeException 包成 IOException（结构损坏 → 回退链）。
- `JsonConfigFile` — 磁盘 IO（全 static）：
  - `pathsFor(base, mainId, jsonName, legacyName)` → `Paths(target, tmp, old, legacy, legacyOld)`
  - `save(paths, data, codec, rotate)`：写 `.tmp` → （rotate 且正本存在：正本→`.old`）
    → `ATOMIC_MOVE` 提交。**纯 JSON 文本**（pretty + disableHtmlEscaping，非 ASCII 组名可读）
  - `load(paths, fallback, jsonCodec, legacyCodec?)`：回退链
    `.json` → `.json.old` → `.sm4x`（legacy 读出即迁移落盘）→ `.sm4x.old` → fallback
  - `retireLegacy(paths)`：legacy 改名 `*.sm4x.legacy` / `*.sm4x.old.legacy` 退出回退链
  - `writeJson` / `readJson`：单文件读写（read 用 lenient Gson，容忍手工编辑）

## 轮替契约（调用点手动保证，框架不追踪状态）

> 写出 `.tmp` → 正本移动到 `.old` → `.tmp` 移动到正本；
> **在重新读取之前的写出，跳过 `.old` 步骤**直接 `.tmp` 覆盖正本。

- `rotate=true` **仅用于"数据源自磁盘读取验证"的生命周期保存**（世界切换 deactivate）。
- 会话内主动刷写 / 周期刷盘必须 `rotate=false`——只有经过读取验证的数据才适合
  覆盖 `.old`，否则会话内快速多次写出会用未经验证的数据覆盖可能有用的 `.old` 备份
  （`.old` 恒为上次生命周期检查点）。
- 调用点格局：门面的 `save()`（lifecycle）= true；`flush()`（会话/周期）= false。

## legacy：`.sm4x` 二进制（frozen）

- `Sm4xCodec<T>` / `Sm4xFile`（magic 信封 + DataStream、版本分支读取）——**冻结格式**，
  仅用于旧文件迁移读取与测试 fixture 生成，新数据一律走 JSON。
- 历史版本读兼容已固化：ConfigData v0/v1、WorldConfig v0/v1/v2（basic 包内），
  StructureData v0/v1（structure 包 `StructureDataLegacy` 中性快照）。
- 迁移语义：读出即写新格式 + `retireLegacy`，**只自动向上升级**。

## 单测

`JsonConfigFile` 不依赖 MC：直接 `@TempDir` + `writeJson/readJson/save/load` 即测，
含回退链与轮替语义（见 `ServerConfigTest` 的回退/轮替/迁移用例）。
