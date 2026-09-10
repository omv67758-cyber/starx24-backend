package com.arenax.tournament.fragment;

import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.R;
import com.arenax.tournament.adapter.LeaderboardAdapter;
import com.arenax.tournament.data.FirebaseRepository;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

public class LeaderboardFragment extends Fragment {
    private final List<String[]> rows = new ArrayList<>();
    private final List<String[]> podium = new ArrayList<>();
    private LeaderboardAdapter adapter;
    private ValueEventListener listener;
    private View rootView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_leaderboard, container, false);
        SpannableString title = new SpannableString("LEADERBOARD");
        title.setSpan(new ForegroundColorSpan(0xFFF4F4F7), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setSpan(new ForegroundColorSpan(0xFFE21E2B), 5, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ((TextView) rootView.findViewById(R.id.leaderboard_title)).setText(title);
        if (!FirebaseRepository.isReady(requireContext())) {
            android.widget.Toast.makeText(requireContext(), "Live arena data is temporarily unavailable. Please try again.", android.widget.Toast.LENGTH_LONG).show();
            return rootView;
        }
        RecyclerView list = rootView.findViewById(R.id.leaderboard_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new LeaderboardAdapter(rows);
        list.setAdapter(adapter);
        rootView.findViewById(R.id.tab_weekly).setOnClickListener(v -> selectPeriod("weekly"));
        rootView.findViewById(R.id.tab_monthly).setOnClickListener(v -> selectPeriod("monthly"));
        rootView.findViewById(R.id.tab_fulltime).setOnClickListener(v -> selectPeriod("allTime"));
        loadPeriod("weekly");
        return rootView;
    }

    private void loadPeriod(String period) {
        if (listener != null) FirebaseRepository.leaderboard().child(activePeriod).removeEventListener(listener);
        activePeriod = period;
        listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                rows.clear();
                podium.clear();
                int rank = 1;
                for (DataSnapshot child : snapshot.getChildren()) {
                    String name = value(child.child("username").getValue(), value(child.child("name").getValue(), "Arena player"));
                    String matches = value(child.child("matches").getValue(), "0");
                    String kills = value(child.child("kills").getValue(), "0");
                    String score = value(child.child("winning").getValue(), value(child.child("score").getValue(), "0 XP"));
                    String[] row = new String[]{String.valueOf(rank), name, matches, kills, score};
                    if (rank <= 3) podium.add(row); else rows.add(row);
                    rank++;
                }
                bindPodium();
                adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        FirebaseRepository.leaderboard().child(activePeriod).addValueEventListener(listener);
    }

    private String activePeriod = "weekly";

    private void selectPeriod(String period) {
        loadPeriod(period);
        int active = 0xFFE21E2B;
        int inactive = 0xFF9299AA;
        TextView weekly = rootView.findViewById(R.id.tab_weekly);
        TextView monthly = rootView.findViewById(R.id.tab_monthly);
        TextView fulltime = rootView.findViewById(R.id.tab_fulltime);
        weekly.setBackground(null); monthly.setBackground(null); fulltime.setBackground(null);
        weekly.setTextColor(inactive); monthly.setTextColor(inactive); fulltime.setTextColor(inactive);
        TextView selected = "weekly".equals(period) ? weekly : ("monthly".equals(period) ? monthly : fulltime);
        selected.setBackgroundResource(R.drawable.bg_leaderboard_tab_active);
        selected.setTextColor(0xFFFFFFFF);
    }

    private void bindPodium() {
        bindPodiumItem(R.id.podium_name_1, R.id.podium_score_1, 0, "Top player");
        bindPodiumItem(R.id.podium_name_2, R.id.podium_score_2, 1, "Second player");
        bindPodiumItem(R.id.podium_name_3, R.id.podium_score_3, 2, "Third player");
    }

    private void bindPodiumItem(int nameId, int scoreId, int index, String fallback) {
        TextView name = rootView.findViewById(nameId);
        TextView score = rootView.findViewById(scoreId);
        if (index < podium.size()) {
            String[] row = podium.get(index);
            name.setText(row[1]);
            score.setText("● " + row[4]);
        } else {
            name.setText(fallback);
            score.setText("0 XP");
        }
    }

    private static String value(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }

    @Override public void onDestroyView() {
        if (listener != null) FirebaseRepository.leaderboard().child(activePeriod).removeEventListener(listener);
        rootView = null;
        super.onDestroyView();
    }
}
