package com.example.imageprocessingapp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import com.google.mlkit.vision.text.Text;

public class TextGraphic extends GraphicOverlay.Graphic {
    private static final int TEXT_COLOR = Color.GREEN;
    private static final float STROKE_WIDTH = 4.0f;
    private static final float TEXT_SIZE = 35.0f; // Optional: for drawing text

    private final Paint rectPaint;
    private final Paint textPaint; // Optional
    private final Text.Element element; // Could be Line or TextBlock too
    private final Text.Line line; // For drawing lines
    private final RectF rectF = new RectF(); // Reusable RectF

    public TextGraphic(GraphicOverlay overlay, Text.Element element) {
        super(overlay);
        this.element = element;
        this.line = null;

        rectPaint = new Paint();
        rectPaint.setColor(TEXT_COLOR);
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(STROKE_WIDTH);

        textPaint = new Paint();
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(TEXT_SIZE);
    }

    public TextGraphic(GraphicOverlay overlay, Text.Line line) {
        super(overlay);
        this.element = null;
        this.line = line;

        rectPaint = new Paint();
        rectPaint.setColor(Color.YELLOW); // Different color for lines
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(STROKE_WIDTH);

        textPaint = new Paint();
    }


    @Override
    public void draw(Canvas canvas) {
        Rect boundingBox = null;
        String textToDraw = null;

        if (element != null) {
            boundingBox = element.getBoundingBox();
            textToDraw = element.getText();
        } else if (line != null) {
            boundingBox = line.getBoundingBox();
            // textToDraw = line.getText(); // Optionally draw line text
        }

        if (boundingBox != null) {
            // Transform the bounding box to view coordinates
            rectF.set(boundingBox); // Use the member RectF
            RectF transformedRect = translateRect(rectF); // Uses GraphicOverlay's method
            canvas.drawRect(transformedRect, rectPaint);

            // Optional: Draw text (can be very cluttered if drawing element text)
            // if (textToDraw != null && textPaint != null && element != null) { // Only draw text for elements for now
            //    canvas.drawText(textToDraw, transformedRect.left, transformedRect.bottom, textPaint);
            // }
        }
    }
}
