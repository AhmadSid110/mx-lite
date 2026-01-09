#pragma once
#include <atomic>
#include <cstdint>

class Clock {
public:
    void setUs(int64_t us);
    int64_t getUs() const;

private:
    std::atomic<int64_t> ptsUs_{0};
};
