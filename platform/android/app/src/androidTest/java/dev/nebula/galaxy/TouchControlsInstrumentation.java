// SPDX-License-Identifier: GPL-3.0-only
package dev.nebula.galaxy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real Android View/MotionEvent regressions, with a listener replacing JNI. No game process, assets or native
 * libraries are loaded. Run with am instrument -w <test package>/dev.nebula.galaxy.TouchControlsInstrumentation.
 * The framework Instrumentation runner is deliberately self-contained: no AndroidX or JUnit dependency.
 */
public final class TouchControlsInstrumentation extends Instrumentation {
    private static final int CASES = 7;
    private int cases, checks;
    private String currentCase;
    private TouchControlsView view;
    private RecordingListener listener;
    private long eventTime, downTime;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                runCase("simultaneousSparseFingers", this::simultaneousSparseFingers);
                runCase("crossingNeverStealsRoles", this::crossingNeverStealsRoles);
                runCase("cancelFocusAndResizeRelease", this::cancelFocusAndResizeRelease);
                runCase("cameraToggleAndOriginalDpad", this::cameraToggleAndOriginalDpad);
                runCase("cameraSettingsPersistAndLeavePointerUnchanged", this::cameraSettingsPersistAndLeavePointerUnchanged);
                runCase("menuPointerAndHome", this::menuPointerAndHome);
                runCase("galaxyPresetClearsHudAndPreservesCustom", this::galaxyPresetClearsHudAndPreservesCustom);
            } catch (Throwable error) {
                failure[0] = error;
            } finally {
                try {
                    if (view != null) view.cancelTouches();
                } catch (Throwable cleanupError) {
                    if (failure[0] == null) failure[0] = cleanupError;
                }
            }
        });
        final Bundle result = new Bundle();
        result.putInt("numtests", CASES);
        result.putInt("checks", checks);
        if (failure[0] == null) {
            result.putString("stream", "\nOK (" + cases + " Android touch tests, " + checks + " checks)\n");
            finish(Activity.RESULT_OK, result);
        } else {
            final String stack = Log.getStackTraceString(failure[0]);
            result.putString("stream", "\nFAILED " + currentCase + "\n" + stack);
            result.putString("shortMsg", failure[0].toString());
            result.putString("stack", stack);
            sendStatus(-2, result);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runCase(String name, Runnable test) {
        currentCase = name;
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", name);
        status.putInt("numtests", CASES);
        status.putInt("current", cases + 1);
        sendStatus(1, status);
        freshView();
        test.run();
        view.cancelTouches();
        ++cases;
        status.putString("stream", ".");
        sendStatus(0, status);
    }

    private void freshView() {
        if (view != null) view.cancelTouches();
        listener = new RecordingListener();
        final String preferencePrefix = "touch-instrumentation-" + SystemClock.uptimeMillis() + "-" + cases + "-";
        // Target Resources/Android View implementation, with isolated layout preferences: never alter a player's HUD.
        ContextWrapper target = new ContextWrapper(getTargetContext()) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(preferencePrefix + name, mode);
            }
        };
        view = new TouchControlsView(target, listener);
        // Use the target's real Resources/DisplayMetrics and a sufficiently large landscape playing picture.
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        view.layout(0, 0, Math.round(1000 * density), Math.round(600 * density));
        view.configure(0.6f, false);
        eventTime = SystemClock.uptimeMillis();
        downTime = eventTime;
    }

    private void simultaneousSparseFingers() {
        Finger stick = stick(7), pointer = picture(21), a = button(3, 0), z = button(29, 3), spin = button(14, 2);
        send(MotionEvent.ACTION_DOWN, stick.id, stick);
        stick.x += geometry("stickRadius") * 0.8f;
        send(MotionEvent.ACTION_MOVE, -1, stick);
        check(listener.x > 0.7f, "movement starts");
        send(MotionEvent.ACTION_POINTER_DOWN, pointer.id, pointer, stick);
        send(MotionEvent.ACTION_POINTER_DOWN, a.id, stick, a, pointer);
        send(MotionEvent.ACTION_POINTER_DOWN, z.id, z, pointer, a, stick);
        send(MotionEvent.ACTION_POINTER_DOWN, spin.id, pointer, spin, stick, z, a);
        check((listener.buttons & (TouchControlsView.A | TouchControlsView.Z | TouchControlsView.SHAKE))
                == (TouchControlsView.A | TouchControlsView.Z | TouchControlsView.SHAKE), "A+Z+spin held together");
        check(listener.x > 0.7f && Math.abs(listener.y) < 0.05f, "action fingers preserve movement");
        check(listener.pointers.size() == 1 && listener.pointers.containsKey(21), "one independent Wii pointer");
        pointer.x += dp(35);
        send(MotionEvent.ACTION_MOVE, -1, a, pointer, z, spin, stick);
        near(listener.pointers.get(21)[0], pointer.x, "reordered pointer index tracks id");
        send(MotionEvent.ACTION_POINTER_UP, a.id, spin, pointer, stick, z, a);
        check((listener.buttons & TouchControlsView.A) == 0, "sparse A id released");
        check((listener.buttons & (TouchControlsView.Z | TouchControlsView.SHAKE))
                == (TouchControlsView.Z | TouchControlsView.SHAKE), "A release preserves Z+spin");
        check(listener.x > 0.7f && listener.pointers.containsKey(pointer.id), "A release preserves stick+pointer");
        send(MotionEvent.ACTION_POINTER_UP, pointer.id, z, stick, pointer, spin);
        check(listener.pointers.isEmpty() && listener.pointerUps == 1, "correct pointer up after index reorder");
        send(MotionEvent.ACTION_CANCEL, -1, stick, z, spin);
        neutral("end simultaneous gesture");
    }

    private void crossingNeverStealsRoles() {
        Finger stick = stick(31), pointer = picture(2), a = button(17, 0);
        send(MotionEvent.ACTION_DOWN, stick.id, stick);
        send(MotionEvent.ACTION_POINTER_DOWN, pointer.id, stick, pointer);
        Finger aCentre = button(0, 0);
        pointer.x = aCentre.x; pointer.y = aCentre.y;
        send(MotionEvent.ACTION_MOVE, -1, pointer, stick);
        check(listener.buttons == 0, "pointer crossing A never jumps");
        near(listener.pointers.get(pointer.id)[0], aCentre.x, "pointer crosses action without being captured");
        // A second picture finger must not replace the Wii pointer, including when the owner is later lifted.
        Finger spare = picture(8);
        spare.y -= dp(50);
        send(MotionEvent.ACTION_POINTER_DOWN, spare.id, pointer, spare, stick);
        check(listener.pointerDowns == 1, "second picture finger cannot steal pointer");
        send(MotionEvent.ACTION_POINTER_DOWN, a.id, a, spare, pointer, stick);
        check((listener.buttons & TouchControlsView.A) != 0, "A finger can coexist with pointer over A");
        Finger bCentre = button(0, 1);
        a.x = bCentre.x; a.y = bCentre.y;
        stick.x = aCentre.x; stick.y = aCentre.y;
        send(MotionEvent.ACTION_MOVE, -1, spare, a, stick, pointer);
        check(listener.buttons == 0, "A finger crossing B does not shoot; stick crossing A does not jump");
        check(Math.hypot(listener.x, listener.y) > 0.9, "stick remains movement after crossing picture");
        check(listener.pointerDowns == 1 && listener.pointers.containsKey(pointer.id), "neither control finger becomes pointer");
        send(MotionEvent.ACTION_POINTER_UP, pointer.id, a, stick, pointer, spare);
        int before = listener.pointerEvents;
        spare.x += dp(80);
        send(MotionEvent.ACTION_MOVE, -1, spare, stick, a);
        check(listener.pointerEvents == before && listener.pointers.isEmpty(), "ignored finger never inherits released pointer");
        send(MotionEvent.ACTION_CANCEL, -1, stick, spare, a);
        neutral("crossed controls cancel");
    }

    private void cancelFocusAndResizeRelease() {
        Finger stick = stick(5), pointer = picture(27), a = button(11, 0);
        holdThree(stick, pointer, a);
        // Deliberately omit pointer/A from CANCEL: a platform interruption still releases all remembered roles.
        send(MotionEvent.ACTION_CANCEL, -1, stick);
        neutral("partial CANCEL releases every role");
        check(listener.pointers.isEmpty() && listener.pointerUps == 1, "CANCEL sends remembered pointer up");
        int events = listener.pointerEvents;
        send(MotionEvent.ACTION_MOVE, -1, a, pointer, stick);
        neutral("late moves after cancel ignored");
        check(listener.pointerEvents == events, "late pointer cannot resurrect");

        stick = stick(5); pointer = picture(27); a = button(11, 0);
        holdThree(stick, pointer, a);
        view.onWindowFocusChanged(false);
        neutral("focus loss releases actions and stick");
        check(listener.pointers.isEmpty(), "focus loss releases pointer");
        view.onWindowFocusChanged(true);

        stick = stick(5); pointer = picture(27); a = button(11, 0);
        holdThree(stick, pointer, a);
        view.layout(0, 0, view.getWidth() - Math.round(dp(100)), view.getHeight() - Math.round(dp(40)));
        neutral("resize releases pre-layout controls");
        check(listener.pointers.isEmpty(), "resize releases pre-layout pointer");

        // A new DOWN can heal a stream that was interrupted without CANCEL.
        stick = stick(5); pointer = picture(27); a = button(11, 0);
        holdThree(stick, pointer, a);
        Finger fresh = picture(19);
        send(MotionEvent.ACTION_DOWN, fresh.id, fresh);
        neutral("fresh gesture clears stale movement and buttons");
        check(listener.pointers.size() == 1 && listener.pointers.containsKey(19), "fresh DOWN replaces stale pointer cleanly");
    }

    private void cameraToggleAndOriginalDpad() {
        Finger toggle = mode(13);
        int pointerEvents = listener.pointerEvents;
        send(MotionEvent.ACTION_DOWN, toggle.id, toggle);
        send(MotionEvent.ACTION_UP, toggle.id, toggle);
        neutral("toggle is not movement or a button");
        check(listener.pointerEvents == pointerEvents, "toggle never enters Wii pointer path");
        Finger camera = picture(31);
        float anchor = camera.x;
        send(MotionEvent.ACTION_DOWN, camera.id, camera);
        camera.x += dp(80);
        send(MotionEvent.ACTION_MOVE, -1, camera);
        check(listener.buttons == TouchControlsView.LEFT, "right look drag sends Wii LEFT, matching device gameplay direction");
        check(listener.x == 0 && listener.y == 0 && listener.pointers.isEmpty(), "camera does not move Mario or aim Wii pointer");

        toggle = mode(4);
        send(MotionEvent.ACTION_POINTER_DOWN, toggle.id, toggle, camera);
        send(MotionEvent.ACTION_POINTER_UP, toggle.id, camera, toggle);
        camera.x = anchor - dp(80);
        send(MotionEvent.ACTION_MOVE, -1, camera);
        check(listener.buttons == TouchControlsView.RIGHT, "mode change preserves current camera finger's ownership");
        check(listener.pointers.isEmpty() && listener.x == 0, "switching mode cannot turn existing drag into pointer/stick");
        send(MotionEvent.ACTION_UP, camera.id, camera);
        neutral("camera release clears D-pad");

        // Toggle taps are short: going out and back must not accidentally change POINTER back to CAM.
        toggle = mode(8);
        float tx = toggle.x;
        send(MotionEvent.ACTION_DOWN, toggle.id, toggle);
        toggle.x += dp(60); send(MotionEvent.ACTION_MOVE, -1, toggle);
        toggle.x = tx; send(MotionEvent.ACTION_MOVE, -1, toggle);
        send(MotionEvent.ACTION_UP, toggle.id, toggle);
        Finger pointer = picture(17);
        send(MotionEvent.ACTION_DOWN, pointer.id, pointer);
        pointer.x += dp(80); send(MotionEvent.ACTION_MOVE, -1, pointer);
        check(listener.pointers.containsKey(17) && listener.buttons == 0, "dragged mode control does not toggle; new picture touch points");
        send(MotionEvent.ACTION_CANCEL, -1, pointer);

        // Direct D-pad remains available without the toggle.
        Finger dpad = new Finger(25, geometry("dpadX") + geometry("dpadRadius") * 0.8f, geometry("dpadY"));
        send(MotionEvent.ACTION_DOWN, dpad.id, dpad);
        check(listener.buttons == TouchControlsView.RIGHT, "direct D-pad retains Wii RIGHT despite look-gesture mapping");
        send(MotionEvent.ACTION_UP, dpad.id, dpad);
        neutral("direct D-pad release");
    }

    private void menuPointerAndHome() {
        listener.menu = true;
        view.syncMenuContext();
        Finger finger = stick(23);
        send(MotionEvent.ACTION_DOWN, finger.id, finger);
        finger.x += dp(100); send(MotionEvent.ACTION_MOVE, -1, finger);
        check(listener.pointers.containsKey(23), "menu touch over stick geometry remains Wii pointer");
        neutral("menu pointer never moves Mario");
        send(MotionEvent.ACTION_UP, finger.id, finger);
        check(listener.pointers.isEmpty(), "menu pointer releases");
        Finger home = button(9, 6);
        send(MotionEvent.ACTION_DOWN, home.id, home);
        check(listener.buttons == TouchControlsView.HOME && listener.pointers.isEmpty(), "HOME still available over menu");
        send(MotionEvent.ACTION_UP, home.id, home);
        neutral("HOME release");
        listener.menu = false;
        view.syncMenuContext();
        Finger pointer = picture(29);
        send(MotionEvent.ACTION_DOWN, pointer.id, pointer);
        view.setControlsShown(false);
        neutral("hiding HUD releases inputs");
        check(listener.pointers.isEmpty(), "hiding HUD releases pointer");
        // SDL's settings/Home overlay can still consume pointing while the game HUD is hidden.
        send(MotionEvent.ACTION_DOWN, pointer.id, pointer);
        check(listener.pointers.containsKey(29), "hidden HUD still passes picture pointing");
        send(MotionEvent.ACTION_UP, pointer.id, pointer);
    }

    private void cameraSettingsPersistAndLeavePointerUnchanged() {
        view.applyCameraSettings(2, true, true);
        // Recreate against the SAME isolated target context: verify the next View loads persisted preferences.
        android.content.Context context = view.getContext();
        int width = view.getWidth(), height = view.getHeight();
        view = new TouchControlsView(context, listener);
        view.layout(0, 0, width, height);
        view.configure(0.6f, false);
        tapMode(3);
        Finger camera = picture(23);
        float x = camera.x, y = camera.y;
        send(MotionEvent.ACTION_DOWN, camera.id, camera);
        camera.x = x + dp(10); send(MotionEvent.ACTION_MOVE, -1, camera);
        neutral("persisted 2x sensitivity retains 11dp dead zone");
        camera.x = x + dp(14); send(MotionEvent.ACTION_MOVE, -1, camera);
        check(listener.buttons == TouchControlsView.RIGHT, "persisted X inversion reverses look-drag base in actual View output");
        camera.x = x; camera.y = y - dp(14); send(MotionEvent.ACTION_MOVE, -1, camera);
        check(listener.buttons == TouchControlsView.DOWN, "persisted Y inversion affects actual View output");
        send(MotionEvent.ACTION_UP, camera.id, camera);
        tapMode(3);
        Finger pointer = picture(11);
        send(MotionEvent.ACTION_DOWN, pointer.id, pointer);
        pointer.x += dp(17); pointer.y -= dp(23);
        send(MotionEvent.ACTION_MOVE, -1, pointer);
        near(listener.pointers.get(11)[0], pointer.x, "camera X inversion never changes Wii pointer X");
        near(listener.pointers.get(11)[1], pointer.y, "camera Y inversion never changes Wii pointer Y");
        check(listener.buttons == 0, "inverted pointer gesture never sends camera steps");
        view.applyCameraSettings(0.35f, false, false);
        check(listener.pointers.isEmpty(), "changing camera options releases existing pointer safely");
        tapMode(7);
        camera = picture(29);
        x = camera.x;
        send(MotionEvent.ACTION_DOWN, camera.id, camera);
        camera.x = x + dp(30); send(MotionEvent.ACTION_MOVE, -1, camera);
        neutral("low sensitivity needs a larger drag");
        camera.x = x + dp(70); send(MotionEvent.ACTION_MOVE, -1, camera);
        check(listener.buttons == TouchControlsView.LEFT, "low sensitivity reaches right-look camera step at expected distance");
        send(MotionEvent.ACTION_CANCEL, -1, camera);
        neutral("camera options case ends neutral");
    }

    private void tapMode(int id) {
        Finger toggle = mode(id);
        send(MotionEvent.ACTION_DOWN, toggle.id, toggle);
        send(MotionEvent.ACTION_UP, toggle.id, toggle);
    }

    private void galaxyPresetClearsHudAndPreservesCustom() {
        view.layout(0, 0, 2772, 1280);
        final RectF life = new RectF(2390, 60, 2640, 350);
        final RectF counters = new RectF(2420, 960, 2660, 1180);
        final RectF modeBounds = new RectF((RectF)field(view, "aimMode"));
        check(!RectF.intersects(modeBounds, life) && !RectF.intersects(modeBounds, counters), "toggle clears both Galaxy HUD corners");
        near(modeBounds.centerX(), 2772 * 0.72f, "Galaxy toggle sits inward of LIFE");
        for (int index = 0; index <= 4; ++index) {
            Object b = ((List<?>)field(view, "buttons")).get(index);
            Finger center = button(index + 1, index);
            float radius = ((Number)field(b, "radius")).floatValue() * 1.25f;
            RectF hit = new RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius);
            check(!RectF.intersects(hit, life), "action " + index + " hit geometry clears LIFE");
            check(!RectF.intersects(hit, counters), "action " + index + " hit geometry clears coin/Star Bit counters");
            send(MotionEvent.ACTION_DOWN, center.id, center);
            check(listener.buttons != 0, "moved default action " + index + " still answers touch at its visible centre");
            send(MotionEvent.ACTION_UP, center.id, center);
        }
        android.content.Context context = view.getContext();
        TouchLayouts saved = new TouchLayouts(context);
        String key = TouchLayouts.key(2772, 1280, getTargetContext().getResources().getDisplayMetrics().density);
        TouchLayouts.Layout custom = saved.load(key);
        custom.x[0] = 0.9f; custom.y[0] = 0.85f;
        custom.x[4] = 0.7f; custom.y[4] = 0.8f;
        saved.save(key, custom);
        view = new TouchControlsView(context, listener);
        view.layout(0, 0, 2772, 1280);
        near(button(1, 0).x, 2772 * 0.9f, "saved custom A X overrides updated preset");
        near(button(1, 0).y, 1280 * 0.85f, "saved custom A Y is never lifted by preset");
        near(button(1, 4).x, 2772 * 0.7f, "saved custom C X preserved");
        near(button(1, 4).y, 1280 * 0.8f, "saved custom C Y preserved");
    }

    private void holdThree(Finger stick, Finger pointer, Finger a) {
        send(MotionEvent.ACTION_DOWN, stick.id, stick);
        stick.x += geometry("stickRadius") * 0.8f;
        send(MotionEvent.ACTION_MOVE, -1, stick);
        send(MotionEvent.ACTION_POINTER_DOWN, pointer.id, stick, pointer);
        send(MotionEvent.ACTION_POINTER_DOWN, a.id, a, pointer, stick);
        check(listener.x > 0.7f && (listener.buttons & TouchControlsView.A) != 0 && listener.pointers.size() == 1,
                "precondition: movement + pointer + jump held");
    }

    private void send(int action, int actionId, Finger... fingers) {
        eventTime += 16;
        if (action == MotionEvent.ACTION_DOWN) downTime = eventTime;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[fingers.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[fingers.length];
        int actionIndex = 0;
        for (int i = 0; i < fingers.length; ++i) {
            Finger f = fingers[i];
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = f.id;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = f.x; coords[i].y = f.y; coords[i].pressure = 1; coords[i].size = 0.1f;
            if (f.id == actionId) actionIndex = i;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action |= actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        }
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, fingers.length, properties, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            check(view.dispatchTouchEvent(event), "View consumes touch stream");
        } finally {
            event.recycle();
        }
    }

    private Finger stick(int id) { return new Finger(id, geometry("stickRestX"), geometry("stickRestY")); }
    private Finger picture(int id) { return new Finger(id, view.getWidth() * 0.55f, view.getHeight() * 0.42f); }
    private Finger mode(int id) {
        RectF bounds = (RectF) field(view, "aimMode");
        return new Finger(id, bounds.centerX(), bounds.centerY());
    }
    private Finger button(int id, int index) {
        Object b = ((List<?>) field(view, "buttons")).get(index);
        return new Finger(id, ((Number)field(b, "x")).floatValue(), ((Number)field(b, "y")).floatValue());
    }
    private float geometry(String name) { return ((Number)field(view, name)).floatValue(); }
    private static Object field(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Missing control geometry " + name, error);
        }
    }
    private float dp(float value) { return value * getTargetContext().getResources().getDisplayMetrics().density; }
    private void neutral(String message) { check(listener.buttons == 0 && listener.x == 0 && listener.y == 0, message); }
    private void near(float actual, float expected, String message) { check(Math.abs(actual - expected) < 0.01f, message); }
    private void check(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(currentCase + ": " + message);
    }

    static final class Finger {
        final int id;
        float x, y;
        Finger(int id, float x, float y) { this.id = id; this.x = x; this.y = y; }
    }
    static final class RecordingListener implements TouchControlsView.Listener {
        int buttons, pointerDowns, pointerUps, pointerEvents;
        float x, y;
        boolean menu;
        final Map<Integer, float[]> pointers = new HashMap<>();
        public void touchStarted() { }
        public void cameraDialogChanged(boolean open) { }
        public void onControls(int buttons, float x, float y) { this.buttons = buttons; this.x = x; this.y = y; }
        public void onPointer(int action, int id, float x, float y) {
            ++pointerEvents;
            if (action == 0) { ++pointerDowns; pointers.put(id, new float[] {x, y}); }
            else if (action == 1) {
                if (!pointers.containsKey(id)) throw new AssertionError("Pointer move without down: " + id);
                pointers.put(id, new float[] {x, y});
            } else if (action == 2) {
                if (pointers.remove(id) == null) throw new AssertionError("Pointer up without down: " + id);
                ++pointerUps;
            }
        }
        public boolean menuContext() { return menu; }
        public boolean titleScreen() { return false; }
        public boolean touchTarget(float x, float y) { return false; }
        public void arrangingDone() { }
        public void touchSettings(int size, int opacity, boolean haptics) { }
    }
}
