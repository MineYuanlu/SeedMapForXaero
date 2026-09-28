# perf — perf_config.json（全局性能配置）

`gameDir/xaero/seed-map-for-xaero/global/perf_config.json`。
**JVM 级全局语义**（线程数/缓存容量不随服务器切换），单文件，client init 时
`PerfConfig.init()` 加载一次，不随世界切换 activate/deactivate。
无 legacy 前身（v2 新增配置），`loadConfig` 传 `legacyCodec=null` 跳过迁移链；
损坏 → 回退默认值。

## Classes

- `PerfConfig` — 门面：静态便捷读写 + `flush()`（rotate=false，无生命周期切换点，
  `.old` 恒缺省）+ `addListener(Runnable)` 变更通知（CacheHelper 重建线程池、
  CellCache 调整预算等）。setter 只标脏 + 通知；落盘靠 60s 周期 flush
  （`XaeroSeedMapClient`）与面板显式 flush。
- `PerfConfigData` — 数据体 + `JSON_CODEC`。字段全部写出（文件自描述）；
  读端缺失字段落默认值 + 数值钳制（单字段容错）。

## JSON 文档结构（v1）

```
{ "version": 1,
  "generationThreads": 0,      // 0=自动 min(4, cores/4)，手动 1..cores（setter 钳 0..64）
  "cacheCapacityMB": 128,      // CellCache 全局 LRU 预算 32..512；1MB=64 条(16KB/条)
  "prefetchEnabled": true,     // 视野外圈预取
  "pregenEnabled": false,      // 主动预生成（默认关）
  "pregenRadiusBlocks": 8192,  // 1024..65536
  "diskCacheEnabled": false,   // 磁盘缓存开关（设计规范见 doc/perf.md，未实现）
  "diskCacheMaxMB": 256,       // 32..4096
  "debugOverlay": false }      // 性能统计 HUD
```

## Thread safety

`active` volatile；`flush`/`saveCurrent` synchronized；`PerfConfigData` setter
synchronized + 相等短路 + `AtomicBoolean dirty`（CAS-claim，写盘失败恢复脏标志）。
监听器列表 `CopyOnWriteArrayList`，在配置变化后同步触发（渲染线程），
回调必须轻量。
