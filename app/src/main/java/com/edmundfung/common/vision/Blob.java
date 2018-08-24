package com.edmundfung.common.vision;

public class Blob implements Comparable<Blob> {
    int area = 0;
    private int wTotal = 0;
    private int hTotal = 0;

    // AddPoint adds a point to the Blob
    public void AddPoint(int w, int h) {
        area++;
        wTotal+=w;
        hTotal+=h;
    }
    public void AddPoint(Point p) {
        AddPoint(p.width, p.height);
    }

    // GetCenter returns the center of the blob where the index 0 is the w or x axis and index 1
    // is the h or y axis.
    public int[] GetCenter() {
        return new int[]{(int) wTotal/area, (int) hTotal/area};
    }

    @Override
    public int compareTo(Blob b) {
        return area - b.area;
    }
}
