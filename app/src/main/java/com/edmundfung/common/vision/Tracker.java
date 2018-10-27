package com.edmundfung.common.vision;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.Image;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.edmundfung.carrotstick.R;
import com.edmundfung.common.helpers.CameraPermissionHelper;
import com.edmundfung.common.helpers.SnackbarHelper;
import com.edmundfung.common.helpers.TapHelper;
import com.edmundfung.common.rendering.PlaneRenderer;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.DeadlineExceededException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Vector;

public class Tracker {
    private static final String TAG = Tracker.class.getSimpleName();
    private static final float closenessThreshold = 0.2f;

    private Activity activity;
    private TapHelper tapHelper;
    private final SnackbarHelper snackbar = new SnackbarHelper();

    private Session session;
    private final List<ColoredAnchor> anchors = new ArrayList<>();
    private static final int anchorCapacity = 20;
    private Frame frame;
    private boolean installRequested;
    private boolean isMoving = false;

    // tensorflow
    private final TensorFlowPoseDetector poseDetector;
    private boolean computingDetection = false;
    private Handler handler;
    private HandlerThread handlerThread;
    private ImageView trackingOverlay;
    private Bitmap copyBitmap;
    private static final int actualBitmapSize = 1439;
    private static final int bitmapHorzOffset = (int) Math.rint((actualBitmapSize - 1080) / 2.0);


    public Tracker(Activity a, final AssetManager assetManager) {
        activity = a;
        installRequested = false;
        poseDetector = new TensorFlowPoseDetector(assetManager);

        FrameLayout root = (FrameLayout)a.findViewById(R.id.root);
        ImageView img = new ImageView(a.getBaseContext());
//        img.setBackgroundColor(Color.MAGENTA);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(actualBitmapSize, actualBitmapSize);
        params.leftMargin = -1 * bitmapHorzOffset;
        params.topMargin  = 0;
        root.addView(img, params);
        trackingOverlay = img;
        trackingOverlay.setImageAlpha(80);
    }

    public void SetTapHelper(TapHelper th){
        tapHelper = th;
    }

    public void Resume(){
        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        if (session == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(activity)) {
                    CameraPermissionHelper.requestCameraPermission(activity);
                    return;
                }

                // Create the session.
                session = new Session(/* context= */ activity);

            } catch (UnavailableArcoreNotInstalledException
                    | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (UnavailableDeviceNotCompatibleException e) {
                message = "This device does not support AR";
                exception = e;
            } catch (Exception e) {
                message = "Failed to create AR session";
                exception = e;
            }

            if (message != null) {
                snackbar.showError(activity, message);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            // In some cases (such as another camera app launching) the camera may be given to
            // a different app instead. Handle this properly by showing a message and recreate the
            // session at the next iteration.
            snackbar.showError(activity, "Camera not available. Please restart the app.");
            session = null;
            return;
        }
    }

    public void Pause() {
        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
            // oops
        }

        session.pause();
    }

    public boolean IsActive(){
        return session != null;
    }

    public boolean IsMoving(){
        return isMoving;
    }

    public void SetDisplayGeometry(int rotation, int width, int height) {
        session.setDisplayGeometry(rotation, width, height);
    }

    public void SetCameraTextureName(int id){
        session.setCameraTextureName(id);
    }

    public void Update() throws CameraNotAvailableException {
        frame = session.update();
    }

    public void Track() throws NoSuchElementException {
        removeNextAnchorIfClose(frame.getCamera().getPose());

        if (anchorCapacityFull() || !IsTracking()) {
            return;
        }

        activity.runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    trackingOverlay.setImageBitmap(copyBitmap);
                }
            }
        );

        if (computingDetection) {
            return;
        }
        computingDetection = true;
        runInBackground(
            new Runnable() {
                @Override
                public void run() {
                    try {
                        Bitmap bitmap = poseDetector.GetBitmap(frame);
                        if(bitmap == null) {
                            return;
                        }
                        Vector<Human> humans = poseDetector.FindHumans(bitmap);
                        Log.e("EDMUND tensorflow human count", String.valueOf(humans.size()));

                        copyBitmap = Bitmap.createBitmap(bitmap);
                        final Canvas canvas = new Canvas(copyBitmap);
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(2.0f);
                        for (final Human human : humans) {
                            drawAllPoints(canvas, paint, human);
                            drawAllConnections(canvas, paint, human);
                        }
                        findStanding(canvas, humans);
                    } catch(NotYetAvailableException | DeadlineExceededException e) {
                        // haha...
                    }
                    computingDetection = false;
                }
            }
        );

//        if(checkTaps() || (System.nanoTime() > nextTrackTime && isMoving)) {
//            try {
//                ArrayList<Float> blobData = getBlob(frame);
//                for (HitResult hit : frame.hitTest(blobData.get(0), blobData.get(1))) {
//                    if (checkHit(hit)){
//                        addAnchor(hit.createAnchor(), blobData.get(2));
//                        break;
//                    }
//                }
//                nextTrackTime = System.nanoTime() + timeTillNextTrack;
//            } catch (NotYetAvailableException | NoSuchElementException e) {
//                // hahaha ... ignore :P
//            }
//         }
    }

    public Frame GetFrame() {
        return frame;
    }

    public boolean IsTracking(){
        if (frame == null || frame.getCamera() == null) {
            return false;
        }
        return frame.getCamera().getTrackingState() == TrackingState.TRACKING;
    }

    public float DistanceToNextAnchor() {
        if (anchors.isEmpty() || frame == null || frame.getCamera() == null){
            return 0.0f;
        }
        return distanceBetweenPoses(anchors.get(0).anchor.getPose(), frame.getCamera().getPose());
    }

    public List<ColoredAnchor> GetAnchors() {
        return anchors;
    }

    public double AngleToNextAnchor() {
        if (anchors.isEmpty()) {
            return 0f;
        }
        Pose anchor = anchors.get(0).anchor.getPose();
        Pose camera = frame.getCamera().getPose();

        // NOTE:
        // x: left and right
        // y: up and down
        // z: back and forth
        // atan2 gives angles like: -180 0 +180 where 0 is horizontal, + is clockwise and - is counter-
        // clockwise
        double relativeAngle = Math.toDegrees(Math.atan2((camera.tz() - anchor.tz()), (camera.tx() - anchor.tx())));
        double adjustment = quaternionToAngleY(camera);
        // Make it all clockwise
        double angle = relativeAngle - adjustment - 90;
        if (angle > 180) {
            angle = angle - 360;
        }
        if (angle < -180) {
            angle = angle + 360;
        }
        return angle;
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    private void findStanding(Canvas canvas, Vector<Human> humans){
        int scaleFactor = 8;
        for (Human human : humans) {
            if (human.coords_index_assigned[9] && human.coords_index_assigned[10] && human.coords_index_assigned[12] && human.coords_index_assigned[13]) {
                // also scale coords to real size
                int rightAnkleY = (human.parts_coords[10][0] * scaleFactor * actualBitmapSize) / 368;
                int rightAnkleX = (human.parts_coords[10][1] * scaleFactor * actualBitmapSize) / 368;
                int rightKneeY = (human.parts_coords[9][0] * scaleFactor * actualBitmapSize) / 368;
                int rightKneeX = (human.parts_coords[9][1] * scaleFactor * actualBitmapSize) / 368;
                int leftAnkleY = (human.parts_coords[13][0] * scaleFactor * actualBitmapSize) / 368;
                int leftAnkleX = (human.parts_coords[13][1] * scaleFactor * actualBitmapSize) / 368;
                int leftKneeY = (human.parts_coords[12][0] * scaleFactor * actualBitmapSize) / 368;
                int leftKneeX = (human.parts_coords[12][1] * scaleFactor * actualBitmapSize) / 368;
                int middleX = (rightAnkleX + leftAnkleX) / 2;
                int middleY = rightAnkleY;
                int legLength = (int) distanceBetweenPoints(rightAnkleX, rightAnkleY, rightKneeX, rightKneeY);
                if (leftAnkleY > rightAnkleY) {
                    middleY = leftAnkleY;
                    legLength = (int) distanceBetweenPoints(leftAnkleX, leftAnkleY, leftKneeX, leftKneeY);
                }
                // Our horz axis is larger than our AR horz
                middleX = middleX - bitmapHorzOffset;

                // Account for feet location from ankle location and lower leg height
                middleY = middleY + (int) (legLength * 0.15);

                final Paint paint = new Paint();
                paint.setColor(Color.BLUE);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3.0f);
                canvas.drawCircle((middleX + bitmapHorzOffset) * (float) (368.0 / actualBitmapSize), middleY * (float) (368.0 /  actualBitmapSize), 2, paint);

                // (X is 1080 and Y is 1920)
                Log.e("EDMUND tensorflow human coordinates", String.format("x: %d y: %d leg: %d offset: %d", middleX, middleY, legLength, (int) (legLength * 0.15)));
                for (HitResult hit : frame.hitTest(middleX, middleY)) {
                    if (checkHit(hit)){
                        addAnchor(hit.createAnchor(), 0f);
                        Log.e("EDMUND tensorflow human", "HIT");
                        break;
                    }
                    Log.e("EDMUND tensorflow human miss plane", "");
                }
            }
        }
    }

    private static final int meterLength = 104;
    private static final int fov = 40;
    private static final char blank = '-';
    private static final char target = ':';
    public String GetDirectionMeter() {
        if (anchors.isEmpty()) {
            return "";
        }

        double absoluteAngle = Math.abs(AngleToNextAnchor() + 90);
        // We don't care about front vs back right now so flip everything to the front
        if (absoluteAngle > 180) {
            absoluteAngle = 360 - absoluteAngle;
        }

        // Convert the angle to something we can print
        // Identify whether absoluteAngle is in our FOV
        double angleInFOV = absoluteAngle;
        int fovLowerBound = 90 - fov/2;
        int fovUpperBound = 90 + fov/2;
        // If out of fov on the left
        if (angleInFOV < fovLowerBound) {
            angleInFOV = fovLowerBound;
        }
        // If out of fov on the right
        if (angleInFOV > fovUpperBound) {
            angleInFOV = fovUpperBound;
        }
        // Squash to range: 0 to fov
        angleInFOV-=fovLowerBound;
        // Convert to index on meter
        int meterIndex = ((int) Math.round(angleInFOV) * meterLength) / fov;
        if (meterIndex < 0) {
            meterIndex = 0;
        }
        if (meterIndex > meterLength) {
            meterIndex = meterLength;
        }

        // Create meter string
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < meterLength; i++) {
            if (i == meterIndex - 1 || i == meterIndex || i == meterIndex + 1) {
                b.append(target);
            } else {
                b.append(blank);
            }
        }
        return b.toString();
    }

    public <T extends Trackable> Collection<T> GetAllTrackables(Class<T> type){
        return session.getAllTrackables(type);
    }

    public float GetNextScore() {
        if (!anchors.isEmpty()) {
            return anchors.get(0).score;
        }
        return 0f;
    }

    // checkTaps check for tap operations. It returns true if we should look for object
    private boolean checkTaps() {
        if (tapHelper.pollDouble() != null){
            Log.d("EDMUND", "double");
            isMoving = !isMoving;
            return false;
        }
        if (tapHelper.pollSingle() != null){
            Log.d("EDMUND", "single");
            if (isMoving) {
                if (!anchors.isEmpty()){
                    anchors.get(0).anchor.detach();
                    anchors.remove(0);
                }
            } else {
                return true;
            }
        }
        if (tapHelper.pollLong() != null){
            Log.d("EDMUND", "long");
            if (!isMoving) {
                anchors.clear();
            }
        }
        return false;
    }

    private void removeNextAnchorIfClose(Pose currentPose) {
        if (!anchors.isEmpty() && isAnchorClose(anchors.get(0).anchor, currentPose)) {
            anchors.get(0).anchor.detach();
            anchors.remove(0);
        }
    }

    private boolean isAnchorClose(Anchor a, Pose p) {
        return distanceBetweenPoses(a.getPose(), p) < closenessThreshold;
    }

    private ArrayList<Float> getBlob(Frame f) throws NotYetAvailableException, NoSuchElementException {
        BlobFinder bf = new BlobFinder(f);
        List<Blob> blobs = bf.Find();
        Blob biggestBlob = Collections.max(blobs);
        ArrayList<Float> results = bf.ScaleCoordsToScreen(biggestBlob.GetCenter()[0], biggestBlob.GetCenter()[1]);
        results.add(biggestBlob.GetScore());
        return results;
    }

    // checkHit ensures that the hit was on a plane
    private boolean checkHit(HitResult hit) {
        Trackable trackable = hit.getTrackable();
        return trackable instanceof Plane
                && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), frame.getCamera().getPose()) > 0);
    }

    private boolean anchorCapacityFull(){
        return anchors.size() >= anchorCapacity;
    }

    private void addAnchor(Anchor a, float s){
        if (anchorCapacityFull()) {
            return;
        }
        if (anchors.size() != 0 && distanceBetweenPoses(anchors.get(anchors.size() - 1).anchor.getPose(), a.getPose()) < .005) {
            return;
        }
        anchors.add(new ColoredAnchor(a, s));
    }

    // quaternionToAngleY takes a pose and returns the angle from origin in the qy direction
    private double quaternionToAngleY(Pose p) {
        double t = 2.0 * (p.qw() * p.qy() - p.qz() * p.qx());
        if(t > 1) {
            t = 1;
        } else if(t < -1){
            t = -1;
        }
        double angle = Math.toDegrees(Math.asin(t));
        if (p.qy() <= -0.70) {
            if (angle < 0) {
                angle = -180 - angle;
            } else {
                angle = 180 - angle;
            }
        }
        return -1 * angle;
    }

    private float distanceBetweenPoses(Pose p1, Pose p2) {
        float dx = p1.tx() - p2.tx();
        float dy = p1.ty() - p2.ty();
        float dz = p1.tz() - p2.tz();
        return (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
    }

    private double distanceBetweenPoints(int x1, int y1, int x2, int y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    private void drawAllPoints(Canvas canvas, Paint paint, Human human) {
        final int points[][] = human.parts_coords;
        for (int i = 0; i < points.length; i++) {
            canvas.drawCircle((float) points[i][1]*drawScale, (float) points[i][0]*drawScale, 1, paint);
//            Log.e("EDMUND", String.format("%d: %d, %d", i,points[i][1]*drawScale, points[i][0]*drawScale));
        }
    }

    private void drawAllConnections(Canvas canvas, Paint paint, Human human) {
        // face
        drawConnection(canvas, paint, human, 0,1);
        drawConnection(canvas, paint, human, 0,14);
        drawConnection(canvas, paint, human, 14,16);
        drawConnection(canvas, paint, human, 0,15);
        drawConnection(canvas, paint, human, 15,17);

        // right arm
        drawConnection(canvas, paint, human, 2,3);
        drawConnection(canvas, paint, human, 3,4);

        //left arm
        drawConnection(canvas, paint, human, 5,6);
        drawConnection(canvas, paint, human, 6,7);

        // right leg
        drawConnection(canvas, paint, human, 8,9);
        drawConnection(canvas, paint, human, 9,10);

        // left leg
        drawConnection(canvas, paint, human, 11,12);
        drawConnection(canvas, paint, human, 12,13);

        // body
        drawConnection(canvas, paint, human, 1,2);
        drawConnection(canvas, paint, human, 1,5);
        drawConnection(canvas, paint, human, 2,8);
        drawConnection(canvas, paint, human, 5,11);
        drawConnection(canvas, paint, human, 8,11);
    }

    private final int drawScale = 8;
    private void drawConnection(Canvas canvas, Paint paint, Human human, int a, int b) {
        if (human.coords_index_assigned[a] && human.coords_index_assigned[b]) {
            canvas.drawLine((float) human.parts_coords[a][1]*drawScale, (float) human.parts_coords[a][0]*drawScale, (float) human.parts_coords[b][1]*drawScale, (float) human.parts_coords[b][0]*drawScale, paint);
        }
    }
}


