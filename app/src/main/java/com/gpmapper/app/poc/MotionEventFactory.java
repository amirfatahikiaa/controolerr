package com.gpmapper.app.poc;

import android.util.Log;
import android.view.MotionEvent;
import java.lang.reflect.Method;

public class MotionEventFactory {

    private static final String TAG = "MEventFactory";
    private static Method sObtainMethod;
    private static String sResolvedSignature;

    static {
        try {
            Method[] methods = MotionEvent.class.getDeclaredMethods();
            Log.i(TAG, "MotionEvent has " + methods.length + " declared methods");

            for (Method m : methods) {
                if (!"obtain".equals(m.getName())) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;

                Class<?>[] p = m.getParameterTypes();
                StringBuilder sb = new StringBuilder("obtain(");
                for (int i = 0; i < p.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(p[i].getName());
                }
                sb.append(") [").append(p.length).append(" params]");
                Log.i(TAG, "  Found overload: " + sb.toString());

                // Match the 10-param overload:
                // obtain(long, long, int, int, PointerProperties[], PointerCoords[], int, int, int, int)
                if (p.length == 10
                        && p[0] == long.class
                        && p[1] == long.class
                        && p[2] == int.class
                        && p[3] == int.class
                        && p[4] == MotionEvent.PointerProperties[].class
                        && p[5] == MotionEvent.PointerCoords[].class
                        && p[6] == int.class
                        && p[7] == int.class
                        && p[8] == int.class
                        && p[9] == int.class) {
                    sObtainMethod = m;
                    sObtainMethod.setAccessible(true);
                    sResolvedSignature = sb.toString();
                    Log.i(TAG, "MATCHED 10-param overload: " + sResolvedSignature);
                }

                // Also try to match the 14-param overload:
                // obtain(long, long, int, float, float, float, float, int, float, float, int, int, int, int)
                if (sObtainMethod == null && p.length == 14
                        && p[0] == long.class
                        && p[1] == long.class
                        && p[2] == int.class
                        && p[3] == float.class
                        && p[4] == float.class
                        && p[5] == float.class
                        && p[6] == float.class
                        && p[7] == int.class
                        && p[8] == float.class
                        && p[9] == float.class
                        && p[10] == int.class
                        && p[11] == int.class
                        && p[12] == int.class
                        && p[13] == int.class) {
                    sObtainMethod = m;
                    sObtainMethod.setAccessible(true);
                    sResolvedSignature = sb.toString();
                    Log.i(TAG, "MATCHED 14-param overload: " + sResolvedSignature);
                }
            }

            if (sObtainMethod == null) {
                Log.e(TAG, "NO matching MotionEvent.obtain overload found!");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to enumerate MotionEvent.obtain overloads", e);
        }
    }

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
            throw new IllegalStateException(
                    "MotionEvent.obtain not found. Resolved: " + sResolvedSignature);
        }

        Class<?>[] paramTypes = sObtainMethod.getParameterTypes();

        if (paramTypes.length == 10) {
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = pointerId;
            pp.toolType = MotionEvent.TOOL_TYPE_FINGER;

            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            pc.x = x;
            pc.y = y;
            pc.pressure = pressure;
            pc.size = size;

            MotionEvent.PointerProperties[] ppArr = new MotionEvent.PointerProperties[]{pp};
            MotionEvent.PointerCoords[] pcArr = new MotionEvent.PointerCoords[]{pc};

            Log.i(TAG, "Invoking 10-param: downTime=" + downTime + " eventTime=" + eventTime
                    + " action=" + action + " pointerCount=1 source=" + source);

            return (MotionEvent) sObtainMethod.invoke(null,
                    downTime, eventTime, action, 1,
                    ppArr, pcArr,
                    0, source, 0, 0);
        } else if (paramTypes.length == 14) {
            Log.i(TAG, "Invoking 14-param: downTime=" + downTime + " eventTime=" + eventTime
                    + " action=" + action + " x=" + x + " y=" + y + " source=" + source);

            return (MotionEvent) sObtainMethod.invoke(null,
                    downTime, eventTime, action,
                    x, y, pressure, size,
                    0, 1.0f, 1.0f,
                    0, 0, source, 0);
        } else {
            throw new IllegalStateException(
                    "Unexpected obtain param count: " + paramTypes.length);
        }
    }
}
