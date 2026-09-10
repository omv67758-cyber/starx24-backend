package com.arenax.tournament;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shown instead of a silent crash so the error can actually be read on-device
 * and copied/screenshotted, without needing adb or Android Studio.
 */
public class CrashActivity extends Activity {
    public static final String EXTRA_TRACE = "trace";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String trace = getIntent() != null ? getIntent().getStringExtra(EXTRA_TRACE) : null;
        if (trace == null) trace = "Unknown crash (no stack trace captured).";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 23, 32));
        int pad = dp(16);
        root.setPadding(pad, dp(40), pad, pad);

        TextView title = new TextView(this);
        title.setText("STARX24 crashed");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Copy or screenshot this and send it to support:");
        subtitle.setTextColor(Color.rgb(180, 185, 195));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        TextView traceView = new TextView(this);
        traceView.setText(trace);
        traceView.setTextColor(Color.rgb(255, 120, 120));
        traceView.setTextIsSelectable(true);
        traceView.setTextSize(11);
        traceView.setTypeface(android.graphics.Typeface.MONOSPACE);
        traceView.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(traceView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        android.widget.Button close = new android.widget.Button(this);
        close.setText("CLOSE APP");
        close.setOnClickListener(v -> finishAffinity());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.topMargin = dp(12);
        root.addView(close, closeParams);

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
