#ifndef XAERO_SEED_MAP_APIS_RENDER_H
#define XAERO_SEED_MAP_APIS_RENDER_H

#include <stdint.h>

#include "../utils/micro.h"


// void GenerateBiomeImage(
//     int zoomLevel, // 已调整的 zoom
//     int worldX, // 方块坐标
//     int worldZ, // 方块坐标
//     int yLevel, // Y 高度
//     int shaderKind, // None/Simple/Stepped 地形阴影
//     int contourKind, // None/Simple 等高线
//     bool fade, // 边缘淡化
//     bool desaturate, // 去饱和度
//     bool highlight, // 高亮模式
//     bool showSlime // 史莱姆区块
// );


#if defined(__cplusplus)
extern "C" {
#endif

/// @brief 设置生物群系颜色表
/// @param colors 颜色表数组 size*2, 每2个为一组，代表BiomeID和颜色值(RGBA)
/// @param size 颜色表数组大小
/// @return false: 设置失败, 参数异常
XSM_API bool setBiomeColorTable(uint32_t* colors, uint32_t size);

/// @brief 设置生物群系颜色表为内置
XSM_API bool setBiomeColorTableNative(void);


/// @brief 设置游戏版本
/// @param version 版本字符串; 来自 `SharedConstants.getCurrentVersion().name()`
XSM_API bool setGameVersion(const char* version);

/// @brief 设置世界信息
/// @param seed 世界种子
/// @param dim 世界维度(-1: NETHER; 0: OVERWORLD; 1: THE_END)
/// @note 必须先执行`setGameVersion`
XSM_API bool setWorld(uint64_t seed, int dim);

/// @brief 设置生物群系禁用
/// @details 设置一个bitset，代表对应BiomeID的禁用状态;
/// 默认为全启用
/// @param bitset 位集数组, 每个位对应一个BiomeID, 1=禁用, 0=启用
/// @param size 位集数组大小
XSM_API bool setBiomeDisabled(const uint8_t* const bitset, uint32_t size);


/// @brief 生成指定位置的图像
/// @details 以生物群系为底，混合地形光照渲染; 
/// @details 输出固定为64*64像素, 包含方块有scale控制;
/// @details scale=1时，包含64*64方块; scale=4时，包含256*256方块;
/// @param scale 缩放因子, 支持1,4,16,64,256(256仅支持主世界)
/// @param worldX 世界坐标X
/// @param worldZ 世界坐标Z
/// @param absY 绝对高度（仅用于群系生成）
/// @param data 生成的图像数据, 32位 RGB
/// @param light 是否启用地形光照（Sobel坡面着色 + 深度暗化 + 水下染色）
/// @return 错误码; 0=成功
XSM_API uint32_t genCellImg(uint32_t scale, int32_t worldX, int32_t worldZ,
                     uint32_t absY, unsigned char* data, bool light);

/// @brief 查询指定方块位置的群系名称和地表高度
/// @param worldX 世界坐标X
/// @param worldZ 世界坐标Z
/// @param biomeName 输出缓冲区, 用于存储群系名称 (如 "plains")
/// @param biomeNameLen 缓冲区长度 (建议 >= 32)
/// @param height 输出地表高度
/// @return 错误码; 0=成功
XSM_API uint32_t queryPoint(int32_t worldX, int32_t worldZ,
                     char* biomeName, uint32_t biomeNameLen,
                     int32_t* height);

/// @brief 查询指定区块的精确地表高度（使用 generateRegion）
/// @param chunkX 区块坐标X
/// @param chunkZ 区块坐标Z
/// @param heightsOut 输出 256 个方块高度的数组 (16x16)
/// @return 错误码; 0=成功, -1=世界未设置, -2=不支持的维度/版本
XSM_API uint32_t queryExactChunkHeight(int32_t chunkX, int32_t chunkZ,
                                 int32_t* heightsOut);

/// @brief 获取结构配置
/// @param structureType 结构类型 ID
/// @param outSalt 输出 salt
/// @param outRegionSize 输出 regionSize
/// @param outChunkRange 输出 chunkRange
/// @param outDim 输出维度
/// @param outRarity 输出稀有度
/// @return 0=失败, 1=成功
XSM_API int32_t xsmGetStructureConfig(
    int32_t structureType,
    int32_t* outSalt,
    int32_t* outRegionSize,
    int32_t* outChunkRange,
    int32_t* outDim,
    float*   outRarity
);

/// @brief 获取结构类型数量(枚举最大值)
XSM_API int32_t xsmGetStructFEATURE_NUM(void);

/// @brief 批量查询某个结构类型在矩形 (rx0, rz0) ~ (rx1, rz1) 但不在矩形 (rx2, rz2) ~ (rx3, rz3) 区域内的结构位置。
/// @details 对每个 region (rx, rz) ∈ [rx0, rx1) × [rz0, rz1) 且 (rx, rz) ∉ [rx2, rx3) × [rz2, rz3)：
///   index 为x优先的遍历，抛除不在区域内的 region
///   如果该 region 有有效的结构生成点：
///     outFound[index] = 1
///     outBlockX[index] = pos.x
///     outBlockZ[index] = pos.z
///   否则：
///     outFound[index] = 0
/// 输出数组由调用方预分配，大小 = (rx1 - rx0) * (rz1 - rz0) - (rx3 - rx2) * (rz3 - rz2)。
XSM_API uint32_t queryRegionStructuresGrid(
    int32_t  structureType,
    int32_t  rx0, int32_t rz0,
    int32_t rx1, int32_t rz1,
    int32_t  rx2, int32_t rz2,
    int32_t rx3, int32_t rz3,
    int8_t*  outFound,
    int32_t* outBlockX,
    int32_t* outBlockZ
);

/// @brief 查询要塞精确位置, 返回 index ∈ [from, to) 的部分
/// @details 内部从 index 0 重放 RNG 链以保证链一致; 1.19.3+ 仅对目标区间
/// 做生物群系搜索(区间外以近似推进), 旧版本为保证 RNG 链会对全部 index 搜索
/// @param from 起始 index(含)
/// @param to 结束 index(不含)
/// @param outBlockX 输出 X, 容量 >= to-from
/// @param outBlockZ 输出 Z, 容量 >= to-from
/// @return 实际写入数量(可能小于 to-from, 如旧版仅 3 个要塞)
XSM_API uint32_t queryStrongholdsRange(
    int32_t from, int32_t to,
    int32_t* outBlockX,
    int32_t* outBlockZ
);

/// @brief 查询当前版本下, biomeId 对应的生物群系名称(key)
XSM_API bool xsmBiome2str(int32_t biomeId, char* out, uint32_t outLen);

#if DEBUG_TIMINGS
/// @brief 获取 gen 内部 4 段时间的累计纳秒数 (0=校验, 1=缓存分配,
/// 2=生物群系生成, 3=图像转换)
XSM_API void getGenTimings(uint64_t out[4]);

/// @brief 重置 gen 时间统计
XSM_API void resetGenTimings();

#endif

#if defined(__cplusplus)
}
#endif

#endif