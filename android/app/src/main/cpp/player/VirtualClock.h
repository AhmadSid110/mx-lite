#pragma once
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>

class VirtualClock {
public:
  void start();
  void pause();
  void resume();
  void seekUs(int64_t us);
  void reset();

  int64_t positionUs() const;
  bool isPaused() const;
  bool isRunning() const;

  // Debugging
  void getLastLog(char *buffer, size_t size) const;

private:
  void log(const char *fmt, ...);

  std::atomic<bool> running_{false};
  std::atomic<int64_t> baseUs_{0};
  std::atomic<int64_t> offsetUs_{0};

  mutable std::mutex logMutex_;
  char lastLog_[256] = "Ready";

  static int64_t nowUs();
};
