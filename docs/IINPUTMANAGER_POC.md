# IInputManager Shizuku Binder PoC

## Overview

This PoC validates whether `injectInputEvent()` via `ShizukuBinderWrapper` can provide real, observable touch injection on stock Android without root, using Shizuku's shell UID to bypass the `INJECT_EVENTS` permission check.

## Current Status

**BINDARY ACCEPTED, DISPATCH NOT CONFIRMED**

`injectInputEvent()` returns `true` on Android 15 (API 35). Events are accepted by the Binder interface. However, **zero injected events are observed arriving at the View's `onTouchEvent()` callback**. The InputDispatcher silently drops the events after Binder acceptance. This is the current blocking issue.

## Architecture

```
Shizuku Process (UID 2000 = shell)
    └─ ServiceManager.getService("input") → IBinder
    └─ ShizukuBinderWrapper(rawBinder) → wrapped IBinder
    └─ IInputManager.Stub.asInterface(wrappedBinder) → AIDL proxy
    └─ proxy.injectInputEvent(event, mode) → returns true
        └─ InputManagerService.injectInputEvent()
            └─ Binder.getCallingUid() → 2000 (shell)
            └─ appops check → shell has INJECT_EVENTS
            └─ accepts event, adds to InputDispatcher queue
            └─ InputDispatcher validates event... [SILENT DROP HERE]
```

## What Works

| Stage | Status | Evidence |
|-------|--------|----------|
| A: Shizuku bound & authorized | VERIFIED | `shizukuBound=true`, `shizukuAuthorized=true` |
| A: Raw IBinder acquired | VERIFIED | `ServiceManager.getService("input")` non-null |
| A: ShizukuBinderWrapper created | VERIFIED | Wrapper object created successfully |
| A: IInputManager.Stub.asInterface proxy | VERIFIED | Proxy object: `android.hardware.input.IInputManager$Stub$Proxy` |
| B: MotionEvent creation | VERIFIED | `MotionEvent.obtain(6-param)` SUCCESS, `setSource(SOURCE_TOUCHSCREEN)` |
| B: MotionEvent fields logged | VERIFIED | All fields logged before injection |
| C: injectInputEvent() returns true | VERIFIED | Binder call succeeds, no exception, returns `true` |
| D: Framework dispatches event | **NOT CONFIRMED** | Zero events arrive at View.onTouchEvent() |
| E: View receives event | **NOT CONFIRMED** | No `[INJ]` tagged events in canvas records |

## What Doesn't Work

### Root Cause Analysis

`injectInputEvent()` returns `true`, meaning `InputManagerService` accepted the event into its input pipeline. However, the event never reaches the target Activity's `View.onTouchEvent()`. Possible causes investigated:

1. **Display ID mismatch**: Events injected without specifying display 0 may be dispatched to a wrong display. Current code uses default display for both Activity and injection.

2. **Event source validation**: Android 15 InputDispatcher may reject events with `SOURCE_TOUCHSCREEN` from a process that doesn't own a touchscreen device. Shell UID (2000) doesn't have a registered input device.

3. **FLAG_INJECTED not set**: Android's internal injection path sets `FLAG_INJECTED` on events. Without this flag, the InputDispatcher may treat the event as a synthetic event and apply different validation rules. Current fix: we now set `FLAG_INJECTED` manually.

4. **Window token validation**: InputDispatcher validates that the target window's token matches the calling process. Injected events may need special handling to route to the correct window.

5. **Android 15 security hardening**: Android 15 may have additional restrictions on input injection that were not present in earlier versions. The `injectInputEvent()` API may accept events but the InputDispatcher may silently filter them.

6. **SELinux context**: Even though Binder calls succeed (UID check passes), SELinux may block the actual event dispatch at a lower level.

### Forensic Evidence

- `injectInputEvent()` returns `true` for all event types (DOWN, UP, MOVE, POINTER_DOWN, POINTER_UP)
- No `SecurityException` or other exception thrown
- No events observed in `VisualTouchCanvas.onTouchEvent()`
- Physical touch events ARE observed (green [PHY] tags appear)
- `MotionEvent.deviceId = 0` for injected events (correct for synthetic events)
- `MotionEvent.source = SOURCE_TOUCHSCREEN` (0x00001002) set explicitly
- `MotionEvent.flags` includes `FLAG_INJECTED` (0x01000000) after fix

## Hidden APIs Used

| API | Purpose | Risk Level |
|-----|---------|------------|
| `android.os.ServiceManager.getService(String)` | Get system service binder | @hide, reflection required |
| `android.hardware.input.IInputManager$Stub.asInterface(IBinder)` | Get AIDL proxy | @hide, reflection required |
| `android.hardware.input.IInputManager.injectInputEvent()` | Inject motion/key events | @hide, via AIDL proxy |

## Diagnostic Instrumentation

### MotionEventFactory
- Creates events via direct `MotionEvent.obtain(6-param)` public API
- Sets `SOURCE_TOUCHSCREEN` and `FLAG_INJECTED`
- Logs comprehensive event diagnostics before injection

### InjectionTestRunner
- 5-stage result model: A (Binder) → B (Create) → C (Inject) → D (Observe) → E (View)
- Logs injection mode (ASYNC=0, WAIT_FOR_FINISH=1)
- Logs all MotionEvent fields before injection
- Reports receiver counter changes

### VisualTouchCanvas
- Raw `onTouchEvent()` receives ALL MotionEvents
- Classifies by `FLAG_INJECTED` or `deviceId == 0`
- Logs action, source, deviceId, flags, pointerCount for every event

### PoCActivity
- Logs display ID, window focus, lifecycle state, window token, bounds
- Logs raw event counter for ALL events reaching the root View
- Separate physical vs injected counters

## Device/Test Environment

**Test Device:**
- Model: [TO BE FILLED]
- Android version: 15
- API level: 35
- Build fingerprint: [TO BE FILLED]
- Root status: ROOTLESS
- Shizuku version: [TO BE FILLED]

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

| Test | A:Binder | B:Create | C:Inject | D:Observe | E:View | Classification |
|------|----------|----------|----------|-----------|--------|----------------|
| Single Tap | OK | OK | OK (true) | **0 events** | **0 events** | PARTIALLY_VERIFIED |
| Swipe | OK | OK | OK (true) | **0 events** | **0 events** | PARTIALLY_VERIFIED |
| Two-Pointer | OK | OK | OK (true) | **0 events** | **0 events** | PARTIALLY_VERIFIED |

**CRITICAL: "returns true" is ONLY Binder-level acceptance. Actual Android InputDispatcher/View delivery has NOT been physically demonstrated.**

## Latency Measurements

**IMPORTANT:** Binder-return latency ≠ end-to-end input-to-screen latency.

- Binder-return time: Cannot measure until events are actually dispatched
- Actual touch-to-display latency: **TO BE PHYSICALLY MEASURED** once dispatch is confirmed

## Known Limitations

1. **InputDispatcher silent drop**: The primary blocking issue. Events accepted by Binder but not dispatched to windows.
2. **Transaction codes may vary**: `FIRST_CALL_TRANSACTION = 1` is AIDL default but may differ on custom Android builds.
3. **Interface token may vary**: `"android.hardware.input.IInputManager"` may not exist on all API levels.
4. **Hidden API restrictions**: Android 9+ blocks reflection on hidden APIs; Shizuku bypasses this for Binder calls.
5. **SELinux policy**: Even with shell UID, SELinux may block event dispatch.
6. **Android 15 hardening**: Additional input validation may prevent dispatched events from reaching windows.

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| `injectInputEvent()` returns true but no events | InputDispatcher rejects event | Check display ID, source, flags; try WAIT_FOR_FINISH mode |
| `SecurityException` | SELinux or permission denied | Check `adb shell cat /proc/attr/current` |
| MotionEvent not created | Hidden API blocked | Use direct public `MotionEvent.obtain()` |
| Events arrive at wrong window | Display ID mismatch | Verify Activity display ID matches injection target |

## Next Steps

### Immediate (Current Blocker)
1. Investigate why InputDispatcher silently drops accepted events
2. Test with `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH` (mode=1) to see if error is returned
3. Check if `InputDevice.SOURCE_KEYBOARD` works instead of `SOURCE_TOUCHSCREEN`
4. Verify display ID 0 is correct for the target Activity
5. Check Android 15 `InputManagerService` source code for injection validation

### If Dispatch Confirmed
1. Integrate `IInputManagerBinderHelper` into production architecture
2. Profile end-to-end latency
3. Test multi-pointer injection under real DS4 input
4. Optimize for <5ms latency target

## Files

- `IInputManagerBinderHelper.kt` — Binder acquisition and AIDL proxy injection
- `InjectionTestRunner.kt` — 5-stage test sequence with diagnostics
- `VisualTouchCanvas.kt` — Visual event observation with raw logging
- `PoCActivity.kt` — Diagnostic UI with window/display/lifecycle diagnostics
- `MotionEventFactory.java` — MotionEvent creation with FLAG_INJECTED
- `LatencyRecorder.kt` — Percentile latency statistics
