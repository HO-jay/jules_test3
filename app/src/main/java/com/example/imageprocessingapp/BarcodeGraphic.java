package com.example.imageprocessingapp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import com.google.mlkit.vision.barcode.common.Barcode;

public class BarcodeGraphic extends GraphicOverlay.Graphic {
    private static final int BOX_COLOR = Color.BLUE;
    private static final float STROKE_WIDTH = 5.0f;
    private static final float TEXT_SIZE = 30.0f; // For drawing barcode value

    private final Paint rectPaint;
    private final Paint textPaint;
    private final Barcode barcode;
    private final RectF rectF = new RectF(); // Reusable RectF

    public BarcodeGraphic(GraphicOverlay overlay, Barcode barcode) {
        super(overlay);
        this.barcode = barcode;

        rectPaint = new Paint();
        rectPaint.setColor(BOX_COLOR);
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(STROKE_WIDTH);

        textPaint = new Paint();
        textPaint.setColor(BOX_COLOR);
        textPaint.setTextSize(TEXT_SIZE);
    }

    @Override
    public void draw(Canvas canvas) {
        if (barcode == null) {
            return;
        }

        Rect boundingBox = barcode.getBoundingBox();
        if (boundingBox != null) {
            rectF.set(boundingBox); // Use the member RectF
            RectF transformedRect = translateRect(rectF); // Use GraphicOverlay's transformation
            canvas.drawRect(transformedRect, rectPaint);

            // Optional: Draw barcode raw value
            // String rawValue = barcode.getRawValue();
            // if (rawValue != null && !rawValue.isEmpty()) {
            //     canvas.drawText(rawValue, transformedRect.left, transformedRect.top - STROKE_WIDTH, textPaint);
            // }
        }
    }
}
