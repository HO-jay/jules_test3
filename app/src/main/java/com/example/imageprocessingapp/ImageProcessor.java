package com.example.imageprocessingapp;

import androidx.camera.core.ImageProxy;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.util.Log;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
// import org.opencv.core.Rect; // Already imported from android.graphics
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
// import org.opencv.imgcodecs.Imgcodecs; // For debugging

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ImageProcessor {
    private static final String TAG = "ImageProcessor";

    private Mat yuvMat;
    private Mat grayMat;
    private Mat thresholdMat;
    private Mat denoisedMat;
    private Mat dewarpedGrayMat;
    private Mat hierarchy;
    private TextRecognizer textRecognizer;
    private BarcodeScanner barcodeScanner;
    private TireDataParser tireDataParser;
    private ImageProcessingListener imageProcessingListener;

    // Interface for callbacks to MainActivity
    public interface ImageProcessingListener {
        void onResults(TireInfo tireInfo, Text ocrText, List<Barcode> barcodes, Bitmap sourceBitmap, int imageWidth, int imageHeight);
        void onError(String errorMessage);
    }

    public void setImageProcessingListener(ImageProcessingListener listener) {
        this.imageProcessingListener = listener;
    }

    private Text latestOcrResult = null;
    private List<Barcode> latestBarcodeResults = null;
    private long lastFrameTimestamp = -1;
    private int currentFrameImageWidth = 0;
    private int currentFrameImageHeight = 0;

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
        barcodeScanner = BarcodeScanning.getClient();
        tireDataParser = new TireDataParser();
        Log.d(TAG, "ImageProcessor components initialized.");
    }

    public Mat processImage(ImageProxy imageProxy) {
        if (imageProxy == null || imageProxy.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid ImageProxy.");
            if (imageProcessingListener != null) imageProcessingListener.onError("Invalid ImageProxy.");
            if (imageProxy != null) imageProxy.close();
            return null;
        }

        final long currentFrameTimestamp = imageProxy.getImageInfo().getTimestamp();
        currentFrameImageWidth = imageProxy.getWidth();
        currentFrameImageHeight = imageProxy.getHeight();

        imageProxyToYuvMat(imageProxy); // Populates class member yuvMat
        if (yuvMat == null || yuvMat.empty()) {
            Log.e(TAG, "YUV Mat creation failed.");
            if (imageProcessingListener != null) imageProcessingListener.onError("YUV Mat creation failed.");
            return null; // yuvMat is a class member, no need to release imageProxy here, done by caller
        }

        ensureMatAllocation(currentFrameImageWidth, currentFrameImageHeight);

        Mat tempGrayMat = yuvMat.submat(0, currentFrameImageHeight, 0, currentFrameImageWidth);
        tempGrayMat.copyTo(grayMat);

        Bitmap bitmapToSaveForCallback = null;
        if (!grayMat.empty()) {
            bitmapToSaveForCallback = Bitmap.createBitmap(grayMat.cols(), grayMat.rows(), Bitmap.Config.ARGB_8888);
            Mat tempRgbaForSave = new Mat();
            Imgproc.cvtColor(grayMat, tempRgbaForSave, Imgproc.COLOR_GRAY2RGBA);
            Utils.matToBitmap(tempRgbaForSave, bitmapToSaveForCallback);
            tempRgbaForSave.release();
        } else {
            Log.w(TAG, "grayMat is empty, cannot create bitmapToSaveForCallback");
        }

        scanBarcodesFromMat(grayMat.clone(), currentFrameTimestamp, bitmapToSaveForCallback);

        Imgproc.adaptiveThreshold(grayMat, thresholdMat, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 2);
        Imgproc.medianBlur(thresholdMat, denoisedMat, 3);

        try {
            dewarpTextRegions(denoisedMat, grayMat);
        } catch (Exception e) {
            Log.e(TAG, "Error during dewarping: " + e.getMessage(), e);
            if (grayMat != null && !grayMat.empty()) grayMat.copyTo(dewarpedGrayMat);
        }

        Mat resultMatToReturn = null;
        if (dewarpedGrayMat != null && !dewarpedGrayMat.empty()) {
            recognizeTextFromMat(dewarpedGrayMat.clone(), currentFrameTimestamp, bitmapToSaveForCallback);
            resultMatToReturn = dewarpedGrayMat.clone();
        } else if (grayMat != null && !grayMat.empty()) {
            Log.w(TAG, "DewarpedMat is empty, using grayMat for text recognition.");
            recognizeTextFromMat(grayMat.clone(), currentFrameTimestamp, bitmapToSaveForCallback);
            resultMatToReturn = grayMat.clone();
        } else {
            Log.e(TAG, "Both dewarpedGrayMat and grayMat are empty. Cannot perform text recognition.");
            // Call listener with no OCR data but potentially with barcode data and the (possibly null) bitmapToSaveForCallback
            if (imageProcessingListener != null) {
                 synchronized (ImageProcessor.this) { // Protect access to latestBarcodeResults
                    TireInfo tireInfo = tireDataParser.parse(null, latestBarcodeResults);
                    imageProcessingListener.onResults(tireInfo, null, latestBarcodeResults, bitmapToSaveForCallback, currentFrameImageWidth, currentFrameImageHeight);
                 }
            }
        }
        return resultMatToReturn;
    }

    public void recognizeTextFromMat(Mat inputMat, final long currentFrameTimestamp, final Bitmap finalBitmapToSave) {
        if (inputMat == null || inputMat.empty()) {
            Log.e(TAG, "Input Mat for text recognition is null or empty for frame: " + currentFrameTimestamp);
            if (imageProcessingListener != null) {
                synchronized (ImageProcessor.this) {
                    TireInfo tireInfo = tireDataParser.parse(null, latestBarcodeResults); // Parse with what we have
                    imageProcessingListener.onResults(tireInfo, null, latestBarcodeResults, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                }
            }
            if (inputMat != null) inputMat.release(); // Release if it was passed in non-null but empty
            return;
        }
         if (inputMat.type() != CvType.CV_8UC1 && inputMat.type() != CvType.CV_8UC3 && inputMat.type() != CvType.CV_8UC4) {
            Log.e(TAG, "Input Mat type not suitable for Bitmap conversion: " + inputMat.type() + " for frame: " + currentFrameTimestamp);
            if (imageProcessingListener != null) {
                synchronized (ImageProcessor.this) {
                    TireInfo tireInfo = tireDataParser.parse(null, latestBarcodeResults);
                    imageProcessingListener.onResults(tireInfo, null, latestBarcodeResults, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                }
            }
            inputMat.release();
            return;
        }

        Bitmap bitmapForTextRecognition = Bitmap.createBitmap(inputMat.cols(), inputMat.rows(), Bitmap.Config.ARGB_8888);
        Mat conversionMat = new Mat();
        if (inputMat.type() == CvType.CV_8UC1) {
            Imgproc.cvtColor(inputMat, conversionMat, Imgproc.COLOR_GRAY2RGBA);
            Utils.matToBitmap(conversionMat, bitmapForTextRecognition);
        } else if (inputMat.type() == CvType.CV_8UC3) {
            Imgproc.cvtColor(inputMat, conversionMat, Imgproc.COLOR_BGR2RGBA);
            Utils.matToBitmap(conversionMat, bitmapForTextRecognition);
        } else { // CV_8UC4
            Utils.matToBitmap(inputMat, bitmapForTextRecognition);
        }
        conversionMat.release();
        inputMat.release(); // Release the clone passed to this method

        if (bitmapForTextRecognition == null) {
            Log.e(TAG, "Failed to convert Mat to Bitmap for text recognition for frame: " + currentFrameTimestamp);
             if (imageProcessingListener != null) {
                synchronized (ImageProcessor.this) {
                     TireInfo tireInfo = tireDataParser.parse(null, latestBarcodeResults);
                     imageProcessingListener.onResults(tireInfo, null, latestBarcodeResults, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                }
            }
            return;
        }

        InputImage localInputImage = InputImage.fromBitmap(bitmapForTextRecognition, 0);
        textRecognizer.process(localInputImage)
            .addOnSuccessListener(visionText -> {
                Log.d(TAG, "Text recognition successful for frame: " + currentFrameTimestamp);
                logRecognizedText(visionText);
                synchronized (ImageProcessor.this) {
                    latestOcrResult = visionText;
                    if (lastFrameTimestamp != currentFrameTimestamp) latestBarcodeResults = null;
                    lastFrameTimestamp = currentFrameTimestamp;
                    if (imageProcessingListener != null) {
                        TireInfo tireInfo = tireDataParser.parse(latestOcrResult, latestBarcodeResults);
                        imageProcessingListener.onResults(tireInfo, latestOcrResult, latestBarcodeResults, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                    }
                }
                if (!bitmapForTextRecognition.isRecycled()) bitmapForTextRecognition.recycle();
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Text recognition failed for frame: " + currentFrameTimestamp, e);
                synchronized (ImageProcessor.this) {
                    latestOcrResult = null;
                    if (imageProcessingListener != null) {
                        if (lastFrameTimestamp == currentFrameTimestamp && latestBarcodeResults != null) {
                            TireInfo tireInfo = tireDataParser.parse(null, latestBarcodeResults);
                            imageProcessingListener.onResults(tireInfo, null, latestBarcodeResults, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                        } else { // Covers case where lastFrameTimestamp != currentFrameTimestamp OR latestBarcodeResults is null
                            imageProcessingListener.onResults(tireDataParser.parse(null, null), null, null, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                        }
                    }
                }
                if (!bitmapForTextRecognition.isRecycled()) bitmapForTextRecognition.recycle();
            });
    }

    private void scanBarcodesFromMat(Mat inputMatForBarcode, final long currentFrameTimestamp, final Bitmap finalBitmapToSave) {
        if (inputMatForBarcode == null || inputMatForBarcode.empty()) {
            Log.e(TAG, "Input Mat for barcode scanning is null or empty for frame: " + currentFrameTimestamp);
            if (imageProcessingListener != null) {
                synchronized (ImageProcessor.this) {
                    TireInfo tireInfo = tireDataParser.parse(latestOcrResult, null); // Parse with what we have
                    imageProcessingListener.onResults(tireInfo, latestOcrResult, null, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                }
            }
            if (inputMatForBarcode != null) inputMatForBarcode.release();
            return;
        }
        Mat matForConversion = new Mat();
        if (inputMatForBarcode.type() == CvType.CV_8UC1) {
            inputMatForBarcode.copyTo(matForConversion); // Use directly if already grayscale
        } else if (inputMatForBarcode.type() == CvType.CV_8UC3 || inputMatForBarcode.type() == CvType.CV_8UC4) {
            Imgproc.cvtColor(inputMatForBarcode, matForConversion, Imgproc.COLOR_BGR2GRAY); // Or RGBA2GRAY
        } else {
            Log.e(TAG, "Unsupported Mat type for barcode conversion: " + inputMatForBarcode.type());
            inputMatForBarcode.release();
            return;
        }
        inputMatForBarcode.release(); // Release the clone passed in

        Bitmap bitmapForBarcode = Bitmap.createBitmap(matForConversion.cols(), matForConversion.rows(), Bitmap.Config.ARGB_8888);
        Mat tempRgbaMat = new Mat();
        Imgproc.cvtColor(matForConversion, tempRgbaMat, Imgproc.COLOR_GRAY2RGBA);
        Utils.matToBitmap(tempRgbaMat, bitmapForBarcode);
        tempRgbaMat.release();
        matForConversion.release();

        if (bitmapForBarcode == null) {
            Log.e(TAG, "Failed to convert Mat to Bitmap for barcode scanning for frame: " + currentFrameTimestamp);
            return;
        }

        InputImage localInputImage = InputImage.fromBitmap(bitmapForBarcode, 0);
        barcodeScanner.process(localInputImage)
            .addOnSuccessListener(barcodes -> {
                Log.d(TAG, "Barcode scanning successful for frame: " + currentFrameTimestamp + ". Found " + barcodes.size() + " barcode(s).");
                synchronized (ImageProcessor.this) {
                    latestBarcodeResults = new ArrayList<>(barcodes);
                    if (lastFrameTimestamp != currentFrameTimestamp) latestOcrResult = null;
                    lastFrameTimestamp = currentFrameTimestamp;
                    if (imageProcessingListener != null) {
                        TireInfo tireInfo = tireDataParser.parse(latestOcrResult, latestBarcodeResults);
                        imageProcessingListener.onResults(tireInfo, latestOcrResult, latestBarcodeResults, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                    }
                }
                if (!bitmapForBarcode.isRecycled()) bitmapForBarcode.recycle();
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Barcode scanning failed for frame: " + currentFrameTimestamp, e);
                synchronized (ImageProcessor.this) {
                    latestBarcodeResults = null;
                    if (imageProcessingListener != null) {
                        if (lastFrameTimestamp == currentFrameTimestamp && latestOcrResult != null) {
                            TireInfo tireInfo = tireDataParser.parse(latestOcrResult, null);
                            imageProcessingListener.onResults(tireInfo, latestOcrResult, null, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                        } else { // Covers case where lastFrameTimestamp != currentFrameTimestamp OR latestOcrResult is null
                             imageProcessingListener.onResults(tireDataParser.parse(null,null), null, null, finalBitmapToSave, currentFrameImageWidth, currentFrameImageHeight);
                        }
                    }
                }
                if (!bitmapForBarcode.isRecycled()) bitmapForBarcode.recycle();
            });
    }

    // (Keep existing logRecognizedText, ensureMatAllocation, dewarpTextRegions, imageProxyToYuvMat, getBytesFromBuffer, cleanup, valueFormatToString, barcodeFormatToString methods)

    private void logRecognizedText(Text visionText) {
        String resultText = visionText.getText();
        Log.i(TAG, "Recognized Text (Full): \n" + resultText);
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            Log.d(TAG, "TextBlock: '" + block.getText() + "' Box: " + block.getBoundingBox());
            for (Text.Line line : block.getLines()) {
                Log.d(TAG, "  Line: '" + line.getText() + "' Box: " + line.getBoundingBox());
                for (Text.Element element : line.getElements()) {
                    Log.d(TAG, "    Element: '" + element.getText() + "' Box: " + element.getBoundingBox());
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
            if (dewarpedGrayMat != null) dewarpedGrayMat.release();
            dewarpedGrayMat = new Mat(height, width, CvType.CV_8UC1); // Initial size, dewarping might change it
        }
        if (hierarchy == null) {
            hierarchy = new Mat();
        } else {
             hierarchy.release();
             hierarchy = new Mat();
         }
    }

    private void dewarpTextRegions(Mat processedBinaryMat, Mat sourceGrayMat) {
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(processedBinaryMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        if (contours.isEmpty()) {
            sourceGrayMat.copyTo(dewarpedGrayMat); return;
        }
        MatOfPoint largestContour = Collections.max(contours, Comparator.comparingDouble(Imgproc::contourArea));
        if (Imgproc.contourArea(largestContour) < 100) {
            sourceGrayMat.copyTo(dewarpedGrayMat); largestContour.release(); return;
        }
        Rect boundingBox = Imgproc.boundingRect(largestContour);
        MatOfPoint2f srcPoints = new MatOfPoint2f(
                new Point(boundingBox.x, boundingBox.y),
                new Point(boundingBox.x + boundingBox.width, boundingBox.y),
                new Point(boundingBox.x, boundingBox.y + boundingBox.height),
                new Point(boundingBox.x + boundingBox.width, boundingBox.y + boundingBox.height)
        );
        float desiredWidth = boundingBox.width;
        float desiredHeight = boundingBox.height;
        MatOfPoint2f dstPoints = new MatOfPoint2f(
                new Point(0, 0), new Point(desiredWidth - 1, 0),
                new Point(0, desiredHeight - 1), new Point(desiredWidth - 1, desiredHeight - 1)
        );
        Mat perspectiveMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
        if (perspectiveMatrix.empty()) {
            sourceGrayMat.copyTo(dewarpedGrayMat);
        } else {
            Size dewarpedSize = new Size(desiredWidth, desiredHeight);
            // Ensure dewarpedGrayMat is correctly sized for the output of warpPerspective
            if (dewarpedGrayMat.width() != (int)desiredWidth || dewarpedGrayMat.height() != (int)desiredHeight) {
                 if(dewarpedGrayMat != null) dewarpedGrayMat.release();
                 dewarpedGrayMat = new Mat((int)desiredHeight, (int)desiredWidth, CvType.CV_8UC1);
            }
            Imgproc.warpPerspective(sourceGrayMat, dewarpedGrayMat, perspectiveMatrix, dewarpedSize, Imgproc.INTER_LINEAR);
        }
        srcPoints.release(); dstPoints.release(); perspectiveMatrix.release(); largestContour.release();
        // Release other contour Mats in contours list if they were substantial and not cleared.
        for(MatOfPoint contour : contours) { if(contour != largestContour) contour.release(); }
    }

    private Mat imageProxyToYuvMat(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int width = image.getWidth(); int height = image.getHeight();
        if (yuvMat == null || yuvMat.cols() != width || yuvMat.rows() != (height + height / 2)) {
            if (yuvMat != null) yuvMat.release();
            yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
        }
        ByteBuffer yBuffer = planes[0].getBuffer();
        yuvMat.put(0, 0, getBytesFromBuffer(yBuffer, planes[0].getRowStride(), width, height, yBuffer.remaining(), true));
        ByteBuffer uBuffer = planes[1].getBuffer(); ByteBuffer vBuffer = planes[2].getBuffer();
        byte[] vuData = new byte[width * height / 2]; int vuIndex = 0;
        byte[] uBytesWithPadding = new byte[uBuffer.capacity()]; uBuffer.get(uBytesWithPadding); uBuffer.rewind();
        byte[] vBytesWithPadding = new byte[vBuffer.capacity()]; vBuffer.get(vBytesWithPadding); vBuffer.rewind();
        int uPixelStride = planes[1].getPixelStride(); int vPixelStride = planes[2].getPixelStride();
        int uRowStride = planes[1].getRowStride(); int vRowStride = planes[2].getRowStride();
        for (int r = 0; r < height / 2; r++) {
            for (int c = 0; c < width / 2; c++) {
                int vAddr = r * vRowStride + c * vPixelStride; int uAddr = r * uRowStride + c * uPixelStride;
                if (vAddr < vBytesWithPadding.length) vuData[vuIndex++] = vBytesWithPadding[vAddr]; else break;
                if (uAddr < uBytesWithPadding.length) vuData[vuIndex++] = uBytesWithPadding[uAddr]; else break;
                if (vuIndex >= vuData.length) break;
            } if (vuIndex >= vuData.length) break;
        }
        yuvMat.put(height, 0, vuData);
        return yuvMat;
    }

    private byte[] getBytesFromBuffer(ByteBuffer buffer, int rowStride, int width, int height, int bufferSize, boolean rewind) {
        if (rewind) buffer.rewind();
        byte[] data;
        if (buffer.hasArray() && buffer.arrayOffset() == 0 && rowStride == width && buffer.array().length == bufferSize) {
            data = buffer.array();
        } else {
            data = new byte[bufferSize]; buffer.get(data);
            if (rowStride != width) {
                byte[] tightData = new byte[width * height]; int srcOffset = 0; int dstOffset = 0;
                for (int i = 0; i < height; i++) {
                    if (srcOffset + width <= data.length && dstOffset + width <= tightData.length) {
                        System.arraycopy(data, srcOffset, tightData, dstOffset, width);
                    } else { Log.e(TAG, "Buffer copy error due to stride handling mismatch."); break; }
                    srcOffset += rowStride; dstOffset += width;
                } return tightData;
            }
        } return data;
    }

    public void cleanup() {
        if (yuvMat != null) { yuvMat.release(); yuvMat = null; }
        if (grayMat != null) { grayMat.release(); grayMat = null; }
        if (thresholdMat != null) { thresholdMat.release(); thresholdMat = null; }
        if (denoisedMat != null) { denoisedMat.release(); denoisedMat = null; }
        if (dewarpedGrayMat != null) { dewarpedGrayMat.release(); dewarpedGrayMat = null; }
        if (hierarchy != null) { hierarchy.release(); hierarchy = null; }
        Log.d(TAG, "ImageProcessor cleaned up. OpenCV Mats released.");
    }

    private String valueFormatToString(int format) {
        switch (format) {
            case Barcode.TYPE_UNKNOWN: return "UNKNOWN"; case Barcode.TYPE_CONTACT_INFO: return "CONTACT_INFO";
            case Barcode.TYPE_EMAIL: return "EMAIL"; case Barcode.TYPE_ISBN: return "ISBN";
            case Barcode.TYPE_PHONE: return "PHONE"; case Barcode.TYPE_PRODUCT: return "PRODUCT";
            case Barcode.TYPE_SMS: return "SMS"; case Barcode.TYPE_TEXT: return "TEXT";
            case Barcode.TYPE_URL: return "URL"; case Barcode.TYPE_WIFI: return "WIFI";
            case Barcode.TYPE_GEO: return "GEO"; case Barcode.TYPE_CALENDAR_EVENT: return "CALENDAR_EVENT";
            case Barcode.TYPE_DRIVER_LICENSE: return "DRIVER_LICENSE"; default: return "OTHER (" + format + ")";
        }
    }

    private String barcodeFormatToString(int format) {
        switch (format) {
            case Barcode.FORMAT_UNKNOWN: return "UNKNOWN"; case Barcode.FORMAT_ALL_FORMATS: return "ALL_FORMATS";
            case Barcode.FORMAT_CODE_128: return "CODE_128"; case Barcode.FORMAT_CODE_39: return "CODE_39";
            case Barcode.FORMAT_CODE_93: return "CODE_93"; case Barcode.FORMAT_CODABAR: return "CODABAR";
            case Barcode.FORMAT_DATA_MATRIX: return "DATA_MATRIX"; case Barcode.FORMAT_EAN_13: return "EAN_13";
            case Barcode.FORMAT_EAN_8: return "EAN_8"; case Barcode.FORMAT_ITF: return "ITF";
            case Barcode.FORMAT_QR_CODE: return "QR_CODE"; case Barcode.FORMAT_UPC_A: return "UPC_A";
            case Barcode.FORMAT_UPC_E: return "UPC_E"; case Barcode.FORMAT_PDF417: return "PDF417";
            case Barcode.FORMAT_AZTEC: return "AZTEC"; default: return "OTHER (" + format + ")";
        }
    }
}
