#pragma once
#include <atomic>
#include <cstdint>

class VirtualClock {
public:
    void start();
    void pause();
    void resume();
    void seekUs(int64_t us);

    int64_t positionUs() const;
    bool isPaused() const;

private:
    std::atomic<bool> paused_{true};
    std::atomic<int64_t> baseUs_{0};
    std::atomic<int64_t> pausedUs_{0};

    static int64_t nowUs();
};
