# GP-Mapper V1 Implementation Audit

**Date:** 2026-08-18
**Auditor:** Automated Code Review
**Scope:** Full 37-file scaffold, runtime viability, architectural correctness

---

## Executive Summary

The V1 scaffold contains **3 critical architectural failures** that prevent the application from functioning on any unrooted Android device. The `/dev/uinput` touch injection path is fundamentally impossible under the claimed rootless architecture. The Shizuku integration is a dead import with no functional IPC. The Kotlin TouchInjector attempts to invoke non-static JNI methods via reflection, which will crash at runtime. **No file in this project can successfully inject a touch event on a real device.**

---

## 1. /dev/uinput Reality Check

### Finding: BLOCKED on unrooted Android

**File:** `app/src/main/cpp/native_input_core.cpp:31`

```cpp
m_uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
```

**Verdict: NOT POSSIBLE under rootless architecture.**

On production Android devices:
- `/dev/uinput` is owned by `system:system` or `input:input` with permissions `0660` or stricter
- SELinux policy (`su.te`, `untrusted_app.te`) blocks untrusted_app domain from accessing `/dev/uinput`
- Even with `CAP_NET_RAW` or other capabilities, the SELinux `neverallow` rules prevent this
- The only way to access `/dev/uinput` is: (a) root shell, (b) a system daemon, or (c) a custom ROM with permissive SELinux

**Shizuku does NOT solve this.** Shizuku runs a helper process with `shell` UID (via `appops`), but:
1. The native JNI code executes in the **app's process** (UID `u0_aXXX`), not Shizuku's process
2. Shizuku provides binder IPC to run shell commands, not to share file descriptors or process memory
3. Even if Shizuku ran a shell command to open `/dev/uinput`, the FD would be in Shizuku's process, not the app's

**What would actually work for uinput:** A separate daemon running as `shell` or `root` that opens `/dev/uinput` and accepts injection commands via a local socket. This is what projects like `magisk-touchscreen-remap` do. But this requires root or a custom system service.

**Impact:** The entire C++ native injection core (`native_input_core.cpp`, `native_input_core.h`, `touch_injector.cpp`, `touch_injector.h`) is non-functional on unrooted devices.

### CMakeLists.txt Issue

**File:** `app/src/main/cpp/CMakeLists.txt:27`

```cmake
target_link_libraries(gpmapper_native android log uinput)
```

There is no `libuinput` in the Android NDK. The `uinput` ioctl constants come from `<linux/uinput.h>` which is a kernel header, not a library. Linking against `uinput` will cause a linker error. The correct approach is to include the kernel header and not link any library for uinput.

---

## 2. Shizuku Architecture Audit

### Finding: DEAD INTEGRATION

**Files:** `GPMapperApp.kt`, `MappingService.kt`, `build.gradle.kts`

**What exists:**
- `dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5` are declared as dependencies
- `ShizukuProvider` is declared in `AndroidManifest.xml`
- `GPMapperApp.kt` registers binder received/dead listeners

**What is missing (critical):**
1. **No `Shizuku.ping()` call** to check if Shizuku is running
2. **No `Shizuku.requestPermission()` call** to request authorization
3. **No `Shizuku.newProcess()` or `ShizukuRemoteProcess` usage** to execute privileged operations
4. **No AIDL service definition** for binder communication
5. **No check of `Shizuku.checkSelfPermission()`** before attempting operations
6. The `binderReceivedListener` only logs; it does not establish any IPC channel
7. The `permissionResultListener` only logs; it does not gate any functionality

**The native JNI code in MappingService runs in the app's own process.** It is NOT executed through Shizuku's privileged process. The `System.loadLibrary("gpmapper_native")` call at `MappingService.kt:71` loads the native library into the app process. All JNI calls thereafter execute with the app's UID, not Shizuku's shell UID.

**Shizuku's actual capability:** Shizuku can execute shell commands via `Shizuku.newProcess(["cmd", "input", "tap", "x", "y"], null, null)`. This is the only rootless injection mechanism Shizuku provides. It uses Android's `input` command which has INPUT_METHOD permission. But the original spec explicitly forbids `input tap`/`input swipe` commands due to latency.

**Possible Shizuku usage (not implemented):**
- Use `ShizukuRemoteProcess` to run a persistent native daemon that opens `/dev/uinput` with shell UID
- The daemon would accept injection commands via Unix domain socket from the app
- This would give the app indirect access to `/dev/uinput` through Shizuku's elevated process

**Impact:** Shizuku is imported but provides zero functional value in the current architecture.

---

## 3. JNI Architecture - Privilege Boundary

### Finding: FUNDAMENTALLY BROKEN

**File:** `app/src/main/java/com/gpmapper/app/input/TouchInjector.kt:129-136`

```kotlin
private fun nativeTouchDown(pointerId: Int, x: Float, y: Float, pressure: Float) {
    try {
        val clazz = Class.forName("com.gpmapper.app.service.MappingService")
        val method = clazz.getDeclaredMethod("nativeInjectTouch", ...)
        method.invoke(null, nativeHandle, pointerId, x, y, pressure)
    } catch (e: Exception) {
        Log.e(TAG, "Native touch down failed", e)
    }
}
```

**Multiple critical bugs:**

1. **`method.invoke(null, ...)` on non-static methods.** The `nativeInjectTouch` JNI method is bound to `MappingService` instance methods (via `JNICALL Java_com_gpmapper_app_service_MappingService_nativeInjectTouch`). The `external` declarations in `MappingService.kt` are instance methods, not companion object methods. `invoke(null, ...)` will throw `NullPointerException` because it tries to call an instance method without an instance.

2. **Reflection overhead on the hot path.** Every touch injection goes through `Class.forName()` + `getDeclaredMethod()` + `Method.invoke()`. This adds microseconds of reflection overhead per call, compounding across multi-touch gesture injection.

3. **The correct approach** is to call the native methods directly through the `MappingService` instance or through a static reference held by `TouchInjector`.

**File:** `app/src/main/java/com/gpmapper/app/service/MappingService.kt:49-68`

The `external fun` declarations are in the `companion object`, which means they are static JNI methods. However, the JNI naming convention `Java_com_gpmapper_app_service_MappingService_nativeInjectTouch` maps to the companion object methods. The `thiz` parameter in the JNI functions refers to the companion object, not an instance. The `nativeHandle` is passed as a parameter, so this part is technically correct for the JNI binding. **But** the `TouchInjector.kt` reflection approach is wrong because it uses `clazz.getDeclaredMethod(...)` which may not find the companion object methods through the Java class.

---

## 4. Controller Input - Polling Model

### Finding: INCORRECT AXIS READING

**File:** `app/src/main/java/com/gpmapper/app/service/MappingService.kt:183-234`

**Bug 1: Reading `flat` instead of actual axis value.**

```kotlin
private fun getCenteredAxis(...): Float {
    val value = device.getMotionRange(axis, source)?.let {
        val rawValue = it.flat  // <-- THIS IS THE DEADZONE THRESHOLD, NOT THE AXIS VALUE
        val rangeSize = it.range
        if (rangeSize == 0f) 0f else (rawValue / rangeSize)
    } ?: 0f
    return value
}
```

`MotionRange.flat` returns the flat zone (deadzone) size, not the current axis value. The function always returns `flat/range` which is a constant (typically ~0.1). The actual axis value is never read. **Controller analog sticks produce no meaningful output.**

**Correct approach:** Use `InputDevice.getMotionRange(axis, source)` only for range info, and read the actual value from a `MotionEvent` delivered via `InputDeviceListener` or by polling `InputManager` with proper event dispatch.

**Bug 2: DS4 device ID is captured once at startPolling() and never refreshed.** If the controller connects after service start, it is never discovered.

**Bug 3: The 4ms polling loop** reads device properties, not events. `InputManager.getInputDevice()` returns static device info, not the latest input state. There is no API to poll the "current" axis state of a joystick from `InputManager` without receiving a `MotionEvent`.

**Polling vs Event-driven:** The polling model is architecturally wrong. Android delivers controller input via `InputEvent` callbacks. The correct approach is to register an `InputManager.InputEventListener` (API 34+) or use `InputManager.registerInputDeviceListener` + capture events via `AccessibilityService` or `InputMethodService`. For older APIs, the standard approach is to have the service itself receive events through an input method or accessibility service.

---

## 5. Gesture Engine

### Finding: PARTIALLY IMPLEMENTED, SEVERAL BUGS

**File:** `app/src/main/java/com/gpmapper/app/input/GestureEngine.kt`

- `handleButtonPress`/`handleButtonRelease`: Functional logic for modifier tracking
- `checkAndExecuteCombos`: Correct bidirectional modifier check
- **Bug:** `handleButtonTap` calls `touchInjector.tap()` but `tap()` is never triggered by `MappingService` because the polling model never calls `handleButtonTap`
- **Bug:** `executeMacro` calculates `delay` but never uses it for timing between steps (the variable is accumulated but not applied as a sleep/wait)
- **Bug:** `handleLeftStick`/`handleRightStick` produce continuous touch moves on every polling cycle (250Hz), but without proper touch-down/touch-up lifecycle management. The touch pointer (ID 10/11) is moved every 4ms without ever being lifted, which may confuse the Android input subsystem.

---

## 6. Overlay Validation

### Finding: MOSTLY FUNCTIONAL

**File:** `app/src/main/java/com/gpmapper/app/overlay/OverlayManager.kt`

- Uses `TYPE_APPLICATION_OVERLAY` on API 26+ (correct)
- Permission check via `Settings.canDrawOverlays()` (correct)
- **Bug:** `FLAG_NOT_TOUCH_MODAL` on the root FrameLayout means ALL touch events are intercepted. For a transparent overlay used during gameplay, this should use `FLAG_NOT_TOUCHABLE` when not in config mode, or `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` and handle pass-through correctly.
- Drag-to-reposition of target views works correctly
- Profile loading from SharedPreferences works

---

## 7. Analog Processing

### Finding: CORRECT ALGORITHMS, UNUSED

**File:** `app/src/main/java/com/gpmapper/app/util/AnalogProcessor.kt`

The deadzone, response curve, and EMA smoothing implementations are mathematically correct. However:
- `DualShock4Handler.kt` implements its own duplicate analog processing
- `analog_processor.cpp` implements a third copy in C++
- None of these are connected to each other
- The Kotlin `AnalogProcessor` class is never instantiated or used anywhere

---

## 8. Build System

### Finding: WILL NOT COMPILE

**Critical build errors:**

1. **`CMakeLists.txt` links against `uinput` library** which does not exist in the NDK. This will cause a linker error: `cannot find -luinput`.

2. **`MappingProfile.kt` uses `@Serializable`** from `kotlinx.serialization` but the `kotlinx-serialization` Gradle plugin is not applied in `build.gradle.kts`. The `@Serializable` annotation will be present but no `$serializer` companion will be generated, causing runtime crashes when Gson tries to serialize/deserialize.

3. **`aidl = true` is enabled** in `buildFeatures` but no AIDL files exist. This may or may not cause a build error depending on AGP version.

4. **`MappingService` declared with `BIND_INPUT_METHOD` permission** and `InputMethod` intent-filter, but it does not implement `InputMethodService` or any input method interface. This will cause the system to reject the service registration.

---

## 9. DS4 Validation

### Finding: INCORRECT PRODUCT ID

**File:** `app/src/main/java/com/gpmapper/app/input/DualShock4Handler.kt`

```kotlin
const val DS4_VENDOR_ID = 0x054C
const val DS4_PRODUCT_ID = 0x09CC
```

`0x09CC` is the CUH-ZCT2 (v2) model. The original CUH-ZCT1 has product ID `0x05C4`. The DualSense (PS5) has `0x0CE6`. The code will miss the v1 DS4 and DualSense controllers entirely.

Additionally, many DS4 controllers connected via Bluetooth report different vendor/product IDs depending on the host's HID driver. Android's generic HID driver may present them with different IDs.

---

## 10. Latency Claims

### Finding: UNSUBSTANTIATED

The original spec claims "<5ms" latency. This is impossible with:
- Kotlin coroutine `delay()` calls (minimum 1ms granularity, typically 4-16ms)
- Coroutine dispatcher scheduling overhead
- JNI reflection overhead (Class.forName + Method.invoke per touch)
- The C++ `injectSwipe` uses `std::this_thread::sleep_for(2000us)` which adds 2ms per step minimum
- Android's input dispatch pipeline adds 1-2 frames of latency

Realistic achievable latency: **15-50ms end-to-end** on a typical device, limited by Android's input dispatch pipeline and the polling interval.

---

## Summary Table

| Component | Status | Blocking? |
|-----------|--------|-----------|
| /dev/uinput access | **IMPOSSIBLE** rootless | YES |
| Shizuku integration | **DEAD IMPORT** | YES |
| JNI touch injection | **CRASH** (null invoke) | YES |
| C++ native core | **LINK ERROR** (libuinput) | YES |
| Controller polling | **WRONG** (reads flat, not value) | YES |
| Gesture engine | Partial | No |
| Overlay system | Mostly functional | No |
| Analog processing | Correct but unused | No |
| Profile management | Functional | No |
| Compose UI | Functional | No |
| CI/CD workflow | Mostly correct | No |

---

## Architecture Changes Required

### Path A: Shizuku-Executed Native Daemon (Recommended)

1. Ship a static native binary (`gpmapper_daemon`) in the APK's assets
2. Use `Shizuku.newProcess()` to execute the binary with shell UID
3. The daemon opens `/dev/uinput`, creates a virtual touch device
4. The app communicates with the daemon via a local Unix domain socket
5. Injection commands: `TOUCH_DOWN:pointer_id:x:y:pressure\n`, `TOUCH_MOVE:...`, `TOUCH_UP:...`
6. The daemon runs a tight event loop, forwarding commands to `/dev/uinput`

**Advantages:** True rootless injection, shell UID has `/dev/uinput` access on most devices.
**Disadvantages:** Requires Shizuku running, binary must be architecture-compatible, socket adds ~0.1ms latency.

### Path B: Shizuku Shell Command Injection (Fallback)

1. Use `Shizuku.newProcess(["input", "tap", "x", "y"], null, null)` for basic taps
2. Use `Shizuku.newProcess(["input", "swipe", "sx", "sy", "ex", "ey", "duration"], null, null)` for swipes
3. Accept higher latency (50-200ms per command)
4. Use for non-time-critical actions only

### Path C: AccessibilityService Injection (No Shizuku)

1. Implement an `AccessibilityService` with `FLAG_REQUEST_TOUCH_EXPLORATION`
2. Use `dispatchGesture()` for tap/swipe injection
3. Works on all Android versions without root or Shizuku
4. Limited to 10 concurrent gestures, ~50ms minimum per gesture
5. Most viable path for a public release

**Recommendation:** Implement Path A (Shizuku daemon) as primary, Path C (AccessibilityService) as fallback, and deprecate the current `/dev/uinput` direct-access approach entirely.

---

## Device Test Plan

1. **Android 10-14, rooted:** Test `/dev/uinput` direct access
2. **Android 10-14, unrooted + Shizuku:** Test Shizuku daemon approach
3. **Android 10-14, unrooted, no Shizuku:** Test AccessibilityService fallback
4. **DS4 v1 (CUH-ZCT1), DS4 v2 (CUH-ZCT2), DualSense:** Verify vendor/product ID detection
5. **Bluetooth vs USB:** Verify input event delivery differences
6. **Different SoCs:** Snapdragon, Exynos, MediaTek, Tensor - verify input subsystem behavior

---

## Latency Measurement Methodology

1. Inject a touch at known timestamp T0
2. Use `MotionEvent.getEventTime()` on the receiving activity to measure T1
3. End-to-end latency = T1 - T0
4. Measure across 1000 iterations, report min/p50/p95/p99/max
5. Separate measurements for: JNI call overhead, uinput write, Android dispatch, app receipt

---

## Appendix: Forensic Backend Verification (2026-08-18)

### ShizukuDaemonBackend Verdict: NON-COMPLIANT

**Exact code** (`ShizukuDaemonBackend.kt:87-91`):
```kotlin
val cmd = arrayOf("sh", "-c", "input touchscreen tap ${(x * 1080).toInt()} ${(y * 2340).toInt()}")
val process = Shizuku.newProcess(cmd, null, null)
val exitCode = process.waitFor()
```

**Classification: NON-COMPLIANT** — Uses the `input` CLI executable which V1 requirements explicitly forbid ("input tap / input swipe are strictly forbidden"). Additional issues:
- Hardcoded 1080x2340 screen resolution
- `input touchscreen tap` only produces tap (down+up), no swipe or continuous movement
- `process.waitFor()` blocks for 50-200ms per injection
- No multi-touch capability
- The `action` parameter (`touch_down`/`touch_move`/`touch_up`) is ignored — all produce the same `input touchscreen tap` command

### NativeUinputBackend Verdict: UNAVAILABLE

**Exact code** (`native_input_core.cpp:31`): `open("/dev/uinput", O_WRONLY | O_NONBLOCK)`

**Classification: UNAVAILABLE on normal rootless devices** — The app process runs as UID `u0_aXXX` in the `untrusted_app` SELinux domain. `/dev/uinput` is owned by `system:system` with mode `0660`. SELinux `neverallow` rules in `untrusted_app.te` prevent access. `File.canWrite()` does not check SELinux and gives false positives. The C++ uinput protocol implementation is correct (slot-based MT protocol B) but cannot be reached.

### AccessibilityServiceBackend Verdict: FALLBACK (not implemented)

**Exact code** (`AccessibilityServiceBackend.kt`): Declares a `GestureCallback` interface but provides no implementation. No `AccessibilityService` subclass exists. No `accessibility_service_config.xml`. No manifest declaration. `injectTouchUp()` returns `true` as a no-op. Even if fully implemented, `AccessibilityService.dispatchGesture()` does not support multi-touch or continuous movement.

### Event Delivery: COMPLETELY DISCONNECTED

`MappingService.handleMotionEvent()` and `handleKeyEvent()` are public methods with **no caller**. The original polling model was removed during the audit. No `InputEventReceiver`, no `AccessibilityService`, no other mechanism feeds controller events to the mapping engine.

### Bottom Line

**There is currently no rootless injection backend capable of providing low-latency multi-touch injection on stock Android.** No backend satisfies V1 requirements. The project requires significant rearchitecture before any injection can be validated on a physical device.
