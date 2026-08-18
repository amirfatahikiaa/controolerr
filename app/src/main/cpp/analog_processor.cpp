#include "analog_processor.h"

AnalogProcessor::AnalogProcessor()
    : m_deadzone_inner(0.08f)
    , m_deadzone_outer(1.0f)
    , m_response_exponent(2.0f)
    , m_sensitivity(1.0f)
    , m_smoothing_factor(0.7f)
    , m_filtered_x(0.0f)
    , m_filtered_y(0.0f) {
}

void AnalogProcessor::setDeadzone(float inner, float outer) {
    m_deadzone_inner = std::clamp(inner, DEADZONE_MIN, DEADZONE_MAX);
    m_deadzone_outer = std::clamp(outer, m_deadzone_inner + 0.01f, DEADZONE_MAX);
}

void AnalogProcessor::setResponseCurve(float exponent) {
    m_response_exponent = std::clamp(exponent, RESPONSE_MIN, RESPONSE_MAX);
}

void AnalogProcessor::setSensitivity(float sensitivity) {
    m_sensitivity = std::max(0.0f, sensitivity);
}

void AnalogProcessor::setSmoothing(float factor) {
    m_smoothing_factor = std::clamp(factor, SMOOTH_MIN, SMOOTH_MAX);
}

void AnalogProcessor::reset() {
    m_filtered_x = 0.0f;
    m_filtered_y = 0.0f;
}

float AnalogProcessor::applyDeadzone(float value) const {
    float magnitude = std::abs(value);
    if (magnitude < m_deadzone_inner) return 0.0f;
    if (magnitude > m_deadzone_outer) return std::copysign(1.0f, value);

    float normalized = (magnitude - m_deadzone_inner) / (m_deadzone_outer - m_deadzone_inner);
    return std::copysign(normalized, value);
}

float AnalogProcessor::applyResponseCurve(float value) const {
    float magnitude = std::abs(value);
    float curved = std::pow(magnitude, m_response_exponent);
    return std::copysign(curved, value);
}

float AnalogProcessor::smooth(float current, float previous, float factor) const {
    return previous * factor + current * (1.0f - factor);
}

StickState AnalogProcessor::processRaw(float raw_x, float raw_y) {
    float dx = applyDeadzone(raw_x);
    float dy = applyDeadzone(raw_y);

    dx = applyResponseCurve(dx) * m_sensitivity;
    dy = applyResponseCurve(dy) * m_sensitivity;

    dx = std::clamp(dx, -1.0f, 1.0f);
    dy = std::clamp(dy, -1.0f, 1.0f);

    m_filtered_x = smooth(dx, m_filtered_x, m_smoothing_factor);
    m_filtered_y = smooth(dy, m_filtered_y, m_smoothing_factor);

    return {m_filtered_x, m_filtered_y};
}

StickState AnalogProcessor::processLeftStick(float raw_x, float raw_y) {
    return processRaw(raw_x, raw_y);
}

StickState AnalogProcessor::processRightStick(float raw_x, float raw_y) {
    return processRaw(raw_x, raw_y);
}
