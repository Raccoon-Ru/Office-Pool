package com.officepool;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ThreadLocalRandom;

final class PoolMotion {
    private static final int[] BALL_COLORS = {
        0xffE5BA39, 0xff2469B1, 0xffB73B37, 0xff754093,
        0xffD97833, 0xff368358, 0xff792D38, 0xff171C19
    };
    private PoolMotion() {}

    static boolean enabled(Context context) {
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1) != 0;
    }

    static TextView ball(Context context) {
        TextView view = new TextView(context);
        setBallNumber(view, 8);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(0xff151917);
        view.setTextSize(16);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setClipToOutline(true);
        return view;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static void setBallNumber(TextView view, int number) {
        int color = BALL_COLORS[(number - 1) % BALL_COLORS.length];
        boolean striped = number > 8;
        GradientDrawable circle = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                striped ? new int[] {0xffFFF9E9, 0xffCFC8B3} : new int[] {color, color & 0xffBFBFBF});
        circle.setShape(GradientDrawable.OVAL);
        GradientDrawable numberCircle = new GradientDrawable();
        numberCircle.setColor(0xffFFF9E9);
        numberCircle.setShape(GradientDrawable.OVAL);
        GradientDrawable stripe = new GradientDrawable();
        stripe.setColor(color);
        LayerDrawable face = new LayerDrawable(striped
                ? new GradientDrawable[] {circle, stripe, numberCircle}
                : new GradientDrawable[] {circle, numberCircle});
        if (striped) face.setLayerInset(1, 0, dp(view, 10), 0, dp(view, 10));
        int inset = dp(view, 8);
        face.setLayerInset(striped ? 2 : 1, inset, inset, inset, inset);
        view.setBackground(face);
        view.setText(String.valueOf(number));
    }

    static View cue(Context context) {
        View cue = new View(context);
        GradientDrawable shaft = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xff5A3826, 0xffBF874D, 0xffF0D9A5});
        shaft.setCornerRadius(dp(cue, 3));
        cue.setBackground(shaft);
        cue.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return cue;
    }

    static final class PullScroll extends ScrollView {
        interface Refresh {
            void start(Runnable finished);
        }

        private final int threshold;
        private final TextView ball;
        private final View cue;
        private final Refresh refresh;
        private float startX, startY, distance;
        private boolean pulling, refreshing, shotFinished, refreshFinished;

        PullScroll(Context context, int threshold, TextView ball, View cue, Refresh refresh) {
            super(context);
            this.threshold = threshold;
            this.ball = ball;
            this.cue = cue;
            this.refresh = refresh;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pulling = !refreshing && getScrollY() == 0;
                    startX = event.getX();
                    startY = event.getY();
                    distance = 0;
                    if (pulling) {
                        ball.animate().cancel();
                        cue.animate().cancel();
                        setBallNumber(ball, ThreadLocalRandom.current().nextInt(1, 16));
                        ball.setTranslationX(0);
                        ball.setTranslationY(0);
                        ball.setRotation(0);
                        cue.setAlpha(1);
                        cue.setTranslationY(0);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!pulling) break;
                    float downward = event.getY() - startY;
                    if (downward < 0 || Math.abs(event.getX() - startX) > downward) {
                        pulling = false;
                        hideShot();
                        break;
                    }
                    distance = Math.min(downward, threshold * 1.5f);
                    if (distance > threshold / 6f) {
                        ball.animate().cancel();
                        ball.setAlpha(1);
                        ball.setVisibility(VISIBLE);
                        cue.setVisibility(VISIBLE);
                        cue.setTranslationX(cueAtBall());
                        if (enabled(getContext())) {
                            ball.setTranslationY(distance / 2f);
                            cue.setTranslationY(distance / 2f);
                            cue.setTranslationX(cueAtBall() - distance / 2f);
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (pulling && distance >= threshold) {
                        refreshing = true;
                        playShot();
                    } else if (!refreshing) {
                        hideShot();
                    }
                    pulling = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    pulling = false;
                    if (!refreshing) hideShot();
                    break;
                default:
                    break;
            }
            return super.dispatchTouchEvent(event);
        }

        private float cueAtBall() {
            return -(cue.getLayoutParams().width + ball.getLayoutParams().width) / 2f;
        }

        private void playShot() {
            shotFinished = !enabled(getContext());
            refreshFinished = false;
            if (!shotFinished) {
                cue.animate().translationX(cueAtBall()).setDuration(105).withEndAction(() -> {
                    cue.animate().alpha(0).setDuration(140).start();
                    ball.animate().translationX(dp(ball, 112)).rotation(360).setDuration(285)
                            .withEndAction(() -> {
                                shotFinished = true;
                                settle();
                            }).start();
                }).start();
            }
            refresh.start(() -> {
                refreshFinished = true;
                settle();
            });
        }

        private void settle() {
            if (!shotFinished || !refreshFinished) return;
            refreshing = false;
            if (!enabled(getContext())) {
                hideShot();
                return;
            }
            ball.animate().alpha(0).setDuration(210).withEndAction(this::hideShot).start();
        }

        private void hideShot() {
            ball.setVisibility(GONE);
            cue.setVisibility(GONE);
        }
    }
}
