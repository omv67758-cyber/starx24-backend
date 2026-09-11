package com.arenax.tournament.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.R;
import com.arenax.tournament.model.Banner;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;

/** Same item layout serves two contexts: a fixed-width card in the home carousel, and a
 *  grid tile in the admin banner-management list — adminMode controls sizing + the delete affordance. */
public class BannerAdapter extends RecyclerView.Adapter<BannerAdapter.Holder> {
    public interface OnDeleteClick { void onDelete(Banner banner); }
    public interface OnBannerClick { void onClick(Banner banner); }

    private final List<Banner> items;
    private final boolean adminMode;
    private final OnDeleteClick deleteListener;
    private final OnBannerClick clickListener;

    public BannerAdapter(List<Banner> items, boolean adminMode, OnDeleteClick deleteListener) {
        this(items, adminMode, deleteListener, null);
    }

    public BannerAdapter(List<Banner> items, boolean adminMode, OnDeleteClick deleteListener,
                          OnBannerClick clickListener) {
        this.items = items;
        this.adminMode = adminMode;
        this.deleteListener = deleteListener;
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutRes = adminMode ? R.layout.item_banner_admin : R.layout.item_banner;
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        // Admin cards size themselves (wrap_content, one per row); only the home carousel needs
        // an explicit full-width match so each banner snaps to fill the screen.
        if (!adminMode) {
            ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
            if (params != null) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                holder.itemView.setLayoutParams(params);
            }
        }
        Banner banner = items.get(position);
        if (banner.getImageUrl() == null || banner.getImageUrl().trim().isEmpty()) {
            holder.image.setImageResource(R.drawable.ic_arenax);
        } else {
            Glide.with(holder.image).load(banner.getImageUrl())
                    .placeholder(R.drawable.bg_banner)
                    .error(R.drawable.bg_banner)
                    .centerCrop()
                    .into(holder.image);
        }
        boolean hasTitle = banner.getTitle() != null && !banner.getTitle().trim().isEmpty();
        holder.title.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
        if (hasTitle) holder.title.setText(banner.getTitle().toUpperCase(Locale.ROOT));
        boolean hasSubtitle = banner.getSubtitle() != null && !banner.getSubtitle().trim().isEmpty();
        holder.subtitle.setVisibility(hasSubtitle ? View.VISIBLE : View.GONE);
        if (hasSubtitle) holder.subtitle.setText(banner.getSubtitle());
        holder.delete.setVisibility(adminMode ? View.VISIBLE : View.GONE);
        holder.delete.setOnClickListener(v -> {
            if (deleteListener != null) deleteListener.onDelete(banner);
        });
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onClick(banner);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView image;
        TextView title;
        TextView subtitle;
        MaterialButton delete;

        Holder(@NonNull View view) {
            super(view);
            image = view.findViewById(R.id.banner_image);
            title = view.findViewById(R.id.banner_title);
            subtitle = view.findViewById(R.id.banner_subtitle);
            delete = view.findViewById(R.id.banner_delete);
        }
    }
}
