package com.herohan.uvcapp.activity;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.graphics.PixelFormat;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.herohan.uvcapp.ImageCapture;
import com.herohan.uvcapp.R;
import com.herohan.uvcapp.VideoCapture;
import com.herohan.uvcapp.databinding.ActivityMainBinding;
import com.herohan.uvcapp.fragment.CameraControlsDialogFragment;
import com.herohan.uvcapp.fragment.DeviceListDialogFragment;
import com.herohan.uvcapp.fragment.VideoFormatDialogFragment;
import com.herohan.uvcapp.utils.SaveHelper;
import com.hjq.permissions.XXPermissions;
import com.serenegiant.opengl.renderer.MirrorMode;
import com.serenegiant.usb.IButtonCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCParam;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.utils.UriHelper;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final boolean DEBUG = true;
    private static final int MTK_PREFERRED_WIDTH = 640;
    private static final int MTK_PREFERRED_HEIGHT = 480;
    private static final int MTK_FALLBACK_WIDTH = 640;
    private static final int MTK_FALLBACK_HEIGHT = 360;
    private static final int MTK_SAFE_FPS = 15;

    private ActivityMainBinding mBinding;

    private static final int QUARTER_SECOND = 250;
    private static final int HALF_SECOND = 500;
    private static final int ONE_SECOND = 1000;

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;

    /**
     * Camera preview width
     */
    private int mPreviewWidth = DEFAULT_WIDTH;
    /**
     * Camera preview height
     */
    private int mPreviewHeight = DEFAULT_HEIGHT;

    private int mPreviewRotation = 0;

    private ICameraHelper mCameraHelper;

    private UsbDevice mUsbDevice;
    private final ICameraHelper.StateCallback mStateCallback = new MyCameraHelperCallback();

    private long mRecordStartTime = 0;
    private Timer mRecordTimer = null;
    private DecimalFormat mDecimalFormat;

    private boolean mIsRecording = false;
    private boolean mIsCameraConnected = false;
    private boolean mPreviewHolderCallbackAdded = false;

    private CameraControlsDialogFragment mControlsDialog;
    private DeviceListDialogFragment mDeviceListDialog;
    private VideoFormatDialogFragment mFormatDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        setSupportActionBar(mBinding.toolbar);

        checkCameraHelper();

        setListeners();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            if (!mIsCameraConnected) {
                mUsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                selectDevice(mUsbDevice);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        initPreviewView();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mIsRecording) {
            toggleVideoRecord(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearCameraHelper();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_control) {
            showCameraControlsDialog();
        } else if (id == R.id.action_device) {
            showDeviceListDialog();
        } else if (id == R.id.action_safely_eject) {
            safelyEject();
        } else if (id == R.id.action_settings) {
        } else if (id == R.id.action_video_format) {
            showVideoFormatDialog();
        } else if (id == R.id.action_rotate_90_CW) {
            rotateBy(90);
        } else if (id == R.id.action_rotate_90_CCW) {
            rotateBy(-90);
        } else if (id == R.id.action_flip_horizontally) {
            flipHorizontally();
        } else if (id == R.id.action_flip_vertically) {
            flipVertically();
        }

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mIsCameraConnected) {
            menu.findItem(R.id.action_control).setVisible(true);
            menu.findItem(R.id.action_safely_eject).setVisible(true);
            menu.findItem(R.id.action_video_format).setVisible(true);
            menu.findItem(R.id.action_rotate_90_CW).setVisible(true);
            menu.findItem(R.id.action_rotate_90_CCW).setVisible(true);
            menu.findItem(R.id.action_flip_horizontally).setVisible(true);
            menu.findItem(R.id.action_flip_vertically).setVisible(true);
        } else {
            menu.findItem(R.id.action_control).setVisible(false);
            menu.findItem(R.id.action_safely_eject).setVisible(false);
            menu.findItem(R.id.action_video_format).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CW).setVisible(false);
            menu.findItem(R.id.action_rotate_90_CCW).setVisible(false);
            menu.findItem(R.id.action_flip_horizontally).setVisible(false);
            menu.findItem(R.id.action_flip_vertically).setVisible(false);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private void setListeners() {
        mBinding.fabPicture.setOnClickListener(v -> {
            XXPermissions.with(this)
                    .permission(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                    .request((permissions, all) -> {
                        takePicture();
                    });
        });

        mBinding.fabVideo.setOnClickListener(v -> {
            XXPermissions.with(this)
                    .permission(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                    .permission(Manifest.permission.RECORD_AUDIO)
                    .request((permissions, all) -> {
                        toggleVideoRecord(!mIsRecording);
                    });
        });
    }

    private void showCameraControlsDialog() {
        if (mControlsDialog == null) {
            mControlsDialog = new CameraControlsDialogFragment(mCameraHelper);
        }
        // When DialogFragment is not showing
        if (!mControlsDialog.isAdded()) {
            mControlsDialog.show(getSupportFragmentManager(), "camera_controls");
        }
    }

    private void showDeviceListDialog() {
        if (mDeviceListDialog != null && mDeviceListDialog.isAdded()) {
            return;
        }

        mDeviceListDialog = new DeviceListDialogFragment(mCameraHelper, mIsCameraConnected ? mUsbDevice : null);
        mDeviceListDialog.setOnDeviceItemSelectListener(usbDevice -> {
            if (mIsCameraConnected) {
                mCameraHelper.closeCamera();
            }
            mUsbDevice = usbDevice;
            selectDevice(mUsbDevice);
        });

        mDeviceListDialog.show(getSupportFragmentManager(), "device_list");
    }

    private void showVideoFormatDialog() {
        if (mFormatDialog != null && mFormatDialog.isAdded()) {
            return;
        }

        Size currentSize = mCameraHelper.getPreviewSize();

        List<com.serenegiant.usb.Format> formats = mCameraHelper.getSupportedFormatList();

        // If you can't select a size (because the camera hasn't successfully opened), please select a default size from the list.
        if (currentSize == null && formats != null && !formats.isEmpty()) {
            com.serenegiant.usb.Format.Descriptor desc = formats.get(0).frameDescriptors.get(0);
            currentSize = new Size(desc.type, desc.width, desc.height, desc.intervals.get(0).fps, null);
        }

        if (currentSize == null) {
            Toast.makeText(this, "Camera data could not be obtained.", Toast.LENGTH_SHORT).show();
            return;
        }

        mFormatDialog = new VideoFormatDialogFragment(mCameraHelper.getSupportedFormatList(), mCameraHelper.getPreviewSize());

        mFormatDialog.setOnVideoFormatSelectListener(size -> {
            try {
                if (mIsCameraConnected && !mCameraHelper.isRecording()) {
                    mCameraHelper.stopPreview();
                    mCameraHelper.setPreviewSize(size);
                    mCameraHelper.startPreview();
                    resizePreviewView(size);
                    // save selected preview size
                    setSavedPreviewSize(size);
                }
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "showVideoFormatDialog: " + e.getMessage());
            }
        });

        mFormatDialog.show(getSupportFragmentManager(), "video_format");
    }

    private void closeAllDialogFragment() {
        if (mControlsDialog != null && mControlsDialog.isAdded()) {
            mControlsDialog.dismiss();
        }
        if (mDeviceListDialog != null && mDeviceListDialog.isAdded()) {
            mDeviceListDialog.dismiss();
        }
        if (mFormatDialog != null && mFormatDialog.isAdded()) {
            mFormatDialog.dismiss();
        }
    }

    private void safelyEject() {
        if (mCameraHelper != null) {
            mCameraHelper.closeCamera();
        }
    }

    private void rotateBy(int angle) {
        mPreviewRotation += angle;
        mPreviewRotation %= 360;
        if (mPreviewRotation < 0) {
            mPreviewRotation += 360;
        }

        if (mCameraHelper != null) {
            mCameraHelper.setPreviewConfig(
                    mCameraHelper.getPreviewConfig().setRotation(mPreviewRotation));
        }
    }

    private void flipHorizontally() {
        if (mCameraHelper != null) {
            mCameraHelper.setPreviewConfig(
                    mCameraHelper.getPreviewConfig().setMirror(MirrorMode.MIRROR_HORIZONTAL));
        }
    }

    private void flipVertically() {
        if (mCameraHelper != null) {
            mCameraHelper.setPreviewConfig(
                    mCameraHelper.getPreviewConfig().setMirror(MirrorMode.MIRROR_VERTICAL));
        }
    }

    private void checkCameraHelper() {
        if (!mIsCameraConnected) {
            clearCameraHelper();
        }
        initCameraHelper();
    }

    private void initCameraHelper() {
        if (mCameraHelper == null) {
            mCameraHelper = new CameraHelper();
            mCameraHelper.setStateCallback(mStateCallback);

            setCustomImageCaptureConfig();
            setCustomVideoCaptureConfig();
        }
    }

    private void clearCameraHelper() {
        if (DEBUG) Log.v(TAG, "clearCameraHelper:");
        if (mCameraHelper != null) {
            mCameraHelper.release();
            mCameraHelper = null;
        }
    }

    private void initPreviewView() {
        mBinding.viewMainPreview.setAspectRatio(mPreviewWidth, mPreviewHeight);
        configurePreviewHolder(mPreviewWidth, mPreviewHeight);
        if (mPreviewHolderCallbackAdded) {
            return;
        }
        mPreviewHolderCallbackAdded = true;
        mBinding.viewMainPreview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                if (mCameraHelper != null) {
                    mCameraHelper.addSurface(holder.getSurface(), false);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (mCameraHelper != null) {
                    mCameraHelper.removeSurface(holder.getSurface());
                }
            }
        });
    }

    private void configurePreviewHolder(int width, int height) {
        SurfaceHolder holder = mBinding.viewMainPreview.getHolder();
        holder.setFormat(PixelFormat.RGBA_8888);
        if (width > 0 && height > 0) {
            holder.setFixedSize(width, height);
        } else {
            holder.setSizeFromLayout();
        }
    }


    public void attachNewDevice(UsbDevice device) {
        if (mUsbDevice == null) {
            mUsbDevice = device;

            selectDevice(device);
        }
    }

    /**
     * In Android9+, connected to the UVC CAMERA, CAMERA permission is required
     *
     * @param device
     */
    protected void selectDevice(UsbDevice device) {
        if (DEBUG) Log.v(TAG, "selectDevice:device=" + device.getDeviceName());

        XXPermissions.with(this)
                .permission(Manifest.permission.CAMERA)
                .request((permissions, all) -> {
                    mIsCameraConnected = false;
                    updateUIControls();

                    if (mCameraHelper != null) {
                        // 通过UsbDevice对象，尝试获取设备权限
                        mCameraHelper.selectDevice(device);
                    }
                });
    }

    private class MyCameraHelperCallback implements ICameraHelper.StateCallback {
        @Override
        public void onAttach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:device=" + device.getDeviceName());

            attachNewDevice(device);
        }

        /**
         * After obtaining USB device permissions, connect the USB camera
         */
        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (DEBUG) Log.v(TAG, "onDeviceOpen:device=" + device.getDeviceName());

            mCameraHelper.openCamera(getCameraOpenParam());

            mCameraHelper.setButtonCallback(new IButtonCallback() {
                @Override
                public void onButton(int button, int state) {
                    Toast.makeText(MainActivity.this, "onButton(button=" + button + "; " +
                            "state=" + state + ")", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraOpen:device=" + device.getDeviceName());
            applyMediaTekPreviewPreference();
            mCameraHelper.startPreview();

            // After connecting to the camera, you can get preview size of the camera
            Size size = mCameraHelper.getPreviewSize();
            if (size != null) {
                resizePreviewView(size);
            }

            mCameraHelper.addSurface(mBinding.viewMainPreview.getHolder().getSurface(), false);

            mIsCameraConnected = true;
            updateUIControls();
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCameraClose:device=" + device.getDeviceName());

            if (mIsRecording) {
                toggleVideoRecord(false);
            }

            if (mCameraHelper != null) {
                mCameraHelper.removeSurface(mBinding.viewMainPreview.getHolder().getSurface());
            }

            mIsCameraConnected = false;
            updateUIControls();

            closeAllDialogFragment();
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDeviceClose:device=" + device.getDeviceName());
        }

        @Override
        public void onDetach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDetach:device=" + device.getDeviceName());

            if (device.equals(mUsbDevice)) {
                mUsbDevice = null;
            }
        }

        @Override
        public void onCancel(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onCancel:device=" + device.getDeviceName());

            if (device.equals(mUsbDevice)) {
                mUsbDevice = null;
            }
        }
    }

    private void resizePreviewView(Size size) {
        // Update the preview size
        mPreviewWidth = size.width;
        mPreviewHeight = size.height;
        configurePreviewHolder(mPreviewWidth, mPreviewHeight);
        mBinding.viewMainPreview.setAspectRatio(mPreviewWidth, mPreviewHeight);
    }

    private void updateUIControls() {
        runOnUiThread(() -> {
            if (mIsCameraConnected) {
                mBinding.viewMainPreview.setVisibility(View.VISIBLE);
                mBinding.tvConnectUSBCameraTip.setVisibility(View.GONE);

                mBinding.fabPicture.setVisibility(View.VISIBLE);
                mBinding.fabVideo.setVisibility(View.VISIBLE);

                // Update record button
                int colorId = R.color.WHITE;
                if (mIsRecording) {
                    colorId = R.color.RED;
                }
                ColorStateList colorStateList = ColorStateList.valueOf(getResources().getColor(colorId));
                mBinding.fabVideo.setSupportImageTintList(colorStateList);

            } else {
                mBinding.viewMainPreview.setVisibility(View.GONE);
                mBinding.tvConnectUSBCameraTip.setVisibility(View.VISIBLE);

                mBinding.fabPicture.setVisibility(View.GONE);
                mBinding.fabVideo.setVisibility(View.GONE);

                mBinding.tvVideoRecordTime.setVisibility(View.GONE);
            }
            invalidateOptionsMenu();
        });
    }

    private Size getSavedPreviewSize() {
        String key = getString(R.string.saved_preview_size) + USBMonitor.getProductKey(mUsbDevice);
        String sizeStr = getPreferences(MODE_PRIVATE).getString(key, null);
        if (TextUtils.isEmpty(sizeStr)) {
            return null;
        }
        Gson gson = new Gson();
        return gson.fromJson(sizeStr, Size.class);
    }

    private UVCParam getCameraOpenParam() {
        boolean isMediaTek = isMediaTekDevice();
        Size previewSize = getSavedPreviewSize();
        if (isMediaTek) {
            previewSize = getMediaTekOpenCandidate(previewSize);
        }
        int quirks = getPreviewQuirks(previewSize);
        if (DEBUG) Log.i(TAG, "getCameraOpenParam: quirks=0x" + Integer.toHexString(quirks)
                + ", previewSize=" + previewSize
                + ", hardware=" + Build.HARDWARE
                + ", board=" + Build.BOARD);
        return new UVCParam(previewSize, quirks);
    }

    private int getPreviewQuirks(Size previewSize) {
        int quirks = 0;
        if (previewSize != null && previewSize.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED) {
            quirks |= UVCCamera.UVC_QUIRK_FIX_BANDWIDTH;
        }
        return quirks;
    }

    private Size getMediaTekOpenCandidate(Size previewSize) {
        if (previewSize == null) {
            return new Size(
                    UVCCamera.UVC_VS_FRAME_UNCOMPRESSED,
                    MTK_PREFERRED_WIDTH,
                    MTK_PREFERRED_HEIGHT,
                    MTK_SAFE_FPS,
                    new ArrayList<>());
        }
        Size safeSize = previewSize.clone();
        if (safeSize.width == UVCCamera.DEFAULT_PREVIEW_WIDTH
                && safeSize.height == UVCCamera.DEFAULT_PREVIEW_HEIGHT) {
            safeSize.width = MTK_PREFERRED_WIDTH;
            safeSize.height = MTK_PREFERRED_HEIGHT;
        }
        safeSize.type = UVCCamera.UVC_VS_FRAME_UNCOMPRESSED;
        if (safeSize.width == MTK_FALLBACK_WIDTH && safeSize.height == MTK_FALLBACK_HEIGHT) {
            safeSize.width = MTK_PREFERRED_WIDTH;
            safeSize.height = MTK_PREFERRED_HEIGHT;
        }
        if (safeSize.fps > MTK_SAFE_FPS) {
            safeSize.fps = MTK_SAFE_FPS;
        }
        if (safeSize.fps <= 0) {
            safeSize.fps = MTK_SAFE_FPS;
        }
        if (safeSize.fpsList == null) {
            safeSize.fpsList = new ArrayList<>();
        }
        if (!safeSize.fpsList.contains(safeSize.fps)) {
            safeSize.fpsList.add(safeSize.fps);
        }
        return safeSize;
    }

    private void applyMediaTekPreviewPreference() {
        if (!isMediaTekDevice() || mCameraHelper == null) {
            return;
        }
        Size currentSize = mCameraHelper.getPreviewSize();
        List<Size> supportedSizes = mCameraHelper.getSupportedSizeList();
        if (currentSize == null || supportedSizes == null || supportedSizes.isEmpty()) {
            return;
        }
        Size preferredSize = resolveMediaTekPreferredSize(getSavedPreviewSize(), currentSize, supportedSizes);
        if (preferredSize == null || isSamePreviewSize(currentSize, preferredSize)) {
            return;
        }
        if (DEBUG) {
            Log.i(TAG, "applyMediaTekPreviewPreference: switch " + currentSize + " -> " + preferredSize);
        }
        mCameraHelper.setPreviewSize(preferredSize);
        setSavedPreviewSize(preferredSize);
    }

    private Size resolveMediaTekPreferredSize(Size savedSize, Size currentSize, List<Size> supportedSizes) {
        Size preferred = findExactSize(supportedSizes, MTK_PREFERRED_WIDTH, MTK_PREFERRED_HEIGHT, MTK_SAFE_FPS);
        if (preferred != null) {
            return preferred;
        }

        preferred = findMatchingUncompressedSize(supportedSizes, savedSize);
        if (preferred != null && !isLegacyMediaTekFallbackSize(preferred)) {
            return preferred;
        }

        preferred = findMatchingUncompressedSize(supportedSizes, currentSize);
        if (preferred != null && !isLegacyMediaTekFallbackSize(preferred)) {
            return preferred;
        }

        preferred = findExactSize(supportedSizes, MTK_FALLBACK_WIDTH, MTK_FALLBACK_HEIGHT, MTK_SAFE_FPS);
        if (preferred != null) {
            return preferred;
        }

        preferred = findBestUncompressedUnderSafeSize(supportedSizes,
                MTK_PREFERRED_WIDTH, MTK_PREFERRED_HEIGHT, MTK_SAFE_FPS);
        if (preferred != null) {
            return preferred;
        }

        preferred = findBestUncompressedSize(supportedSizes, MTK_SAFE_FPS);
        if (preferred != null) {
            return preferred;
        }

        return currentSize;
    }

    private Size findMatchingUncompressedSize(List<Size> supportedSizes, Size sourceSize) {
        if (sourceSize == null) {
            return null;
        }
        return findExactSize(supportedSizes, sourceSize.width, sourceSize.height, sourceSize.fps);
    }

    private boolean isLegacyMediaTekFallbackSize(Size size) {
        return size != null
                && size.width == MTK_FALLBACK_WIDTH
                && size.height == MTK_FALLBACK_HEIGHT;
    }

    private Size findExactSize(List<Size> supportedSizes, int width, int height, int targetFps) {
        for (Size supportedSize : supportedSizes) {
            if (supportedSize.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED
                    && supportedSize.width == width
                    && supportedSize.height == height) {
                return copySizeWithChosenFps(supportedSize, targetFps);
            }
        }
        return null;
    }

    private Size findBestUncompressedUnderSafeSize(List<Size> supportedSizes,
                                                   int safeWidth,
                                                   int safeHeight,
                                                   int targetFps) {
        Size best = null;
        int bestArea = -1;
        for (Size supportedSize : supportedSizes) {
            if (supportedSize.type != UVCCamera.UVC_VS_FRAME_UNCOMPRESSED
                    || supportedSize.width > safeWidth
                    || supportedSize.height > safeHeight) {
                continue;
            }
            int area = supportedSize.width * supportedSize.height;
            if (area > bestArea) {
                best = supportedSize;
                bestArea = area;
            }
        }
        return best != null ? copySizeWithChosenFps(best, targetFps) : null;
    }

    private Size findBestUncompressedSize(List<Size> supportedSizes, int targetFps) {
        Size best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Size supportedSize : supportedSizes) {
            if (supportedSize.type != UVCCamera.UVC_VS_FRAME_UNCOMPRESSED) {
                continue;
            }
            int score = Math.abs((supportedSize.width * supportedSize.height)
                    - (MTK_PREFERRED_WIDTH * MTK_PREFERRED_HEIGHT));
            if (score < bestScore) {
                best = supportedSize;
                bestScore = score;
            }
        }
        return best != null ? copySizeWithChosenFps(best, targetFps) : null;
    }

    private Size copySizeWithChosenFps(Size supportedSize, int targetFps) {
        Size selected = supportedSize.clone();
        selected.fps = chooseBestFps(supportedSize, targetFps);
        return selected;
    }

    private int chooseBestFps(Size supportedSize, int targetFps) {
        int bestAtOrBelowTarget = -1;
        int bestAboveTarget = Integer.MAX_VALUE;
        List<Integer> fpsList = supportedSize.fpsList;
        if (fpsList != null && !fpsList.isEmpty()) {
            for (Integer fps : fpsList) {
                if (fps == null || fps <= 0) {
                    continue;
                }
                if (fps <= targetFps && fps > bestAtOrBelowTarget) {
                    bestAtOrBelowTarget = fps;
                } else if (fps > targetFps && fps < bestAboveTarget) {
                    bestAboveTarget = fps;
                }
            }
        }
        if (bestAtOrBelowTarget > 0) {
            return bestAtOrBelowTarget;
        }
        if (bestAboveTarget != Integer.MAX_VALUE) {
            return bestAboveTarget;
        }
        return supportedSize.fps > 0 ? supportedSize.fps : targetFps;
    }

    private boolean isSamePreviewSize(Size first, Size second) {
        return first != null
                && second != null
                && first.type == second.type
                && first.width == second.width
                && first.height == second.height
                && first.fps == second.fps;
    }

    private boolean isMediaTekDevice() {
        return isMediaTekBuildValue(Build.HARDWARE)
                || isMediaTekBuildValue(Build.BOARD)
                || isMediaTekBuildValue(Build.MANUFACTURER)
                || isMediaTekBuildValue(Build.BRAND)
                || isMediaTekBuildValue(Build.DEVICE)
                || isMediaTekBuildValue(Build.MODEL);
    }

    private boolean isMediaTekBuildValue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.US);
        return normalized.contains("mediatek")
                || normalized.contains("mtk")
                || normalized.matches(".*(^|[^a-z0-9])mt[0-9].*")
                || normalized.matches("^mt[0-9].*");
    }

    private void setSavedPreviewSize(Size size) {
        String key = getString(R.string.saved_preview_size) + USBMonitor.getProductKey(mUsbDevice);
        Gson gson = new Gson();
        String json = gson.toJson(size);
        getPreferences(MODE_PRIVATE)
                .edit()
                .putString(key, json)
                .apply();
    }

    private void setCustomImageCaptureConfig() {
//        mCameraHelper.setImageCaptureConfig(
//                mCameraHelper.getImageCaptureConfig().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY));
        mCameraHelper.setImageCaptureConfig(
                mCameraHelper.getImageCaptureConfig().setJpegCompressionQuality(90));
    }

    public void takePicture() {
        if (mIsRecording) {
            return;
        }

        try {
            File file = new File(SaveHelper.getSavePhotoPath());
            ImageCapture.OutputFileOptions options =
                    new ImageCapture.OutputFileOptions.Builder(file).build();
            mCameraHelper.takePicture(options, new ImageCapture.OnImageCaptureCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    Toast.makeText(MainActivity.this,
                            "save \"" + UriHelper.getPath(MainActivity.this, outputFileResults.getSavedUri()) + "\"",
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int imageCaptureError, @NonNull String message, @Nullable Throwable cause) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
    }

    public void toggleVideoRecord(boolean isRecording) {
        try {
            if (isRecording) {
                if (mIsCameraConnected && mCameraHelper != null && !mCameraHelper.isRecording()) {
                    startRecord();
                }
            } else {
                if (mIsCameraConnected && mCameraHelper != null && mCameraHelper.isRecording()) {
                    stopRecord();
                }

                stopRecordTimer();
            }
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
            stopRecordTimer();
        }

        mIsRecording = isRecording;

        updateUIControls();
    }

    private void setCustomVideoCaptureConfig() {
        mCameraHelper.setVideoCaptureConfig(
                mCameraHelper.getVideoCaptureConfig()
//                        .setAudioCaptureEnable(false)
                        .setBitRate((int) (1024 * 1024 * 25 * 0.25))
                        .setVideoFrameRate(25)
                        .setIFrameInterval(1));
    }

    private void startRecord() {
        File file = new File(SaveHelper.getSaveVideoPath());
        VideoCapture.OutputFileOptions options =
                new VideoCapture.OutputFileOptions.Builder(file).build();
        mCameraHelper.startRecording(options, new VideoCapture.OnVideoCaptureCallback() {
            @Override
            public void onStart() {
                startRecordTimer();
            }

            @Override
            public void onVideoSaved(@NonNull VideoCapture.OutputFileResults outputFileResults) {
                toggleVideoRecord(false);

                Toast.makeText(
                        MainActivity.this,
                        "save \"" + UriHelper.getPath(MainActivity.this, outputFileResults.getSavedUri()) + "\"",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(int videoCaptureError, @NonNull String message, @Nullable Throwable cause) {
                toggleVideoRecord(false);

                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void stopRecord() {
        mCameraHelper.stopRecording();
    }

    private void startRecordTimer() {
        runOnUiThread(() -> mBinding.tvVideoRecordTime.setVisibility(View.VISIBLE));

        // Set “00:00:00” to record time TextView
        setVideoRecordTimeText(formatTime(0));

        // Start Record Timer
        mRecordStartTime = SystemClock.elapsedRealtime();
        mRecordTimer = new Timer();
        //The timer is refreshed every quarter second
        mRecordTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                long recordTime = (SystemClock.elapsedRealtime() - mRecordStartTime) / 1000;
                if (recordTime > 0) {
                    setVideoRecordTimeText(formatTime(recordTime));
                }
            }
        }, QUARTER_SECOND, QUARTER_SECOND);
    }

    private void stopRecordTimer() {
        runOnUiThread(() -> mBinding.tvVideoRecordTime.setVisibility(View.GONE));

        // Stop Record Timer
        mRecordStartTime = 0;
        if (mRecordTimer != null) {
            mRecordTimer.cancel();
            mRecordTimer = null;
        }
        // Set “00:00:00” to record time TextView
        setVideoRecordTimeText(formatTime(0));
    }

    private void setVideoRecordTimeText(String timeText) {
        runOnUiThread(() -> {
            mBinding.tvVideoRecordTime.setText(timeText);
        });
    }

    /**
     * 将秒转化为 HH:mm:ss 的格式
     *
     * @param time 秒
     * @return
     */
    private String formatTime(long time) {
        if (mDecimalFormat == null) {
            mDecimalFormat = new DecimalFormat("00");
        }
        String hh = mDecimalFormat.format(time / 3600);
        String mm = mDecimalFormat.format(time % 3600 / 60);
        String ss = mDecimalFormat.format(time % 60);
        return hh + ":" + mm + ":" + ss;
    }
}
