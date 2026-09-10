package com.arenax.tournament.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.R;
import com.arenax.tournament.adapter.BannerAdapter;
import com.arenax.tournament.adapter.GameModeAdapter;
import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.model.Banner;
import com.arenax.tournament.model.GameMode;
import com.bumptech.glide.Glide;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class HomeFragment extends Fragment {
    // Banner carousel auto-advances every 2.5s, matching the reference design.
    private static final long BANNER_AUTO_SLIDE_MS = 2500L;
    private final List<GameMode> gameModes = new ArrayList<>();
    private final List<Banner> banners = new ArrayList<>();
    private ValueEventListener gameModeListener;
    private ValueEventListener bannerListener;
    private ValueEventListener brandingListener;
    private ValueEventListener noticeListener;
    private ValueEventListener pointsListener;
    private LinearLayout dotsContainer;
    private RecyclerView bannerListView;
    private final android.os.Handler bannerAutoSlideHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable bannerAutoSlideRunnable = new Runnable() {
        @Override
        public void run() {
            if (bannerListView == null || banners.size() <= 1) return;
            RecyclerView.LayoutManager lm = bannerListView.getLayoutManager();
            if (!(lm instanceof LinearLayoutManager)) return;
            int current = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
            int next = (current + 1) % banners.size();
            bannerListView.smoothScrollToPosition(next);
            setActiveDot(next);
            bannerAutoSlideHandler.postDelayed(this, BANNER_AUTO_SLIDE_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        if (!FirebaseRepository.isReady(requireContext())) {
            android.widget.Toast.makeText(requireContext(),
                    "Live arena data is temporarily unavailable. Please try again.",
                    android.widget.Toast.LENGTH_LONG).show();
            return view;
        }
        brandingListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                String logoUrl = snapshot.child("branding").child("logoUrl").getValue(String.class);
                if (logoUrl == null || logoUrl.trim().isEmpty()) return;
                android.widget.ImageView logo = view.findViewById(R.id.home_logo_image);
                if (logo != null) Glide.with(HomeFragment.this).load(logoUrl).centerCrop().into(logo);
            }

            @Override
            public void onCancelled(DatabaseError error) {}
        };
        FirebaseRepository.appConfig().addValueEventListener(brandingListener);

        // Account icon replaces the old "LIVE ARENA" label: taps into the existing Profile
        // tab, which already carries login/account info plus support, team, FAQ and logout —
        // i.e. everything that normally lives under "settings" — rather than a new screen.
        View accountButton = view.findViewById(R.id.home_account_button);
        if (accountButton != null) {
            accountButton.setOnClickListener(v -> {
                com.google.android.material.bottomnavigation.BottomNavigationView nav =
                        requireActivity().findViewById(R.id.bottom_nav);
                if (nav != null) nav.setSelectedItemId(R.id.nav_profile);
            });
        }

        // Header points badge: shows the player's cumulative competitive score from the
        // existing leaderboard node (kills + placement based, not currency). Purely
        // informational/gamification — no wallet, balance or purchasable value.
        android.widget.TextView pointsValue = view.findViewById(R.id.home_points_value);
        String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if (uid != null && pointsValue != null) {
            pointsListener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    Long score = snapshot.child("score").getValue(Long.class);
                    pointsValue.setText(String.valueOf(score == null ? 0 : score));
                }

                @Override
                public void onCancelled(DatabaseError error) {}
            };
            FirebaseRepository.leaderboard().child("allTime").child(uid).addValueEventListener(pointsListener);
        }

        RecyclerView gameModeList = view.findViewById(R.id.home_game_mode_list);
        gameModeList.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        gameModeList.setNestedScrollingEnabled(false);
        gameModeList.setHasFixedSize(false);
        gameModeList.setAdapter(new GameModeAdapter(gameModes, false, null, mode -> {
            Intent intent = new Intent(requireContext(), com.arenax.tournament.CategoryMatchesActivity.class);
            intent.putExtra("categoryId", mode.getId());
            intent.putExtra("categoryTitle", mode.getTitle());
            startActivity(intent);
        }));
        RecyclerView bannerList = view.findViewById(R.id.home_banner_list);
        bannerListView = bannerList;
        dotsContainer = view.findViewById(R.id.home_banner_dots);
        bannerList.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bannerList.setAdapter(new BannerAdapter(banners, false, null));
        // PagerSnapHelper so each banner snaps fully into view at full width instead of a
        // half-cropped strip. Auto-slide is layered on top via bannerAutoSlideRunnable below,
        // and is paused while the user is actively touching/dragging the carousel.
        PagerSnapHelper snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(bannerList);
        bannerList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                if (!(lm instanceof LinearLayoutManager)) return;
                int position = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
                setActiveDot(position);
            }
        });
        bannerList.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    bannerAutoSlideHandler.removeCallbacks(bannerAutoSlideRunnable);
                } else if (e.getAction() == android.view.MotionEvent.ACTION_UP || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    restartBannerAutoSlide();
                }
                return false;
            }
        });
        bannerListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                banners.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Object raw = child.getValue();
                    if (!(raw instanceof java.util.Map)) continue;
                    Banner banner = Banner.fromMap(child.getKey(), (java.util.Map<String, Object>) raw);
                    if (banner != null && banner.isActive()) banners.add(banner);
                }
                Collections.sort(banners, Comparator.comparingInt(Banner::getDisplayOrder));
                bannerList.getAdapter().notifyDataSetChanged();
                rebuildDots();
                restartBannerAutoSlide();
            }

            @Override
            public void onCancelled(DatabaseError error) {}
        };
        FirebaseRepository.banners().addValueEventListener(bannerListener);

        // Admin-controlled notice strip shown above the banner carousel (appConfig/homeNotice).
        View noticeContainer = view.findViewById(R.id.home_notice_container);
        android.widget.TextView noticeText = view.findViewById(R.id.home_notice_text);
        noticeListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                DataSnapshot notice = snapshot.child("homeNotice");
                String text = notice.child("text").getValue(String.class);
                String title = notice.child("title").getValue(String.class);
                Boolean enabled = notice.child("enabled").getValue(Boolean.class);
                boolean show = Boolean.TRUE.equals(enabled) && text != null && !text.trim().isEmpty();
                if (noticeContainer != null) noticeContainer.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    noticeText.setText(text);
                    noticeText.setSelected(true); // needed for marquee to actually scroll
                    final String finalTitle = title;
                    final String finalText = text;
                    if (noticeContainer != null) {
                        noticeContainer.setOnClickListener(v -> {
                            Intent intent = new Intent(requireContext(), com.arenax.tournament.NoticeDetailActivity.class);
                            intent.putExtra(com.arenax.tournament.NoticeDetailActivity.EXTRA_TITLE, finalTitle);
                            intent.putExtra(com.arenax.tournament.NoticeDetailActivity.EXTRA_TEXT, finalText);
                            startActivity(intent);
                        });
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {}
        };
        FirebaseRepository.appConfig().addValueEventListener(noticeListener);
        gameModeListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                gameModes.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Object raw = child.getValue();
                    if (!(raw instanceof java.util.Map)) continue;
                    GameMode mode = GameMode.fromMap(child.getKey(),
                            (java.util.Map<String, Object>) raw);
                    if (mode != null && mode.isActive()) gameModes.add(mode);
                }
                Collections.sort(gameModes, Comparator.comparingInt(GameMode::getDisplayOrder));
                gameModeList.getAdapter().notifyDataSetChanged();
                // A RecyclerView with wrap_content height inside a plain ScrollView does not
                // reliably re-measure itself when the item count changes (a well-known
                // RecyclerView/ScrollView interaction issue) — it was getting stuck at the
                // height captured on first layout (2 rows / 4 cards) no matter how many
                // categories actually existed, with nothing below rendered or reachable by
                // scrolling. Forcing an explicit measure pass and pinning the RecyclerView's
                // LayoutParams height to the real measured content height fixes that for good:
                // the grid now always sizes to fit every category, and the outer ScrollView
                // scrolls the rest of the page (including any rows below the fold) normally.
                gameModeList.post(() -> {
                    if (!isAdded()) return;
                    int width = gameModeList.getWidth();
                    if (width <= 0) return;
                    gameModeList.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    ViewGroup.LayoutParams params = gameModeList.getLayoutParams();
                    if (params != null) {
                        params.height = gameModeList.getMeasuredHeight();
                        gameModeList.setLayoutParams(params);
                    }
                });
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (isAdded()) android.widget.Toast.makeText(requireContext(),
                        "Game categories unavailable: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            }
        };
        FirebaseRepository.gameModes().addValueEventListener(gameModeListener);
        return view;
    }

    private void rebuildDots() {
        if (dotsContainer == null) return;
        dotsContainer.removeAllViews();
        if (banners.size() <= 1) return; // no dots needed for 0 or 1 banner
        int size = dpToPx(8);
        int margin = dpToPx(3);
        for (int i = 0; i < banners.size(); i++) {
            android.widget.ImageView dot = new android.widget.ImageView(requireContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(params);
            dot.setImageDrawable(null);
            dot.setBackgroundResource(i == 0 ? R.drawable.dot_active : R.drawable.dot_inactive);
            dotsContainer.addView(dot);
        }
    }

    private void setActiveDot(int position) {
        if (dotsContainer == null) return;
        for (int i = 0; i < dotsContainer.getChildCount(); i++) {
            View dot = dotsContainer.getChildAt(i);
            dot.setBackgroundResource(i == position ? R.drawable.dot_active : R.drawable.dot_inactive);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void restartBannerAutoSlide() {
        bannerAutoSlideHandler.removeCallbacks(bannerAutoSlideRunnable);
        if (banners.size() > 1) {
            bannerAutoSlideHandler.postDelayed(bannerAutoSlideRunnable, BANNER_AUTO_SLIDE_MS);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        bannerAutoSlideHandler.removeCallbacks(bannerAutoSlideRunnable);
    }

    @Override
    public void onResume() {
        super.onResume();
        restartBannerAutoSlide();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bannerAutoSlideHandler.removeCallbacks(bannerAutoSlideRunnable);
        bannerListView = null;
        if (gameModeListener != null) FirebaseRepository.gameModes().removeEventListener(gameModeListener);
        if (bannerListener != null) FirebaseRepository.banners().removeEventListener(bannerListener);
        if (brandingListener != null) FirebaseRepository.appConfig().removeEventListener(brandingListener);
        if (noticeListener != null) FirebaseRepository.appConfig().removeEventListener(noticeListener);
        if (pointsListener != null) {
            String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
            if (uid != null) FirebaseRepository.leaderboard().child("allTime").child(uid).removeEventListener(pointsListener);
        }
        dotsContainer = null;
    }
}
