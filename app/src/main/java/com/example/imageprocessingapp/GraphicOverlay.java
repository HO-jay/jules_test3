package com.example.imageprocessingapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * A view which renders a series of custom graphics to be overlaid on top of an image.
 * The responsibility of this class is to manage the drawing of objects and provide a
 * transformation matrix to convert image coordinates to view coordinates.
 */
public class GraphicOverlay extends View {
    private final Object lock = new Object();
    private final List<Graphic> graphics = new ArrayList<>();

    // Matrix for transforming from image coordinates to view coordinates.
    private final Matrix transformationMatrix = new Matrix();
    private int imageWidth;
    private int imageHeight;

    // The factor of scaling needed to fit the image data into the view.
    private float scaleFactor = 1.0f;
    // The number of pixels to offset in x coordinates to center the image.
    private float postScaleWidthOffset;
    // The number of pixels to offset in y coordinates to center the image.
    private float postScaleHeightOffset;


    /**
     * Base class for a custom graphics object to be rendered within the graphic overlay.
     */
    public abstract static class Graphic {
        protected GraphicOverlay graphicOverlay;

        public Graphic(GraphicOverlay overlay) {
            this.graphicOverlay = overlay;
        }

        /**
         * Draw the graphic on the supplied canvas. Drawing should use the functions of
         * the canvas to draw directly on it. Transformations are applied by the overlay.
         *
         * @param canvas The canvas to draw on.
         */
        public abstract void draw(Canvas canvas);

        /** Transforms a point from image coordinates to view coordinates. */
        public RectF translateRect(RectF rect) {
            return graphicOverlay.translateRect(rect);
        }
         public float scaleX(float horizontal) {
            return horizontal * graphicOverlay.scaleFactor;
        }
        public float scaleY(float vertical) {
            return vertical * graphicOverlay.scaleFactor;
        }

        public float translateX(float x) {
            return scaleX(x) + graphicOverlay.postScaleWidthOffset;
        }
        public float translateY(float y) {
            return scaleY(y) + graphicOverlay.postScaleHeightOffset;
        }


    }

    public GraphicOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Removes all graphics from the overlay.
     */
    public void clear() {
        synchronized (lock) {
            graphics.clear();
        }
        postInvalidate();
    }

    /**
     * Adds a graphic to the overlay.
     */
    public void add(Graphic graphic) {
        synchronized (lock) {
            graphics.add(graphic);
        }
        postInvalidate();
    }

    public void addAll(List<Graphic> newGraphics) {
        if (newGraphics == null || newGraphics.isEmpty()) {
            return;
        }
        synchronized (lock) {
            graphics.addAll(newGraphics);
        }
        postInvalidate();
    }


    /**
     * Sets the attributes of the image data being rendered. Dimensions are used to compute a
     * transformation matrix for scaling image data to fit the view.
     *
     * @param imageWidth  The width of the image translated to landscape mode.
     * @param imageHeight The height of the image translated to landscape mode.
     */
    public void setImageSourceInfo(int imageWidth, int imageHeight) {
        if (imageWidth == 0 || imageHeight == 0) {
            // Avoid division by zero
            return;
        }
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        configureTransform();
        postInvalidate();
    }

    private void configureTransform() {
        if (imageWidth <= 0 || imageHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float imageAspectRatio = (float) imageWidth / imageHeight;
        float viewAspectRatio = viewWidth / viewHeight;

        postScaleWidthOffset = 0;
        postScaleHeightOffset = 0;

        if (imageAspectRatio > viewAspectRatio) {
            // Image is wider than view, scale by width
            scaleFactor = viewWidth / imageWidth;
            postScaleHeightOffset = (viewHeight - imageHeight * scaleFactor) / 2;
        } else {
            // Image is taller than view, scale by height
            scaleFactor = viewHeight / imageHeight;
            postScaleWidthOffset = (viewWidth - imageWidth * scaleFactor) / 2;
        }

        transformationMatrix.reset();
        transformationMatrix.postScale(scaleFactor, scaleFactor);
        transformationMatrix.postTranslate(postScaleWidthOffset, postScaleHeightOffset);

        invalidate();
    }


    /**
     * Draws the overlay with its associated graphics.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        synchronized (lock) {
            if (imageWidth != 0 && imageHeight != 0) { // Only draw if configured
                for (Graphic graphic : graphics) {
                    graphic.draw(canvas);
                }
            }
        }
    }

    /** Transforms a rectangle from image coordinates to view coordinates. */
    public RectF translateRect(RectF rect) {
        RectF translatedRect = new RectF();
        transformationMatrix.mapRect(translatedRect, rect);
        return translatedRect;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            configureTransform();
        }
    }
}
