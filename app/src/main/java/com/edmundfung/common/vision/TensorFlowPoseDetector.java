package com.edmundfung.common.vision;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Trace;
import android.util.Log;

import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Vector;

// logic from: https://github.com/liljom/openpose-tf-mobilenet-java
// logic also from: https://gist.github.com/jax79sg/f111bfbb6d1ef87e41cbacdfe70d8a7e
public class TensorFlowPoseDetector {
    private boolean logStats = false;
    private TensorFlowInferenceInterface inferenceInterface;

    // buffers
    private int[] input;
    private float[] floatInput;
    private float[] output;

    // model const
    private final String inputName = "image";
    private final String outputName = "Openpose/concat_stage7";
    private final String[] outputNames = new String[] {outputName};
    private final String modelFilename = "file:///android_asset/graph_freeze.pb";

    private final float NMS_Threshold = 0.15f;
    private final float Local_PAF_Threshold = 0.2f;
    private final float Part_Score_Threshold = 0.2f;
    private final int PAF_Count_Threshold = 5;
    private final int Part_Count_Threshold = 4;

    private final int inputSize = 368;
    private final int MapHeight = inputSize / 8;
    private final int MapWidth = inputSize / 8;
    private final int HeatMapCount = 19;
    private final int MaxPairCount = 17;
    private final int PafMapCount = 38;
    private final int MaximumFilterSize = 5;
    private final int frameWidth = 640;
    private final int frameHeight = 480;

    public static final int[][] CocoPairs = {{1, 2}, {1, 5}, {2, 3}, {3, 4}, {5, 6}, {6, 7}, {1, 8}, {8, 9}, {9, 10}, {1, 11},
            {11, 12}, {12, 13}, {1, 0}, {0, 14}, {14, 16}, {0, 15}, {15, 17}};
    private final int[][] CocoPairsNetwork = {{12, 13}, {20, 21}, {14, 15}, {16, 17}, {22, 23}, {24, 25}, {0, 1}, {2, 3},
            {4, 5}, {6, 7}, {8, 9}, {10, 11}, {28, 29}, {30, 31}, {34, 35}, {32, 33}, {36, 37}, {18, 19}, {26, 27}};


    public TensorFlowPoseDetector(final AssetManager assetManager) {
        inferenceInterface = new TensorFlowInferenceInterface(assetManager, modelFilename);
        input = new int[inputSize * inputSize];
        floatInput = new float[inputSize * inputSize *3];
        output = new float[MapHeight * MapWidth * (HeatMapCount + PafMapCount)];
    }


    public Bitmap GetBitmap(Frame frame) throws NotYetAvailableException {
        Image image = frame.acquireCameraImage();
        Bitmap bitmap = imageToBitmap(image);
        image.close();
        bitmap = Bitmap.createBitmap(bitmap, 0,0, frameHeight, frameHeight); // chop off the bottom of frame
        bitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);
        // rotate 90 degrees clockwise
        Matrix matrix = new Matrix();
        matrix.postRotate(90);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    public Vector<Human> FindHumans(final Bitmap bitmap) {
        if (bitmap == null) {
            Log.e("EDMUND tensorflow", "bitmap is null");
            return new Vector<Human>();
        }
        // Log this method so that it can be analyzed with systrace.
        Trace.beginSection("recognizeImage");
        Trace.beginSection("preprocessBitmap");
        bitmap.getPixels(input, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        for (int i = 0; i < input.length; ++i) {
            floatInput[i * 3 + 2] = ((input[i] >> 16) & 0xFF);  // R
            floatInput[i * 3 + 1] = ((input[i] >> 8) & 0xFF) ;  // G
            floatInput[i * 3 + 0] = (input[i] & 0xFF);          // B
        }
        Trace.endSection(); // preprocessBitmap

        // Copy the input data into TensorFlow.
        Trace.beginSection("feed");
        inferenceInterface.feed(inputName, floatInput, 1, inputSize, inputSize, 3);
        Trace.endSection(); // feed

        // Run the inference call.
        Trace.beginSection("run");
        inferenceInterface.run(outputNames, logStats);
        Trace.endSection();

        // Copy the output Tensor back into the output array.
        Trace.beginSection("fetch");
        inferenceInterface.fetch(outputName, output);
        Trace.endSection(); // fetch

        // process
        Vector<Human> humans = processOutput();
        Trace.endSection(); // recognizeImage
        return humans;
    }

    private Vector<Human> processOutput() {
        Vector<int[]> coordinates[] = new Vector[HeatMapCount - 1];

        // eliminate duplicate part recognitions
        for (int i = 0; i < (HeatMapCount - 1); i++) {
            coordinates[i] = new Vector<int[]>();
            for (int j = 0; j < MapHeight; j++) {
                for (int k = 0; k < MapWidth; k++) {
                    int[] coordinate = {j, k};
                    float max_value = 0;
                    for (int dj = -(MaximumFilterSize - 1) / 2; dj < (MaximumFilterSize + 1) / 2; dj++) {
                        if ((j + dj) >= MapHeight || (j + dj) < 0) {
                            break;
                        }
                        for (int dk = -(MaximumFilterSize - 1) / 2; dk < (MaximumFilterSize + 1) / 2; dk++) {
                            if ((k + dk) >= MapWidth || (k + dk) < 0) {
                                break;
                            }
                            float value = output[(HeatMapCount + PafMapCount) * MapWidth * (j + dj) + (HeatMapCount + PafMapCount) * (k + dk) + i];
                            if (value > max_value) {
                                max_value = value;
                            }
                        }
                    }
                    if (max_value > NMS_Threshold) {
                        if (max_value == output[(HeatMapCount + PafMapCount) * MapWidth * j + (HeatMapCount + PafMapCount) * k + i]) {
                            coordinates[i].addElement(coordinate);
                        }
                    }
                }
            }
        }

        // eliminate duplicate connections
        Vector<int[]> pairs[] = new Vector[MaxPairCount];
        Vector<int[]> pairs_final[] = new Vector[MaxPairCount];
        Vector<Float> pairs_scores[] = new Vector[MaxPairCount];
        Vector<Float> pairs_scores_final[] = new Vector[MaxPairCount];
        for (int i = 0; i < MaxPairCount; i++) {
            pairs[i] = new Vector<int[]>();
            pairs_scores[i] = new Vector<Float>();
            pairs_final[i] = new Vector<int[]>();
            pairs_scores_final[i] = new Vector<Float>();
            Vector<Integer> part_set = new Vector<Integer>();
            for (int p1 = 0; p1 < coordinates[CocoPairs[i][0]].size(); p1++) {
                for (int p2 = 0; p2 < coordinates[CocoPairs[i][1]].size(); p2++) {
                    int count = 0;
                    float score = 0.0f;
                    float scores[] = new float[10];
                    int p1x = coordinates[CocoPairs[i][0]].get(p1)[0];
                    int p1y = coordinates[CocoPairs[i][0]].get(p1)[1];
                    int p2x = coordinates[CocoPairs[i][1]].get(p2)[0];
                    int p2y = coordinates[CocoPairs[i][1]].get(p2)[1];
                    float dx = p2x - p1x;
                    float dy = p2y - p1y;
                    float normVec = (float) Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2));

                    if (normVec < 0.0001f) {
                        break;
                    }
                    float vx = dx / normVec;
                    float vy = dy / normVec;
                    for (int t = 0; t < 10; t++) {
                        int tx = (int) ((float) p1x + (t * dx / 9) + 0.5);
                        int ty = (int) ((float) p1y + (t * dy / 9) + 0.5);
                        int location = tx * (HeatMapCount + PafMapCount) * MapWidth + ty * (HeatMapCount + PafMapCount) + HeatMapCount;
                        scores[t] = vy * output[location + CocoPairsNetwork[i][0]];
                        scores[t] += vx * output[location + CocoPairsNetwork[i][1]];
                    }
                    for (int h = 0; h < 10; h++) {
                        if (scores[h] > Local_PAF_Threshold) {
                            count += 1;
                            score += scores[h];
                        }
                    }
                    if (score > Part_Score_Threshold && count >= PAF_Count_Threshold) {
                        boolean inserted = false;
                        int pair[] = {p1, p2};
                        for (int l = 0; l < pairs[i].size(); l++) {
                            if (score > pairs_scores[i].get(l)) {
                                pairs[i].insertElementAt(pair, l);
                                pairs_scores[i].insertElementAt(score, l);
                                inserted = true;
                                break;
                            }
                        }
                        if (!inserted) {
                            pairs[i].addElement(pair);
                            pairs_scores[i].addElement(score);
                        }
                    }
                }
            }
            for (int m = 0; m < pairs[i].size(); m++) {
                boolean conflict = false;
                for (int n = 0; n < part_set.size(); n++) {
                    if (pairs[i].get(m)[0] == part_set.get(n) || pairs[i].get(m)[1] == part_set.get(n)) {
                        conflict = true;
                        break;
                    }
                }
                if (!conflict) {
                    pairs_final[i].addElement(pairs[i].get(m));
                    pairs_scores_final[i].addElement(pairs_scores[i].get(m));
                    part_set.addElement(pairs[i].get(m)[0]);
                    part_set.addElement(pairs[i].get(m)[1]);
                }
            }
        }

        Vector<Human> humans = new Vector<Human>();
        Vector<Human> humans_final = new Vector<Human>();
        for (int i = 0; i < MaxPairCount; i++) {
            for (int j = 0; j < pairs_final[i].size(); j++) {
                boolean merged = false;
                int p1 = CocoPairs[i][0];
                int p2 = CocoPairs[i][1];
                int ip1 = pairs_final[i].get(j)[0];
                int ip2 = pairs_final[i].get(j)[1];
                for (int k = 0; k < humans.size(); k++) {
                    Human human = humans.get(k);
                    if ((ip1 == human.coords_index_set[p1] && human.coords_index_assigned[p1]) || (ip2 == human.coords_index_set[p2] && human.coords_index_assigned[p2])) {
                        human.parts_coords[p1] = coordinates[p1].get(ip1);
                        human.parts_coords[p2] = coordinates[p2].get(ip2);
                        human.coords_index_set[p1] = ip1;
                        human.coords_index_set[p2] = ip2;
                        human.coords_index_assigned[p1] = true;
                        human.coords_index_assigned[p2] = true;
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    Human human = new Human();
                    human.parts_coords[p1] = coordinates[p1].get(ip1);
                    human.parts_coords[p2] = coordinates[p2].get(ip2);
                    human.coords_index_set[p1] = ip1;
                    human.coords_index_set[p2] = ip2;
                    human.coords_index_assigned[p1] = true;
                    human.coords_index_assigned[p2] = true;
                    humans.addElement(human);
                }
            }
        }

        // remove people with too few parts
        for (int i = 0; i < humans.size(); i++) {
            int human_part_count = 0;
            for (int j = 0; j < HeatMapCount - 1; j++) {
                if (humans.get(i).coords_index_assigned[j]) {
                    human_part_count += 1;
                }
            }
            if (human_part_count > Part_Count_Threshold) {
                humans_final.addElement(humans.get(i));
            }
        }
        return humans_final;
    }

    private static Bitmap imageToBitmap(Image image){
        byte[] byteArray = null;
        byteArray = NV21toJPEG(YUV420toNV21(image),image.getWidth(),image.getHeight(),100);
        return BitmapFactory.decodeByteArray(byteArray,0,byteArray.length);
    }

    private static byte[] NV21toJPEG(byte[] nv21, int width, int height, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        yuv.compressToJpeg(new Rect(0, 0, width, height), quality, out);
        return out.toByteArray();
    }

    private static byte[] YUV420toNV21(Image image) {
        byte[] nv21;
        // Get the three planes.
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();


        nv21 = new byte[ySize + uSize + vSize];

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        return nv21;
    }

    public void close() {
        inferenceInterface.close();
    }
}
