#include "render.h"

#include <stdlib.h>
#include <string.h>

#if DEBUG_TIMINGS
#include <atomic>
#include <chrono>
#endif
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include "../utils/mutex.h"

#include "../../cubiomes/generator.h"
#include "../../cubiomes/terrainnoise.h"
#include "../../cubiomes/util.h"
#include "../../cubiomes/finders.h"

namespace {
static const std::unordered_map<std::string, MCVersion> mcVersionMap = {
    {"b1.7", MC_B1_7},       {"b1.7.2", MC_B1_7},   {"b1.7.3", MC_B1_7},
    {"b1.8", MC_B1_8},       {"b1.8.1", MC_B1_8},   {"1.0", MC_1_0},
    {"1.1", MC_1_1},         {"1.2", MC_1_2_5},     {"1.2.1", MC_1_2_5},
    {"1.2.2", MC_1_2_5},     {"1.2.3", MC_1_2_5},   {"1.2.4", MC_1_2_5},
    {"1.2.5", MC_1_2_5},     {"1.3", MC_1_3_2},     {"1.3.1", MC_1_3_2},
    {"1.3.2", MC_1_3_2},     {"1.4", MC_1_4_7},     {"1.4.2", MC_1_4_7},
    {"1.4.4", MC_1_4_7},     {"1.4.5", MC_1_4_7},   {"1.4.6", MC_1_4_7},
    {"1.4.7", MC_1_4_7},     {"1.5", MC_1_5_2},     {"1.5.1", MC_1_5_2},
    {"1.5.2", MC_1_5_2},     {"1.6", MC_1_6_4},     {"1.6.1", MC_1_6_4},
    {"1.6.2", MC_1_6_4},     {"1.6.4", MC_1_6_4},   {"1.7", MC_1_7_10},
    {"1.7.2", MC_1_7_10},    {"1.7.3", MC_1_7_10},  {"1.7.4", MC_1_7_10},
    {"1.7.5", MC_1_7_10},    {"1.7.6", MC_1_7_10},  {"1.7.7", MC_1_7_10},
    {"1.7.8", MC_1_7_10},    {"1.7.9", MC_1_7_10},  {"1.7.10", MC_1_7_10},
    {"1.8", MC_1_8_9},       {"1.8.1", MC_1_8_9},   {"1.8.2", MC_1_8_9},
    {"1.8.3", MC_1_8_9},     {"1.8.4", MC_1_8_9},   {"1.8.5", MC_1_8_9},
    {"1.8.6", MC_1_8_9},     {"1.8.7", MC_1_8_9},   {"1.8.8", MC_1_8_9},
    {"1.8.9", MC_1_8_9},     {"1.9", MC_1_9_4},     {"1.9.1", MC_1_9_4},
    {"1.9.2", MC_1_9_4},     {"1.9.3", MC_1_9_4},   {"1.9.4", MC_1_9_4},
    {"1.10", MC_1_10_2},     {"1.10.1", MC_1_10_2}, {"1.10.2", MC_1_10_2},
    {"1.11", MC_1_11_2},     {"1.11.1", MC_1_11_2}, {"1.11.2", MC_1_11_2},
    {"1.12", MC_1_12_2},     {"1.12.1", MC_1_12_2}, {"1.12.2", MC_1_12_2},
    {"1.13", MC_1_13_2},     {"1.13.1", MC_1_13_2}, {"1.13.2", MC_1_13_2},
    {"1.14", MC_1_14_4},     {"1.14.1", MC_1_14_4}, {"1.14.2", MC_1_14_4},
    {"1.14.3", MC_1_14_4},   {"1.14.4", MC_1_14_4}, {"1.15", MC_1_15_2},
    {"1.15.1", MC_1_15_2},   {"1.15.2", MC_1_15_2}, {"1.16", MC_1_16_1},
    {"1.16.1", MC_1_16_1},   {"1.16.2", MC_1_16_1}, {"1.16.3", MC_1_16_1},
    {"1.16.4", MC_1_16_1},   {"1.16.5", MC_1_16_5}, {"1.17", MC_1_17_1},
    {"1.17.1", MC_1_17_1},   {"1.18", MC_1_18_2},   {"1.18.1", MC_1_18_2},
    {"1.18.2", MC_1_18_2},   {"1.19", MC_1_19_2},   {"1.19.1", MC_1_19_2},
    {"1.19.2", MC_1_19_2},   {"1.19.3", MC_1_19_2}, {"1.19.4", MC_1_19_4},
    {"1.20", MC_1_20_6},     {"1.20.1", MC_1_20_6}, {"1.20.2", MC_1_20_6},
    {"1.20.3", MC_1_20_6},   {"1.20.4", MC_1_20_6}, {"1.20.5", MC_1_20_6},
    {"1.20.6", MC_1_20_6},   {"1.21", MC_1_21_1},   {"1.21.1", MC_1_21_1},
    {"1.21.2", MC_1_21_1},   {"1.21.3", MC_1_21_3}, {"1.21.4", MC_1_21_4},
    {"1.21.5", MC_1_21_5},   {"1.21.6", MC_1_21_5}, {"1.21.7", MC_1_21_5},
    {"1.21.8", MC_1_21_5},   {"1.21.9", MC_1_21_9}, {"1.21.10", MC_1_21_9},
    {"1.21.11", MC_1_21_11}, {"26.1", MC_26_1},     {"26.1.1", MC_26_1},
    {"26.1.2", MC_26_1},     {"26.2", MC_26_2}};

    static constexpr const size_t MAX_BIOMES = 256;

xsm::mutex setting_mtx;

/// 生物群系颜色表
/// key为BiomeID，value为颜色值(RGB)
unsigned char biomeColorTable[MAX_BIOMES][3];
bool bct_set = false;  ///< 是否已经设置生物群系颜色表

bool biomeColorDisabled[MAX_BIOMES]={};
unsigned char biomeColorTableMask[MAX_BIOMES][3];

/// 生成器实例（TerrainNoise 内嵌 Generator g 作为第一成员，&tn.g 即为 Generator*）
TerrainNoise tn{};
bool gen_setGameVersion = false;  ///< 是否已经设置游戏版本
bool gen_setWorld = false;        ///< 是否已经设置世界信息

#if DEBUG_TIMINGS
/// gen 时间统计 (纳秒累计)
std::atomic<uint64_t> timing_check{0};
std::atomic<uint64_t> timing_alloc{0};
std::atomic<uint64_t> timing_genbiomes{0};
std::atomic<uint64_t> timing_toimage{0};
#endif

// ===== Terrain Lighting Tuning Knobs (adjust then rebuild) =====
static const float XSM_SHADE_FACTOR     = 0.50f;   // 坡度影响强度
static const float XSM_MIN_LIGHT        = 0.60f;   // 最低亮度 multiplier
static const float XSM_MAX_LIGHT        = 1.40f;   // 最高亮度 multiplier
static const float XSM_DEPTH_MIN        = 0.85f;   // y=0 处的深度暗化系数
static const float XSM_AQUA_RED_GREEN   = 0.85f;   // 水下红/绿衰减
static const float XSM_AQUA_BLUE        = 0.90f;   // 水下蓝衰减
}  // namespace


/// @brief 从 biomeColorTable 根据 biomeColorDisabled 掩码写入
/// biomeColorTableMask
void updateMaskedBiomeColorTable(void) {
  for (int i = 0; i < MAX_BIOMES; i++) {
    for (int j = 0; j < 3; j++) {
      if (biomeColorDisabled[i]) {
        biomeColorTableMask[i][j] = 0;  // 0x000000
      } else {
        biomeColorTableMask[i][j] = biomeColorTable[i][j];
      }
    }
  }
}

bool setBiomeColorTable(uint32_t* colors, uint32_t size) {
  std::lock_guard<xsm::mutex> lock(setting_mtx);
  if (colors == NULL && size != 0) return false;
  if (size > MAX_BIOMES) return false;

  memset(biomeColorTable, 0, MAX_BIOMES * 3);

  for (uint32_t i = 0; i < size; i++) {
    auto id = colors[i * 2];
    auto hex = colors[i * 2 + 1];
    biomeColorTable[id][0] = (hex >> 16) & 0xff;
    biomeColorTable[id][1] = (hex >> 8) & 0xff;
    biomeColorTable[id][2] = (hex >> 0) & 0xff;
  }
  updateMaskedBiomeColorTable();
  bct_set = true;
  return true;
}


bool setBiomeColorTableNative(void) {
  std::lock_guard<xsm::mutex> lock(setting_mtx);
  initBiomeColors(biomeColorTable);
  updateMaskedBiomeColorTable();
  bct_set = true;
  return true;
}


bool setGameVersion(const char* version) {
  std::lock_guard<xsm::mutex> lock(setting_mtx);
  const auto v = mcVersionMap.find(std::string(version));
  if (v == mcVersionMap.end()) return false;
  memset(&tn.ss, 0, sizeof(tn.ss));
  setupTerrainNoise(&tn, v->second, 0);
  gen_setWorld = false;
  gen_setGameVersion = true;
  return true;
}


bool setWorld(uint64_t seed, int dim) {
  std::lock_guard<xsm::mutex> lock(setting_mtx);
  if (!gen_setGameVersion) return false;
  initTerrainNoise(&tn, seed, dim);
  gen_setWorld = true;
  return true;
}

XSM_API bool setBiomeDisabled(const uint8_t* const bitset, uint32_t size) {
  std::lock_guard<xsm::mutex> lock(setting_mtx);
  for (uint32_t i = 0; i <MAX_BIOMES; i++) {
    const uint32_t idx = i / 8;
    const bool bit = idx < size ? bitset[idx] & (1 << (i % 8)) : 0;
    biomeColorDisabled[i] = bit;
  }
  if (bct_set) updateMaskedBiomeColorTable();
  return true;
}


static constexpr const uint32_t PIXEL_PER_TILE = 64;

/// 获取 worldX/worldZ 处的真实地表高度（方块单位），仅适用于 26.1+ (1.18+)。
static float getSurfaceHeight(const Generator* g, int32_t worldX, int32_t worldZ) {
  if (g->dim == DIM_NETHER) return 127.0f;
  if (g->dim == DIM_END)    return 63.0f;
  int64_t np[6];
  sampleBiomeNoise(&g->bn, np, worldX >> 2, 0, worldZ >> 2, NULL, 0);
  return (float)(np[NP_DEPTH] / 76.0);
}

/// 获取当前版本/维度的世界最低 Y。
/// 1.18+ Overworld = -64，其余维度/旧版 = 0。
/// 用于 generateRegion 局部 Y（0-based）到世界 Y 的转换。
static int getWorldBottomY() {
    if (tn.g.mc >= MC_1_18 && tn.g.dim == DIM_OVERWORLD) return -64;
    return 0;
}

/// heights 数组步长（带 padding 后的宽度）
static constexpr const uint32_t HSTRIDE = PIXEL_PER_TILE + 2;  // 66

/// @param pixels  [w*h*3] RGB 字节，就地修改
/// @param heights [(w+2)*(h+2)] 带 1 像素 padding 的逐像素高度场（方块单位）
/// @param biomes  [w*h] 群系 ID
/// @param blocksPerPixel 每像素代表的方块数（= scale），用于归一化 Sobel 梯度
/// @param seaLevel 海平面 Y（通常 63）
static void applyTerrainLighting(unsigned char* pixels,
    const float* heights, const int* biomes,
    int w, int h, float blocksPerPixel, float seaLevel) {
  for (int pz = 0; pz < h; pz++) {
    for (int px = 0; px < w; px++) {
      // 带 padding 的索引：中心 64×64 映射到 heights 中 (1..64)×(1..64)
      int hi = (pz + 1) * (int)HSTRIDE + (px + 1);
      float h = heights[hi];

      // ---- Stage 1: Sobel 坡面着色（从 padding 读取邻居，无 clamp）----
      float hL = heights[hi - 1];
      float hR = heights[hi + 1];
      float hT = heights[hi - (int)HSTRIDE];
      float hB = heights[hi + (int)HSTRIDE];

      // 归一化到每方块坡度，跨 scale 一致
      float dx = (hR - hL) * 0.5f / blocksPerPixel;
      float dz = (hB - hT) * 0.5f / blocksPerPixel;

      float cos = (1.0f + dz) / (sqrtf(1.0f + dx*dx + dz*dz) * 1.41421356f);
      float light = 1.0f + (cos - 0.70710678f) * XSM_SHADE_FACTOR * 2.0f;
      if (light < XSM_MIN_LIGHT) light = XSM_MIN_LIGHT;
      if (light > XSM_MAX_LIGHT) light = XSM_MAX_LIGHT;

      // ---- Apply Sobel ----
      int poff = (pz * w + px) * 3;
      float r = (float)pixels[poff]     * light;
      float g = (float)pixels[poff + 1] * light;
      float b = (float)pixels[poff + 2] * light;

      // ---- Stage 2: 水下染色 ----
      if (h < seaLevel || isOceanic(biomes[pz * w + px])) {
        r *= XSM_AQUA_RED_GREEN;
        g *= XSM_AQUA_RED_GREEN;
        b *= XSM_AQUA_BLUE;
      }

      // ---- Stage 3: 深度暗化 ----
      float t = h / 128.0f;
      if (t > 1.0f) t = 1.0f;
      float depthMul = XSM_DEPTH_MIN + t * (1.0f - XSM_DEPTH_MIN);
      r *= depthMul;
      g *= depthMul;
      b *= depthMul;

      // ---- 钳位写回 ----
      pixels[poff]     = (unsigned char)(r > 255.0f ? 255 : (r < 0 ? 0 : r + 0.5f));
      pixels[poff + 1] = (unsigned char)(g > 255.0f ? 255 : (g < 0 ? 0 : g + 0.5f));
      pixels[poff + 2] = (unsigned char)(b > 255.0f ? 255 : (b < 0 ? 0 : b + 0.5f));
    }
  }
}

/// 从 4 方块网格双线性插值生成带 padding 的高度图（scale=1 专用，避免网格）。
static void fillHeightsInterp(float* heights, const Generator* g,
    int worldX, int worldZ) {
  static constexpr int MARGIN_BLOCKS = 8;  // 覆盖 padding 后的全范围
  int worldMinX = worldX - MARGIN_BLOCKS;
  int worldMinZ = worldZ - MARGIN_BLOCKS;
  int worldMaxX = worldX + PIXEL_PER_TILE + MARGIN_BLOCKS;
  int worldMaxZ = worldZ + PIXEL_PER_TILE + MARGIN_BLOCKS;

  int bcMinX = worldMinX >> 2;
  int bcMinZ = worldMinZ >> 2;
  int bcMaxX = (worldMaxX + 3) >> 2;
  int bcMaxZ = (worldMaxZ + 3) >> 2;
  int cw = bcMaxX - bcMinX + 1;
  int ch = bcMaxZ - bcMinZ + 1;

  float coarse[22 * 22];  // 最大 cw/ch=22，见注释
  for (int cz = 0; cz < ch; cz++)
    for (int cx = 0; cx < cw; cx++)
      coarse[cz * cw + cx] = getSurfaceHeight(g, (bcMinX + cx) * 4 + 2, (bcMinZ + cz) * 4 + 2);

  for (int pz = 0; pz < (int)HSTRIDE; pz++) {
    for (int px = 0; px < (int)HSTRIDE; px++) {
      float wx = (float)(worldX - 1) + (float)px + 0.5f;  // padding 偏移
      float wz = (float)(worldZ - 1) + (float)pz + 0.5f;

      float bcx = wx / 4.0f - (float)bcMinX;
      float bcz = wz / 4.0f - (float)bcMinZ;

      int ix0 = (int)bcx;  if (ix0 < 0) ix0 = 0;
      int iz0 = (int)bcz;  if (iz0 < 0) iz0 = 0;
      int ix1 = ix0 + 1;   if (ix1 >= cw) ix1 = cw - 1;
      int iz1 = iz0 + 1;   if (iz1 >= ch) iz1 = ch - 1;

      float fx = bcx - (float)ix0;
      float fz = bcz - (float)iz0;

      heights[pz * HSTRIDE + px] =
          (1-fx)*(1-fz) * coarse[iz0*cw+ix0]
        +     fx *(1-fz) * coarse[iz0*cw+ix1]
        + (1-fx)*    fz  * coarse[iz1*cw+ix0]
        +     fx *    fz  * coarse[iz1*cw+ix1];
    }
  }
}

uint32_t genCellImg(uint32_t scale, int32_t worldX, int32_t worldZ, uint32_t absY,
             unsigned char* data, bool light) {
  XSM_TIME_POINT(t1);
  if (!gen_setWorld) return -1;
  switch (scale) {
    case 1: case 4: case 16: case 64: break;
    case 256: if (tn.g.dim != DIM_OVERWORLD) return -4; break;
    default: return -2;
  }

  XSM_TIME_POINT(t2);
  auto cacheSize =
      getMinCacheSize(&tn.g, scale, PIXEL_PER_TILE, 1, PIXEL_PER_TILE);
  if (cacheSize == 0) return -3;
  auto cache = std::make_unique<int[]>(cacheSize);

  XSM_TIME_POINT(t3);
  genBiomes(&tn.g, cache.get(),
            {(int)scale, worldX / (int)scale, worldZ / (int)scale,
             PIXEL_PER_TILE, PIXEL_PER_TILE, (int)absY, 1});

  XSM_TIME_POINT(t4);
  biomesToImage(data, biomeColorTableMask, cache.get(), PIXEL_PER_TILE,
                PIXEL_PER_TILE, 1, 1);

  XSM_TIME_POINT(t5);

  if (light && tn.g.dim == DIM_OVERWORLD) {
    float heights[HSTRIDE * HSTRIDE];
    if ((int)scale < 4) {
      fillHeightsInterp(heights, &tn.g, worldX, worldZ);
    } else {
      float hscale = (float)scale;
      for (int pz = 0; pz < (int)HSTRIDE; pz++)
        for (int px = 0; px < (int)HSTRIDE; px++) {
          int wx = worldX + (int)((float)(px - 1) * hscale) + (int)(hscale * 0.5f);
          int wz = worldZ + (int)((float)(pz - 1) * hscale) + (int)(hscale * 0.5f);
          heights[pz * HSTRIDE + px] = floorf(getSurfaceHeight(&tn.g, wx, wz));
        }
    }
    applyTerrainLighting(data, heights, cache.get(),
                         PIXEL_PER_TILE, PIXEL_PER_TILE,
                         (float)scale, 63.0f);
  }

  XSM_TIME_ADD(timing_check, t1, t2);
  XSM_TIME_ADD(timing_alloc, t2, t3);
  XSM_TIME_ADD(timing_genbiomes, t3, t4);
  XSM_TIME_ADD(timing_toimage, t4, t5);

  return 0;
}

#if DEBUG_TIMINGS
void getGenTimings(uint64_t out[4]) {
  out[0] = timing_check.load(std::memory_order_relaxed);
  out[1] = timing_alloc.load(std::memory_order_relaxed);
  out[2] = timing_genbiomes.load(std::memory_order_relaxed);
  out[3] = timing_toimage.load(std::memory_order_relaxed);
}

void resetGenTimings() {
  timing_check.store(0, std::memory_order_relaxed);
  timing_alloc.store(0, std::memory_order_relaxed);
  timing_genbiomes.store(0, std::memory_order_relaxed);
  timing_toimage.store(0, std::memory_order_relaxed);
}

#endif

uint32_t queryPoint(int32_t worldX, int32_t worldZ,
                     char* biomeName, uint32_t biomeNameLen,
                     int32_t* height) {
  if (!gen_setWorld) return -1;
  if (biomeNameLen < 1) return -2;

  int id;

  if (tn.g.mc >= MC_1_18 && tn.g.dim == DIM_OVERWORLD) {
    int64_t np[6];
    id = sampleBiomeNoise(&tn.g.bn, np, worldX >> 2, 0, worldZ >> 2,
                          NULL, 0);
    *height = (int32_t)(np[NP_DEPTH] / 76.0f);
  }
  else {
    id = getBiomeAt(&tn.g, 1, worldX, 63, worldZ);

    if (tn.g.dim == DIM_NETHER)
      *height = 127;
    else if (tn.g.dim == DIM_END)
      *height = getEndSurfaceHeight(tn.g.mc, tn.g.seed,
                                    worldX, worldZ);
    else if (tn.g.mc < MC_1_18)
    {
      double d, s;
      getBiomeDepthAndScale(id, &d, &s, NULL);
      *height = (int32_t)(64.0 + d * 4.0 * s);
    }
    else
      *height = 63;
  }

  const char* name = id >= 0 ? biome2str(tn.g.mc, id) : NULL;
  if (!name) {
    strncpy(biomeName, "unknown", biomeNameLen - 1);
    biomeName[biomeNameLen - 1] = '\0';
    *height = 0;
    return -3;
  }

  strncpy(biomeName, name, biomeNameLen - 1);
  biomeName[biomeNameLen - 1] = '\0';
  return 0;
}

uint32_t queryExactChunkHeight(int32_t chunkX, int32_t chunkZ,
                                int32_t* heightsOut) {
  if (!gen_setWorld) return -1;
  if (tn.g.mc < MC_1_18 || tn.g.dim != DIM_OVERWORLD) return -2;

  generateRegion(&tn, chunkX, chunkZ, 1, 1, NULL, heightsOut, 1);
  int bottom = getWorldBottomY();
  for (int i = 0; i < 256; i++) {
    heightsOut[i] += bottom - 1;
  }
  return 0;
}

int32_t xsmGetStructureConfig(
    int32_t structureType,
    int32_t* outSalt, int32_t* outRegionSize,
    int32_t* outChunkRange, int32_t* outDim, float* outRarity)
{
    StructureConfig sconf;
    if (!getStructureConfig(structureType, tn.g.mc, &sconf))
        return 0;
    *outSalt       = sconf.salt;
    *outRegionSize = sconf.regionSize;
    *outChunkRange = sconf.chunkRange;
    *outDim        = sconf.dim;
    *outRarity     = sconf.rarity;
    return 1;
}

int32_t xsmGetStructFEATURE_NUM(void){
  return FEATURE_NUM;
}

uint32_t queryRegionStructuresGrid(int32_t structureType, int32_t rx0,
                                   int32_t rz0, int32_t rx1, int32_t rz1,
                                   int32_t rx2, int32_t rz2, int32_t rx3,
                                   int32_t rz3, int8_t* outFound,
                                   int32_t* outBlockX, int32_t* outBlockZ) {
  if (!gen_setWorld) return 0;


  uint32_t index = 0;
  uint32_t cnt = 0;
  for (int32_t x = rx0; x < rx1; x++) {
    const bool inX = rx2 <= x && x < rx3;
    for (int32_t z = rz0; z < rz1; z++) {
      const bool inZ = rz2 <= z && z < rz3;
      if (inX && inZ) continue;

      const auto idx = index++;

      Pos pos;
      if (!getStructurePos(structureType, tn.g.mc, tn.g.seed, x, z, &pos)) {
        outFound[idx] = 0;
        continue;
      }
      if (!isViableStructurePos(structureType, &tn.g, pos.x, pos.z, 0)) {
        outFound[idx] = 0;
        continue;
      }
      outFound[idx] = 1;
      outBlockX[idx] = pos.x;
      outBlockZ[idx] = pos.z;
      ++cnt;
    }
  }
  return cnt;
}

uint32_t queryStrongholdsRange(int32_t from, int32_t to,
                               int32_t* outBlockX, int32_t* outBlockZ) {
  if (!gen_setWorld || tn.g.dim != DIM_OVERWORLD) return 0;
  if (to <= from || !outBlockX || !outBlockZ) return 0;

  Generator g;
  setupGenerator(&g, tn.g.mc, 0);
  applySeed(&g, DIM_OVERWORLD, tn.g.seed);

  const bool canSkip = tn.g.mc > MC_1_19_2;
  StrongholdIter sh;
  initFirstStronghold(&sh, tn.g.mc, tn.g.seed & MASK48);
  uint32_t n = 0;
  for (int k = 0; k < to; k++) {
    const bool want = (k >= from);
    const Generator* gk = (canSkip && !want) ? NULL : &g;
    int rem = nextStronghold(&sh, gk);
    if (rem < 0) break;
    if (want) {
      outBlockX[n] = sh.pos.x;
      outBlockZ[n] = sh.pos.z;
      n++;
    }
    if (rem == 0) break;
  }
  return n;
}


bool xsmBiome2str(int32_t biomeId, char* out, uint32_t outLen) {
  if (!gen_setGameVersion) return false;
  const char* const name = biome2str(tn.g.mc, biomeId);
  if (!name) return false;
  strncpy(out, name, outLen - 1);
  return true;
}