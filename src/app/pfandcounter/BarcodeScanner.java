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
import android.os.SystemClock;
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
        /** One of the CAM_ reasons; the app shows its own text, in the phone's language. */
        void onProblem(int reason);
        /** Whether the camera just opened has a flash; main thread, after every open. */
        void onTorchKnown(boolean available);
        /** The first frame after a problem: the camera works again, whatever onProblem said. */
        void onCameraBack();
        /** Asked on the camera thread: a code the app already knows needs less proof. */
        boolean isKnown(String code);
    }

    /** Why the camera does not work; the raw text goes to the log only. */
    static final int CAM_NONE = 0, CAM_BUSY = 1, CAM_DISABLED = 2, CAM_PERMISSION = 3,
            CAM_PREVIEW = 4, CAM_ERROR = 5;

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

    /** Automatic reopens after a lost camera, refilled by a preview that ran for STABLE_MS. */
    private static final int REOPEN_BUDGET = 5;
    /** A preview lost sooner than this counts as part of the same failure. */
    private static final long STABLE_MS = 10000;
    /**
     * Reopens after another app took the camera; they do not spend REOPEN_BUDGET (split-screen
     * focus switches would use it up), but a device that keeps reporting itself taken stops here.
     */
    private static final int BUSY_BUDGET = 30;
    /** One timed retry after a loss, in case no availability report comes. */
    private static final long FALLBACK_MS = 3000;

    // Camera state below is touched on the main thread only: start/stop, the surface listener,
    // and every camera, session and availability callback run there. The background thread
    // only decodes frames. The main looper never quits, so a late onOpened always gets closed.
    private HandlerThread thread;
    private Handler background;
    private CameraManager manager;
    private CameraDevice camera;
    /** The open in flight; any other callback that turns up is stale and only closes its device. */
    private DeviceCallback opening;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private ImageReader imageReader;
    private Size frameSize;
    private String cameraId;
    private boolean torchOn;
    private boolean torchAvailable;
    private boolean started;
    /** The camera was taken away or failed; reopen once it is free again. */
    private boolean waiting;
    private int reopensLeft;
    private int busyReopensLeft;
    /** The CAM_ reason of the last loss or failed open, or -1. */
    private int lastReason = -1;
    /** When the current preview started (elapsedRealtime), or -1 while none runs. */
    private long configuredAt = -1;
    /** Bumped by every new or dropped session; a callback of an older one only cleans up. */
    private int sessionGen;
    /** The preview surface of the current session, released when it is replaced or closed. */
    private Surface previewSurface;
    /** Set during start(): the availability callback's first reports are ignored. */
    private boolean settling;
    /** Bumped by start() and stop(); only the settle runnable of the current start clears settling. */
    private int settleToken;

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

    /** Main thread. */
    void start() {
        if (started) return;
        started = true;
        waiting = false;
        reopensLeft = REOPEN_BUDGET;
        busyReopensLeft = BUSY_BUDGET;
        lastReason = -1;
        thread = new HandlerThread("camera");
        thread.start();
        background = new Handler(thread.getLooper());
        manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        view.setSurfaceTextureListener(this);
        // Registered first; its initial reports land on the main queue now and are ignored, so a
        // failed first open is not retried (and reported) a second time at once.
        settling = true;
        final int token = ++settleToken;
        if (manager != null) manager.registerAvailabilityCallback(availability, main);
        main.post(new Runnable() {
            @Override public void run() {
                if (token == settleToken) settling = false;
            }
        });
        if (view.isAvailable()) {
            openCamera(view.getWidth(), view.getHeight());
        }
    }

    /** Main thread. Safe to call twice, and while the camera is still opening. */
    void stop() {
        started = false;
        waiting = false;
        settleToken++;
        main.removeCallbacks(fallback);
        opening = null; // its onOpened will close the device
        if (manager != null) manager.unregisterAvailabilityCallback(availability);
        // A surface that turns ready after this must not open the camera in the background.
        view.setSurfaceTextureListener(null);
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

    /** Main thread. Does nothing when stopped, already open, or an open is in flight. */
    private void openCamera(int viewWidth, int viewHeight) {
        if (!started || camera != null || opening != null) return;
        if (manager == null) {
            problem(CAM_NONE, "no camera service");
            return;
        }
        try {
            cameraId = pickBackCamera(manager);
            if (cameraId == null) {
                problem(CAM_NONE, "no camera found");
                return;
            }
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            torchAvailable = flash != null && flash;
            final boolean torch = torchAvailable;
            main.post(new Runnable() {
                @Override public void run() {
                    if (started) listener.onTorchKnown(torch);
                }
            });
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                problem(CAM_ERROR, "camera has no usable configuration");
                return;
            }
            frameSize = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (imageReader != null) imageReader.close();
            imageReader = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(onFrame, background);
            configureTransform(viewWidth, viewHeight);
            DeviceCallback callback = new DeviceCallback();
            opening = callback;
            manager.openCamera(cameraId, callback, main);
        } catch (CameraAccessException e) {
            opening = null;
            waiting = true; // disabled or in use: try again once it is free
            lastReason = accessReason(e);
            Log.w(Store.TAG, "openCamera failed", e);
            problem(lastReason, "openCamera: reason " + e.getReason());
        } catch (SecurityException e) {
            opening = null;
            Log.w(Store.TAG, "openCamera failed", e);
            problem(CAM_PERMISSION, "openCamera: no permission");
        } catch (IllegalArgumentException e) {
            opening = null;
            Log.w(Store.TAG, "openCamera failed", e);
            problem(CAM_ERROR, "openCamera: bad argument");
        }
    }

    /**
     * Main thread: an automatic reopen after a lost camera, within the budget. Only an
     * availability report (`timed` false) may reopen for free after another app took the camera;
     * the timed fallback always spends REOPEN_BUDGET, so nothing loops on a timer.
     */
    private void reopen(boolean timed) {
        if (settling || !started || !waiting || camera != null || opening != null) return;
        // No surface yet: onSurfaceTextureAvailable opens the camera when it comes.
        if (!view.isAvailable()) return;
        boolean busy = lastReason == CAM_BUSY && !timed;
        if (busy ? busyReopensLeft <= 0 : reopensLeft <= 0) {
            // A spent timer leaves the wait to the availability callback, which may be free.
            if (timed) return;
            waiting = false;
            main.removeCallbacks(fallback);
            Log.w(Store.TAG, "camera reopen given up until the app comes back");
            return;
        }
        if (busy) {
            busyReopensLeft--;
        } else {
            reopensLeft--;
        }
        waiting = false;
        main.removeCallbacks(fallback);
        Log.i(Store.TAG, "camera free again, reopening");
        openCamera(view.getWidth(), view.getHeight());
    }

    private final Runnable fallback = new Runnable() {
        @Override public void run() {
            reopen(true);
        }
    };

    /** Main thread: the camera is gone or unusable; reopen once it is free (or after a while). */
    private void waitForCamera(int reason) {
        lastReason = reason;
        waiting = true;
        main.removeCallbacks(fallback);
        main.postDelayed(fallback, FALLBACK_MS);
    }

    /** Main thread: drops what belonged to a device that is gone, keeps the frame reader. */
    private void forgetDevice() {
        torchOn = false;
        sessionGen++; // a session still configuring for it only cleans up
        session = null; // closed with its device
        releasePreview();
        camera = null;
        request = null;
    }

    private void releasePreview() {
        if (previewSurface != null) previewSurface.release();
        previewSurface = null;
    }

    private final CameraManager.AvailabilityCallback availability = new CameraManager.AvailabilityCallback() {
        /** After MAX_CAMERAS_IN_USE another camera closing frees ours too, so any id counts then. */
        @Override public void onCameraAvailable(String id) {
            if (id.equals(cameraId) || lastReason == CAM_BUSY) reopen(false);
        }

        /** Focus moved between apps sharing the screen: now we may win the camera back. */
        @Override public void onCameraAccessPrioritiesChanged() {
            reopen(false);
        }
    };

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

    /** One per open, delivered on the main thread; only the current one may touch the state. */
    private final class DeviceCallback extends CameraDevice.StateCallback {
        @Override public void onOpened(CameraDevice device) {
            if (opening != this || !started) {
                device.close(); // stopped (or superseded) while it was opening
                return;
            }
            opening = null;
            camera = device;
            createSession();
        }

        @Override public void onDisconnected(CameraDevice device) {
            lost(device, CAM_BUSY, "disconnected");
        }

        @Override public void onError(CameraDevice device, int error) {
            lost(device, errorReason(error), "device error " + error);
        }

        /** Closing twice is harmless; only a current device reports and waits to come back. */
        private void lost(CameraDevice device, int reason, String detail) {
            device.close();
            boolean current = opening == this || camera == device;
            if (!current) return;
            if (opening == this) opening = null;
            if (camera == device) forgetDevice();
            // Only a preview that ran a while earns new reopens; one that dies right after it
            // starts would otherwise loop open, configure, lose for ever.
            if (configuredAt >= 0 && SystemClock.elapsedRealtime() - configuredAt > STABLE_MS) {
                reopensLeft = REOPEN_BUDGET;
                busyReopensLeft = BUSY_BUDGET;
            }
            configuredAt = -1;
            if (!started) return;
            Log.w(Store.TAG, "camera lost: " + detail);
            problem(reason, detail);
            // The availability callback reopens: when the other app lets go, or, after a fault
            // of our own, as soon as the close above has gone through. A timer backs it up once.
            waitForCamera(reason);
        }
    }

    private static int errorReason(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return CAM_BUSY;
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return CAM_DISABLED;
            default:
                return CAM_ERROR;
        }
    }

    private static int accessReason(CameraAccessException e) {
        switch (e.getReason()) {
            case CameraAccessException.CAMERA_IN_USE:
            case CameraAccessException.MAX_CAMERAS_IN_USE:
                return CAM_BUSY;
            case CameraAccessException.CAMERA_DISABLED:
                return CAM_DISABLED;
            default:
                return CAM_ERROR;
        }
    }

    /** Main thread. Needs an open camera and a ready surface; a new session replaces the old. */
    private void createSession() {
        SurfaceTexture texture = view.getSurfaceTexture();
        if (texture == null || camera == null || imageReader == null || background == null) return;
        texture.setDefaultBufferSize(frameSize.getWidth(), frameSize.getHeight());
        final Surface preview = new Surface(texture);
        final Surface frames = imageReader.getSurface();
        final CameraDevice device = camera;
        final int gen = ++sessionGen;
        // Setting up the streams blocks for a while: keep it off the main thread. stop() closes
        // the device first, so a late run here fails harmlessly or meets the checks below.
        background.post(new Runnable() {
            @Override public void run() {
                configureSession(device, preview, frames, gen);
            }
        });
    }

    /**
     * Main thread: the open camera got no session. Closing it lets the availability callback
     * (or the timer) reopen within the budget instead of leaving an open camera with no preview.
     */
    private void giveUpDevice(int reason, String detail) {
        CameraDevice d = camera;
        forgetDevice();
        configuredAt = -1;
        d.close();
        waitForCamera(reason);
        problem(reason, detail);
    }

    /** Main thread: frees the surface of a session that failed or was replaced before it ran. */
    private void dropped(Surface preview) {
        if (preview != previewSurface) preview.release();
    }

    /** Camera thread. The session callbacks come back on the main thread. */
    private void configureSession(final CameraDevice device, final Surface preview, Surface frames,
            final int gen) {
        try {
            final CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(preview);
            builder.addTarget(frames);
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device.createCaptureSession(Arrays.asList(preview, frames),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (gen != sessionGen || !started || camera != device) {
                                configured.close(); // stopped, lost or replaced meanwhile
                                dropped(preview);
                                return;
                            }
                            if (previewSurface != preview) releasePreview(); // the old session's
                            previewSurface = preview;
                            // A replacing session keeps the light as the button shows it.
                            if (torchOn) {
                                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                            }
                            request = builder;
                            session = configured;
                            configuredAt = SystemClock.elapsedRealtime();
                            main.removeCallbacks(fallback);
                            repeat();
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession configured) {
                            configured.close();
                            dropped(preview);
                            if (gen != sessionGen || !started || camera != device) return;
                            giveUpDevice(CAM_PREVIEW, "configure failed");
                        }
                    }, main);
        } catch (final CameraAccessException e) {
            Log.w(Store.TAG, "createSession failed", e);
            main.post(new Runnable() {
                @Override public void run() {
                    dropped(preview);
                    if (gen == sessionGen && started && camera == device) {
                        giveUpDevice(accessReason(e), "createSession: reason " + e.getReason());
                    }
                }
            });
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(Store.TAG, "createSession on a closed camera", e);
            main.post(new Runnable() {
                @Override public void run() {
                    dropped(preview);
                }
            });
        }
    }

    private void repeat() {
        if (session == null || request == null) return;
        try {
            session.setRepeatingRequest(request.build(), null, null);
        } catch (CameraAccessException e) {
            Log.w(Store.TAG, "setRepeatingRequest failed", e);
        } catch (IllegalStateException e) {
            Log.w(Store.TAG, "session already closed", e);
        }
    }

    /** Main thread. Idempotent. */
    private void closeCamera() {
        if (session != null) session.close();
        if (camera != null) camera.close();
        forgetDevice();
        configuredAt = -1;
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    /** For the test intent: the same path a real camera error takes. */
    void simulateProblem() {
        problem(CAM_ERROR, "test");
    }

    private void problem(final int reason, String detail) {
        Log.w(Store.TAG, "camera problem " + reason + ": " + detail);
        main.post(new Runnable() {
            @Override public void run() {
                listener.onProblem(reason);
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
        // stop() removes this listener; the check covers a call already under way.
        if (!started) return;
        if (camera != null) {
            createSession(); // a new surface for the camera that is already open
        } else {
            openCamera(width, height);
        }
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
