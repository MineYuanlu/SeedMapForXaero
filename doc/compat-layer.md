# MC 跨版本兼容层（client/compat/）

单 universal jar（对最老支持线编译，运行于全部支持版本）+ 多版本编译矩阵的组合，
决定了版本敏感 API 的**唯一可持续写法**：反射缝收编进独立子包，业务代码零版本敏感 import。

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
`net.minecraft.world.level.levelgen.synth.*` **只允许在 `client/compat/` 内 import**。
新缝出现时的做法：在 compat 门面加方法 + 反射解析，调用方改调门面，不许在业务代码绕过。

## 反射缝清单与解析策略

解析全部基于**运行时类型扫描**（`getMethod(...).getReturnType()`、按实参运行时类匹配重载），
不写死任何一端独有的 FQN；MethodHandle/Constructor 在首次使用时（渲染线程）解析一次并缓存，
逐帧零解析开销。capability 判断 = 天然版本自适应。

| 类 | 缝 | 说明 |
| --- | --- | --- |
| `CompatTextures` | `RenderSystem.getDevice()`、`GpuDevice.createTexture/createTextureView`、`CommandEncoder.writeToTexture`、`GpuTextureAndView` 构造器与 `view` 字段、`MultiTextureRenderTypeRenderer.begin`、`AbstractTexture.getTextureView/getSampler`、`TextureSetup.singleTexture`、`RenderPipelines.GUI_TEXTURED`、`BlitRenderState` 13 参构造器 | 纹理创建/上传/瓦片绘制/图标 blit 全链路 |
| `CompatGui` | `GuiGraphicsExtractor.tooltip`（26.1 六参 / 26.3 七参）、`blit`/`blitSprite` 带 pipeline 重载 | tooltip 参数个数；pipeline 参数类型搬家 |
| `CompatPose` | 无（`mulPose(Matrix4fc)` 是 26.1/26.3 类型交集） | `rotateYDegrees` 替代被删的 `mulPose(Quaternionf)` |
| `CompatNoise` | 无（纯自实现） | 26.3 移除 `PerlinSimplexNoise` 的逐位对齐副本，见下 |

重载匹配注意：`GpuDevice.createTexture` 有 `(String,…)` 与 `(Supplier<String>,…)` 两个 7 参
重载，谓词必须区分 `parameterTypes()[0]`（26.3 上 `getMethods()` 顺序与 26.1 不同，E2E 抓过）。

## FQN 稳定（可保持强类型）的判例

截至 26.3：`TextureSetup`、`BlitRenderState`、`GuiRenderState`、`AbstractTexture`、
`NativeImage`、`PoseStack`、`VertexConsumer`/`BufferBuilder`、`InputConstants`、
`GuiGraphicsExtractor` 及其 `fill/text/item` 等、Xaero `GpuTextureAndView`/
`MultiTextureRenderTypeRenderer`/`MapRenderHelper`/`ImprovedFramebuffer`（签名已核对
1.40.14 ↔ 1.46.4 一致）。这些若在未来版本搬家，修复点 = compat 内的 import + 解析逻辑。

## CompatNoise（PerlinSimplexNoise 副本）

MC 26.3 彻底移除该类且无等价 API（`NoiseStack` 范式不同、无法逐位对齐）。swamp 草色噪声
使用形态固定为 `new PerlinSimplexNoise(RandomSource.create(0), List.of(0))`，退化后只是
单个 `SimplexNoise`（种子 0 的 LegacyRandomSource），故按 26.1.2 反编译逐位复刻为
`CompatNoise`。`CompatNoiseTest` 在存在 vanilla 类的版本上做 4000+ 点**逐位**对照回归
（26.3+ 无对照目标，退化为确定性断言）。

## 新 MC 版本发布的操作清单

1. 跑 `tools/resolve_versions.py update` 更新 `versions.json`（Xaero/minimap/fabricApi 线）。
2. 编译矩阵暴露新断点 → 在 compat 门面加缝/调整解析（业务代码不动）。
3. C 侧 `render.cpp` `mcVersionMap` 加新版本映射（worldgen 未变则映射到上一版本，
   如 26.3→MC_26_2）+ 同步 `Xsm.SUPPORTED_VERSIONS`。
4. 本地复刻 universal-e2e：对最老线编 jar → `runProductionClientGameTestUniversal`
   带 26.3 版本覆盖 + `-PclientGameTestXVFB=true`（无头），grep `E2E assertions passed`。
5. worldgen 若有变（E2E 事实断言失败）→ 升级 cubiomes 子模块，另立任务。

## 已知限制

- `CellCache` TTL 驱逐的 GPU 纹理依赖 GC 释放：26.1/26.3 的 `GpuDevice` 均无 destroy API，
  非兼容层可解，属 MC 侧资源管理方式。
- `CompatGui.tooltip` 26.3 的第 7 参（单行 -2px 微调）传 `false`，与 26.1 观感一致；
  多行 tooltip 场景无差异。
- chest 战利品 tooltip / 面板 blitSprite 等低频 GUI 路径无 E2E 覆盖，靠 26.1/26.3 双端
  人工开图验证兜底。
