#pragma once

#include "native_input_core.h"
#include <thread>
#include <atomic>
#include <vector>
#include <functional>

struct InterpolatedPoint {
    float x;
    float y;
    float timestamp_ms;
};

class TouchInjector {
public:
    TouchInjector(NativeInputCore* core);
    ~TouchInjector();

    void start();
    void stop();

    bool injectSmoothSwipe(
        int32_t pointer_id,
        float start_x, float start_y,
        float end_x, float end_y,
        int32_t duration_ms,
        int32_t interpolation_steps = 20);

    bool injectTap(float x, float y, int32_t duration_ms = 50);
    bool injectMultiTap(const std::vector<std::pair<float,float>>& points, int32_t interval_ms = 30);

    bool injectContinuousHold(
        int32_t pointer_id,
        float x, float y,
        int32_t duration_ms);

    bool injectRadialSwipe(
        int32_t pointer_id,
        float center_x, float center_y,
        float radius,
        float start_angle_deg,
        float end_angle_deg,
        int32_t duration_ms);

private:
    std::vector<InterpolatedPoint> lerpPath(
        float sx, float sy, float ex, float ey,
        int32_t steps) const;

    std::vector<InterpolatedPoint> cubicBezierPath(
        float sx, float sy,
        float cx1, float cy1,
        float cx2, float cy2,
        float ex, float ey,
        int32_t steps) const;

    NativeInputCore* m_core;
    std::atomic<bool> m_running;
};
