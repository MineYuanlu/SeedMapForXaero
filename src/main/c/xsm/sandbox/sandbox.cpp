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

int main() {
  int r;
  std::printf("Running tests...\n");
  if ((r = test1()) != 0) return r;
  std::printf(" test1 passed\n");  // test1 passed
  if ((r = test2()) != 0) return r;
  std::printf(" test2 passed\n");  // test2 passed
  return 0;
}