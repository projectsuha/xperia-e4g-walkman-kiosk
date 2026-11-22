package com.walkman.kiosk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Minimal kiosk launcher.
 *
 * Registered as the HOME activity, it immediately forwards to the Sony Walkman
 * app and finishes. Pressing Home at any time re-triggers this and returns the
 * user to Walkman. No internet, no extra UI, no root required.
 *
 * Change WALKMAN_PKG to target a different player if you like.
 */
public class HomeActivity extends Activity {

    private static final String WALKMAN_PKG = "com.sonyericsson.music";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openTarget();
    }

    @Override
    protected void onResume() {
        super.onResume();
        openTarget();
    }

    private void openTarget() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(WALKMAN_PKG);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
        }
        finish();
    }
}
