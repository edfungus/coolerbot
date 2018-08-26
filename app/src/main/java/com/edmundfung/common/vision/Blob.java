package com.edmundfung.common.vision;

public class Blob implements Comparable<Blob> {
    private int width;
    private int height;
    int area = 0;
    private int wSum = 0;
    private int hSum = 0;
    private int minWidth = 99999;
    private int minHeight = 99999;
    private int maxWidth = 0;
    private int maxHeight = 0;

    public Blob(int w, int h) {
        width = w;
        height = h;
    }

    // AddPoint adds a point to the Blob
    public void AddPoint(int w, int h) {
        area++;
        wSum +=w;
        hSum +=h;
    }

    public void AddPoint(Point p) {
        AddPoint(p.width, p.height);
    }

    // GetCenter returns the center of the blob where the index 0 is the w or x axis and index 1
    // is the h or y axis.
    public int[] GetCenter() {
        return new int[]{(int) wSum /area, (int) hSum /area};
    }

    public float GetDensity() {
        float totalArea = (maxWidth - minWidth) * (maxHeight - minHeight);
        return (float) area / totalArea;
    }

    public float GetRoundness() {
        float r = (float) (maxWidth - minWidth) / (float) (maxHeight - minHeight);
        if (r > 1) {
            r = 1/r;
        }
        return r;
    }

    public float GetScore() {
        int[] c = GetCenter();
        float wCenter = 1 - (float) Math.abs(c[0] - width/2) / (width/2);
        float hCenter = 1 - (float) Math.abs(c[1] - height/2) / (height/2);
        return GetDensity() * 0.1f + GetRoundness() * 0.7f + wCenter * 0.1f + hCenter * 0.1f;
    }

    @Override
    public int compareTo(Blob b) {
        if (GetScore() > b.GetScore()) {
            return 1;
        }
        if (GetScore() < b.GetScore()) {
            return -1;
        }
        return 0;
    }

}
