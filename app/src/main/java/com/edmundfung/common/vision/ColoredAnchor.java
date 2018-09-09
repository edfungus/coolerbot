package com.edmundfung.common.vision;

import com.google.ar.core.Anchor;

import java.util.Random;

public class ColoredAnchor {
    public final Anchor anchor;
    public final float[] color;

    public ColoredAnchor(Anchor a) {
        this.anchor = a;
        this.color = new float[] {randomColorFloat(), randomColorFloat(), randomColorFloat(), 255.0f};
    }

    public ColoredAnchor(Anchor a, float[] color4f) {
        this.anchor = a;
        this.color = color4f;
    }

    private static final Random r = new Random();
    private static final float maxColorValue = 255.0f;
    private float randomColorFloat() {
        return r.nextFloat() * maxColorValue;
    }
}
