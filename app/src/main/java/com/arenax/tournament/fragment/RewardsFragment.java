package com.arenax.tournament.fragment;

import android.os.Bundle;
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
import com.arenax.tournament.data.FirebaseRepository;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

public class RewardsFragment extends Fragment {
    private final List<String[]> rewards = new ArrayList<>();
    private RewardAdapter adapter;
    private ValueEventListener listener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_rewards, container, false);
        if (!FirebaseRepository.isReady(requireContext())) {
            android.widget.Toast.makeText(requireContext(),
                    "Live arena data is temporarily unavailable. Please try again.",
                    android.widget.Toast.LENGTH_LONG).show();
            return view;
        }        RecyclerView list = view.findViewById(R.id.rewards_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new RewardAdapter(rewards);
        list.setAdapter(adapter);
        listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                rewards.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String title = text(child.child("title").getValue(), "Arena challenge");
                    String description = text(child.child("description").getValue(),
                            "Complete this challenge to unlock your next badge.");
                    String state = text(child.child("status").getValue(), "AVAILABLE");
                    rewards.add(new String[]{title, description, state});
                }
                adapter.notifyDataSetChanged();
                view.findViewById(R.id.rewards_empty).setVisibility(
                        rewards.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                view.findViewById(R.id.rewards_empty).setVisibility(View.VISIBLE);
            }
        };
        FirebaseRepository.rewards().addValueEventListener(listener);
        return view;
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    @Override
    public void onDestroyView() {
        if (listener != null) FirebaseRepository.rewards().removeEventListener(listener);
        super.onDestroyView();
    }

    private static class RewardAdapter extends RecyclerView.Adapter<RewardAdapter.Holder> {
        private final List<String[]> items;

        RewardAdapter(List<String[]> items) { this.items = items; }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_reward, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String[] reward = items.get(position);
            holder.title.setText(reward[0]);
            holder.description.setText(reward[1]);
            holder.status.setText(reward[2]);
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class Holder extends RecyclerView.ViewHolder {
            TextView title, description, status;

            Holder(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.reward_title);
                description = itemView.findViewById(R.id.reward_description);
                status = itemView.findViewById(R.id.reward_status);
            }
        }
    }
}