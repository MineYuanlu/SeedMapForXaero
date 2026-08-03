# gui — SeedMapPanel side panel & icon textures

## SeedMapPanel

- Toggle via gear button (bottom-left of map screen, `XsmIconButton`)
- Sections: biomes (collapsible with search), structures (collapsible with search)
- Per-biome toggle → updates `WorldConfig.disabledBiomes` → C-side `setBiomeDisabled`
- Per-structure toggle → updates `WorldConfig.disabledStructure` (bit0 = type)
- Structure list is a **tree, always expanded**: variant-bearing types (`getVariants()` non-empty) show each variant code indented below; per-variant toggle → `WorldConfig.disabledStructure` variant bit (render-only, no cache clear). **结构整类禁用时不显示变种行**
- Search also matches variant names (`variantTranslationKey`) — auto-shows only matching variant rows
- Structure icon size slider (0.05~2.0, polynomial mapping: 0.5@t=0.25)
- All text via `Component.translatable()` — see `lang/*.json`

## Icon textures

- `biomes.png` sprite sheet + `biomes.ini` (sprite index); `BiomeType` loads from `biomes.ini` at init — must regenerate `biomes.png` if biome list changes (`tools/gen_biomes_icon.py`)
- Structure icons: `structures.png` (20×20 outlined) + `structures_plain.png` (16×16); regenerate with `tools/gen_structures_icon.py`
