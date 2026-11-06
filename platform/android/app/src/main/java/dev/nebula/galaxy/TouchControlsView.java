// SPDX-License-Identifier: GPL-3.0-only
package dev.nebula.galaxy;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The on-screen Wii Remote and Nunchuk over the picture. Every touch comes here: a finger that lands on a control
 * keeps its role until released. Movement, Wii pointing and action buttons can be held together. The visible
 * POINTER/CAM switch changes only new picture gestures; CAM sends the original game's D-pad camera steps.
 */
final class TouchControlsView extends View {
    // As platform/sdl/sdl_host.h's TouchButton.
    static final int A = 1, B = 1 << 1, Z = 1 << 2, C = 1 << 3, SHAKE = 1 << 4, MINUS = 1 << 5, PLUS = 1 << 6,
            HOME = 1 << 7, ONE = 1 << 8, TWO = 1 << 9, UP = 1 << 10, DOWN = 1 << 11, LEFT = 1 << 12, RIGHT = 1 << 13;

    interface Listener {
        /** Reveal automatic touch controls before assigning this gesture to a role. */
        void touchStarted();

        /** Camera options own the picture until dismissed; the Activity pauses only while it is foreground. */
        void cameraDialogChanged(boolean open);
        /** The held buttons and the stick (-1..1, up positive) changed. */
        void onControls(int buttons, float stickX, float stickY);

        /** A finger on the picture: 0 down, 1 moved, 2 up; x, y in pixels. */
        void onPointer(int action, int id, float x, float y);

        /** The game is on a pointer screen: only the stick's own circle is the stick. */
        boolean menuContext();

        boolean titleScreen();

        /** A finger at x, y (pixels) lands on something the pointer grabs: it points, whatever side of the screen it is on. */
        boolean touchTarget(float x, float y);

        /** The player pressed OK. */
        void arrangingDone();

        /** What General's rows show for the screen as it is now; 0: the size the game gives this screen. */
        void touchSettings(int sizePercent, int opacityPercent, boolean haptics);
    }

    // What each finger (by pointer id, 0..31) landed on: a button's index or one of these. Arrays, so a touch
    // allocates nothing.
    private static final int MAX_POINTERS = TouchInputModel.COUNT;
    private static final int TARGET_NONE = TouchInputModel.NONE;
    private static final int TARGET_STICK = TouchInputModel.STICK;
    private static final int TARGET_POINTER = TouchInputModel.POINTER;
    private static final int TARGET_DPAD = TouchInputModel.DPAD;
    private static final int TARGET_CAMERA = TouchInputModel.CAMERA;
    private static final int TARGET_MODE = TouchInputModel.MODE;
    private static final float STICK_DEAD_ZONE = 0.06f;
    // Places in `buttons`.
    private static final int HOME_BUTTON = 6;
    private static final int TWO_BUTTON = 9;
    // Places in a saved layout: the buttons', then these.
    private static final int ITEM_STICK = 10;
    private static final int ITEM_DPAD = 11;
    private static final float SIZE_MIN = 0.4f;
    private static final float SIZE_MAX = 2.0f;

    private static final class Button {
        final int bit;
        final String label;
        final boolean spinIcon;
        final boolean homeIcon;
        float x, y, radius;

        Button(int bit, String label, boolean spinIcon, boolean homeIcon) {
            this.bit = bit;
            this.label = label;
            this.spinIcon = spinIcon;
            this.homeIcon = homeIcon;
        }
    }

    private final Listener listener;
    private final float dp;
    private final List<Button> buttons = new ArrayList<>();
    private final TouchInputModel input = new TouchInputModel();
    private final float[] stickValue = new float[2];
    private final float[] lastX = new float[MAX_POINTERS], lastY = new float[MAX_POINTERS];
    private final RectF aimMode = new RectF();
    private boolean cameraMode;
    private TouchLayouts.CameraSettings cameraSettings;
    private AlertDialog cameraDialog;
    private final Runnable cameraOptions = this::openCameraSettings;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private float opacity;
    private float scale;
    private boolean haptics;
    private boolean shown = true;
    private Insets safe = Insets.NONE;

    // The D-pad: centre and reach.
    private float dpadX, dpadY, dpadRadius;
    // The stick: where it rests, its reach, and while held, where it was touched and where the finger is.
    private float stickRestX, stickRestY, stickRadius;
    private float stickBaseX, stickBaseY, stickKnobX, stickKnobY;
    private int stickPointer = -1;
    private boolean pointerScreen = false;
    private float homeX;
    private float homeY;
    private float stickZoneRight, stickZoneTop;

    private int heldButtons;
    private float stickX, stickY;

    // The arrangement of this shape of screen.
    private final TouchLayouts layouts;
    private TouchLayouts.Layout layout = new TouchLayouts.Layout();
    private String layoutKey;
    private boolean stickOnLeft = true;
    private boolean layoutLoaded;

    // Arranging: the OK button, and the control under the one finger that counts.
    private boolean arranging;
    private int selected = -1;
    private int dragPointer = -1;
    private float dragOffsetX, dragOffsetY;
    private int okPointer = -1;
    private final RectF ok = new RectF();

    TouchControlsView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        layouts = new TouchLayouts(context);
        cameraSettings = layouts.loadCamera();
        dp = context.getResources().getDisplayMetrics().density;
        buttons.add(new Button(A, "A", false, false));
        buttons.add(new Button(B, "B", false, false));
        buttons.add(new Button(SHAKE, "", true, false));
        buttons.add(new Button(Z, "Z", false, false));
        buttons.add(new Button(C, "C", false, false));
        buttons.add(new Button(MINUS, "−", false, false));
        buttons.add(new Button(HOME, "", false, true));
        buttons.add(new Button(PLUS, "+", false, false));
        buttons.add(new Button(ONE, "1", false, false));
        buttons.add(new Button(TWO, "2", false, false));
        stroke.setStyle(Paint.Style.STROKE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        setFocusable(false);
        configure(0.6f, true);
    }

    void configure(float opacity, boolean haptics) {
        this.opacity = Math.max(0.2f, Math.min(1.0f, opacity));
        this.haptics = haptics;
        layoutControls(getWidth(), getHeight());
        invalidate();
    }

    /** The game went to or from a pointer screen (GameActivity.menuContextChanged). */
    void syncMenuContext() {
        final boolean menu = listener.menuContext();
        if (menu == pointerScreen) {
            return;
        }
        pointerScreen = menu;
        placeHome();
        cancelTouches();
        invalidate();
    }

    // HOME over the picture on a pointer screen: in the top corner, clear of the game's own buttons.
    private void placeHome() {
        final Button home = buttons.get(HOME_BUTTON);
        home.x = pointerScreen ? buttons.get(TWO_BUTTON).x : homeX;
        home.y = pointerScreen ? buttons.get(TWO_BUTTON).y : homeY;
        if (pointerScreen && getWidth() > 0 && getHeight() > 0) {
            // The file card (FileInfo: 286 canvas units either side, its foot 110 under the canvas's top) spans a screen
            // narrower than 16:9 at the top: HOME goes under its foot.
            final float aspect = (float) getWidth() / getHeight();
            final float unit = aspect < 4.0f / 3.0f ? getWidth() / 608.0f : getHeight() / 456.0f;
            if (home.x - home.radius < getWidth() / 2.0f + 286.0f * unit) {
                home.y = Math.max(home.y, 118.0f * unit + home.radius);
            }
        }
    }

    void setControlsShown(boolean value) {
        if (shown == value) {
            return;
        }
        shown = value;
        cancelTouches();
        invalidate();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        cancelTouches();
        safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout() | WindowInsets.Type.systemGestures());
        layoutControls(getWidth(), getHeight());
        return insets;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        cancelTouches();
        layoutControls(width, height);
    }

    // The size a screen starts at: controls in proportion to it, so a small phone isn't covered by them.
    private float startingSize(int width, int height) {
        final float shortest = Math.min(width, height) / dp;
        return Math.max(0.55f, Math.min(1.1f, shortest / 400.0f));
    }

    private void layoutControls(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        final String key = TouchLayouts.key(width, height, dp);
        if (!key.equals(layoutKey)) {
            if (arranging && layoutKey != null) {
                layouts.save(layoutKey, layout);
            }
            layoutKey = key;
            layout = layouts.load(key);
            selected = -1;
            layoutLoaded = true;
        } else {
            layoutLoaded = false;
        }
        scale = Float.isNaN(layout.size) ? startingSize(width, height) : layout.size;
        final float u = dp * scale;
        final float left = Math.max(safe.left, 0) + 20 * dp;
        final float right = width - Math.max(safe.right, 0) - 20 * dp;
        final float top = Math.max(safe.top, 0) + 14 * dp;
        final float bottom = height - Math.max(safe.bottom, 0) - 18 * dp;

        // The right thumb's arc: A at its centre, Spin, B and Z around it.
        final float ra = 40 * u;
        final float rs = 29 * u;
        final Button a = buttons.get(0);
        a.radius = ra;
        a.x = right - ra;
        a.y = TouchLayouts.galaxyActionY(width, height, bottom - ra, ra, u, dp);
        final float reach = ra + rs + 14 * u;
        placeOnArc(buttons.get(2), a, reach, 98, rs);   // Spin
        placeOnArc(buttons.get(1), a, reach, 142, rs);  // B
        placeOnArc(buttons.get(3), a, reach, 186, rs);  // Z
        // C above the arc, at the edge.
        final Button c = buttons.get(4);
        c.radius = 24 * u;
        // C sits inward of LIFE, leaving the raised Spin button room below it.
        c.x = width > height ? width * 0.815f : right - c.radius;
        c.y = width > height ? height * 0.34f : a.y - reach - rs - 34 * u;

        // The small buttons: - HOME + at the top centre, 1 2 at the top right (a row lower where the screen is too
        // narrow for both).
        final float rm = 19 * u;
        final float cx = width / 2.0f;
        final float row = top + rm;
        place(buttons.get(5), cx - 3.0f * rm, row, rm);
        place(buttons.get(6), cx, row, rm * 1.1f);
        place(buttons.get(7), cx + 3.0f * rm, row, rm);
        final float row12 = cx + 4.2f * rm > right - 4.4f * rm ? row + 2.8f * rm : row;
        place(buttons.get(9), right - rm, row12, rm);
        place(buttons.get(8), right - 3.4f * rm, row12, rm);
        // Keep the optional 1/2 and minus in saved layouts and Arrange, but leave the playing picture light.
        aimMode.set(right - 100 * u, top, right, top + 38 * u);
        if (width > height) {
            // The real game HUD reserves the far right: place the switch inward, around (2000,220) at 2772x1280.
            final float modeX = TouchInputModel.clampCenter(width * 0.72f, 50 * u, left, right);
            final float modeY = TouchInputModel.clampCenter(height * 0.172f, 19 * u,
                    Math.max(safe.top, 0), height - Math.max(safe.bottom, 0));
            aimMode.set(modeX - 50 * u, modeY - 19 * u, modeX + 50 * u, modeY + 19 * u);
        }

        // The stick at the bottom left; the D-pad at the top left, or on a tall screen (a phone upright, the Fold
        // open) just above the stick, where the thumb reaches it.
        dpadRadius = 32 * u;
        stickRadius = 62 * u;
        stickRestX = left + stickRadius + 10 * u;
        stickRestY = bottom - stickRadius;
        dpadX = left + dpadRadius;
        dpadY = height > width ? stickRestY - stickRadius - dpadRadius - 28 * u : top + dpadRadius + 8 * u;
        stickZoneRight = width * 0.46f;
        stickZoneTop = dpadY + dpadRadius + 12 * u;
        text.setTextSize(20 * u);

        // Where the player put them for this shape of screen, on the screen however large they now are.
        for (int i = 0; i < buttons.size(); ++i) {
            final Button button = buttons.get(i);
            if (layout.moved(i)) {
                button.x = onScreen(layout.x[i] * width, button.radius, width);
                button.y = onScreen(layout.y[i] * height, button.radius, height);
            }
        }
        if (layout.moved(ITEM_DPAD)) {
            dpadX = onScreen(layout.x[ITEM_DPAD] * width, dpadRadius, width);
            dpadY = onScreen(layout.y[ITEM_DPAD] * height, dpadRadius, height);
        }
        if (layout.moved(ITEM_STICK)) {
            stickRestX = onScreen(layout.x[ITEM_STICK] * width, stickRadius, width);
            stickRestY = onScreen(layout.y[ITEM_STICK] * height, stickRadius, height);
        }
        stickOnLeft = stickRestX < width / 2.0f;
        if (layout.moved(ITEM_STICK) || layout.moved(ITEM_DPAD)) {
            // The stick floats in the lower part of its side, above which the D-pad may sit.
            final boolean dpadAbove = (dpadX < width / 2.0f) == stickOnLeft && dpadY < stickRestY;
            stickZoneTop = dpadAbove ? dpadY + dpadRadius + 12 * u : Math.max(0.0f, stickRestY - stickRadius * 2.6f);
        }
        homeX = buttons.get(HOME_BUTTON).x;
        homeY = buttons.get(HOME_BUTTON).y;
        placeHome();
        if (arranging) {
            layoutOk(width, height);
        }
        if (layoutLoaded) {
            reportSettings();
        }
    }

    private static float onScreen(float at, float radius, int size) {
        return TouchInputModel.clampCenter(at, radius, 0, size);
    }

    private void reportSettings() {
        listener.touchSettings(Float.isNaN(layout.size) ? 0 : Math.round(layout.size * 100.0f), Math.round(opacity * 100.0f), haptics);
    }

    /** From General's rows. */
    void applySettings(int sizePercent, int opacityPercent, boolean vibrate) {
        cancelTouches();
        layout.size = sizePercent == 0 ? Float.NaN : Math.max(SIZE_MIN, Math.min(SIZE_MAX, sizePercent / 100.0f));
        layouts.save(layoutKey, layout);
        opacity = Math.max(0.2f, Math.min(1.0f, opacityPercent / 100.0f));
        haptics = vibrate;
        layoutControls(getWidth(), getHeight());
        reportSettings();
        invalidate();
    }

    /** From General's row: every control back where the game puts it on this shape of screen. */
    void resetPlaces() {
        cancelTouches();
        Arrays.fill(layout.x, Float.NaN);
        Arrays.fill(layout.y, Float.NaN);
        layouts.save(layoutKey, layout);
        layoutControls(getWidth(), getHeight());
        invalidate();
    }

    // Arranging the controls: the icons can be dragged, OK locks them in. Nothing else answers. Saved for this shape of
    // screen.
    void startArranging() {
        cancelTouches();
        arranging = true;
        selected = -1;
        dragPointer = -1;
        okPointer = -1;
        // A finger or button held when this began lets go.
        layoutOk(getWidth(), getHeight());
        invalidate();
    }

    boolean isArranging() {
        return arranging;
    }

    void finishArranging() {
        dragPointer = okPointer = -1;
        arranging = false;
        selected = -1;
        layouts.save(layoutKey, layout);
        listener.arrangingDone();
        invalidate();
    }

    private void layoutOk(int width, int height) {
        final float wide = 132 * dp;
        final float tall = 56 * dp;
        ok.set((width - wide) / 2.0f, (height - tall) / 2.0f, (width + wide) / 2.0f, (height + tall) / 2.0f);
    }

    // The control under a point: its place in a layout, or -1.
    private int itemAt(float x, float y) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < buttons.size(); ++i) {
            final Button button = buttons.get(i);
            final float distance = (float) Math.hypot(x - button.x, y - button.y) / (button.radius * 1.25f);
            if (distance <= 1.0f && distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        final float dpad = (float) Math.hypot(x - dpadX, y - dpadY) / (dpadRadius * 1.1f);
        if (dpad <= 1.0f && dpad < bestDistance) {
            best = ITEM_DPAD;
            bestDistance = dpad;
        }
        final float stick = (float) Math.hypot(x - stickRestX, y - stickRestY) / stickRadius;
        if (stick <= 1.0f && stick < bestDistance) {
            best = ITEM_STICK;
        }
        return best;
    }

    private float itemX(int item) {
        return item == ITEM_STICK ? stickRestX : item == ITEM_DPAD ? dpadX : buttons.get(item).x;
    }

    private float itemY(int item) {
        return item == ITEM_STICK ? stickRestY : item == ITEM_DPAD ? dpadY : buttons.get(item).y;
    }

    private float itemRadius(int item) {
        return item == ITEM_STICK ? stickRadius : item == ITEM_DPAD ? dpadRadius : buttons.get(item).radius;
    }

    private void moveItem(int item, float x, float y) {
        final float r = itemRadius(item);
        final float cx = Math.max(r, Math.min(getWidth() - r, x));
        final float cy = Math.max(r, Math.min(getHeight() - r, y));
        layout.x[item] = cx / getWidth();
        layout.y[item] = cy / getHeight();
        layoutControls(getWidth(), getHeight());
        invalidate();
    }

    // One finger at a time moves an icon or presses OK; any other touch is nothing.
    private void arrangeEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            dragPointer = okPointer = -1;
            invalidate();
            return;
        }
        final int index = event.getActionIndex();
        final int id = event.getPointerId(index);
        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN: {
            if (dragPointer >= 0 || okPointer >= 0) {
                return;
            }
            final float x = event.getX(index);
            final float y = event.getY(index);
            if (ok.contains(x, y)) {
                okPointer = id;
                invalidate();
                return;
            }
            final int item = itemAt(x, y);
            selected = item;
            if (item >= 0) {
                dragPointer = id;
                dragOffsetX = itemX(item) - x;
                dragOffsetY = itemY(item) - y;
                if (haptics) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            }
            invalidate();
            return;
        }
        case MotionEvent.ACTION_MOVE: {
            if (dragPointer < 0 || selected < 0) {
                return;
            }
            final int finger = event.findPointerIndex(dragPointer);
            if (finger >= 0) {
                moveItem(selected, event.getX(finger) + dragOffsetX, event.getY(finger) + dragOffsetY);
            }
            return;
        }
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
        case MotionEvent.ACTION_CANCEL:
            if (id == dragPointer) {
                dragPointer = -1;
            } else if (id == okPointer) {
                okPointer = -1;
                if (action != MotionEvent.ACTION_CANCEL && ok.contains(event.getX(index), event.getY(index))) {
                    if (haptics) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    finishArranging();
                    return;
                }
                invalidate();
            }
            return;
        default:
            return;
        }
    }

    private void drawArranging(Canvas canvas) {
        // A plain backdrop: nothing behind it looks as if it could be pressed.
        canvas.drawColor(0xFF0A1030);
        final float savedSize = text.getTextSize();
        for (Button button : buttons) {
            drawButton(canvas, button, false);
        }
        drawDpad(canvas);
        drawStick(canvas);
        if (selected >= 0) {
            stroke.setColor(0xFFFFD84D);
            stroke.setAlpha(255);
            stroke.setStrokeWidth(3 * dp);
            canvas.drawCircle(itemX(selected), itemY(selected), itemRadius(selected) + 5 * dp, stroke);
            stroke.setStrokeWidth(1.6f * dp);
        }
        text.setTextSize(15 * dp);
        text.setColor(0xFFC8D2F0);
        text.setAlpha(255);
        canvas.drawText(getContext().getString(R.string.arrange_hint), ok.centerX(), ok.top - 16 * dp, text);
        fill.setColor(okPointer >= 0 ? 0xFFFFE680 : 0xFFFFD84D);
        fill.setAlpha(255);
        canvas.drawRoundRect(ok, ok.height() / 2.0f, ok.height() / 2.0f, fill);
        text.setTextSize(21 * dp);
        text.setColor(0xFF1B1600);
        text.setAlpha(255);
        text.getFontMetrics(fontMetrics);
        final Paint.FontMetrics metrics = fontMetrics;
        canvas.drawText(getContext().getString(R.string.arrange_ok), ok.centerX(), ok.centerY() - (metrics.ascent + metrics.descent) / 2.0f, text);
        text.setTextSize(savedSize);
    }

    private static void place(Button button, float x, float y, float radius) {
        button.x = x;
        button.y = y;
        button.radius = radius;
    }

    private static void placeOnArc(Button button, Button centre, float reach, float degrees, float radius) {
        final double angle = Math.toRadians(degrees);
        place(button, (float) (centre.x + reach * Math.cos(angle)), (float) (centre.y - reach * Math.sin(angle)), radius);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // A fresh gesture also heals an interrupted stream whose CANCEL was lost by a parent/window.
            cancelTouches();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (arranging) {
            arrangeEvent(event);
            return true;
        }
        final int action = event.getActionMasked();
        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN: {
            final int index = event.getActionIndex();
            down(event.getPointerId(index), event.getX(index), event.getY(index));
            break;
        }
        case MotionEvent.ACTION_MOVE:
            for (int i = 0; i < event.getPointerCount(); ++i) {
                move(event.getPointerId(i), event.getX(i), event.getY(i));
            }
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP: {
            final int index = event.getActionIndex();
            up(event.getPointerId(index), event.getX(index), event.getY(index));
            break;
        }
        case MotionEvent.ACTION_CANCEL:
            cancelTouches();
            break;
        default:
            break;
        }
        return true;
    }

    private void down(int id, float x, float y) {
        if (id < 0 || id >= MAX_POINTERS) {
            return;
        }
        listener.touchStarted();
        int target = TARGET_POINTER;
        syncMenuContext();
        if (shown) {
            final int button = buttonAt(x, y);
            if (!pointerScreen && !listener.titleScreen() && aimMode.contains(x, y)) {
                target = TARGET_MODE;
            } else if (button >= 0) {
                target = button;
            } else if (pointerScreen) {
                target = TARGET_POINTER;
            } else if (!cameraMode && listener.touchTarget(x, y)) {
                target = TARGET_POINTER;
            } else if (dpadBits(x, y) != 0 || Math.hypot(x - dpadX, y - dpadY) <= dpadRadius * 1.2f) {
                target = TARGET_DPAD;
            } else if (stickPointer < 0 && !listener.titleScreen() && inStickZone(x, y)) {
                target = TARGET_STICK;
                stickPointer = id;
                // The stick centres under the thumb, kept whole on the screen.
                stickBaseX = TouchInputModel.clampCenter(x, stickRadius,
                        Math.max(safe.left, 0) + 8 * dp, getWidth() - Math.max(safe.right, 0) - 8 * dp);
                stickBaseY = TouchInputModel.clampCenter(y, stickRadius,
                        Math.max(safe.top, 0) + 8 * dp, getHeight() - Math.max(safe.bottom, 0) - 8 * dp);
                stickKnobX = x;
                stickKnobY = y;
            } else if (!listener.titleScreen() && cameraMode) {
                target = TARGET_CAMERA;
            }
        }
        if (!input.claim(id, target, x, y)) return;
        lastX[id] = x;
        lastY[id] = y;
        input.motion(id, x, y);
        if (target == TARGET_POINTER) {
            listener.onPointer(0, id, x, y);
            return;
        }
        if (target == TARGET_MODE) postDelayed(cameraOptions, ViewConfiguration.getLongPressTimeout());
        update(id, x, y);
    }

    private void move(int id, float x, float y) {
        final int target = input.role(id);
        if (target == TARGET_NONE) {
            return;
        }
        lastX[id] = x;
        lastY[id] = y;
        input.motion(id, x, y);
        if (target == TARGET_MODE && !input.isTap(id, 24 * dp)) removeCallbacks(cameraOptions);
        if (target == TARGET_POINTER) {
            listener.onPointer(1, id, x, y);
            return;
        }
        update(id, x, y);
    }

    private void up(int id, float x, float y) {
        final int target = input.role(id);
        if (target == TARGET_NONE) {
            return;
        }
        input.motion(id, x, y);
        input.release(id);
        if (target == TARGET_MODE) {
            removeCallbacks(cameraOptions);
            if (aimMode.contains(x, y) && input.isTap(id, 24 * dp)) {
                cameraMode = !cameraMode;
                invalidate();
            }
        }
        if (target == TARGET_POINTER) {
            listener.onPointer(2, id, x, y);
            return;
        }
        if (target == TARGET_STICK) {
            stickPointer = -1;
        }
        recompute();
    }

    // A control finger moved or landed: what it holds now.
    private void update(int id, float x, float y) {
        final int target = input.role(id);
        int bits = 0;
        if (target == TARGET_STICK) {
            stickKnobX = x;
            stickKnobY = y;
        } else if (target == TARGET_DPAD) {
            bits = dpadBits(x, y);
        } else if (target == TARGET_CAMERA) {
            bits = TouchInputModel.cameraDirection(input.dx(id, x), input.dy(id, y), cameraSettings.thresholdDp() * dp,
                    cameraSettings.invertX, cameraSettings.invertY);
        } else if (target >= 0 && target < buttons.size()) {
            // Only the button this finger started on can answer it; crossing the picture never grabs another role.
            final Button button = buttons.get(target);
            if (TouchInputModel.circle(x, y, button.x, button.y, button.radius * 1.5f)) bits = button.bit;
        }
        input.setButtons(id, bits);
        recompute();
    }

    private void recompute() {
        final int bits = input.held();
        float sx = 0.0f;
        float sy = 0.0f;
        if (stickPointer >= 0) {
            TouchInputModel.stick(stickKnobX - stickBaseX, stickBaseY - stickKnobY, stickRadius, STICK_DEAD_ZONE, stickValue);
            sx = stickValue[0];
            sy = stickValue[1];
        }
        publish(bits, sx, sy);
    }

    private void publish(int bits, float sx, float sy) {
        final int pressed = bits & ~heldButtons;
        if (pressed != 0 && haptics) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (bits != heldButtons || sx != stickX || sy != stickY) {
            heldButtons = bits;
            stickX = sx;
            stickY = sy;
            listener.onControls(bits, sx, sy);
            invalidate();
        }
    }

    private boolean inStickZone(float x, float y) {
        if (listener.menuContext()) {
            return Math.hypot(x - stickRestX, y - stickRestY) <= stickRadius * 1.15f;
        }
        final boolean side = stickOnLeft ? x < stickZoneRight : x > getWidth() - stickZoneRight;
        return side && y > stickZoneTop;
    }

    private int buttonAt(float x, float y) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < buttons.size(); ++i) {
            if (!pointerScreen && !visibleButton(i)) continue;
            if (pointerScreen && i != HOME_BUTTON) {
                continue;
            }
            final Button button = buttons.get(i);
            final float distance = (float) Math.hypot(x - button.x, y - button.y);
            // A little beyond the drawn circle: thumbs land wide.
            if (distance <= button.radius * 1.25f && distance / button.radius < bestDistance) {
                best = i;
                bestDistance = distance / button.radius;
            }
        }
        return best;
    }

    // Eight directions: the arm under the finger, both arms on a diagonal.
    private int dpadBits(float x, float y) {
        return TouchInputModel.dpad(x - dpadX, y - dpadY, dpadRadius);
    }

    private boolean visibleButton(int index) { return index != 5 && index != 8 && index != 9; }

    /** Release all roles, including a Wii pointer absent from the final MotionEvent. Safe to call repeatedly. */
    void cancelTouches() {
        removeCallbacks(cameraOptions);
        for (int i = 0; i < MAX_POINTERS; ++i) {
            if (input.role(i) == TARGET_POINTER) listener.onPointer(2, i, lastX[i], lastY[i]);
        }
        input.clear();
        stickPointer = -1;
        dragPointer = okPointer = -1;
        publish(0, 0, 0);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelTouches();
        if (cameraDialog != null) cameraDialog.dismiss();
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (!hasFocus) cancelTouches();
        super.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (arranging) {
            stroke.setStrokeWidth(1.6f * dp);
            drawArranging(canvas);
            return;
        }
        if (!shown) {
            return;
        }
        stroke.setStrokeWidth(1.6f * dp);
        if (pointerScreen) {
            final Button home = buttons.get(HOME_BUTTON);
            drawButton(canvas, home, (heldButtons & home.bit) != 0);
            return;
        }
        for (int i = 0; i < buttons.size(); ++i) {
            final Button button = buttons.get(i);
            if (visibleButton(i)) drawButton(canvas, button, (heldButtons & button.bit) != 0);
        }
        drawDpad(canvas);
        drawStick(canvas);
        if (!listener.titleScreen()) drawAimMode(canvas);
    }

    private void drawAimMode(Canvas canvas) {
        fill.setColor(0xFF101D3C);
        fill.setAlpha(alpha(0.7f));
        canvas.drawRoundRect(aimMode, aimMode.height() / 2, aimMode.height() / 2, fill);
        stroke.setColor(cameraMode ? 0xFFFFDB76 : 0xFF8CE8FF);
        stroke.setAlpha(alpha(0.85f));
        canvas.drawRoundRect(aimMode, aimMode.height() / 2, aimMode.height() / 2, stroke);
        final float oldSize = text.getTextSize();
        text.setTextSize(11 * dp * scale);
        text.setColor(cameraMode ? 0xFFFFDB76 : 0xFFB5F0FF);
        text.setAlpha(alpha(1));
        text.getFontMetrics(fontMetrics);
        final Paint.FontMetrics metrics = fontMetrics;
        final boolean portuguese = "pt".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
        canvas.drawText(cameraMode ? (portuguese ? "CÂMERA ↔" : "CAM ↔") : (portuguese ? "PONTEIRO" : "POINTER"), aimMode.centerX(),
                aimMode.centerY() - (metrics.ascent + metrics.descent) / 2, text);
        text.setTextSize(oldSize);
    }

    /** These settings apply to D-pad camera gestures only. Wii pointer coordinates are always passed through. */
    void applyCameraSettings(float sensitivity, boolean invertX, boolean invertY) {
        cancelTouches();
        cameraSettings = new TouchLayouts.CameraSettings(sensitivity, invertX, invertY);
        layouts.saveCamera(cameraSettings);
    }

    private void openCameraSettings() {
        if (cameraDialog != null) return;
        final int finger = input.owner(TARGET_MODE);
        if (finger < 0 || !input.isTap(finger, 24 * dp)) return;
        cancelTouches(); // The long press is consumed; releasing it must not toggle or leak into the Wii pointer.
        if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        final boolean pt = "pt".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
        final LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        final int padding = Math.round(20 * dp);
        content.setPadding(padding, padding / 2, padding, 0);
        final TextView explanation = new TextView(getContext());
        explanation.setText(pt
                ? "O arrasto envia passos do direcional, não órbita analógica. Maior sensibilidade reduz a distância mínima. O ponteiro Wii não muda. O jogo fica pausado enquanto esta janela está aberta."
                : "Dragging sends D-pad camera steps, not analog orbit. Higher sensitivity reduces the minimum drag distance. The Wii pointer is unchanged. The game is paused while this window is open.");
        content.addView(explanation);
        final TextView distance = new TextView(getContext());
        distance.setPadding(0, Math.round(14 * dp), 0, 0);
        content.addView(distance);
        final SeekBar sensitivity = new SeekBar(getContext());
        sensitivity.setMax(165);
        sensitivity.setProgress(Math.round((cameraSettings.sensitivity - 0.35f) * 100));
        content.addView(sensitivity);
        final CheckBox invertX = new CheckBox(getContext());
        invertX.setText(pt ? "Inverter horizontal (X)" : "Invert horizontal (X)");
        invertX.setChecked(cameraSettings.invertX);
        content.addView(invertX);
        final CheckBox invertY = new CheckBox(getContext());
        invertY.setText(pt ? "Inverter vertical (Y)" : "Invert vertical (Y)");
        invertY.setChecked(cameraSettings.invertY);
        content.addView(invertY);
        final Runnable updateDistance = () -> {
            final TouchLayouts.CameraSettings setting = new TouchLayouts.CameraSettings(0.35f + sensitivity.getProgress() / 100.0f, false, false);
            distance.setText(String.format(pt ? java.util.Locale.forLanguageTag("pt-BR") : java.util.Locale.US,
                    pt ? "Sensibilidade: %.2f× · distância mínima: %.1f dp" : "Sensitivity: %.2f× · minimum distance: %.1f dp",
                    setting.sensitivity, setting.thresholdDp()));
        };
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { updateDistance.run(); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        updateDistance.run();
        final AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(pt ? "Câmera por gestos" : "Camera gestures")
                .setView(content)
                .setNegativeButton(pt ? "Cancelar" : "Cancel", null)
                .setNeutralButton(pt ? "Padrão" : "Reset", (window, which) -> applyCameraSettings(1, false, false))
                .setPositiveButton(pt ? "Salvar" : "Save", (window, which) ->
                        applyCameraSettings(0.35f + sensitivity.getProgress() / 100.0f, invertX.isChecked(), invertY.isChecked()))
                .create();
        cameraDialog = dialog;
        dialog.setOnDismissListener(window -> {
            cameraDialog = null;
            listener.cameraDialogChanged(false);
        });
        listener.cameraDialogChanged(true);
        try {
            dialog.show();
        } catch (RuntimeException error) {
            cameraDialog = null;
            listener.cameraDialogChanged(false);
            throw error;
        }
    }

    private int alpha(float value) {
        return Math.round(255 * Math.max(0.0f, Math.min(1.0f, value * opacity)));
    }

    private void drawButton(Canvas canvas, Button button, boolean held) {
        final float r = held ? button.radius * 0.94f : button.radius;
        fill.setColor(held ? 0xFFFFFFFF : 0xFF0A0C1C);
        fill.setAlpha(alpha(held ? 0.42f : 0.38f));
        canvas.drawCircle(button.x, button.y, r, fill);
        stroke.setColor(button.bit == A ? 0xFF8CE8FF : button.bit == SHAKE ? 0xFFFFDB76
                : button.bit == B ? 0xFFD4ADFF : 0xFFE3EDFF);
        stroke.setAlpha(alpha(held ? 0.95f : 0.62f));
        canvas.drawCircle(button.x, button.y, r, stroke);
        final int ink = held ? 0xFF0A0C1C : 0xFFFFFFFF;
        if (button.bit == B) {
            drawStarBit(canvas, button.x, button.y - r * 0.15f, r * 0.44f, ink);
            text.setColor(ink);
            text.setAlpha(alpha(0.95f));
            final float size = text.getTextSize();
            text.setTextSize(r * 0.42f);
            canvas.drawText("B", button.x, button.y + r * 0.72f, text);
            text.setTextSize(size);
        } else if (button.spinIcon) {
            drawSpin(canvas, button.x, button.y, r * 0.5f, ink);
        } else if (button.homeIcon) {
            drawHome(canvas, button.x, button.y, r * 0.48f, ink);
        } else {
            text.setColor(ink);
            text.setAlpha(alpha(0.95f));
            final float size = text.getTextSize();
            text.setTextSize(Math.min(size, r * (button.label.length() > 1 ? 0.7f : 0.95f)));
            text.getFontMetrics(fontMetrics);
            final Paint.FontMetrics metrics = fontMetrics;
            canvas.drawText(button.label, button.x, button.y - (metrics.ascent + metrics.descent) / 2.0f, text);
            text.setTextSize(size);
        }
    }

    private void drawStarBit(Canvas canvas, float x, float y, float radius, int ink) {
        path.reset();
        for (int i = 0; i < 10; ++i) {
            final double angle = -Math.PI / 2 + i * Math.PI / 5;
            final float r = (i & 1) == 0 ? radius : radius * 0.46f;
            final float px = x + (float)Math.cos(angle) * r;
            final float py = y + (float)Math.sin(angle) * r;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        fill.setColor(ink);
        fill.setAlpha(alpha(0.95f));
        canvas.drawPath(path, fill);
    }

    // Two arrows chasing round a circle: the shake that spins.
    private void drawSpin(Canvas canvas, float x, float y, float r, int ink) {
        line.setColor(ink);
        line.setAlpha(alpha(0.95f));
        line.setStrokeWidth(Math.max(2.0f * dp, r * 0.16f));
        rect.set(x - r, y - r, x + r, y + r);
        for (int k = 0; k < 2; ++k) {
            final float start = k * 180.0f + 20.0f;
            canvas.drawArc(rect, start, 130.0f, false, line);
            final double end = Math.toRadians(start + 130.0f);
            final float ex = (float) (x + r * Math.cos(end));
            final float ey = (float) (y + r * Math.sin(end));
            final double tangent = end + Math.PI / 2.0;
            final float head = r * 0.42f;
            path.reset();
            path.moveTo(ex + (float) (head * Math.cos(tangent + 2.6)), ey + (float) (head * Math.sin(tangent + 2.6)));
            path.lineTo(ex, ey);
            path.lineTo(ex + (float) (head * Math.cos(tangent - 2.6)), ey + (float) (head * Math.sin(tangent - 2.6)));
            canvas.drawPath(path, line);
        }
    }

    private void drawHome(Canvas canvas, float x, float y, float r, int ink) {
        fill.setColor(ink);
        fill.setAlpha(alpha(0.95f));
        path.reset();
        path.moveTo(x, y - r);
        path.lineTo(x + r, y - r * 0.05f);
        path.lineTo(x + r * 0.68f, y - r * 0.05f);
        path.lineTo(x + r * 0.68f, y + r * 0.85f);
        path.lineTo(x + r * 0.18f, y + r * 0.85f);
        path.lineTo(x + r * 0.18f, y + r * 0.3f);
        path.lineTo(x - r * 0.18f, y + r * 0.3f);
        path.lineTo(x - r * 0.18f, y + r * 0.85f);
        path.lineTo(x - r * 0.68f, y + r * 0.85f);
        path.lineTo(x - r * 0.68f, y - r * 0.05f);
        path.lineTo(x - r, y - r * 0.05f);
        path.close();
        canvas.drawPath(path, fill);
    }

    private void drawDpad(Canvas canvas) {
        final float arm = dpadRadius * 0.38f;
        final float r = dpadRadius;
        path.reset();
        path.moveTo(dpadX - arm, dpadY - r);
        path.lineTo(dpadX + arm, dpadY - r);
        path.lineTo(dpadX + arm, dpadY - arm);
        path.lineTo(dpadX + r, dpadY - arm);
        path.lineTo(dpadX + r, dpadY + arm);
        path.lineTo(dpadX + arm, dpadY + arm);
        path.lineTo(dpadX + arm, dpadY + r);
        path.lineTo(dpadX - arm, dpadY + r);
        path.lineTo(dpadX - arm, dpadY + arm);
        path.lineTo(dpadX - r, dpadY + arm);
        path.lineTo(dpadX - r, dpadY - arm);
        path.lineTo(dpadX - arm, dpadY - arm);
        path.close();
        fill.setColor(0xFF0A0C1C);
        fill.setAlpha(alpha(0.38f));
        canvas.drawPath(path, fill);
        stroke.setColor(0xFFFFFFFF);
        stroke.setAlpha(alpha(0.62f));
        canvas.drawPath(path, stroke);
        // The held arms, lit.
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(alpha(0.45f));
        final float inner = arm * 0.82f;
        if ((heldButtons & UP) != 0) canvas.drawRect(dpadX - inner, dpadY - r + 2 * dp, dpadX + inner, dpadY - arm, fill);
        if ((heldButtons & DOWN) != 0) canvas.drawRect(dpadX - inner, dpadY + arm, dpadX + inner, dpadY + r - 2 * dp, fill);
        if ((heldButtons & LEFT) != 0) canvas.drawRect(dpadX - r + 2 * dp, dpadY - inner, dpadX - arm, dpadY + inner, fill);
        if ((heldButtons & RIGHT) != 0) canvas.drawRect(dpadX + arm, dpadY - inner, dpadX + r - 2 * dp, dpadY + inner, fill);
    }

    private void drawStick(Canvas canvas) {
        final boolean held = stickPointer >= 0;
        final float bx = held ? stickBaseX : stickRestX;
        final float by = held ? stickBaseY : stickRestY;
        fill.setColor(0xFF0A0C1C);
        fill.setAlpha(alpha(held ? 0.32f : 0.22f));
        canvas.drawCircle(bx, by, stickRadius, fill);
        stroke.setColor(0xFFFFFFFF);
        stroke.setAlpha(alpha(held ? 0.7f : 0.4f));
        canvas.drawCircle(bx, by, stickRadius, stroke);
        // The knob under the thumb, kept inside the rim.
        float kx = bx;
        float ky = by;
        if (held) {
            final float dx = stickKnobX - bx;
            final float dy = stickKnobY - by;
            final float length = (float) Math.hypot(dx, dy);
            final float k = length > stickRadius ? stickRadius / length : 1.0f;
            kx = bx + dx * k;
            ky = by + dy * k;
        }
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(alpha(held ? 0.72f : 0.45f));
        canvas.drawCircle(kx, ky, stickRadius * 0.44f, fill);
    }
}
