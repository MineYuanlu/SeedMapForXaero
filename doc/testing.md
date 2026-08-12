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

断言通过后打印 `seed-map E2E assertions passed` 供 CI grep。主 mod 和测试 mod 的 `fabric.mod.json` 都用宽松下限写死版本（`>=26.1`），**不 expand**——单 jar 在任意支持版本上通用，无需跟随矩阵精确版本。

## 版本矩阵与单 jar 策略

- `versions.json` + `tools/resolve_versions.py`（update/check/matrix 三模式）解析出 **8 组合**：4 个 MC 版本（26.1/26.1.1/26.1.2/26.2）× 每版本 oldest/newest 两个 Xaero 版本。
- 版本覆盖用 `-P` 属性传入，**key 与 gradle.properties 同名**（见下节）。
- `refresh-versions.yml` 每周一自动跑 `update` 并仅在有真实变更时提交。
- **发布产物是单 jar（universal jar）**：`fabric.mod.json` 的 `"minecraft": ">=26.1"` 让 loader 接受全部版本；编译目标取全局最老 Xaero（1.40.14，26.1.2 线），引用的符号是全部支持版本集合的子集，向前兼容所有版本。未来 MC 若有破坏性变更，由 CI 的 universal E2E（矩阵随 versions.json 自动增长）提前暴露。

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

# 用预构建 universal jar 跑 E2E（验证发布产物，不在目标版本上重编译 mod）
# 排除 -sources.jar：其 fabric.mod.json 是未展开的 ${version} 模板，误载会解析失败
./gradlew runProductionClientGameTestUniversal -PskipNativeWindows=true \
  -PuniversalJar="$(find build/libs -name 'seed-map-for-xaero-*.jar' ! -name '*-sources.jar' | head -1)"
```

### CI（`.github/workflows/matrix-test.yml` + native 矩阵）

单人开发流程：`dev/xxx`、`fix/xxx` 只跑 `pr-check.yml` 快速检查；**develop / master 是全面门禁**（`matrix-test.yml` 版本矩阵 + E2E 与 `build.yml` 全平台 native + 打包都触发）。

- `resolve`：校验 `versions.json` 新鲜度（过期仅告警不阻塞）+ 输出两套矩阵
  - `matrix`：8 组合（4 MC × oldest/newest Xaero），`test` 用
  - `matrix-e2e`：4 组合（每 MC × **newest** Xaero），`universal-e2e` 用
- `test`（8 行，**源码兼容预警**）：`cmake` C 单测（带 `XSM_TEST_MC_VERSION`）→ `./gradlew build -x compileNativeWindows`（含 JUnit）。保证源码在全部 MC × Xaero 组合下可编译 + JUnit 通过；产物是编译载体，不发布
- `build-universal`（1 行）：编一个 universal jar（最老 Xaero 线 1.40.14），上传 artifact
- `universal-e2e`（4 行，依赖 `build-universal`）：真实启动 MC 的 E2E，**复用同一个 universal jar**，`./gradlew runProductionClientGameTestUniversal -PskipNativeWindows=true -PuniversalJar=<artifact>` + 版本 `-P` 覆盖，grep `seed-map E2E assertions passed` 判定成功；每组合的 `gametest.log` + `run/screenshots` 按 `mc` 命名始终上传

**native 架构矩阵**：定义在 `reusable-native-build.yml`（5 个 compile job），被 `build.yml`（develop/master/tag push）、`release.yml`、`build-test-jar.yml` 通过 `uses:` 调用；汇总打包统一在 `reusable-package.yml`（下载 natives → universal JAR，含内置校验）。feature 分支/PR 的快速检查在 `pr-check.yml`。

| Job | Runner | 产物 | 验证 |
| --- | ------ | ---- | ---- |
| `compile-native-linux` | `ubuntu-24.04` | `linux/x86_64/libxsmcore.so` | —（package 阶段跑 C 单测） |
| `compile-native-linux-arm` | `ubuntu-24.04-arm`（原生） | `linux/aarch64/libxsmcore.so` | 该 job 内原生跑 `xsmtest` |
| `compile-native-android` | `ubuntu-24.04` + NDK r27c | `android/arm64` + `android/x86_64`（bionic） | ELF 校验：`file` 确认 ABI + `readelf -d` DT_NEEDED 无版本化 SONAME（等价 FCL `checkElfIsAndroid`） |
| `compile-native-macos` | `macos-latest` | `macos/universal/libxsmcore.dylib` | `lipo -info` 确认双架构 |
| `compile-native-windows` | `windows-latest` (msys2) | `windows/x86_64/xsmcore.dll` | — |
| `package` | `ubuntu-24.04`（reusable-package） | 汇总全部进 JAR | C 单测 + JUnit + 逐项断言 6 个 native 文件 |

Android 产物无法在 CI 直接运行（无 arm64 Android 模拟器），用 ELF 校验 + 真机/模拟器上的 FCL 手动 E2E 兜底。

> **运行时 vs 编译**：`test` 的 8 组合矩阵保证源码在全部 MC × Xaero 组合下可编译 + JUnit 通过；`universal-e2e` 用发布产物（单个 universal jar）在全部 MC × newest Xaero 上真实启动，验证 mixin 的运行时应用（目标方法/字段在对应版本真实存在、注入点命中）与渲染链路。两个 job 互补，缺一不可。

### 失败诊断

- universal-e2e 失败先看上传的 `gametest.log`：无客户端日志 = 构建阶段挂（如 MinGW），有日志无断言 = 渲染/死锁问题。
- 截图 artifact 为空 = 从未到截图阶段（构建失败或打开地图前就挂）。

## 已知限制与踩坑记录

1. **服务端 `runGameTest` 被禁用**（`build.gradle` `enableGameTests = false`）：XaeroLib 的 `serverStarting` 只对 `DedicatedServer` 调 `freeze()`，game test server 下 registry 永不冻结会崩。我们只要客户端 E2E。
2. **CI 无 MinGW**：`compileNativeWindows`（交叉编译 Windows dll）只在有 MinGW 的主机构建；CI 一律 `-PskipNativeWindows=true` 或 `-x compileNativeWindows`。
3. **Android/FCL 产物不可在 CI 跑**：`compile-native-android` 用 NDK 编 bionic `.so`（arm64+x86_64），无法在 ubuntu runner 上 dlopen 验证，改用 ELF 校验（`file` + `readelf -d` DT_NEEDED 无 `libc.so.6`）等价复刻 FCL 的 `checkElfIsAndroid()`；真机/模拟器 FCL 手动 E2E 兜底。
4. **E2E 退出死锁（MC 26）**：`IntegratedServer.halt` 先 `executeBlocking` 等 server 线程，而 fabric client gametest 的 phaser 让 server 卡在 `postRunTasks` → 三线死锁。绕开：断言后 `runOnServer(server -> server.halt(false))`（server 线程内不阻塞）。
5. **fabric-client-gametest 跨版本 API**：5.1.x（26.1）`getClientLevel().waitForChunksRender()`；6.0.0（26.2）改用 `getConnection().waitForChunksRender()`——测试用反射兼容。同理 `Minecraft.setScreen` 在 26.2 移除，改 `setScreenAndShow`。
6. **网络同步器 bug**：production run task 加 `-Dfabric.client.gametest.disableNetworkSynchronizer=true`（fabric-docs warning）。
7. **Java 25 必需**；native 缺失时 native 集成测试自动跳过，但 CI 矩阵行总是完整执行。

## 参数命名约定

- `gradle.properties` 的 key **即** CI `-P` 覆盖的 key（camelCase）：`fabricApiVersion`、`xaeroMapLine`、`xaeroMapVersion`、`minecraft_version`、`loader_version`。本地可用 `gradle.local.properties`（gitignored）同格式覆盖。
- 其他构建开关：`-PskipNativeBuild`、`-PskipNativeWindows`、`-PjextractPath`、`-PclientGameTestXVFB`、`-PuniversalJar`（runProductionClientGameTestUniversal 用，指向预构建 universal jar）、`-PndkPath`（`compileNativeAndroid` 用，或环境变量 `ANDROID_NDK_HOME`）。
