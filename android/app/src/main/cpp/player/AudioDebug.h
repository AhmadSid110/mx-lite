#pragma once
#include <atomic>
#include <cstdint>

struct AudioDebug {
    // Lifecycle
    std::atomic<bool> engineCreated{false};

    // AAudio state
    std::atomic<bool> aaudioOpened{false};
    std::atomic<bool> aaudioStarted{false};

    // Callback health
    std::atomic<bool> callbackCalled{false};
    std::atomic<int64_t> callbackCount{0};

    // Decoder
    std::atomic<bool> decoderProduced{false};

    // Ring buffer state (frames)
    std::atomic<int64_t> bufferFill{0};
};