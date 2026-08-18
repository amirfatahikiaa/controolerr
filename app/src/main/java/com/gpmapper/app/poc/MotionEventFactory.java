package com.gpmapper.app.poc;

import android.view.MotionEvent;

public class MotionEventFactory {

    @SuppressWarnings("deprecation")
    public static MotionEvent create(
            int action,
            float x,
            float y,
            long downTime,
            long eventTime,
            int pointerId,
            float pressure,
            float size,
            int source
    ) {
        return MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                x,
                y,
                pressure,
                size,
                0,
                1.0f,
                1.0f,
                0,
                0,
                source,
                0
        );
    }
}
