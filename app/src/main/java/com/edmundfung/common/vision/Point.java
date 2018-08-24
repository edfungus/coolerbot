package com.edmundfung.common.vision;

public class Point {
    int width;
    int height;
    private int stride;

    // Point helps convert from 2D to 1D coordinate systems
    public Point(int w, int h, int s){
        width = w;
        height = h;
        stride = s;
    }

    public int GetPosition() {
        return height * stride + width;
    }

}
