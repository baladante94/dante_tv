package com.dante.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Toast;

/** Android's one-time VPN permission dialog, which only answers an activity; connects to the place asked for once allowed. */
public final class VpnConsentActivity extends Activity {

    static final String PLACE = "place";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent consent = VpnService.prepare(this);
        if (consent == null) {
            onActivityResult(0, RESULT_OK, null);
        } else if (state == null) {
            startActivityForResult(consent, 0);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result == RESULT_OK) {
            android.content.Context app = getApplicationContext();
            Vpn.get(app).set(getIntent().getStringExtra(PLACE), (current, failed) ->
                    Toast.makeText(app, failed ? R.string.vpn_failed : R.string.vpn_connected, Toast.LENGTH_LONG).show());
        }
        finish();
    }
}
