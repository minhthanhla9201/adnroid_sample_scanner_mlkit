package com.mtdragon92.mlkitscanner;

import android.Manifest;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.Image;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    // ===== CONST =====
    private static final int REQ_CAMERA = 1001;
    private static final long SCAN_DEBOUNCE_MS = 1200; // chống trùng

    // ===== UI =====
    private PreviewView previewView;
    private Button btnStart, btnEnd, btnFlash;
    private EditText edtResult;

    // ===== CAMERA =====
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private Camera camera;

    // ===== SCANNER =====
    private BarcodeScanner barcodeScanner;
    private boolean isProcessing = false;
    // Toi uu + chong trung
    private long scanStartTime = 0;
    private String lastScannedValue = "";
    private long lastScanTimestamp = 0;

    // ===== EFFECT =====
    // Beep + rung
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;

    // ===== FLASH =====
    private boolean isFlashEnabled = false; // ON / OFF do user chọn
    private boolean isTorchOn = false;      // trạng thái torch thực tế


    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        edtResult = findViewById(R.id.edtResult);
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // Check exist camera
        PackageManager pm = getPackageManager();
        boolean hasCamera = pm.hasSystemFeature(
                PackageManager.FEATURE_CAMERA_ANY
        );
        Log.d("CAMERA", "Has camera: " + hasCamera);

        previewView = findViewById(R.id.previewView);
        btnStart = findViewById(R.id.btnStart);
        btnEnd = findViewById(R.id.btnEnd);
        btnFlash = findViewById(R.id.btnFlash);

        cameraExecutor = Executors.newSingleThreadExecutor();

        initBarcodeScanner();

        btnStart.setOnClickListener(v -> checkCameraPermission());
        btnEnd.setOnClickListener(v -> stopCamera());
        btnFlash.setOnClickListener(v -> toggleFlashSetting());
        previewView.setOnTouchListener((v, event) -> {

            if (event.getAction() == MotionEvent.ACTION_UP) {
                focusOnPoint(event.getX(), event.getY());
            }
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
        barcodeScanner.close();
        cameraExecutor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            startCamera();

        } else {
            Toast.makeText(this,
                    "Camera permission denied",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQ_CAMERA
            );
        }
    }

    private void startCamera() {
        Log.d("CAMERA", "startCamera");

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build();

                analysis.setAnalyzer(
                        cameraExecutor,
                        this::processImage
                );

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                );

                // bật đèn nếu user chọn FLASH ON
                if (isFlashEnabled) {
                    toggleTorch(true);
                }

                Toast.makeText(this,
                        "Camera started",
                        Toast.LENGTH_SHORT).show();

            } catch (Exception e) {
                Log.e("CAMERA", "startCamera error", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        if (camera != null && isTorchOn) {
            toggleTorch(false);
        }

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            Toast.makeText(this,
                    "Camera stopped",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ================= SCAN =================

    private void initBarcodeScanner() {
        BarcodeScannerOptions options =
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                                Barcode.FORMAT_CODE_128,
                                Barcode.FORMAT_EAN_13,
                                Barcode.FORMAT_QR_CODE
                        )
                        .build();

        barcodeScanner = BarcodeScanning.getClient(options);
    }

    private void processImage(@NonNull ImageProxy imageProxy) {

        if (isProcessing) {
            imageProxy.close();
            return;
        }

        @SuppressLint("UnsafeOptInUsageError")
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        isProcessing = true;
        scanStartTime = System.currentTimeMillis();

        InputImage image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.getImageInfo().getRotationDegrees()
        );

        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String value = barcode.getRawValue();
                        if (value == null) continue;

                        long now = System.currentTimeMillis();

                        // ❌ chống scan trùng
                        if (value.equals(lastScannedValue)
                                && (now - lastScanTimestamp) < SCAN_DEBOUNCE_MS) {
                            return;
                        }

                        lastScannedValue = value;
                        lastScanTimestamp = now;

                        long scanTime = now - scanStartTime;

                        onBarcodeDetected(value, scanTime);
                        break;
                    }
                })
                .addOnCompleteListener(task -> {
                    isProcessing = false;
                    imageProxy.close();
                });
    }

    private void onBarcodeDetected(String value, long scanTime) {

        runOnUiThread(() -> {

            // Set vào textbox
            edtResult.setText(value);

            // Beep
            toneGenerator.startTone(
                    ToneGenerator.TONE_PROP_BEEP,
                    150
            );

            // Rung
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(
                        VibrationEffect.createOneShot(
                                80,
                                VibrationEffect.DEFAULT_AMPLITUDE
                        )
                );
            }

            // Log thời gian scan
            Log.d("SCAN",
                    "Value=" + value + " | Time=" + scanTime + " ms");

            Toast.makeText(
                    this,
                    "Scan OK (" + scanTime + " ms)",
                    Toast.LENGTH_SHORT
            ).show();
        });
    }

    /**
     * Focus when touch
     */
    private void focusOnPoint(float x, float y) {

        if (camera == null) return;

        MeteringPointFactory factory =
                previewView.getMeteringPointFactory();

        MeteringPoint point = factory.createPoint(x, y);

        FocusMeteringAction action =
                new FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF
                )
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build();

        CameraControl control = camera.getCameraControl();
        control.startFocusAndMetering(action);

        Log.d("FOCUS", "Tap to focus at x=" + x + ", y=" + y);
    }

    private void toggleFlashSetting() {

        isFlashEnabled = !isFlashEnabled;

        if (isFlashEnabled) {
            btnFlash.setText("FLASH ON");
            btnFlash.setBackgroundColor(0xFF4CAF50); // xanh
        } else {
            btnFlash.setText("FLASH OFF");
            btnFlash.setBackgroundColor(0xFF9E9E9E); // xám
        }

        // Nếu camera đang mở → bật/tắt ngay
        if (camera != null) {
            toggleTorch(isFlashEnabled);
        }
    }

    private void toggleTorch(boolean enable) {

        if (camera == null) return;

        CameraInfo cameraInfo = camera.getCameraInfo();
        CameraControl cameraControl = camera.getCameraControl();

        if (cameraInfo.hasFlashUnit()) {
            cameraControl.enableTorch(enable);
            isTorchOn = enable;
        }
    }
}