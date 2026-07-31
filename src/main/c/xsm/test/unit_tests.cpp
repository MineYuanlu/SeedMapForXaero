#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest/doctest.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <memory>

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
