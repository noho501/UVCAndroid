package com.herohan.uvcapp.activity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.herohan.uvcapp.R;
import com.hjq.permissions.XXPermissions;
import com.serenegiant.usb.Format;
import com.serenegiant.usb.Size;
import com.serenegiant.widget.AspectRatioTextureView;

import java.util.List;

public class MultiUvcController {
    private Context context;
    private View panelView;
    private View border1;
    private View border2;

    private ICameraHelper helper1;
    private ICameraHelper helper2;
    private UsbDevice device1;
    private UsbDevice device2;

    // 2 Màn hình độc lập cho 2 Camera
    private AspectRatioTextureView mainPreviewView1;
    private AspectRatioTextureView mainPreviewView2;

    private int selectedIndex = -1;
    private MultiUvcListener listener;
    private boolean isPanelActive = false;

    private Handler uiHandler = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler bgHandler;

    public interface MultiUvcListener {
        void onMainCameraSelected(ICameraHelper activeHelper);
        void onDeselected();
        void onPanelClosed();
    }

    public MultiUvcController(Context context, View panelView, AspectRatioTextureView originalPreviewView, MultiUvcListener listener) {
        this.context = context;
        this.panelView = panelView;
        this.listener = listener;

        View item1 = panelView.findViewById(R.id.itemUvc1);
        View item2 = panelView.findViewById(R.id.itemUvc2);
        border1 = item1.findViewById(R.id.uvcRedBorder);
        border2 = item2.findViewById(R.id.uvcRedBorder);
        ImageView btnClose = panelView.findViewById(R.id.btnClosePanel);

        helper1 = new CameraHelper();
        helper2 = new CameraHelper();

        // FIX LỖI AUDIO: Tắt ngầm Audio Capture cho cả 2 helper ngay lúc khởi tạo
        // để tránh thư viện libuvccamera tự gọi AudioRecord gây kẹt pipeline hình ảnh
        if (helper1.getVideoCaptureConfig() != null) {
            helper1.setVideoCaptureConfig(helper1.getVideoCaptureConfig().setAudioCaptureEnable(false));
        }
        if (helper2.getVideoCaptureConfig() != null) {
            helper2.setVideoCaptureConfig(helper2.getVideoCaptureConfig().setAudioCaptureEnable(false));
        }

        workerThread = new HandlerThread("MultiUvcWorker");
        workerThread.start();
        bgHandler = new Handler(workerThread.getLooper());

        item1.setOnClickListener(v -> handleSelect(1));
        item2.setOnClickListener(v -> handleSelect(2));
        btnClose.setOnClickListener(v -> showCloseDialog());

        // THUẬT TOÁN TẠO 2 SURFACE RIÊNG BIỆT TRONG CÙNG 1 KHUNG HÌNH (Tránh EGL_BAD_ALLOC)
        this.mainPreviewView1 = originalPreviewView;
        ViewGroup parent = (ViewGroup) mainPreviewView1.getParent();
        int index = parent.indexOfChild(mainPreviewView1);
        ViewGroup.LayoutParams originalParams = mainPreviewView1.getLayoutParams();

        parent.removeView(mainPreviewView1);

        FrameLayout container = new FrameLayout(context);
        container.setLayoutParams(originalParams);

        FrameLayout.LayoutParams flParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        flParams.gravity = Gravity.CENTER;

        mainPreviewView1.setLayoutParams(flParams);
        container.addView(mainPreviewView1);

        // Ép CẢ 2 View phải VISIBLE ngay từ đầu để Native EGL cấp phát Surface
        mainPreviewView1.setVisibility(View.VISIBLE);
        mainPreviewView1.setAlpha(0f);

        this.mainPreviewView2 = new AspectRatioTextureView(context);
        this.mainPreviewView2.setLayoutParams(flParams);
        this.mainPreviewView2.setVisibility(View.VISIBLE);
        this.mainPreviewView2.setAlpha(0f); // Ẩn bằng alpha
        container.addView(this.mainPreviewView2);

        parent.addView(container, index);

        // Gắn cố định helper1 vào màn hình 1
        mainPreviewView1.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                bgHandler.post(() -> { try { helper1.addSurface(st, false); } catch (Exception ignored) {} });
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                bgHandler.post(() -> { try { helper1.removeSurface(st); } catch (Exception ignored) {} });
                return false;
            }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });

        // Gắn cố định helper2 vào màn hình 2
        mainPreviewView2.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                bgHandler.post(() -> { try { helper2.addSurface(st, false); } catch (Exception ignored) {} });
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                bgHandler.post(() -> { try { helper2.removeSurface(st); } catch (Exception ignored) {} });
                return false;
            }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });
    }

    public void checkAndStart(List<UsbDevice> devices) {
        if (devices.size() >= 1) {
            XXPermissions.with(context)
                    .permission(Manifest.permission.CAMERA)
                    .request((permissions, all) -> {
                        if (devices.size() >= 2) {
                            startTwoDevices(devices.get(0), devices.get(1));
                        } else {
                            startSingleDevice(devices.get(0));
                        }
                    });
        } else {
            closePanel();
        }
    }

    // =========================================================================
    // KHỞI ĐỘNG 1 CAMERA
    // =========================================================================
    private void startSingleDevice(UsbDevice dev1) {
        uiHandler.post(() -> {
            isPanelActive = false;
            panelView.setVisibility(View.GONE);
            selectedIndex = -1;
            updateBordersUi();
        });

        bgHandler.post(() -> {
            boolean needInit = device1 == null || !device1.getDeviceName().equals(dev1.getDeviceName());
            device1 = dev1;
            device2 = null;

            if (needInit) {
                helper1.closeCamera();
                helper2.closeCamera();

                helper1.setStateCallback(new ICameraHelper.StateCallback() {
                    @Override public void onDeviceOpen(UsbDevice dev, boolean isFirstOpen) {
                        helper1.openCamera();
                    }
                    @Override public void onCameraOpen(UsbDevice dev) {
                        bgHandler.post(() -> {
                            // 1. Chạy mồi preview để thư viện khởi tạo chuẩn state machine
                            try { helper1.startPreview(); } catch (Exception ignored) {}

                            // 2. Dừng lại ngay lập tức để huỷ bộ giải mã cũ
                            helper1.stopPreview();

                            // 3. Ép độ phân giải nhỏ nhất 30fps an toàn
                            applySmallest30fps(helper1);

                            Size finalSize = helper1.getPreviewSize();
                            if (finalSize != null) {
                                uiHandler.post(() -> mainPreviewView1.setAspectRatio(finalSize.width, finalSize.height));
                            }

                            // 4. Khởi động lại với độ phân giải thấp và gắn Surface
                            try {
                                helper1.startPreview();
                                if (mainPreviewView1.getSurfaceTexture() != null) {
                                    helper1.addSurface(mainPreviewView1.getSurfaceTexture(), false);
                                }
                            } catch (Exception ignored) {}

                            uiHandler.post(() -> handleSelect(1));
                        });
                    }
                    @Override public void onAttach(UsbDevice dev) {}
                    @Override public void onDeviceClose(UsbDevice dev) {}
                    @Override public void onCameraClose(UsbDevice dev) {}
                    @Override public void onDetach(UsbDevice dev) {}
                    @Override public void onCancel(UsbDevice dev) {}
                });
                uiHandler.post(() -> helper1.selectDevice(device1));
            } else {
                uiHandler.post(() -> handleSelect(1));
            }
        });
    }

    // =========================================================================
    // KHỞI ĐỘNG 2 CAMERA (Luôn chạy ngầm đồng thời)
    // =========================================================================
    private void startTwoDevices(UsbDevice dev1, UsbDevice dev2) {
        uiHandler.post(() -> {
            isPanelActive = true;
            panelView.setVisibility(View.VISIBLE);
        });

        bgHandler.post(() -> {
            boolean wasSingleMode = (device1 != null && device2 == null);
            boolean needInit1 = device1 == null || !device1.getDeviceName().equals(dev1.getDeviceName());
            boolean needInit2 = device2 == null || !device2.getDeviceName().equals(dev2.getDeviceName());

            device1 = dev1;
            device2 = dev2;

            if (needInit1) {
                resetSelectionBg();
                helper1.closeCamera();
                helper2.closeCamera();
                initDevice1Then2Bg();
            } else if (needInit2 || wasSingleMode) {
                resetSelectionBg();
                helper2.closeCamera();
                initDeviceBg(helper2, device2);
            }
        });
    }

    private void initDevice1Then2Bg() {
        helper1.setStateCallback(new ICameraHelper.StateCallback() {
            @Override public void onDeviceOpen(UsbDevice dev, boolean isFirstOpen) {
                helper1.openCamera();
            }
            @Override public void onCameraOpen(UsbDevice dev) {
                bgHandler.post(() -> {
                    // Chạy mồi và cấu hình lại
                    try { helper1.startPreview(); } catch (Exception ignored) {}
                    helper1.stopPreview();

                    applySmallest30fps(helper1);
                    Size s = helper1.getPreviewSize();
                    if (s != null) uiHandler.post(() -> mainPreviewView1.setAspectRatio(s.width, s.height));

                    try {
                        helper1.startPreview();
                        if (mainPreviewView1.getSurfaceTexture() != null) {
                            helper1.addSurface(mainPreviewView1.getSurfaceTexture(), false);
                        }
                    } catch (Exception ignored) {}

                    // Đợi luồng 1 mượt mà rồi mới gọi luồng 2
                    bgHandler.postDelayed(() -> initDeviceBg(helper2, device2), 500);
                });
            }
            @Override public void onAttach(UsbDevice dev) {}
            @Override public void onDeviceClose(UsbDevice dev) {}
            @Override public void onCameraClose(UsbDevice dev) {}
            @Override public void onDetach(UsbDevice dev) {}
            @Override public void onCancel(UsbDevice dev) {}
        });
        uiHandler.post(() -> helper1.selectDevice(device1));
    }

    private void initDeviceBg(ICameraHelper helper, UsbDevice device) {
        helper.setStateCallback(new ICameraHelper.StateCallback() {
            @Override public void onDeviceOpen(UsbDevice dev, boolean isFirstOpen) {
                helper.openCamera();
            }
            @Override public void onCameraOpen(UsbDevice dev) {
                bgHandler.post(() -> {
                    // Chạy mồi và cấu hình lại
                    try { helper.startPreview(); } catch (Exception ignored) {}
                    helper.stopPreview();

                    applySmallest30fps(helper);
                    Size s = helper.getPreviewSize();

                    AspectRatioTextureView targetView = (helper == helper1) ? mainPreviewView1 : mainPreviewView2;
                    if (s != null) uiHandler.post(() -> targetView.setAspectRatio(s.width, s.height));

                    try {
                        helper.startPreview();
                        if (targetView.getSurfaceTexture() != null) {
                            helper.addSurface(targetView.getSurfaceTexture(), false);
                        }
                    } catch (Exception ignored) {}
                });
            }
            @Override public void onAttach(UsbDevice dev) {}
            @Override public void onDeviceClose(UsbDevice dev) {}
            @Override public void onCameraClose(UsbDevice dev) {}
            @Override public void onDetach(UsbDevice dev) {}
            @Override public void onCancel(UsbDevice dev) {}
        });
        uiHandler.post(() -> helper.selectDevice(device));
    }

    // =========================================================================
    // TÌM ĐỘ PHÂN GIẢI NHỎ NHẤT HỖ TRỢ 30FPS VÀ SET CỨNG
    // =========================================================================
    private void applySmallest30fps(ICameraHelper helper) {
        List<Format> formats = helper.getSupportedFormatList();
        if (formats == null || formats.isEmpty()) return;

        Size bestSize = null;
        int minArea = Integer.MAX_VALUE;

        for (Format f : formats) {
            for (Format.Descriptor d : f.frameDescriptors) {
                int area = d.width * d.height;
                boolean has30fps = false;

                for (Format.Interval i : d.intervals) {
                    if (i.fps == 30) {
                        has30fps = true;
                        break;
                    }
                }

                // Lấy độ phân giải nhỏ nhất có fps = 30 (chú ý lấy chuẩn d.type)
                if (has30fps && area < minArea) {
                    minArea = area;
                    bestSize = new Size(d.type, d.width, d.height, 30, null);
                }
            }
        }

        // Fallback: Nếu không tìm thấy cái nào 30fps thì lấy cái nhỏ nhất bất kì
        if (bestSize == null) {
            Format.Descriptor smallest = formats.get(0).frameDescriptors.get(0);
            bestSize = new Size(smallest.type, smallest.width, smallest.height, smallest.intervals.get(0).fps, null);
        }

        try {
            helper.setPreviewSize(bestSize);
        } catch (Exception e) {
            Log.e("MultiUvcController", "Lỗi cấu hình độ phân giải", e);
        }
    }

    // =========================================================================
    // XỬ LÝ SỰ KIỆN CLICK CHUYỂN CAMERA BẰNG CÁCH ẨN/HIỆN SURFACE
    // =========================================================================
    private void handleSelect(int index) {
        uiHandler.post(() -> {
            if (selectedIndex == index) {
                // Bấm lại để bỏ chọn, ẩn cả 2 màn hình bằng Alpha
                selectedIndex = -1;
                updateBordersUi();
                mainPreviewView1.setAlpha(0f);
                mainPreviewView2.setAlpha(0f);
                listener.onDeselected();
            } else {
                // Chuyển màn hình cực nhanh bằng Alpha, giữ nguyên trạng thái Surface
                selectedIndex = index;
                updateBordersUi();

                if (index == 1) {
                    mainPreviewView1.setAlpha(1f);
                    mainPreviewView2.setAlpha(0f);
                    listener.onMainCameraSelected(helper1);
                } else if (index == 2) {
                    mainPreviewView1.setAlpha(0f);
                    mainPreviewView2.setAlpha(1f);
                    listener.onMainCameraSelected(helper2);
                }
            }
        });
    }

    private void resetSelectionBg() {
        uiHandler.post(() -> {
            selectedIndex = -1;
            updateBordersUi();
            mainPreviewView1.setAlpha(0f);
            mainPreviewView2.setAlpha(0f);
            listener.onDeselected();
        });
    }

    private void updateBordersUi() {
        border1.setVisibility(selectedIndex == 1 ? View.VISIBLE : View.GONE);
        border2.setVisibility(selectedIndex == 2 ? View.VISIBLE : View.GONE);
    }

    private void showCloseDialog() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.multi_uvc_close_title)
                .setMessage(R.string.multi_uvc_close_msg)
                .setPositiveButton(R.string.multi_uvc_yes, (dialog, which) -> closePanel())
                .setNegativeButton(R.string.multi_uvc_no, null)
                .show();
    }

    public void closePanel() {
        uiHandler.post(() -> {
            isPanelActive = false;
            panelView.setVisibility(View.GONE);
            selectedIndex = -1;
            updateBordersUi();
            mainPreviewView1.setAlpha(0f);
            mainPreviewView2.setAlpha(0f);
            listener.onPanelClosed();

            bgHandler.post(() -> {
                helper1.closeCamera();
                helper2.closeCamera();
                device1 = null;
                device2 = null;
            });
        });
    }

    private ICameraHelper getActiveHelperBg() {
        if (device2 == null && device1 != null) return helper1;
        if (selectedIndex == 1) return helper1;
        if (selectedIndex == 2) return helper2;
        return null;
    }

    public ICameraHelper getActiveHelper() {
        return getActiveHelperBg();
    }

    public void release() {
        bgHandler.post(() -> {
            helper1.release();
            helper2.release();
            workerThread.quitSafely();
        });
    }
}