package com.herohan.uvcapp.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Receives USB_DEVICE_ATTACHED in place of MainActivity. MainActivity is singleTask, so
 * delivering the intent to it straight away would close MultiCameraActivity on top of it
 * whenever a camera is plugged in. While the multi camera screen exists it picks the new
 * device up through its own CameraHelpers, so the intent is dropped; otherwise it goes on
 * to MainActivity as before.
 */
public class UsbAttachActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!MultiCameraActivity.isActive()) {
            Intent intent = new Intent(getIntent());
            intent.setClass(this, MainActivity.class);
            startActivity(intent);
        }
        finish();
    }
}
