# Xaero 跨版本兼容

发布产物是**单个 universal jar**，只对最老的受支持 Xaero 编译一次，却要在
`versions.json` 覆盖的全部 MC × Xaero 组合上运行。因此凡是跨版本发生变化的
Xaero API，都必须用「对最老版本可编译、对新版本可运行」的写法接入。

本文按版本记录这类差异和规避手法。新增兼容问题请追加一节。

## 版本范围

`versions.json`（`tools/resolve_versions.py` 生成/校验）定义每个 MC 线的
`xaeroOldest` / `xaeroNewest`。矩阵 8 组合 = 4 MC × oldest/newest，E2E 只跑
newest。

## 排查手法

`matrix-test` 里 `test` 任务对 oldest/newest 都编译：**如果只有 newest 组合挂**，
基本就是 Xaero 在新版本改了签名。定位步骤：

```bash
# 1. 从 chocolateminecraft maven 拉新旧两个 jar
curl -o new.jar https://chocolateminecraft.com/maven/xaero/map/\
xaeroworldmap-fabric-<line>/<newVersion>/xaeroworldmap-fabric-<line>-<newVersion>.jar

# 2. 对比目标类的方法描述符
unzip -o new.jar 'xaero/map/**/Foo.class' -d new/
javap -p new/xaero/map/**/Foo.class
```

编译错误只会告诉我们「新版本不匹配」，**运行时的二进制不兼容**（在旧版本编译、
新版本运行时调用不到）不会在编译期暴露——覆盖 override 时要同时确认新旧两端都
会分派到自己的实现。

## 记录

### `RightClickOption.getDisplayName()` 返回类型 String → Component（1.42.0）

| 版本 | `getDisplayName()` |
| ---- | ------------------ |
| 1.40.14 / 1.41.0–1.41.3 | `String` |
| 1.42.0+ | `net.minecraft.network.chat.Component` |

**症状**：对 1.45.0 编译 `StructureRightClick` 时
`return type String is not compatible with Component`。universal jar 若仍以旧
签名覆写，在新版本运行时会静默调用到基类实现（前缀/分组名丢失），且不报错。

**规避**：`RightClickOption` 两个版本的 `getDisplayName()` 内部都调用
`protected String getName()`，而 `getName()` 在 1.40.14–1.45.0 签名稳定
（`protected String getName()`）。因此改为覆写 `getName()`，让基类
`getDisplayName()` 负责 String/Component 的差异：

```java
options.add(new RightClickOption("xsm.menu.group." + group, options.size(), this) {
    @Override
    protected String getName() {   // 不要覆写 getDisplayName()
        return prefix + I18n.get(...);
    }
    ...
});
```

见 `src/client/java/bid/yuanlu/seedmap4xaero/client/structure/StructureRightClick.java`。
