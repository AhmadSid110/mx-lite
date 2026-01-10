#pragma once
#include <atomic>

struct AudioDebug {
    std::atomic<bool> engineCreated{false};
    std::atomic<bool> aaudioOpened{false};
    std::atomic<bool> aaudioStarted{false};
    std::atomic<bool> callbackCalled{false};
    std::atomic<bool> decoderProduced{false};
    std::atomic<int> bufferFill{0};
};