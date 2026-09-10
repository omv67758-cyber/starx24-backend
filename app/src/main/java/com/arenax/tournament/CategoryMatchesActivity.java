package com.arenax.tournament;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.adapter.TournamentAdapter;
import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.model.Tournament;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CategoryMatchesActivity extends AppCompatActivity {
    private final List<Tournament> matches = new ArrayList<>();
    private final List<Tournament> allMatches = new ArrayList<>();
    private ValueEventListener listener;
    private String categoryId;
    private boolean adminMode;
    private TournamentAdapter adapter;
    private TextView empty;
    private String activeFilter = "UPCOMING";
    private ImageView matchPreview;
    private EditText matchImageUrlField;
    private Uri selectedMatchImage;
    private static final int REQ_MATCH_IMAGE = 8001;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        categoryId = getIntent().getStringExtra("categoryId");
        if (categoryId == null) categoryId = "";
        String categoryTitle = getIntent().getStringExtra("categoryTitle");
        if (categoryTitle == null || categoryTitle.trim().isEmpty()) categoryTitle = "CATEGORY MATCHES";
        adminMode = getIntent().getBooleanExtra("adminMode", false);
        buildScreen(categoryTitle);
        listenForMatches();
    }

    private void buildScreen(String categoryTitle) {
        getWindow().setStatusBarColor(Color.rgb(8, 3, 6));
        getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 3, 6));
        root.setPadding(dp(0), getStatusBarHeight(), dp(0), dp(0));

        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(18), dp(18), dp(18), dp(12));
        top.setBackgroundResource(R.drawable.bg_match_header);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 34, Color.WHITE, true);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(42), dp(54)));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = label(adminMode ? "ADMIN MATCH CONTROL" : "LIVE TOURNAMENTS", 10, Color.rgb(237, 47, 62), true);
        TextView title = label(categoryTitle, 21, Color.WHITE, true);
        heading.addView(kicker); heading.addView(title);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (adminMode) {
            MaterialButton add = new MaterialButton(this);
            add.setText("+ ADD MATCH");
            add.setTextColor(Color.WHITE);
            add.setTextSize(11);
            add.setTypeface(null, android.graphics.Typeface.BOLD);
            add.setAllCaps(false);
            add.setCornerRadius(dp(14));
            add.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(237, 47, 62)));
            add.setElevation(0f);
            add.setInsetTop(0);
            add.setInsetBottom(0);
            add.setOnClickListener(v -> showAddMatchDialog(categoryTitle));
            top.addView(add, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView ongoing = matchTab("Ongoing");
        TextView upcoming = matchTab("Upcoming");
        TextView resulted = matchTab("Resulted");
        tabs.addView(ongoing, new LinearLayout.LayoutParams(0, dp(50), 1));
        tabs.addView(upcoming, new LinearLayout.LayoutParams(0, dp(50), 1));
        tabs.addView(resulted, new LinearLayout.LayoutParams(0, dp(50), 1));
        View.OnClickListener tabClick = v -> {
            activeFilter = v == ongoing ? "ONGOING" : (v == upcoming ? "UPCOMING" : "COMPLETED");
            styleMatchTabs(ongoing, upcoming, resulted);
            refreshFilteredMatches();
        };
        ongoing.setOnClickListener(tabClick); upcoming.setOnClickListener(tabClick); resulted.setOnClickListener(tabClick);
        root.addView(tabs);
        styleMatchTabs(ongoing, upcoming, resulted);
        empty = label("No matches in this category yet.", 15, Color.LTGRAY, false);
        empty.setGravity(Gravity.CENTER);
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TournamentAdapter(matches, tournament -> {
            Intent intent = new Intent(this, TournamentDetailActivity.class);
            intent.putExtra("tournament", tournament);
            startActivity(intent);
        });
        if (adminMode) {
            adapter.setOnDeleteClick(this::confirmDeleteMatch);
        }
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(empty, new LinearLayout.LayoutParams(-1, dp(90)));
        setContentView(root);
    }

    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : dp(24);
    }

    private void listenForMatches() {
        if (!FirebaseRepository.isReady(this)) { toast("Live match data is unavailable right now."); return; }
        listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                matches.clear();
                allMatches.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    try {
                        Tournament match = Tournament.fromMap(child.getKey(), (Map<String, Object>) child.getValue());
                        if (match != null && categoryId.equals(match.getCategoryId()) && match.isActive()) allMatches.add(match);
                    } catch (RuntimeException ignored) { }
                }
                refreshFilteredMatches();
            }
            @Override public void onCancelled(DatabaseError error) { toast("Could not load matches: " + error.getMessage()); }
        };
        FirebaseRepository.tournaments().addValueEventListener(listener);
    }

    private TextView matchTab(String text) {
        TextView tab = label(text, 17, Color.WHITE, false);
        tab.setGravity(Gravity.CENTER);
        return tab;
    }

    private void styleMatchTabs(TextView ongoing, TextView upcoming, TextView resulted) {
        ongoing.setTextColor("ONGOING".equals(activeFilter) ? Color.WHITE : Color.rgb(180, 200, 220));
        upcoming.setTextColor("UPCOMING".equals(activeFilter) ? Color.WHITE : Color.rgb(180, 200, 220));
        resulted.setTextColor("COMPLETED".equals(activeFilter) ? Color.WHITE : Color.rgb(180, 200, 220));
        ongoing.setTypeface(null, "ONGOING".equals(activeFilter) ? Typeface.BOLD : Typeface.NORMAL);
        upcoming.setTypeface(null, "UPCOMING".equals(activeFilter) ? Typeface.BOLD : Typeface.NORMAL);
        resulted.setTypeface(null, "COMPLETED".equals(activeFilter) ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void refreshFilteredMatches() {
        matches.clear();
        for (Tournament item : allMatches) {
            String status = item.getStatus() == null ? "UPCOMING" : item.getStatus().toUpperCase(java.util.Locale.ROOT);
            boolean include = "COMPLETED".equals(activeFilter)
                    ? ("COMPLETED".equals(status) || "RESULTED".equals(status))
                    : activeFilter.equals(status);
            if (include) matches.add(item);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) empty.setVisibility(matches.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showAddMatchDialog(String categoryTitle) {
        selectedMatchImage = null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(4), dp(22), dp(8));

        form.addView(sectionLabel("MATCH IMAGE"));
        MaterialButton chooseImage = new MaterialButton(this);
        chooseImage.setText("SELECT PNG/JPG FROM DEVICE");
        chooseImage.setTextColor(Color.WHITE);
        chooseImage.setAllCaps(false);
        chooseImage.setTypeface(null, Typeface.BOLD);
        chooseImage.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(237, 47, 62)));
        chooseImage.setCornerRadius(dp(14));
        chooseImage.setElevation(0f);
        chooseImage.setInsetTop(0);
        chooseImage.setInsetBottom(0);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-1, dp(48));
        chooseParams.topMargin = dp(6);
        chooseParams.bottomMargin = dp(10);
        chooseImage.setLayoutParams(chooseParams);
        chooseImage.setOnClickListener(v -> chooseMatchImage());
        form.addView(chooseImage);

        matchPreview = new ImageView(this);
        matchPreview.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(140)));
        matchPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        matchPreview.setBackgroundResource(R.drawable.bg_game_card_gold);
        form.addView(matchPreview);

        matchImageUrlField = premiumField("Or paste image URL instead");
        form.addView(matchImageUrlField);

        form.addView(sectionLabel("MATCH NAME"));
        EditText title = premiumField("Match name");
        form.addView(title);

        form.addView(sectionLabel("SCHEDULE"));
        EditText date = premiumField("Date (e.g. 20 SEP 2026)");
        EditText time = premiumField("Time (e.g. 08:30 PM)");
        form.addView(row(date, time));

        form.addView(sectionLabel("PRIZE & SLOTS"));
        EditText prize = premiumField("Prize information (e.g. Diamonds / Bundle)");
        form.addView(prize);
        EditText prizePool = premiumNumberField("Prize pool coins");
        EditText entryFee = premiumNumberField("Entry coins (demo)");
        form.addView(row(prizePool, entryFee));
        EditText perKill = premiumNumberField("Per kill coins");
        EditText gameType = premiumField("Game type (BATTLE ROYALE / CLASH SQUAD)");
        form.addView(row(perKill, gameType));
        EditText slots = premiumNumberField("Total slots");
        EditText teamSize = premiumNumberField("Team size");
        form.addView(row(slots, teamSize));

        form.addView(sectionLabel("MATCH TYPE & MAP"));
        EditText matchType = premiumField("Match type (SOLO / DUO / SQUAD)");
        EditText map = premiumField("Map name");
        form.addView(row(matchType, map));

        form.addView(sectionLabel("DESCRIPTION & RULES"));
        EditText description = premiumField("Match description (optional)");
        form.addView(description);
        EditText rules = premiumField("Rules (optional)");
        form.addView(rules);

        form.addView(sectionLabel("ROOM (OPTIONAL)"));
        EditText room = premiumField("Room ID (optional)");
        form.addView(room);

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(form);

        Dialog dialog = buildStickyFormDialog(
                "Add match",
                "Publish a new match live to Firebase for " + categoryTitle + ". Add a banner image, then save.",
                scroll,
                "SAVE MATCH",
                () -> {
                    String name = value(title);
                    if (name.isEmpty()) { title.setError("Enter match name"); return; }
                    String prizeValue = value(prize).isEmpty() ? "Free entry" : value(prize);
                    int slotsValue = integer(slots, 48);
                    int teamValue = Math.max(1, integer(teamSize, 1));
                    String dateValue = value(date).isEmpty() ? "TBA" : value(date).toUpperCase(java.util.Locale.ROOT);
                    String timeValue = value(time).isEmpty() ? "TBA" : value(time).toUpperCase(java.util.Locale.ROOT);
                    long startAt = parseDateTime(dateValue, timeValue);
                    String matchTypeValue = value(matchType).isEmpty() ? "SOLO" : value(matchType).toUpperCase(java.util.Locale.ROOT);
                    String mapValue = value(map).isEmpty() ? "TBA" : value(map);
                    String descriptionValue = value(description).isEmpty()
                            ? "Live match in " + categoryTitle : value(description);

                    if (selectedMatchImage != null) {
                        Toast.makeText(this, "Uploading image securely…", Toast.LENGTH_SHORT).show();
                        com.arenax.tournament.data.ImgBbUploader.uploadImage(this, selectedMatchImage,
                                new com.arenax.tournament.data.StorageUploader.Callback() {
                                    @Override public void onSuccess(String fileUrl) {
                                        saveMatch(categoryTitle, name, fileUrl, descriptionValue, dateValue, timeValue,
                                                startAt, prizeValue, slotsValue, teamValue, mapValue, matchTypeValue,
                                                value(rules), value(room), integer(entryFee, 0), integer(prizePool, 0), integer(perKill, 0), value(gameType));
                                    }
                                    @Override public void onFailure(String message) {
                                        toast("Image upload failed: " + message);
                                    }
                                });
                    } else {
                        saveMatch(categoryTitle, name, value(matchImageUrlField), descriptionValue, dateValue, timeValue,
                                startAt, prizeValue, slotsValue, teamValue, mapValue, matchTypeValue,
                                value(rules), value(room), integer(entryFee, 0), integer(prizePool, 0), integer(perKill, 0), value(gameType));
                    }
                });
        activeMatchDialog = dialog;
        dialog.show();
    }

    private Dialog activeMatchDialog;

    private void saveMatch(String categoryTitle, String name, String bannerUrl, String descriptionValue,
                            String dateValue, String timeValue, long startAt, String prizeValue, int slotsValue,
                            int teamValue, String mapValue, String matchTypeValue, String rulesValue, String roomValue,
                            int entryFeeCoins, int prizePoolCoins, int perKillCoins, String gameTypeValue) {
        Tournament match = new Tournament(null, name, categoryTitle, categoryId, bannerUrl, descriptionValue,
                dateValue, timeValue, startAt, 0L, 0L, prizeValue, slotsValue, 0,
                teamValue, mapValue, matchTypeValue, rulesValue, "", roomValue, "",
                false, "", "UPCOMING", "OPEN", true, Color.rgb(237, 47, 62));
        match.setCoinDetails(entryFeeCoins, prizePoolCoins, perKillCoins, gameTypeValue);
        FirebaseRepository.saveTournament(match)
                .addOnSuccessListener(done -> {
                    mirrorToMessengerApp(match, categoryTitle, startAt, roomValue);
                    toast("Match saved in " + categoryTitle);
                    if (activeMatchDialog != null) activeMatchDialog.dismiss();
                })
                .addOnFailureListener(error -> toast("Save failed: " + error.getMessage()));
    }

    /**
     * Bridges this "Add match" form (which actually saves a Tournament, see
     * FirebaseRepository.saveTournament above) into the /matches node so the
     * android-messenger-app's "Manage Matches" screen (its Match.java model)
     * picks it up immediately without a separate admin step. Field names below
     * match android-messenger-app/app/src/main/java/com/tournament/messanger/Match.java
     * exactly: tournamentTitle, name, category, imageUrl, scheduledAt, roomId,
     * roomPassword, roomReleased, status, participants.
     */
    private void mirrorToMessengerApp(Tournament match, String categoryTitle, long startAt, String roomValue) {
        java.util.Map<String, Object> mirror = new java.util.HashMap<>();
        mirror.put("tournamentTitle", match.getTitle());
        mirror.put("name", match.getTitle());
        mirror.put("category", categoryTitle);
        mirror.put("imageUrl", match.getBannerUrl());
        mirror.put("scheduledAt", startAt);
        mirror.put("roomId", roomValue == null ? "" : roomValue);
        mirror.put("roomPassword", "");
        mirror.put("roomReleased", false);
        mirror.put("status", "UPCOMING");
        mirror.put("participants", new java.util.HashMap<String, Object>());
        FirebaseRepository.matches().push().setValue(mirror);
    }

    private void chooseMatchImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_MATCH_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MATCH_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedMatchImage = data.getData();
            if (matchPreview != null) matchPreview.setImageURI(selectedMatchImage);
        }
    }

    /** Two fields side by side — matches the reference form's Date/Time, Slots/Type rows. */
    private LinearLayout row(View left, View right) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        left.setLayoutParams(lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = dp(6);
        right.setLayoutParams(rp);
        r.addView(left);
        r.addView(right);
        return r;
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(166, 171, 184));
        label.setTextSize(11f);
        label.setTypeface(null, Typeface.BOLD);
        label.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
        label.setLayoutParams(params);
        return label;
    }

    /**
     * Dialog with a fixed header, a height-capped scrollable form, and a footer with
     * CANCEL / positive buttons that always stay visible — no more Save button
     * disappearing off-screen on long forms. Styled in the app's red brand theme.
     */
    private Dialog buildStickyFormDialog(String title, String subtitle, View scrollableForm,
                                          String positiveLabel, Runnable onPositive) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_card);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(22), dp(20), dp(22), dp(16));
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(19f);
        titleView.setTypeface(null, Typeface.BOLD);
        header.addView(titleView);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(Color.rgb(166, 171, 184));
            subtitleView.setTextSize(12.5f);
            subtitleView.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(6);
            subtitleView.setLayoutParams(sp);
            header.addView(subtitleView);
        }
        root.addView(header);
        root.addView(matchDialogDivider());

        int maxFormHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.5);
        LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(-1, maxFormHeight);
        scrollableForm.setLayoutParams(formParams);
        root.addView(scrollableForm);
        root.addView(matchDialogDivider());

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(16), dp(12), dp(16), dp(14));

        MaterialButton cancelButton = new MaterialButton(this);
        cancelButton.setText("CANCEL");
        cancelButton.setTextColor(Color.rgb(166, 171, 184));
        cancelButton.setAllCaps(false);
        cancelButton.setBackgroundResource(R.drawable.bg_dialog_button_outline);
        cancelButton.setElevation(0f);
        cancelButton.setInsetTop(0);
        cancelButton.setInsetBottom(0);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-2, dp(46));
        cancelParams.rightMargin = dp(10);
        cancelButton.setLayoutParams(cancelParams);
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        footer.addView(cancelButton);

        MaterialButton positiveButton = new MaterialButton(this);
        positiveButton.setText(positiveLabel);
        positiveButton.setTextColor(Color.WHITE);
        positiveButton.setAllCaps(false);
        positiveButton.setTypeface(null, Typeface.BOLD);
        positiveButton.setBackgroundResource(R.drawable.bg_button_gradient);
        positiveButton.setElevation(0f);
        positiveButton.setInsetTop(0);
        positiveButton.setInsetBottom(0);
        positiveButton.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(46)));
        positiveButton.setOnClickListener(v -> onPositive.run());
        footer.addView(positiveButton);

        root.addView(footer);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            window.setLayout((int) (metrics.widthPixels * 0.92), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(true);
        return dialog;
    }

    private View matchDialogDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(38, 54, 94));
        divider.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return divider;
    }

    /** Admin-only: confirm before permanently removing a match from this category. */
    private void confirmDeleteMatch(Tournament tournament) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + tournament.getTitle() + "?")
                .setMessage("This permanently removes the match from Firebase. Players will no longer see it.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (dialog, which) ->
                        FirebaseRepository.deleteTournament(tournament.getId())
                                .addOnSuccessListener(done -> {
                                    toast("Match deleted.");
                                    FirebaseRepository.logActivity("TOURNAMENT_DELETED", tournament.getTitle(), "");
                                })
                                .addOnFailureListener(error -> toast("Delete failed: " + error.getMessage())))
                .show();
    }

    private EditText premiumField(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(150, 150, 158));
        input.setSingleLine(true);
        input.setBackgroundResource(R.drawable.bg_input);
        int hPad = dp(16), vPad = dp(14);
        input.setPadding(hPad, vPad, hPad, vPad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        input.setLayoutParams(params);
        return input;
    }

    private EditText premiumNumberField(String hint) {
        EditText input = premiumField(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setTextColor(Color.WHITE); return b; }
    private TextView label(String text, int size, int color, boolean bold) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); v.setTypeface(null, bold ? 1 : 0); return v; }
    private LinearLayout.LayoutParams margin(int width, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, dp(50)); p.topMargin = top; return p; }
    private String value(EditText e) { return e.getText() == null ? "" : e.getText().toString().trim(); }
    private int integer(EditText e, int fallback) { try { return Integer.parseInt(value(e)); } catch (NumberFormatException ignored) { return fallback; } }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private long parseDateTime(String date, String time) {
        if (date == null || time == null || date.trim().isEmpty() || time.trim().isEmpty()
                || "TBA".equalsIgnoreCase(date) || "TBA".equalsIgnoreCase(time)) return 0L;
        String combined = date.trim() + " " + time.trim();
        String[] formats = {"dd MMM yyyy hh:mm a", "dd MMM yyyy HH:mm"};
        for (String format : formats) {
            try {
                java.util.Date parsed = new java.text.SimpleDateFormat(format, java.util.Locale.ENGLISH).parse(combined);
                if (parsed != null) return parsed.getTime();
            } catch (java.text.ParseException ignored) {
                // Keep the record publishable even if the optional date can't be parsed.
            }
        }
        return 0L;
    }

    @Override protected void onDestroy() { if (listener != null) FirebaseRepository.tournaments().removeEventListener(listener); super.onDestroy(); }
}
