package com.example.imageprocessingapp;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.view.PreviewView;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
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
    private Button buttonCopyText;
    private Button buttonSavePicture;

    private TireInfo latestTireInfo; // To store the latest results
    private Bitmap latestBitmapToSave; // To store the latest bitmap for saving


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
        buttonCopyText = findViewById(R.id.buttonCopyText);
        buttonSavePicture = findViewById(R.id.buttonSavePicture);


        buttonCopyText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTireInfoToClipboard();
            }
        });

        buttonSavePicture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (latestBitmapToSave != null && !latestBitmapToSave.isRecycled()) {
                    saveBitmapToGallery(latestBitmapToSave);
                } else {
                    Toast.makeText(MainActivity.this, "No image available to save", Toast.LENGTH_SHORT).show();
                }
            }
        });

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
    public void onResults(TireInfo tireInfo, Text ocrText, List<Barcode> barcodes, Bitmap sourceBitmap, int imageWidth, int imageHeight) {
        this.latestTireInfo = tireInfo;

        // Manage the bitmap for saving. Recycle previous one if it exists.
        if (this.latestBitmapToSave != null && !this.latestBitmapToSave.isRecycled()) {
            this.latestBitmapToSave.recycle();
        }
        this.latestBitmapToSave = sourceBitmap; // new bitmap from ImageProcessor

        runOnUiThread(() -> {
            // Update TextViews
            tvTireSize.setText("Tire Size: " + (tireInfo.detectedTireSize != null ? tireInfo.detectedTireSize : "N/A"));
            tvCountryOfOrigin.setText("Country: " + (tireInfo.detectedCountryOfOrigin != null ? tireInfo.detectedCountryOfOrigin : "N/A"));
            tvLoadSpeed.setText("Load/Speed: " + (tireInfo.detectedLoadIndexAndSpeedRating != null ? tireInfo.detectedLoadIndexAndSpeedRating : "N/A"));

            if (tireInfo.detectedBarcodes != null && !tireInfo.detectedBarcodes.isEmpty()) {
                BarcodeInfo firstBarcode = tireInfo.detectedBarcodes.get(0); // Displaying only the first barcode for simplicity
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
            this.latestTireInfo = null;
            if (this.latestBitmapToSave != null && !this.latestBitmapToSave.isRecycled()) {
                this.latestBitmapToSave.recycle();
            }
            this.latestBitmapToSave = null;
        });
    }

    private void copyTireInfoToClipboard() {
        if (latestTireInfo == null) {
            Toast.makeText(this, "No text available to copy", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Extracted Tire Information:\n");

        if (latestTireInfo.detectedTireSize != null && !latestTireInfo.detectedTireSize.isEmpty()) {
            sb.append("Tire Size: ").append(latestTireInfo.detectedTireSize).append("\n");
        }
        if (latestTireInfo.detectedLoadIndexAndSpeedRating != null && !latestTireInfo.detectedLoadIndexAndSpeedRating.isEmpty()) {
            sb.append("Load/Speed: ").append(latestTireInfo.detectedLoadIndexAndSpeedRating).append("\n");
        }
        if (latestTireInfo.detectedCountryOfOrigin != null && !latestTireInfo.detectedCountryOfOrigin.isEmpty()) {
            sb.append("Country of Origin: ").append(latestTireInfo.detectedCountryOfOrigin).append("\n");
        }
        if (latestTireInfo.detectedDotCode != null && !latestTireInfo.detectedDotCode.isEmpty()) {
            sb.append("DOT Code: ").append(latestTireInfo.detectedDotCode).append("\n");
        }

        if (latestTireInfo.detectedBarcodes != null && !latestTireInfo.detectedBarcodes.isEmpty()) {
            sb.append("Barcodes:\n");
            for (BarcodeInfo barcode : latestTireInfo.detectedBarcodes) {
                sb.append("  - Format: ").append(barcode.format)
                  .append(", Value: ").append(barcode.rawValue).append("\n");
            }
        }

        // Optionally add a snippet of raw OCR text if it's useful
        // if (latestTireInfo.rawOcrText != null && !latestTireInfo.rawOcrText.isEmpty()) {
        //     sb.append("\nRaw OCR Text Snippet:\n");
        //     sb.append(latestTireInfo.rawOcrText.substring(0, Math.min(latestTireInfo.rawOcrText.length(), 100))).append("...\n");
        // }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("TireInfo", sb.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to access clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveBitmapToGallery(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Toast.makeText(this, "Image is not available.", Toast.LENGTH_SHORT).show();
            return;
        }

        long timestamp = System.currentTimeMillis();
        String fileName = "TireImage_" + timestamp + ".jpg";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TireScannerApp");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (imageUri != null) {
            try (OutputStream outputStream = getContentResolver().openOutputStream(imageUri)) {
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
                    Toast.makeText(this, "Image saved to Gallery", Toast.LENGTH_SHORT).show();
                } else {
                    throw new IOException("ContentResolver returned null OutputStream");
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(imageUri, values, null, null);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to save bitmap: " + e.getMessage(), e);
                Toast.makeText(this, "Failed to save image: " + e.getMessage(), Toast.LENGTH_LONG).show();
                // Clean up entry in MediaStore if saving failed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // IS_PENDING was set
                    try {
                        getContentResolver().delete(imageUri, null, null);
                    } catch (Exception deleteEx) {
                        Log.e(TAG, "Failed to delete pending MediaStore entry: " + deleteEx.getMessage());
                    }
                }
            }
        } else {
            Toast.makeText(this, "Failed to create MediaStore entry.", Toast.LENGTH_LONG).show();
        }
    }
}
