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
import com.arenax.tournament.util.AccessTokenProvider;
import com.arenax.tournament.util.FcmSender;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

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

        form.addView(fie
