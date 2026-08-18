#pragma once

#include <jni.h>
#include <android/log.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <cstring>
#include <cmath>
#include <atomic>
#include <thread>
#include <mutex>
#include <queue>
#include <array>
#include <chrono>
#include <cstdint>
#include <functional>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

#define LOG_TAG "GPMapperNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

struct TouchEvent {
    int32_t id;
    float x;
    float y;
    float pressure;
    bool active;
    uint64_t timestamp_ns;
};

struct SwipeGesture {
    float start_x;
    float start_y;
    float end_x;
    float end_y;
    int32_t duration_ms;
    int32_t pointer_id;
};

struct AnalogState {
    float lx;
    float ly;
    float rx;
    float ry;
    float deadzone_inner;
    float deadzone_outer;
    float response_curve_exponent;
    float sensitivity;
};

class NativeInputCore {
public:
    NativeInputCore();
    ~NativeInputCore();

    bool initialize();
    void shutdown();

    bool createVirtualDevice(const char* name, int32_t max_slots);
    void destroyVirtualDevice();

    bool injectTouch(int32_t pointer_id, float x, float y, float pressure);
    bool injectTouchUp(int32_t pointer_id);
    bool injectMultiTouch(const TouchEvent* events, int32_t count);

    bool injectKey(int32_t key_code, bool down);
    bool injectSwipe(const SwipeGesture& gesture);

    void setScreenDimensions(int32_t width, int32_t height);
    void setAnalogState(const AnalogState& state);

    float processAnalogValue(float raw, const AnalogState& state) const;
    float processDeadzone(float value, float inner, float outer) const;
    float applyResponseCurve(float value, float exponent) const;

    bool isActive() const { return m_active.load(std::memory_order_relaxed); }
    int32_t getFd() const { return m_uinput_fd; }

private:
    void emitEvent(int32_t type, int32_t code, int32_t value);
    void emitSyn();
    void commitEvents();

    bool m_initialized;
    int32_t m_uinput_fd;
    std::atomic<bool> m_active;
    std::atomic<bool> m_touch_active;

    int32_t m_screen_width;
    int32_t m_screen_height;
    int32_t m_max_slots;

    AnalogState m_analog_state;

    struct TouchSlot {
        int32_t tracking_id;
        float x;
        float y;
        float pressure;
        bool active;
    };
    std::array<TouchSlot, 16> m_slots;
    int32_t m_current_slot;

    std::mutex m_event_mutex;
};
