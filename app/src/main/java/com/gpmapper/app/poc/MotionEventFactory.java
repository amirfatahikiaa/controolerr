package com.gpmapper.app.poc;

import android.view.MotionEvent;

public class MotionEventFactory {

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
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = pointerId;
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = pressure;
        pc.size = size;

        MotionEvent.PointerProperties[] ppArray = {pp};
        MotionEvent.PointerCoords[] pcArray = {pc};

        return MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                ppArray,
                pcArray,
                0,
                source,
                0,
                0
        );
    }
}
