package com.gpmapper.app.poc;

import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

public class MotionEventFactory {

    private static final String TAG = "MEventFactory";
    public static String lastResult = "No attempt yet";

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
            lastResult = "direct MotionEvent.obtain(6-param) SUCCESS";
            Log.i(TAG, lastResult);
            return event;
        } catch (Exception e) {
            lastResult = "direct MotionEvent.obtain FAILED: " + e;
            Log.e(TAG, lastResult, e);
            throw new RuntimeException(lastResult, e);
        }
    }
}
