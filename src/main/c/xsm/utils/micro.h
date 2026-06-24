#ifndef XAERO_SEED_MAP_UTILS_MICRO_H
#define XAERO_SEED_MAP_UTILS_MICRO_H

// #include <stdint.h>
#include <stdbool.h>
// #include "./types.h"

#if defined(_WIN32) || defined(_WIN64)
#define XSM_API __declspec(dllexport)
#else
#define XSM_API
#endif

#if DEBUG_TIMINGS
#define XSM_TIME_POINT(name) \
  auto name = std::chrono::steady_clock::now()
#define XSM_TIME_ADD(target, from, to) \
  target.fetch_add(std::chrono::duration_cast<std::chrono::milliseconds>(to - from).count(), std::memory_order_relaxed);
#else
#define XSM_TIME_POINT(name) \
  do {                       \
  } while (0)
#define XSM_TIME_ADD(target, from, to) \
  do {                                 \
  } while (0)
#endif

#endif