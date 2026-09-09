package com.ricezhou.vsrqg.smoke;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

public final class SmokeActivity extends Activity {
    private static final String EXTRA_ATTEMPT_ID = "attemptId";
    private static final String EXTRA_MODE = "mode";
    private static final String DEMO_LABEL = "SYNTHETIC_DEMO";
    private static final String INVALID_INPUT = "SMOKE_INPUT_INVALID";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        renderIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderIntent(intent);
    }

    private void renderIntent(Intent intent) {
        TextView markerView = new TextView(this);
        markerView.setTextSize(20);
        markerView.setPadding(32, 32, 32, 32);

        try {
            String marker = SmokeMarker.render(
                    intent == null ? null : intent.getStringExtra(EXTRA_ATTEMPT_ID),
                    intent == null ? null : intent.getStringExtra(EXTRA_MODE));
            markerView.setText(DEMO_LABEL + "\n" + marker);
            setContentView(markerView);
        } catch (IllegalArgumentException invalidInput) {
            markerView.setText(INVALID_INPUT);
            setContentView(markerView);
            finish();
        }
    }
}
