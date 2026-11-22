# Xaero Seed Map — 测试文档

三层测试：**JVM 单元测试**（快，无 MC 依赖）→ **native 集成测试**（真实 cubiomes）→ **E2E 客户端 GameTest**（真实启动 MC，CI 上跑）。全部由 CI 矩阵自动执行。

## 测试分层与覆盖

### 1. JVM 单元测试 — `src/test/java`（JUnit 5，20 个测试类 / ~180 用例）

无 native、无 MC 运行时的纯逻辑测试。核心覆盖（用例数以 CI JUnit 报告为准，避免数字漂移）：

- `ServerConfigTest` — 配置 JSON/legacy 读写往返、损坏/截断拒绝、`.old` 回退链、多服务器隔离、golden fixture 迁移
- `StructureDataTest` — marks 分片落盘/迁移/分组重写
- `StructureTypeTest` / `StructureBitFlagTest` / `StructureIconsTest` — 结构枚举、变种位码、图标零分配契约
- `JsonConfigFileTest` — 轮替/损坏矩阵/legacy 保全不变量
- `PerfConfigTest` — 性能配置钳制与监听器
- `GenSchedulerPriorityTest` — 优先级排序语义（预生成不饿死可见瓦片）
- `VanillaBiomeColorTest` / `BiomeTypeTest` / `CellKeyTest` / `BitSetViewTest` / `PageTest` / `LootDisplayModeTest` — 纯逻辑
- `NativeIntegrationTest` / `NativeVersionSwitchTest` — **见下一层**（native 可用时执行）
- `CompatGuiMethodMatchTest` / `CompatNoiseTest` / `HighlightHudRendererTest` / `HighlightedStructuresTest` — compat 形状匹配 / 噪声对照 / 渲染器

### 2. native 集成测试 — C doctest + `NativeIntegrationTest`

- **C 侧**：`src/main/c/xsm/test/unit_tests.cpp`（doctest，13 个 `TEST_CASE`）——`setup+smoke`、`queryPoint`、`genCellImg`（scale=4 逐像素高度 / scale=1 region）、`queryRegionStructuresGrid`、`querySparseStructures`、`queryStrongholdsRange`、`structure variants`。
- **Java 侧**：`NativeIntegrationTest extends NativeMcTest`。native 库（`libxsmcore`）缺失时整套 assumption 跳过（如 `-PskipNativeBuild`）；可用时执行真实 cubiomes 查询，断言跨 26.1/26.2 稳定的**事实值**（26 结构 id、维度、regionSize、biome id↔名、`queryPoint` 高度/确定性）而非逐版本快照。

### 3. E2E 客户端 GameTest — `src/gametest/java`（fabric-client-gametest）

`SeedMapClientGameTest`：真实启动 MC 客户端 → 创建单机世界（`KNOWN_SEED=123456789`）→ 打开 Xaero `GuiMap` → 5 个断言 + 截图：

1. **种子解析**：`ServerConfig.resolveSeed()` 返回已知种子且与服务器实际种子一致
2. **地图激活**：打开 `GuiMap` 后 `ServerConfig.activeMainId() != null`
3. **CellCache 生成**：scale 1/4/16 任一有缓存（验证 `tickWorldInfo → Xsm.setWorld → native C 生成` 链路）
4. **结构缓存**：`StructureCache.REGIONS` 非空（异步 CACHE_WORKER 查询）
5. **HUD 高亮背后隐藏**：从 REGIONS 高亮最近的真实结构，正视断言 `HighlightHudRenderer.lastFrameHighlightBlits == 1`、转向水平镜像点（结构恰好正背后）断言 `== 0`——回归 26.2 线性深度约定下背后点 `NDC.z<0` 漏过旧 `z>1` 检查导致的镜像图标 bug（版本相关深度约定的回归只能靠真实投影的 E2E 抓）。结构超 400 格时先服务端传送玩家到附近并等落地。

断言通过后打印 `seed-map E2E assertions passed` 供 CI grep。主 mod 和测试 mod 的 `fabric.mod.json` 版本区间（`minecraft_dep`/`loader_version`）由构建按节点 expand（modern `>=26.1` / legacy `>=1.21.11 <1.22`）——jar 在各自家族内通用，无需跟随矩阵精确版本。

## 版本矩阵与双 artifact 策略

- 构建是 **Stonecutter 多版本单源码树**：`versions/<MC 版本>/` 每目录一个节点
  （当前 `1.21.11`、`26.1`、`26.1.1`、`26.1.2`、`26.2`、`26.3`），共享根 `src/`。
- `versions.json` + `tools/resolve_versions.py`（update/check/matrix 三模式）按节点
  目录生成行（**支持的 MC 版本 = 节点目录 ∩ piston release**），矩阵 = 每行
  oldest/newest Xaero 两组合。行带 `artifact` 字段（modern/legacy）与按家族的
  `java`（25/21）。
- 版本覆盖用 `-P` 传入：CI 矩阵换 Xaero 端点用**节点作用域** key
  `-PxaeroMapVersion.<节点>=...`（`-P` 是 build 全局属性，一次调用会配置全部
  stonecutter 节点，全局 key 会毒化兄弟节点）；fabricApi/minimap 等由
  versions.json 行按节点 id 提供，`-P` 可覆盖。
- `refresh-versions.yml` 每周一自动跑 `update` 并仅在有真实变更时提交。
- **发布产物是双 jar**：
  - **modern universal**（`:26.1.2` 产出，`X.Y.Z+mc26.x`）：`"minecraft": ">=26.1"`
    通吃 26.1–26.3；编译目标取本线最老 Xaero（1.40.14），引用符号是全家族子集；
    搬家类型 API 经 `compat/` 反射缝适配（src-modern/）。
  - **legacy**（`:1.21.11` 产出，`X.Y.Z+mc1.21.11`）：`"minecraft": ">=1.21.11 <1.22"`；
    mojmap 编译 → loom remapJar 到 intermediary；1.21.11 无反射必要（单版本），
    compat 直调强类型（src-legacy/），JNI 传输层两线共用。
- 未来 MC 若有破坏性变更，由 CI 的 E2E（矩阵随 versions.json 自动增长）提前暴露；
  新增支持版本 = 建 `versions/<MC>/` 节点目录 + settings.gradle 注册节点。

## 运行

### 本地

```bash
# JVM 单元测试 + native 集成测试（按节点；-x compileNativeWindows 跳过 Windows 交叉编译）
./gradlew :26.1.2:build :1.21.11:build -x compileNativeWindows

# C 单元测试（独立，doctest）
cmake -S src/main/c -B build-test -DCMAKE_BUILD_TYPE=Release
cmake --build build-test --target xsmtest
XSM_TEST_MC_VERSION=<mc> ./build-test/xsmtest   # 可选指定 MC 版本常量

# E2E client gametest（需真实显示或用 -PclientGameTestXVFB=true 无头）
./gradlew :26.1.2:runProductionClientGameTest -PskipNativeWindows=true

# 用预构建发布 jar 跑 E2E（验证发布产物，不在目标版本上重编译 mod）
# 排除 -sources.jar：其 fabric.mod.json 是未展开的 ${version} 模板，误载会解析失败
./gradlew :26.1.2:runProductionClientGameTestUniversal -PskipNativeWindows=true \
  -PuniversalJar="$(find versions/26.1.2/build/libs -name 'seed-map-for-xaero-*.jar' ! -name '*-sources.jar' | head -1)"
./gradlew :1.21.11:runProductionClientGameTestUniversal -PskipNativeWindows=true \
  -PuniversalJar="$(find versions/1.21.11/build/libs -name 'seed-map-for-xaero-*.jar' ! -name '*-sources.jar' | head -1)"
```

**无头环境注意（MC 26.3+）**：26.3 渲染栈换成 SDL3+renderpearl，其 `GlDevice`
探针窗口带 `SDL_WINDOW_TRANSPARENT` 且强制 sRGB framebuffer，Xvfb 的 GLX
匹配不到 depth-32 fbconfig（`Couldn't find matching GLX visual`），GL/Vulkan 双
backend 失败后客户端会挂死。CI 在 `universal-e2e` 设
`SDL_VIDEODRIVER=offscreen`（SDL 走 EGL surfaceless 软渲染，绕开 X11/GLX；
需 `libegl1 libegl-mesa0` 包）解决。26.1/26.2 走 GLFW 不读 `SDL_*` 变量，
同一 env 无副作用。本地无显示器跑 26.3+ 时同理：`SDL_VIDEODRIVER=offscreen
./gradlew runProductionClientGameTestUniversal ...`（需系统装了 libEGL，
如 `apt install libegl1 libegl-mesa0`）。

### CI（`.github/workflows/matrix-test.yml` + native 矩阵）

单人开发流程（GitHub Flow）：`dev/xxx`、`fix/xxx` 只跑 `pr-check.yml` 快速检查；**PR → master 与 master 推送是全面门禁**（`matrix-test.yml` 版本矩阵 + E2E 与 `build.yml` 全平台 native + 打包都触发）。

- `resolve`：校验 `versions.json` 新鲜度（过期仅告警不阻塞）+ 输出两套矩阵
  - `matrix`：每节点 × oldest/newest Xaero（行数随 versions.json 增长，当前 6 节点 = 12 组合），`test` 用
  - `matrix-e2e`：每节点一行 × **newest** Xaero（当前 6 组合），`universal-e2e` 用
- `test`（每节点 oldest/newest 各一行，**源码兼容预警**）：`cmake` C 单测（带 `XSM_TEST_MC_VERSION`）→ `./gradlew build -x compileNativeWindows`（含 JUnit）。保证源码在全部 MC × Xaero 组合下可编译 + JUnit 通过；产物是编译载体，不发布
- `build-universal`（1 job）：编两个发布 jar（`:26.1.2:modJar` + `:1.21.11:modJar`），按 artifact 家族上传 `universal-jar` / `legacy-jar` artifact
- `universal-e2e`（每节点一行，依赖 `build-universal`）：真实启动 MC 的 E2E，**按行 artifact 字段复用对应发布 jar**，`./gradlew runProductionClientGameTestUniversal -PskipNativeWindows=true -PuniversalJar=<artifact>` + 版本 `-P` 覆盖，grep `seed-map E2E assertions passed` 判定成功；每组合的 `gametest.log` + `run/screenshots` 按 `mc` 命名始终上传
- `gate`（聚合，固定名字）：任一上游 job 失败/取消/跳过即失败；branch protection 的 required check 用它（动态矩阵 job 名含版本号会变，不能作 required check）。`build.yml` 同样有聚合 job `build-gate`（reusable workflow 调用的 check 名是 `调用job / 被调job` 格式）

**native 架构矩阵**：定义在 `reusable-native-build.yml`（5 个 compile job），被 `build.yml`（master/tag push + PR → master）、`release.yml`、`build-test-jar.yml` 通过 `uses:` 调用；汇总打包统一在 `reusable-package.yml`（下载 natives → universal JAR，含内置校验）。feature 分支/PR 的快速检查在 `pr-check.yml`。

| Job | Runner | 产物 | 验证 |
| --- | ------ | ---- | ---- |
| `compile-native-linux` | `ubuntu-24.04` | `linux/x86_64/libxsmcore.so` | —（package 阶段跑 C 单测） |
| `compile-native-linux-arm` | `ubuntu-24.04-arm`（原生） | `linux/aarch64/libxsmcore.so` | 该 job 内原生跑 `xsmtest` |
| `compile-native-android` | `ubuntu-24.04` + NDK（版本取 `.github/toolchain-versions.json`） | `android/arm64` + `android/x86_64`（bionic） | ELF 校验：`file` 确认 ABI + `readelf -d` DT_NEEDED 无版本化 SONAME（等价 FCL `checkElfIsAndroid`） |
| `compile-native-macos` | `macos-15` | `macos/universal/libxsmcore.dylib` | `lipo -info` 确认双架构 |
| `compile-native-windows` | `windows-2025` (msys2) | `windows/x86_64/xsmcore.dll` | — |
| `package` | `ubuntu-24.04`（reusable-package） | 汇总全部进 JAR | C 单测 + JUnit + 逐项断言 6 个 native 文件 |

Android 产物无法在 CI 直接运行（无 arm64 Android 模拟器），用 ELF 校验 + 真机/模拟器上的 FCL 手动 E2E 兜底。

> **运行时 vs 编译**：`test` 的全节点 × oldest/newest 矩阵保证源码在全部 MC × Xaero 组合下可编译 + JUnit 通过；`universal-e2e` 用发布产物（按行 artifact 家族选 modern/legacy jar）在全部 MC × newest Xaero 上真实启动，验证 mixin 的运行时应用（目标方法/字段在对应版本真实存在、注入点命中）与渲染链路。两个 job 互补，缺一不可。

### 失败诊断

- universal-e2e 失败先看上传的 `gametest.log`：无客户端日志 = 构建阶段挂（如 MinGW），有日志无断言 = 渲染/死锁问题。
- 截图 artifact 为空 = 从未到截图阶段（构建失败或打开地图前就挂）。

## 已知限制与踩坑记录

1. **服务端 `runGameTest` 被禁用**（`build.gradle` `enableGameTests = false`）：XaeroLib 的 `serverStarting` 只对 `DedicatedServer` 调 `freeze()`，game test server 下 registry 永不冻结会崩。我们只要客户端 E2E。
2. **CI 无 MinGW**：`compileNativeWindows`（交叉编译 Windows dll）只在有 MinGW 的主机构建；CI 一律 `-PskipNativeWindows=true` 或 `-x compileNativeWindows`。
3. **Android/FCL 产物不可在 CI 跑**：`compile-native-android` 用 NDK 编 bionic `.so`（arm64+x86_64），无法在 ubuntu runner 上 dlopen 验证，改用 ELF 校验（`file` + `readelf -d` DT_NEEDED 无 `libc.so.6`）等价复刻 FCL 的 `checkElfIsAndroid()`；真机/模拟器 FCL 手动 E2E 兜底。
4. **E2E 退出死锁（MC 26）**：`IntegratedServer.halt` 先 `executeBlocking` 等 server 线程，而 fabric client gametest 的 phaser 让 server 卡在 `postRunTasks` → 三线死锁。绕开：断言后 `runOnServer(server -> server.halt(false))`（server 线程内不阻塞）。
5. **fabric-client-gametest 跨版本 API**：4.x（1.21.11）`getClientWorld().waitForChunksRender(boolean,int)`；5.1.x（26.1）`getClientLevel().waitForChunksRender()`；6.0.0（26.2+）改用 `getConnection().waitForChunksRender()`——测试按序反射兼容。同理 `Minecraft.setScreen` 在 26.2 移除，改 `setScreenAndShow`。
6. **网络同步器 bug**：production run task 加 `-Dfabric.client.gametest.disableNetworkSynchronizer=true`（fabric-docs warning）。
7. **Java 25 必需**；native 缺失时 native 集成测试自动跳过，但 CI 矩阵行总是完整执行。

## 参数命名约定

- 依赖版本真值源 = 根 `versions.json`；build.gradle 按 camelCase key（`fabricApiVersion`、`xaeroMapLine`、`xaeroMinimapVersion` 等）读取并支持 CI `-P` 覆盖（缺省取 versions.json 行）。MC/loader 线别在 `versions/<节点>/gradle.properties`；Xaero 端点例外，用节点作用域 key `xaeroMapVersion.<节点名>`。本地可用 `gradle.local.properties`（gitignored）同格式覆盖。
- 其他构建开关：`-PskipNativeBuild`、`-PskipNativeWindows`、`-PclientGameTestXVFB`、`-PuniversalJar`（runProductionClientGameTestUniversal 用，指向预构建 universal jar）、`-PndkPath`（`compileNativeAndroid` 用，或环境变量 `ANDROID_NDK_HOME`）。
