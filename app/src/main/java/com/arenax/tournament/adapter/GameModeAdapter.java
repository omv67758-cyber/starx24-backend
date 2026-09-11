package com.arenax.tournament.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.R;
import com.arenax.tournament.model.GameMode;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.util.List;

public class GameModeAdapter extends RecyclerView.Adapter<GameModeAdapter.Holder> {
    public interface OnDeleteClick { void onDelete(GameMode mode); }
    public interface OnModeClick { void onClick(GameMode mode); }

    private final List<GameMode> items;
    private final boolean adminMode;
    private final OnDeleteClick deleteListener;
    private final OnModeClick clickListener;
    private OnModeClick editListener;

    public GameModeAdapter(List<GameMode> items, boolean adminMode, OnDeleteClick deleteListener) {
        this(items, adminMode, deleteListener, null);
    }

    public GameModeAdapter(List<GameMode> items, boolean adminMode, OnDeleteClick deleteListener,
                           OnModeClick clickListener) {
        this.items = items;
        this.adminMode = adminMode;
        this.deleteListener = deleteListener;
        this.clickListener = clickListener;
    }

    /** Admin-only: long-press a category card to edit its title/image/slot position. */
    public void setOnEditClick(OnModeClick editListener) {
        this.editListener = editListener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_game_mode, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        GameMode mode = items.get(position);
        holder.title.setText(mode.getTitle());
        holder.users.setText(String.valueOf(mode.getTotalUsers()));
        holder.statusDot.setBackgroundResource(mode.isOnline() ? R.drawable.dot_status_online : R.drawable.dot_status_offline);
        if (mode.getImageUrl().isEmpty()) {
            holder.image.setImageResource(R.drawable.ic_arenax);
            holder.image.setBackgroundResource(R.drawable.bg_game_card_blue);
        } else {
            Glide.with(holder.image).load(mode.getImageUrl())
                    .placeholder(R.drawable.bg_game_card_blue)
                    .error(R.drawable.bg_game_card_purple)
                    .centerCrop()
                    .into(holder.image);
        }
        holder.delete.setVisibility(adminMode ? View.VISIBLE : View.GONE);
        holder.delete.setOnClickListener(v -> {
            if (deleteListener != null) deleteListener.onDelete(mode);
        });
        // Visible pencil button is the primary way into edit (title/image/online-offline
        // toggle) — long-press on the card still works too, but shouldn't be the only way in.
        holder.edit.setVisibility(adminMode ? View.VISIBLE : View.GONE);
        holder.edit.setOnClickListener(v -> {
            if (editListener != null) editListener.onClick(mode);
        });
        if (holder.position != null) {
            if (adminMode) {
                holder.position.setVisibility(View.VISIBLE);
                holder.position.setText("#" + mode.getDisplayOrder());
            } else {
                holder.position.setVisibility(View.GONE);
            }
        }
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onClick(mode);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (adminMode && editListener != null) {
                editListener.onClick(mode);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView image;
        TextView title;
        MaterialButton delete;
        MaterialButton edit;
        TextView position;
        TextView users;
        View statusDot;

        Holder(@NonNull View view) {
            super(view);
            image = view.findViewById(R.id.game_mode_image);
            title = view.findViewById(R.id.game_mode_title);
            delete = view.findViewById(R.id.game_mode_delete);
            edit = view.findViewById(R.id.game_mode_edit);
            position = view.findViewById(R.id.game_mode_position);
            users = view.findViewById(R.id.game_mode_users);
            statusDot = view.findViewById(R.id.game_mode_status_dot);
        }
    }
}