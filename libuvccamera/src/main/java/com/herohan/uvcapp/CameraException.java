package com.herohan.uvcapp;

public class CameraException extends Exception {
    /**
     * Unknown error occurred  when open camera
     */
    public static int CAMERA_OPEN_ERROR_UNKNOWN = 1;
    /**
     * The device is occupied when open camera.
     */
    public static int CAMERA_OPEN_ERROR_BUSY = 2;
    /**
     * Not enough USB bandwidth to start the video stream, e.g. another camera on the same
     * USB bus is using it.
     */
    public static int CAMERA_PREVIEW_ERROR_NO_BANDWIDTH = 3;
    /**
     * The video stream could not be started for another reason.
     */
    public static int CAMERA_PREVIEW_ERROR_UNKNOWN = 4;

    /**
     * Error code
     */
    private int code;

    private Exception internalError;

    CameraException(String message) {
        super(message);
    }

    CameraException(int code, String message) {
        super(message);
        this.code = code;
    }

    CameraException(int code, Exception cause) {
        super(cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @Override
    public String toString() {
        return "CameraException{" +
                "code=" + code +
                ",message=" + getMessage() +
                '}';
    }
}
