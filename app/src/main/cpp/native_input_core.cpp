#include "native_input_core.h"

NativeInputCore::NativeInputCore()
    : m_initialized(false)
    , m_uinput_fd(-1)
    , m_active(false)
    , m_touch_active(false)
    , m_screen_width(1080)
    , m_screen_height(2340)
    , m_max_slots(10)
    , m_current_slot(0) {

    m_analog_state = {0.0f, 0.0f, 0.0f, 0.0f, 0.08f, 1.0f, 2.0f, 1.0f};

    for (auto& slot : m_slots) {
        slot.tracking_id = -1;
        slot.x = 0.0f;
        slot.y = 0.0f;
        slot.pressure = 0.0f;
        slot.active = false;
    }
}

NativeInputCore::~NativeInputCore() {
    shutdown();
}

bool NativeInputCore::initialize() {
    if (m_initialized) return true;

    m_uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (m_uinput_fd < 0) {
        LOGE("Failed to open /dev/uinput: %s", strerror(errno));
        return false;
    }

    if (!createVirtualDevice("GP-Mapper Virtual Touch", m_max_slots)) {
        close(m_uinput_fd);
        m_uinput_fd = -1;
        return false;
    }

    m_initialized = true;
    m_active.store(true, std::memory_order_release);
    LOGI("NativeInputCore initialized successfully");
    return true;
}

void NativeInputCore::shutdown() {
    m_active.store(false, std::memory_order_release);
    destroyVirtualDevice();
    m_initialized = false;
    LOGI("NativeInputCore shut down");
}

bool NativeInputCore::createVirtualDevice(const char* name, int32_t max_slots) {
    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));

    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = 0x1234;
    usetup.id.product = 0x5678;
    strncpy(usetup.name, name, UINPUT_MAX_NAME_SIZE - 1);

    ioctl(m_uinput_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(m_uinput_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(m_uinput_fd, UI_SET_EVBIT, EV_SYN);

    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MINOR);
    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_WIDTH_MAJOR);
    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_WIDTH_MINOR);
    ioctl(m_uinput_fd, UI_SET_ABSBIT, ABS_MT_TOOL_TYPE);

    ioctl(m_uinput_fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(m_uinput_fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);

    struct uinput_abs_setup abs_setup;
    memset(&abs_setup, 0, sizeof(abs_setup));

    abs_setup.code = ABS_MT_SLOT;
    abs_setup.absinfo.maximum = max_slots - 1;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_TRACKING_ID;
    abs_setup.absinfo.maximum = 65535;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_POSITION_X;
    abs_setup.absinfo.maximum = m_screen_width;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_POSITION_Y;
    abs_setup.absinfo.maximum = m_screen_height;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_TOUCH_MAJOR;
    abs_setup.absinfo.maximum = 255;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_TOUCH_MINOR;
    abs_setup.absinfo.maximum = 255;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_PRESSURE;
    abs_setup.absinfo.maximum = 255;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_WIDTH_MAJOR;
    abs_setup.absinfo.maximum = 255;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_WIDTH_MINOR;
    abs_setup.absinfo.maximum = 255;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_TOOL_TYPE;
    abs_setup.absinfo.maximum = 1;
    ioctl(m_uinput_fd, UI_ABS_SETUP, &abs_setup);

    int ret = ioctl(m_uinput_fd, UI_DEV_CREATE);
    if (ret < 0) {
        LOGE("Failed to create uinput device: %s", strerror(errno));
        return false;
    }

    usleep(200000);
    LOGI("Virtual touch device created: %s", name);
    return true;
}

void NativeInputCore::destroyVirtualDevice() {
    if (m_uinput_fd >= 0) {
        ioctl(m_uinput_fd, UI_DEV_DESTROY);
        close(m_uinput_fd);
        m_uinput_fd = -1;
    }
}

void NativeInputCore::emitEvent(int32_t type, int32_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    gettimeofday(&ev.time, nullptr);
    write(m_uinput_fd, &ev, sizeof(ev));
}

void NativeInputCore::emitSyn() {
    emitEvent(EV_SYN, SYN_REPORT, 0);
}

void NativeInputCore::commitEvents() {
    emitSyn();
}

void NativeInputCore::setScreenDimensions(int32_t width, int32_t height) {
    m_screen_width = width;
    m_screen_height = height;
    LOGI("Screen dimensions set: %dx%d", width, height);
}

void NativeInputCore::setAnalogState(const AnalogState& state) {
    std::lock_guard<std::mutex> lock(m_event_mutex);
    m_analog_state = state;
}

float NativeInputCore::processDeadzone(float value, float inner, float outer) const {
    float magnitude = std::abs(value);
    if (magnitude < inner) return 0.0f;
    if (magnitude > outer) return (value > 0.0f) ? 1.0f : -1.0f;
    float normalized = (magnitude - inner) / (outer - inner);
    return std::copysign(normalized, value);
}

float NativeInputCore::applyResponseCurve(float value, float exponent) const {
    float magnitude = std::abs(value);
    float curved = std::pow(magnitude, exponent);
    return std::copysign(curved, value);
}

float NativeInputCore::processAnalogValue(float raw, const AnalogState& state) const {
    float deadzoned = processDeadzone(raw, state.deadzone_inner, state.deadzone_outer);
    float curved = applyResponseCurve(deadzoned, state.response_curve_exponent);
    return curved * state.sensitivity;
}

bool NativeInputCore::injectTouch(int32_t pointer_id, float x, float y, float pressure) {
    std::lock_guard<std::mutex> lock(m_event_mutex);

    if (!m_active.load(std::memory_order_acquire) || m_uinput_fd < 0) return false;

    int32_t slot = pointer_id % m_max_slots;
    if (slot < 0 || slot >= m_max_slots) return false;

    m_current_slot = slot;
    emitEvent(EV_ABS, ABS_MT_SLOT, slot);

    if (!m_slots[slot].active) {
        m_slots[slot].tracking_id = pointer_id;
        emitEvent(EV_ABS, ABS_MT_TRACKING_ID, pointer_id);
        emitEvent(EV_KEY, BTN_TOUCH, 1);
        emitEvent(EV_KEY, BTN_TOOL_FINGER, 1);
    }

    int32_t px = static_cast<int32_t>(x * m_screen_width);
    int32_t py = static_cast<int32_t>(y * m_screen_height);
    int32_t pval = static_cast<int32_t>(pressure * 255.0f);

    emitEvent(EV_ABS, ABS_MT_POSITION_X, px);
    emitEvent(EV_ABS, ABS_MT_POSITION_Y, py);
    emitEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 60);
    emitEvent(EV_ABS, ABS_MT_TOUCH_MINOR, 60);
    emitEvent(EV_ABS, ABS_MT_PRESSURE, pval);
    emitEvent(EV_ABS, ABS_MT_WIDTH_MAJOR, 40);
    emitEvent(EV_ABS, ABS_MT_WIDTH_MINOR, 40);
    emitEvent(EV_ABS, ABS_MT_TOOL_TYPE, 0);

    m_slots[slot].x = x;
    m_slots[slot].y = y;
    m_slots[slot].pressure = pressure;
    m_slots[slot].active = true;

    commitEvents();
    return true;
}

bool NativeInputCore::injectTouchUp(int32_t pointer_id) {
    std::lock_guard<std::mutex> lock(m_event_mutex);

    if (!m_active.load(std::memory_order_acquire) || m_uinput_fd < 0) return false;

    int32_t slot = pointer_id % m_max_slots;
    if (slot < 0 || slot >= m_max_slots) return false;

    emitEvent(EV_ABS, ABS_MT_SLOT, slot);
    emitEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);

    m_slots[slot].tracking_id = -1;
    m_slots[slot].active = false;

    bool any_active = false;
    for (const auto& s : m_slots) {
        if (s.active) { any_active = true; break; }
    }

    if (!any_active) {
        emitEvent(EV_KEY, BTN_TOUCH, 0);
        emitEvent(EV_KEY, BTN_TOOL_FINGER, 0);
    }

    commitEvents();
    return true;
}

bool NativeInputCore::injectMultiTouch(const TouchEvent* events, int32_t count) {
    std::lock_guard<std::mutex> lock(m_event_mutex);

    if (!m_active.load(std::memory_order_acquire) || m_uinput_fd < 0) return false;

    for (int32_t i = 0; i < count; ++i) {
        const auto& ev = events[i];
        int32_t slot = ev.id % m_max_slots;
        if (slot < 0 || slot >= m_max_slots) continue;

        m_current_slot = slot;
        emitEvent(EV_ABS, ABS_MT_SLOT, slot);

        if (ev.active && !m_slots[slot].active) {
            m_slots[slot].tracking_id = ev.id;
            emitEvent(EV_ABS, ABS_MT_TRACKING_ID, ev.id);
            emitEvent(EV_KEY, BTN_TOUCH, 1);
            emitEvent(EV_KEY, BTN_TOOL_FINGER, 1);
        } else if (!ev.active && m_slots[slot].active) {
            emitEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            m_slots[slot].tracking_id = -1;
            m_slots[slot].active = false;
            continue;
        } else if (!ev.active) {
            continue;
        }

        int32_t px = static_cast<int32_t>(ev.x * m_screen_width);
        int32_t py = static_cast<int32_t>(ev.y * m_screen_height);
        int32_t pval = static_cast<int32_t>(ev.pressure * 255.0f);

        emitEvent(EV_ABS, ABS_MT_POSITION_X, px);
        emitEvent(EV_ABS, ABS_MT_POSITION_Y, py);
        emitEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, 60);
        emitEvent(EV_ABS, ABS_MT_TOUCH_MINOR, 60);
        emitEvent(EV_ABS, ABS_MT_PRESSURE, pval);
        emitEvent(EV_ABS, ABS_MT_WIDTH_MAJOR, 40);
        emitEvent(EV_ABS, ABS_MT_WIDTH_MINOR, 40);
        emitEvent(EV_ABS, ABS_MT_TOOL_TYPE, 0);

        m_slots[slot].x = ev.x;
        m_slots[slot].y = ev.y;
        m_slots[slot].pressure = ev.pressure;
        m_slots[slot].active = true;
    }

    commitEvents();
    return true;
}

bool NativeInputCore::injectKey(int32_t key_code, bool down) {
    std::lock_guard<std::mutex> lock(m_event_mutex);

    if (!m_active.load(std::memory_order_acquire) || m_uinput_fd < 0) return false;

    emitEvent(EV_KEY, key_code, down ? 1 : 0);
    commitEvents();
    return true;
}

bool NativeInputCore::injectSwipe(const SwipeGesture& gesture) {
    auto start = std::chrono::steady_clock::now();
    auto end = start + std::chrono::milliseconds(gesture.duration_ms);

    int32_t pointer_id = gesture.pointer_id;
    float steps = static_cast<float>(gesture.duration_ms) / 2.0f;
    float dx = (gesture.end_x - gesture.start_x) / steps;
    float dy = (gesture.end_y - gesture.start_y) / steps;

    injectTouch(pointer_id, gesture.start_x, gesture.start_y, 0.8f);

    for (float t = 0; t <= steps; t += 1.0f) {
        float cx = gesture.start_x + dx * t;
        float cy = gesture.start_y + dy * t;
        cx = std::max(0.0f, std::min(1.0f, cx));
        cy = std::max(0.0f, std::min(1.0f, cy));
        injectTouch(pointer_id, cx, cy, 0.8f);
        std::this_thread::sleep_for(std::chrono::microseconds(2000));
    }

    injectTouch(pointer_id, gesture.end_x, gesture.end_y, 0.8f);
    std::this_thread::sleep_for(std::chrono::microseconds(500));
    injectTouchUp(pointer_id);

    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_gpmapper_app_input_NativeUinputBackend_nativeCreate(JNIEnv* env, jobject thiz) {
    auto* core = new NativeInputCore();
    return reinterpret_cast<jlong>(core);
}

JNIEXPORT jboolean JNICALL
Java_com_gpmapper_app_input_NativeUinputBackend_nativeInitialize(JNIEnv* env, jobject thiz, jlong handle) {
    auto* core = reinterpret_cast<NativeInputCore*>(handle);
    if (!core) return JNI_FALSE;
    return core->initialize() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_gpmapper_app_input_NativeUinputBackend_nativeShutdown(JNIEnv* env, jobject thiz, jlong handle) {
    auto* core = reinterpret_cast<NativeInputCore*>(handle);
    if (core) core->shutdown();
}

JNIEXPORT void JNICALL
Java_com_gpmapper_app_input_NativeUinputBackend_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    auto* core = reinterpret_cast<NativeInputCore*>(handle);
    delete core;
}

JNIEXPORT jboolean JNICALL
Java_com_gpmapper_app_input_NativeUinputBackend_nativeInjectTouch(
    JNIEnv* env, jobject thiz, jlong handle,
    jint pointer_id, jfloat x, jfloat y, jfloat pressure) {
    auto* core = reinterpret_cast<NativeInputCore*>(handle);
    if (!core) return JNI_FALSE;
    return core->injectTouch(pointer_id, x, y, pressure) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_gpmapper_app_input_NativeUinputBackend_nativeInjectTouchUp(
    JNIEnv* env, jobject thiz, jlong handle, jint pointer_id) {
    auto* core = reinterpret_cast<NativeInputCore*>(handle);
    if (!core) return JNI_FALSE;
    return core->injectTouchUp(pointer_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_gpmapper_app_input_NativeUinputBackend_nativeSetScreenDimensions(
    JNIEnv* env, jobject thiz, jlong handle, jint width, jint height) {
    auto* core = reinterpret_cast<NativeInputCore*>(handle);
    if (core) core->setScreenDimensions(width, height);
}

JNIEXPORT jboolean JNICALL
Java_com_gpmapper_app_input_NativeUinputBackend_nativeInjectMultiTouch(
    JNIEnv* env, jobject thiz, jlong handle, jobjectArray eventsArray) {
    auto* core = reinterpret_cast<NativeInputCore*>(handle);
    if (!core || !eventsArray) return JNI_FALSE;

    jsize len = env->GetArrayLength(eventsArray);
    if (len <= 0 || len > 16) return JNI_FALSE;

    std::vector<TouchEvent> events;
    events.reserve(len);

    for (jsize i = 0; i < len; ++i) {
        jobject eventObj = env->GetObjectArrayElement(eventsArray, i);
        if (!eventObj) continue;

        jclass cls = env->GetObjectClass(eventObj);
        TouchEvent te;
        te.id = env->GetIntField(eventObj, env->GetFieldID(cls, "id", "I"));
        te.x = env->GetFloatField(eventObj, env->GetFieldID(cls, "x", "F"));
        te.y = env->GetFloatField(eventObj, env->GetFieldID(cls, "y", "F"));
        te.pressure = env->GetFloatField(eventObj, env->GetFieldID(cls, "pressure", "F"));
        te.active = env->GetBooleanField(eventObj, env->GetFieldID(cls, "active", "Z"));
        te.timestamp_ns = 0;
        events.push_back(te);
        env->DeleteLocalRef(eventObj);
    }

    return core->injectMultiTouch(events.data(), static_cast<int32_t>(events.size())) ? JNI_TRUE : JNI_FALSE;
}

}
