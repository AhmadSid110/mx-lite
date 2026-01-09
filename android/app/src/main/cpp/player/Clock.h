#pragma once
#include <atomic>
#include <cstdint>

class Clock {
public:
    // Reset / seek
    void setUs(int64_t us);

    // Advance clock by rendered audio duration
    void addUs(int64_t deltaUs);

    // Read current master time
    int64_t getUs() const;

private:
    std::atomic<int64_t> ptsUs_{0};
};