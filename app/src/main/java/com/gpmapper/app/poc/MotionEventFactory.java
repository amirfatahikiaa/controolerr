package com.gpmapper.app.poc;

import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

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
