package com.dx.ghostmessenger.call;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Captures low resolution camera frames and hands them over as JPEG.
 * Resolution, quality and frame rate are kept small on purpose because the
 * video travels over Tor, next to the Codec2 voice stream.
 */
@SuppressWarnings("deprecation")
public class VideoCapture implements Camera.PreviewCallback {

    public interface FrameSink {
        /**
         * @param jpeg           encoded frame (not rotated)
         * @param rotationQuarter clockwise quarter turns needed to display the frame upright
         * @param front          true when the frame comes from the front camera
         */
        void onFrame(byte[] jpeg, int rotationQuarter, boolean front);

        void onError(Exception e);
    }

    private static final String TAG = "GHOSTMESSENGER";
    private static final int TARGET_W = 320;
    private static final int TARGET_H = 240;
    private static final int JPEG_QUALITY = 35;
    private static final long MIN_INTERVAL_MS = 200; // ~5 fps

    private final FrameSink sink;
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream(16 * 1024);
    private HandlerThread thread;
    private Camera camera;
    private SurfaceTexture texture;
    private int width;
    private int height;
    private int rotationQuarter;
    private boolean front;
    private long lastFrame;
    private volatile boolean running;

    public VideoCapture(FrameSink sink) {
        this.sink = sink;
    }

    /**
     * @param useFront        use the front camera when available
     * @param displayRotation current display rotation in degrees (0, 90, 180, 270)
     */
    public synchronized void start(boolean useFront, int displayRotation) {
        if (running) {
            return;
        }
        running = true;
        thread = new HandlerThread("ghost-video-capture");
        thread.start();
        new Handler(thread.getLooper()).post(() -> open(useFront, displayRotation));
    }

    private void open(boolean useFront, int displayRotation) {
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int wanted = useFront ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
            int id = -1;
            Camera.CameraInfo chosen = null;
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == wanted) {
                    id = i;
                    chosen = new Camera.CameraInfo();
                    Camera.getCameraInfo(i, chosen);
                    break;
                }
            }
            if (id == -1) { // fall back to whatever camera exists
                if (Camera.getNumberOfCameras() == 0) {
                    throw new IllegalStateException("no camera");
                }
                id = 0;
                chosen = new Camera.CameraInfo();
                Camera.getCameraInfo(0, chosen);
            }
            front = chosen.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
            int degrees = front
                    ? (chosen.orientation + displayRotation) % 360
                    : (chosen.orientation - displayRotation + 360) % 360;
            rotationQuarter = (degrees / 90) & 3;

            camera = Camera.open(id);
            Camera.Parameters p = camera.getParameters();
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            Camera.Size best = sizes.get(0);
            int bestScore = Integer.MAX_VALUE;
            for (Camera.Size s : sizes) {
                int score = Math.abs(s.width - TARGET_W) + Math.abs(s.height - TARGET_H);
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            width = best.width;
            height = best.height;
            p.setPreviewSize(width, height);
            p.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(p);
            texture = new SurfaceTexture(10);
            camera.setPreviewTexture(texture);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            Log.e(TAG, "camera start failed", e);
            stop();
            sink.onError(e);
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        long now = System.currentTimeMillis();
        if (!running || now - lastFrame < MIN_INTERVAL_MS) {
            return;
        }
        lastFrame = now;
        try {
            baos.reset();
            new YuvImage(data, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, baos);
            sink.onFrame(baos.toByteArray(), rotationQuarter, front);
        } catch (Exception e) {
            Log.e(TAG, "frame encode failed", e);
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (camera != null) {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            }
        } catch (Exception ignored) {
        }
        camera = null;
        if (texture != null) {
            texture.release();
            texture = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
