package app.pfandcounter;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Live camera preview plus barcode decoding, on the plain framework Camera2 API — no support
 * libraries, so the app stays a single small APK.
 *
 * Decoding runs on the camera's own background thread, throttled: at most one attempt every
 * {@link #DECODE_INTERVAL_MS}, and never while the UI has a dialog open.
 */
class BarcodeScanner implements TextureView.SurfaceTextureListener {

    interface Listener {
        void onCode(String code);
        /** A code that is not a product number — a web-address QR code, say. Scanning goes on. */
        void onOtherCode();
        void onProblem(String message);
        /** The first frame after a problem: the camera works again, whatever onProblem said. */
        void onCameraBack();
        /** Asked on the camera thread: a code the app already knows needs less proof. */
        boolean isKnown(String code);
    }

    private static final long DECODE_INTERVAL_MS = 120;
    private static final long SAME_CODE_COOLDOWN_MS = 1500;
    private static final long OTHER_CODE_COOLDOWN_MS = 2500;
    /** A number counts once two frames in a row agree; one noisy frame can invent a valid-looking EAN. */
    private static final long CONFIRM_WINDOW_MS = 600;
    /** Frames that must agree: two for a known code, three for one the app would ask about. */
    private static final int AGREE_KNOWN = 2, AGREE_UNKNOWN = 3;

    private final Activity activity;
    private final TextureView view;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Decoder decoder = new Decoder();

    private HandlerThread thread;
    private Handler background;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private ImageReader imageReader;
    private Size frameSize;
    private String cameraId;
    private boolean torchOn;
    private boolean torchAvailable;
    private boolean started;

    private volatile boolean paused;
    /** Set by a problem, cleared by the next frame — so a short outage does not leave its notice behind. */
    private volatile boolean troubled;
    private long lastDecodeAt;
    private String lastCode;
    private long lastCodeAt;
    private long lastOtherAt;
    private String candidate;
    private long candidateAt;
    private int agreeing;
    private byte[] yBuffer;
    private byte[] rotatedBuffer;

    BarcodeScanner(Activity activity, TextureView view, Listener listener) {
        this.activity = activity;
        this.view = view;
        this.listener = listener;
    }

    void start() {
        if (started) return;
        started = true;
        thread = new HandlerThread("camera");
        thread.start();
        background = new Handler(thread.getLooper());
        view.setSurfaceTextureListener(this);
        if (view.isAvailable()) {
            openCamera(view.getWidth(), view.getHeight());
        }
    }

    void stop() {
        started = false;
        closeCamera();
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
            background = null;
        }
    }

    /** True while the UI is busy (a dialog is up) — frames are then dropped, not queued. */
    void setPaused(boolean paused) {
        this.paused = paused;
    }

    boolean isTorchAvailable() {
        return torchAvailable;
    }

    boolean toggleTorch() {
        if (!torchAvailable || request == null || session == null) return false;
        torchOn = !torchOn;
        request.set(CaptureRequest.FLASH_MODE,
                torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        repeat();
        return torchOn;
    }

    /** Lets the same barcode be scanned again right away (after the user edits a line). */
    void forgetLastCode() {
        lastCode = null;
    }

    // --- camera plumbing ---------------------------------------------------

    private void openCamera(int viewWidth, int viewHeight) {
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            problem("No camera service");
            return;
        }
        try {
            cameraId = pickBackCamera(manager);
            if (cameraId == null) {
                problem("No camera found");
                return;
            }
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            torchAvailable = flash != null && flash;
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                problem("Camera has no usable configuration");
                return;
            }
            frameSize = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            imageReader = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(onFrame, background);
            configureTransform(viewWidth, viewHeight);
            manager.openCamera(cameraId, stateCallback, background);
        } catch (CameraAccessException e) {
            problem("Camera busy or blocked");
            Log.w(Store.TAG, "openCamera failed", e);
        } catch (SecurityException e) {
            problem("Camera permission missing");
        } catch (IllegalArgumentException e) {
            problem("Camera cannot be opened");
            Log.w(Store.TAG, "openCamera failed", e);
        }
    }

    private String pickBackCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null || map.getOutputSizes(ImageFormat.YUV_420_888) == null) continue;
            if (fallback == null) fallback = id;
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return fallback;
    }

    /** Around 1280x720 is the sweet spot: enough detail for a thin EAN, cheap enough to decode. */
    private static Size pickSize(Size[] choices) {
        Size best = null;
        for (Size s : choices) {
            long pixels = (long) s.getWidth() * s.getHeight();
            if (s.getWidth() > 1600 || pixels < 300000) continue;
            if (best == null || pixels > (long) best.getWidth() * best.getHeight()) best = s;
        }
        if (best == null) best = choices[0];
        return best;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) {
            camera = device;
            createSession();
        }

        @Override public void onDisconnected(CameraDevice device) {
            device.close();
            camera = null;
        }

        @Override public void onError(CameraDevice device, int error) {
            device.close();
            camera = null;
            problem("Camera error " + error);
        }
    };

    private void createSession() {
        SurfaceTexture texture = view.getSurfaceTexture();
        if (texture == null || camera == null || imageReader == null) return;
        texture.setDefaultBufferSize(frameSize.getWidth(), frameSize.getHeight());
        Surface preview = new Surface(texture);
        try {
            request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(preview);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(preview, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            session = configured;
                            repeat();
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession configured) {
                            problem("Camera preview could not start");
                        }
                    }, background);
        } catch (CameraAccessException e) {
            problem("Camera busy or blocked");
            Log.w(Store.TAG, "createSession failed", e);
        } catch (IllegalStateException e) {
            Log.w(Store.TAG, "createSession on a closed camera", e);
        }
    }

    private void repeat() {
        if (session == null || request == null) return;
        try {
            session.setRepeatingRequest(request.build(), null, background);
        } catch (CameraAccessException e) {
            Log.w(Store.TAG, "setRepeatingRequest failed", e);
        } catch (IllegalStateException e) {
            Log.w(Store.TAG, "session already closed", e);
        }
    }

    private void closeCamera() {
        torchOn = false;
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        request = null;
    }

    /** For the test intent: the same path a real camera error takes. */
    void simulateProblem() {
        problem("Test: camera problem");
    }

    private void problem(final String message) {
        main.post(new Runnable() {
            @Override public void run() {
                listener.onProblem(message);
                // Only now: a frame arriving earlier would take back a notice not yet shown.
                troubled = true;
            }
        });
    }

    // --- decoding ----------------------------------------------------------

    private final ImageReader.OnImageAvailableListener onFrame = new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader source) {
            Image image = null;
            try {
                image = source.acquireLatestImage();
                if (image == null) return;
                if (troubled) {
                    troubled = false;
                    Log.i(Store.TAG, "camera frames again after a problem");
                    main.post(new Runnable() {
                        @Override public void run() {
                            listener.onCameraBack();
                        }
                    });
                }
                long now = System.currentTimeMillis();
                if (paused || now - lastDecodeAt < DECODE_INTERVAL_MS) return;
                lastDecodeAt = now;
                Result read = decode(image);
                if (read == null) return;
                String code = ProductCode.of(read.getBarcodeFormat(), read.getText());
                if (code == null) {
                    if (now - lastOtherAt < OTHER_CODE_COOLDOWN_MS) return;
                    lastOtherAt = now;
                    main.post(new Runnable() {
                        @Override public void run() {
                            listener.onOtherCode();
                        }
                    });
                    return;
                }
                // Measured 25.09. on 1 500 drawn frames: 3 invented numbers from one frame each.
                // Two agreeing frames cost about 0.1 s and leave a fluke nothing to repeat.
                // UPC-E is a short North American form; read off a German EAN-13 it invents a
                // number. 29.09.2026: a kefir bottle, 4104060031960, came back as UPC-E 11040634;
                // the shipped list holds six such 8-digit phantoms, e.g. 11051204 for a
                // 4105120… beer. So an unknown UPC-E is never asked about; the camera keeps
                // looking and finds the real code.
                if (read.getBarcodeFormat() == BarcodeFormat.UPC_E && !listener.isKnown(code)) return;
                // An unknown code asks for a third: a misread there gets learned, not just counted.
                agreeing = code.equals(candidate) && now - candidateAt < CONFIRM_WINDOW_MS ? agreeing + 1 : 1;
                candidate = code;
                candidateAt = now;
                if (agreeing < AGREE_KNOWN) return;
                if (agreeing < AGREE_UNKNOWN && !listener.isKnown(code)) return;
                if (code.equals(lastCode) && now - lastCodeAt < SAME_CODE_COOLDOWN_MS) return;
                lastCode = code;
                lastCodeAt = now;
                final String found = code;
                main.post(new Runnable() {
                    @Override public void run() {
                        listener.onCode(found);
                    }
                });
            } catch (IllegalStateException e) {
                Log.w(Store.TAG, "frame dropped", e);
            } finally {
                if (image != null) image.close();
            }
        }
    };

    /**
     * Tries the frame as it comes off the sensor and again turned by 90 degrees (Decoder.readFrame).
     * A bottle held upright in a portrait phone lands sideways in the sensor buffer, and
     * one-dimensional codes are only read along the buffer's own rows — without the second attempt
     * most real scans fail.
     */
    private Result decode(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int needed = rowStride * height;
        if (yBuffer == null || yBuffer.length < needed) yBuffer = new byte[needed];
        buffer.get(yBuffer, 0, Math.min(needed, buffer.remaining()));

        if (rotatedBuffer == null || rotatedBuffer.length < width * height) {
            rotatedBuffer = new byte[width * height];
        }
        return decoder.readFrame(yBuffer, rowStride, width, height, rotatedBuffer);
    }

    // --- TextureView -------------------------------------------------------

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        openCamera(width, height);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        configureTransform(width, height);
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    /**
     * The camera fills the whole view regardless of shape; without this the preview is stretched,
     * which also stretches the barcode and costs reads.
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (frameSize == null || viewWidth == 0 || viewHeight == 0) return;
        float bufferWidth = frameSize.getHeight();  // sensor is landscape, the screen is not
        float bufferHeight = frameSize.getWidth();
        float scale = Math.max(viewWidth / bufferWidth, viewHeight / bufferHeight);
        Matrix matrix = new Matrix();
        matrix.setScale((bufferWidth * scale) / viewWidth, (bufferHeight * scale) / viewHeight,
                viewWidth / 2f, viewHeight / 2f);
        final Matrix result = matrix;
        main.post(new Runnable() {
            @Override public void run() {
                view.setTransform(result);
            }
        });
    }
}
