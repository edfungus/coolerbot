package com.edmundfung.common.vision;

import java.util.Arrays;
import java.util.List;

public class Human {
    /*Nose = 0
    Neck = 1
    RShoulder = 2
    RElbow = 3
    RWrist = 4
    LShoulder = 5
    LElbow = 6
    LWrist = 7
    RHip = 8
    RKnee = 9
    RAnkle = 10
    LHip = 11
    LKnee = 12
    LAnkle = 13
    REye = 14
    LEye = 15
    REar = 16
    LEar = 17*/
    public static List<String> parts = Arrays.asList("nose", "neck", "rShoulder", "rElbow", "rWist", "lShoulder", "lElbow",
            "lWrist", "rHip", "rKnee", "rAnkle", "lHip", "lKnee", "lAnkle", "rEye", "lEye", "rEar", "lEar");
    public int parts_coords[][] = new int[18][2];
    public int coords_index_set[] = new int[18];
    public boolean coords_index_assigned[] = new boolean[18];
}