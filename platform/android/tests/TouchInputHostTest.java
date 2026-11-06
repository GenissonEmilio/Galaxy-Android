// SPDX-License-Identifier: GPL-3.0-only
package dev.nebula.galaxy;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

/** Runs the production ownership/geometry code with only persistence replaced by an in-memory store. */
public final class TouchInputHostTest {
    private static int assertions;
    private static final int A = 1, B = 2, Z = 4, SHAKE = 16;
    private static final int UP = 1 << 10, DOWN = 1 << 11, LEFT = 1 << 12, RIGHT = 1 << 13;
    private static void check(boolean value, String message) {
        ++assertions;
        if (!value) throw new AssertionError(message);
    }
    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 0.0001f, message + ": " + actual);
    }

    public static void main(String[] args) {
        TouchInputModel m = new TouchInputModel();
        // Three/four fingers: movement, aiming and independent actions cannot steal each other's roles.
        check(m.claim(7, TouchInputModel.STICK, 100, 250), "stick down");
        check(m.claim(12, TouchInputModel.POINTER, 700, 200), "pointer down");
        check(m.claim(2, 0, 850, 350), "jump finger down");
        check(m.claim(31, 3, 780, 350), "crouch finger down");
        m.setButtons(2, A); m.setButtons(31, Z);
        check(m.held() == (A | Z), "simultaneous A+Z");
        check(!m.claim(7, TouchInputModel.POINTER, 700, 200), "moving across screen cannot reassign stick");
        check(!m.claim(4, TouchInputModel.POINTER, 400, 150), "second pointer cannot steal first");
        check(!m.claim(4, TouchInputModel.STICK, 120, 250), "second stick cannot steal first");
        check(m.release(2) == 0 && m.held() == Z, "jump release preserves crouch");
        check(m.owner(TouchInputModel.STICK) == 7 && m.owner(TouchInputModel.POINTER) == 12, "sparse pointer ids stable");
        m.release(12);
        check(m.role(4) == TouchInputModel.NONE, "ignored finger never inherits released pointer");
        check(m.claim(4, TouchInputModel.POINTER, 500, 100), "new down may own pointer");
        check(m.claim(5, 1, 850, 300), "shoot finger"); m.setButtons(5, B);
        check(m.claim(6, 2, 820, 270), "spin finger"); m.setButtons(6, SHAKE);
        check(m.held() == (B | Z | SHAKE), "shoot and spin preserve other controls");
        m.clear();
        check(m.held() == 0 && m.owner(TouchInputModel.STICK) == -1 && m.owner(TouchInputModel.POINTER) == -1,
                "cancel clears every finger, even if omitted from final event");
        m.setButtons(5, A);
        check(m.held() == 0, "late move after cancel cannot press a control");
        check(!m.claim(-1, 0, 0, 0) && !m.claim(32, 0, 0, 0), "invalid ids rejected");
        check(!m.claim(0, TouchInputModel.NONE, 0, 0) && !m.claim(0, 0, Float.NaN, 0), "invalid role/coordinate rejected");
        check(m.claim(0, TouchInputModel.CAMERA, 50, 100), "camera claims its own role");
        check(m.claim(1, TouchInputModel.POINTER, 500, 300), "existing/new pointing independent of camera");
        check(m.claim(3, TouchInputModel.MODE, 800, 40), "mode toggle is not pointer");
        m.motion(3, 806, 44);
        check(m.isTap(3, 24), "short movement remains a mode tap");
        m.motion(3, 850, 40); m.motion(3, 800, 40);
        check(!m.isTap(3, 24), "dragging out and back cannot toggle mode");
        check(!m.claim(8, TouchInputModel.CAMERA, 100, 100), "one camera drag owner");
        near(m.dx(0, 90), 40, "camera delta x"); near(m.dy(0, 80), -20, "camera delta y");

        float[] stick = new float[2];
        TouchInputModel.stick(3, 4, 100, 0.06f, stick);
        near(stick[0], 0, "radial dead zone x"); near(stick[1], 0, "radial dead zone y");
        TouchInputModel.stick(53, 0, 100, 0.06f, stick);
        near(stick[0], 0.5f, "travel rescaled after dead zone");
        TouchInputModel.stick(300, -400, 100, 0.06f, stick);
        near(stick[0], 0.6f, "diagonal x clamped"); near(stick[1], -0.8f, "diagonal y clamped");
        near((float)Math.hypot(stick[0], stick[1]), 1, "no diagonal speed boost");
        TouchInputModel.stick(Float.NaN, 0, 100, 0.06f, stick);
        near(stick[0], 0, "invalid motion stays neutral");
        TouchInputModel.stick(100, 0, 0, 0.06f, stick);
        near(stick[0], 0, "unmeasured stick stays neutral");
        check(TouchInputModel.cameraDirection(10, 10, 22) == 0, "camera drag threshold");
        check(TouchInputModel.cameraDirection(30, 3, 22) == LEFT, "right look drag uses Wii LEFT, confirmed in device gameplay");
        check(TouchInputModel.cameraDirection(-30, 3, 22) == RIGHT, "left look drag uses Wii RIGHT");
        check(TouchInputModel.cameraDirection(3, -30, 22) == UP, "up uses original look command");
        check(TouchInputModel.cameraDirection(3, 30, 22) == DOWN, "down original command");
        check(TouchInputModel.cameraDirection(30, 30, 22) == LEFT, "camera uses one axis at a time");
        check(TouchInputModel.dpad(0, 0, 50) == 0, "D-pad centre is neutral");
        check(TouchInputModel.dpad(40, 0, 50) == RIGHT, "direct D-pad right remains Wii RIGHT");
        check(TouchInputModel.dpad(-40, 0, 50) == LEFT, "direct D-pad left remains Wii LEFT");
        check(TouchInputModel.dpad(40, -40, 50) == (UP | RIGHT), "D-pad diagonal retains both arms");
        check(TouchInputModel.dpad(-40, 40, 50) == (DOWN | LEFT), "opposite D-pad diagonal");
        check(TouchInputModel.dpad(100, 0, 50) == 0, "D-pad cannot stick after leaving control");
        check(TouchInputModel.circle(30, 40, 0, 0, 50), "inclusive circular hit edge");
        check(!TouchInputModel.circle(51, 0, 0, 0, 50), "outside hit rejected");
        near(TouchInputModel.clampCenter(-50, 30, 20, 300), 50, "safe inset left");
        near(TouchInputModel.clampCenter(500, 30, 20, 300), 270, "safe inset right");
        near(TouchInputModel.clampCenter(50, 70, 20, 100), 60, "tiny window remains centred");
        float actionY = TouchLayouts.galaxyActionY(2772, 1280, 936, 130, 3.25f, 3);
        check(actionY + 130 * 1.25f < 960, "Galaxy preset enlarged A hit area clears lower-right counters");
        check(actionY < 936 - 110, "Galaxy preset lifts actions away from counters");
        near(TouchLayouts.galaxyActionY(1280, 2772, 2500, 130, 3.25f, 3), 2500, "portrait default remains unchanged");
        check(TouchLayouts.galaxyActionY(2772, 1280, 1096, 130, 3.25f, 3) + 130 * 1.25f < 960,
                "HUD clearance does not depend on system gesture insets");

        FakeContext context = new FakeContext();
        TouchLayouts layouts = new TouchLayouts(context);
        TouchLayouts.CameraSettings camera = layouts.loadCamera();
        near(camera.sensitivity, 1, "camera starts at original threshold");
        near(camera.thresholdDp(), 22, "default camera step distance");
        layouts.saveCamera(new TouchLayouts.CameraSettings(2, true, true));
        camera = new TouchLayouts(context).loadCamera();
        near(camera.thresholdDp(), 11, "higher sensitivity shortens drag");
        check(camera.invertX && camera.invertY, "camera inversions persist independently of layout");
        check(TouchInputModel.cameraDirection(10, 0, camera.thresholdDp(), true, true) == 0, "sensitivity still keeps dead zone");
        check(TouchInputModel.cameraDirection(12, 0, camera.thresholdDp(), true, false) == RIGHT, "camera X inversion applies beyond look-drag base");
        check(TouchInputModel.cameraDirection(0, -12, camera.thresholdDp(), false, true) == DOWN, "camera Y inversion");
        check(TouchInputModel.cameraDirection(12, 0, camera.thresholdDp(), false, true) == LEFT, "Y inversion leaves X unchanged");
        near(new TouchLayouts.CameraSettings(0.01f, false, false).thresholdDp(), 22 / 0.35f, "minimum sensitivity bounded");
        near(new TouchLayouts.CameraSettings(30, false, false).sensitivity, 2, "maximum sensitivity bounded");
        near(TouchLayouts.CameraSettings.decode("NaN;false;true").sensitivity, 1, "nonfinite sensitivity falls back safely");
        near(TouchLayouts.CameraSettings.decode("oops;true;true").sensitivity, 1, "corrupt camera options fall back");
        check(!TouchLayouts.CameraSettings.decode("2;yes;true").invertX, "malformed boolean cannot enable inversion");
        context.store.values.put("separators", ";;;");
        check(!layouts.load("separators").moved(0), "empty corrupt layout falls back safely");
        context.store.values.put("corrupt", ";0;1,NaN,Infinity;2,0.2;3,0.4,0.6");
        TouchLayouts.Layout loaded = layouts.load("corrupt");
        check(!loaded.moved(1) && loaded.moved(3), "malformed saved fields do not poison valid positions");
        context.store.values.put("range", "30;0,-1,2");
        loaded = layouts.load("range");
        near(loaded.size, 2, "persisted scale bounded"); near(loaded.x[0], 0, "position bounded left");
        near(loaded.y[0], 1, "position bounded bottom");
        layouts.save("roundtrip", loaded);
        check(layouts.load("roundtrip").moved(0), "layout survives persistence round trip");
        layouts.save(null, loaded);
        check(!context.store.values.containsKey(null), "pre-measure settings do not persist null key");
        check(TouchLayouts.key(1920, 1080, 3).equals(TouchLayouts.key(1921, 1081, 3)), "same screen bucket");
        check(!TouchLayouts.key(1920, 1080, 3).equals(TouchLayouts.key(1080, 1920, 3)), "orientation layouts separate");
        System.out.println("PASS: " + assertions + " touch ownership, camera, geometry and layout assertions");
    }
    static final class FakeContext extends Context {
        final Store store = new Store();
        public SharedPreferences getSharedPreferences(String name, int mode) { return store; }
    }
    static final class Store implements SharedPreferences, SharedPreferences.Editor {
        final Map<String, String> values = new HashMap<>();
        public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
        public Editor edit() { return this; }
        public Editor putString(String key, String value) { values.put(key, value); return this; }
        public Editor remove(String key) { values.remove(key); return this; }
        public void apply() { }
    }
}
