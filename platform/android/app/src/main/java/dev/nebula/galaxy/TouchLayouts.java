// SPDX-License-Identifier: GPL-3.0-only
package dev.nebula.galaxy;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;

/**
 * The on-screen controls' arrangement, kept apart for each shape of screen: a phone upright and on its side, a foldable
 * folded and open, a window of any size. Each has its own overall size and each control's place.
 */
final class TouchLayouts {
    static final int ITEMS = 12;  // TouchControlsView's ten buttons, the stick and the D-pad
    private static final String CAMERA_KEY = "camera_gestures";

    static final class CameraSettings {
        float sensitivity = 1;
        boolean invertX, invertY;

        CameraSettings() { }
        CameraSettings(float sensitivity, boolean invertX, boolean invertY) {
            this.sensitivity = boundedSensitivity(sensitivity);
            this.invertX = invertX;
            this.invertY = invertY;
        }
        static float boundedSensitivity(float value) {
            return Float.isFinite(value) ? Math.max(0.35f, Math.min(2, value)) : 1;
        }
        static CameraSettings decode(String text) {
            if (text == null) return new CameraSettings();
            String[] fields = text.split(";");
            if (fields.length != 3 || !(fields[1].equals("true") || fields[1].equals("false"))
                    || !(fields[2].equals("true") || fields[2].equals("false"))) return new CameraSettings();
            try {
                return new CameraSettings(Float.parseFloat(fields[0]), Boolean.parseBoolean(fields[1]), Boolean.parseBoolean(fields[2]));
            } catch (NumberFormatException ignored) {
                return new CameraSettings();
            }
        }
        String encode() { return boundedSensitivity(sensitivity) + ";" + invertX + ";" + invertY; }
        float thresholdDp() { return 22 / boundedSensitivity(sensitivity); }
    }

    /** One shape of screen's arrangement. NaN: as the game lays it out. */
    static final class Layout {
        float size = Float.NaN;
        final float[] x = new float[ITEMS];  // the control's centre, as a fraction of the screen
        final float[] y = new float[ITEMS];

        Layout() {
            clear();
        }

        void clear() {
            size = Float.NaN;
            Arrays.fill(x, Float.NaN);
            Arrays.fill(y, Float.NaN);
        }

        boolean moved(int item) {
            return Float.isFinite(x[item]) && Float.isFinite(y[item]);
        }
    }

    private final SharedPreferences prefs;

    TouchLayouts(Context context) {
        prefs = context.getSharedPreferences("touch_layouts", Context.MODE_PRIVATE);
    }

    CameraSettings loadCamera() { return CameraSettings.decode(prefs.getString(CAMERA_KEY, null)); }

    void saveCamera(CameraSettings camera) { prefs.edit().putString(CAMERA_KEY, camera.encode()).apply(); }

    /** Galaxy's landscape HUD owns the upper/lower right corners. Only untouched defaults use this preset. */
    static float galaxyActionY(int width, int height, float previousY, float radius, float unit, float density) {
        if (width <= height) return previousY;
        // Lift the thumb arc about 36 layout units; its enlarged DOWN hit area must also clear the counters.
        return Math.min(previousY - 36 * unit, height * 0.75f - radius * 1.25f - 8 * density);
    }

    /** The screen's shape in steps of 16 dp, so a window a few pixels different is the same shape. */
    static String key(int width, int height, float density) {
        if (!Float.isFinite(density) || density <= 0) density = 1;
        final int w = Math.round(width / density / 16.0f) * 16;
        final int h = Math.round(height / density / 16.0f) * 16;
        return w + "x" + h;
    }

    Layout load(String key) {
        final Layout layout = new Layout();
        final String text = prefs.getString(key, null);
        if (text == null) {
            return layout;
        }
        final String[] parts = text.split(";");
        if (parts.length == 0) return layout;
        try {
            if (!parts[0].isEmpty()) {
                layout.size = Float.parseFloat(parts[0]);
                if (!Float.isFinite(layout.size)) layout.size = Float.NaN;
                else layout.size = Math.max(0.4f, Math.min(2.0f, layout.size));
            }
            for (int i = 1; i < parts.length; ++i) {
                final String[] item = parts[i].split(",");
                if (item.length != 3) continue;
                final int index = Integer.parseInt(item[0]);
                if (index >= 0 && index < ITEMS) {
                    final float x = Float.parseFloat(item[1]);
                    final float y = Float.parseFloat(item[2]);
                    if (Float.isFinite(x) && Float.isFinite(y)) {
                        layout.x[index] = Math.max(0, Math.min(1, x));
                        layout.y[index] = Math.max(0, Math.min(1, y));
                    }
                }
            }
        } catch (NumberFormatException e) {
            layout.clear();
        }
        return layout;
    }

    void save(String key, Layout layout) {
        if (key == null) return; // Settings can arrive before the first measured frame.
        final StringBuilder text = new StringBuilder();
        if (!Float.isNaN(layout.size)) {
            text.append(layout.size);
        }
        for (int i = 0; i < ITEMS; ++i) {
            if (layout.moved(i)) {
                text.append(';').append(i).append(',').append(layout.x[i]).append(',').append(layout.y[i]);
            }
        }
        prefs.edit().putString(key, text.toString()).apply();
    }

    void remove(String key) {
        prefs.edit().remove(key).apply();
    }
}

/** Android-independent touch ownership and geometry, shared with the host-side Java tests. */
final class TouchInputModel {
    static final int COUNT = 32;
    static final int NONE = -100, STICK = -1, POINTER = -2, DPAD = -3, CAMERA = -4, MODE = -5;
    private final int[] roles = new int[COUNT];
    private final int[] buttons = new int[COUNT];
    private final float[] startX = new float[COUNT], startY = new float[COUNT];
    private final float[] travelSquared = new float[COUNT];

    TouchInputModel() { clear(); }

    int role(int id) { return valid(id) ? roles[id] : NONE; }
    int owner(int role) {
        for (int i = 0; i < COUNT; ++i) if (roles[i] == role) return i;
        return -1;
    }
    boolean claim(int id, int role, float x, float y) {
        if (!valid(id) || role < MODE || roles[id] != NONE || !Float.isFinite(x) || !Float.isFinite(y)) return false;
        if ((role == STICK || role == POINTER || role == CAMERA || role == MODE) && owner(role) >= 0) return false;
        roles[id] = role;
        startX[id] = x;
        startY[id] = y;
        travelSquared[id] = 0;
        return true;
    }
    int release(int id) {
        final int role = role(id);
        if (valid(id)) { roles[id] = NONE; buttons[id] = 0; }
        return role;
    }
    void clear() { Arrays.fill(roles, NONE); Arrays.fill(buttons, 0); }
    void setButtons(int id, int bits) { if (valid(id) && roles[id] != NONE) buttons[id] = bits; }
    int held() { int bits = 0; for (int b : buttons) bits |= b; return bits; }
    float dx(int id, float x) { return valid(id) ? x - startX[id] : 0; }
    float dy(int id, float y) { return valid(id) ? y - startY[id] : 0; }
    void motion(int id, float x, float y) {
        if (role(id) == NONE) return;
        final float dx = dx(id, x), dy = dy(id, y);
        travelSquared[id] = Math.max(travelSquared[id], dx * dx + dy * dy);
    }
    boolean isTap(int id, float slop) { return valid(id) && travelSquared[id] < slop * slop; }
    static boolean valid(int id) { return id >= 0 && id < COUNT; }
    static boolean circle(float x, float y, float cx, float cy, float radius) {
        return Float.isFinite(radius) && radius > 0 && Math.hypot(x - cx, y - cy) <= radius;
    }
    static float clampCenter(float at, float radius, float low, float high) {
        if (high - low <= 2 * radius) return (low + high) * 0.5f;
        return Math.max(low + radius, Math.min(high - radius, at));
    }
    /** Radial dead zone; no diagonal speed boost and no allocations on motion. */
    static void stick(float dx, float dy, float radius, float deadZone, float[] out) {
        out[0] = out[1] = 0;
        if (!(radius > 0) || !Float.isFinite(radius) || !Float.isFinite(dx) || !Float.isFinite(dy)) return;
        deadZone = Math.max(0, Math.min(0.99f, deadZone));
        final float length = (float) Math.hypot(dx, dy) / radius;
        if (length <= deadZone) return;
        final float magnitude = (Math.min(1, length) - deadZone) / (1 - deadZone);
        out[0] = dx / (radius * length) * magnitude;
        out[1] = dy / (radius * length) * magnitude;
    }
    /**
     * Screen-space look drag to the original Wii D-pad. Device gameplay confirmed that Wii RIGHT moves the road
     * right on screen (looks left), so a rightward look gesture must send Wii LEFT. User inversion applies on top.
     * This is a camera step, not analog orbit; the direct D-pad retains its original mapping.
     */
    static int cameraDirection(float dx, float dy, float threshold) {
        return cameraDirection(dx, dy, threshold, false, false);
    }
    static int cameraDirection(float dx, float dy, float threshold, boolean invertX, boolean invertY) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || Math.hypot(dx, dy) < threshold) return 0;
        if (invertX) dx = -dx;
        if (invertY) dy = -dy;
        if (Math.abs(dx) >= Math.abs(dy)) return dx < 0 ? 1 << 13 : 1 << 12;
        return dy < 0 ? 1 << 10 : 1 << 11;
    }
    static int dpad(float dx, float dy, float radius) {
        final double distance = Math.hypot(dx, dy);
        if (!(radius > 0) || !Double.isFinite(distance) || distance < radius * 0.22f || distance > radius * 1.35f) return 0;
        double a = Math.toDegrees(Math.atan2(-dy, dx));
        if (a < 0) a += 360;
        int bits = 0;
        if (a < 67.5 || a >= 292.5) bits |= 1 << 13;
        if (a >= 22.5 && a < 157.5) bits |= 1 << 10;
        if (a >= 112.5 && a < 247.5) bits |= 1 << 12;
        if (a >= 202.5 && a < 337.5) bits |= 1 << 11;
        return bits;
    }
}
