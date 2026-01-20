#pragma once
#include <atomic>
#include <jni.h>

class NativeSwDecoder {
public:
    NativeSwDecoder();
    ~NativeSwDecoder();

    void prepare(int fd);
    void play();
    void pause();
    void seek(long positionMs);
    void stop();
    void release();

private:
    std::atomic<bool> running{false};
    std::atomic<bool> paused{true};
};
