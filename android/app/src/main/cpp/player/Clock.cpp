// Clock neutralized: implementations intentionally left empty.
#include "Clock.h"

// No-op implementations to avoid software timing authority.

void Clock::setUs(int64_t) {}

void Clock::addUs(int64_t) {}

int64_t Clock::getUs() const { return 0; }