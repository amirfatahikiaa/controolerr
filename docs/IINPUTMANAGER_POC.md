# IInputManager Shizuku Binder PoC

## Overview

This PoC validates whether `injectInputEvent()` via `ShizukuBinderWrapper` can provide real, observable touch injection on stock Android without root, using Shizuku's shell UID to bypass the `INJECT_EVENTS` permission check.

## Architecture

```
Shizuku Process (UID 2000 = shell)
    └─ ServiceManager.getService("input") → IBinder
    └─ ShizukuBinderWrapper.wrap(binder) → wrapped IBinder
    └─ wrapped.transact(FIRST_CALL_TRANSACTION, data, reply, 0)
        └─ IInputManager$Stub.onTransact(1, ...)
            └─ injectInputEvent(MotionEvent, mode)
```

**Security Path:**
- `InputManagerService.injectInputEvent()` checks `Binder.getCallingUid()`
- When invoked through Shizuku's binder wrapper, `getCallingUid()` returns 2000 (shell UID)
- Shell UID has `INJECT_EVENTS` appops permission on most devices
- Framework sees shell UID → allows injection

## Hidden APIs Used

| API | Purpose | Risk Level |
|-----|---------|------------|
| `android.os.ServiceManager.getService(String)` | Get system service binder | @hide, reflection required |
| `android.hardware.input.IInputManager$Stub` | AIDL-generated stub for injectInputEvent | Varies by Android version |
| `android.hardware.input.InputManager.injectInputEvent()` | High-level wrapper (alternative) | @hide, reflection required |
| `dev.rikka.shizuku.ShizukuBinderWrapper.wrap(IBinder)` | Wrap binder to run calls in Shizuku process | Shizuku API |

## Transaction Protocol

```
FIRST_CALL_TRANSACTION = 1 (AIDL default)
Parcel.writeInterfaceToken("android.hardware.input.IInputManager")
Parcel.writeStrongBinder(motionEvent.writeToParcel())  // via Parcel.marshall()
Parcel.writeInt(INJECT_INPUT_EVENT_MODE_ASYNC = 0)      // or WAIT_FOR_FINISH = 1
```

**Note:** Transaction codes and interface tokens may vary across Android versions (API 29–35). This PoC targets the most common AIDL pattern but may require adjustment.

## Device/Test Environment

**Test Device:**
- Model: [TO BE FILLED]
- Android version: [TO BE FILLED]
- API level: [TO BE FILLED]
- Build fingerprint: [TO BE FILLED]
- Root status: [ROOTED / ROOTLESS]
- Shizuku version: [TO BE FILLED]
- Shizuku method: [ADB over WiFi / ADB over USB / Root]

**Test Conditions:**
- Display refresh rate: [TO BE FILLED]
- Target application: [TO BE FILLED]
- Time of test: [TO BE FILLED]

## Test Procedure

### Stage A: Binder Acquisition
1. Call `ServiceManager.getService("input")` → verify non-null
2. Call `ShizukuBinderWrapper.wrap(binder)` → verify wrapped
3. Verify Shizuku process is running and authorized

### Stage B: Single Tap Injection
1. Create `MotionEvent` with ACTION_DOWN at (500, 500), then ACTION_UP after 16ms
2. Call `injectInputEvent(motionEvent, 0)` (ASYNC mode)
3. Observe: Does a touch appear on screen at (500, 500)?

### Stage C: Swipe Injection
1. Create sequence of 30 MotionEvents from (200, 800) to (800, 200) over 500ms
2. Call `injectInputEvent()` for each event
3. Observe: Does a swipe gesture register on screen?

### Stage D: Two-Pointer Injection
1. Create two simultaneous MotionEvents with different pointerIds
2. Call `injectInputEvent()` for both (threaded to overlap)
3. Observe: Does multi-touch register (two pointers visible)?

### Stage E: Physical Touch Comparison
1. Physically touch screen at known coordinates
2. Compare injection timing, coordinates, and behavior
3. Document any differences

## Test Results

### Classification System

| Classification | Meaning |
|----------------|---------|
| **VERIFIED** | Injected events appear on screen, touch confirmed visible, coordinates match |
| **PARTIALLY_VERIFIED** | Binder call succeeds (no exception) but events not visible on screen |
| **UNVERIFIED** | Binder call succeeds but cannot confirm event delivery (no device test) |
| **FAILED** | Binder call throws exception or returns false |
| **BLOCKED** | Shizuku not available, permission denied, or SELinux blocks operation |

### Results Table

| Test | Stage | Status | Details |
|------|-------|--------|---------|
| Binder Acquisition | A | [PENDING] | |
| Single Tap | B | [PENDING] | |
| Swipe | C | [PENDING] | |
| Two-Pointer | D | [PENDING] | |
| Physical Comparison | E | [PENDING] | |

### Latency Measurements

**IMPORTANT:** Binder-return latency ≠ end-to-end input-to-screen latency.

- Binder-return time: [PENDING] microseconds (p50/p95)
- Actual touch-to-display latency: [TO BE PHYSICALLY MEASURED]

Latency components not measured by this PoC:
1. Android input pipeline dispatch
2. SurfaceFlinger composition
3. Display refresh (16.6ms at 60Hz, 8.3ms at 120Hz)
4. Application rendering

### Observable Behaviors

Describe what you see on screen when injection runs:

```
[TO BE FILLED DURING TESTING]
```

### Logs

```
[TO BE CAPTURED FROM PoCActivity]
```

## Final Classification

```
Overall: UNVERIFIED
```

**Cannot be changed to VERIFIED without physical device testing.**

## Known Limitations

1. **Transaction codes may vary**: `FIRST_CALL_TRANSACTION = 1` is AIDL default but may differ on custom Android builds
2. **Interface token may vary**: `"android.hardware.input.IInputManager"` may not exist on all API levels
3. **Hidden API restrictions**: Android 9+ blocks reflection on hidden APIs by default; Shizuku may or may not bypass this depending on the API
4. **SELinux policy**: Even with shell UID, SELinux may block the binder transaction
5. **MotionEvent deserialization**: The receiver must be able to deserialize the Parcel-encoded MotionEvent correctly
6. **Timing precision**: `SystemClock.uptimeMillis()` granularity is ~1ms; `System.nanoTime()` is used for measurement but actual injection timing depends on binder thread scheduling

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| `ServiceManager.getService("input")` returns null | Hidden API blocked or wrong service name | Try reflection on `InputManager.class.getMethod("getInstance")` |
| `ShizukuBinderWrapper.wrap()` throws | Shizuku API changed | Check Shizuku docs for correct method: `ShizukuBinderWrapper(binder)` constructor |
| `injectInputEvent()` returns false | Permission check failed | Verify `appops set com.android.shell INJECT_EVENTS allow` |
| `SecurityException` | SELinux or permission denied | Check `adb shell cat /proc/attr/current` for SELinux context |
| MotionEvent not deserialized | Parcel format mismatch | Try `InputManager.injectInputEvent()` via reflection on `android.hardware.input.InputManager` class directly |
| No visible touch | Event dropped by window manager | Verify target window has `FLAG_NOT_TOUCH_MODAL` and is focusable |

## Next Steps (Post-Validation)

### If VERIFIED:
1. Integrate `IInputManagerBinderHelper` into `NativeInputCore` as `BinderBackend`
2. Remove all references to `ShizukuDaemonBackend` (NON-COMPLIANT)
3. Wire `MappingService.handleMotionEvent()` to actual DS4 input events
4. Profile latency under load (10-pointer multi-touch)
5. Optimize `MotionEvent` construction to avoid allocation in hot path

### If UNVERIFIED/BLOCKED:
1. Try alternative injection paths:
   - `InputManager.getInstance().injectInputEvent()` via reflection
   - `InputManager.injectInputEvent()` directly if accessible
   - AIDL `IInputManager.Stub.asInterface(ServiceManager.getService("input"))`
2. Investigate Shizuku's internal `injectInputEvent` implementation
3. Consider Shizuku module approach (run code inside Shizuku process directly)

## Files

- `IInputManagerBinderHelper.kt` — Binder acquisition and injection
- `InjectionTestRunner.kt` — 5-stage test sequence
- `VisualTouchCanvas.kt` — Visual event observation
- `PoCActivity.kt` — Diagnostic UI
- `LatencyRecorder.kt` — Percentile latency statistics
