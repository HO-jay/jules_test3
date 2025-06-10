package com.example.imageprocessingapp;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.view.PreviewView;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.barcode.common.Barcode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements ImageProcessor.ImageProcessingListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"};

    private PreviewView viewFinder;
    private GraphicOverlay graphicOverlay;
    private ExecutorService cameraExecutor;
    private ImageProcessor imageProcessor;

    // TextViews for displaying results
    private TextView tvTireSize;
    private TextView tvCountryOfOrigin;
    private TextView tvLoadSpeed;
    private TextView tvBarcodeValue;
    private TextView tvBarcodeFormat;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        graphicOverlay = findViewById(R.id.graphicOverlay);

        // Initialize TextViews
        tvTireSize = findViewById(R.id.tvTireSize);
        tvCountryOfOrigin = findViewById(R.id.tvCountryOfOrigin);
        tvLoadSpeed = findViewById(R.id.tvLoadSpeed);
        tvBarcodeValue = findViewById(R.id.tvBarcodeValue);
        tvBarcodeFormat = findViewById(R.id.tvBarcodeFormat);


        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        imageProcessor = new ImageProcessor();
        imageProcessor.setImageProcessingListener(this); // Set MainActivity as listener
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // ImageAnalysis use case
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        //.setTargetResolution(new Size(640, 480)) // Adjust as needed
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    // Pass the ImageProxy to the ImageProcessor for processing.
                    // Results will be delivered via the ImageProcessingListener interface.
                    Mat processedMatResult = imageProcessor.processImage(imageProxy);
                    // The processedMatResult can be used for debugging or other purposes if needed,
                    // but the primary data flow for UI is now through the listener.
                    if (processedMatResult != null) {
                        // For example, log its properties or display it if there's an ImageView for debugging
                        // Log.d(TAG, "processImage returned Mat: " + processedMatResult.cols() + "x" + processedMatResult.rows());
                        processedMatResult.release(); // Release the clone if not used further
                    }
                    imageProxy.close(); // Make sure to close the ImageProxy
                });


                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalysis); // Bind imageAnalysis

            } catch (Exception exc) {
                Log.e(TAG, "Use case binding failed", exc);
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }


    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (imageProcessor != null) {
            imageProcessor.cleanup();
        }
    }

    // Implementation of ImageProcessor.ImageProcessingListener
    @Override
    public void onResults(TireInfo tireInfo, Text ocrText, List<Barcode> barcodes, int imageWidth, int imageHeight) {
        runOnUiThread(() -> {
            // Update TextViews
            tvTireSize.setText("Tire Size: " + (tireInfo.detectedTireSize != null ? tireInfo.detectedTireSize : "N/A"));
            tvCountryOfOrigin.setText("Country: " + (tireInfo.detectedCountryOfOrigin != null ? tireInfo.detectedCountryOfOrigin : "N/A"));
            tvLoadSpeed.setText("Load/Speed: " + (tireInfo.detectedLoadIndexAndSpeedRating != null ? tireInfo.detectedLoadIndexAndSpeedRating : "N/A"));

            if (tireInfo.detectedBarcodes != null && !tireInfo.detectedBarcodes.isEmpty()) {
                BarcodeInfo firstBarcode = tireInfo.detectedBarcodes.get(0);
                tvBarcodeValue.setText("Barcode: " + firstBarcode.rawValue);
                tvBarcodeFormat.setText("Barcode Format: " + firstBarcode.format);
            } else {
                tvBarcodeValue.setText("Barcode: N/A");
                tvBarcodeFormat.setText("Barcode Format: N/A");
            }

            // Update GraphicOverlay
            graphicOverlay.clear();
            if (imageWidth > 0 && imageHeight > 0) {
                 graphicOverlay.setImageSourceInfo(imageWidth, imageHeight);
            }


            List<GraphicOverlay.Graphic> graphics = new ArrayList<>();
            if (ocrText != null) {
                for (Text.TextBlock block : ocrText.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        // To draw lines:
                        // graphics.add(new TextGraphic(graphicOverlay, line));
                        for (Text.Element element : line.getElements()) {
                           graphics.add(new TextGraphic(graphicOverlay, element));
                        }
                    }
                }
            }
            if (barcodes != null) {
                for (Barcode barcode : barcodes) {
                    graphics.add(new BarcodeGraphic(graphicOverlay, barcode));
                }
            }
            graphicOverlay.addAll(graphics);
            graphicOverlay.invalidate(); // Request redraw
        });
    }

    @Override
    public void onError(String errorMessage) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Processing Error: " + errorMessage, Toast.LENGTH_LONG).show();
            Log.e(TAG, "ImageProcessingListener Error: " + errorMessage);
            // Optionally clear overlays or reset text views on error
            graphicOverlay.clear();
            tvTireSize.setText("Tire Size: Error");
            tvCountryOfOrigin.setText("Country: Error");
            tvLoadSpeed.setText("Load/Speed: Error");
            tvBarcodeValue.setText("Barcode: Error");
            tvBarcodeFormat.setText("Barcode Format: Error");
        });
    }
}
