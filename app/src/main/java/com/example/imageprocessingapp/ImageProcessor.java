package com.example.imageprocessingapp;

import androidx.camera.core.ImageProxy;
package com.example.imageprocessingapp;

import androidx.camera.core.ImageProxy;
import android.graphics.ImageFormat;
import android.util.Log;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import java.nio.ByteBuffer;

public class ImageProcessor {
    private static final String TAG = "ImageProcessor";

    // Reusable Mats to avoid reallocation in every frame
    private Mat yuvMat; // Full YUV image from camera
    private Mat grayMat; // Grayscale version of the image
    private Mat thresholdMat; // Binary image after adaptive threshold
    private Mat denoisedMat; // Image after noise reduction (on thresholdMat)
    private Mat dewarpedGrayMat; // Dewarped grayscale image
    private Mat hierarchy; // Used by findContours

    // No need for separate yMat, uvMat if yuvMat is properly constructed and parts are accessed via submat or direct copy.

    static {
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed!");
        } else {
            Log.d(TAG, "OpenCV initialized successfully!");
        }
    }

    public ImageProcessor() {
import org.opencv.core.Core;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
// import org.opencv.imgcodecs.Imgcodecs; // For debugging: save intermediate images
import org.opencv.android.Utils; // For Mat to Bitmap conversion
import android.graphics.Bitmap;
import android.graphics.Rect;
// ML Kit Vision
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ImageProcessor {
    private static final String TAG = "ImageProcessor";

    // Reusable Mats to avoid reallocation in every frame
    private Mat yuvMat; // Full YUV image from camera
    private Mat grayMat; // Grayscale version of the image
    private Mat thresholdMat; // Binary image after adaptive threshold
    private Mat denoisedMat; // Image after noise reduction (on thresholdMat)
    private Mat dewarpedGrayMat; // Dewarped grayscale image
    private Mat hierarchy; // Used by findContours
    private TextRecognizer textRecognizer;
    private BarcodeScanner barcodeScanner;
    private TireDataParser tireDataParser;
    private ImageProcessingListener imageProcessingListener;

    // Interface for callbacks to MainActivity
    public interface ImageProcessingListener {
        // Pass raw ML Kit results for MainActivity to create specific graphics
        void onResults(TireInfo tireInfo, Text ocrText, List<Barcode> barcodes, int imageWidth, int imageHeight);
        void onError(String errorMessage);
    }

    public void setImageProcessingListener(ImageProcessingListener listener) {
        this.imageProcessingListener = listener;
    }

    // Temporary storage for results to attempt combined parsing
    private Text latestOcrResult = null;
    private List<Barcode> latestBarcodeResults = null;
    private long lastFrameTimestamp = -1; // To crudely sync results
    private int currentFrameImageWidth = 0; // Store dimensions for GraphicOverlay
    private int currentFrameImageHeight = 0;


    // No need for separate yMat, uvMat if yuvMat is properly constructed and parts are accessed via submat or direct copy.

    static {
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed!");
        } else {
            Log.d(TAG, "OpenCV initialized successfully!");
        }
    }

    public ImageProcessor() {
        hierarchy = new Mat();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Log.d(TAG, "TextRecognizer initialized.");
        barcodeScanner = BarcodeScanning.getClient();
        Log.d(TAG, "BarcodeScanner initialized.");
        tireDataParser = new TireDataParser();
        Log.d(TAG, "TireDataParser initialized.");
    }

    // Modified to return the dewarped Mat
    public Mat processImage(ImageProxy imageProxy) {
        if (imageProxy == null || imageProxy.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid ImageProxy format or null ImageProxy. Required YUV_420_888.");
            if (imageProxy != null) imageProxy.close();
            return null; // Return null if input is invalid
        }

        long startTime = System.currentTimeMillis();

        // STEP 1: Convert ImageProxy to YUV Mat (NV21 format)
        final long currentFrameTimestamp = imageProxy.getImageInfo().getTimestamp();
        currentFrameImageWidth = imageProxy.getWidth(); // Store for GraphicOverlay
        currentFrameImageHeight = imageProxy.getHeight(); // Store for GraphicOverlay


        Mat originalYuvMat = imageProxyToYuvMat(imageProxy); // This uses and modifies class member yuvMat
        if (yuvMat == null || yuvMat.empty()) { // Check class member yuvMat
            Log.e(TAG, "Failed to convert ImageProxy to YUV Mat.");
            if (imageProcessingListener != null) {
                imageProcessingListener.onError("Failed to convert ImageProxy to YUV Mat.");
            }
            if (imageProxy != null) imageProxy.close();
            return null;
        }

        // Initialize or reinitialize Mats if needed (ensureMatAllocation uses these stored dimensions implicitly now)
        ensureMatAllocation(currentFrameImageWidth, currentFrameImageHeight);

        // STEP 2: Grayscaling (Extract Y plane from NV21 Mat)
        Mat tempGrayMat = yuvMat.submat(0, frameHeight, 0, frameWidth); // Use class member yuvMat
        tempGrayMat.copyTo(grayMat); // Now grayMat has its own data.
        Log.d(TAG, "Grayscaling applied.");

        // originalYuvMat (which was yuvMat) is a class member, no need to release here if reused.
        // If yuvMat is not directly returned by imageProxyToYuvMat but a new Mat, then it should be released.
        // Current imageProxyToYuvMat returns the class member yuvMat.

        // --- BARCODE SCANNING ---
        scanBarcodesFromMat(grayMat.clone(), currentFrameTimestamp); // Use a clone for safety

        // --- TEXT PROCESSING PIPELINE CONTINUES ---
        // STEP 3: Adaptive Thresholding (on grayMat)
        Imgproc.adaptiveThreshold(grayMat, thresholdMat, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 2); // INV for black text on white bg
        Log.d(TAG, "Adaptive Thresholding applied.");

        // STEP 4: Noise Reduction (on thresholdMat)
        Imgproc.medianBlur(thresholdMat, denoisedMat, 3);
        Log.d(TAG, "Noise Reduction applied.");

        // STEP 5: Contour Detection & Dewarping (on denoisedMat, apply to grayMat)
        try {
            dewarpTextRegions(denoisedMat, grayMat); // Modifies dewarpedGrayMat internally
        } catch (Exception e) {
            Log.e(TAG, "Error during dewarping: " + e.getMessage(), e);
            // Fallback: if dewarping fails, copy grayMat to dewarpedGrayMat to return something
            if (dewarpedGrayMat == null || dewarpedGrayMat.empty()) {
                 if (grayMat != null && !grayMat.empty()) grayMat.copyTo(dewarpedGrayMat);
            }
        }


        long endTime = System.currentTimeMillis();
        Log.d(TAG, "Total processing time: " + (endTime - startTime) + " ms.");
        if (dewarpedGrayMat != null && !dewarpedGrayMat.empty()) {
            Log.d(TAG, "Dewarped Mat: " + dewarpedGrayMat.cols() + "x" + dewarpedGrayMat.rows());
            // After processing, call text recognition
            recognizeTextFromMat(dewarpedGrayMat.clone()); // Pass a clone to avoid issues if mat is released
        } else {
            Log.w(TAG, "Dewarped Mat is null or empty, skipping text recognition.");
             // If dewarping failed and grayMat was not copied, return grayMat as a last resort
            if (grayMat != null && !grayMat.empty()) {
                 recognizeTextFromMat(grayMat.clone()); // Try OCR on original gray if dewarp failed
                 return grayMat.clone();
            }
            return null;
        }

        return dewarpedGrayMat.clone(); // Return a clone for safety
    }

    // New method for ML Kit Text Recognition
    public void recognizeTextFromMat(Mat inputMat) {
        if (inputMat == null || inputMat.empty()) {
            Log.e(TAG, "Input Mat for text recognition is null or empty.");
            return;
        }
        if (inputMat.type() != CvType.CV_8UC1 && inputMat.type() != CvType.CV_8UC3 && inputMat.type() != CvType.CV_8UC4) {
            Log.e(TAG, "Input Mat type not suitable for Bitmap conversion: " + inputMat.type());
            // Optionally convert to a supported type, e.g., CV_8UC1 to ARGB for bitmap
            // For now, just return.
            return;
        }

        // Convert Mat to Bitmap
        Bitmap bitmap = Bitmap.createBitmap(inputMat.cols(), inputMat.rows(), Bitmap.Config.ARGB_8888);
        if (inputMat.type() == CvType.CV_8UC1) { // Grayscale
            Mat tempMatForBitmap = new Mat();
            Imgproc.cvtColor(inputMat, tempMatForBitmap, Imgproc.COLOR_GRAY2RGBA); // ML Kit prefers ARGB
            Utils.matToBitmap(tempMatForBitmap, bitmap);
            tempMatForBitmap.release();
        } else if (inputMat.type() == CvType.CV_8UC3) { // BGR (OpenCV default for color)
             Mat tempMatForBitmap = new Mat();
             Imgproc.cvtColor(inputMat, tempMatForBitmap, Imgproc.COLOR_BGR2RGBA);
             Utils.matToBitmap(tempMatForBitmap, bitmap);
             tempMatForBitmap.release();
        }
        else { // Assuming CV_8UC4 (RGBA)
            Utils.matToBitmap(inputMat, bitmap);
        }


        if (bitmap == null) {
            Log.e(TAG, "Failed to convert Mat to Bitmap for text recognition.");
            return;
        }

        // Create InputImage from Bitmap (assuming 0 rotation for now)
        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

        Log.d(TAG, "Starting text recognition process.");
        textRecognizer.process(inputImage)
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text visionText) {
                        Log.d(TAG, "Text recognition successful for frame: " + currentFrameTimestamp);
                        logRecognizedText(visionText);

                        // No longer creating graphics here. MainActivity will do it.
                        // List<GraphicOverlay.Graphic> graphics = new ArrayList<>();
                        // for (Text.TextBlock block : visionText.getTextBlocks()) {
                        //     for (Text.Line line : block.getLines()) {
                        //         for (Text.Element element : line.getElements()) {
                        //              graphics.add(new TextGraphic(null, element));
                        //         }
                        //     }
                        // }

                        synchronized (ImageProcessor.this) {
                            latestOcrResult = visionText;
                            if (lastFrameTimestamp != currentFrameTimestamp) {
                                latestBarcodeResults = null;
                            }
                            lastFrameTimestamp = currentFrameTimestamp;
                            if (imageProcessingListener != null) {
                                TireInfo tireInfo = tireDataParser.parse(latestOcrResult, latestBarcodeResults);
                                Log.i(TAG, "Parsed TireInfo (from text recognition callback): " + tireInfo.toString());
                                imageProcessingListener.onResults(tireInfo, latestOcrResult, latestBarcodeResults, currentFrameImageWidth, currentFrameImageHeight);
                            }
                        }
                        if (!bitmap.isRecycled()) { // Should be bitmapForTextRecognition
                            bitmap.recycle();
                        }
                    }
                })
                .addOnFailureListener(
                        new OnFailureListener() {
                            @Override
                            public void onFailure(@NonNull Exception e) {
                                Log.e(TAG, "Text recognition failed for frame: " + currentFrameTimestamp, e);
                                synchronized (ImageProcessor.this) {
                                    latestOcrResult = null;
                                    if (imageProcessingListener != null) {
                                        if (lastFrameTimestamp == currentFrameTimestamp && latestBarcodeResults != null) {
                                            TireInfo tireInfo = tireDataParser.parse(null, latestBarcodeResults);
                                            imageProcessingListener.onResults(tireInfo, null, latestBarcodeResults, currentFrameImageWidth, currentFrameImageHeight);
                                        } else if (lastFrameTimestamp != currentFrameTimestamp) { // If no data for current frame at all
                                            imageProcessingListener.onError("Text recognition failed and no other data for frame: " + e.getMessage());
                                        } else { // If there was no barcode data either for this frame.
                                             imageProcessingListener.onResults(tireDataParser.parse(null, null), null, null, currentFrameImageWidth, currentFrameImageHeight);
                                        }
                                    }
                                }
                                if (!bitmap.isRecycled()) { // Should be bitmapForTextRecognition
                                    bitmap.recycle();
                                }
                            }
                        });
        // inputMat is a clone, release it
        if (inputMat != null) {
            inputMat.release();
        }
    }

    private void logRecognizedText(Text visionText) { // This is the original detailed logger
        String resultText = visionText.getText();
        Log.i(TAG, "Recognized Text (Full): \n" + resultText);

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            String blockText = block.getText();
            Point[] blockCornerPoints = block.getCornerPoints();
            Rect blockFrame = block.getBoundingBox();
            Log.d(TAG, "TextBlock: '" + blockText + "'");
            if (blockFrame != null) {
                 Log.d(TAG, "  BoundingBox: Left=" + blockFrame.left + ", Top=" + blockFrame.top +
                               ", Right=" + blockFrame.right + ", Bottom=" + blockFrame.bottom);
            }
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText();
                Point[] lineCornerPoints = line.getCornerPoints();
                Rect lineFrame = line.getBoundingBox();
                Log.d(TAG, "  Line: '" + lineText + "'");
                for (Text.Element element : line.getElements()) {
                    String elementText = element.getText();
                    Point[] elementCornerPoints = element.getCornerPoints();
                    Rect elementFrame = element.getBoundingBox();
                    Log.d(TAG, "    Element: '" + elementText + "'");
                }
            }
        }
    }


    private void ensureMatAllocation(int width, int height) {
        if (grayMat == null || grayMat.width() != width || grayMat.height() != height) {
            if (grayMat != null) grayMat.release();
            grayMat = new Mat(height, width, CvType.CV_8UC1);
        }
        if (thresholdMat == null || thresholdMat.width() != width || thresholdMat.height() != height) {
            if (thresholdMat != null) thresholdMat.release();
            thresholdMat = new Mat(height, width, CvType.CV_8UC1);
        }
        if (denoisedMat == null || denoisedMat.width() != width || denoisedMat.height() != height) {
            if (denoisedMat != null) denoisedMat.release();
            denoisedMat = new Mat(height, width, CvType.CV_8UC1);
        }
        if (dewarpedGrayMat == null || dewarpedGrayMat.width() != width || dewarpedGrayMat.height() != height) {
            // Dewarped size might be different, but initialize with original size for now
            if (dewarpedGrayMat != null) dewarpedGrayMat.release();
            dewarpedGrayMat = new Mat(height, width, CvType.CV_8UC1);
        }
         if (hierarchy == null) { // hierarchy is used by findContours
            hierarchy = new Mat();
        } else {
             hierarchy.release(); // Release and re-create to ensure it's empty for new contours
             hierarchy = new Mat();
         }
    }


    private void dewarpTextRegions(Mat processedBinaryMat, Mat sourceGrayMat) {
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(processedBinaryMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        if (contours.isEmpty()) {
            Log.w(TAG, "No contours found for dewarping.");
            sourceGrayMat.copyTo(dewarpedGrayMat); // Default to original gray image
            return;
        }

        // Filter and sort contours (e.g., by area, aspect ratio, position)
        // This is a placeholder for more sophisticated text line grouping.
        // For now, let's find the largest contour by area, assuming it's a significant text block.
        MatOfPoint largestContour = Collections.max(contours, Comparator.comparingDouble(Imgproc::contourArea));
        contours.clear(); // Release memory from other contours if not needed

        if (Imgproc.contourArea(largestContour) < 100) { // Minimum area threshold
            Log.w(TAG, "Largest contour is too small.");
            sourceGrayMat.copyTo(dewarpedGrayMat);
            largestContour.release();
            return;
        }

        // Get bounding box of the largest contour
        Rect boundingBox = Imgproc.boundingRect(largestContour);

        // Define source points for perspective transform (corners of the bounding box)
        MatOfPoint2f srcPoints = new MatOfPoint2f(
                new Point(boundingBox.x, boundingBox.y),
                new Point(boundingBox.x + boundingBox.width, boundingBox.y),
                new Point(boundingBox.x, boundingBox.y + boundingBox.height),
                new Point(boundingBox.x + boundingBox.width, boundingBox.y + boundingBox.height)
        );

        // Define destination points (a rectangle of the same size, or a predefined aspect ratio)
        // This will "straighten" the bounding box, not a true cylindrical unwrap.
        // For a more cylindrical effect, source points would need to follow the curve.
        float desiredWidth = boundingBox.width;
        float desiredHeight = boundingBox.height;

        // Simple check: if the contour is very wide and not very tall, it might be a candidate line
        // This heuristic is very basic.
        if (boundingBox.width > boundingBox.height * 2) { // Arbitrary aspect ratio for a "line"
             // Try to estimate a slight curve for a more "unrolling" effect for a single line
            // This is highly speculative and needs actual curve fitting
            Point midTop = new Point(boundingBox.x + boundingBox.width / 2.0, boundingBox.y);
            Point midBottom = new Point(boundingBox.x + boundingBox.width / 2.0, boundingBox.y + boundingBox.height);

            // Let's try to "pull" the horizontal center points outwards slightly if applying to a curved line
            // This is not a robust cylindrical model, just a geometric distortion attempt
            // For a simple straightening of the bounding box, the original srcPoints are fine.
            // To attempt a slight "unroll" for a single dominant line:
            // One might try to get points along the center of the detected line of text
            // and map them to a straight line.
            // For this example, I'm sticking to straightening the bounding box as a first step.
        }


        MatOfPoint2f dstPoints = new MatOfPoint2f(
                new Point(0, 0),
                new Point(desiredWidth - 1, 0),
                new Point(0, desiredHeight - 1),
                new Point(desiredWidth - 1, desiredHeight - 1)
        );

        // Get the perspective transformation matrix
        Mat perspectiveMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);

        if (perspectiveMatrix.empty()) {
            Log.e(TAG, "Failed to get perspective transform matrix.");
            sourceGrayMat.copyTo(dewarpedGrayMat);
            srcPoints.release();
            dstPoints.release();
            largestContour.release();
            return;
        }

        // Apply the perspective transformation to the source grayscale image
        // The output size for warpPerspective will be based on desiredWidth, desiredHeight
        Size dewarpedSize = new Size(desiredWidth, desiredHeight);
        if (dewarpedGrayMat.width() != dewarpedSize.width || dewarpedGrayMat.height() != dewarpedSize.height) {
            if(dewarpedGrayMat != null) dewarpedGrayMat.release();
            dewarpedGrayMat = new Mat((int)dewarpedSize.height, (int)dewarpedSize.width, CvType.CV_8UC1);
        }

        Imgproc.warpPerspective(sourceGrayMat, dewarpedGrayMat, perspectiveMatrix, dewarpedSize, Imgproc.INTER_LINEAR);
        Log.d(TAG, "Perspective transformation applied to straighten the largest contour's bounding box.");

        // Release Mats
        srcPoints.release();
        dstPoints.release();
        perspectiveMatrix.release();
        largestContour.release();
    }


    private Mat imageProxyToYuvMat(ImageProxy image) {
        // This method converts YUV_420_888 ImageProxy to a Mat in NV21 format
        // (Y plane followed by interleaved VU plane).
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer yBuffer = planes[0].getBuffer();
        int ySize = yBuffer.remaining();

        // Create or resize the combined YUV Mat (NV21 format: Y plane + VU interleaved plane)
        if (yuvMat == null || yuvMat.cols() != width || yuvMat.rows() != (height + height / 2)) {
            if (yuvMat != null) yuvMat.release();
            yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
        }

        // Copy Y data to the top part of yuvMat
        yuvMat.put(0, 0, getBytesFromBuffer(yBuffer, planes[0].getRowStride(), width, height, ySize, true));

        // UV Plane data
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        byte[] vuData = new byte[width * height / 2]; // VU plane size for NV21
        int vuIndex = 0;

        // Get all bytes from U and V buffers (potentially including padding)
        byte[] uBytesWithPadding = new byte[uBuffer.capacity()];
        uBuffer.get(uBytesWithPadding);
        uBuffer.rewind();
        byte[] vBytesWithPadding = new byte[vBuffer.capacity()];
        vBuffer.get(vBytesWithPadding);
        vBuffer.rewind();

        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();

        // Interleave V and U data: V first, then U for NV21.
        // Iterate for UV plane (height/2 x width/2 pixels, each pixel is V then U)
        for (int r = 0; r < height / 2; r++) {
            for (int c = 0; c < width / 2; c++) {
                int vAddr = r * vRowStride + c * vPixelStride;
                int uAddr = r * uRowStride + c * uPixelStride;

                if (vAddr < vBytesWithPadding.length) {
                    vuData[vuIndex++] = vBytesWithPadding[vAddr];
                } else {
                    Log.e(TAG, "vAddr out of bounds: " + vAddr); break;
                }
                if (uAddr < uBytesWithPadding.length) {
                     vuData[vuIndex++] = uBytesWithPadding[uAddr];
                } else {
                    Log.e(TAG, "uAddr out of bounds: " + uAddr); break;
                }
                if (vuIndex >= vuData.length) break;
            }
            if (vuIndex >= vuData.length) break;
        }
        yuvMat.put(height, 0, vuData);
        return yuvMat; // Return the class member yuvMat
    }


    private byte[] getBytesFromBuffer(ByteBuffer buffer, int rowStride, int width, int height, int bufferSize, boolean rewind) {
        if (rewind) buffer.rewind();
        byte[] data;
        if (buffer.hasArray() && buffer.arrayOffset() == 0 && rowStride == width && buffer.array().length == bufferSize) {
            // If buffer has a backing array, is not offset, no padding, and array size matches buffer size
            data = buffer.array();
        } else {
            data = new byte[bufferSize];
            buffer.get(data); // Copy data from buffer
            if (rowStride != width) { // Handle row stride if padding exists
                byte[] tightData = new byte[width * height];
                int srcOffset = 0;
                int dstOffset = 0;
                for (int i = 0; i < height; i++) {
                    if (srcOffset + width <= data.length && dstOffset + width <= tightData.length) {
                        System.arraycopy(data, srcOffset, tightData, dstOffset, width);
                    } else {
                        Log.e(TAG, "Buffer copy error due to stride handling mismatch.");
                        break; // Avoid ArrayOutOfBoundsException
                    }
                    srcOffset += rowStride;
                    dstOffset += width;
                }
                return tightData;
            }
        }
        return data;
    }


    public void cleanup() {
        // Release all OpenCV Mats
        if (yuvMat != null) { yuvMat.release(); yuvMat = null; }
        if (grayMat != null) { grayMat.release(); grayMat = null; }
        if (thresholdMat != null) { thresholdMat.release(); thresholdMat = null; }
        if (denoisedMat != null) { denoisedMat.release(); denoisedMat = null; }
        if (dewarpedGrayMat != null) { dewarpedGrayMat.release(); dewarpedGrayMat = null; }
        if (hierarchy != null) { hierarchy.release(); hierarchy = null; }
        // ML Kit recognizers using GMS do not need explicit close generally.
        // if (textRecognizer != null) { textRecognizer.close(); }
        // if (barcodeScanner != null) { barcodeScanner.close(); }
        Log.d(TAG, "ImageProcessor cleaned up. OpenCV Mats released.");
    }

    private void scanBarcodesFromMat(Mat inputMat, final long currentFrameTimestamp) {
        if (inputMat == null || inputMat.empty()) {
            Log.e(TAG, "Input Mat for barcode scanning is null or empty for frame: " + currentFrameTimestamp);
            // Attempt to parse with OCR data if it exists for this frame
            synchronized (ImageProcessor.this) {
                if (lastFrameTimestamp == currentFrameTimestamp && latestOcrResult != null) {
                    TireInfo tireInfo = tireDataParser.parse(latestOcrResult, null);
                    Log.i(TAG, "Parsed TireInfo (barcode scan input empty, from barcode scan method): " + tireInfo.toString());
                }
            }
            return;
        }
        // Ensure Mat is CV_8UC1 (grayscale) or convert it appropriately for Bitmap
        if (inputMat.type() != CvType.CV_8UC1) { // This check is important
            Log.w(TAG, "Barcode scanning expected CV_8UC1 Mat, got " + inputMat.type() + ". Attempting conversion for frame: " + currentFrameTimestamp);
            Mat convertedMat = new Mat();
            if (inputMat.type() == CvType.CV_8UC3 || inputMat.type() == CvType.CV_8UC4) {
                 Imgproc.cvtColor(inputMat, convertedMat, Imgproc.COLOR_BGR2GRAY); // or COLOR_RGBA2GRAY
            } else {
                Log.e(TAG, "Unsupported Mat type for barcode conversion: " + inputMat.type());
                inputMat.release(); // Release the clone
                return;
            }
            inputMat.release(); // Release the original clone
            inputMat = convertedMat; // Use the new converted mat
        }

        Bitmap bitmapForBarcode = Bitmap.createBitmap(inputMat.cols(), inputMat.rows(), Bitmap.Config.ARGB_8888);
        Mat tempRgbaMat = new Mat(); // Temporary Mat for RGBA conversion
        Imgproc.cvtColor(inputMat, tempRgbaMat, Imgproc.COLOR_GRAY2RGBA); // ML Kit prefers ARGB for Bitmaps
        Utils.matToBitmap(tempRgbaMat, bitmapForBarcode);
        tempRgbaMat.release(); // Release the temporary RGBA Mat
        inputMat.release(); // Release the cloned Mat passed to this method (inputMat)

        if (bitmapForBarcode == null) {
            Log.e(TAG, "Failed to convert Mat to Bitmap for barcode scanning for frame: " + currentFrameTimestamp);
            return;
        }

        InputImage imageForBarcode = InputImage.fromBitmap(bitmapForBarcode, 0); // Assuming 0 rotation

        Log.d(TAG, "Starting barcode scanning process for frame: " + currentFrameTimestamp);
        barcodeScanner.process(imageForBarcode)
                .addOnSuccessListener(barcodes -> {
                    Log.d(TAG, "Barcode scanning successful for frame: " + currentFrameTimestamp + ". Found " + barcodes.size() + " barcode(s).");
                    // No longer creating graphics here
                    // List<GraphicOverlay.Graphic> graphics = new ArrayList<>();
                    // if (!barcodes.isEmpty()) {
                    //     for (Barcode barcode : barcodes) {
                    //         Log.i(TAG, "Barcode Raw Value: " + barcode.getRawValue() + " (Frame: " + currentFrameTimestamp + ")");
                    //         graphics.add(new BarcodeGraphic(null, barcode));
                    //     }
                    // }
                    synchronized (ImageProcessor.this) {
                        latestBarcodeResults = new ArrayList<>(barcodes);
                        if (lastFrameTimestamp != currentFrameTimestamp) {
                            latestOcrResult = null;
                        }
                        lastFrameTimestamp = currentFrameTimestamp;
                        if (imageProcessingListener != null) {
                             TireInfo tireInfo = tireDataParser.parse(latestOcrResult, latestBarcodeResults);
                             Log.i(TAG, "Parsed TireInfo (from barcode scan callback): " + tireInfo.toString());
                             imageProcessingListener.onResults(tireInfo, latestOcrResult, latestBarcodeResults, currentFrameImageWidth, currentFrameImageHeight);
                        }
                    }
                    if (!bitmapForBarcode.isRecycled()) {
                        bitmapForBarcode.recycle();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Barcode scanning failed for frame: " + currentFrameTimestamp, e);
                     synchronized (ImageProcessor.this) {
                        latestBarcodeResults = null;
                        if (imageProcessingListener != null) {
                            if (lastFrameTimestamp == currentFrameTimestamp && latestOcrResult != null) {
                                TireInfo tireInfo = tireDataParser.parse(latestOcrResult, null);
                                imageProcessingListener.onResults(tireInfo, latestOcrResult, null, currentFrameImageWidth, currentFrameImageHeight);
                            } else if (lastFrameTimestamp != currentFrameTimestamp) {
                                imageProcessingListener.onError("Barcode scanning failed and no other data for frame: " + e.getMessage());
                            } else {
                                imageProcessingListener.onResults(tireDataParser.parse(null,null), null, null, currentFrameImageWidth, currentFrameImageHeight);
                            }
                        }
                    }
                    if (!bitmapForBarcode.isRecycled()) {
                        bitmapForBarcode.recycle();
                    }
                });
        // Note: inputMat was already released within scanBarcodesFromMat after tempRgbaMat was created or if an early error occurred.
    }

    private String valueFormatToString(int format) { // Keep this helper
        switch (format) {
            case Barcode.TYPE_UNKNOWN: return "UNKNOWN";
            case Barcode.TYPE_CONTACT_INFO: return "CONTACT_INFO";
            case Barcode.TYPE_EMAIL: return "EMAIL";
            case Barcode.TYPE_ISBN: return "ISBN";
            case Barcode.TYPE_PHONE: return "PHONE";
            case Barcode.TYPE_PRODUCT: return "PRODUCT";
            case Barcode.TYPE_SMS: return "SMS";
            case Barcode.TYPE_TEXT: return "TEXT";
            case Barcode.TYPE_URL: return "URL";
            case Barcode.TYPE_WIFI: return "WIFI";
            case Barcode.TYPE_GEO: return "GEO";
            case Barcode.TYPE_CALENDAR_EVENT: return "CALENDAR_EVENT";
            case Barcode.TYPE_DRIVER_LICENSE: return "DRIVER_LICENSE";
            default: return "OTHER_TYPE (" + format + ")";
        }
    }
     private String barcodeFormatToString(int format) {
        switch (format) {
            case Barcode.FORMAT_UNKNOWN: return "UNKNOWN";
            case Barcode.FORMAT_ALL_FORMATS: return "ALL_FORMATS";
            case Barcode.FORMAT_CODE_128: return "CODE_128";
            case Barcode.FORMAT_CODE_39: return "CODE_39";
            case Barcode.FORMAT_CODE_93: return "CODE_93";
            case Barcode.FORMAT_CODABAR: return "CODABAR";
            case Barcode.FORMAT_DATA_MATRIX: return "DATA_MATRIX";
            case Barcode.FORMAT_EAN_13: return "EAN_13";
            case Barcode.FORMAT_EAN_8: return "EAN_8";
            case Barcode.FORMAT_ITF: return "ITF";
            case Barcode.FORMAT_QR_CODE: return "QR_CODE";
            case Barcode.FORMAT_UPC_A: return "UPC_A";
            case Barcode.FORMAT_UPC_E: return "UPC_E";
            case Barcode.FORMAT_PDF417: return "PDF417";
            case Barcode.FORMAT_AZTEC: return "AZTEC";
            default: return "OTHER_FORMAT (" + format + ")";
        }
    }

}
