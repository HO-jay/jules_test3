package com.example.imageprocessingapp;

import java.util.List;
import java.util.ArrayList;

public class TireInfo {
    public String rawOcrText = "";
    public String detectedTireSize = null;
    public String detectedLoadIndexAndSpeedRating = null;
    public String detectedCountryOfOrigin = null;
    public String detectedDotCode = null; // For now, initialize to null
    public List<BarcodeInfo> detectedBarcodes = new ArrayList<>();

    @Override
    public String toString() {
        StringBuilder barcodesStr = new StringBuilder();
        for (BarcodeInfo barcode : detectedBarcodes) {
            barcodesStr.append(barcode.toString()).append("; ");
        }
        if (barcodesStr.length() > 0) {
            barcodesStr.setLength(barcodesStr.length() - 2); // Remove last "; "
        }

        return "TireInfo{" +
                "\n  rawOcrText='" + (rawOcrText.length() > 50 ? rawOcrText.substring(0, 50) + "..." : rawOcrText) + '\'' + // Truncate for brevity
                ",\n  detectedTireSize='" + detectedTireSize + '\'' +
                ",\n  detectedLoadIndexAndSpeedRating='" + detectedLoadIndexAndSpeedRating + '\'' +
                ",\n  detectedCountryOfOrigin='" + detectedCountryOfOrigin + '\'' +
                ",\n  detectedDotCode='" + detectedDotCode + '\'' +
                ",\n  detectedBarcodes=[" + barcodesStr.toString() + "]" +
                "\n}";
    }
}
