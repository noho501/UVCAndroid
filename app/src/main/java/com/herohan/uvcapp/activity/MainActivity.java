package com.herohan.uvcapp.activity;

import android.Manifest;
import android.content.res.ColorStateList;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.herohan.uvcapp.ICameraHelper;
import com.herohan.uvcapp.ImageCapture;
import com.herohan.uvcapp.R;
import com.herohan.uvcapp.VideoCapture;
import com.herohan.uvcapp.databinding.ActivityMainBinding;
import com.herohan.uvcapp.fragment.CameraControlsDialogFragment;
import com.herohan.uvcapp.utils.SaveHelper;
import com.hjq.permissions.XXPermissions;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.utils.UriHelper;

import java.io.File;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private ActivityMainBinding mBinding;

    private MultiUvcController mMultiUvcController;
    private USBMonitor mUSBMonitor;

    private boolean mIsRecording = false;
    private boolean mIsCameraConnected = false;
    private Timer mRecordTimer = null;
    private long mRecordStartTime = 0;
    private DecimalFormat mDecimalFormat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        setSupportActionBar(mBinding.toolbar);

        initMultiUvcController();
        setListeners();
    }

    private void initMultiUvcController() {
        View panelView = findViewById(R.id.multiUvcPanel);
        mMultiUvcController = new MultiUvcController(this, panelView, mBinding.viewMainPreview, new MultiUvcController.MultiUvcListener() {
            @Override
            public void onMainCameraSelected(ICameraHelper activeHelper) {
                mIsCameraConnected = true;
                updateUIControls();
            }

            @Override
            public void onDeselected() {
                mIsCameraConnected = false;
                updateUIControls();
            }

            @Override
            public void onPanelClosed() {
                mIsCameraConnected = false;
                updateUIControls();
            }
        });

        // Giao quyền lắng nghe USB cho một USBMonitor độc lập
        mUSBMonitor = new USBMonitor(this, new USBMonitor.OnDeviceConnectListener() {
            @Override
            public void onAttach(UsbDevice device) {
                List<UsbDevice> list = mUSBMonitor.getDeviceList();
                List<UsbDevice> uvcList = getOnlyUvcDevices(list); // Lọc thiết bị

                if (!uvcList.isEmpty()) {
                    mMultiUvcController.checkAndStart(uvcList);
                }
            }
            @Override
            public void onDetach(UsbDevice device) {
                List<UsbDevice> list = mUSBMonitor.getDeviceList();
                List<UsbDevice> uvcList = getOnlyUvcDevices(list); // Lọc thiết bị

                if (uvcList.isEmpty()) {
                    mMultiUvcController.closePanel();
                } else {
                    mMultiUvcController.checkAndStart(uvcList);
                }
            }

            @Override
            public void onDeviceOpen(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {

            }

            @Override
            public void onDeviceClose(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {

            }

            @Override public void onCancel(UsbDevice device) {}
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUSBMonitor.register();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mIsRecording) toggleVideoRecord(false);
        mUSBMonitor.unregister();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mMultiUvcController != null) mMultiUvcController.release();
        if (mUSBMonitor != null) mUSBMonitor.destroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean visible = mIsCameraConnected;
        menu.findItem(R.id.action_control).setVisible(visible);
        menu.findItem(R.id.action_safely_eject).setVisible(visible);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_control) {
            ICameraHelper helper = mMultiUvcController.getActiveHelper();
            if (helper != null) {
                CameraControlsDialogFragment fragment = new CameraControlsDialogFragment(helper);
                fragment.show(getSupportFragmentManager(), "camera_controls");
            }
        } else if (id == R.id.action_safely_eject) {
            mMultiUvcController.closePanel();
        }
        return true;
    }

    private void setListeners() {
        mBinding.fabPicture.setOnClickListener(v -> {
            XXPermissions.with(this).permission(Manifest.permission.MANAGE_EXTERNAL_STORAGE).request((p, a) -> takePicture());
        });
        mBinding.fabVideo.setOnClickListener(v -> {
            XXPermissions.with(this).permission(Manifest.permission.MANAGE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
                    .request((p, a) -> toggleVideoRecord(!mIsRecording));
        });
    }

    private void updateUIControls() {
        runOnUiThread(() -> {
            if (mIsCameraConnected) {
                // Đã xóa setVisibility để không can thiệp đè lên logic Alpha của Controller
                mBinding.tvConnectUSBCameraTip.setVisibility(View.GONE);
                mBinding.fabPicture.setVisibility(View.VISIBLE);
                mBinding.fabVideo.setVisibility(View.VISIBLE);
                ColorStateList c = ColorStateList.valueOf(getResources().getColor(mIsRecording ? R.color.RED : R.color.WHITE));
                mBinding.fabVideo.setSupportImageTintList(c);
            } else {
                // Đã xóa setVisibility(View.GONE), thay vào đó Controller sẽ tự set Alpha về 0
                mBinding.tvConnectUSBCameraTip.setVisibility(View.VISIBLE);
                mBinding.fabPicture.setVisibility(View.GONE);
                mBinding.fabVideo.setVisibility(View.GONE);
                mBinding.tvVideoRecordTime.setVisibility(View.GONE);
            }
            invalidateOptionsMenu();
        });
    }

    public void takePicture() {
        ICameraHelper helper = mMultiUvcController.getActiveHelper();
        if (mIsRecording || helper == null) return;
        try {
            File f = new File(SaveHelper.getSavePhotoPath());
            ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(f).build();
            helper.takePicture(options, new ImageCapture.OnImageCaptureCallback() {
                @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults res) {
                    Toast.makeText(MainActivity.this, "Saved: " + UriHelper.getPath(MainActivity.this, res.getSavedUri()), Toast.LENGTH_SHORT).show();
                }
                @Override public void onError(int err, @NonNull String msg, @Nullable Throwable cause) {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void toggleVideoRecord(boolean isRecording) {
        ICameraHelper helper = mMultiUvcController.getActiveHelper();
        if (helper == null) return;

        try {
            if (isRecording && !helper.isRecording()) {
                File f = new File(SaveHelper.getSaveVideoPath());
                VideoCapture.OutputFileOptions options = new VideoCapture.OutputFileOptions.Builder(f).build();
                helper.startRecording(options, new VideoCapture.OnVideoCaptureCallback() {
                    @Override public void onStart() { startRecordTimer(); }
                    @Override public void onVideoSaved(@NonNull VideoCapture.OutputFileResults res) {
                        toggleVideoRecord(false);
                    }
                    @Override public void onError(int err, @NonNull String msg, @Nullable Throwable cause) {
                        toggleVideoRecord(false);
                    }
                });
            } else if (!isRecording && helper.isRecording()) {
                helper.stopRecording();
                stopRecordTimer();
            }
        } catch (Exception e) { stopRecordTimer(); }
        mIsRecording = isRecording;
        updateUIControls();
    }

    private void startRecordTimer() {
        runOnUiThread(() -> mBinding.tvVideoRecordTime.setVisibility(View.VISIBLE));
        mRecordStartTime = SystemClock.elapsedRealtime();
        mRecordTimer = new Timer();
        mRecordTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                long t = (SystemClock.elapsedRealtime() - mRecordStartTime) / 1000;
                if (t > 0) runOnUiThread(() -> mBinding.tvVideoRecordTime.setText(formatTime(t)));
            }
        }, 250, 250);
    }

    private void stopRecordTimer() {
        runOnUiThread(() -> {
            mBinding.tvVideoRecordTime.setVisibility(View.GONE);
            mBinding.tvVideoRecordTime.setText(formatTime(0));
        });
        if (mRecordTimer != null) { mRecordTimer.cancel(); mRecordTimer = null; }
        mRecordStartTime = 0;
    }

    private String formatTime(long time) {
        if (mDecimalFormat == null) mDecimalFormat = new DecimalFormat("00");
        return mDecimalFormat.format(time / 3600) + ":" + mDecimalFormat.format(time % 3600 / 60) + ":" + mDecimalFormat.format(time % 60);
    }

    // Hàm lọc chỉ lấy các thiết bị có chức năng truyền Video (UVC)
    private List<UsbDevice> getOnlyUvcDevices(List<UsbDevice> allDevices) {
        List<UsbDevice> uvcDevices = new java.util.ArrayList<>();
        if (allDevices == null || allDevices.isEmpty()) return uvcDevices;

        for (UsbDevice device : allDevices) {
            boolean isVideoDevice = false;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                android.hardware.usb.UsbInterface usbInterface = device.getInterface(i);
                // 14 là mã chuẩn của android.hardware.usb.UsbConstants.USB_CLASS_VIDEO
                if (usbInterface.getInterfaceClass() == 14 ||
                        usbInterface.getInterfaceClass() == android.hardware.usb.UsbConstants.USB_CLASS_VIDEO) {
                    isVideoDevice = true;
                    break;
                }
            }
            if (isVideoDevice) {
                uvcDevices.add(device);
            }
        }
        return uvcDevices;
    }
}