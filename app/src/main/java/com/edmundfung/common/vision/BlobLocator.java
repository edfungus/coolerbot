package com.edmundfung.common.vision;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class BlobLocator {
    private int width;
    private int height;
    private int stride;
    private boolean[] markedCache;

    private static final int areaThreshold = 100; // encoding uses a 2x2 block size

    // BlobLocator takes all the valid points in a binary image and generates Blob objects for them
    public BlobLocator(int w, int h, int s) {
        width = w;
        height = h;
        stride = s;
        markedCache = new boolean[h*s];
    }

    // Mark marks 2x2 encoding block in the binary image based on single linear index
    public void Mark(int j) {
        try {
            markedCache[j] = true;
            markedCache[j + 1] = true;
            markedCache[j + stride] = true;
            markedCache[j + stride + 1] = true;

            // Additional none true pixel marker for smoothing
            markedCache[j+stride-1] = true;
            markedCache[j-1] = true;
            markedCache[j-stride] = true;
            markedCache[j-stride+1] = true;
            markedCache[j-stride-1] = true;
            markedCache[j-stride-2] = true;
            markedCache[j-2*stride-1] = true;
            markedCache[j-2*stride-2] = true;
        } catch (ArrayIndexOutOfBoundsException e) {
            // ignore...
        }
    }

    // GetBlobs will use the markedCache to find blobs. The markCache WILL BE DESTROYED!
    public List<Blob> GetBlobs() {
        List<Blob> blobs = new ArrayList<Blob>();

        // Stores the next points which are attached to current point. When this queue is empty,
        // that means that there are no more adjacent marked points and this blob is markedCache.
        Queue<Point> queue = new LinkedList<Point>();

        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                if (checkPoint(i,j)) {
                    Blob b = new Blob(width, height);

                    Point p = new Point(i,j,stride);
                    queue.add(p);
                    markedCache[p.GetPosition()] = false;
                    b.AddPoint(i,j);

                    while (queue.peek() != null) {
                        p = queue.remove();

                        // All the points we need to check whether we have a connected blob points
                        Point[] pointsToCheck = new Point[]{
                                new Point(p.width - 1,p.height - 1,stride),
                                new Point(p.width,p.height - 1,stride),
                                new Point(p.width + 1,p.height - 1,stride),
                                new Point(p.width - 1,p.height,stride),
                                new Point(p.width + 1,p.height,stride),
                                new Point(p.width - 1,p.height + 1,stride),
                                new Point(p.width,p.height + 1,stride),
                                new Point(p.width + 1,p.height + 1,stride),
                        };
                        // Actually check the points to see if they are marked. If so add to queue
                        // to check around that point too
                        for (int ptc = 0; ptc < pointsToCheck.length ; ptc++) {
                            if (checkPoint(pointsToCheck[ptc])) {
                                queue.add(pointsToCheck[ptc]);
                                markedCache[pointsToCheck[ptc].GetPosition()] = false;
                                b.AddPoint(pointsToCheck[ptc]);
                            }
                        }
                    }

                    // Only if the Blob is big enough, then we consider it
                    if (b.area > areaThreshold) {
                        blobs.add(b);
                    }
                }
            }
        }
        return blobs;
    }

    // checkPoint performs boundary checks and whether or not this coordinate is marked
    private boolean checkPoint(int w, int h) {
        if (h >= 0 && w >= 0 && h < height && w < width && markedCache[h * stride + w]) {
            return true;
        }
        return false;
    }
    private boolean checkPoint(Point p) {
        return checkPoint(p.width, p.height);
    }
}
