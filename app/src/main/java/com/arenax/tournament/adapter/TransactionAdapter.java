package com.arenax.tournament.adapter;

import android.content.res.ColorStateList;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.arenax.tournament.R;
import com.arenax.tournament.model.Transaction;

import java.util.List;
import java.util.Locale;

public class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.Holder> {

    private final List<Transaction> items;

    public TransactionAdapter(List<Transaction> items) {
        this.items = items;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transaction, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Transaction t = items.get(position);
        android.content.Context ctx = holder.itemView.getContext();

        holder.title.setText(t.getTitle());
        holder.datetime.setText(DateFormat.format("dd MMM, hh:mm a", t.getTimestamp()));

        boolean credit = t.isCredit();
        String sign = credit ? "+" : "-";
        holder.amount.setText(String.format(Locale.getDefault(), "%s₹%d", sign, t.getAmount()));
        holder.amount.setTextColor(ContextCompat.getColor(ctx,
                credit ? R.color.wallet_credit : R.color.wallet_debit));

        switch (t.getType()) {
            case DEPOSIT: holder.icon.setImageResource(R.drawable.ic_coin); break;
            case WITHDRAWAL: holder.icon.setImageResource(R.drawable.ic_withdraw); break;
            case WINNING_REWARD: holder.icon.setImageResource(R.drawable.ic_trophy); break;
            case CONTEST_ENTRY: holder.icon.setImageResource(R.drawable.ic_ongoing); break;
            case REFUND: holder.icon.setImageResource(R.drawable.ic_coin); break;
        }

        int badgeColor;
        String badgeText;
        switch (t.getStatus()) {
            case PENDING:
                badgeColor = R.color.wallet_badge_pending; badgeText = "PENDING"; break;
            case FAILED:
                badgeColor = R.color.wallet_debit; badgeText = "FAILED"; break;
            default:
                badgeColor = R.color.wallet_credit; badgeText = "SUCCESS"; break;
        }
        holder.status.setText(badgeText);
        holder.status.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, badgeColor)));
    }

    @Override public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView title, datetime, amount, status;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.row_icon);
            title = itemView.findViewById(R.id.row_title);
            datetime = itemView.findViewById(R.id.row_datetime);
            amount = itemView.findViewById(R.id.row_amount);
            status = itemView.findViewById(R.id.row_status);
        }
    }
}
