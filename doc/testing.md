# Xaero Seed Map — 测试文档

三层测试：**JVM 单元测试**（快，无 MC 依赖）→ **native 集成测试**（真实 cubiomes）→ **E2E 客户端 GameTest**（真实启动 MC，CI 上跑）。全部由 CI 矩阵自动执行。

## 测试分层与覆盖

### 1. JVM 单元测试 — `src/test/java`（JUnit 5，52 用例）

无 native、无 MC 运行时的纯逻辑测试。覆盖：

| 测试类                  | 用例 | 覆盖                                                                         |
| ----------------------- | ---- | ---------------------------------------------------------------------------- |
| `ServerConfigTest`      | 12   | 配置二进制读写往返、损坏/截断拒绝、`.old` 回退链、多服务器隔离、种子使用标记 |
| `StructureTypeTest`     | 11   | 26 种结构枚举一致性、稀疏结构概率配置                                        |
| `VanillaBiomeColorTest` | 7    | vanilla 生物群系颜色表                                                       |
| `BitSetViewTest`        | 6    | 不可变 BitSet 包装语义                                                       |
| `NativeIntegrationTest` | 8    | **见下一层**（native 可用时执行）                                            |
| `BiomeTypeTest`         | 4    | biome 图标索引映射                                                           |
| `CellKeyTest`           | 4    | 缓存键坐标/尺度换算                                                          |

### 2. native 集成测试 — C doctest + `NativeIntegrationTest`

- **C 侧**：`src/main/c/xsm/test/unit_tests.cpp`（doctest，8 个 `TEST_CASE`）——`setup+smoke`、`queryPoint`、`genCellImg`（scale=4 逐像素高度 / scale=1 region）、`queryRegionStructuresGrid`、`querySparseStructures`、`queryStrongholdsRange`、`structure variants`。
- **Java 侧**：`NativeIntegrationTest extends NativeMcTest`。native 库（`libxsmcore`）缺失时整套 assumption 跳过（如 `-PskipNativeBuild`）；可用时执行真实 cubiomes 查询，断言跨 26.1/26.2 稳定的**事实值**（26 结构 id、维度、regionSize、biome id↔名、`queryPoint` 高度/确定性）而非逐版本快照。

### 3. E2E 客户端 GameTest — `src/gametest/java`（fabric-client-gametest）

`SeedMapClientGameTest`：真实启动 MC 客户端 → 创建单机世界（`KNOWN_SEED=123456789`）→ 打开 Xaero `GuiMap` → 4 个断言 + 截图：

1. **种子解析**：`ServerConfig.resolveSeed()` 返回已知种子且与服务器实际种子一致
2. **地图激活**：打开 `GuiMap` 后 `ServerConfig.activeMainId() != null`
3. **CellCache 生成**：scale 1/4/16 任一有缓存（验证 `tickWorldInfo → Xsm.setWorld → native C 生成` 链路）
4. **结构缓存**：`StructureCache.REGIONS` 非空（异步 CACHE_WORKER 查询）

断言通过后打印 `seed-map E2E assertions passed` 供 CI grep。测试 mod 的 `fabric.mod.json` 用宽松下限写死版本（`>=26.1` 等），**不 expand**——测试 mod 只需在任意支持版本上跑通，无需跟随矩阵精确版本。

## 版本矩阵

- `versions.json` + `tools/resolve_versions.py`（update/check/matrix 三模式）解析出 **8 组合**：4 个 MC 版本（26.1/26.1.1/26.1.2/26.2）× 每版本 oldest/newest 两个 Xaero 版本。
- 版本覆盖用 `-P` 属性传入，**key 与 gradle.properties 同名**（见下节）。
- `refresh-versions.yml` 每周一自动跑 `update` 并仅在有真实变更时提交。

## 运行

### 本地

```bash
# JVM 单元测试 + native 集成测试（含 JUnit）
./gradlew build -x compileNativeWindows

# C 单元测试（独立，doctest）
cmake -S src/main/c -B build-test -DCMAKE_BUILD_TYPE=Release
cmake --build build-test --target xsmtest
XSM_TEST_MC_VERSION=<mc> ./build-test/xsmtest   # 可选指定 MC 版本常量

# E2E client gametest（需真实显示或用 -PclientGameTestXVFB=true 无头）
./gradlew runProductionClientGameTest -PskipNativeWindows=true
```

### CI（`.github/workflows/matrix-test.yml`）

| Job            | 内容                                                                                                                                                                      |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `resolve`      | 校验 `versions.json` 新鲜度（过期仅告警不阻塞）+ 输出矩阵                                                                                                                 |
| `test`（8 行） | `cmake` C 单测（带 `XSM_TEST_MC_VERSION`）→ `./gradlew build -x compileNativeWindows`（含 JUnit）                                                                         |
| `client-test`  | 主版本 E2E：`./gradlew runProductionClientGameTest -PskipNativeWindows=true`，grep `seed-map E2E assertions passed` 判定成功；`gametest.log` + `run/screenshots` 始终上传 |

### 失败诊断

- client-test 失败先看上传的 `gametest.log`：无客户端日志 = 构建阶段挂（如 MinGW），有日志无断言 = 渲染/死锁问题。
- 截图 artifact 为空 = 从未到截图阶段（构建失败或打开地图前就挂）。

## 已知限制与踩坑记录

1. **服务端 `runGameTest` 被禁用**（`build.gradle` `enableGameTests = false`）：XaeroLib 的 `serverStarting` 只对 `DedicatedServer` 调 `freeze()`，game test server 下 registry 永不冻结会崩。我们只要客户端 E2E。
2. **CI 无 MinGW**：`compileNativeWindows`（交叉编译 Windows dll）只在有 MinGW 的主机构建；CI 一律 `-PskipNativeWindows=true` 或 `-x compileNativeWindows`。
3. **E2E 退出死锁（MC 26）**：`IntegratedServer.halt` 先 `executeBlocking` 等 server 线程，而 fabric client gametest 的 phaser 让 server 卡在 `postRunTasks` → 三线死锁。绕开：断言后 `runOnServer(server -> server.halt(false))`（server 线程内不阻塞）。
4. **fabric-client-gametest 跨版本 API**：5.1.x（26.1）`getClientLevel().waitForChunksRender()`；6.0.0（26.2）改用 `getConnection().waitForChunksRender()`——测试用反射兼容。同理 `Minecraft.setScreen` 在 26.2 移除，改 `setScreenAndShow`。
5. **网络同步器 bug**：production run task 加 `-Dfabric.client.gametest.disableNetworkSynchronizer=true`（fabric-docs warning）。
6. **Java 25 必需**；native 缺失时 native 集成测试自动跳过，但 CI 矩阵行总是完整执行。

## 参数命名约定

- `gradle.properties` 的 key **即** CI `-P` 覆盖的 key（camelCase）：`fabricApiVersion`、`xaeroMapLine`、`xaeroMapVersion`、`minecraft_version`、`loader_version`。本地可用 `gradle.local.properties`（gitignored）同格式覆盖。
- 其他构建开关：`-PskipNativeBuild`、`-PskipNativeWindows`、`-PjextractPath`、`-PclientGameTestXVFB`。
