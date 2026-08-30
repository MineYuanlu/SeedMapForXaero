# configs — persistence framework & basic config

```
configs/
├── core/    # 通用 .sm4x 框架（类型无关，无 MC 依赖）— 见 core/AGENTS.md
└── basic/   # server_config.sm4x 的数据与门面 — 见 basic/AGENTS.md
```

后续新增配置文件（如 structures.sm4x）自建新包（`configs/<name>/`），
实现一个 `Sm4xCodec` + 调 `Sm4xFile` 即可，各包独立。
