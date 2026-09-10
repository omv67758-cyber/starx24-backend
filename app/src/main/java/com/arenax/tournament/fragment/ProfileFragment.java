package com.arenax.tournament.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.arenax.tournament.R;
import com.arenax.tournament.data.FirebaseRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;

public class ProfileFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profile, container, false);
        if (!FirebaseRepository.isReady(requireContext())) {
            android.widget.Toast.makeText(requireContext(),
                    "Live arena data is temporarily unavailable. Please try again.",
                    android.widget.Toast.LENGTH_LONG).show();
            return view;
        }        TextView name = view.findViewById(R.id.profile_name);
        TextView stats = view.findViewById(R.id.profile_stats);
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            String uid = FirebaseAuth.getInstance().getUid();
            FirebaseRepository.users().child(uid).get().addOnSuccessListener(snapshot -> {
                String displayName = text(snapshot.child("name").getValue(), "ARENA PLAYER");
                name.setText(displayName);
                stats.setText(profileStats(snapshot));
            });
        }
        view.findViewById(R.id.profile_settings).setOnClickListener(v ->
                Toast.makeText(requireContext(), "This section is controlled by your Arena admin.", Toast.LENGTH_SHORT).show());
        view.findViewById(R.id.profile_coin_balance).setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), com.arenax.tournament.WalletActivity.class)));
        view.findViewById(R.id.profile_support).setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), com.arenax.tournament.MyMatchesActivity.class)));
        view.findViewById(R.id.profile_faq).setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), com.arenax.tournament.NotificationsActivity.class)));
        view.findViewById(R.id.profile_team).setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), com.arenax.tournament.TeamActivity.class)));
        view.findViewById(R.id.profile_faq_row).setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), com.arenax.tournament.FaqActivity.class)));
        view.findViewById(R.id.profile_logout).setOnClickListener(v -> {
            FirebaseRepository.signOut();
            startActivity(new android.content.Intent(requireContext(), com.arenax.tournament.LoginActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            requireActivity().finish();
        });
        return view;
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String profileStats(DataSnapshot snapshot) {
        return text(snapshot.child("matchesPlayed").getValue(), "0") + " MATCHES   •   "
                + text(snapshot.child("wins").getValue(), "0") + " WINS   •   "
                + text(snapshot.child("totalKills").getValue(), "0") + " KILLS";
    }
}
