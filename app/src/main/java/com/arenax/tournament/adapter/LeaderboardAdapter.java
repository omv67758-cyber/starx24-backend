package com.arenax.tournament.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.arenax.tournament.R;
import java.util.List;

public class LeaderboardAdapter extends RecyclerView.Adapter<LeaderboardAdapter.Holder> {
    private final List<String[]> rows;
    public LeaderboardAdapter(List<String[]> rows) { this.rows = rows; }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_leaderboard, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        String[] row = rows.get(position);
        h.rank.setText(row[0]);
        h.avatar.setText(row[1].isEmpty() ? "P" : row[1].substring(0, 1).toUpperCase());
        h.name.setText(row[1]);
        h.stats.setText(row[2] + " matches  •  " + row[3] + " kills");
        h.winning.setText("●  " + row[4]);
    }
    @Override public int getItemCount() { return rows.size(); }
    static class Holder extends RecyclerView.ViewHolder {
        TextView rank, avatar, name, stats, winning;
        Holder(View v) {
            super(v);
            rank = v.findViewById(R.id.leaderboard_rank);
            avatar = v.findViewById(R.id.leaderboard_avatar);
            name = v.findViewById(R.id.leaderboard_name);
            stats = v.findViewById(R.id.leaderboard_stats);
            winning = v.findViewById(R.id.leaderboard_winning);
        }
    }
}
