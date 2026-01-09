#include "Clock.h"

void Clock::setUs(int64_t us) {
    ptsUs_.store(us, std::memory_order_relaxed);
}

int64_t Clock::getUs() const {
    return ptsUs_.load(std::memory_order_relaxed);
}