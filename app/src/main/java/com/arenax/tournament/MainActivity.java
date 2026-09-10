package com.arenax.tournament;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.fragment.HomeFragment;
import com.arenax.tournament.fragment.LeaderboardFragment;
import com.arenax.tournament.fragment.ProfileFragment;
import com.arenax.tournament.fragment.RewardsFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001);
        }
        registerPushToken();
        // Deep-linked category browsing now opens CategoryMatchesActivity directly from
        // HomeFragment's Esports Games grid, so MainActivity no longer needs to switch
        // bottom-nav tabs based on an incoming categoryId extra.
        // Do not trust a stale remote flag by default. Enable intentionally with
        // remote.maintenance.enabled=true in the root local.properties.
        if (BuildConfig.REMOTE_MAINTENANCE_ENABLED && FirebaseRepository.isReady(this)) {
            FirebaseRepository.appConfig().child("maintenance").get().addOnSuccessListener(snapshot -> {
            if (Boolean.TRUE.equals(snapshot.getValue(Boolean.class)) && !isFinishing()) {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("STARX24 is under maintenance")
                        .setMessage("The admin has temporarily paused player access. Please check back soon.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
        }
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (savedInstanceState == null) showFragment(new HomeFragment());
        nav.setOnItemSelectedListener(item -> {
            Fragment fragment;
            if (item.getItemId() == R.id.nav_leaderboard) fragment = new LeaderboardFragment();
            else if (item.getItemId() == R.id.nav_wallet) {
                startActivity(new android.content.Intent(this, WalletActivity.class));
                return true;
            }
            else if (item.getItemId() == R.id.nav_profile) fragment = new ProfileFragment();
            else fragment = new HomeFragment();
            showFragment(fragment);
            return true;
        });
    }

    private void registerPushToken() {
        if (!FirebaseRepository.isFirebaseAvailable(this)) return;
        if (FirebaseRepository.currentUser() == null) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token ->
                FirebaseRepository.users().child(FirebaseRepository.currentUser().getUid()).child("fcmToken").setValue(token));
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}
