# MC 跨版本兼容层（compat/，双家族）

**双 artifact 家族**（Stonecutter 单源码树）：
- **modern**（26.1–26.3，unobf，Java 25）：单 universal jar 对最老线编译、运行于全部
  支持版本 → 版本敏感 API 必须**反射缝收编**（`src-modern/client/java/…/compat/`），
  业务代码零版本敏感 import。
- **legacy**（≤1.21.11，混淆线，Java 21）：每版本独立编译 + loom 重映射到 intermediary，
  mojmap 名字的反射串在 intermediary 运行时**必然失效** → compat **直接强类型调用**
  （`src-legacy/client/java/…/compat/`），重映射交给 loom。

两家族 compat 门面的**公开 API 签名保持一致**（业务代码零感知），新增门面方法时
`src-modern` 与 `src-legacy` 两侧同步。共享 compat 类（`CompatPose`/`CompatNoise`/
`CompatInput`）放 `src/client/java/…/compat/`，家族差异用 stonecutter 条件注释内联。

## 为什么必须这样做

JVM 方法/字段/构造器的二进制描述符**包含类型 FQN**。MC 26.3 把
`com.mojang.blaze3d.textures.*`、`blaze3d.systems.GpuDevice`、`blaze3d.pipeline.RenderPipeline`
整体迁到 `com.mojang.renderpearl.api.*`。于是：

- 对 26.1.2 编译的 jar，任何强类型调用点（哪怕 FQN 没变，如 `RenderSystem.getDevice()` 的
  返回类型、`TextureSetup.singleTexture(view, sampler)` 的参数类型）在 26.3 运行时
  `NoSuchMethodError` / `NoSuchFieldError`——**编译期完全不可见**（对 26.3 源码编译也能过）。
- 反过来（对 26.3 编译、跑 26.1）同理。CI 的 8 组合编译矩阵保证源码可编译，但无法覆盖运行时
  描述符断裂；`universal-e2e`（同一 jar 真实启动）才能抓到。

这类断裂随 MC 大版本高频出现（26.2 getter 改名、26.3 renderpearl 迁移），故统一收编。

## 强约束（pr-check 有 grep 强制）

`com.mojang.blaze3d.{textures,systems,pipeline}`、`com.mojang.renderpearl.*`、
`net.minecraft.world.level.levelgen.synth.*` **只允许在 `compat/` 内 import**
（覆盖 `src/`、`src-modern/`、`src-legacy/` 三棵源码树）。
modern 线新缝出现时的做法：在 compat 门面加方法 + 反射解析（modern 侧）+ 直调实现
（legacy 侧），调用方改调门面，不许在业务代码绕过。

## modern 反射缝清单与解析策略

解析全部基于**运行时类型扫描**（`getMethod(...).getReturnType()`、按实参运行时类匹配重载），
不写死任何一端独有的 FQN；MethodHandle/Constructor 在首次使用时（渲染线程）解析一次并缓存，
逐帧零解析开销。capability 判断 = 天然版本自适应。

| 类 | 缝 | 说明 |
| --- | --- | --- |
| `CompatTextures` | `RenderSystem.getDevice()`、`GpuDevice.createTexture/createTextureView`、`CommandEncoder.writeToTexture`、`GpuTextureAndView` 构造器与 `view` 字段、`MultiTextureRenderTypeRenderer.begin`、`AbstractTexture.getTextureView/getSampler`、`TextureSetup.singleTexture`、`RenderPipelines.GUI_TEXTURED`、`BlitRenderState` 13 参构造器 | 纹理创建/上传/瓦片绘制/图标 blit 全链路 |
| `CompatGui` | `GuiGraphicsExtractor.tooltip`（26.1 六参 / 26.3 七参）、`blit`/`blitSprite` 带 pipeline 重载、`Minecraft.screen`（26.1 字段）→ `Minecraft.gui.screen()`（26.2+ 访问器） | tooltip 参数个数；pipeline 参数类型搬家；blitSprite 按**精确形参形状**匹配（见下）；当前 screen 字段 26.2 移除 |
| `CompatPose` | 无（`mulPose(Matrix4fc)` 是 26.1/26.3 类型交集） | `rotateYDegrees` 替代被删的 `mulPose(Quaternionf)` |
| `CompatNoise` | 无（纯自实现） | 26.3 移除 `PerlinSimplexNoise` 的逐位对齐副本，见下 |

重载匹配注意：`GpuDevice.createTexture` 有 `(String,…)` 与 `(Supplier<String>,…)` 两个 7 参
重载，谓词必须区分 `parameterTypes()[0]`（26.3 上 `getMethods()` 顺序与 26.1 不同，E2E 抓过）。
`blitSprite` 同理且更隐蔽：26.1/26.3 上 `(…, int×4, float)` 与 `(…, int×4, int)` 两个 7 参
重载并存，只按参数个数匹配时 26.3 顺序未定义地命中 int 版 → `invoke(float)` 抛
`WrongMethodTypeException`（issue #32，面板群系渲染稳定崩溃）。因此 `blit`/`blitSprite`
一律按**精确形参形状**（逐位基本类型）匹配：优先取 float（alpha）版本，仅剩 int（color）
版本时降级为 `color = alpha<<24 | 0xFFFFFF`（ARGB tint，语义等价）。

## FQN 稳定（可保持强类型）的判例

截至 26.3：`TextureSetup`、`BlitRenderState`、`GuiRenderState`、`AbstractTexture`、
`NativeImage`、`PoseStack`、`VertexConsumer`/`BufferBuilder`、`InputConstants`、
`GuiGraphicsExtractor` 及其 `fill/text/item` 等（legacy 线为 `GuiGraphics`/`fill/drawString/renderItem`）、Xaero `GpuTextureAndView`/
`MultiTextureRenderTypeRenderer`/`MapRenderHelper`/`ImprovedFramebuffer`（签名已核对
1.40.14 ↔ 1.46.4 一致）。这些若在未来版本搬家，修复点 = compat 内的 import + 解析逻辑。

## CompatNoise（PerlinSimplexNoise 副本）

MC 26.3 彻底移除该类且无等价 API（`NoiseStack` 范式不同、无法逐位对齐）。swamp 草色噪声
使用形态固定为 `new PerlinSimplexNoise(RandomSource.create(0), List.of(0))`，退化后只是
单个 `SimplexNoise`（种子 0 的 LegacyRandomSource），故按 26.1.2 反编译逐位复刻为
`CompatNoise`。`CompatNoiseTest` 在存在 vanilla 类的版本上做 4000+ 点**逐位**对照回归
（26.3+ 无对照目标，退化为确定性断言）。

## 新 MC 版本发布的操作清单

1. 建 stonecutter 节点：`versions/<MC>/gradle.properties`（loader/minecraft_dep）+
   `settings.gradle` 注册节点版本。
2. 跑 `tools/resolve_versions.py update` 更新 `versions.json`（resolver 按节点目录
   过滤行；Xaero/minimap/fabricApi 线自动解析）。
3. 编译矩阵暴露新断点 → modern 线在 compat 门面加缝/调整解析（legacy 线若该版本
   也支持，同步直调侧）；业务代码不动。
4. C 侧 `render.cpp` `mcVersionMap` 加新版本映射（worldgen 未变则映射到上一版本，
   如 26.3→MC_26_2）+ 同步 `Xsm.SUPPORTED_VERSIONS`（按家族条件注释）。
5. 本地复刻 E2E：编 jar → `:<MC>:runProductionClientGameTestUniversal`
   + `-PclientGameTestXVFB=true`（无头），grep `E2E assertions passed`。
6. worldgen 若有变（E2E 事实断言失败）→ 升级 cubiomes 子模块，另立任务。

## 双向 replacements 安全守则（stonecutter.gradle.kts）

机械改名（`GuiGraphicsExtractor↔GuiGraphics`、`text↔drawString`、mixin 目标
`extractRenderState↔render` 等）走 controller 的 replacements。⚠ stonecutter 的
replacement 是**双向**的：条件为真的节点应用正向 from→to，条件为假的节点应用
**反向 to→from**。因此每条规则必须验证：**to 串（反向目标）在 modern 源码零出现**、
**from 串在 legacy 语义下唯一**。regex 规则用 `\b\B`（永不匹配）作反向模式实现
单向替换。无法安全双向化的差异（如 `ChestLootWidget` 方法声明会反向命中 modern 的
`void render(GuiGraphicsExtractor…)`）必须用源码内联条件注释，不许进 replacements。

## 已知限制

- `CellCache` TTL 驱逐的 GPU 纹理依赖 GC 释放：26.1/26.3 的 `GpuDevice` 均无 destroy API，
  非兼容层可解，属 MC 侧资源管理方式。
- `GameRenderer.projectPointToScreen` 的 NDC z 不可用于跨版本判前后：26.2/26.3 的 `Projection`
  交换 near/far 做 reversed-Z，且 `isZZeroToOne` 取决于驱动是否支持 `GL_ARB_clip_control`。
  HUD 前后判定必须用 `Camera.forwardVector()` 点积（`HighlightHudRenderer.isBehind`）。
- `CompatGui.tooltip` 26.3 的第 7 参（单行 -2px 微调）传 `false`，与 26.1 观感一致；
  多行 tooltip 场景无差异。
- 反射调用点务必把 `unreflect` 出的实例方法句柄的**接收者**作为首参传入（`CompatGui.tooltip`
  早期漏传 `GuiGraphicsExtractor` 导致 `WrongMethodTypeException` 崩溃，见 `blit*` 的正确写法）。
- chest 战利品标题 tooltip 已有 E2E 覆盖（`SeedMapClientGameTest.assertLootTitleTooltipNoCrash`
  驱动真实渲染帧命中标题）；面板 blitSprite 路径的重载选择由 `CompatGuiMethodMatchTest`
  覆盖（模拟 26.1/26.3 两种方法表），渲染路径由 E2E 的群系区展开截图覆盖
  （`panel-biomes`，断言 `CompatGui.blitSpriteCallCount` 增长 + 未崩溃）。群系区之外
  的低频 GUI 状态（tooltip 悬停变体等）仍靠 26.1/26.3 双端人工开图兜底。
