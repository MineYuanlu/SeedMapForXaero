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

// MC 版本来自环境变量 XSM_TEST_MC_VERSION (矩阵化测试用), 默认 26.1
const char* testMcVersion() {
  const char* v = std::getenv("XSM_TEST_MC_VERSION");
  return (v != nullptr && v[0] != '\0') ? v : "26.1";
}

// 当前测试版本的 cubiomes MCVersion 枚举
static MCVersion testMcEnum() {
  const int32_t mc = xsmGetMCVersion(testMcVersion());
  REQUIRE_MESSAGE(mc > MC_UNDEF, "unknown MC version for test: ", testMcVersion());
  return (MCVersion)mc;
}

static void setupOrFail() {
  REQUIRE(setBiomeColorTableNative());
  REQUIRE_MESSAGE(setGameVersion(testMcVersion()),
                  "setGameVersion(", testMcVersion(), ") failed — unsupported MC version");
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
  queryRegionStructuresGrid(25, 0, 0, 4, 4, 0, 0, 0, 0, found, bx, bz, nullptr);

  // 超出边界的 region 应返回 0; type=1, rect [999999,1000000), 无排除
  int8_t found2[1];
  int32_t bx2[1], bz2[1];
  uint32_t n = queryRegionStructuresGrid(1, 999999, 999999, 1000000, 1000000, 0, 0, 0, 0, found2, bx2, bz2, nullptr);
  CHECK(n == 0);

  // 完全被排除: include=[0,5)×[0,5), exclude=[0,5)×[0,5) → n=0
  int8_t found3[1];
  int32_t bx3[1], bz3[1];
  n = queryRegionStructuresGrid(25, 0, 0, 5, 5, 0, 0, 5, 5, found3, bx3, bz3, nullptr);
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
    queryRegionStructuresGrid(type, 0, 0, N, N, 0, 0, 0, 0, found.data(), bx.data(), bz.data(), nullptr);
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
      uint32_t n = querySparseStructures(type, 0, 0, N, N, 0, 0, 0, 0, s, 65536, xb, zb, nullptr, &next);
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
      uint32_t n = querySparseStructures(type, 0, 0, N, N, 0, 0, 0, 0, s, 1, &x1b, &z1b, nullptr, &next2);
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
  uint32_t n = querySparseStructures(Mineshaft, 0, 0, 8, 8, 0, 0, 8, 8, -1, 8, xb, zb, nullptr, &next);
  CHECK(n == 0);
  CHECK(next == -1);

  // 4. 负坐标: 与 grid 一致
  {
    std::vector<int64_t> expect, got;
    const int R = 32;
    const size_t total = (size_t)(2 * R) * (2 * R);  // 64x64 = 4096
    std::vector<int8_t> found(total);
    std::vector<int32_t> bxg(total), bzg(total);
    queryRegionStructuresGrid(Mineshaft, -R, -R, R, R, 0, 0, 0, 0, found.data(), bxg.data(), bzg.data(), nullptr);
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
      uint32_t m = querySparseStructures(Mineshaft, -R, -R, R, R, 0, 0, 0, 0, s, 4096, xa, za, nullptr, &next3);
      for (uint32_t i = 0; i < m; i++)
        got.push_back(((int64_t)xa[i] << 32) | (za[i] & 0xFFFFFFFFL));
    } while (next3 >= 0);
    CHECK(got.size() == expect.size());
    for (size_t i = 0; i < expect.size(); i++)
      CHECK(got[i] == expect[i]);
  }

  // 5. End 类型: 仅 End 维度可查; 1.18+ End_Island 仅在小岛群系 (small_end_islands)
  //    区块命中 (真实浮岛只在其中生成, 其余群系标记为虚假)
  int32_t xe[4096], ze[4096];
  setWorld(0, 1);  // DIM_END
  n = querySparseStructures(End_Island, 0, 0, 64, 64, 0, 0, 0, 0, -1, 4096, xe, ze, nullptr, &next);
  CHECK_MESSAGE(n > 0, "End_Island in End dim should be found, got ", n);
  Generator eg;
  setupGenerator(&eg, testMcEnum(), 0);
  applySeed(&eg, DIM_END, 0);
  for (uint32_t i = 0; i < n; i++)
    CHECK_MESSAGE(getBiomeAt(&eg, 16, xe[i] >> 4, 0, ze[i] >> 4) == small_end_islands,
                  "End_Island hit at (", xe[i], ", ", ze[i],
                  ") not in small_end_islands biome");
  n = querySparseStructures(End_Gateway, 0, 0, 64, 64, 0, 0, 0, 0, -1, 4096, xe, ze, nullptr, &next);
  CHECK(next == -1);  // 单轮即可扫完(命中远小于 cap)
  // 错误维度: End_Island 在主世界 → 0
  setWorld(0, 0);  // DIM_OVERWORLD
  n = querySparseStructures(End_Island, 0, 0, 64, 64, 0, 0, 0, 0, -1, 4096, xe, ze, nullptr, &next);
  CHECK(n == 0);
  CHECK(next == -1);

  // 6. 续传 + 排除矩形组合: cap=1 逐条续传, 排除 [8,24)×[8,24), 与 grid 去排除一致
  {
    const int R = 32;
    const size_t total = (size_t)R * R;
    std::vector<int8_t> found(total);
    std::vector<int32_t> bxg(total), bzg(total);
    queryRegionStructuresGrid(Mineshaft, 0, 0, R, R, 0, 0, 0, 0, found.data(), bxg.data(), bzg.data(), nullptr);
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
      uint32_t m = querySparseStructures(Mineshaft, 0, 0, R, R, 8, 8, 24, 24, s, 1, &xa2, &za2, nullptr, &next4);
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

namespace {

/// 全量查询 region 矩形 [rx0,rx1)×[rz0,rz1), 返回 (x,z,variant) 命中列表
struct Hit {
  int32_t x, z, v;
};
std::vector<Hit> queryHits(int type, int rx0, int rz0, int rx1, int rz1) {
  const size_t total = (size_t)(rx1 - rx0) * (rz1 - rz0);
  std::vector<int8_t> found(total);
  std::vector<int32_t> bx(total), bz(total), vr(total);
  queryRegionStructuresGrid(type, rx0, rz0, rx1, rz1, 0, 0, 0, 0,
                            found.data(), bx.data(), bz.data(), vr.data());
  std::vector<Hit> out;
  for (int x = rx0; x < rx1; x++)
    for (int z = rz0; z < rz1; z++) {
      size_t idx = (size_t)(x - rx0) * (rz1 - rz0) + (z - rz0);
      if (found[idx])
        out.push_back({bx[idx], bz[idx], vr[idx]});
    }
  return out;
}

} // namespace

TEST_CASE("structure variants") {
  setupOrFail();
  setWorld(0, 0);

  // 1. 末地城船判定 (seed 0, 末地; 位置为实测确定值)
  setWorld(0, 1);
  {
    auto hits = queryHits(End_City, 0, 0, 40, 40);
    bool hasShip = false, hasNoShip = false;
    for (auto& h : hits) {
      bool okBits = (h.v & ~XSM_VAR_END_CITY_SHIP) == 0;
      CHECK_MESSAGE(okBits, "End_City bad variant bits: ", h.v);
      if (h.x == 80 && h.z == 3280)
        CHECK_MESSAGE(h.v == XSM_VAR_END_CITY_SHIP,
                      "expected ship at (80,3280), got ", h.v);
      if (h.x == 64 && h.z == 2304)
        CHECK_MESSAGE(h.v == 0, "expected no-ship at (64,2304), got ", h.v);
      if (h.v) hasShip = true;
      else hasNoShip = true;
    }
    bool bothShipVariants = hasShip && hasNoShip;
    CHECK_MESSAGE(bothShipVariants, "range should contain both ship variants");
  }

  // 2. 村庄类型 + 僵尸村 (seed 0, 主世界; 位置为实测确定值)
  setWorld(0, 0);
  {
    auto hits = queryHits(Village, 0, 0, 50, 50);
    std::vector<int> typeSeen(5, 0);
    bool seenZombie = false;
    for (auto& h : hits) {
      int type = h.v & XSM_VAR_VILLAGE_TYPE_MASK;
      bool okType = type >= 0 && type <= 4;
      CHECK_MESSAGE(okType, "bad village type in ", h.v);
      bool okBits = (h.v & ~(XSM_VAR_VILLAGE_TYPE_MASK | XSM_VAR_VILLAGE_ZOMBIE)) == 0;
      CHECK_MESSAGE(okBits, "bad village bits: ", h.v);
      if (h.v & XSM_VAR_VILLAGE_ZOMBIE)
        seenZombie = true;
      typeSeen[type]++;
    }
    for (int t = 0; t < 5; t++)
      CHECK_MESSAGE(typeSeen[t] > 0, "village type ", t, " not found");
    CHECK_MESSAGE(seenZombie, "no zombie village in range");
    // 确定性: 实测确定的 (x,z)->variant
    for (auto& h : hits) {
      if (h.x == 272 && h.z == 944) CHECK(h.v == 0);
      if (h.x == 3408 && h.z == 21856) CHECK(h.v == 1);
      if (h.x == 608 && h.z == 720) CHECK(h.v == 2);
      if (h.x == 16 && h.z == 2976) CHECK(h.v == 3);
      if (h.x == 128 && h.z == 19968) CHECK(h.v == 4);
      if (h.x == 7216 && h.z == 4032) CHECK(h.v == 8);
    }
  }

  // 3. 堡垒 4 类型 (seed 0, 下界)
  setWorld(0, -1);
  {
    auto hits = queryHits(Bastion, 0, 0, 50, 50);
    bool seen[4] = {false, false, false, false};
    for (auto& h : hits) {
      bool okBits = (h.v & ~XSM_VAR_BASTION_TYPE_MASK) == 0;
      CHECK_MESSAGE(okBits, "bad bastion bits: ", h.v);
      seen[h.v] = true;
    }
    for (int t = 0; t < 4; t++)
      CHECK_MESSAGE(seen[t], "bastion type ", t, " not found");
  }

  // 4. 其余变种类型: 范围内应出现非零变种
  setWorld(0, 0);
  {
    static const struct { int id; const char* name; int wantBit; } types[] = {
        {Igloo, "Igloo", XSM_VAR_IGLOO_BASEMENT},
        {Shipwreck, "Shipwreck", XSM_VAR_SHIPWRECK_BEACHED},
        {Ruined_Portal, "Ruined_Portal", XSM_VAR_PORTAL_GIANT},
        {Trial_Chambers, "Trial_Chambers", XSM_VAR_TRIAL_CHAMBERS_MASK},
    };
    for (auto& t : types) {
      auto hits = queryHits(t.id, 0, 0, 50, 50);
      bool seen = false;
      for (auto& h : hits) {
        if (t.id == Ruined_Portal) {
          bool okBits = (h.v & ~(XSM_VAR_PORTAL_GIANT | XSM_VAR_PORTAL_UNDERGROUND |
                                  XSM_VAR_PORTAL_AIRPOCKET)) == 0;
          CHECK_MESSAGE(okBits, "bad portal bits: ", h.v);
          if (h.v & t.wantBit) seen = true;
        } else if (t.id == Trial_Chambers) {
          bool okBits = (h.v & ~XSM_VAR_TRIAL_CHAMBERS_MASK) == 0;
          CHECK_MESSAGE(okBits, "bad trial chamber bits: ", h.v);
          if (h.v != 0) seen = true;
        } else {
          if (h.v == t.wantBit) seen = true;
        }
      }
      CHECK_MESSAGE(seen, t.name, " nonzero variant not found");
    }
  }

  // 5. 稀疏查询 (Geode) 变种: 与 grid 全量一致
  {
    auto gridHits = queryHits(Geode, 0, 0, 64, 64);
    std::vector<int64_t> gv, sv;
    for (auto& h : gridHits)
      gv.push_back((int64_t)h.v);
    int64_t next = -1;
    int32_t xb[4096], zb[4096], vr[4096];
    do {
      int64_t s = next;
      uint32_t n = querySparseStructures(Geode, 0, 0, 64, 64, 0, 0, 0, 0, s,
                                         4096, xb, zb, vr, &next);
      for (uint32_t i = 0; i < n; i++)
        sv.push_back(vr[i]);
    } while (next >= 0);
    CHECK(sv.size() == gv.size());
    bool seenCracked = false;
    for (size_t i = 0; i < sv.size() && i < gv.size(); i++) {
      CHECK(sv[i] == gv[i]);
      if (sv[i]) seenCracked = true;
    }
    CHECK_MESSAGE(seenCracked, "no cracked geode found");
  }

  // 6. 确定性: 相同查询两次结果一致
  setWorld(0, 1);
  {
    auto a = queryHits(End_City, 0, 0, 40, 40);
    auto b = queryHits(End_City, 0, 0, 40, 40);
    CHECK(a.size() == b.size());
    for (size_t i = 0; i < a.size() && i < b.size(); i++) {
      CHECK(a[i].x == b[i].x);
      CHECK(a[i].z == b[i].z);
      CHECK(a[i].v == b[i].v);
    }
  }
}
