# core — 通用 .sm4x 配置框架

类型无关、无 Minecraft 依赖的配置文件 IO，可被任意新增配置文件复用。

## Classes

- `Sm4xCodec<T>` — 一个配置文档的编解码器接口：`write(data, out)` / `read(in)`。
  实现只写数据体，magic 信封由 `Sm4xFile` 统一包装，实现类内部不要再写 magic word。
  数据体布局完全由实现自定（是否用版本号、版本号的位置与含义都是文档内部细节，框架不感知）。
  推荐惯例：`write` 开头写一个 version int，`read` 先读它再按历史版本分发，
  不支持的版本抛 `IOException`，历史版本保持 prefix-compatible 读取。
- `Sm4xFile` — 磁盘 IO（全 static）：
  - `MAGIC_WORD`（"SEEDMAP4XAERO"，全仓库唯一持有处）
  - `pathsFor(baseDir, mainId, fileName)` → `Sm4xPaths(target, tmp, old)`，目录按 `baseDir/<mainId>/` 平铺
  - `save(paths, data, codec)`：写 `.tmp` → 主文件轮替到 `.old` → `ATOMIC_MOVE` 提交
  - `load(paths, fallback, codec)`：主文件 → 损坏删主文件回退 `.old` → 都不行返回 `fallback`
  - `writeFrame` / `readFrame`：单帧读写（magic + version + body + magic）

## 新增一个 .sm4x 配置文件（如 structures.sm4x）

1. 新建包 `configs/<name>/`（不要混入 `basic/`）。
2. 写数据类 + 实现 `Sm4xCodec<T>`（参照 `basic.ConfigData.CODEC`；布局自定，
   推荐开头写 version int 以便升级）。
3. 文件名常量 + `Sm4xFile.pathsFor(base, mainId, "<name>.sm4x")` 生成路径；
   保存时用脏标志 CAS 控制（参照 `basic.ServerConfig.save`），在
   `ServerConfig.activate`/`deactivate` 的生命周期钩子处加载/刷脏（可按需加注册表）。
4. 单测直接用 `Sm4xFile.writeFrame/readFrame` + `@TempDir`，无需启动 MC。
