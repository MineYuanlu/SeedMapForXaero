# datapack — datapack_imports.json（手动导入数据包列表）

配置文档：`gameDir/xaero/seed-map-for-xaero/<mainId>/datapack_imports.json`，
每个多人服务器/单机世界根一个文件（与 `server_config.json` 同级，per-mainId 隔离
——避免 A 服务器的导入列表污染 B 服务器产生假阳性预测）。

## 数据体

```json
{ "version": 1, "imports": ["/packs/Terralith.zip", "/packs/bettermineshafts"] }
```

- **列表顺序 = 优先级**：先加入的优先级低，后加入的覆盖先加入的
  （对齐 vanilla 数据包"后加载覆盖"语义）；`PathDatapackSource` 按此顺序消费。
- 路径存 `toAbsolutePath().normalize()` 后的字符串（`~` 展开与相对路径解析在
  输入侧 `DatapackImports.resolvePath` 完成）；不做 normalize 重写用户输入。
- 无 legacy 前身（新文件，回退链 `.json → .json.old → 新建`）。

## Classes

- `DatapackImportsConfig` — 门面：activate/deactivate/save/flush + `entries()` /
  `add` / `remove`。由 `WorldSwitchMixin` 世界切换时激活（照 ServerConfig 模式）。
  `saveConfig(base, mainId, ...)` / `loadConfig(base, mainId)` 包私有，base 独立
  注入以便 JVM 单测。
- `DatapackImportsData` — 数据体 + `JSON_CODEC`：缺失字段落默认空、非字符串条目
  丢弃、条目去重保序（单字段容错）；`dirty` 标志走通用 flush 契约。

## 消费端

交互入口在 `client/datapack/DatapackImports`（统一内核：校验 + flush + reload +
`Result` i18n 反馈），命令与后续 UI 都调它；本包只管存取，不做校验/触发。
