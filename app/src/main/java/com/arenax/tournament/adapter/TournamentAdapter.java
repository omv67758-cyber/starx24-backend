package com.arenax.tournament.adapter;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.R;
import com.arenax.tournament.model.Tournament;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;

public class TournamentAdapter extends RecyclerView.Adapter<TournamentAdapter.Holder> {
    public interface OnTournamentClick { void onClick(Tournament tournament); }
    private final List<Tournament> items;
    private final OnTournamentClick listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean deleteEnabled = false;
    private OnTournamentClick deleteListener;

    public TournamentAdapter(List<Tournament> items, OnTournamentClick listener) {
        this.items = items;
        this.listener = listener;
    }

    /** Turns on the per-card DELETE button (used in admin match-management screens only). */
    public void setOnDeleteClick(OnTournamentClick deleteListener) {
        this.deleteEnabled = true;
        this.deleteListener = deleteListener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tournament, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Tournament t = items.get(position);
        h.title.setText(t.getTitle());
        if (t.getBannerUrl() != null && !t.getBannerUrl().trim().isEmpty()) {
            Glide.with(h.image).load(t.getBannerUrl())
                    .placeholder(R.drawable.bg_game_card_blue)
                    .error(R.drawable.bg_game_card_purple)
                    .centerCrop()
                    .into(h.image);
        } else {
            h.image.setImageResource(R.drawable.ic_arenax);
        }
        h.mode.setText(t.getMode());
        h.typeMap.setText(t.getMatchType() + "  •  " + t.getMap());
        h.dateValue.setText(t.getDate());
        h.timeValue.setText(t.getTime());
        h.prize.setText("🪙 " + t.getPrizePoolCoins());
        String entry = "🪙 " + t.getEntryFeeCoins() + " ENTRY";
        entry += "  •  🪙 " + t.getPerKillCoins() + "/KILL";
        h.entry.setText(entry);
        h.slots.setText(t.getJoinedSlots() + "/" + t.getTotalSlots());
        h.status.setText(t.getStatus());
        h.status.setTextColor(t.getAccentColor());
        h.join.setOnClickListener(v -> listener.onClick(t));
        h.itemView.setOnClickListener(v -> listener.onClick(t));
        if (deleteEnabled) {
            h.delete.setVisibility(View.VISIBLE);
            h.delete.setOnClickListener(v -> {
                if (deleteListener != null) deleteListener.onClick(t);
            });
        } else {
            h.delete.setVisibility(View.GONE);
        }
        bindCountdown(h, t);
    }

    private void bindCountdown(@NonNull Holder h, Tournament t) {
        // Cancel any countdown left over from a recycled view before starting a fresh one.
        if (h.tickRunnable != null) handler.removeCallbacks(h.tickRunnable);
        long startAt = t.getEffectiveStartAt();
        if (startAt <= 0L) {
            h.countdown.setVisibility(View.GONE);
            return;
        }
        h.countdown.setVisibility(View.VISIBLE);
        h.tickRunnable = new Runnable() {
            @Override
            public void run() {
                long remainingMs = startAt - System.currentTimeMillis();
                if (remainingMs <= 0L) {
                    h.countdown.setText("ROOM IS READY  •  WAITING FOR ROOM ID & PASSWORD");
                    h.countdown.setBackgroundResource(R.drawable.bg_status_dark);
                    h.countdown.setTextColor(ContextCompat.getColor(h.countdown.getContext(), R.color.success));
                    return; // Reached zero: stop ticking, no more callbacks needed.
                }
                long totalSeconds = remainingMs / 1000L;
                long hours = totalSeconds / 3600L;
                long minutes = (totalSeconds % 3600L) / 60L;
                long seconds = totalSeconds % 60L;
                String formatted = hours > 0
                        ? String.format(Locale.ROOT, "STARTS IN  %02d:%02d:%02d", hours, minutes, seconds)
                        : String.format(Locale.ROOT, "STARTS IN  %02d:%02d", minutes, seconds);
                h.countdown.setText(formatted);
                h.countdown.setBackgroundResource(R.drawable.bg_status_gold);
                h.countdown.setTextColor(ContextCompat.getColor(h.countdown.getContext(), R.color.ink));
                handler.postDelayed(this, 1000L);
            }
        };
        h.tickRunnable.run();
    }

    @Override
    public void onViewRecycled(@NonNull Holder h) {
        super.onViewRecycled(h);
        if (h.tickRunnable != null) handler.removeCallbacks(h.tickRunnable);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        TextView title, mode, typeMap, dateValue, timeValue, prize, entry, slots, status, countdown;
        android.widget.ImageView image;
        MaterialButton join, delete;
        Runnable tickRunnable;
        Holder(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.tournament_title);
            image = v.findViewById(R.id.tournament_image);
            mode = v.findViewById(R.id.tournament_mode);
            typeMap = v.findViewById(R.id.tournament_type_map);
            dateValue = v.findViewById(R.id.tournament_date_value);
            timeValue = v.findViewById(R.id.tournament_time_value);
            prize = v.findViewById(R.id.tournament_prize);
            entry = v.findViewById(R.id.tournament_entry);
            slots = v.findViewById(R.id.tournament_slots);
            status = v.findViewById(R.id.tournament_status);
            countdown = v.findViewById(R.id.tournament_countdown);
            join = v.findViewById(R.id.button_card_join);
            delete = v.findViewById(R.id.button_card_delete);
        }
    }
}
