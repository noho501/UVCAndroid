package com.herohan.uvcapp.activity;

import android.Manifest;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.herohan.uvcapp.R;
import com.herohan.uvcapp.databinding.ActivityMultiCameraBinding;
import com.herohan.uvcapp.databinding.ItemUvcPreviewBinding;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;
import com.serenegiant.widget.AspectRatioSurfaceView;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Two UVC cameras: the selected one fills the main preview on the left, both are shown as
 * thumbnails in the panel on the right, and tapping a thumbnail selects that camera.
 * <p>
 * Camera handling follows the demo's MultiCameraNewActivity: one CameraHelper per camera,
 * USB permission requested for one device at a time, preview started once per camera.
 * Each camera owns its main and thumbnail SurfaceViews, so selecting a camera only changes
 * which main view is visible and no surface ever moves between cameras.
 * <p>
 * The frame counters of every open camera are polled: a camera whose stream runs without a
 * decoded frame shows "No picture" instead of a green dot over a black view, and a camera that
 * delivers frames none of which decode is moved to its next lower resolution.
 */
public class MultiCameraActivity extends AppCompatActivity {

    private static final String TAG = MultiCameraActivity.class.getSimpleName();
    private static final int MAX_BUSY_RETRIES = 3;
    private static final long BUSY_RETRY_DELAY_MS = 1000;
    private static final long BANDWIDTH_RETRY_DELAY_MS = 1000;
    private static final long STATS_POLL_MS = 500;
    // a running stream without a decoded frame for this long has no picture
    private static final long NO_PICTURE_TIMEOUT_MS = 3000;
    // frames that must arrive, none of them decodable, before a lower resolution is tried
    private static final int MIN_UNDECODABLE_FRAMES = 10;
    private static final int MAX_RESOLUTION_FALLBACKS = 2;
    // polls without counters before a slot stops waiting for them and shows the camera as live
    private static final int MAX_MISSING_COUNTS = 6;

    private static volatile boolean sActive;

    /**
     * True while this screen exists, so UsbAttachActivity leaves new devices to it
     * instead of bringing MainActivity (singleTask) to the front and closing this screen.
     */
    static boolean isActive() {
        return sActive;
    }

    private ActivityMultiCameraBinding mBinding;
    private Slot[] mSlots;
    private int mSelectedIndex = -1;
    // also read by the stats thread, which polls only while the screen is started
    private volatile boolean mStarted;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread mHandlerThread;
    private Handler mAsyncHandler;
    private final ConcurrentLinkedQueue<UsbDevice> mReadyUsbDeviceList = new ConcurrentLinkedQueue<>();
    private final ConditionVariable mReadyDeviceConditionVariable = new ConditionVariable();
    // Frame counters are read off the main thread: the call waits while a camera starts its preview
    private HandlerThread mStatsThread;
    private Handler mStatsHandler;
    // resolution that decoded after a fallback, per camera model (vid:pid), reused when it reconnects
    private final Map<String, Size> mFallbackSizes = new HashMap<>();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActive = true;

        mBinding = ActivityMultiCameraBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        mSlots = new Slot[]{
                new Slot(0, mBinding.viewMainPreview1, mBinding.itemUvc1),
                new Slot(1, mBinding.viewMainPreview2, mBinding.itemUvc2),
        };

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mAsyncHandler = new Handler(mHandlerThread.getLooper());
        mStatsThread = new HandlerThread(TAG + "Stats");
        mStatsThread.start();
        mStatsHandler = new Handler(mStatsThread.getLooper());

        mBinding.btnClosePanel.setOnClickListener(v -> finish());
        updateViews();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStarted = true;
        mStatsHandler.removeCallbacks(mStatsPoll);
        mStatsHandler.post(mStatsPoll);
        // In Android9+, connected to the UVC CAMERA, CAMERA permission is required
        XXPermissions.with(this)
                .permission(Manifest.permission.CAMERA)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(@NonNull List<String> permissions, boolean all) {
                        if (mStarted) {
                            initCameraHelpers();
                        }
                    }

                    @Override
                    public void onDenied(@NonNull List<String> permissions, boolean never) {
                        // No camera can be opened without the CAMERA permission
                    }
                });
    }

    @Override
    protected void onStop() {
        mStarted = false;
        mStatsHandler.removeCallbacks(mStatsPoll);
        clearCameraHelpers();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mMainHandler.removeCallbacksAndMessages(null);
        mReadyUsbDeviceList.clear();
        mReadyDeviceConditionVariable.open();
        mHandlerThread.quitSafely();
        mStatsHandler.removeCallbacksAndMessages(null);
        mStatsThread.quit();
        sActive = false;
        super.onDestroy();
    }

    private void initCameraHelpers() {
        for (Slot slot : mSlots) {
            if (slot.helper == null) {
                slot.helper = new CameraHelper();
                slot.helper.setStateCallback(slot);
            }
        }
    }

    private void clearCameraHelpers() {
        mMainHandler.removeCallbacksAndMessages(null);
        for (Slot slot : mSlots) {
            if (slot.helper != null) {
                slot.helper.release();
                slot.helper = null;
            }
            slot.device = null;
            slot.opened = false;
            slot.noBandwidth = false;
            slot.resetPictureWatch();
            slot.fallbackSize = null;
        }
        // Unblock a select that is still waiting for another device's permission
        mReadyUsbDeviceList.clear();
        mReadyDeviceConditionVariable.open();
        mSelectedIndex = -1;
        updateSelectionViews();
    }

    /**
     * Puts a newly attached device into the first free slot. Every helper reports every
     * device, so this runs once per helper for the same device and ignores the repeats.
     */
    private void assignDevice(UsbDevice device) {
        Slot free = null;
        for (Slot slot : mSlots) {
            if (device.equals(slot.device)) {
                return;
            }
            if (free == null && slot.device == null && slot.helper != null) {
                free = slot;
            }
        }
        if (free == null) {
            return;
        }
        free.device = device;
        free.busyRetries = 0;
        selectDevice(free, device);
        updateViews();
    }

    private void releaseDevice(UsbDevice device) {
        for (Slot slot : mSlots) {
            if (device.equals(slot.device)) {
                slot.device = null;
            }
        }
        removeSelectedDevice(device);
        updateViews();
    }

    private void selectDevice(Slot slot, UsbDevice device) {
        mAsyncHandler.post(() -> {
            waitCanSelectDevice(device);

            ICameraHelper helper = slot.helper;
            if (helper != null && device.equals(slot.device)) {
                helper.selectDevice(device);
            } else {
                removeSelectedDevice(device);
            }
        });
    }

    /**
     * wait for only one camera need request permission
     */
    private void waitCanSelectDevice(UsbDevice device) {
        mReadyUsbDeviceList.add(device);
        while (mReadyUsbDeviceList.size() > 1) {
            mReadyDeviceConditionVariable.block();
            mReadyDeviceConditionVariable.close();
        }
    }

    /**
     * remove ready camera that wait for select
     */
    private void removeSelectedDevice(UsbDevice device) {
        mReadyUsbDeviceList.remove(device);
        mReadyDeviceConditionVariable.open();
    }

    private void select(Slot slot) {
        mSelectedIndex = slot.index;
        updateSelectionViews();
    }

    private void selectFirstOpened() {
        mSelectedIndex = -1;
        for (Slot slot : mSlots) {
            if (slot.opened) {
                select(slot);
                return;
            }
        }
        updateSelectionViews();
    }

    private void updateSelectionViews() {
        for (Slot slot : mSlots) {
            boolean selected = slot.index == mSelectedIndex;
            slot.border.setVisibility(selected ? View.VISIBLE : View.GONE);
            slot.mainView.setVisibility(selected ? View.VISIBLE : View.GONE);
        }
        updateViews();
    }

    /**
     * Every preview area keeps a visible frame, label and status, and shows a placeholder
     * saying what to do while it has no picture, so it never looks like a plain black screen.
     */
    private void updateViews() {
        boolean anyConnecting = false;
        for (Slot slot : mSlots) {
            SlotState state = slot.state();
            anyConnecting |= state == SlotState.CONNECTING;
            setDotColor(slot.statusDot, state.colorRes);
            slot.placeholder.setVisibility(state == SlotState.LIVE ? View.GONE : View.VISIBLE);
            if (state != SlotState.LIVE) {
                if (state == SlotState.CONNECTING && slot.fallbackSize != null) {
                    slot.placeholderText.setText(trying(slot.fallbackSize));
                } else {
                    slot.placeholderText.setText(state.textRes);
                }
                slot.placeholderText.setTextColor(ContextCompat.getColor(this, state.warning
                        ? R.color.multi_status_busy : R.color.multi_text_secondary));
            }
        }

        Slot selected = mSelectedIndex >= 0 ? mSlots[mSelectedIndex] : null;
        SlotState selectedState = selected != null ? selected.state() : SlotState.EMPTY;
        mBinding.mainLabelChip.setVisibility(selected != null ? View.VISIBLE : View.GONE);
        if (selected != null) {
            mBinding.tvMainLabel.setText(selected.label);
            setDotColor(mBinding.mainStatusDot, selectedState.colorRes);
        }
        mBinding.mainPlaceholder.setVisibility(selectedState == SlotState.LIVE ? View.GONE : View.VISIBLE);
        if (selectedState == SlotState.NO_BANDWIDTH) {
            mBinding.tvMainPlaceholderTitle.setText(selected.label);
            mBinding.tvMainPlaceholderText.setText(getString(R.string.multi_camera_status_detail,
                    getString(R.string.multi_camera_no_bandwidth),
                    getString(R.string.multi_camera_no_bandwidth_hint)));
        } else if (selectedState == SlotState.NO_PICTURE) {
            mBinding.tvMainPlaceholderTitle.setText(selected.label);
            mBinding.tvMainPlaceholderText.setText(getString(R.string.multi_camera_status_detail,
                    getString(R.string.multi_camera_no_picture),
                    getString(selected.lastReceived > 0 ? R.string.multi_camera_no_picture_undecodable
                            : R.string.multi_camera_no_picture_no_frames)));
        } else if (selectedState == SlotState.CONNECTING && selected.fallbackSize != null) {
            mBinding.tvMainPlaceholderTitle.setText(selected.label);
            mBinding.tvMainPlaceholderText.setText(trying(selected.fallbackSize));
        } else {
            mBinding.tvMainPlaceholderTitle.setText(R.string.multi_camera_main_title);
            mBinding.tvMainPlaceholderText.setText(anyConnecting
                    ? R.string.multi_camera_slot_connecting : R.string.main_connect_usb_tip);
        }
    }

    private String trying(Size size) {
        return getString(R.string.multi_camera_trying_size, size.width, size.height);
    }

    private void setDotColor(View dot, int colorRes) {
        Drawable background = dot.getBackground().mutate();
        if (background instanceof GradientDrawable) {
            ((GradientDrawable) background).setColor(ContextCompat.getColor(this, colorRes));
        }
    }

    /**
     * A camera whose stream could not start for lack of USB bandwidth gets another
     * startPreview once another camera has closed and released its bandwidth.
     */
    private void retryNoBandwidthCameras() {
        for (Slot slot : mSlots) {
            if (slot.noBandwidth && slot.opened && slot.helper != null) {
                slot.noBandwidth = false;
                slot.helper.startPreview();
                slot.resetPictureWatch();
            }
        }
        updateViews();
    }

    /**
     * Reads the frame counters of every open camera on the stats thread and hands them to the
     * slot on the main thread.
     */
    private final Runnable mStatsPoll = new Runnable() {
        @Override
        public void run() {
            for (Slot slot : mSlots) {
                ICameraHelper helper = slot.helper;
                int generation = slot.previewGeneration;
                if (helper != null && slot.opened) {
                    int[] counts = helper.getPreviewFrameCounts();
                    mMainHandler.post(() -> slot.onFrameCounts(helper, generation, counts));
                }
            }
            if (mStarted) {
                // one pending poll at most, also when onStart posted one meanwhile
                mStatsHandler.removeCallbacks(this);
                mStatsHandler.postDelayed(this, STATS_POLL_MS);
            }
        }
    };

    private static String modelKey(UsbDevice device) {
        return String.format(Locale.US, "%04x:%04x", device.getVendorId(), device.getProductId());
    }

    private enum SlotState {
        EMPTY(R.string.multi_camera_slot_empty, R.color.multi_status_idle, false),
        // device assigned but not open yet, or open without a decoded frame yet
        CONNECTING(R.string.multi_camera_slot_connecting, R.color.multi_status_busy, false),
        NO_BANDWIDTH(R.string.multi_camera_no_bandwidth, R.color.multi_status_busy, true),
        // stream running, but no frame decoded for NO_PICTURE_TIMEOUT_MS
        NO_PICTURE(R.string.multi_camera_no_picture, R.color.multi_status_busy, true),
        LIVE(0, R.color.multi_status_live, false);

        final int textRes;
        final int colorRes;
        // shown in the warning color
        final boolean warning;

        SlotState(int textRes, int colorRes, boolean warning) {
            this.textRes = textRes;
            this.colorRes = colorRes;
            this.warning = warning;
        }
    }

    private class Slot implements ICameraHelper.StateCallback {
        final int index;
        final String label;
        final AspectRatioSurfaceView mainView;
        final AspectRatioSurfaceView thumbView;
        final View border;
        final View placeholder;
        final TextView placeholderText;
        final View statusDot;

        // helper, opened and previewGeneration are also read by the stats thread
        volatile ICameraHelper helper;
        UsbDevice device;
        volatile boolean opened;
        // the camera is open but its stream could not start (UVC_ERROR_NO_BANDWIDTH)
        boolean noBandwidth;
        int busyRetries;

        // Picture watch, restarted with every startPreview. Counters read for an older
        // generation are dropped, since the native counters restart at 0 with each preview.
        volatile int previewGeneration;
        long previewStartedAt;
        long lastDecodeAt;
        int lastReceived;
        int lastDecoded;
        boolean everDecoded;
        boolean noPicture;
        int missingCounts;
        // no counters arrived, so the camera is shown as live without checking (old behavior)
        boolean countsMissing;

        // resolution fallback for cameras whose frames do not decode
        Size previewSize;
        List<Size> supportedSizes;
        int fallbacks;
        // the lower resolution being tried, until it shows a picture
        Size fallbackSize;

        Slot(int index, AspectRatioSurfaceView mainView, ItemUvcPreviewBinding item) {
            this.index = index;
            this.label = getString(R.string.multi_camera_slot_label, index + 1);
            this.mainView = mainView;
            this.thumbView = item.uvcPreview;
            this.border = item.uvcRedBorder;
            this.placeholder = item.uvcPlaceholder;
            this.placeholderText = item.uvcPlaceholderText;
            this.statusDot = item.uvcStatusDot;
            item.uvcLabel.setText(label);
            bindSurface(mainView);
            bindSurface(thumbView);
            item.getRoot().setOnClickListener(v -> {
                if (!opened) {
                    return;
                }
                select(this);
            });
        }

        SlotState state() {
            if (opened) {
                if (noBandwidth) {
                    return SlotState.NO_BANDWIDTH;
                }
                if (noPicture) {
                    return SlotState.NO_PICTURE;
                }
                return everDecoded || countsMissing ? SlotState.LIVE : SlotState.CONNECTING;
            }
            return device != null ? SlotState.CONNECTING : SlotState.EMPTY;
        }

        void resetPictureWatch() {
            previewGeneration++;
            previewStartedAt = lastDecodeAt = SystemClock.uptimeMillis();
            lastReceived = lastDecoded = 0;
            everDecoded = noPicture = countsMissing = false;
            missingCounts = 0;
        }

        /**
         * Frame counters read by the stats thread: {received, decoded} since startPreview,
         * or null when the camera gave none.
         */
        void onFrameCounts(ICameraHelper from, int generation, int[] counts) {
            if (from != helper || generation != previewGeneration || !opened || noBandwidth) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (counts == null || counts.length < 2) {
                if (!everDecoded && !countsMissing && ++missingCounts >= MAX_MISSING_COUNTS) {
                    countsMissing = true;
                    updateViews();
                }
                return;
            }
            missingCounts = 0;
            int received = counts[0];
            int decoded = counts[1];
            boolean changed = false;
            if (decoded > lastDecoded) {
                lastDecodeAt = now;
                if (!everDecoded) {
                    everDecoded = true;
                    changed = true;
                    if (fallbackSize != null && device != null) {
                        mFallbackSizes.put(modelKey(device), fallbackSize);
                    }
                    fallbackSize = null;
                }
                if (noPicture) {
                    noPicture = false;
                    changed = true;
                }
            }
            lastReceived = received;
            lastDecoded = decoded;
            if (!noPicture && now - lastDecodeAt >= NO_PICTURE_TIMEOUT_MS) {
                noPicture = true;
                changed = true;
                if (!everDecoded && received >= MIN_UNDECODABLE_FRAMES) {
                    tryLowerResolution(received);
                }
            }
            if (changed) {
                updateViews();
            }
        }

        /**
         * Frames arrive but none decodes at this resolution: restart at the next lower one.
         */
        private void tryLowerResolution(int received) {
            Size next = fallbacks < MAX_RESOLUTION_FALLBACKS ? nextLowerSize(previewSize) : null;
            if (next == null || helper == null) {
                fallbackSize = null;
                return;
            }
            fallbacks++;
            fallbackSize = next;
            previewSize = next;
            helper.stopPreview();
            helper.setPreviewSize(next);
            helper.startPreview();
            mainView.setAspectRatio(next.width, next.height);
            thumbView.setAspectRatio(next.width, next.height);
            resetPictureWatch();
        }

        /**
         * The largest supported size of the same frame type (MJPEG / YUV) below the current one.
         */
        private Size nextLowerSize(Size current) {
            if (current == null || supportedSizes == null) {
                return null;
            }
            long currentArea = (long) current.width * current.height;
            Size best = null;
            for (Size size : supportedSizes) {
                long area = (long) size.width * size.height;
                if (size.type == current.type && area < currentArea
                        && (best == null || area > (long) best.width * best.height)) {
                    best = size;
                }
            }
            return best;
        }

        private void bindSurface(AspectRatioSurfaceView view) {
            view.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    if (opened && helper != null) {
                        helper.addSurface(holder.getSurface(), false);
                    }
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    if (helper != null) {
                        helper.removeSurface(holder.getSurface());
                    }
                }
            });
        }

        private void addSurfaces() {
            addSurface(mainView);
            addSurface(thumbView);
        }

        private void addSurface(AspectRatioSurfaceView view) {
            // Otherwise added later from surfaceCreated (e.g. main view of a camera that is not selected)
            if (view.getHolder().getSurface().isValid()) {
                helper.addSurface(view.getHolder().getSurface(), false);
            }
        }

        private void removeSurfaces() {
            if (helper != null) {
                helper.removeSurface(mainView.getHolder().getSurface());
                helper.removeSurface(thumbView.getHolder().getSurface());
            }
        }

        private void openCamera() {
            UVCParam param = new UVCParam();
            param.setQuirks(UVCCamera.UVC_QUIRK_FIX_BANDWIDTH);
            Size remembered = device != null ? mFallbackSizes.get(modelKey(device)) : null;
            if (remembered != null) {
                param.setPreviewSize(remembered);
            }
            helper.openCamera(param);
        }

        @Override
        public void onAttach(UsbDevice device) {
            assignDevice(device);
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (helper != null && device.equals(this.device)) {
                openCamera();
            }

            removeSelectedDevice(device);
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            if (helper != null && device.equals(this.device)) {
                supportedSizes = helper.getSupportedSizeList();
                noBandwidth = false;
                fallbacks = 0;
                fallbackSize = null;
                helper.startPreview();

                Size size = helper.getPreviewSize();
                previewSize = size;
                if (size != null) {
                    //auto aspect ratio
                    mainView.setAspectRatio(size.width, size.height);
                    thumbView.setAspectRatio(size.width, size.height);
                }

                opened = true;
                resetPictureWatch();
                addSurfaces();
                if (mSelectedIndex < 0) {
                    select(this);
                }
                updateViews();
            }
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            if (device.equals(this.device) || opened) {
                removeSurfaces();
                opened = false;
                noBandwidth = false;
                resetPictureWatch();
                fallbackSize = null;
                previewSize = null;
                if (mSelectedIndex == index) {
                    selectFirstOpened();
                }
                updateViews();
                for (Slot slot : mSlots) {
                    if (slot.noBandwidth) {
                        mMainHandler.postDelayed(MultiCameraActivity.this::retryNoBandwidthCameras,
                                BANDWIDTH_RETRY_DELAY_MS);
                        break;
                    }
                }
            }
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
        }

        @Override
        public void onDetach(UsbDevice device) {
            releaseDevice(device);
        }

        @Override
        public void onCancel(UsbDevice device) {
            releaseDevice(device);
        }

        @Override
        public void onError(UsbDevice device, CameraException e) {
            if (e.getCode() == CameraException.CAMERA_PREVIEW_ERROR_NO_BANDWIDTH) {
                // Open but not streaming; retried when another camera closes
                if (device.equals(this.device) && opened) {
                    noBandwidth = true;
                    updateViews();
                }
                return;
            }
            // The camera may still be held by the previous screen for a moment
            if (e.getCode() == CameraException.CAMERA_OPEN_ERROR_BUSY && device.equals(this.device)
                    && busyRetries < MAX_BUSY_RETRIES) {
                busyRetries++;
                mMainHandler.postDelayed(() -> {
                    if (helper != null && device.equals(this.device) && !opened) {
                        openCamera();
                    }
                }, BUSY_RETRY_DELAY_MS);
            }
        }
    }
}