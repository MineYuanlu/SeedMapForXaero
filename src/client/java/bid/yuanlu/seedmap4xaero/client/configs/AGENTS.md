# configs — persistence framework & basic config

```
configs/
├── core/       # 通用配置文件 IO（JSON 现行格式 + legacy .sm4x 迁移，无 MC 依赖）— 见 core/AGENTS.md
├── basic/      # server_config.json 的数据 — 见 basic/AGENTS.md
└── structure/  # structure_settings.json + marks/ 分片的持久标记 — 见 structure/AGENTS.md
```

配置系统 v2（JSON + 稳定 key）：
- 现行格式为**纯 JSON 文本**（pretty、无信封），文件名 `*.json`；
  旧版二进制 `*.sm4x` 在加载链自动向上迁移（读出即写新格式 + 改名 `*.legacy`）。
- 结构/生物群系一律持久化**稳定字符串 key**（`minecraft:village` / `minecraft:plains`，
  自定义结构为完整 id 如 `terralith:xxx`），运行时仍用 int id（热路径 bitset 不变），
  key↔id 转换只在读写边界（`client/structure/StructureKeys`、`client/biome/BiomeKeys`）。
- 无法识别的 key 以 orphan 原样保留（数据包结构移除后数据不丢，恢复后自动重挂）。
- 只支持自动向上升级；降级不在支持范围（旧文件留 `*.legacy` 可手动恢复）。

后续新增配置文件（如 structures.json）自建新包（`configs/<name>/`），
实现一个 `JsonCodec` + 调 `JsonConfigFile` 即可，各包独立。
