#pragma once

#include <cstdint>

class Clock {
public:
    void setUs(int64_t);
    void addUs(int64_t);
    int64_t getUs() const;
};