#include "NativeSwDecoder.h"

NativeSwDecoder::NativeSwDecoder() {}

NativeSwDecoder::~NativeSwDecoder() {}

void NativeSwDecoder::prepare(int /*fd*/) {
  running = true;
  paused = true;
}

void NativeSwDecoder::play() { paused = false; }

void NativeSwDecoder::pause() { paused = true; }

void NativeSwDecoder::seek(long /*positionMs*/) {
  // no-op for now
}

void NativeSwDecoder::stop() {
  running = false;
  paused = true;
}

void NativeSwDecoder::release() {
  running = false;
  paused = true;
}
