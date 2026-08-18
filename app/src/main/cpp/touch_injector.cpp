#include "touch_injector.h"

TouchInjector::TouchInjector(NativeInputCore* core)
    : m_core(core)
    , m_running(false) {
}

TouchInjector::~TouchInjector() {
    stop();
}

void TouchInjector::start() {
    m_running.store(true, std::memory_order_release);
}

void TouchInjector::stop() {
    m_running.store(false, std::memory_order_release);
}

std::vector<InterpolatedPoint> TouchInjector::lerpPath(
    float sx, float sy, float ex, float ey,
    int32_t steps) const {

    std::vector<InterpolatedPoint> path;
    path.reserve(steps + 1);

    for (int32_t i = 0; i <= steps; ++i) {
        float t = static_cast<float>(i) / static_cast<float>(steps);
        InterpolatedPoint p;
        p.x = sx + (ex - sx) * t;
        p.y = sy + (ey - sy) * t;
        p.timestamp_ms = t * 1000.0f;
        path.push_back(p);
    }
    return path;
}

std::vector<InterpolatedPoint> TouchInjector::cubicBezierPath(
    float sx, float sy,
    float cx1, float cy1,
    float cx2, float cy2,
    float ex, float ey,
    int32_t steps) const {

    std::vector<InterpolatedPoint> path;
    path.reserve(steps + 1);

    for (int32_t i = 0; i <= steps; ++i) {
        float t = static_cast<float>(i) / static_cast<float>(steps);
        float u = 1.0f - t;
        float tt = t * t;
        float uu = u * u;
        float uuu = uu * u;
        float ttt = tt * t;

        InterpolatedPoint p;
        p.x = uuu * sx + 3.0f * uu * t * cx1 + 3.0f * u * tt * cx2 + ttt * ex;
        p.y = uuu * sy + 3.0f * uu * t * cy1 + 3.0f * u * tt * cy2 + ttt * ey;
        p.timestamp_ms = t * 1000.0f;
        path.push_back(p);
    }
    return path;
}

bool TouchInjector::injectSmoothSwipe(
    int32_t pointer_id,
    float start_x, float start_y,
    float end_x, float end_y,
    int32_t duration_ms,
    int32_t interpolation_steps) {

    if (!m_core || !m_running.load(std::memory_order_acquire)) return false;

    auto path = lerpPath(start_x, start_y, end_x, end_y, interpolation_steps);

    float delay_ms = static_cast<float>(duration_ms) / static_cast<float>(interpolation_steps);

    for (const auto& point : path) {
        if (!m_running.load(std::memory_order_acquire)) return false;
        m_core->injectTouch(pointer_id, point.x, point.y, 0.8f);
        std::this_thread::sleep_for(
            std::chrono::microseconds(static_cast<int64_t>(delay_ms * 1000.0f)));
    }

    m_core->injectTouchUp(pointer_id);
    return true;
}

bool TouchInjector::injectTap(float x, float y, int32_t duration_ms) {
    if (!m_core || !m_running.load(std::memory_order_acquire)) return false;

    m_core->injectTouch(99, x, y, 0.9f);
    std::this_thread::sleep_for(std::chrono::milliseconds(duration_ms));
    m_core->injectTouchUp(99);
    return true;
}

bool TouchInjector::injectMultiTap(
    const std::vector<std::pair<float,float>>& points,
    int32_t interval_ms) {

    if (!m_core || !m_running.load(std::memory_order_acquire)) return false;

    for (size_t i = 0; i < points.size(); ++i) {
        if (!m_running.load(std::memory_order_acquire)) return false;

        int32_t pid = static_cast<int32_t>(90 + i);
        m_core->injectTouch(pid, points[i].first, points[i].second, 0.8f);
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
        m_core->injectTouchUp(pid);
        std::this_thread::sleep_for(std::chrono::milliseconds(interval_ms));
    }
    return true;
}

bool TouchInjector::injectContinuousHold(
    int32_t pointer_id,
    float x, float y,
    int32_t duration_ms) {

    if (!m_core || !m_running.load(std::memory_order_acquire)) return false;

    m_core->injectTouch(pointer_id, x, y, 0.85f);
    std::this_thread::sleep_for(std::chrono::milliseconds(duration_ms));
    m_core->injectTouchUp(pointer_id);
    return true;
}

bool TouchInjector::injectRadialSwipe(
    int32_t pointer_id,
    float center_x, float center_y,
    float radius,
    float start_angle_deg,
    float end_angle_deg,
    int32_t duration_ms) {

    if (!m_core || !m_running.load(std::memory_order_acquire)) return false;

    int32_t steps = duration_ms / 2;
    float angle_range = end_angle_deg - start_angle_deg;

    m_core->injectTouch(pointer_id, center_x, center_y, 0.7f);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));

    for (int32_t i = 0; i <= steps; ++i) {
        if (!m_running.load(std::memory_order_acquire)) return false;

        float t = static_cast<float>(i) / static_cast<float>(steps);
        float angle = (start_angle_deg + angle_range * t) * 3.14159265f / 180.0f;
        float px = center_x + radius * std::cos(angle);
        float py = center_y + radius * std::sin(angle);

        m_core->injectTouch(pointer_id, px, py, 0.8f);
        std::this_thread::sleep_for(std::chrono::microseconds(2000));
    }

    m_core->injectTouchUp(pointer_id);
    return true;
}
