# IInputManager Shizuku Binder PoC

## Overview

This PoC validates whether `injectInputEvent()` via `ShizukuBinderWrapper` can provide real, observable touch injection on stock Android without root, using Shizuku's shell UID to bypass the `INJECT_EVENTS` permission check.

## Verified Facts (Physically Confirmed)

| Fact | Status | Evidence |
|------|--------|----------|
| Shizuku bound & authorized | VERIFIED | `shizukuBound=true`, `shizukuAuthorized=true` |
| Raw IBinder acquired | VERIFIED | `ServiceManager.getService("input")` non-null |
| IInputManager.Stub.asInterface proxy | VERIFIED | Proxy class: `android.hardware.input.IInputManager$Stub$Proxy` |
| injectInputEvent() returns true (single-touch) | VERIFIED | Binder call succeeds, returns `true`, no exception |
| Canvas receives injected single-touch events | VERIFIED | Events observed in `VisualTouchCanvas.onTouchEvent()` |
| Canvas receives injected swipe events | VERIFIED | Swipe sequence observed in `VisualTouchCanvas.onTouchEvent()` |
| Multi-pointer events: partial delivery | PARTIALLY_VERIFIED | Some events in sequence drop (see below) |
| E2E latency measurement | UNAVAILABLE | Cross-domain timestamp subtraction produces invalid values |
| DS4 controller integration | UNTESTED | Not connected to injection pipeline |

## Event Classification Limitation

**Public SDK cannot reliably distinguish injected from physical events.**

- `FLAG_INJECTED` (0x01000000) is set by Android's InputDispatcher internally
- `MotionEvent.setFlags()` is a hidden API not available in `compileSdk 35`
- Events created via `MotionEvent.obtain(6-param)` have `deviceId=0` and `SOURCE_TOUCHSCREEN`
- Without `FLAG_INJECTED`, these events are classified as `UNKNOWN` (yellow)
- The `INJECTED_CANDIDATE` tag is used when `deviceId==0 AND source==SOURCE_TOUCHSCREEN`
- All visual tags are `[UNK]` (yellow) by default — no reliable public SDK classifier exists

## Multi-Touch Findings

### Sub-test Results (Isolated)

| Sub-test | Expected | Received | Classification |
|----------|----------|----------|----------------|
| D1: DOWN only | 1 | 1 | VERIFIED |
| D2: DOWN + POINTER_DOWN | 2 | varies | PARTIALLY_VERIFIED |
| D3: DOWN + POINTER_DOWN + MOVE | 3 | varies | PARTIALLY_VERIFIED |
| D4: DOWN -> PDOWN -> MOVE -> PUP -> UP | 5 | varies | PARTIALLY_VERIFIED |

### Root Cause of Multi-Touch Drop

The 6-param `MotionEvent.obtain(long, long, int, float, float, int)` creates **single-pointer events** (pointerCount=1). When injected with multi-pointer actions:
- `ACTION_POINTER_DOWN` with pointerId=1 expects 2 pointers, but event has 1
- `ACTION_MOVE` with 2 pointers expects pointerCount=2, but event has 1
- InputDispatcher silently drops events where action/pointerCount is inconsistent

**Fix applied**: `MotionEventFactory.createMultiPointer()` uses reflection on `MotionEvent.PointerProperties` and `MotionEvent.PointerCoords` hidden classes to create proper multi-pointer events via the 14-param `MotionEvent.obtain()` overload.

## Latency Methodology

### What is Measured

**Binder invocation latency** (per-event): `System.nanoTime()` before/after `injectMethod.invoke()`. This measures the Binder IPC round-trip time.

### What is NOT Measured (and Why)

**End-to-end latency** (injection to display): UNAVAILABLE.

- Injection timestamps use `System.nanoTime()` (monotonic, nanosecond)
- Receiver timestamps use `event.eventTimeNanos` (based on `SystemClock.uptimeMillis()`, millisecond)
- These are **different time domains** — subtracting them produces invalid negative values
- True E2E latency requires same-clock-domain measurement or physical oscilloscope

### Latency Claims

| Claim | Status |
|-------|--------|
| Binder IPC latency measurable | VERIFIED |
| Binder IPC < 5ms | UNVERIFIED (needs device measurement) |
| End-to-end input-to-screen latency | UNAVAILABLE (cross-domain) |
| Physical touch-to-display latency | UNVERIFIED (needs physical measurement) |

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
            └─ accepts event into InputDispatcher queue
```

## Hidden APIs Used

| API | Purpose | Risk Level |
|-----|---------|------------|
| `android.os.ServiceManager.getService(String)` | Get system service binder | @hide, reflection required |
| `android.hardware.input.IInputManager$Stub.asInterface(IBinder)` | Get AIDL proxy | @hide, reflection required |
| `android.hardware.input.IInputManager.injectInputEvent()` | Inject events | @hide, via AIDL proxy |
| `android.view.MotionEvent$PointerProperties` | Multi-pointer support | @hide, reflection required |
| `android.view.MotionEvent$PointerCoords` | Multi-pointer support | @hide, reflection required |

## Test Results Summary

| Test | Classification | Binder | Create | Inject | Observe | View |
|------|----------------|--------|--------|--------|---------|------|
| Single Tap | **VERIFIED** | OK | OK | true | observed | observed |
| Swipe | **VERIFIED** | OK | OK | true | observed | observed |
| Multi-touch D1 (DOWN only) | **VERIFIED** | OK | OK | true | observed | observed |
| Multi-touch D2 (DOWN+PDOWN) | **PARTIALLY_VERIFIED** | OK | OK | true | partial | partial |
| Multi-touch D3 (DOWN+PDOWN+MOVE) | **PARTIALLY_VERIFIED** | OK | OK | true | partial | partial |
| Multi-touch D4 (full sequence) | **PARTIALLY_VERIFIED** | OK | OK | true | partial | partial |
| DS4 Controller | **UNTESTED** | - | - | - | - | - |
| E2E Latency | **UNAVAILABLE** | - | - | - | - | - |

## Files

- `IInputManagerBinderHelper.kt` — Binder acquisition and AIDL proxy injection
- `InjectionTestRunner.kt` — 5-stage test with isolated multi-touch sub-tests
- `VisualTouchCanvas.kt` — Visual event observation with UNKNOWN classification
- `PoCActivity.kt` — Diagnostic UI with Binder latency stats
- `MotionEventFactory.java` — MotionEvent creation (6-param and 14-param multi-pointer)
- `LatencyRecorder.kt` — Binder-only latency statistics
