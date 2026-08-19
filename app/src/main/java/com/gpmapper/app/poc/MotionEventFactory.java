package com.gpmapper.app.poc;

import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class MotionEventFactory {

    private static final String TAG = "MEventFactory";
    public static String lastResult = "No attempt yet";

    private static final int FLAG_INJECTED = 0x01000000;

    public static MotionEvent create(
            int action, float x, float y,
            long downTime, long eventTime,
            int pointerId, float pressure, float size,
            int source
    ) {
        try {
            MotionEvent event = MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    x,
                    y,
                    0
            );
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);

            lastResult = "SUCCESS";
            Log.i(TAG, lastResult);
            return event;
        } catch (Exception e) {
            lastResult = "FAILED: " + e;
            Log.e(TAG, lastResult, e);
            throw new RuntimeException(lastResult, e);
        }
    }

    public static MotionEvent createMultiPointer(
            int action, float x, float y,
            long downTime, long eventTime,
            int pointerId, float pressure, float size,
            int source, int pointerCount
    ) {
        try {
            Class<?> ppClass = Class.forName("android.view.MotionEvent$PointerProperties");
            Class<?> pcClass = Class.forName("android.view.MotionEvent$PointerCoords");
            Constructor<?> ppCtor = ppClass.getDeclaredConstructor();
            Constructor<?> pcCtor = pcClass.getDeclaredConstructor();
            ppCtor.setAccessible(true);
            pcCtor.setAccessible(true);

            Class<?> ppArrayClass = Array.newInstance(ppClass, 0).getClass();
            Class<?> pcArrayClass = Array.newInstance(pcClass, 0).getClass();
            Object ppArray = Array.newInstance(ppClass, pointerCount);
            Object pcArray = Array.newInstance(pcClass, pointerCount);

            Method idSetter = ppClass.getMethod("setId", int.class);
            Method xSetter = pcClass.getMethod("setX", float.class);
            Method ySetter = pcClass.getMethod("setY", float.class);
            Method pSetter = pcClass.getMethod("setPressure", float.class);
            Method sSetter = pcClass.getMethod("setSize", float.class);

            for (int i = 0; i < pointerCount; i++) {
                Object pp = ppCtor.newInstance();
                Object pc = pcCtor.newInstance();

                idSetter.invoke(pp, i);

                if (i == pointerId || (pointerId == 0 && i == 0)) {
                    xSetter.invoke(pc, x);
                    ySetter.invoke(pc, y);
                    pSetter.invoke(pc, pressure);
                    sSetter.invoke(pc, size);
                } else {
                    xSetter.invoke(pc, 0f);
                    ySetter.invoke(pc, 0f);
                    pSetter.invoke(pc, 0f);
                    sSetter.invoke(pc, 0f);
                }

                Array.set(ppArray, i, pp);
                Array.set(pcArray, i, pc);
            }

            Method obtainMethod = MotionEvent.class.getMethod(
                    "obtain",
                    long.class, long.class, int.class, int.class,
                    ppArrayClass, pcArrayClass,
                    int.class, int.class, float.class, float.class,
                    int.class, int.class, int.class, int.class
            );

            MotionEvent event = (MotionEvent) obtainMethod.invoke(null,
                    downTime, eventTime, action, pointerCount,
                    ppArray, pcArray,
                    0, 0, 1.0f, 1.0f,
                    0, 0, source, 0
            );

            lastResult = "SUCCESS_MULTI";
            Log.i(TAG, lastResult + " ptrCount=" + pointerCount + " action=0x" + Integer.toHexString(action));
            return event;
        } catch (Exception e) {
            lastResult = "FAILED_MULTI: " + e;
            Log.e(TAG, lastResult, e);
            throw new RuntimeException(lastResult, e);
        }
    }

    public static String diagnoseEvent(MotionEvent event, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- MotionEvent Diagnostics: ").append(label).append(" ---\n");
        sb.append("action=0x").append(Integer.toHexString(event.getAction()))
          .append(" actionMasked=").append(event.getActionMasked()).append("\n");
        sb.append("actionIndex=").append(event.getActionIndex()).append("\n");
        sb.append("pointerCount=").append(event.getPointerCount()).append("\n");
        sb.append("downTime=").append(event.getDownTime()).append("\n");
        sb.append("eventTime=").append(event.getEventTime()).append("\n");
        sb.append("eventTimeNanos=").append(event.getEventTimeNanos()).append("\n");
        sb.append("deviceId=").append(event.getDeviceId()).append("\n");
        sb.append("source=0x").append(Integer.toHexString(event.getSource())).append("\n");
        sb.append("flags=0x").append(Integer.toHexString(event.getFlags())).append("\n");
        sb.append("FLAG_INJECTED=").append((event.getFlags() & FLAG_INJECTED) != 0)
          .append(" (cannot set via public SDK — hidden API setFlags())\n");

        for (int i = 0; i < event.getPointerCount(); i++) {
            sb.append("pointer[").append(i).append("] id=")
              .append(event.getPointerId(i))
              .append(" x=").append(event.getX(i))
              .append(" y=").append(event.getY(i))
              .append(" pressure=").append(event.getPressure(i))
              .append(" size=").append(event.getSize(i))
              .append("\n");
        }

        String result = sb.toString();
        Log.i(TAG, result);
        return result;
    }
}
