package com.example.imageprocessingapp;

import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.barcode.common.Barcode;

import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.util.Log;

public class TireDataParser {
    private static final String TAG = "TireDataParser";

    // Regex patterns (can be refined)
    // Standard tire size: P225/60R16, 225/60R16, 225/60 R16, LT225/60R16
    private static final Pattern TIRE_SIZE_PATTERN = Pattern.compile("(P|LT)?\\s*\\d{2,3}\\s*/\\s*\\d{2,3}\\s*R\\s*\\d{2,3}");
    // More specific, capturing groups: (P or LT)(225)/(60)R(16)
    // private static final Pattern TIRE_SIZE_PATTERN_DETAILED = Pattern.compile("(P|LT)?(\\d{2,3})/(\\d{2,3})\\s?R(\\d{2,3})");

    // Load Index and Speed Rating: 98H, 100W etc. (usually after tire size)
    private static final Pattern LOAD_SPEED_PATTERN = Pattern.compile("\\b\\d{2,3}[A-Z]\\b");

    // Country of Origin: Made in XXXXX
    private static final Pattern MADE_IN_PATTERN = Pattern.compile("Made\\s+in\\s+([A-Za-z\\s]+)", Pattern.CASE_INSENSITIVE);

    // Basic DOT code prefix (actual DOT codes are more complex)
    // private static final Pattern DOT_CODE_PREFIX_PATTERN = Pattern.compile("\\bDOT\\b\\s*([A-Z0-9]{4}\\s*[A-Z0-9]{4}\\s*[A-Z0-9]{0,4})");


    public TireInfo parse(Text ocrResult, List<Barcode> barcodeResults) {
        TireInfo tireInfo = new TireInfo();

        // Process OCR results
        if (ocrResult != null) {
            tireInfo.rawOcrText = ocrResult.getText();
            Log.d(TAG, "Raw OCR Text: " + tireInfo.rawOcrText);

            // For regex matching, it's often easier to work with the full text,
            // or iterate line by line if specific line context is important.
            String fullText = tireInfo.rawOcrText.replace("\n", " "); // Replace newlines for easier regex across lines

            Matcher sizeMatcher = TIRE_SIZE_PATTERN.matcher(fullText);
            if (sizeMatcher.find()) {
                tireInfo.detectedTireSize = sizeMatcher.group(0).trim();
                Log.d(TAG, "Found Tire Size: " + tireInfo.detectedTireSize);

                // Attempt to find Load/Speed rating immediately after tire size
                // This is a common placement, but not guaranteed.
                String textAfterSize = fullText.substring(sizeMatcher.end()).trim();
                Matcher loadSpeedMatcher = LOAD_SPEED_PATTERN.matcher(textAfterSize);
                if (loadSpeedMatcher.find() && loadSpeedMatcher.start() < 10) { // Check if it's close to the tire size
                    tireInfo.detectedLoadIndexAndSpeedRating = loadSpeedMatcher.group(0).trim();
                    Log.d(TAG, "Found Load/Speed (after size): " + tireInfo.detectedLoadIndexAndSpeedRating);
                }
            }

            // If Load/Speed wasn't found immediately after size, try searching the whole text
            if (tireInfo.detectedLoadIndexAndSpeedRating == null) {
                Matcher loadSpeedMatcherGlobal = LOAD_SPEED_PATTERN.matcher(fullText);
                if (loadSpeedMatcherGlobal.find()) {
                     // This might pick up other numbers, so context is important.
                     // A more robust solution would analyze proximity to tire size or specific keywords.
                    tireInfo.detectedLoadIndexAndSpeedRating = loadSpeedMatcherGlobal.group(0).trim();
                    Log.d(TAG, "Found Load/Speed (global search): " + tireInfo.detectedLoadIndexAndSpeedRating);
                }
            }


            Matcher madeInMatcher = MADE_IN_PATTERN.matcher(fullText);
            if (madeInMatcher.find()) {
                tireInfo.detectedCountryOfOrigin = madeInMatcher.group(1).trim(); // Group 1 is the country name
                Log.d(TAG, "Found Country of Origin: " + tireInfo.detectedCountryOfOrigin);
            }

            // DOT code parsing is more complex and will be basic here or left for future.
            // For now, setting to null as per requirement.
            tireInfo.detectedDotCode = null; // Placeholder

        } else {
            Log.w(TAG, "OCR Result is null.");
        }

        // Process Barcode results
        if (barcodeResults != null) {
            for (Barcode mlKitBarcode : barcodeResults) {
                String rawValue = mlKitBarcode.getRawValue();
                String formatStr = barcodeFormatToString(mlKitBarcode.getFormat());
                tireInfo.detectedBarcodes.add(new BarcodeInfo(rawValue, formatStr));
            }
            Log.d(TAG, "Processed " + tireInfo.detectedBarcodes.size() + " barcodes.");
        } else {
            Log.w(TAG, "Barcode Results list is null.");
        }

        Log.i(TAG, "Parsed TireInfo: " + tireInfo.toString());
        return tireInfo;
    }

    // Helper to convert barcode format int to String (already in ImageProcessor, duplicated for now or could be utility)
    private String barcodeFormatToString(int format) {
        switch (format) {
            case Barcode.FORMAT_UNKNOWN: return "UNKNOWN";
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
            default: return "OTHER (" + format + ")";
        }
    }
}
