package com.arenax.tournament;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import com.google.firebase.FirebaseApp;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Installs a global uncaught-exception handler as early as possible
 * (attachBaseContext, before ContentProviders such as Firebase's own
 * auto-init provider run) so even a startup-time crash shows a readable
 * screen instead of silently closing the app.
 */
public class STARX24App extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        installCrashHandler();
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                Intent intent = new Intent(getApplicationContext(), CrashActivity.class);
                intent.putExtra(CrashActivity.EXTRA_TRACE, sw.toString());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            } catch (Throwable ignored) {
                // fall through to default handler below
            } finally {
                Process.killProcess(Process.myPid());
                System.exit(10);
                if (previous != null) previous.uncaughtException(thread, throwable);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // The FirebaseInitProvider is intentionally disabled in the manifest so
        // startup remains resilient to a bad config. Initialize explicitly here
        // before any Activity or messaging service calls FirebaseAuth.getInstance().
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
            }
        } catch (RuntimeException ignored) {
            // LoginActivity/FirebaseRepository will show the normal unavailable
            // state instead of crashing if google-services.json is invalid.
        }
    }
}
