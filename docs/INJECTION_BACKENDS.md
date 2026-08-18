# Injection Backend Forensic Verification

**Date:** 2026-08-18
**Scope:** Exact injection path tracing, backend capability verification, V1 compliance

---

## 1. Complete Event Path Trace

```
DualShock 4 Hardware
  │ (Bluetooth/USB HID report)
  ▼
Android InputDispatcher
  │ (kernel evdev -> InputReader -> InputDispatcher)
  ▼
MotionEvent / KeyEvent delivered to app process
  │ (via InputManager or AccessibilityService callback)
  │
  │ ** BLOCKER: No code currently delivers events TO MappingService **
  │ MappingService.handleMotionEvent() and handleKeyEvent() are public methods
  │ but no caller invokes them. The original polling model was removed.
  │ There is no InputManager.InputEventReceiver, no AccessibilityService,
  │ and no other mechanism feeding events into MappingService.
  │
  ▼
MappingService.handleMotionEvent(event)          [line 187]
  │ Validates deviceId matches connectedDs4DeviceId
  │ Timestamps: controllerStart = System.nanoTime()
  ▼
DualShock4Handler.processMotionEvent(event)      [line 192]
  │ Reads AXIS_X, AXIS_Y, AXIS_Z, AXIS_RZ, AXIS_HAT_X/Y, AXIS_LTRIGGER/RTRIGGER
  │ Applies deadzone, response curve, EMA smoothing
  │ Detects pressed/released buttons via isButtonPressed()
  │ Returns ControllerState with sticks, triggers, buttons, pressedButtons, releasedButtons
  │ Timestamps: mappingStart = System.nanoTime()
  ▼
GestureEngine.handleButtonPress(button)          [line 197]
GestureEngine.handleButtonRelease(button)        [line 200]
GestureEngine.handleButtonTap(button)            [line 201]
GestureEngine.handleAnalogInput(stick, x, y)     [line 206-207]
GestureEngine.handleDpadInput(hatX, hatY)        [line 211]
  │ Looks up mapping in currentProfile.mappings
  │ For "tap": calls touchInjector.tap(x, y, 50, pointerId)
  │ For "swipe": calls touchInjector.smoothSwipe(startX, startY, endX, endY, durationMs, pointerId)
  │ For "hold": calls touchInjector.touchDown(pointerId, x, y)
  │ For combos: calls touchInjector.smoothSwipe() with modifier gesture config
  │ Timestamps: injectionStart = System.nanoTime()
  ▼
TouchInjector.tap() / smoothSwipe() / touchDown()    [lines 99-126]
  │ Enqueues TouchAction into ConcurrentLinkedQueue<TouchAction>
  │ Queue processed by coroutine on Dispatchers.Default
  │ executeAction() calls backend.injectTouchDown/Move/Up
  │ For swipe: executes lerp interpolation with Thread.sleep(delayPerStep)
  ▼
InjectionBackend.injectTouchDown(pointerId, x, y, pressure)
  │
  ├──► ShizukuDaemonBackend.injectViaShizuku()     [line 85-107]
  │      Calls: Shizuku.newProcess(["sh", "-c", "input touchscreen tap X Y"], null, null)
  │      Waits for: process.waitFor()
  │      Returns: exitCode == 0
  │
  ├──► NativeUinputBackend.injectTouchDown()       [line 80-91]
  │      Calls: nativeInjectTouch(nativeHandle, pointerId, x, y, pressure)
  │      JNI -> NativeInputCore::injectTouch()
  │      Opens: /dev/uinput (O_WRONLY | O_NONBLOCK)
  │      Creates: virtual MT touch device via UI_DEV_CREATE
  │      Writes: input_event structs (EV_ABS, EV_KEY, EV_SYN)
  │
  └──► AccessibilityServiceBackend.injectTouchDown()  [line 42-53]
         Calls: gestureCallback.dispatchTap(x, y, 50)
         gestureCallback is set by external caller via setGestureCallback()
         No actual AccessibilityService implementation exists in the codebase
```

---

## 2. ShizukuDaemonBackend — Forensic Analysis

### Exact Code (lines 85-107)

```kotlin
private fun injectViaShizuku(action: String, pointerId: Int, x: Float, y: Float, pressure: Float): Boolean {
    return try {
        val cmd = arrayOf(
            "sh", "-c",
            "input touchscreen tap ${(x * 1080).toInt()} ${(y * 2340).toInt()}"
        )
        val process = Shizuku.newProcess(cmd, null, null)
        val exitCode = process.waitFor()
        ...
```

### Exact Injection Mechanism

| Step | Detail |
|------|--------|
| **API used** | `Shizuku.newProcess(String[], String[], String[])` |
| **Process UID** | `shell` (UID 2000) — Shizuku's helper process runs as shell |
| **Command executed** | `sh -c "input touchscreen tap 540 1170"` (example) |
| **Android tool invoked** | `input` CLI (`com.android.commands.input.Input`) |
| **Final injection API** | `InputManager.injectInputEvent()` via reflection inside the `input` CLI process |
| **Event type created** | `MotionEvent.obtain()` with ACTION_DOWN then ACTION_UP at same coordinates |
| **Screen resolution** | **Hardcoded to 1080x2340** — not read from device |
| **Action parameter** | Ignored — `touch_down`, `touch_move`, `touch_up` all produce the same `input touchscreen tap` |

### What `input touchscreen tap` Actually Does

1. `Input.main()` parses arguments
2. Creates `MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)` 
3. Calls `InputManager.getInstance().injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)`
4. Sleeps 50ms (hardcoded in AOSP `input` source)
5. Creates `MotionEvent.obtain(..., ACTION_UP, ...)`
6. Calls `injectInputEvent()` again
7. Returns

### Critical Limitations

| Limitation | Impact |
|------------|--------|
| **Uses `input` CLI** | Explicitly forbidden by V1 requirements ("input tap / input swipe are strictly forbidden") |
| **Blocking `process.waitFor()`** | Each injection blocks the calling thread for 50-200ms |
| **Hardcoded 1080x2340** | Wrong coordinates on any other resolution |
| **No continuous move** | `input touchscreen tap` only does tap (down+up). No swipe, no drag, no continuous movement |
| **No multi-touch** | `input touchscreen tap` creates single-pointer events only |
| **No pointer tracking** | Cannot maintain separate pointer IDs for concurrent touches |
| **~100ms per injection** | `input` CLI has 50ms sleep + process spawn overhead + IPC overhead |
| **`input touchscreen` variant** | Not available on all Android versions; `input tap` is the standard form |

### V1 Compliance: **NON-COMPLIANT**

The backend uses the `input` CLI executable, which the V1 requirements explicitly forbid. It cannot perform continuous swipe, multi-touch, or achieve low latency.

---

## 3. NativeUinputBackend — Forensic Analysis

### Exact Code Path

```
NativeUinputBackend.checkAvailability()          [line 32-38]
  → File("/dev/uinput").exists() && File("/dev/uinput").canWrite()

NativeUinputBackend.initialize()                 [line 41-77]
  → nativeCreate()  → new NativeInputCore()
  → nativeInitialize(handle)  → NativeInputCore::initialize()
    → open("/dev/uinput", O_WRONLY | O_NONBLOCK)
    → createVirtualDevice()
      → ioctl(UI_SET_EVBIT, EV_ABS/EV_KEY/EV_SYN)
      → ioctl(UI_SET_ABSBIT, ABS_MT_*)
      → ioctl(UI_SET_KEYBIT, BTN_TOUCH/BTN_TOOL_FINGER)
      → ioctl(UI_ABS_SETUP, ...) for each axis
      → ioctl(UI_DEV_CREATE)
      → usleep(200000)  // 200ms wait for device registration
```

### Linux Permission Analysis

| Property | Value | Source |
|----------|-------|--------|
| **`/dev/uinput` owner** | `system:system` or `input:input` | Android init.rc, varies by OEM |
| **`/dev/uinput` permissions** | `0660` (rw-rw----) or `0600` (rw-------) | Android ueventd.rc |
| **App process UID** | `u0_aXXX` (10000+ range) | Android application sandbox |
| **App process groups** | `inet`, `inet_raw`, `media_rw`, etc. | No `input` or `system` group |
| **SELinux domain** | `untrusted_app` (or `untrusted_app_XX` on newer) | Default for third-party apps |
| **SELinux uinput access** | **neverallow** in `untrusted_app.te` | AOSP CTS policy |

### Why `File.canWrite()` Gives False Positives

`File.canWrite()` checks the Unix DAC permission bits (owner/group/other write). On Linux, DAC is checked first. If the file mode says `0660` and the process is in the `system` group, DAC passes. **But** SELinux's mandatory access control is checked at the `open()` syscall level, after DAC. A DAC-passing open can still be denied by SELinux with `avc: denied { write } for ... scontext=u:r:untrusted_app:s0 ... tcontext=u:object_r:input_device:s0`.

`File.canWrite()` **does not check SELinux**. It returns `true` even when SELinux will block the actual `open()`.

### What Happens on Real Devices

| Device State | `File.canWrite()` | `open("/dev/uinput")` | Result |
|-------------|-------------------|----------------------|--------|
| Rooted, permissive SELinux | `true` | `true` (fd >= 0) | Works |
| Rooted, enforcing SELinux, custom policy | `true` | Depends | May work |
| Unrooted, Shizuku process | N/A (different process) | N/A | Different UID |
| **Unrooted, app process, stock** | **`true` on some** | **`false` (errno=13 EACCES, or EPERM)** | **FAILS** |
| Unrooted, app process, enforcing SELinux | `true` on some | **`false` (errno=13, dmesg: avc denied)** | **FAILS** |

### If Open Succeeds (root/shell UID)

The C++ code correctly implements the Linux `uinput` protocol:
- Creates a virtual multitouch device with `ABS_MT_SLOT`, `ABS_MT_TRACKING_ID`, `ABS_MT_POSITION_X/Y`
- Proper slot-based protocol B event sequencing
- Correct `SYN_REPORT` framing
- Supports up to 10 concurrent touch pointers

### V1 Compliance: **UNAVAILABLE on normal rootless devices**

Requires either root access or a process running with `shell` UID and appropriate SELinux context. The code itself is correct but the access path is blocked on stock Android.

---

## 4. AccessibilityServiceBackend — Forensic Analysis

### Exact Code

```kotlin
interface GestureCallback {
    fun dispatchTap(x: Float, y: Float, durationMs: Long): Boolean
    fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean
    fun dispatchMultiGesture(events: Array<InjectionBackend.TouchEvent>): Boolean
}

fun setGestureCallback(callback: GestureCallback) {
    gestureCallback = callback
    isAvailable = true
}
```

### What Exists

- Interface definition for a callback
- `setGestureCallback()` sets the callback and marks backend as available
- `injectTouchDown()` calls `gestureCallback.dispatchTap(x, y, 50)`
- `injectTouchUp()` returns `true` (no-op)
- `injectTouchMove()` calls `injectTouchDown()` (same as tap)

### What Does NOT Exist

1. **No `AccessibilityService` subclass** in the codebase
2. **No `accessibility_service_config.xml`** in resources
3. **No manifest declaration** for an AccessibilityService
4. **No actual `dispatchGesture()` call** anywhere in the code
5. **No implementation** of the `GestureCallback` interface
6. **The callback is never registered** — `MappingService.registerAccessibilityCallback()` exists but is never called

### If an AccessibilityService Were Implemented

| Capability | `AccessibilityService` API | Limitation |
|-----------|--------------------------|------------|
| Tap | `dispatchGesture(GestureDescription)` with stroke path | Single pointer only |
| Long press | `dispatchGesture()` with hold duration | Single pointer only |
| Swipe | `dispatchGesture()` with stroke path from A to B | Single pointer only |
| Multi-touch | **NOT SUPPORTED** | `GestureDescription` supports max 1 pointer per stroke, max 10 strokes, but each stroke is independent and cannot represent concurrent touch on same screen region |
| Continuous movement | **NOT SUPPORTED** | Must dispatch new gesture for each move; ~50ms minimum between gestures |
| Simultaneous pointers | **NOT SUPPORTED** | Gestures are dispatched sequentially, not as concurrent touch streams |
| Minimum gesture duration | ~50-100ms | Platform-imposed; shorter gestures may be ignored |
| API level | API 24+ (Android 7.0) | Not available on older devices |

### V1 Compliance: **FALLBACK only**

Cannot satisfy multi-touch, continuous movement, or low-latency requirements. Only usable as a last-resort fallback for simple tap actions.

---

## 5. Backend Capability Matrix

| Property | ShizukuDaemonBackend | NativeUinputBackend | AccessibilityServiceBackend |
|----------|---------------------|--------------------|-----------------------------|
| **V1 Classification** | NON-COMPLIANT | UNAVAILABLE | FALLBACK |
| **Root Required** | No (Shizuku) | Yes (or shell UID) | No |
| **Shizuku Required** | Yes | No | No |
| **Injection Mechanism** | `input` CLI via `Shizuku.newProcess()` | `/dev/uinput` via JNI | `dispatchGesture()` (not implemented) |
| **Input CLI Used** | **YES** — `input touchscreen tap` | No | No |
| **Framework API Used** | Indirectly (via `input` CLI -> InputManager) | Direct kernel uinput | `AccessibilityService.dispatchGesture` (not wired) |
| **Multi-touch** | No | Yes (10 pointers) | No |
| **Continuous Swipe** | No (tap only) | Yes (lerp interpolation) | No (single gesture at a time) |
| **Continuous Movement** | No | Yes (per-frame injection) | No |
| **Pointer ID Tracking** | No | Yes (slot-based MT protocol) | No |
| **Expected Latency** | 100-300ms per injection | <1ms kernel write | 50-100ms per gesture dispatch |
| **Stock Android Support** | Yes (with Shizuku installed) | No | Yes (with AccessibilityService enabled) |
| **Screen Resolution** | Hardcoded 1080x2340 | Reads from DisplayMetrics | N/A (callback-provided) |
| **Event Timestamps** | None (CLI process) | Kernel timestamps | N/A |
| **Observable E2E Validation** | Not implemented | Not implemented | Not implemented |

---

## 6. V1 Compliance Classification

### PRIMARY (Required for V1)
**None.** There is currently no backend that satisfies V1 requirements:
- Low latency (<5ms target)
- Multi-touch (10 pointers)
- Continuous movement/swipe
- Works on rootless stock Android

### NON-COMPLIANT
**ShizukuDaemonBackend** — Uses the `input` CLI which is explicitly forbidden by V1 requirements. Cannot perform multi-touch or continuous movement.

### UNAVAILABLE
**NativeUinputBackend** — Code is correct but `/dev/uinput` is inaccessible from the app process on unrooted stock Android. Requires root or shell UID.

### FALLBACK
**AccessibilityServiceBackend** — No actual implementation exists. Even if implemented, cannot provide multi-touch or continuous movement.

---

## 7. Critical Architectural Gaps

### Gap 1: No Event Delivery to MappingService

`MappingService.handleMotionEvent()` and `handleKeyEvent()` are public methods but **no caller invokes them**. The previous polling model was removed during the audit. There is currently:

- No `InputManager.InputEventReceiver` (API 24+, requires Looper-based input channel)
- No `AccessibilityService` subclass that receives events
- No broadcast receiver for controller events
- No other mechanism feeding controller events into MappingService

**Result:** Even if a working injection backend existed, no controller events would reach the mapping engine.

### Gap 2: No Shizuku Binder Proxy for IInputManager

The `ShizukuDaemonBackend` uses shell command execution, not a binder proxy. A proper Shizuku integration would:

1. Use `ShizukuBinderWrapper` to wrap `IInputManager` from `ServiceManager.getService("input")`
2. Call `IInputManager.injectInputEvent(MotionEvent, mode)` through the wrapped binder
3. This would run with `shell` UID privileges inside Shizuku's process

However, `IInputManager.injectInputEvent()` requires `android.permission.INJECT_EVENTS` which is a signature-level permission. The `shell` UID has this permission via `appops`. A Shizuku binder proxy **could** technically forward this call, but:

- Shizuku's `newProcess()` executes shell commands, not arbitrary binder calls
- `ShizukuBinderWrapper` exists for wrapping arbitrary services, but wrapping `IInputManager` requires knowing the exact AIDL interface and service registration
- This is technically possible but requires significant implementation work
- Even if the binder call succeeds, there is no way to verify the event was accepted without observable end-to-end validation on a physical device

### Gap 3: No Observable End-to-End Validation

No backend implements any mechanism to confirm that Android actually received and dispatched the injected touch event. The `DiagnosticTestRunner` calls `backend.injectTouchDown()` and checks the return value, but:

- `ShizukuDaemonBackend` returns `true` if `exitCode == 0` (the `input` CLI process exited successfully)
- `NativeUinputBackend` returns `true` if `write()` to `/dev/uinput` returned the expected byte count
- Neither verifies that Android's InputDispatcher actually delivered the event to a target window

---

## 8. Remaining Blockers for V1

| # | Blocker | Impact |
|---|---------|--------|
| 1 | No event delivery from controller to MappingService | Nothing works |
| 2 | No rootless backend with multi-touch + low latency | Core requirement unmet |
| 3 | ShizukuDaemonBackend uses forbidden `input` CLI | Non-compliant |
| 4 | NativeUinputBackend blocked by SELinux on stock Android | Unavailable |
| 5 | AccessibilityServiceBackend has no implementation | No fallback exists |
| 6 | No Shizuku binder proxy for IInputManager | No proper Shizuku integration |
| 7 | No end-to-end validation | Cannot verify injection works |

---

## 9. Physical Device Tests Required

### Test 1: /dev/uinput Accessibility
```
adb shell "ls -la /dev/uinput"
adb shell "su -c 'echo test > /dev/uinput'"  # If rooted
# From app process:
# File("/dev/uinput").canWrite() -> ?
# open("/dev/uinput", O_WRONLY) -> fd? errno? dmesg avc?
```

### Test 2: Shizuku Shell Injection
```
# With Shizuku running:
# Shizuku.newProcess(["input", "tap", "540", "1170"], null, null)
# Does the tap appear on screen? How fast?
# Measure: time from process.start() to screen response
```

### Test 3: Shizuku Binder Proxy (IInputManager)
```
# Attempt to get IInputManager binder:
# ShizukuBinderWrapper.wrap(ServiceManager.getService("input"))
# Call IInputManager.injectInputEvent(MotionEvent, mode)
# Does it return true? Does Android dispatch the event?
```

### Test 4: AccessibilityService Gesture Dispatch
```
# Register AccessibilityService with FLAG_REQUEST_TOUCH_EXPLORATION
# Call dispatchGesture(GestureDescription, callback, handler)
# Does the gesture appear? What is the minimum duration?
# Can multiple gestures be in-flight simultaneously?
```

### Test 5: End-to-End Latency
```
# Inject touch at known T0
# Instrument target app to log MotionEvent.getEventTime() at T1
# Latency = T1 - T0
# Repeat 1000x, measure p50/p95/p99
```

---

## 10. Recommendation for Next Engineering Step

**The project has no working injection path.** Before any feature development:

1. **Fix event delivery** — Implement an `InputEventReceiver` or `AccessibilityService` to actually receive controller events and feed them to `MappingService.handleMotionEvent()`

2. **Evaluate Shizuku binder proxy** — Determine whether `IInputManager.injectInputEvent()` is callable through Shizuku's binder wrapper with shell UID. This is the most promising rootless path but requires a proof-of-concept on a real device.

3. **If Shizuku binder proxy works** — Implement it as the PRIMARY backend with proper `MotionEvent` construction (not `input` CLI)

4. **If Shizuku binder proxy does not work** — The only remaining options are:
   - Root-only uinput backend (mark as requiring root)
   - AccessibilityService fallback (accept latency/multitouch limitations)
   - Ship a Shizuku helper APK that runs a persistent daemon with shell UID

5. **Remove `ShizukuDaemonBackend`** in its current form — it uses the forbidden `input` CLI

6. **Build device test harness** — A minimal APK that tests each injection mechanism and reports results, before rebuilding the full application
