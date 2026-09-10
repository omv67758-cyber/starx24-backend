package com.arenax.tournament.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.R;
import com.arenax.tournament.TournamentDetailActivity;
import com.arenax.tournament.adapter.TournamentAdapter;
import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.model.Tournament;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TournamentsFragment extends Fragment {
    private final List<Tournament> allTournaments = new ArrayList<>();
    private final List<Tournament> filtered = new ArrayList<>();
    private TournamentAdapter adapter;
    private ValueEventListener listener;
    private String activeFilter = "ALL";
    private String activeCategoryId = "";
    private String query = "";
    private final List<TextView> filterPills = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_tournaments, container, false);
        if (!FirebaseRepository.isReady(requireContext())) {
            android.widget.Toast.makeText(requireContext(),
                    "Live arena data is temporarily unavailable. Please try again.",
                    android.widget.Toast.LENGTH_LONG).show();
            return view;
        }        RecyclerView list = view.findViewById(R.id.tournament_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TournamentAdapter(filtered, tournament -> {
            Intent intent = new Intent(requireContext(), TournamentDetailActivity.class);
            intent.putExtra("tournament", tournament);
            startActivity(intent);
        });
        list.setAdapter(adapter);
        EditText search = view.findViewById(R.id.tournament_search);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                refreshFiltered();
            }
            public void afterTextChanged(android.text.Editable s) {}
        });

        TextView all = view.findViewById(R.id.chip_all);
        TextView upcoming = view.findViewById(R.id.chip_upcoming);
        TextView ongoing = view.findViewById(R.id.chip_ongoing);
        TextView completed = view.findViewById(R.id.chip_completed);
        filterPills.clear();
        Collections.addAll(filterPills, all, upcoming, ongoing, completed);
        View.OnClickListener filterListener = v -> {
            activeFilter = ((TextView) v).getText().toString();
            updatePillStyles((TextView) v);
            refreshFiltered();
        };
        for (TextView pill : filterPills) pill.setOnClickListener(filterListener);
        updatePillStyles(all);
        activeCategoryId = requireActivity().getIntent().getStringExtra("categoryId");
        if (activeCategoryId == null) activeCategoryId = "";
        listener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!isAdded()) return;
                allTournaments.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Object rawValue = child.getValue();
                    if (!(rawValue instanceof java.util.Map)) continue;
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> valueMap = (java.util.Map<String, Object>) rawValue;
                    Tournament tournament = Tournament.fromMap(child.getKey(), valueMap);
                    if (tournament != null && tournament.isActive()) allTournaments.add(tournament);
                }
                Collections.sort(allTournaments,
                        Comparator.comparingLong(Tournament::getEffectiveStartAt));
                refreshFiltered();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (isAdded()) android.widget.Toast.makeText(requireContext(),
                        "Live tournaments unavailable: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            }
        };
        FirebaseRepository.tournaments().addValueEventListener(listener);
        return view;
    }

    private void updatePillStyles(TextView selected) {
        for (TextView pill : filterPills) {
            boolean isSelected = pill == selected;
            pill.setBackgroundResource(isSelected ? R.drawable.bg_status_gold : R.drawable.bg_status_dark);
            pill.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                    isSelected ? R.color.ink : R.color.text_primary));
        }
    }

    private void refreshFiltered() {
        filtered.clear();
        for (Tournament tournament : allTournaments) {
            boolean statusMatches = "ALL".equals(activeFilter)
                    || activeFilter.equalsIgnoreCase(tournament.getStatus());
            boolean categoryMatches = activeCategoryId.isEmpty() || activeCategoryId.equals(tournament.getCategoryId());
            String searchable = (tournament.getTitle() + " " + tournament.getMode() + " "
                    + tournament.getMatchType()).toLowerCase(java.util.Locale.ROOT);
            boolean queryMatches = query.isEmpty() || searchable.contains(query);
            if (statusMatches && categoryMatches && queryMatches) filtered.add(tournament);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        View empty = getView() == null ? null : getView().findViewById(R.id.tournament_empty);
        if (empty != null) empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (listener != null) FirebaseRepository.tournaments().removeEventListener(listener);
    }
}