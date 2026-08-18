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

    static constexpr int32_t ABS_MT_SLOT = 0x2f;
    static constexpr int32_t ABS_MT_TOUCH_MAJOR = 0x30;
    static constexpr int32_t ABS_MT_TOUCH_MINOR = 0x31;
    static constexpr int32_t ABS_MT_WIDTH_MAJOR = 0x32;
    static constexpr int32_t ABS_MT_WIDTH_MINOR = 0x33;
    static constexpr int32_t ABS_MT_ORIENTATION = 0x34;
    static constexpr int32_t ABS_MT_POSITION_X = 0x35;
    static constexpr int32_t ABS_MT_POSITION_Y = 0x36;
    static constexpr int32_t ABS_MT_TOOL_TYPE = 0x37;
    static constexpr int32_t ABS_MT_BLOB_ID = 0x38;
    static constexpr int32_t ABS_MT_TRACKING_ID = 0x39;
    static constexpr int32_t ABS_MT_PRESSURE = 0x3a;
    static constexpr int32_t ABS_MT_DISTANCE = 0x3b;
    static constexpr int32_t ABS_MT_TOOL_X = 0x3c;
    static constexpr int32_t ABS_MT_TOOL_Y = 0x3d;
    static constexpr int32_t BTN_TOUCH = 0x14a;
    static constexpr int32_t BTN_TOOL_PEN = 0x140;
    static constexpr int32_t BTN_TOOL_FINGER = 0x145;
};
