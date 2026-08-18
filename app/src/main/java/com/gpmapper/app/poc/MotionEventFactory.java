package com.gpmapper.app.poc;

import android.view.MotionEvent;
import java.lang.reflect.Method;

public class MotionEventFactory {

    private static Method sObtainMethod;

    static {
        try {
            for (Method m : MotionEvent.class.getDeclaredMethods()) {
                if ("obtain".equals(m.getName())
                        && java.lang.reflect.Modifier.isStatic(m.modifiers())) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 14) {
                        sObtainMethod = m;
                        sObtainMethod.setAccessible(true);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // Will be reported at runtime
        }
    }

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
    ) throws Exception {
        if (sObtainMethod == null) {
            throw new IllegalStateException("MotionEvent.obtain not found via reflection");
        }
        return (MotionEvent) sObtainMethod.invoke(null,
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
