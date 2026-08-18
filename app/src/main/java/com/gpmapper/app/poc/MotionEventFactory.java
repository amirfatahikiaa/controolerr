package com.gpmapper.app.poc;

import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

public class MotionEventFactory {

    private static final String TAG = "MEventFactory";

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
            Log.i(TAG, "direct MotionEvent.obtain(6-param) SUCCESS");
            return event;
        } catch (Exception e) {
            Log.e(TAG, "direct MotionEvent.obtain FAILED: " + e, e);
            throw new RuntimeException("MotionEvent.obtain failed: " + e.getMessage(), e);
        }
    }
}
