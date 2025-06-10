package com.example.imageprocessingapp;

public class BarcodeInfo {
    public String rawValue;
    public String format; // e.g., "QR_CODE", "EAN_13"

    public BarcodeInfo(String rawValue, String format) {
        this.rawValue = rawValue;
        this.format = format;
    }

    @Override
    public String toString() {
        return "BarcodeInfo{" +
                "rawValue='" + rawValue + '\'' +
                ", format='" + format + '\'' +
                '}';
    }
}
