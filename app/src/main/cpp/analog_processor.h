#pragma once

#include <cmath>
#include <algorithm>
#include <array>

struct StickState {
    float x;
    float y;
};

class AnalogProcessor {
public:
    AnalogProcessor();

    void setDeadzone(float inner, float outer);
    void setResponseCurve(float exponent);
    void setSensitivity(float sensitivity);
    void setSmoothing(float factor);

    StickState processRaw(float raw_x, float raw_y);
    StickState processLeftStick(float raw_x, float raw_y);
    StickState processRightStick(float raw_x, float raw_y);

    float getFilteredX() const { return m_filtered_x; }
    float getFilteredY() const { return m_filtered_y; }

    void reset();

private:
    float applyDeadzone(float value) const;
    float applyResponseCurve(float value) const;
    float smooth(float current, float previous, float factor) const;

    float m_deadzone_inner;
    float m_deadzone_outer;
    float m_response_exponent;
    float m_sensitivity;
    float m_smoothing_factor;

    float m_filtered_x;
    float m_filtered_y;

    static constexpr float DEADZONE_MIN = 0.01f;
    static constexpr float DEADZONE_MAX = 0.99f;
    static constexpr float SMOOTH_MIN = 0.0f;
    static constexpr float SMOOTH_MAX = 1.0f;
    static constexpr float RESPONSE_MIN = 0.5f;
    static constexpr float RESPONSE_MAX = 5.0f;
};
