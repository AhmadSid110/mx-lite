#pragma once
#include <atomic>
#include <cstdint>

/**
 * Master clock driven ONLY by audio render progress.
 * Time unit: microseconds (µs)
 */
class Clock {
public:
    void setUs(int64_t us);
    void addUs(int64_t deltaUs);
    int64_t getUs() const;

private:
    std::atomic<int64_t> ptsUs_{0};
};