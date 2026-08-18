package com.gpmapper.app.poc;

import android.util.Log;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class MotionEventFactory {

    private static final String TAG = "MEventFactory";
    private static Method sObtainMethod;
    private static String sResolvedSignature;
    private static String sDiagnostics = "Not initialized";

    public static String getDiagnostics() { return sDiagnostics; }

    static {
        StringBuilder diag = new StringBuilder();
        try {
            // getDeclaredMethods() can be blocked by hidden API policy on API 28+.
            // getMethods() returns all public methods and is less restricted.
            Method[] declared = MotionEvent.class.getDeclaredMethods();
            Method[] pub = MotionEvent.class.getMethods();

            diag.append("getDeclaredMethods: ").append(declared.length).append("\n");
            diag.append("getMethods: ").append(pub.length).append("\n\n");

            // Log ALL obtain overloads from getDeclaredMethods
            diag.append("--- getDeclaredMethods obtain ---\n");
            int obtainCount = 0;
            for (Method m : declared) {
                if ("obtain".equals(m.getName()) && Modifier.isStatic(m.getModifiers())) {
                    obtainCount++;
                    diag.append(sig(m)).append("\n");
                    Log.i(TAG, "getDeclaredMethods: " + sig(m));
                }
            }
            diag.append("count: ").append(obtainCount).append("\n\n");

            // Log ALL obtain overloads from getMethods
            diag.append("--- getMethods obtain ---\n");
            obtainCount = 0;
            for (Method m : pub) {
                if ("obtain".equals(m.getName()) && Modifier.isStatic(m.getModifiers())) {
                    obtainCount++;
                    diag.append(sig(m)).append("\n");
                    Log.i(TAG, "getMethods: " + sig(m));
                }
            }
            diag.append("count: ").append(obtainCount).append("\n\n");

            // Try to match using getMethods first (public, less restricted)
            sObtainMethod = matchObtain(pub);
            if (sObtainMethod == null) {
                sObtainMethod = matchObtain(declared);
            }

            if (sObtainMethod != null) {
                sResolvedSignature = sig(sObtainMethod);
                diag.append("MATCHED: ").append(sResolvedSignature).append("\n");
                Log.i(TAG, "MATCHED: " + sResolvedSignature);
            } else {
                diag.append("NO MATCH FOUND\n");
                diag.append("\n--- All public methods on MotionEvent ---\n");
                for (Method m : pub) {
                    diag.append(Modifier.toString(m.getModifiers()))
                        .append(" ").append(m.getReturnType().getSimpleName())
                        .append(" ").append(m.getName()).append("(");
                    Class<?>[] p = m.getParameterTypes();
                    for (int i = 0; i < p.length; i++) {
                        if (i > 0) diag.append(", ");
                        diag.append(p[i].getSimpleName());
                    }
                    diag.append(")\n");
                }
                Log.e(TAG, "NO matching obtain overload found!");
            }
        } catch (Exception e) {
            diag.append("EXCEPTION: ").append(e).append("\n");
            Log.e(TAG, "Failed to enumerate methods", e);
        }
        sDiagnostics = diag.toString();
    }

    private static Method matchObtain(Method[] methods) {
        for (Method m : methods) {
            if (!"obtain".equals(m.getName())) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();

            // 10-param: (long, long, int, int, PointerProperties[], PointerCoords[], int, int, int, int)
            if (p.length == 10
                    && p[0] == long.class && p[1] == long.class
                    && p[2] == int.class && p[3] == int.class
                    && p[4] == MotionEvent.PointerProperties[].class
                    && p[5] == MotionEvent.PointerCoords[].class
                    && p[6] == int.class && p[7] == int.class
                    && p[8] == int.class && p[9] == int.class) {
                m.setAccessible(true);
                return m;
            }

            // 14-param: (long, long, int, float, float, float, float, int, float, float, int, int, int, int)
            if (p.length == 14
                    && p[0] == long.class && p[1] == long.class
                    && p[2] == int.class && p[3] == float.class
                    && p[4] == float.class && p[5] == float.class
                    && p[6] == float.class && p[7] == int.class
                    && p[8] == float.class && p[9] == float.class
                    && p[10] == int.class && p[11] == int.class
                    && p[12] == int.class && p[13] == int.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append("(");
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(p[i].getName());
        }
        sb.append(") [").append(p.length).append(" params]");
        return sb.toString();
    }

    public static MotionEvent create(
            int action, float x, float y,
            long downTime, long eventTime,
            int pointerId, float pressure, float size,
            int source
    ) throws Exception {
        if (sObtainMethod == null) {
            throw new IllegalStateException(
                    "MotionEvent.obtain not found.\n" + sDiagnostics);
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

            return (MotionEvent) sObtainMethod.invoke(null,
                    downTime, eventTime, action, 1,
                    new MotionEvent.PointerProperties[]{pp},
                    new MotionEvent.PointerCoords[]{pc},
                    0, source, 0, 0);
        } else if (paramTypes.length == 14) {
            return (MotionEvent) sObtainMethod.invoke(null,
                    downTime, eventTime, action,
                    x, y, pressure, size,
                    0, 1.0f, 1.0f,
                    0, 0, source, 0);
        } else {
            throw new IllegalStateException(
                    "Unexpected param count " + paramTypes.length + "\n" + sDiagnostics);
        }
    }
}
