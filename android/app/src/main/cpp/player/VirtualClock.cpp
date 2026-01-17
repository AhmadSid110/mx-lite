#include "VirtualClock.h"
#include <android/log.h>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <time.h>


#define LOG_TAG "VirtualClock"

int64_t VirtualClock::nowUs() {
  timespec ts{};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

void VirtualClock::log(const char *fmt, ...) {
  char buf[256];
  va_list args;
  va_start(args, fmt);
  vsnprintf(buf, sizeof(buf), fmt, args);
  va_end(args);

  // Logcat
  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", buf);

  // Store for UI
  std::lock_guard<std::mutex> lock(logMutex_);
  strncpy(lastLog_, buf, sizeof(lastLog_) - 1);
  lastLog_[sizeof(lastLog_) - 1] = '\0';
}

void VirtualClock::getLastLog(char *buffer, size_t size) const {
  if (!buffer || size == 0)
    return;
  std::lock_guard<std::mutex> lock(logMutex_);
  strncpy(buffer, lastLog_, size - 1);
  buffer[size - 1] = '\0';
}

void VirtualClock::start() {
  log("Clock start");
  if (running_.exchange(true))
    return;
  baseUs_.store(nowUs());
}

void VirtualClock::pause() {
  log("Clock pause");
  if (!running_.exchange(false))
    return;

  // Accumulate elapsed time into offset
  int64_t now = nowUs();
  int64_t base = baseUs_.load();
  offsetUs_.fetch_add(now - base);
}

void VirtualClock::resume() {
  log("Clock resume");
  if (running_.exchange(true))
    return;
  baseUs_.store(nowUs());
}

void VirtualClock::seekUs(int64_t us) {
  log("Clock seek to %lld", (long long)us);
  offsetUs_.store(us);
  baseUs_.store(nowUs());
}

void VirtualClock::reset() {
  log("Clock reset");
  running_.store(false);
  baseUs_.store(0);
  offsetUs_.store(0);
}

int64_t VirtualClock::positionUs() const {
  if (!running_.load(std::memory_order_acquire)) {
    return offsetUs_.load();
  }
  return offsetUs_.load() + (nowUs() - baseUs_.load());
}

bool VirtualClock::isPaused() const { return !running_.load(); }

bool VirtualClock::isRunning() const {
  return running_.load(std::memory_order_acquire);
}
