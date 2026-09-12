# command — /sm4x 客户端命令（二阶段）

`Sm4xCommand.register()` 在 `XaeroSeedMapClient` 经 `ClientCommandRegistrationCallback` 注册。
注意：本 fabric-api 版本 (3.0.x) 的入口类是 **`ClientCommands`**（旧名 `ClientCommandManager` 已更名）。

## 命令树

- `/sm4x`（无参）— 帮助
- `/sm4x history seed list [page=1]` — ServerConfig 种子历史（`ConfigData.getSeedHistory()`，MRU）：绿色 `[seed]` 点击复制（`ClickEvent.CopyToClipboard`）+ 上次使用时间
- `/sm4x history structure list [page=1]` — structure_data 按种子：组数（去重非默认组）+ 结构记录数
- `/sm4x history structure remove <seed>` — 删除该种子全部数据 + `flush(rotate=false)`；当前使用中的种子拒绝（提示先切换）

## 约定

- 命令树拆为 `cmdSm4x` → `cmdHistory` → `cmdHistorySeed`/`cmdHistoryStructure` 扁平方法，扩展新子命令加一层方法即可，禁止深嵌套 lambda
- 分页走纯函数 `Page.slice`（8 条/页、越界收敛、无 MC 依赖可 JVM 单测）；页脚 ◀▶ 经 `ClickEvent.RunCommand` 翻页
- 命令运行在客户端游戏线程，禁止重活；删除操作同步执行（数据量小）
- 全部文案走 i18n（`xsm.command.*`）
