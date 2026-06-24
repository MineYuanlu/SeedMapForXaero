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
#ifdef __x86_64__
            __builtin_ia32_pause();
#endif
        }
    }

    void unlock() {
        flag.clear(std::memory_order_release);
    }
};

} // namespace xsm

#endif
