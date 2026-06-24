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