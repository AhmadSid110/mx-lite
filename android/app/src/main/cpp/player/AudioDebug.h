#pragma once
#include <atomic>
#include <cstdint>

struct AudioDebug {
  std::atomic<bool> nativePlayCalled{false}; // ✅ ADD THIS

  // Lifecycle
  std::atomic<bool> engineCreated{false};

  // AAudio state
  std::atomic<bool> aaudioOpened{false};
  std::atomic<bool> aaudioStarted{false};
  std::atomic<bool> audioStarted{false};
  std::atomic<int> aaudioError{0};
  std::atomic<int> openStage{0};

  // Callback health
  std::atomic<bool> callbackCalled{false};
  std::atomic<int64_t> callbackCount{0};

  // Decoder
  std::atomic<bool> decoderProduced{false};
  std::atomic<bool> decodeActive{false};

  // Ring buffer state (frames)
  std::atomic<int64_t> bufferFill{0};
};