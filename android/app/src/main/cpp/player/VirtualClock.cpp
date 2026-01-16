#include "VirtualClock.h"
#include <time.h>

int64_t VirtualClock::nowUs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

void VirtualClock::start() {
    baseUs_.store(nowUs());
    paused_.store(false);
}

void VirtualClock::pause() {
    pausedUs_.store(positionUs());
    paused_.store(true);
}

void VirtualClock::resume() {
    baseUs_.store(nowUs() - pausedUs_.load());
    paused_.store(false);
}

void VirtualClock::seekUs(int64_t us) {
    baseUs_.store(nowUs() - us);
    pausedUs_.store(us);
}

void VirtualClock::reset() {
    baseUs_.store(0);
    pausedUs_.store(0);
    paused_.store(true);
}

int64_t VirtualClock::positionUs() const {
    if (paused_.load()) return pausedUs_.load();
    return nowUs() - baseUs_.load();
}

bool VirtualClock::isPaused() const {
    return paused_.load();
}
