#pragma once

#include <atomic>
#include <cstdint>

class Clock {
public:
    // Absolute set (used on seek / start)
    void setUs(int64_t us);

    // Incremental add (used during playback)
    void addUs(int64_t deltaUs);

    // Read current clock
    int64_t getUs() const;

private:
    std::atomic<int64_t> ptsUs_{0};
};