package com.arenax.tournament;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

/** Full-screen read-out for the home notice strip. Opened when the player taps the
 *  scrolling notice bar on the Home tab. Deliberately plain: white background, small
 *  thin black body copy, bold headline — both the headline and body come straight from
 *  what the admin published (appConfig/homeNotice), so there is nothing hardcoded here. */
public class NoticeDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "notice_title";
    public static final String EXTRA_TEXT = "notice_text";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String body = getIntent().getStringExtra(EXTRA_TEXT);
        if (body == null) body = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // Top bar with a back arrow, kept minimal so the notice content is the focus.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(14), dp(18), dp(6));
        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextSize(28);
        back.setTextColor(Color.BLACK);
        back.setTypeface(null, Typeface.BOLD);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView notice = new TextView(this);
        notice.setText("NOTICE");
        notice.setTextSize(11);
        notice.setTextColor(Color.rgb(120, 120, 120));
        notice.setTypeface(null, Typeface.BOLD);
        notice.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        noticeLp.setMarginStart(dp(4));
        top.addView(notice, noticeLp);
        root.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(230, 230, 230));
        root.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // Scrollable body — headline bold, notice copy small/thin, both black on white.
        NestedScrollView scroll = new NestedScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(28));

        if (title != null && !title.trim().isEmpty()) {
            TextView headline = new TextView(this);
            headline.setText(title.trim());
            headline.setTextSize(17);
            headline.setTextColor(Color.BLACK);
            headline.setTypeface(null, Typeface.BOLD);
            headline.setLineSpacing(dp(2), 1f);
            content.addView(headline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            View spacer = new View(this);
            content.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
        }

        TextView bodyView = new TextView(this);
        bodyView.setText(body.trim());
        bodyView.setTextSize(13);
        bodyView.setTextColor(Color.BLACK);
        bodyView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL), Typeface.NORMAL);
        bodyView.setLineSpacing(dp(4), 1f);
        content.addView(bodyView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
