#ifndef XAERO_SEED_MAP_UTILS_MUTEX_H
#define XAERO_SEED_MAP_UTILS_MUTEX_H

#include <atomic>

namespace xsm {

class mutex {
    std::atomic_flag flag = ATOMIC_FLAG_INIT;
public:
    mutex() = default;
    mutex(const mutex&) = delete;
    mutex& operator=(const mutex&) = delete;

    void lock() {
        while (flag.test_and_set(std::memory_order_acquire)) {
#if defined(__x86_64__)
            __builtin_ia32_pause();
#elif defined(__aarch64__)
            // YIELD 提示指令: Android/ARM 关键平台, 避免纯忙等。
            // 用内联汇编而非 __builtin_arm_yield —— GCC aarch64 后端无此 builtin（仅 32-bit ARM 有）,
            // clang(bionic/NDK/apple) 与 gcc 在此均接受该指令。
            __asm__ __volatile__("yield");
#endif
        }
    }

    void unlock() {
        flag.clear(std::memory_order_release);
    }
};

} // namespace xsm

#endif
