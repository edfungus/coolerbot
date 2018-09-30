package com.edmundfung.common.vision;

import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;

import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class BlobFinder {
    private int width;
    private int height;
    private int stride;
    private int pixelStride;
    private byte[] inputPixelsV = new byte[0]; // Reuse java byte array to avoid multiple allocations.
    private byte[] inputPixelsU = new byte[0]; // Reuse java byte array to avoid multiple allocations.

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
        ByteBuffer inputU = image.getPlanes()[1].getBuffer();
        if (inputU.capacity() != inputPixelsU.length) {
            inputPixelsU = new byte[inputU.capacity()];
        }
        inputU.position(0);
        inputU.get(inputPixelsU);

        image.close();
    }

    // Returns a list of Blobs where there are a significant amount of connected red pixels
    public List<Blob> Find() {
        BlobLocator bl = new BlobLocator(width, height, stride);
        // Go through all pixels in the V space and map to pixel space
        int inputEnd = inputPixelsV.length - (stride/encodingBlockSize) - 1;

        // Starting not at 0 to stay within bounds during marking since we mark a larger area for
        // smoothing
        int j = 2*stride;
        for (int i = 2*stride; i < inputEnd; i+=pixelStride){
            // U has "0xFF" at 0x7F and "0x00" at 0x80 so it makes the partitioning statement tricky
            boolean uSpace = (inputPixelsU[i] > (byte) 0x6A && inputPixelsU[i] < (byte) 0x7F) || (inputPixelsU[i] > (byte) 0x80 && inputPixelsU[i] < (byte) 0x9A);
            boolean vSpace = inputPixelsV[i] > (byte) 0xA0 && inputPixelsV[i] < (byte) 0xD0;
            if (j >= 2 && uSpace && vSpace){
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
    public ArrayList<Float> ScaleCoordsToScreen(int x, int y) {
        float thx = screenX - (((float) y * screenX) / (float) height);
        float twy = ((float) x * screenY) / (float) width;
//        Log.d("EDMUND", String.format("x: %d, y:%d, tw: %f, th: %f, w: %d, h: %d", x, y, twy, thx, width, height));
        ArrayList<Float> results = new ArrayList<Float>();
        results.add(thx);
        results.add(twy);
        return results;
    }
}

