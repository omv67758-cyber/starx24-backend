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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.adapter.BannerAdapter;
import com.arenax.tournament.adapter.GameModeAdapter;
import com.arenax.tournament.adapter.TournamentAdapter;
import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.model.Banner;
import com.arenax.tournament.model.GameMode;
import com.arenax.tournament.model.Tournament;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.auth.FirebaseAuth;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AdminActivity extends AppCompatActivity {
    private TournamentAdapter adapter;
    private final List<Tournament> tournaments = new ArrayList<>();
    private final List<GameMode> gameModes = new ArrayList<>();
    private ValueEventListener tournamentListener;
    private ValueEventListener gameModeListener;
    private ValueEventListener configListener;
    private boolean applyingConfig;
    private Dialog categoryDialog;
    private ImageView categoryPreview;
    private EditText categoryImageUrl;
    private Uri selectedCategoryImage;
    private final List<Banner> banners = new ArrayList<>();
    private ValueEventListener bannerListener;
    private AlertDialog bannerDialog;
    private ImageView bannerPreview;
    private EditText bannerImageUrlField;
    private Uri selectedBannerImage;
    private AlertDialog logoDialog;
    private ImageView logoPreview;
    private Uri selectedLogoImage;
    private ImageView tournamentPreview;
    private EditText tournamentImageUrlField;
    private Uri selectedTournamentImage;
    private android.widget.ScrollView dashboardScroll;
    private android.widget.ScrollView workspaceScroll;
    private LinearLayout workspaceContent;
    private String adminRole = "SUPER_ADMIN";

    /** A1 RBAC — which workspace pages each role may open. Dashboard is always visible to every role. */
    private static final Map<String, java.util.Set<String>> ROLE_PAGES = new HashMap<>();
    static {
        java.util.Set<String> superAdmin = new java.util.HashSet<>(java.util.Arrays.asList(
                "dashboard", "users", "battles", "tournaments", "esports", "category", "teams", "matches",
                "results", "disputes", "reports", "faq", "notice", "analytics", "activity", "settings", "roles"));
        java.util.Set<String> manager = new java.util.HashSet<>(java.util.Arrays.asList(
                "dashboard", "battles", "tournaments", "esports", "category", "teams", "matches", "results", "activity"));
        java.util.Set<String> support = new java.util.HashSet<>(java.util.Arrays.asList(
                "dashboard", "faq", "notice", "battles", "tournaments"));
        java.util.Set<String> moderator = new java.util.HashSet<>(java.util.Arrays.asList(
                "dashboard", "reports", "disputes", "users", "activity"));
        ROLE_PAGES.put("SUPER_ADMIN", superAdmin);
        ROLE_PAGES.put("TOURNAMENT_MANAGER", manager);
        ROLE_PAGES.put("SUPPORT_AGENT", support);
        ROLE_PAGES.put("MODERATOR", moderator);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin);
        String roleExtra = getIntent().getStringExtra("adminRole");
        if (roleExtra != null && ROLE_PAGES.containsKey(roleExtra)) adminRole = roleExtra;
        dashboardScroll = findViewById(R.id.admin_dashboard_scroll);
        workspaceScroll = findViewById(R.id.admin_workspace_scroll);
        workspaceContent = findViewById(R.id.admin_workspace_content);
        setupNavigation();
        applyRolePermissions();

        RecyclerView list = findViewById(R.id.admin_tournament_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TournamentAdapter(tournaments, this::showTournamentActions);
        adapter.setOnDeleteClick(this::showDeleteConfirm);
        list.setAdapter(adapter);

        RecyclerView categoryList = findViewById(R.id.admin_game_mode_list);
        categoryList.setLayoutManager(new GridLayoutManager(this, 2));
        GameModeAdapter categoryAdapter = new GameModeAdapter(gameModes, true, this::confirmDeleteGameMode,
                this::showCategoryMatches);
        categoryAdapter.setOnEditClick(this::showEditCategoryDialog);
        categoryList.setAdapter(categoryAdapter);

        RecyclerView bannerList = findViewById(R.id.admin_banner_list);
        bannerList.setLayoutManager(new LinearLayoutManager(this));
        bannerList.setAdapter(new BannerAdapter(banners, true, this::confirmDeleteBanner));

        findViewById(R.id.admin_back).setOnClickListener(v -> finish());
        findViewById(R.id.admin_add_tournament).setOnClickListener(v -> showAddTournamentDialog("", ""));
        findViewById(R.id.admin_add_category).setOnClickListener(v -> showAddCategoryDialog());
        findViewById(R.id.admin_add_banner).setOnClickListener(v -> showAddBannerDialog());
        findViewById(R.id.admin_send_notice).setOnClickListener(v -> publishNotice());
        findViewById(R.id.admin_global_search).setOnClickListener(v -> showGlobalSearchDialog());
        findViewById(R.id.admin_home_notice_publish).setOnClickListener(v -> publishHomeNotice());

    }

    /** Hides every nav row the signed-in admin's role isn't allowed to open. Read-only "battles/tournaments"
     *  access for Support Agent is enforced by keeping create/delete actions role-gated where those live too. */
    private void applyRolePermissions() {
        java.util.Set<String> allowed = ROLE_PAGES.getOrDefault(adminRole, ROLE_PAGES.get("SUPPORT_AGENT"));
        Map<Integer, String> idToPage = new HashMap<>();
        idToPage.put(R.id.admin_nav_users, "users");
        idToPage.put(R.id.admin_nav_battles, "battles");
        idToPage.put(R.id.admin_nav_tournaments, "tournaments");
        idToPage.put(R.id.admin_nav_esports, "esports");
        idToPage.put(R.id.admin_nav_category, "category");
        idToPage.put(R.id.admin_nav_teams, "teams");
        idToPage.put(R.id.admin_nav_matches, "matches");
        idToPage.put(R.id.admin_nav_results, "results");
        idToPage.put(R.id.admin_nav_disputes, "disputes");
        idToPage.put(R.id.admin_nav_reports, "reports");
        idToPage.put(R.id.admin_nav_faq, "faq");
        idToPage.put(R.id.admin_nav_notice, "notice");
        idToPage.put(R.id.admin_nav_analytics, "analytics");
        idToPage.put(R.id.admin_nav_activity, "activity");
        for (Map.Entry<Integer, String> entry : idToPage.entrySet()) {
            View row = findViewById(entry.getKey());
            if (row != null) row.setVisibility(allowed.contains(entry.getValue()) ? View.VISIBLE : View.GONE);
        }
        View rolesRow = findViewById(R.id.admin_nav_roles);
        if (rolesRow != null) rolesRow.setVisibility("SUPER_ADMIN".equals(adminRole) ? View.VISIBLE : View.GONE);
    }

    private void watchAppConfig() {
        configListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                applyingConfig = true;
                SwitchMaterial maintenance = findViewById(R.id.admin_maintenance);
                Boolean enabled = snapshot.child("maintenance").getValue(Boolean.class);
                maintenance.setChecked(Boolean.TRUE.equals(enabled));

                EditText noticeTitleInput = findViewById(R.id.admin_home_notice_title_input);
                EditText noticeInput = findViewById(R.id.admin_home_notice_input);
                SwitchMaterial noticeSwitch = findViewById(R.id.admin_home_notice_switch);
                if (noticeTitleInput != null && !noticeTitleInput.isFocused()) {
                    String noticeTitle = snapshot.child("homeNotice").child("title").getValue(String.class);
                    noticeTitleInput.setText(noticeTitle == null ? "" : noticeTitle);
                }
                if (noticeInput != null && !noticeInput.isFocused()) {
                    String noticeText = snapshot.child("homeNotice").child("text").getValue(String.class);
                    noticeInput.setText(noticeText == null ? "" : noticeText);
                }
                if (noticeSwitch != null) {
                    Boolean noticeEnabled = snapshot.child("homeNotice").child("enabled").getValue(Boolean.class);
                    noticeSwitch.setChecked(Boolean.TRUE.equals(noticeEnabled));
                }
                applyingConfig = false;
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(AdminActivity.this, "Could not load app settings: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        };
        FirebaseRepository.appConfig().addValueEventListener(configListener);
    }

    @Override
    protected void onStart() {
        super.onStart();
        tournamentListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                tournaments.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Tournament tournament = Tournament.fromMap(child.getKey(),
                            (java.util.Map<String, Object>) child.getValue());
                    if (tournament != null) tournaments.add(tournament);
                }
                adapter.notifyDataSetChanged();
                TextView battleStat = findViewById(R.id.admin_stat_battles);
                TextView prizeStat = findViewById(R.id.admin_stat_prize);
                if (battleStat != null) battleStat.setText(tournaments.size() + "\nBATTLES");
                if (prizeStat != null) prizeStat.setText("FREE\nENTRY");
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(AdminActivity.this, "Could not load tournaments: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        };
        FirebaseRepository.tournaments().addValueEventListener(tournamentListener);
        gameModeListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                gameModes.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Object raw = child.getValue();
                    if (!(raw instanceof Map)) continue;
                    GameMode mode = GameMode.fromMap(child.getKey(), (Map<String, Object>) raw);
                    if (mode != null) gameModes.add(mode);
                }
                RecyclerView categoryList = findViewById(R.id.admin_game_mode_list);
                if (categoryList.getAdapter() != null) categoryList.getAdapter().notifyDataSetChanged();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(AdminActivity.this, "Could not load categories: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        };
        FirebaseRepository.gameModes().addValueEventListener(gameModeListener);
        bannerListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                banners.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Object raw = child.getValue();
                    if (!(raw instanceof Map)) continue;
                    Banner banner = Banner.fromMap(child.getKey(), (Map<String, Object>) raw);
                    if (banner != null) banners.add(banner);
                }
                java.util.Collections.sort(banners, java.util.Comparator.comparingInt(Banner::getDisplayOrder));
                RecyclerView bannerList = findViewById(R.id.admin_banner_list);
                if (bannerList.getAdapter() != null) bannerList.getAdapter().notifyDataSetChanged();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(AdminActivity.this, "Could not load banners: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        };
        FirebaseRepository.banners().addValueEventListener(bannerListener);
        FirebaseRepository.users().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                TextView stat = findViewById(R.id.admin_stat_users);
                if (stat != null) stat.setText(snapshot.getChildrenCount() + "\nUSERS");
            }
            @Override public void onCancelled(DatabaseError error) { }
        });
        watchAppConfig();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (tournamentListener != null) FirebaseRepository.tournaments().removeEventListener(tournamentListener);
        if (gameModeListener != null) FirebaseRepository.gameModes().removeEventListener(gameModeListener);
        if (bannerListener != null) FirebaseRepository.banners().removeEventListener(bannerListener);
        if (configListener != null) FirebaseRepository.appConfig().removeEventListener(configListener);
    }

    private void showCategoryMatches(GameMode mode) {
        Intent page = new Intent(this, CategoryMatchesActivity.class);
        page.putExtra("categoryId", mode.getId());
        page.putExtra("categoryTitle", mode.getTitle());
        page.putExtra("adminMode", true);
        startActivity(page);
        return;
        /*
        FirebaseRepository.tournaments().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                StringBuilder matches = new StringBuilder();
                int count = 0;
                for (DataSnapshot child : snapshot.getChildren()) {
                    Tournament tournament = Tournament.fromMap(child.getKey(),
                            (Map<String, Object>) child.getValue());
                    if (tournament != null && mode.getId().equals(tournament.getCategoryId())) {
                        count++;
                        matches.append("• ").append(tournament.getTitle())
                                .append(" | ").append(tournament.getDate()).append(" ")
                                .append(tournament.getTime()).append(" | Prize ")
                                .append(tournament.getPrizeInfo()).append(" | FREE ENTRY").append("\n");
                    }
                }
                if (count == 0) matches.append("No matches published in this category yet.");
                new MaterialAlertDialogBuilder(AdminActivity.this)
                        .setTitle(mode.getTitle() + " • MATCHES")
                        .setMessage(matches.toString())
                        .setNegativeButton("CLOSE", null)
                        .setPositiveButton("OPEN MATCH FORM", (dialog, which) ->
                                showAddTournamentDialog(mode.getId(), mode.getTitle()))
                        .show();
            }
            @Override public void onCancelled(DatabaseError error) {
                Toast.makeText(AdminActivity.this, "Could not load category matches: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        */
    }

    private void showAddTournamentDialog(String categoryId, String categoryTitle) {
        selectedTournamentImage = null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(4), dp(24), dp(8));

        form.addView(fieldLabel("MATCH IMAGE"));
        MaterialButton chooseImage = new MaterialButton(this);
        chooseImage.setText("SELECT PNG/JPG FROM DEVICE");
        chooseImage.setTextColor(getColor(R.color.text_primary));
        chooseImage.setBackgroundResource(R.drawable.bg_button_gradient);
        chooseImage.setMaxLines(1);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chooseParams.topMargin = dp(6);
        chooseParams.bottomMargin = dp(10);
        chooseImage.setLayoutParams(chooseParams);
        chooseImage.setOnClickListener(v -> chooseTournamentImage());
        form.addView(chooseImage);

        tournamentPreview = new ImageView(this);
        tournamentPreview.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        tournamentPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tournamentPreview.setBackgroundResource(R.drawable.bg_game_card_gold);
        form.addView(tournamentPreview);

        tournamentImageUrlField = field("Or paste image URL instead");
        form.addView(tournamentImageUrlField);

        form.addView(fieldLabel("MATCH NAME"));
        EditText title = field("Tournament name");
        form.addView(title);

        EditText mode = field("Category ID or game mode");
        if (!categoryId.isEmpty()) {
            mode.setText(categoryId);
            mode.setEnabled(false);
        } else {
            form.addView(fieldLabel("CATEGORY / MODE"));
        }
        form.addView(mode);

        form.addView(fieldLabel("SCHEDULE"));
        EditText date = field("Start date (e.g. 20 SEP 2026)");
        EditText time = field("Start time (e.g. 08:30 PM)");
        form.addView(formRow(date, time));

        form.addView(fieldLabel("PRIZE & SLOTS"));
        EditText prize = field("Prize information (e.g. Diamonds / Bundle)");
        form.addView(prize);
        EditText slots = field("Total slots");
        slots.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText teamSize = field("Team size");
        teamSize.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(formRow(slots, teamSize));

        form.addView(fieldLabel("MATCH TYPE & MAP"));
        EditText matchType = field("Match type (SOLO / DUO / SQUAD)");
        EditText map = field("Map name");
        form.addView(formRow(matchType, map));

        form.addView(fieldLabel("MATCH DESCRIPTION"));
        EditText description = field("Match description");
        form.addView(description);

        form.addView(fieldLabel("RULES (OPTIONAL)"));
        EditText rules = field("Rules (optional)");
        form.addView(rules);

        form.addView(fieldLabel("ROOM DETAILS (OPTIONAL)"));
        EditText roomId = field("Room ID (optional)");
        form.addView(roomId);
        EditText roomDate = field("Room release date (optional)");
        EditText roomTime = field("Room release time (optional)");
        form.addView(formRow(roomDate, roomTime));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(form);

        Dialog dialog = buildStickyFormDialog(
                categoryTitle.isEmpty() ? "Create tournament" : "Add match • " + categoryTitle,
                "Set match time, prize information, slots and rules. Add a banner image, then publish live to Firebase.",
                scroll,
                "SAVE MATCH",
                () -> {
                    String tournamentTitle = title.getText().toString().trim();
                    if (tournamentTitle.isEmpty()) {
                        title.setError("Enter a name");
                        return;
                    }
                    String prizeValue = prize.getText().toString().trim().isEmpty() ? "Free entry" : prize.getText().toString().trim();
                    int slotValue = numberOrDefault(slots.getText().toString(), 48);
                    int teamValue = numberOrDefault(teamSize.getText().toString(), 1);
                    String modeValue = mode.getText().toString().trim().isEmpty()
                            ? "CUSTOM MODE" : mode.getText().toString().trim().toUpperCase();
                    String dateValue = date.getText().toString().trim().isEmpty()
                            ? "TBA" : date.getText().toString().trim().toUpperCase();
                    String timeValue = time.getText().toString().trim().isEmpty()
                            ? "TBA" : time.getText().toString().trim().toUpperCase();
                    long startAt = parseDateTime(dateValue, timeValue);
                    long roomReleaseAt = parseDateTime(
                            roomDate.getText().toString().trim().toUpperCase(),
                            roomTime.getText().toString().trim().toUpperCase());
                    String categoryValue = categoryId.isEmpty() ? modeValue : categoryId;

                    if (selectedTournamentImage != null) {
                        Toast.makeText(this, "Uploading image securely…", Toast.LENGTH_SHORT).show();
                        com.arenax.tournament.data.ImgBbUploader.uploadImage(this, selectedTournamentImage,
                                new com.arenax.tournament.data.StorageUploader.Callback() {
                                    @Override public void onSuccess(String fileUrl) {
                                        saveTournamentEntry(tournamentTitle, modeValue, categoryValue, fileUrl,
                                                description.getText().toString().trim(), dateValue, timeValue, startAt,
                                                roomReleaseAt, prizeValue, slotValue, teamValue,
                                                map.getText().toString().trim(), matchType.getText().toString().trim(),
                                                rules.getText().toString().trim(), roomId.getText().toString().trim());
                                    }
                                    @Override public void onFailure(String message) {
                                        showUploadErrorDialog("Image upload failed", message);
                                    }
                                });
                    } else {
                        saveTournamentEntry(tournamentTitle, modeValue, categoryValue,
                                tournamentImageUrlField.getText().toString().trim(),
                                description.getText().toString().trim(), dateValue, timeValue, startAt,
                                roomReleaseAt, prizeValue, slotValue, teamValue,
                                map.getText().toString().trim(), matchType.getText().toString().trim(),
                                rules.getText().toString().trim(), roomId.getText().toString().trim());
                    }
                });
        categoryDialog = dialog;
        dialog.show();
    }

    private void saveTournamentEntry(String tournamentTitle, String modeValue, String categoryValue, String bannerUrlValue,
                                      String descriptionValue, String dateValue, String timeValue, long startAt,
                                      long roomReleaseAt, String prizeValue, int slotValue, int teamValue,
                                      String mapValue, String matchTypeValue, String rulesValue, String roomIdValue) {
        Tournament tournament = new Tournament(
                null,
                tournamentTitle,
                modeValue,
                categoryValue,
                bannerUrlValue,
                descriptionValue,
                dateValue,
                timeValue,
                startAt,
                0L,
                roomReleaseAt,
                prizeValue, slotValue, 0,
                Math.max(1, teamValue),
                mapValue.isEmpty() ? "TBA" : mapValue,
                matchTypeValue.isEmpty() ? "SOLO" : matchTypeValue.toUpperCase(),
                rulesValue,
                "",
                roomIdValue,
                "",
                false, "",
                "UPCOMING", "OPEN", true, Color.rgb(237, 47, 62));
        FirebaseRepository.saveTournament(tournament)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, "Tournament published. User app updated live.", Toast.LENGTH_SHORT).show();
                    FirebaseRepository.logActivity("TOURNAMENT_SAVED", tournamentTitle, "Slots " + slotValue + ", mode " + modeValue);
                    if (categoryDialog != null) categoryDialog.dismiss();
                })
                .addOnFailureListener(error -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void chooseTournamentImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, 7004);
    }

    /** Puts two fields side by side (2-column row), matching the reference form design. */
    private LinearLayout formRow(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftParams.rightMargin = dp(6);
        left.setLayoutParams(leftParams);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightParams.leftMargin = dp(6);
        right.setLayoutParams(rightParams);
        row.addView(left);
        row.addView(right);
        return row;
    }

    private void showAddCategoryDialog() {
        selectedCategoryImage = null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText title = field("Game category name");
        categoryImageUrl = field("PNG/JPG image URL (optional)");
        EditText positionField = field("Slot position (1, 2, 3… blank = add to end)");
        positionField.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(title);
        form.addView(categoryImageUrl);
        form.addView(positionField);

        MaterialButton chooseImage = new MaterialButton(this);
        chooseImage.setText("SELECT PNG/JPG FROM DEVICE");
        chooseImage.setTextColor(getColor(R.color.text_primary));
        chooseImage.setMaxLines(1);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chooseParams.topMargin = dp(6);
        chooseParams.bottomMargin = dp(10);
        chooseImage.setLayoutParams(chooseParams);
        chooseImage.setOnClickListener(v -> chooseCategoryImage());
        form.addView(chooseImage);

        categoryPreview = new ImageView(this);
        categoryPreview.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        categoryPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        categoryPreview.setBackgroundResource(R.drawable.bg_game_card_blue);
        form.addView(categoryPreview);

        android.widget.ScrollView formScroll = new android.widget.ScrollView(this);
        formScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        formScroll.addView(form);

        categoryDialog = buildStickyFormDialog(
                "Create esports category",
                "Publish live to Firebase. Add a public image URL or select a PNG/JPG from this device.",
                formScroll,
                "PUBLISH",
                () -> {
                    String categoryTitle = title.getText().toString().trim();
                    if (categoryTitle.isEmpty()) {
                        title.setError("Enter a category name");
                        return;
                    }
                    int position = parsePosition(positionField.getText().toString().trim(), gameModes.size() + 1);
                    publishCategory(categoryTitle, categoryImageUrl.getText().toString().trim(), position);
                });
        categoryDialog.show();
    }

    /** Home screen shows 2 categories per line, so slot 1 & 2 = line 1, 3 & 4 = line 2, and so on. */
    private int parsePosition(String rawValue, int fallback) {
        if (rawValue.isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(rawValue);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** Like parsePosition but allows zero (a category can legitimately show 0 users). */
    private int parseNonNegative(String rawValue, int fallback) {
        if (rawValue.isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(rawValue);
            return parsed >= 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void showEditCategoryDialog(GameMode existing) {
        selectedCategoryImage = null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText title = field("Game category name");
        title.setText(existing.getTitle());
        categoryImageUrl = field("PNG/JPG image URL (leave as-is to keep current image)");
        categoryImageUrl.setText(existing.getImageUrl());
        EditText positionField = field("Slot position (1, 2, 3…)");
        positionField.setInputType(InputType.TYPE_CLASS_NUMBER);
        positionField.setText(String.valueOf(existing.getDisplayOrder()));
        EditText usersField = field("Total users shown (e.g. 32)");
        usersField.setInputType(InputType.TYPE_CLASS_NUMBER);
        usersField.setText(String.valueOf(existing.getTotalUsers()));
        form.addView(fieldLabel("CATEGORY NAME"));
        form.addView(title);
        form.addView(fieldLabel("IMAGE URL"));
        form.addView(categoryImageUrl);
        form.addView(fieldLabel("SLOT POSITION"));
        form.addView(positionField);
        form.addView(fieldLabel("TOTAL USERS SHOWN"));
        form.addView(usersField);

        LinearLayout onlineRow = new LinearLayout(this);
        onlineRow.setOrientation(LinearLayout.HORIZONTAL);
        onlineRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams onlineRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        onlineRowParams.topMargin = dp(6);
        onlineRowParams.bottomMargin = dp(6);
        onlineRow.setLayoutParams(onlineRowParams);
        TextView onlineLabel = new TextView(this);
        onlineLabel.setText("Category status: ONLINE (green dot on Home) / OFFLINE (red dot)");
        onlineLabel.setTextColor(getColor(R.color.text_primary));
        onlineLabel.setTextSize(11f);
        onlineLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        com.google.android.material.switchmaterial.SwitchMaterial onlineSwitch =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        onlineSwitch.setChecked(existing.isOnline());
        onlineRow.addView(onlineLabel);
        onlineRow.addView(onlineSwitch);
        form.addView(onlineRow);

        MaterialButton chooseImage = new MaterialButton(this);
        chooseImage.setText("SELECT PNG/JPG FROM DEVICE");
        chooseImage.setTextColor(getColor(R.color.text_primary));
        chooseImage.setMaxLines(1);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chooseParams.topMargin = dp(6);
        chooseParams.bottomMargin = dp(10);
        chooseImage.setLayoutParams(chooseParams);
        chooseImage.setOnClickListener(v -> chooseCategoryImage());
        form.addView(chooseImage);

        categoryPreview = new ImageView(this);
        categoryPreview.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        categoryPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        categoryPreview.setBackgroundResource(R.drawable.bg_game_card_blue);
        if (!existing.getImageUrl().isEmpty()) {
            Glide.with(this).load(existing.getImageUrl()).centerCrop().into(categoryPreview);
        }
        form.addView(categoryPreview);

        android.widget.ScrollView formScroll = new android.widget.ScrollView(this);
        formScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        formScroll.addView(form);

        categoryDialog = buildStickyFormDialog(
                "Edit " + existing.getTitle(),
                "Change the slot position to move this category to a different line/spot on the Home screen grid (2 per line).",
                formScroll,
                "SAVE",
                () -> {
                    String categoryTitle = title.getText().toString().trim();
                    if (categoryTitle.isEmpty()) {
                        title.setError("Enter a category name");
                        return;
                    }
                    int position = parsePosition(positionField.getText().toString().trim(), existing.getDisplayOrder());
                    int totalUsers = parseNonNegative(usersField.getText().toString().trim(), existing.getTotalUsers());
                    boolean online = onlineSwitch.isChecked();
                    if (selectedCategoryImage != null) {
                        Toast.makeText(this, "Uploading image securely…", Toast.LENGTH_SHORT).show();
                        com.arenax.tournament.data.ImgBbUploader.uploadImage(this, selectedCategoryImage,
                                new com.arenax.tournament.data.StorageUploader.Callback() {
                                    @Override public void onSuccess(String fileUrl) {
                                        saveCategory(existing.getId(), categoryTitle, fileUrl, position, online, totalUsers);
                                    }
                                    @Override public void onFailure(String message) {
                                        showUploadErrorDialog("Image upload failed", message);
                                    }
                                });
                    } else {
                        saveCategory(existing.getId(), categoryTitle, categoryImageUrl.getText().toString().trim(), position, online, totalUsers);
                    }
                });
        categoryDialog.show();
    }

    private void chooseCategoryImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, 7001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 7001 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedCategoryImage = data.getData();
            if (categoryPreview != null) categoryPreview.setImageURI(selectedCategoryImage);
        }
        if (requestCode == 7002 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedLogoImage = data.getData();
            if (logoPreview != null) logoPreview.setImageURI(selectedLogoImage);
        }
        if (requestCode == 7003 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedBannerImage = data.getData();
            if (bannerPreview != null) bannerPreview.setImageURI(selectedBannerImage);
        }
        if (requestCode == 7004 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedTournamentImage = data.getData();
            if (tournamentPreview != null) tournamentPreview.setImageURI(selectedTournamentImage);
        }
    }

    private void publishCategory(String title, String imageUrl, int position) {
        String id = FirebaseRepository.gameModes().push().getKey();
        if (id == null) {
            Toast.makeText(this, "Could not create category key.", Toast.LENGTH_LONG).show();
            return;
        }
        if (selectedCategoryImage == null) {
            saveCategory(id, title, imageUrl, position);
            return;
        }
        Toast.makeText(this, "Uploading image securely…", Toast.LENGTH_SHORT).show();
        com.arenax.tournament.data.ImgBbUploader.uploadImage(this, selectedCategoryImage,
                new com.arenax.tournament.data.StorageUploader.Callback() {
                    @Override public void onSuccess(String fileUrl) {
                        saveCategory(id, title, fileUrl, position);
                    }
                    @Override public void onFailure(String message) {
                        showUploadErrorDialog("Image upload failed", message);
                    }
                });
    }

    /** Toast truncates long text; upload errors (e.g. Telegram's JSON error body) need the full message visible and copyable. */
    private void showUploadErrorDialog(String title, String message) {
        android.widget.TextView content = new android.widget.TextView(this);
        content.setText(message);
        content.setTextIsSelectable(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(content)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Whether a category with this Firebase key already exists (i.e. this save is an edit, not a new create). */
    private boolean categoryExists(String id) {
        for (GameMode existing : gameModes) {
            if (existing.getId().equals(id)) return true;
        }
        return false;
    }

    private long existingCreatedAt(String id, long fallback) {
        for (GameMode existing : gameModes) {
            if (existing.getId().equals(id)) return existing.getCreatedAt();
        }
        return fallback;
    }

    private void saveCategory(String id, String title, String imageUrl, int displayOrder) {
        saveCategory(id, title, imageUrl, displayOrder, false, 0);
    }

    private void saveCategory(String id, String title, String imageUrl, int displayOrder, boolean online, int totalUsers) {
        boolean isUpdate = categoryExists(id);
        long createdAt = existingCreatedAt(id, System.currentTimeMillis());
        Map<String, Object> mode = new HashMap<>();
        mode.put("title", title.toUpperCase());
        mode.put("imageUrl", imageUrl);
        mode.put("displayOrder", displayOrder);
        mode.put("createdAt", createdAt);
        mode.put("active", true);
        mode.put("online", online);
        mode.put("totalUsers", totalUsers);
        FirebaseRepository.saveGameMode(id, mode)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, isUpdate ? "Category updated. Slot #" + displayOrder + "."
                            : "Category published live at slot #" + displayOrder + ".", Toast.LENGTH_SHORT).show();
                    FirebaseRepository.logActivity(isUpdate ? "CATEGORY_UPDATED" : "CATEGORY_CREATED",
                            title, "slot #" + displayOrder);
                    if (categoryDialog != null) categoryDialog.dismiss();
                })
                .addOnFailureListener(error ->
                        Toast.makeText(this, "Category save failed: " + error.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void confirmDeleteGameMode(GameMode mode) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove " + mode.getTitle() + "?")
                .setMessage("This category will disappear from the User app after Firebase updates.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("REMOVE", (dialog, which) ->
                        FirebaseRepository.deleteGameMode(mode.getId())
                                .addOnSuccessListener(unused -> {
                                    Toast.makeText(this, "Category removed.", Toast.LENGTH_SHORT).show();
                                    FirebaseRepository.logActivity("CATEGORY_DELETED", mode.getTitle(), "");
                                })
                                .addOnFailureListener(error -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show()))
                .show();
    }

    private static final int MAX_HOME_BANNERS = 10;

    private void showAddBannerDialog() {
        if (banners.size() >= MAX_HOME_BANNERS) {
            Toast.makeText(this, "Maximum " + MAX_HOME_BANNERS + " banners reached. Delete one to add a new banner.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        selectedBannerImage = null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText title = field("Banner title (e.g. SEASON 04 LIVE)");
        EditText subtitle = field("Subtitle (optional)");
        bannerImageUrlField = field("Banner image URL (optional)");
        form.addView(title);
        form.addView(subtitle);
        form.addView(bannerImageUrlField);

        MaterialButton chooseImage = new MaterialButton(this);
        chooseImage.setText("SELECT IMAGE FROM DEVICE");
        chooseImage.setTextColor(getColor(R.color.text_primary));
        chooseImage.setMaxLines(1);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chooseParams.topMargin = dp(6);
        chooseParams.bottomMargin = dp(10);
        chooseImage.setLayoutParams(chooseParams);
        chooseImage.setOnClickListener(v -> chooseBannerImage());
        form.addView(chooseImage);

        bannerPreview = new ImageView(this);
        bannerPreview.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        bannerPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bannerPreview.setBackgroundResource(R.drawable.bg_banner);
        form.addView(bannerPreview);

        bannerDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Add home banner")
                .setMessage("Publish live to Firebase. This appears instantly at the top of the User app home screen.")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("PUBLISH", null)
                .create();
        bannerDialog.setOnShowListener(ignored -> bannerDialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v ->
                publishBanner(title.getText().toString().trim(), subtitle.getText().toString().trim(),
                        bannerImageUrlField.getText().toString().trim())));
        bannerDialog.show();
    }

    private void chooseBannerImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, 7003);
    }

    private void publishBanner(String title, String subtitle, String imageUrl) {
        if (banners.size() >= MAX_HOME_BANNERS) {
            Toast.makeText(this, "Maximum " + MAX_HOME_BANNERS + " banners reached. Delete one to add a new banner.",
                    Toast.LENGTH_LONG).show();
            if (bannerDialog != null) bannerDialog.dismiss();
            return;
        }
        String id = FirebaseRepository.banners().push().getKey();
        if (id == null) {
            Toast.makeText(this, "Could not create banner key.", Toast.LENGTH_LONG).show();
            return;
        }
        if (selectedBannerImage == null) {
            saveBanner(id, title, subtitle, imageUrl);
            return;
        }
        Toast.makeText(this, "Uploading image securely…", Toast.LENGTH_SHORT).show();
        com.arenax.tournament.data.ImgBbUploader.uploadImage(this, selectedBannerImage,
                new com.arenax.tournament.data.StorageUploader.Callback() {
                    @Override public void onSuccess(String fileUrl) {
                        saveBanner(id, title, subtitle, fileUrl);
                    }
                    @Override public void onFailure(String message) {
                        showUploadErrorDialog("Image upload failed", message);
                    }
                });
    }

    private void saveBanner(String id, String title, String subtitle, String imageUrl) {
        Map<String, Object> banner = new HashMap<>();
        banner.put("title", title);
        banner.put("subtitle", subtitle);
        banner.put("imageUrl", imageUrl);
        banner.put("displayOrder", banners.size());
        banner.put("createdAt", System.currentTimeMillis());
        banner.put("active", true);
        FirebaseRepository.saveBanner(id, banner)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, "Banner published live.", Toast.LENGTH_SHORT).show();
                    FirebaseRepository.logActivity("BANNER_CREATED", title.isEmpty() ? "Untitled banner" : title, "");
                    if (bannerDialog != null) bannerDialog.dismiss();
                })
                .addOnFailureListener(error ->
                        Toast.makeText(this, "Banner publish failed: " + error.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void confirmDeleteBanner(Banner banner) {
        String label = banner.getTitle() == null || banner.getTitle().trim().isEmpty() ? "this banner" : banner.getTitle();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove " + label + "?")
                .setMessage("This banner will disappear from the User app home screen after Firebase updates.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("REMOVE", (dialog, which) ->
                        FirebaseRepository.deleteBanner(banner.getId())
                                .addOnSuccessListener(unused -> {
                                    Toast.makeText(this, "Banner removed.", Toast.LENGTH_SHORT).show();
                                    FirebaseRepository.logActivity("BANNER_DELETED", label, "");
                                })
                                .addOnFailureListener(error -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show()))
                .show();
    }

    private void showTournamentActions(Tournament tournament) {
        String details = "Mode: " + tournament.getMode() + "\n"
                + "Date: " + tournament.getDate() + " " + tournament.getTime() + "\n"
                + "Entry: FREE   Prize: " + tournament.getPrizeInfo() + "\n"
                + "Slots: " + tournament.getJoinedSlots() + "/" + tournament.getTotalSlots();
        new MaterialAlertDialogBuilder(this)
                .setTitle(tournament.getTitle())
                .setMessage(details)
                .setNeutralButton("CONTROL CENTER", (dialog, which) -> {
                    Intent page = new Intent(this, TournamentControlCenterActivity.class);
                    page.putExtra("tournamentId", tournament.getId());
                    page.putExtra("title", tournament.getTitle());
                    startActivity(page);
                })
                .setNegativeButton("DELETE", (dialog, which) -> showDeleteConfirm(tournament))
                .setPositiveButton("MANAGE ROOM", (dialog, which) -> showRoomControlDialog(tournament))
                .show();
    }

    private void showRoomControlDialog(Tournament tournament) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        TextView status = new TextView(this);
        status.setText(tournament.isRoomReleased() ? "ROOM CURRENTLY RELEASED TO PLAYERS" : "ROOM CURRENTLY HIDDEN");
        status.setTextColor(tournament.isRoomReleased() ? Color.rgb(46, 204, 143) : Color.LTGRAY);
        status.setTextSize(12);
        status.setPadding(0, 0, 0, dp(10));
        form.addView(status);

        EditText roomId = field("Room ID");
        roomId.setText(tournament.getRoomId());
        EditText roomPass = field("Room Password");
        roomPass.setText(tournament.getRoomPassword());
        EditText delay = field("Delay reason (leave empty if not delayed)");
        delay.setText(tournament.getDelayReason());
        form.addView(roomId);
        form.addView(roomPass);
        form.addView(delay);

        // A8 — Waitlist control lives alongside room/slot control since both manage match capacity.
        TextView waitlistStatus = new TextView(this);
        waitlistStatus.setTextColor(Color.LTGRAY);
        waitlistStatus.setTextSize(12);
        waitlistStatus.setPadding(0, dp(14), 0, dp(4));
        waitlistStatus.setText("Checking waitlist…");
        form.addView(waitlistStatus);
        MaterialButton promote = new MaterialButton(this);
        promote.setText("PROMOTE NEXT FROM WAITLIST");
        promote.setTextColor(Color.WHITE);
        promote.setTextSize(11);
        promote.setAllCaps(false);
        promote.setCornerRadius(dp(14));
        promote.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(60, 62, 70)));
        promote.setElevation(0f);
        promote.setInsetTop(0);
        promote.setInsetBottom(0);
        promote.setEnabled(false);
        form.addView(promote, new LinearLayout.LayoutParams(-1, dp(44)));
        refreshWaitlistStatus(tournament, waitlistStatus, promote);
        promote.setOnClickListener(v -> FirebaseRepository.promoteNextFromWaitlist(tournament.getId())
                .addOnSuccessListener(done -> {
                    Toast.makeText(this, "Promoted the next waitlisted player into the match.", Toast.LENGTH_SHORT).show();
                    FirebaseRepository.logActivity("WAITLIST_PROMOTED", tournament.getTitle(), "");
                    refreshWaitlistStatus(tournament, waitlistStatus, promote);
                })
                .addOnFailureListener(error -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show()));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Room control • " + tournament.getTitle())
                .setMessage("Release sends Room ID + Password live to every registered participant instantly.")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setNeutralButton(tournament.isRoomReleased() ? "HIDE ROOM" : "RELEASE ROOM", null)
                .setPositiveButton("SAVE", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    updateRoom(tournament, roomId.getText().toString().trim(),
                            roomPass.getText().toString().trim(),
                            tournament.isRoomReleased(),
                            delay.getText().toString().trim(), dialog));
            dialog.getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    updateRoom(tournament, roomId.getText().toString().trim(),
                            roomPass.getText().toString().trim(),
                            !tournament.isRoomReleased(),
                            delay.getText().toString().trim(), dialog));
        });
        dialog.show();
    }

    /** A8 — shows the current free-waitlist count and enables "Promote" only when someone is waiting. */
    private void refreshWaitlistStatus(Tournament tournament, TextView status, MaterialButton promote) {
        FirebaseRepository.tournamentWaitlist(tournament.getId()).get()
                .addOnSuccessListener(snapshot -> {
                    long count = snapshot.getChildrenCount();
                    status.setText(count == 0 ? "Waitlist is empty." : count + " player(s) waiting for a free slot.");
                    promote.setEnabled(count > 0);
                })
                .addOnFailureListener(error -> status.setText("Waitlist unavailable: " + error.getMessage()));
    }

    /**
     * A19 — Targeted room-release push. The Android app sends only the target
     * scope and message to the trusted backend; Firebase/FCM credentials never
     * enter an APK and participant tokens never leave the server.
     */
    private void sendRoomReleasePush(String targetType, String targetId, String title, String body) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        FirebaseAuth.getInstance().getCurrentUser().getIdToken(false)
                .addOnSuccessListener(result -> {
                    String idToken = result == null ? "" : String.valueOf(result.getToken());
                    if (idToken.trim().isEmpty()) return;
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("targetType", targetType);
                        payload.put("targetId", targetId);
                        payload.put("title", title);
                        payload.put("body", body);
                        Request request = new Request.Builder()
                                .url(BuildConfig.STARX_API_BASE_URL + "/admin/notifyParticipants")
                                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                                .addHeader("Authorization", "Bearer " + idToken)
                                .build();
                        new OkHttpClient().newCall(request).enqueue(new okhttp3.Callback() {
                            @Override public void onFailure(okhttp3.Call call, java.io.IOException error) {
                                runOnUiThread(() -> toast("Push notification could not be sent."));
                            }
                            @Override public void onResponse(okhttp3.Call call, Response response) throws java.io.IOException {
                                String text = response.body() == null ? "" : response.body().string();
                                response.close();
                                if (!response.isSuccessful()) {
                                    runOnUiThread(() -> toast("Push notification failed."));
                                    return;
                                }
                                try {
                                    JSONObject resultJson = new JSONObject(text);
                                    int sent = resultJson.optInt("sent", 0);
                                    int total = resultJson.optInt("total", 0);
                                    runOnUiThread(() -> toast("Notified " + sent + "/" + total + " joined player(s)."));
                                } catch (Exception ignored) {
                                    runOnUiThread(() -> toast("Push notification completed."));
                                }
                            }
                        });
                    } catch (Exception ignored) {
                        toast("Push notification could not be prepared.");
                    }
                });
    }

    private void updateRoom(Tournament t, String roomId, String roomPass, boolean released,
                             String delayReason, AlertDialog dialog) {
        Tournament updated = new Tournament(t.getId(), t.getTitle(), t.getMode(), t.getCategoryId(),
                t.getBannerUrl(), t.getDescription(), t.getDate(), t.getTime(), t.getStartAt(),
                t.getRegistrationCloseAt(), t.getRoomReleaseAt(), t.getPrizeInfo(), t.getTotalSlots(),
                t.getJoinedSlots(), t.getTeamSize(), t.getMap(), t.getMatchType(), t.getRules(),
                t.getPrizeDistribution(), roomId, roomPass, released, delayReason,
                t.getStatus(), t.getRegistrationStatus(), t.isActive(), t.getAccentColor());
        FirebaseRepository.saveTournament(updated)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, released ? "Room released live to players." : "Room hidden from players.",
                            Toast.LENGTH_SHORT).show();
                    FirebaseRepository.logActivity(released ? "ROOM_RELEASED" : "ROOM_HIDDEN", t.getTitle(), delayReason);
                    FirebaseRepository.publishNotification(
                            released ? "Room Released" : "Room Update",
                            released ? t.getTitle() + " room ID & password are now live."
                                    : (delayReason.isEmpty() ? t.getTitle() + " room hidden."
                                    : t.getTitle() + " delayed: " + delayReason));
                    if (released) {
                        sendRoomReleasePush("tournament", t.getId(),
                                t.getTitle() + " — Room Released!",
                                "Room ID: " + roomId + " | Password: " + roomPass);
                    }
                    dialog.dismiss();
                })
                .addOnFailureListener(error -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void showDeleteConfirm(Tournament tournament) {
        new MaterialAlertDialogBuilder(this)
                        .setTitle("Delete " + tournament.getTitle() + "?")
                        .setMessage("This permanently removes the live Firebase tournament record.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("DELETE", (confirm, ignored) -> FirebaseRepository.deleteTournament(tournament.getId())
                                .addOnSuccessListener(done -> {
                                    Toast.makeText(this, "Tournament deleted.", Toast.LENGTH_SHORT).show();
                                    FirebaseRepository.logActivity("TOURNAMENT_DELETED", tournament.getTitle(), "");
                                })
                                .addOnFailureListener(error -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show()))
                .show();
    }

    private void setupNavigation() {
        int[] ids = {R.id.admin_nav_dashboard, R.id.admin_nav_users, R.id.admin_nav_battles,
                R.id.admin_nav_tournaments, R.id.admin_nav_esports, R.id.admin_nav_category,
                R.id.admin_nav_teams, R.id.admin_nav_matches, R.id.admin_nav_results, R.id.admin_nav_disputes,
                R.id.admin_nav_reports, R.id.admin_nav_faq,
                R.id.admin_nav_notice, R.id.admin_nav_analytics, R.id.admin_nav_activity, R.id.admin_nav_settings,
                R.id.admin_nav_roles};
        String[] pages = {"dashboard", "users", "battles", "tournaments", "esports", "category",
                "teams", "matches", "results", "disputes", "reports", "faq",
                "notice", "analytics", "activity", "settings", "roles"};
        for (int i = 0; i < ids.length; i++) {
            final String page = pages[i];
            View view = findViewById(ids[i]);
            if (view != null) view.setOnClickListener(v -> showWorkspacePage(page));
        }
    }

    private void showWorkspacePage(String page) {
        if ("dashboard".equals(page)) {
            workspaceScroll.setVisibility(View.GONE);
            dashboardScroll.setVisibility(View.VISIBLE);
            return;
        }
        // A1 — hidden nav rows already stop normal taps, but this blocks any direct call too
        // (client-side only: true enforcement still needs the Firebase rules / custom claims layer).
        java.util.Set<String> allowed = ROLE_PAGES.getOrDefault(adminRole, ROLE_PAGES.get("SUPPORT_AGENT"));
        if (!allowed.contains(page)) {
            Toast.makeText(this, "Your admin role does not have access to this section.", Toast.LENGTH_SHORT).show();
            return;
        }
        dashboardScroll.setVisibility(View.GONE);
        workspaceScroll.setVisibility(View.VISIBLE);
        workspaceContent.removeAllViews();
        TextView title = pageTitle(page);
        workspaceContent.addView(title);
        TextView subtitle = pageText("Live Firebase data and controls for this section.");
        workspaceContent.addView(subtitle);
        if ("users".equals(page)) loadUsersPage();
        else if ("battles".equals(page) || "tournaments".equals(page)) loadTournamentsPage(page);
        else if ("esports".equals(page) || "category".equals(page)) loadCategoriesPage();
        else if ("teams".equals(page)) loadTeamsPage();
        else if ("matches".equals(page)) loadMatchesPage();
        else if ("results".equals(page)) loadResultsPage();
        else if ("disputes".equals(page)) loadDisputesPage();
        else if ("reports".equals(page)) loadReportsPage();
        else if ("faq".equals(page)) loadFaqPage();
        else if ("notice".equals(page)) loadNoticePage();
        else if ("analytics".equals(page)) loadAnalyticsPage();
        else if ("activity".equals(page)) loadActivityLogPage();
        else if ("settings".equals(page)) loadSettingsPage();
        else if ("roles".equals(page)) loadRolesPage();
    }

    private TextView pageTitle(String page) {
        TextView title = pageText(page.toUpperCase(Locale.ENGLISH));
        title.setTextSize(25);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(8));
        return title;
    }

    private TextView pageText(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(getColor(R.color.text_secondary));
        text.setTextSize(12);
        text.setPadding(0, dp(6), 0, dp(6));
        return text;
    }

    private MaterialButton pageButton(String label, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setAllCaps(false);
        button.setLetterSpacing(0.02f);
        button.setCornerRadius(dp(16));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.blood_red)));
        button.setRippleColor(android.content.res.ColorStateList.valueOf(getColor(R.color.blood_red_dark)));
        button.setElevation(0f);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p.topMargin = dp(10);
        workspaceContent.addView(button, p);
        return button;
    }

    private MaterialButton pageButtonTonal(String label, int tintColorRes, int strokeColorRes, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextColor(getColor(tintColorRes));
        button.setTextSize(12);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setAllCaps(false);
        button.setLetterSpacing(0.02f);
        button.setCornerRadius(dp(16));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(tintColorRes == R.color.accent_purple ? R.color.accent_purple_tint
                : tintColorRes == R.color.accent_blue ? R.color.accent_blue_tint
                : tintColorRes == R.color.accent_green ? R.color.accent_green_tint
                : R.color.accent_yellow_tint)));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(getColor(strokeColorRes)));
        button.setStrokeWidth(dp(1));
        button.setRippleColor(android.content.res.ColorStateList.valueOf(getColor(strokeColorRes)));
        button.setElevation(0f);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p.topMargin = dp(10);
        workspaceContent.addView(button, p);
        return button;
    }

    private void loadUsersPage() {
        pageButtonTonal("REFRESH USERS", R.color.accent_purple, R.color.accent_purple_stroke, v -> showWorkspacePage("users"));
        FirebaseRepository.users().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                workspaceContent.addView(pageText("TOTAL USERS: " + snapshot.getChildrenCount()));
                for (DataSnapshot child : snapshot.getChildren()) {
                    workspaceContent.addView(buildPlayerRow(child));
                }
            }
            @Override public void onCancelled(DatabaseError error) { workspaceContent.addView(pageText("Users unavailable: " + error.getMessage())); }
        });
    }

    /** A10 — Player Management: profile summary + moderation actions (Warn/Restrict/Suspend/Ban/Unban) per row. */
    private android.view.View buildPlayerRow(DataSnapshot child) {
        String uid = child.getKey();
        Object raw = child.getValue();
        Map<String, Object> data = raw instanceof Map ? (Map<String, Object>) raw : null;
        String name = data == null ? "" : String.valueOf(data.get("name"));
        String email = data == null ? "" : String.valueOf(data.get("email"));
        String phone = data == null ? "" : String.valueOf(data.get("phone"));
        String status = data == null || data.get("status") == null ? "ACTIVE" : String.valueOf(data.get("status"));
        String matches = data == null || data.get("matchesPlayed") == null ? "0" : String.valueOf(data.get("matchesPlayed"));
        String wins = data == null || data.get("wins") == null ? "0" : String.valueOf(data.get("wins"));
        String kills = data == null || data.get("totalKills") == null ? "0" : String.valueOf(data.get("totalKills"));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(8);
        card.setLayoutParams(cp);

        TextView title = pageText((name.isEmpty() || "null".equals(name) ? "Arena Player" : name)
                + "   [" + status + "]");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);
        card.addView(pageText(email + (phone == null || phone.isEmpty() || "null".equals(phone) ? "" : "  •  " + phone)));
        card.addView(pageText(matches + " matches  •  " + wins + " wins  •  " + kills + " kills  •  uid " + uid));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        rp.topMargin = dp(8);
        row.setLayoutParams(rp);
        row.addView(inlineButton("WARN", R.color.accent_yellow_tint, R.color.accent_yellow_stroke, v -> moderatePlayer(uid, name, "WARNED")));
        row.addView(inlineButton("SUSPEND", R.color.accent_blue_tint, R.color.accent_blue_stroke, v -> moderatePlayer(uid, name, "SUSPENDED")));
        row.addView(inlineButton("BAN", R.color.accent_purple_tint, R.color.accent_purple_stroke, v -> moderatePlayer(uid, name, "BANNED")));
        row.addView(inlineButton("ACTIVATE", R.color.accent_green_tint, R.color.accent_green_stroke, v -> moderatePlayer(uid, name, "ACTIVE")));
        card.addView(row);
        return card;
    }

    private void moderatePlayer(String uid, String name, String status) {
        new MaterialAlertDialogBuilder(this)
                .setTitle((name == null || name.isEmpty() ? "This player" : name) + " → " + status + "?")
                .setMessage("This is written to the player's profile and enforced by the Firebase rules on their next request.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CONFIRM", (d, w) -> FirebaseRepository.setUserStatus(uid, status)
                        .addOnSuccessListener(u -> {
                            FirebaseRepository.logActivity("USER_" + status, name == null || name.isEmpty() ? uid : name, "uid " + uid);
                            toast("Player marked " + status + ".");
                        })
                        .addOnFailureListener(e -> toast(e.getMessage())))
                .show();
    }

    private void loadTournamentsPage(String page) {
        pageButtonTonal("＋ NEW BATTLE", R.color.accent_purple, R.color.accent_purple_stroke, v -> showAddTournamentDialog("", ""));
        workspaceContent.addView(pageText("Existing tournaments are live below in the dashboard list. Use NEW BATTLE to publish a real Firebase record."));
        pageButtonTonal("OPEN DASHBOARD LIST", R.color.accent_blue, R.color.accent_blue_stroke, v -> showWorkspacePage("dashboard"));
    }

    private void loadCategoriesPage() {
        pageButtonTonal("＋ NEW ESPORTS CATEGORY", R.color.accent_green, R.color.accent_green_stroke, v -> showAddCategoryDialog());
        workspaceContent.addView(pageText("Categories are read from Firebase. Add or remove categories using the real controls below."));
        pageButtonTonal("OPEN CATEGORY GRID", R.color.accent_blue, R.color.accent_blue_stroke, v -> showWorkspacePage("dashboard"));
    }

    // ---------------------------------------------------------------------
    // A1 — Roles & Permissions (Super Admin only): assign a role to another admin UID
    // ---------------------------------------------------------------------
    private void loadRolesPage() {
        workspaceContent.addView(pageText("Assign a role to any UID that already has the Firebase admin custom claim. "
                + "Super Admin: full access. Tournament Manager: tournaments, matches, schedule, room, results. "
                + "Support Agent: tickets, FAQ, announcements, read-only tournaments. Moderator: reports, disputes, warnings, bans."));

        EditText uidField = field("Admin UID to assign a role to");
        workspaceContent.addView(uidField);
        Spinner roleSpinner = new Spinner(this);
        roleSpinner.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"SUPER_ADMIN", "TOURNAMENT_MANAGER", "SUPPORT_AGENT", "MODERATOR"}));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        sp.topMargin = dp(8);
        workspaceContent.addView(roleSpinner, sp);
        pageButton("ASSIGN ROLE", v -> {
            String uid = uidField.getText().toString().trim();
            if (uid.isEmpty()) { toast("Enter the admin's UID."); return; }
            String role = String.valueOf(roleSpinner.getSelectedItem());
            FirebaseRepository.setAdminRole(uid, role)
                    .addOnSuccessListener(unused -> {
                        FirebaseRepository.logActivity("ADMIN_ROLE_ASSIGNED", uid, role);
                        toast("Role assigned. It takes effect next time that admin signs in.");
                        loadRolesPage();
                    })
                    .addOnFailureListener(error -> toast(error.getMessage()));
        });

        workspaceContent.addView(pageText("CURRENT ASSIGNMENTS"));
        FirebaseRepository.adminRoles().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                    workspaceContent.addView(pageText("No roles assigned yet."));
                    return;
                }
                for (DataSnapshot child : snapshot.getChildren()) {
                    String role = String.valueOf(child.child("role").getValue());
                    workspaceContent.addView(pageText(child.getKey() + "  →  " + role));
                }
            }
            @Override public void onCancelled(DatabaseError error) { workspaceContent.addView(pageText("Roles unavailable: " + error.getMessage())); }
        });
    }

    // ---------------------------------------------------------------------
    // A9 — Teams workspace: inspect, verify, disband
    // ---------------------------------------------------------------------
    private void loadTeamsPage() {
        workspaceContent.addView(pageText("Teams are created by players from the User App. Verify or disband them here."));
        FirebaseRepository.teams().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                    workspaceContent.addView(pageText("No teams created yet."));
                    return;
                }
                for (DataSnapshot child : snapshot.getChildren()) {
                    com.arenax.tournament.model.Team team = com.arenax.tournament.model.Team.fromMap(
                            child.getKey(), (Map<String, Object>) child.getValue());
                    if (team == null) continue;
                    workspaceContent.addView(pageText(team.getName() + "  •  " + team.getStatus()
                            + "  •  " + team.getMemberCount() + " players  •  id: " + team.getId()));
                    LinearLayout row = new LinearLayout(AdminActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
                    rp.bottomMargin = dp(10);
                    row.setLayoutParams(rp);
                    row.addView(inlineButton("VERIFY", R.color.accent_green_tint, R.color.accent_green_stroke, v -> {
                        FirebaseRepository.setTeamStatus(team.getId(), "VERIFIED");
                        FirebaseRepository.logActivity("TEAM_VERIFIED", team.getName(), "Team ID " + team.getId());
                        toast("Team verified.");
                    }));
                    row.addView(inlineButton("DISBAND", R.color.accent_purple_tint, R.color.accent_purple_stroke, v ->
                            new MaterialAlertDialogBuilder(AdminActivity.this)
                                    .setTitle("Disband " + team.getName() + "?")
                                    .setNegativeButton("CANCEL", null)
                                    .setPositiveButton("DISBAND", (d, w) -> {
                                        FirebaseRepository.disbandTeam(team.getId());
                                        FirebaseRepository.logActivity("TEAM_DISBANDED", team.getName(), "Team ID " + team.getId());
                                        toast("Team disbanded.");
                                    }).show()));
                    workspaceContent.addView(row);
                }
            }
            @Override public void onCancelled(DatabaseError error) { workspaceContent.addView(pageText("Teams unavailable: " + error.getMessage())); }
        });
    }

    // ---------------------------------------------------------------------
    // A5/A6/A7 — Match Management: per-tournament matches with exact countdown + room control
    // ---------------------------------------------------------------------
    private void loadMatchesPage() {
        workspaceContent.addView(pageText("Pick a tournament to manage its rounds/matches — exact schedule, room release and delay, independent of the tournament record."));
        for (Tournament t : tournaments) {
            workspaceContent.addView(pageButtonTonal(t.getTitle() + "  •  MATCHES", R.color.accent_blue, R.color.accent_blue_stroke,
                    v -> showMatchesForTournament(t)));
        }
        if (tournaments.isEmpty()) workspaceContent.addView(pageText("No tournaments published yet."));
    }

    private void showMatchesForTournament(Tournament t) {
        workspaceContent.removeAllViews();
        workspaceContent.addView(pageTitle("Matches • " + t.getTitle()));
        pageButton("+ CREATE MATCH", v -> showMatchDialog(t, null));
        workspaceContent.addView(pageButtonTonal("← BACK TO TOURNAMENT LIST", R.color.accent_purple, R.color.accent_purple_stroke,
                v -> loadMatchesPage()));
        FirebaseRepository.matches().orderByChild("tournamentId").equalTo(t.getId())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snapshot) {
                        if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                            workspaceContent.addView(pageText("No matches scheduled yet for this tournament."));
                            return;
                        }
                        for (DataSnapshot child : snapshot.getChildren()) {
                            com.arenax.tournament.model.Match match = com.arenax.tournament.model.Match.fromMap(
                                    child.getKey(), (Map<String, Object>) child.getValue());
                            if (match != null) workspaceContent.addView(matchCard(t, match));
                        }
                    }
                    @Override public void onCancelled(DatabaseError error) { workspaceContent.addView(pageText("Matches unavailable: " + error.getMessage())); }
                });
    }

    private LinearLayout matchCard(Tournament t, com.arenax.tournament.model.Match m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.surface));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), getColor(R.color.stroke));
        card.setBackground(bg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        card.setLayoutParams(cp);

        TextView name = pageText("ROUND " + m.getRound() + " • " + m.getName());
        name.setTextColor(getColor(R.color.text_primary));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(name);
        card.addView(pageText(m.getMap() + "  •  " + m.getMode()
                + "  •  " + (m.getScheduledAt() > 0
                ? new SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.ENGLISH).format(new Date(m.getScheduledAt()))
                : "Time TBA")));
        TextView status = pageText("STATUS: " + m.getEffectiveStatus() + (m.isDelayed() && !m.getDelayReason().isEmpty() ? " — " + m.getDelayReason() : ""));
        status.setTextColor(getColor(matchStatusColor(m.getEffectiveStatus())));
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(status);
        if (m.isRoomReleased()) {
            card.addView(pageText("Room ID: " + m.getRoomId() + "   Password: " + m.getRoomPassword()));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        rp.topMargin = dp(8);
        row.setLayoutParams(rp);
        row.addView(inlineButton("EDIT", R.color.accent_blue_tint, R.color.accent_blue_stroke, v -> showMatchDialog(t, m)));
        row.addView(inlineButton("ROOM", R.color.accent_green_tint, R.color.accent_green_stroke, v -> showMatchRoomDialog(m)));
        row.addView(inlineButton(m.getStatus().equals(com.arenax.tournament.model.Match.LIVE) ? "SET COMPLETED" : "SET LIVE",
                R.color.accent_yellow_tint, R.color.accent_yellow_stroke, v -> {
                    String next = com.arenax.tournament.model.Match.LIVE.equals(m.getStatus())
                            ? com.arenax.tournament.model.Match.COMPLETED : com.arenax.tournament.model.Match.LIVE;
                    FirebaseRepository.setMatchStatus(m.getId(), next);
                    FirebaseRepository.logActivity("MATCH_STATUS", m.getName(), next);
                    if (com.arenax.tournament.model.Match.COMPLETED.equals(next)) {
                        FirebaseRepository.publishNotification("Match Completed", m.getName() + " has ended. Check results & leaderboard.");
                    } else {
                        FirebaseRepository.publishNotification("Match Live", m.getName() + " is now LIVE.");
                    }
                    toast("Match status updated.");
                    showMatchesForTournament(t);
                }));
        row.addView(inlineButton("DELETE", R.color.accent_purple_tint, R.color.accent_purple_stroke, v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Delete " + m.getName() + "?")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("DELETE", (d, w) -> {
                            FirebaseRepository.deleteMatch(m.getId());
                            FirebaseRepository.logActivity("MATCH_DELETED", m.getName(), "");
                            toast("Match deleted.");
                            showMatchesForTournament(t);
                        }).show()));
        card.addView(row);
        return card;
    }

    private int matchStatusColor(String status) {
        switch (status) {
            case "LIVE": return R.color.blood_red;
            case "ROOM_RELEASED": return R.color.accent_green;
            case "WAITING": return R.color.accent_yellow;
            case "DELAYED": return R.color.accent_yellow;
            case "COMPLETED": return R.color.text_secondary;
            case "CANCELLED": return R.color.text_muted;
            default: return R.color.accent_blue;
        }
    }

    private void showMatchDialog(Tournament t, com.arenax.tournament.model.Match existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText name = field("Match name (e.g. Round 1 — Group A)");
        EditText round = field("Round number");
        round.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText map = field("Map (e.g. Bermuda, Purgatory, Kalahari)");
        EditText mode = field("Mode (e.g. Squad, Clash Squad)");
        EditText date = field("Scheduled date (e.g. 20 SEP 2026)");
        EditText time = field("Scheduled time (e.g. 08:30 PM)");
        EditText maxSlots = field("Max slots");
        maxSlots.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText rules = field("Match rules (optional)");
        if (existing != null) {
            name.setText(existing.getName());
            round.setText(String.valueOf(existing.getRound()));
            map.setText(existing.getMap());
            mode.setText(existing.getMode());
            if (existing.getScheduledAt() > 0) {
                Date d = new Date(existing.getScheduledAt());
                date.setText(new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(d));
                time.setText(new SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(d));
            }
            maxSlots.setText(String.valueOf(existing.getMaxSlots()));
            rules.setText(existing.getRules());
        } else {
            round.setText("1");
            mode.setText(t.getMatchType());
            maxSlots.setText(String.valueOf(t.getTotalSlots()));
        }
        form.addView(name); form.addView(round); form.addView(map); form.addView(mode);
        form.addView(date); form.addView(time); form.addView(maxSlots); form.addView(rules);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(form);

        new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? "Create match" : "Edit match")
                .setMessage("Countdown on the User App is computed from this exact date/time, not a client timer — it stays correct after refresh.")
                .setView(scroll)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", (d, w) -> {
                    long scheduledAt = parseDateTime(date.getText().toString().trim(), time.getText().toString().trim());
                    com.arenax.tournament.model.Match match = new com.arenax.tournament.model.Match(
                            existing == null ? null : existing.getId(), t.getId(), t.getTitle(),
                            name.getText().toString().trim(), numberOrDefault(round.getText().toString().trim(), 1),
                            map.getText().toString().trim(), mode.getText().toString().trim(), scheduledAt,
                            numberOrDefault(maxSlots.getText().toString().trim(), 0), rules.getText().toString().trim(),
                            existing == null ? com.arenax.tournament.model.Match.UPCOMING : existing.getStatus(),
                            existing == null ? "" : existing.getRoomId(), existing == null ? "" : existing.getRoomPassword(),
                            existing != null && existing.isRoomReleased(), existing == null ? 0L : existing.getRoomReleasedAt(),
                            existing == null ? "" : existing.getDelayReason(), existing == null ? 0L : existing.getDelayExpectedAt());
                    FirebaseRepository.saveMatch(match)
                            .addOnSuccessListener(unused -> {
                                FirebaseRepository.logActivity(existing == null ? "MATCH_CREATED" : "MATCH_EDITED", match.getName(), t.getTitle());
                                if (existing == null) FirebaseRepository.publishNotification("New Match Scheduled", match.getName() + " • " + t.getTitle());
                                toast(existing == null ? "Match created." : "Match updated.");
                                showMatchesForTournament(t);
                            })
                            .addOnFailureListener(error -> toast(error.getMessage()));
                })
                .show();
    }

    private void showMatchRoomDialog(com.arenax.tournament.model.Match m) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        TextView status = new TextView(this);
        status.setText(m.isRoomReleased() ? "ROOM CURRENTLY RELEASED TO PLAYERS" : "ROOM CURRENTLY HIDDEN");
        status.setTextColor(m.isRoomReleased() ? Color.rgb(46, 204, 143) : Color.LTGRAY);
        status.setTextSize(12);
        status.setPadding(0, 0, 0, dp(10));
        form.addView(status);

        EditText roomId = field("Room ID");
        roomId.setText(m.getRoomId());
        EditText roomPass = field("Room Password");
        roomPass.setText(m.getRoomPassword());
        EditText delay = field("Delay reason (leave empty if not delayed)");
        delay.setText(m.getDelayReason());
        form.addView(roomId); form.addView(roomPass); form.addView(delay);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Room control • " + m.getName())
                .setMessage("Release sends Room ID + Password live to this match's participants instantly. A delay reason shows \"MATCH DELAYED — WAIT FOR ROOM ID & PASSWORD\" on the User App.")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setNeutralButton(m.isRoomReleased() ? "HIDE ROOM" : "RELEASE ROOM", null)
                .setPositiveButton(delay.getText().toString().trim().isEmpty() ? "SAVE" : "SAVE + MARK DELAYED", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String reason = delay.getText().toString().trim();
                if (!reason.isEmpty()) {
                    FirebaseRepository.setMatchDelay(m.getId(), reason, 0L);
                    FirebaseRepository.publishNotification("Match Delayed", m.getName() + " delayed: " + reason);
                    FirebaseRepository.logActivity("MATCH_DELAYED", m.getName(), reason);
                } else {
                    FirebaseRepository.clearMatchDelay(m.getId());
                }
                toast("Room details saved.");
                dialog.dismiss();
            });
            dialog.getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (m.isRoomReleased()) {
                    FirebaseRepository.hideMatchRoom(m.getId());
                    FirebaseRepository.logActivity("MATCH_ROOM_HIDDEN", m.getName(), "");
                    toast("Room hidden from players.");
                } else {
                    String id = roomId.getText().toString().trim();
                    String pass = roomPass.getText().toString().trim();
                    FirebaseRepository.releaseMatchRoom(m.getId(), id, pass);
                    FirebaseRepository.logActivity("MATCH_ROOM_RELEASED", m.getName(), "Room " + id);
                    FirebaseRepository.publishNotification("Room Released", m.getName() + " room ID & password are now live.");
                    sendRoomReleasePush("match", m.getId(),
                            m.getName() + " — Room Released!",
                            "Room ID: " + id + " | Password: " + pass);
                    toast("Room released live to players.");
                }
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    // ---------------------------------------------------------------------
    // A12 — Match Results entry: pick a tournament -> enter kills/placement per participant
    // ---------------------------------------------------------------------
    private void loadResultsPage() {
        workspaceContent.addView(pageText("Pick a tournament to enter kills + placement per participant. Points auto-calculate and push straight to the leaderboard."));
        for (Tournament t : tournaments) {
            workspaceContent.addView(resultsTournamentButton(t));
        }
        if (tournaments.isEmpty()) workspaceContent.addView(pageText("No tournaments published yet."));
    }

    private MaterialButton resultsTournamentButton(Tournament t) {
        return pageButtonTonal(t.getTitle() + "  •  ENTER RESULTS", R.color.accent_blue, R.color.accent_blue_stroke,
                v -> showResultsEntryDialog(t));
    }

    private void showResultsEntryDialog(Tournament t) {
        FirebaseRepository.tournamentParticipants(t.getId()).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                    toast("No registered participants yet for " + t.getTitle() + ".");
                    return;
                }
                LinearLayout form = new LinearLayout(AdminActivity.this);
                form.setOrientation(LinearLayout.VERTICAL);
                form.setPadding(dp(24), dp(8), dp(24), dp(8));
                List<String> uids = new ArrayList<>();
                List<EditText> killFields = new ArrayList<>();
                List<EditText> placeFields = new ArrayList<>();
                for (DataSnapshot participant : snapshot.getChildren()) {
                    String uid = participant.getKey();
                    String teamName = String.valueOf(participant.child("teamName").getValue());
                    TextView rowLabel = pageText((teamName == null || teamName.isEmpty() || "null".equals(teamName)
                            ? "Player " + (uids.size() + 1) : teamName) + "  (uid " + uid + ")");
                    rowLabel.setTextColor(getColor(R.color.text_primary));
                    form.addView(rowLabel);
                    EditText kills = field("Kills");
                    kills.setInputType(InputType.TYPE_CLASS_NUMBER);
                    EditText placement = field("Placement (1 = winner)");
                    placement.setInputType(InputType.TYPE_CLASS_NUMBER);
                    form.addView(kills);
                    form.addView(placement);
                    uids.add(uid);
                    killFields.add(kills);
                    placeFields.add(placement);
                }
                android.widget.ScrollView scroll = new android.widget.ScrollView(AdminActivity.this);
                scroll.addView(form);
                new MaterialAlertDialogBuilder(AdminActivity.this)
                        .setTitle("Enter results • " + t.getTitle())
                        .setView(scroll)
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("PUBLISH RESULTS", (d, w) -> {
                             boolean invalid = false;
                             int maxPlacement = Math.max(1, t.getTotalSlots());
                             int publishedRows = 0;
                            for (int i = 0; i < uids.size(); i++) {
                                 String killsText = killFields.get(i).getText().toString().trim();
                                 String placementText = placeFields.get(i).getText().toString().trim();
                                 if (placementText.isEmpty()) continue;
                                 int kills = numberOrDefault(killsText, 0);
                                 int placement = numberOrDefault(placementText, 0);
                                 if (kills < 0 || placement < 1 || placement > maxPlacement) {
                                     invalid = true;
                                     break;
                                 }
                             }
                             if (invalid) {
                                 toast("Check results: kills cannot be negative and placement must be between 1 and "
                                         + maxPlacement + ".");
                                 return;
                             }
                             for (int i = 0; i < uids.size(); i++) {
                                 String placementText = placeFields.get(i).getText().toString().trim();
                                 if (placementText.isEmpty()) continue;
                                 int kills = numberOrDefault(killFields.get(i).getText().toString().trim(), 0);
                                 int placement = numberOrDefault(placementText, 0);
                                FirebaseRepository.publishResult(t.getId(), uids.get(i), t.getTitle(), kills, placement);
                                 publishedRows++;
                             }
                             if (publishedRows == 0) {
                                 toast("Enter at least one placement before publishing.");
                                 return;
                            }
                            FirebaseRepository.logActivity("RESULT_PUBLISHED", t.getTitle(),
                                     publishedRows + " participant rows entered");
                            FirebaseRepository.publishNotification("Result Published",
                                    t.getTitle() + " results are live. Check the leaderboard!");
                            toast("Results published. Leaderboard updated live.");
                        })
                        .show();
            }
            @Override public void onCancelled(DatabaseError error) { toast(error.getMessage()); }
        });
    }

    // ---------------------------------------------------------------------
    // A13 — Result Disputes queue
    // ---------------------------------------------------------------------
    private void loadDisputesPage() {
        workspaceContent.addView(pageText("Player-submitted result disputes. Review, resolve or reject each one."));
        FirebaseRepository.disputes().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                    workspaceContent.addView(pageText("No disputes right now."));
                    return;
                }
                for (DataSnapshot child : snapshot.getChildren()) {
                    String id = child.getKey();
                    String title = String.valueOf(child.child("tournamentTitle").getValue());
                    String reason = String.valueOf(child.child("reason").getValue());
                    String description = String.valueOf(child.child("description").getValue());
                    String status = String.valueOf(child.child("status").getValue());
                    workspaceContent.addView(pageText(title + "  •  " + reason + "  •  STATUS: " + status));
                    workspaceContent.addView(pageText(description));
                    LinearLayout row = new LinearLayout(AdminActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
                    rp.bottomMargin = dp(14);
                    row.setLayoutParams(rp);
                    row.addView(inlineButton("REVIEW", R.color.accent_blue_tint, R.color.accent_blue_stroke, v ->
                            updateDispute(id, "UNDER_REVIEW", title)));
                    row.addView(inlineButton("RESOLVE", R.color.accent_green_tint, R.color.accent_green_stroke, v ->
                            updateDispute(id, "RESOLVED", title)));
                    row.addView(inlineButton("REJECT", R.color.accent_purple_tint, R.color.accent_purple_stroke, v ->
                            updateDispute(id, "REJECTED", title)));
                    workspaceContent.addView(row);
                }
            }
            @Override public void onCancelled(DatabaseError error) { workspaceContent.addView(pageText("Disputes unavailable: " + error.getMessage())); }
        });
    }

    private void updateDispute(String id, String status, String title) {
        FirebaseRepository.updateDisputeStatus(id, status, "")
                .addOnSuccessListener(u -> {
                    FirebaseRepository.logActivity("DISPUTE_" + status, title, "Dispute " + id);
                    toast("Dispute marked " + status + ".");
                })
                .addOnFailureListener(e -> toast(e.getMessage()));
    }

    // ---------------------------------------------------------------------
    // A14 — Reports & Moderation queue
    // ---------------------------------------------------------------------
    private void loadReportsPage() {
        workspaceContent.addView(pageText("Player-submitted reports. Warn, restrict, suspend or ban the reported user, or dismiss the report."));
        pageButtonTonal("EXPORT REPORTS CSV", R.color.accent_blue, R.color.accent_blue_stroke,
                v -> exportReportsCsv());
        FirebaseRepository.reports().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                    workspaceContent.addView(pageText("No reports right now."));
                    return;
                }
                for (DataSnapshot child : snapshot.getChildren()) {
                    String id = child.getKey();
                    String targetType = String.valueOf(child.child("targetType").getValue());
                    String targetLabel = String.valueOf(child.child("targetLabel").getValue());
                    String reason = String.valueOf(child.child("reason").getValue());
                    String description = String.valueOf(child.child("description").getValue());
                    String status = String.valueOf(child.child("status").getValue());
                    workspaceContent.addView(pageText(targetType + ": " + targetLabel + "  •  " + reason + "  •  STATUS: " + status));
                    workspaceContent.addView(pageText(description));
                    LinearLayout row = new LinearLayout(AdminActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
                    rp.bottomMargin = dp(14);
                    row.setLayoutParams(rp);
                    row.addView(inlineButton("DISMISS", R.color.accent_blue_tint, R.color.accent_blue_stroke, v ->
                            updateReport(id, "DISMISSED", targetLabel)));
                    row.addView(inlineButton("RESOLVE", R.color.accent_green_tint, R.color.accent_green_stroke, v ->
                            updateReport(id, "RESOLVED", targetLabel)));
                    workspaceContent.addView(row);
                }
                workspaceContent.addView(pageText("To warn, restrict, suspend or ban a specific player, open Users and use their user ID with the moderation dialog below."));
                pageButtonTonal("MODERATE A USER BY UID", R.color.accent_purple, R.color.accent_purple_stroke,
                        v -> showModerateUserDialog());
            }
            @Override public void onCancelled(DatabaseError error) { workspaceContent.addView(pageText("Reports unavailable: " + error.getMessage())); }
        });
    }

    private void updateReport(String id, String status, String target) {
        FirebaseRepository.updateReportStatus(id, status, "")
                .addOnSuccessListener(u -> {
                    FirebaseRepository.logActivity("REPORT_" + status, target, "Report " + id);
                    toast("Report marked " + status + ".");
                })
                .addOnFailureListener(e -> toast(e.getMessage()));
    }

    /** A21 — lightweight CSV export that works without storage permissions or a backend job. */
    private void exportReportsCsv() {
        FirebaseRepository.reports().get().addOnSuccessListener(snapshot -> {
            StringBuilder csv = new StringBuilder("id,targetType,targetLabel,reason,status,userId,createdAt,description\n");
            for (DataSnapshot child : snapshot.getChildren()) {
                csv.append(csvCell(child.getKey())).append(',')
                        .append(csvCell(child.child("targetType").getValue(String.class))).append(',')
                        .append(csvCell(child.child("targetLabel").getValue(String.class))).append(',')
                        .append(csvCell(child.child("reason").getValue(String.class))).append(',')
                        .append(csvCell(child.child("status").getValue(String.class))).append(',')
                        .append(csvCell(child.child("userId").getValue(String.class))).append(',')
                        .append(csvCell(String.valueOf(child.child("createdAt").getValue()))).append(',')
                        .append(csvCell(child.child("description").getValue(String.class))).append('\n');
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_SUBJECT, "STARX24 reports export");
            share.putExtra(Intent.EXTRA_TEXT, csv.toString());
            startActivity(Intent.createChooser(share, "Share reports CSV"));
        }).addOnFailureListener(e -> toast("Could not export reports: " + e.getMessage()));
    }

    private String csvCell(String value) {
        if (value == null || "null".equals(value)) return "\"\"";
        return "\"" + value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\"";
    }

    private void showModerateUserDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        EditText uid = field("Player UID");
        form.addView(uid);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Moderate player")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("WARN", (d, w) -> moderate(uid.getText().toString().trim(), "WARNED"))
                .setPositiveButton("BAN", (d, w) -> moderate(uid.getText().toString().trim(), "BANNED"))
                .show();
    }

    private void moderate(String uid, String status) {
        if (uid.isEmpty()) { toast("Enter a UID."); return; }
        FirebaseRepository.setUserStatus(uid, status)
                .addOnSuccessListener(u -> {
                    FirebaseRepository.logActivity("USER_" + status, uid, "Moderation action");
                    toast("Player marked " + status + ".");
                })
                .addOnFailureListener(e -> toast(e.getMessage()));
    }

    // ---------------------------------------------------------------------
    // A16 — FAQ Management
    // ---------------------------------------------------------------------
    private void loadFaqPage() {
        pageButtonTonal("＋ NEW FAQ ENTRY", R.color.accent_green, R.color.accent_green_stroke, v -> showAddFaqDialog());
        FirebaseRepository.faq().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                    workspaceContent.addView(pageText("No FAQ entries yet. Add one above — it appears instantly in the User App Help Center."));
                    return;
                }
                for (DataSnapshot child : snapshot.getChildren()) {
                    String id = child.getKey();
                    String question = String.valueOf(child.child("question").getValue());
                    workspaceContent.addView(pageText("Q: " + question));
                    workspaceContent.addView(inlineButton("DELETE", R.color.accent_purple_tint, R.color.accent_purple_stroke, v -> {
                        FirebaseRepository.deleteFaq(id);
                        FirebaseRepository.logActivity("FAQ_DELETED", question, "FAQ " + id);
                        toast("FAQ removed.");
                    }));
                }
            }
            @Override public void onCancelled(DatabaseError error) { workspaceContent.addView(pageText("FAQ unavailable: " + error.getMessage())); }
        });
    }

    private void showAddFaqDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        EditText question = field("Question");
        EditText answer = field("Answer");
        EditText category = field("Category (e.g. Registration, Room ID, Result)");
        form.addView(question);
        form.addView(answer);
        form.addView(category);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Add FAQ entry")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("PUBLISH", (d, w) -> {
                    String q = question.getText().toString().trim();
                    String a = answer.getText().toString().trim();
                    if (q.isEmpty() || a.isEmpty()) { toast("Enter both a question and an answer."); return; }
                    FirebaseRepository.saveFaq(null, q, a, category.getText().toString().trim())
                            .addOnSuccessListener(u -> {
                                FirebaseRepository.logActivity("FAQ_CREATED", q, "");
                                toast("FAQ published live.");
                            })
                            .addOnFailureListener(e -> toast(e.getMessage()));
                })
                .show();
    }

    // ---------------------------------------------------------------------
    // A22 — Admin Activity Log
    // ---------------------------------------------------------------------
    private void loadActivityLogPage() {
        workspaceContent.addView(pageText("Every meaningful admin mutation is written here for audit."));
        pageButtonTonal("EXPORT ACTIVITY CSV", R.color.accent_blue, R.color.accent_blue_stroke,
                v -> exportActivityLogCsv());
        FirebaseRepository.activityLogs().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                    workspaceContent.addView(pageText("No activity recorded yet."));
                    return;
                }
                List<DataSnapshot> entries = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) entries.add(child);
                for (int i = entries.size() - 1; i >= 0; i--) {
                    DataSnapshot entry = entries.get(i);
                    String admin = String.valueOf(entry.child("admin").getValue());
                    String action = String.valueOf(entry.child("action").getValue());
                    String target = String.valueOf(entry.child("target").getValue());
                    workspaceContent.addView(pageText(admin + "  →  " + action + "  →  " + target));
                }
            }
            @Override public void onCancelled(DatabaseError error) { workspaceContent.addView(pageText("Activity log unavailable: " + error.getMessage())); }
        });
    }

    /** A22 — shareable audit export for compliance reviews and offline analysis. */
    private void exportActivityLogCsv() {
        FirebaseRepository.activityLogs().get().addOnSuccessListener(snapshot -> {
            StringBuilder csv = new StringBuilder("id,admin,action,target,details,timestamp\n");
            for (DataSnapshot child : snapshot.getChildren()) {
                csv.append(csvCell(child.getKey())).append(',')
                        .append(csvCell(child.child("admin").getValue(String.class))).append(',')
                        .append(csvCell(child.child("action").getValue(String.class))).append(',')
                        .append(csvCell(child.child("target").getValue(String.class))).append(',')
                        .append(csvCell(child.child("details").getValue(String.class))).append(',')
                        .append(csvCell(String.valueOf(child.child("timestamp").getValue()))).append('\n');
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_SUBJECT, "STARX24 activity log export");
            share.putExtra(Intent.EXTRA_TEXT, csv.toString());
            startActivity(Intent.createChooser(share, "Share activity CSV"));
        }).addOnFailureListener(e -> toast("Could not export activity: " + e.getMessage()));
    }

    private android.view.View inlineButton(String label, int tintRes, int strokeRes, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextColor(getColor(strokeRes));
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setCornerRadius(dp(12));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(tintRes)));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(getColor(strokeRes)));
        button.setStrokeWidth(dp(1));
        button.setElevation(0f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1);
        p.rightMargin = dp(6);
        button.setLayoutParams(p);
        button.setOnClickListener(listener);
        return button;
    }

    private void loadNoticePage() {
        pageButtonTonal("＋ NEW ANNOUNCEMENT", R.color.accent_blue, R.color.accent_blue_stroke, v -> showAnnouncementComposer());
        workspaceContent.addView(pageText("Target All Users, a specific Tournament or a specific Team. Pinned announcements stay at the top of the User App feed."));
        FirebaseRepository.announcements().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                    workspaceContent.addView(pageText("No announcements published yet."));
                    return;
                }
                List<DataSnapshot> entries = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) entries.add(child);
                for (int i = entries.size() - 1; i >= 0; i--) {
                    DataSnapshot a = entries.get(i);
                    String id = a.getKey();
                    String title = String.valueOf(a.child("title").getValue());
                    String targetType = String.valueOf(a.child("targetType").getValue());
                    String targetId = String.valueOf(a.child("targetId").getValue());
                    boolean pinned = Boolean.TRUE.equals(a.child("pinned").getValue());
                    String target = "ALL".equals(targetType) ? "ALL USERS"
                            : targetType + (targetId == null || targetId.isEmpty() || "null".equals(targetId) ? "" : " • " + targetId);
                    Long scheduledAt = a.child("scheduledAt").getValue(Long.class);
                    Long expiresAt = a.child("expiresAt").getValue(Long.class);
                    String timing = scheduledAt != null && scheduledAt > System.currentTimeMillis()
                            ? " • SCHEDULED " + formatAnnouncementTime(scheduledAt) : "";
                    if (expiresAt != null && expiresAt > 0L) timing += " • EXPIRES " + formatAnnouncementTime(expiresAt);
                    workspaceContent.addView(pageText((pinned ? "PINNED • " : "") + title + "  —  " + target + timing));
                    workspaceContent.addView(inlineButton("DELETE", R.color.accent_purple_tint, R.color.accent_purple_stroke, v -> {
                        FirebaseRepository.deleteAnnouncement(id);
                        FirebaseRepository.logActivity("ANNOUNCEMENT_DELETED", title, "");
                        toast("Announcement removed.");
                    }));
                }
            }
            @Override public void onCancelled(DatabaseError error) { workspaceContent.addView(pageText("Announcements unavailable: " + error.getMessage())); }
        });
    }

    private void showAnnouncementComposer() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText title = field("Announcement title");
        EditText message = field("Message");
        Spinner target = new Spinner(this);
        String[] targets = {"ALL USERS", "SPECIFIC TOURNAMENT", "SPECIFIC TEAM"};
        target.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, targets));
        EditText targetId = field("Tournament or Team ID (leave blank for All Users)");
        EditText scheduledAt = field("Publish at (dd MMM yyyy HH:mm, blank = now)");
        EditText expiresAt = field("Expires at (dd MMM yyyy HH:mm, blank = never)");
        android.widget.CheckBox pin = new android.widget.CheckBox(this);
        pin.setText("Pin to top of feed");
        pin.setTextColor(getColor(R.color.text_primary));

        form.addView(title);
        form.addView(message);
        form.addView(target);
        form.addView(targetId);
        form.addView(scheduledAt);
        form.addView(expiresAt);
        form.addView(pin);

        new MaterialAlertDialogBuilder(this)
                .setTitle("New announcement")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("PUBLISH", (d, w) -> {
                    String titleValue = title.getText().toString().trim();
                    String messageValue = message.getText().toString().trim();
                    if (titleValue.isEmpty() || messageValue.isEmpty()) {
                        toast("Enter both a title and a message.");
                        return;
                    }
                    String targetType = target.getSelectedItemPosition() == 1 ? "TOURNAMENT"
                            : target.getSelectedItemPosition() == 2 ? "TEAM" : "ALL";
                    String targetIdValue = targetId.getText().toString().trim();
                    if (!"ALL".equals(targetType) && targetIdValue.isEmpty()) {
                        toast("Enter the target tournament or team ID.");
                        return;
                    }
                    long scheduledMillis = parseAnnouncementTime(scheduledAt.getText().toString().trim());
                    long expiresMillis = parseAnnouncementTime(expiresAt.getText().toString().trim());
                    if (!scheduledAt.getText().toString().trim().isEmpty() && scheduledMillis == 0L) {
                        toast("Use date format: 07 Sep 2026 20:30");
                        return;
                    }
                    if (!expiresAt.getText().toString().trim().isEmpty() && expiresMillis == 0L) {
                        toast("Use date format: 07 Sep 2026 20:30");
                        return;
                    }
                    if (scheduledMillis > 0L && expiresMillis > 0L && expiresMillis <= scheduledMillis) {
                        toast("Expiry must be after publish time.");
                        return;
                    }
                    FirebaseRepository.publishAnnouncement(titleValue, messageValue, targetType,
                                    targetIdValue, pin.isChecked(), scheduledMillis, expiresMillis)
                            .addOnSuccessListener(u -> {
                                FirebaseRepository.logActivity("ANNOUNCEMENT_PUBLISHED", titleValue, targetType);
                                toast("Announcement published live.");
                            })
                            .addOnFailureListener(e -> toast(e.getMessage()));
                })
                .show();
    }

    private void loadAnalyticsPage() {
        workspaceContent.addView(pageText("Live metrics are read from Firebase. Refresh this page whenever you need a current platform snapshot. Status bars make the distribution easier to scan."));
        pageButtonTonal("↻ REFRESH LIVE METRICS", R.color.accent_blue, R.color.accent_blue_stroke,
                v -> showWorkspacePage("analytics"));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(10);
        grid.setLayoutParams(gridParams);
        workspaceContent.addView(grid);
        addAnalyticsMetricRow(grid, "USERS", FirebaseRepository.users(),
                "TOURNAMENTS", FirebaseRepository.tournaments());
        addAnalyticsMetricRow(grid, "MATCHES", FirebaseRepository.matches(),
                "TEAMS", FirebaseRepository.teams());
        addAnalyticsMetricRow(grid, "SUPPORT TICKETS", FirebaseRepository.support(),
                "REPORTS", FirebaseRepository.reports());
        addAnalyticsMetricRow(grid, "DISPUTES", FirebaseRepository.disputes(),
                "ACTIVITY EVENTS", FirebaseRepository.activityLogs());

        TextView registrationSummary = pageText("REGISTRATIONS: loading…");
        registrationSummary.setTextColor(getColor(R.color.accent_green));
        registrationSummary.setTypeface(null, android.graphics.Typeface.BOLD);
        workspaceContent.addView(registrationSummary);
        FirebaseRepository.tournaments().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                long registrations = 0;
                for (DataSnapshot child : snapshot.getChildren()) {
                    Number joined = child.child("joinedSlots").getValue(Number.class);
                    if (joined != null) registrations += Math.max(0, joined.longValue());
                }
                registrationSummary.setText("REGISTRATIONS: " + registrations + "  •  FREE ENTRY ONLY");
            }
            @Override public void onCancelled(DatabaseError error) {
                registrationSummary.setText("REGISTRATIONS: unavailable");
            }
        });

        workspaceContent.addView(pageText("TOURNAMENT STATUS BREAKDOWN"));
        TextView tournamentStatus = pageText("Loading tournament statuses…");
        workspaceContent.addView(tournamentStatus);
        LinearLayout tournamentBars = new LinearLayout(this);
        tournamentBars.setOrientation(LinearLayout.VERTICAL);
        workspaceContent.addView(tournamentBars);
        FirebaseRepository.tournaments().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String status = child.child("status").getValue(String.class);
                    if (status == null || status.trim().isEmpty()) status = "UNKNOWN";
                    counts.put(status, counts.containsKey(status) ? counts.get(status) + 1 : 1);
                }
                tournamentStatus.setText(formatCounts(counts, "No tournaments yet."));
                renderAnalyticsBars(tournamentBars, counts, R.color.blood_red);
            }
            @Override public void onCancelled(DatabaseError error) {
                tournamentStatus.setText("Tournament status data unavailable.");
            }
        });

        workspaceContent.addView(pageText("MATCH STATUS BREAKDOWN"));
        TextView matchStatus = pageText("Loading match statuses…");
        workspaceContent.addView(matchStatus);
        LinearLayout matchBars = new LinearLayout(this);
        matchBars.setOrientation(LinearLayout.VERTICAL);
        workspaceContent.addView(matchBars);
        FirebaseRepository.matches().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String status = child.child("status").getValue(String.class);
                    if (status == null || status.trim().isEmpty()) status = "UNKNOWN";
                    counts.put(status, counts.containsKey(status) ? counts.get(status) + 1 : 1);
                }
                matchStatus.setText(formatCounts(counts, "No matches yet."));
                renderAnalyticsBars(matchBars, counts, R.color.accent_blue);
            }
            @Override public void onCancelled(DatabaseError error) {
                matchStatus.setText("Match status data unavailable.");
            }
        });
    }

    private void addAnalyticsMetricRow(LinearLayout grid, String leftLabel, DatabaseReference leftRef,
                                       String rightLabel, DatabaseReference rightRef) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82));
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);
        TextView leftValue = analyticsValue();
        TextView rightValue = analyticsValue();
        addAnalyticsCard(row, leftLabel, leftValue, R.color.accent_blue);
        addAnalyticsCard(row, rightLabel, rightValue, R.color.accent_purple);
        grid.addView(row);
        loadAnalyticsCount(leftRef, leftValue);
        loadAnalyticsCount(rightRef, rightValue);
    }

    private TextView analyticsValue() {
        TextView value = new TextView(this);
        value.setText("…");
        value.setTextColor(getColor(R.color.text_primary));
        value.setTextSize(22);
        value.setTypeface(null, android.graphics.Typeface.BOLD);
        return value;
    }

    private void addAnalyticsCard(LinearLayout row, String label, TextView value, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(8));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(getColor(R.color.surface));
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), getColor(accent));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        card.setLayoutParams(params);
        TextView title = pageText(label);
        title.setTextColor(getColor(accent));
        title.setTextSize(9);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);
        card.addView(value);
        row.addView(card);
    }

    private void loadAnalyticsCount(DatabaseReference reference, TextView value) {
        reference.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                value.setText(String.valueOf(snapshot.getChildrenCount()));
            }
            @Override public void onCancelled(DatabaseError error) {
                value.setText("—");
            }
        });
    }

    private String formatCounts(Map<String, Integer> counts, String emptyText) {
        if (counts.isEmpty()) return emptyText;
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (text.length() > 0) text.append("   •   ");
            text.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return text.toString();
    }

    private void renderAnalyticsBars(LinearLayout container, Map<String, Integer> counts, int accent) {
        container.removeAllViews();
        int max = 0;
        for (Integer value : counts.values()) max = Math.max(max, value == null ? 0 : value);
        if (max == 0) {
            container.addView(pageText("No data to chart yet."));
            return;
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = pageText(entry.getKey() + "  " + entry.getValue());
            label.setTextSize(11);
            label.setSingleLine(true);
            row.addView(label, new LinearLayout.LayoutParams(dp(124), dp(32)));
            View bar = new View(this);
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(getColor(accent));
            background.setCornerRadius(dp(4));
            bar.setBackground(background);
            int width = Math.max(dp(12), dp(190) * entry.getValue() / max);
            row.addView(bar, new LinearLayout.LayoutParams(width, dp(10)));
            container.addView(row);
        }
    }

    private String formatAnnouncementTime(long millis) {
        return new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.ENGLISH).format(new Date(millis));
    }

    private long parseAnnouncementTime(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            Date parsed = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.ENGLISH).parse(value.trim());
            return parsed == null ? 0L : parsed.getTime();
        } catch (ParseException ignored) {
            return 0L;
        }
    }

    /** A26 — one search entry point across the main admin data collections. */
    private void showGlobalSearchDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(6), dp(22), dp(4));
        EditText query = field("Search users, teams, tournaments, matches, tickets or reports");
        form.addView(query);
        TextView hint = pageText("Search is read-only and shows matching live Firebase records.");
        form.addView(hint);
        android.widget.ScrollView resultsScroll = new android.widget.ScrollView(this);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        resultsScroll.addView(results);
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(250));
        resultsParams.topMargin = dp(10);
        resultsScroll.setLayoutParams(resultsParams);
        form.addView(resultsScroll);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Global Search")
                .setView(form)
                .setNegativeButton("CLOSE", null)
                .setPositiveButton("SEARCH", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String term = query.getText().toString().trim().toLowerCase(Locale.ENGLISH);
            results.removeAllViews();
            if (term.isEmpty()) {
                results.addView(pageText("Enter a search term first."));
                return;
            }
            int[] pending = {7};
            searchCollection("TOURNAMENT", FirebaseRepository.tournaments(), term, results, pending);
            searchCollection("USER", FirebaseRepository.users(), term, results, pending);
            searchCollection("TEAM", FirebaseRepository.teams(), term, results, pending);
            searchCollection("MATCH", FirebaseRepository.matches(), term, results, pending);
            searchCollection("TICKET", FirebaseRepository.support(), term, results, pending);
            searchCollection("REPORT", FirebaseRepository.reports(), term, results, pending);
            searchCollection("DISPUTE", FirebaseRepository.disputes(), term, results, pending);
        }));
        dialog.show();
    }

    private void searchCollection(String label, DatabaseReference reference, String term,
                                  LinearLayout results, int[] pending) {
        reference.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                for (DataSnapshot child : snapshot.getChildren()) {
                    String raw = String.valueOf(child.getValue()).toLowerCase(Locale.ENGLISH);
                    String key = child.getKey() == null ? "" : child.getKey().toLowerCase(Locale.ENGLISH);
                    if (raw.contains(term) || key.contains(term)) {
                        String preview = String.valueOf(child.getValue()).replace("\n", " ");
                        if (preview.length() > 150) preview = preview.substring(0, 150) + "…";
                        results.addView(pageText(label + "  •  " + child.getKey() + "\n" + preview));
                    }
                }
                finishSearchCollection(results, pending);
            }
            @Override public void onCancelled(DatabaseError error) {
                finishSearchCollection(results, pending);
            }
        });
    }

    private void finishSearchCollection(LinearLayout results, int[] pending) {
        pending[0]--;
        if (pending[0] == 0 && results.getChildCount() == 1) {
            results.addView(pageText("No matching records found."));
        }
    }

    private void loadSettingsPage() {
        pageButtonTonal("＋ CHANGE APP LOGO", R.color.accent_yellow, R.color.accent_yellow_stroke, v -> showAppLogoDialog());
        pageButtonTonal("SUPPORT TICKETS", R.color.accent_green, R.color.accent_green_stroke, v -> {
            Intent page = new Intent(this, SupportActivity.class);
            page.putExtra("adminMode", true);
            startActivity(page);
        });
        pageButtonTonal("OPEN DASHBOARD SETTINGS", R.color.accent_blue, R.color.accent_blue_stroke, v -> showWorkspacePage("dashboard"));
        pageButtonTonal("⚡ SEED DEMO DATA", R.color.accent_yellow, R.color.accent_yellow_stroke, v -> confirmSeedDemoData());
    }

    /** Demo Data Requirement (master prompt): one confirm dialog, then a realistic non-financial
     *  seed — categories, 8 tournaments across Upcoming/Ongoing/Completed, 6 teams, matches in
     *  Live/Waiting/Room-Released states, a 20-row leaderboard, FAQ, an announcement and two
     *  support tickets — useful for demos and for exercising every screen with real-looking data. */
    private void confirmSeedDemoData() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Seed demo data?")
                .setMessage("This adds realistic Free Fire Max sample tournaments, teams, matches, leaderboard rows, FAQ, an announcement and support tickets to this Firebase project. It will not remove any existing data, and adds nothing payment or wallet related.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SEED NOW", (dialog, which) ->
                        FirebaseRepository.seedDemoData()
                                .addOnSuccessListener(unused -> {
                                    Toast.makeText(this, "Demo data seeded — check Tournaments, Teams and Leaderboard.", Toast.LENGTH_LONG).show();
                                    FirebaseRepository.logActivity("Seeded demo data", "platform", "Categories, tournaments, teams, matches, leaderboard, FAQ, announcement, tickets");
                                })
                                .addOnFailureListener(error -> toast("Seeding failed: " + error.getMessage())))
                .show();
    }

    private void showAppLogoDialog() {
        selectedLogoImage = null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));

        EditText logoUrl = field("PNG/JPG logo URL (optional)");
        form.addView(logoUrl);

        MaterialButton chooseImage = new MaterialButton(this);
        chooseImage.setText("SELECT LOGO FROM DEVICE");
        chooseImage.setTextColor(getColor(R.color.text_primary));
        chooseImage.setMaxLines(1);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chooseParams.topMargin = dp(6);
        chooseParams.bottomMargin = dp(10);
        chooseImage.setLayoutParams(chooseParams);
        chooseImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, 7002);
        });
        form.addView(chooseImage);

        logoPreview = new ImageView(this);
        logoPreview.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        logoPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logoPreview.setBackgroundResource(R.drawable.bg_game_card_blue);
        form.addView(logoPreview);

        logoDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Change app logo")
                .setMessage("This replaces the STARX24 logo shown on every user's home screen, live from Firebase.")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE LOGO", null)
                .create();
        logoDialog.setOnShowListener(ignored -> logoDialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = logoUrl.getText().toString().trim();
            if (selectedLogoImage == null && url.isEmpty()) {
                Toast.makeText(this, "Add a logo URL or select an image.", Toast.LENGTH_LONG).show();
                return;
            }
            if (selectedLogoImage == null) {
                saveAppLogo(url);
                return;
            }
            Toast.makeText(this, "Uploading logo securely…", Toast.LENGTH_SHORT).show();
            com.arenax.tournament.data.ImgBbUploader.uploadImage(this, selectedLogoImage,
                    new com.arenax.tournament.data.StorageUploader.Callback() {
                        @Override public void onSuccess(String fileUrl) { saveAppLogo(fileUrl); }
                        @Override public void onFailure(String message) {
                            showUploadErrorDialog("Logo upload failed", message);
                        }
                    });
        }));
        logoDialog.show();
    }

    private void saveAppLogo(String logoUrl) {
        FirebaseRepository.appConfig().child("branding").child("logoUrl").setValue(logoUrl)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, "App logo updated live for all users.", Toast.LENGTH_SHORT).show();
                    if (logoDialog != null) logoDialog.dismiss();
                })
                .addOnFailureListener(error -> Toast.makeText(this, "Logo update failed: " + error.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void toast(String message) {
        Toast.makeText(this, message == null ? "Action failed" : message, Toast.LENGTH_LONG).show();
    }

    /** A small persistent caption placed above a field. Hints alone aren't enough for edit
     *  forms: once an EditText is pre-filled with an existing value, its hint is hidden, so a
     *  field like "position" pre-filled with "1" would otherwise show as an unlabeled box. */
    /**
     * Builds a dialog with a fixed header, a height-capped scrollable form, and a footer
     * with CANCEL / positive buttons that are ALWAYS visible — regardless of how tall the
     * form content is. This replaces MaterialAlertDialogBuilder's default button bar, which
     * gets pushed off-screen (and disappears) when the form is longer than the screen.
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
        titleView.setTextColor(getColor(R.color.text_primary));
        titleView.setTextSize(19f);
        titleView.setTypeface(null, Typeface.BOLD);
        header.addView(titleView);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(getColor(R.color.text_secondary));
            subtitleView.setTextSize(12.5f);
            subtitleView.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = dp(6);
            subtitleView.setLayoutParams(subtitleParams);
            header.addView(subtitleView);
        }
        root.addView(header);
        root.addView(dialogDivider());

        // Cap the form's height so header + footer always stay on screen; the form itself
        // scrolls internally if it's taller than this.
        int maxFormHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.5);
        LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxFormHeight);
        scrollableForm.setLayoutParams(formParams);
        root.addView(scrollableForm);
        root.addView(dialogDivider());

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(16), dp(12), dp(16), dp(14));

        MaterialButton cancelButton = new MaterialButton(this);
        cancelButton.setText("CANCEL");
        cancelButton.setTextColor(getColor(R.color.text_secondary));
        cancelButton.setBackgroundResource(R.drawable.bg_dialog_button_outline);
        cancelButton.setElevation(0f);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        cancelParams.rightMargin = dp(10);
        cancelButton.setLayoutParams(cancelParams);
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        footer.addView(cancelButton);

        MaterialButton positiveButton = new MaterialButton(this);
        positiveButton.setText(positiveLabel);
        positiveButton.setTextColor(getColor(R.color.text_primary));
        positiveButton.setBackgroundResource(R.drawable.bg_button_gradient);
        positiveButton.setElevation(0f);
        positiveButton.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));
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

    private View dialogDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.stroke));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return divider;
    }

    private TextView fieldLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.text_secondary));
        label.setTextSize(11f);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
        label.setLayoutParams(params);
        return label;
    }

    private EditText field(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary));
        input.setSingleLine(true);
        input.setBackgroundResource(R.drawable.bg_input);
        int hPad = dp(16);
        int vPad = dp(14);
        input.setPadding(hPad, vPad, hPad, vPad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        input.setLayoutParams(params);
        return input;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int numberOrDefault(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private long parseDateTime(String date, String time) {
        if (date == null || time == null || date.trim().isEmpty() || time.trim().isEmpty()
                || "TBA".equalsIgnoreCase(date) || "TBA".equalsIgnoreCase(time)) return 0L;
        String combined = date.trim() + " " + time.trim();
        String[] formats = {"dd MMM yyyy hh:mm a", "dd MMM yyyy HH:mm"};
        for (String format : formats) {
            try {
                Date parsed = new SimpleDateFormat(format, Locale.ENGLISH).parse(combined);
                if (parsed != null) return parsed.getTime();
            } catch (ParseException ignored) {
                // Keep the record publishable when an optional date is not parseable.
            }
        }
        return 0L;
    }

    private void publishNotice() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(4));
        EditText title = field("Notification title");
        EditText message = field("Message for all user devices");
        message.setMinLines(3);
        message.setGravity(Gravity.TOP);
        form.addView(title);
        form.addView(message);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Send Device Notification")
                .setMessage("This will create a broadcast for the user devices.")
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SEND", (dialog, which) -> {
                    String heading = title.getText().toString().trim();
                    String body = message.getText().toString().trim();
                    if (heading.isEmpty() || body.isEmpty()) {
                        Toast.makeText(this, "Enter both title and message.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    FirebaseRepository.publishNotification(heading, body)
                            .addOnSuccessListener(unused -> Toast.makeText(this, "Notification queued for devices.", Toast.LENGTH_SHORT).show())
                            .addOnFailureListener(error -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
                }).show();
    }

    /** Publishes the sticky red notice strip shown above the home banner in the User app
     *  (appConfig/homeNotice) — separate from the one-off push notice above. */
    private void publishHomeNotice() {
        EditText noticeTitleInput = findViewById(R.id.admin_home_notice_title_input);
        EditText noticeInput = findViewById(R.id.admin_home_notice_input);
        SwitchMaterial noticeSwitch = findViewById(R.id.admin_home_notice_switch);
        if (noticeInput == null || noticeSwitch == null) return;
        String title = noticeTitleInput == null ? "" : noticeTitleInput.getText().toString().trim();
        String text = noticeInput.getText().toString().trim();
        boolean enabled = noticeSwitch.isChecked();
        if (enabled && text.isEmpty()) {
            Toast.makeText(this, "Enter the notice text before enabling it.", Toast.LENGTH_LONG).show();
            return;
        }
        Map<String, Object> notice = new HashMap<>();
        notice.put("title", title);
        notice.put("text", text);
        notice.put("enabled", enabled);
        FirebaseRepository.appConfig().child("homeNotice").setValue(notice)
                .addOnSuccessListener(unused -> Toast.makeText(this, "Home notice updated.", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(error -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
    }
}
