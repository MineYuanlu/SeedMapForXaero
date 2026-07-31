#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest/doctest.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <memory>
#include <vector>

#include "../../cubiomes/finders.h"
#include "../../cubiomes/util.h"
#include "../apis/render.h"

namespace {

struct Img {
  uint8_t* d = nullptr;
  int w = 64, h = 64;
  bool ok = false;
  Img() {
    d = (uint8_t*)malloc(w * h * 3);
    ok = d != nullptr;
  }
  ~Img() { free(d); }
  Img(const Img&) = delete;
  Img& operator=(const Img&) = delete;
  uint8_t* data() { return d; }
};

static void setupOrFail() {
  REQUIRE(setBiomeColorTableNative());
  REQUIRE(setGameVersion("26.1"));
  REQUIRE(setWorld(0, 0));
}

static void checkGen(Img& img, int scale, int worldX, int worldZ) {
  REQUIRE(img.ok);
  auto code = genCellImg(scale, worldX, worldZ, 64, img.data(), true);
  REQUIRE_MESSAGE(code == 0, "genCellImg(", scale, ", ", worldX, ", ", worldZ,
                  ") = ", code);
  // sanity: not all-zero
  uint32_t sum = 0;
  for (int i = 0; i < 64 * 64 * 3; i++)
    sum += img.d[i];
  REQUIRE_MESSAGE(sum != 0, "genCellImg(", scale, ", ", worldX, ", ", worldZ,
                  ") all-zero");
}

} // namespace

TEST_CASE("setup + smoke") {
  setupOrFail();
  Img img;
  checkGen(img, 4, 0, 0);
}

TEST_CASE("queryPoint") {
  setupOrFail();
  char name[32];
  int32_t h;
  for (int wx : {0, -1, 1000, -1000}) {
    for (int wz : {0, -1, 500, -500}) {
      auto code = queryPoint(wx, wz, name, sizeof(name), &h);
      INFO("queryPoint(", wx, ", ", wz, ")");
      CHECK(code == 0);
    }
  }
}

TEST_CASE("genCellImg scale=4 (per-pixel height)") {
  setupOrFail();
  Img img;
  std::mt19937 rng(123);
  std::uniform_int_distribution<int> dist(-1000000, 1000000);
  for (int i = 0; i < 20; i++) {
    int wx = dist(rng);
    int wz = dist(rng);
    checkGen(img, 4, wx, wz);
  }
}

TEST_CASE("queryRegionStructuresGrid") {
  setupOrFail();
  setWorld(0, 0);

  // 无排除, 不崩溃即可
  int8_t found[16];
  int32_t bx[16], bz[16];
  queryRegionStructuresGrid(25, 0, 0, 4, 4, 0, 0, 0, 0, found, bx, bz);

  // 超出边界的 region 应返回 0; type=1, rect [999999,1000000), 无排除
  int8_t found2[1];
  int32_t bx2[1], bz2[1];
  uint32_t n = queryRegionStructuresGrid(1, 999999, 999999, 1000000, 1000000, 0, 0, 0, 0, found2, bx2, bz2);
  CHECK(n == 0);

  // 完全被排除: include=[0,5)×[0,5), exclude=[0,5)×[0,5) → n=0
  int8_t found3[1];
  int32_t bx3[1], bz3[1];
  n = queryRegionStructuresGrid(25, 0, 0, 5, 5, 0, 0, 5, 5, found3, bx3, bz3);
  CHECK(n == 0);
}

TEST_CASE("querySparseStructures") {
  setupOrFail();

  // 稀疏类型 (regionSize=1) 的 id
  static const int sparseOverworld[] = {Treasure, Mineshaft, Desert_Well, Geode};
  const int N = 64;  // [0,64)×[0,64) = 4096 区块

  auto fullViaGrid = [&](int type, std::vector<int64_t>& out) {
    std::vector<int8_t> found((size_t)N * N);
    std::vector<int32_t> bx((size_t)N * N), bz((size_t)N * N);
    queryRegionStructuresGrid(type, 0, 0, N, N, 0, 0, 0, 0, found.data(), bx.data(), bz.data());
    for (int x = 0; x < N; x++)
      for (int z = 0; z < N; z++) {
        int idx = x * N + z;
        if (found[idx])
          out.push_back(((int64_t)bx[idx] << 32) | (bz[idx] & 0xFFFFFFFFL));
      }
  };

  for (int type : sparseOverworld) {
    // 1. 与 grid 全量一致
    std::vector<int64_t> expect;
    fullViaGrid(type, expect);
    std::vector<int64_t> got;
    int32_t xb[65536], zb[65536];
    int64_t next = -1;
    do {
      int64_t s = next;
      uint32_t n = querySparseStructures(type, 0, 0, N, N, 0, 0, 0, 0, s, 65536, xb, zb, &next);
      for (uint32_t i = 0; i < n; i++)
        got.push_back(((int64_t)xb[i] << 32) | (zb[i] & 0xFFFFFFFFL));
      if (next >= 0)
        CHECK(n == 65536);
    } while (next >= 0);
    CHECK_MESSAGE(got.size() == expect.size(), "type ", type, ": sparse=", got.size(),
                  " grid=", expect.size());
    for (size_t i = 0; i < expect.size(); i++)
      CHECK(got[i] == expect[i]);

    // 2. cap 拆分续传: 每次 cap=1, 逐条续传, 不重不漏
    std::vector<int64_t> got2;
    int64_t next2 = -1;
    do {
      int64_t s = next2;
      int32_t x1b, z1b;
      uint32_t n = querySparseStructures(type, 0, 0, N, N, 0, 0, 0, 0, s, 1, &x1b, &z1b, &next2);
      REQUIRE(n <= 1);
      if (n == 1)
        got2.push_back(((int64_t)x1b << 32) | (z1b & 0xFFFFFFFFL));
      if (next2 >= 0)
        REQUIRE(n == 1);
    } while (next2 >= 0);
    CHECK_MESSAGE(got2.size() == expect.size(), "type ", type, ": split=", got2.size());
    for (size_t i = 0; i < expect.size(); i++)
      CHECK(got2[i] == expect[i]);
  }

  // 3. 排除矩形: 排除全部 → 0
  int32_t xb[8], zb[8];
  int64_t next;
  uint32_t n = querySparseStructures(Mineshaft, 0, 0, 8, 8, 0, 0, 8, 8, -1, 8, xb, zb, &next);
  CHECK(n == 0);
  CHECK(next == -1);

  // 4. 负坐标: 与 grid 一致
  {
    std::vector<int64_t> expect, got;
    const int R = 32;
    const size_t total = (size_t)(2 * R) * (2 * R);  // 64x64 = 4096
    std::vector<int8_t> found(total);
    std::vector<int32_t> bxg(total), bzg(total);
    queryRegionStructuresGrid(Mineshaft, -R, -R, R, R, 0, 0, 0, 0, found.data(), bxg.data(), bzg.data());
    for (int x = -R; x < R; x++)
      for (int z = -R; z < R; z++) {
        int idx = (x + R) * 2 * R + (z + R);
        if (found[idx])
          expect.push_back(((int64_t)bxg[idx] << 32) | (bzg[idx] & 0xFFFFFFFFL));
      }
    int64_t next3 = -1;
    int32_t xa[4096], za[4096];
    do {
      int64_t s = next3;
      uint32_t m = querySparseStructures(Mineshaft, -R, -R, R, R, 0, 0, 0, 0, s, 4096, xa, za, &next3);
      for (uint32_t i = 0; i < m; i++)
        got.push_back(((int64_t)xa[i] << 32) | (za[i] & 0xFFFFFFFFL));
    } while (next3 >= 0);
    CHECK(got.size() == expect.size());
    for (size_t i = 0; i < expect.size(); i++)
      CHECK(got[i] == expect[i]);
  }

  // 5. End 类型: 仅 End 维度可查; End_Island 绕过 viability
  int32_t xe[4096], ze[4096];
  setWorld(0, 1);  // DIM_END
  n = querySparseStructures(End_Island, 0, 0, 64, 64, 0, 0, 0, 0, -1, 4096, xe, ze, &next);
  CHECK_MESSAGE(n > 0, "End_Island in End dim should be found, got ", n);
  n = querySparseStructures(End_Gateway, 0, 0, 64, 64, 0, 0, 0, 0, -1, 4096, xe, ze, &next);
  CHECK(next == -1);  // 单轮即可扫完(命中远小于 cap)
  // 错误维度: End_Island 在主世界 → 0
  setWorld(0, 0);  // DIM_OVERWORLD
  n = querySparseStructures(End_Island, 0, 0, 64, 64, 0, 0, 0, 0, -1, 4096, xe, ze, &next);
  CHECK(n == 0);
  CHECK(next == -1);

  // 6. 续传 + 排除矩形组合: cap=1 逐条续传, 排除 [8,24)×[8,24), 与 grid 去排除一致
  {
    const int R = 32;
    const size_t total = (size_t)R * R;
    std::vector<int8_t> found(total);
    std::vector<int32_t> bxg(total), bzg(total);
    queryRegionStructuresGrid(Mineshaft, 0, 0, R, R, 0, 0, 0, 0, found.data(), bxg.data(), bzg.data());
    std::vector<int64_t> expect, got;
    for (int x = 0; x < R; x++)
      for (int z = 0; z < R; z++) {
        if (x >= 8 && x < 24 && z >= 8 && z < 24)
          continue;
        int idx = x * R + z;
        if (found[idx])
          expect.push_back(((int64_t)bxg[idx] << 32) | (bzg[idx] & 0xFFFFFFFFL));
      }
    int64_t next4 = -1;
    do {
      int64_t s = next4;
      int32_t xa2, za2;
      uint32_t m = querySparseStructures(Mineshaft, 0, 0, R, R, 8, 8, 24, 24, s, 1, &xa2, &za2, &next4);
      REQUIRE(m <= 1);
      if (m == 1)
        got.push_back(((int64_t)xa2 << 32) | (za2 & 0xFFFFFFFFL));
      if (next4 >= 0)
        REQUIRE(m == 1);
    } while (next4 >= 0);
    CHECK_MESSAGE(got.size() == expect.size(), "excl+resume: ", got.size(), " vs ", expect.size());
    for (size_t i = 0; i < expect.size(); i++)
      CHECK(got[i] == expect[i]);
  }
}

TEST_CASE("queryStrongholdsRange") {
  setupOrFail();

  int32_t x[128], z[128];
  uint32_t n = queryStrongholdsRange(0, 128, x, z);
  REQUIRE_MESSAGE(n == 128, "queryStrongholdsRange(0,128) = ", n);

  // 确定性: 两次调用结果一致
  int32_t x2[128], z2[128];
  REQUIRE(queryStrongholdsRange(0, 128, x2, z2) == 128);
  for (uint32_t i = 0; i < 128; i++) {
    CHECK(x2[i] == x[i]);
    CHECK(z2[i] == z[i]);
  }

  // 区间拆分一致性: [0,64) + [64,128) == [0,128)
  int32_t x0[64], z0[64], x1[64], z1[64];
  REQUIRE(queryStrongholdsRange(0, 64, x0, z0) == 64);
  REQUIRE(queryStrongholdsRange(64, 128, x1, z1) == 64);
  for (int i = 0; i < 64; i++) {
    CHECK(x0[i] == x[i]);
    CHECK(z0[i] == z[i]);
    CHECK(x1[i] == x[i + 64]);
    CHECK(z1[i] == z[i + 64]);
  }

  // 范围 sanity: 全部在 ~27km 内 (最外环 ~24km)
  for (uint32_t i = 0; i < 128; i++) {
    int64_t dx = x[i], dz = z[i];
    CHECK_MESSAGE(dx * dx + dz * dz < 27000LL * 27000LL,
                  "stronghold ", i, " out of range: (", x[i], ", ", z[i], ")");
  }
}

TEST_CASE("genCellImg scale=1 (generateRegion)") {
  setupOrFail();
  Img img;
  // coordinates that create non-square chunk regions (chunkW != chunkH)
  // these triggered a cubiomes buffer-overflow bug
  int badZ[] = {1, -1, 2, -2, 15, 16, 17, -15, -16, -17};
  for (int z : badZ) {
    checkGen(img, 1, 0, z);
    checkGen(img, 1, z, 0);
  }
  // random
  std::mt19937 rng(42);
  std::uniform_int_distribution<int> dist(-500000, 500000);
  for (int i = 0; i < 50; i++) {
    int wx = dist(rng);
    int wz = dist(rng);
    checkGen(img, 1, wx, wz);
  }
}
