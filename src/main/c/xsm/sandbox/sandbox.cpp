#include <chrono>
#include <cstdio>
#include <random>

#include "../../cubiomes/util.h"
#include "../apis/render.h"

int test1() {
  if (!setBiomeColorTableNative()) return -1;
  if (!setGameVersion("26.1")) return -2;
  if (!setWorld(0, 0)) return -3;
  uint8_t data[64 * 64 * 3];
  auto code = genCellImg(4, 0, 0, 64, data, true);
  if (code != 0) return -4;
  if (savePPM("test1.ppm", data, 64, 64)) return -5;
  return 0;
}

int test2() {
  if (!setBiomeColorTableNative()) return -1;
  if (!setGameVersion("26.1")) return -2;
  if (!setWorld(12345, 0)) return -3;

  uint8_t data[64 * 64 * 3];

  // warmup: one gen like test1
  auto code = genCellImg(1, 0, 0, 64, data, true);
  if (code != 0) return -4;

#if DEBUG_TIMINGS
  resetGenTimings();
#endif
  std::mt19937 rng(67890);
  std::uniform_int_distribution<int> dist(-1000000, 1000000);

  constexpr int N = 1000;
  auto start = std::chrono::steady_clock::now();

  for (int i = 0; i < N; i++) {
    int wx = dist(rng);
    int wz = dist(rng);
    genCellImg(4, wx, wz, 64, data, true);
  }

  auto end = std::chrono::steady_clock::now();
  auto wallms =
      std::chrono::duration_cast<std::chrono::milliseconds>(end - start)
          .count();


#if DEBUG_TIMINGS
  uint64_t timings[4];
  getGenTimings(timings);

  const char* labels[] = {"check", "alloc", "genBiomes", "toImage"};
  uint64_t sum = 0;
  for (int i = 0; i < 4; i++) {
    sum += timings[i];
    std::printf("  %-10s %8ld ms  %6.1f ms/op  %5.1f%%\n", labels[i],
                timings[i], (double)timings[i] / N,
                100.0 * timings[i] / wallms);
  }
  std::printf("  %-10s %8ld ms  %6.1f ms/op  (sum of 4 segments)\n", "sum", sum,
              (double)sum / N);
  std::printf("  %-10s %8ld ms  %6.1f ms/op  (wall clock)\n", "wall", wallms,
              (double)wallms / N);
#else
  std::printf("  %-10s %8ld ms  %6.1f ms/op\n", "wall", wallms,
              (double)wallms / N);  // wall clock only
#endif
  return 0;
}

int test3() {
  if (!setBiomeColorTableNative()) return -1;
  if (!setGameVersion("26.1")) return -2;
  if (!setWorld(0, 0)) return -3;

  int32_t x[128], z[128];

  // warmup: one full query
  if (queryStrongholdsRange(0, 128, x, z) != 128) return -4;

  constexpr int N = 20;
  auto start = std::chrono::steady_clock::now();
  for (int i = 0; i < N; i++)
    queryStrongholdsRange(0, 128, x, z);
  auto end = std::chrono::steady_clock::now();
  double full_ms =
      std::chrono::duration<double, std::milli>(end - start).count() / N;
  std::printf("  full(128): %8.1f ms/call  (128 biome searches)\n", full_ms);

  static const int ringEnds[] = {3, 9, 19, 34, 55, 83, 119, 128};
  std::printf("  per-ring (incremental display cost):\n");
  double total = 0;
  for (int r = 0; r < 8; r++) {
    int from = r == 0 ? 0 : ringEnds[r - 1];
    int to = ringEnds[r];
    auto s2 = std::chrono::steady_clock::now();
    uint32_t n = queryStrongholdsRange(from, to, x, z);
    auto e2 = std::chrono::steady_clock::now();
    double ms = std::chrono::duration<double, std::milli>(e2 - s2).count();
    total += ms;
    std::printf("    ring %d [%3d,%3d): %8.1f ms  (%u strongholds)\n", r, from,
                to, ms, n);
  }
  std::printf("  per-ring sum: %8.1f ms\n", total);
  return 0;
}

int main() {
  int r;
  std::printf("Running tests...\n");
  if ((r = test1()) != 0) return r;
  std::printf(" test1 passed\n");  // test1 passed
  if ((r = test2()) != 0) return r;
  std::printf(" test2 passed\n");  // test2 passed
  if ((r = test3()) != 0) return r;
  std::printf(" test3 passed\n");  // test3 passed
  return 0;
}