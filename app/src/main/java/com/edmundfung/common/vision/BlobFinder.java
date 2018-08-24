package com.edmundfung.common.vision;

import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;

import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.nio.ByteBuffer;
import java.util.List;

public class BlobFinder {
    private int width;
    private int height;
    private int stride;
    private int pixelStride;
    private byte[] inputPixelsV = new byte[0]; // Reuse java byte array to avoid multiple allocations.

    private static final int encodingBlockSize = 2; // encoding uses a 2x2 block size

    // BlobFinder can find unique features in the image and return their positions
    public BlobFinder(Frame frame) throws NotYetAvailableException, IllegalArgumentException {
        Image image = frame.acquireCameraImage();
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException(
                    "Expected image in YUV_420_888 format, got format " + image.getFormat());
        }

        width = image.getWidth();
        height = image.getHeight();
        // we only care about the V plane for the red color
        stride = image.getPlanes()[2].getRowStride();
        pixelStride = image.getPlanes()[2].getPixelStride();
        ByteBuffer inputV = image.getPlanes()[2].getBuffer();
        if (inputV.capacity() != inputPixelsV.length) {
            inputPixelsV = new byte[inputV.capacity()];
        }
        inputV.position(0);
        inputV.get(inputPixelsV);

        image.close();
    }

    // Returns a list of Blobs where there are a significant amount of connected red pixels
    public List<Blob> Find() {
        BlobLocator bl = new BlobLocator(width, height, stride);
        // Go through all pixels in the V space and map to pixel space
        int inputEnd = inputPixelsV.length - (stride/encodingBlockSize) - 1;
        int j = 0;
        for (int i = 0; i < inputEnd; i+=pixelStride){
            if (inputPixelsV[i] < (byte) 0xD0 && inputPixelsV[i] > (byte) 0xB0){
                bl.Mark(j);
            }
            j+=encodingBlockSize;
            if (j % stride == 0) {
                j+=stride;
            }
        }
        return bl.GetBlobs();
    }

    private static final float screenY = 1920;
    private static final float screenX = 1080;

    // ScaleCoordsToScreen scales the x,y of the image to the x,y of a touch event
    public float[] ScaleCoordsToScreen(int x, int y) {
        float thx = screenX - (((float) y * screenX) / (float) height);
        float twy = ((float) x * screenY) / (float) width;
        Log.d("EDMUND", String.format("x: %d, y:%d, tw: %f, th: %f, w: %d, h: %d", x, y, twy, thx, width, height));
        return new float[]{thx, twy};
    }
}

