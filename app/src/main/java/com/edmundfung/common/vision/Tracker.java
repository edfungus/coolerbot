package com.edmundfung.common.vision;

import android.app.Activity;
import android.util.Log;
import android.view.MotionEvent;

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

public class Tracker {
    private static final String TAG = Tracker.class.getSimpleName();
    private static final float closenessThreshold = 0.2f;

    private Activity activity;
    private TapHelper tapHelper;
    private final SnackbarHelper snackbar = new SnackbarHelper();

    private Session session;
    private final List<ColoredAnchor> anchors = new ArrayList<>();
    private static final int anchorCapacity = 5;
    private Frame frame;
    private boolean installRequested;

    public Tracker(Activity a) {
        activity = a;
        installRequested = false;
    }

    public void SetTapHelper(TapHelper th){
        tapHelper = th;
    }

    public void Resume(){
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
        session.pause();
    }

    public boolean IsActive(){
        return session != null;
    }

    public void SetDisplayGeometry(int rotation, int width, int height) {
        session.setDisplayGeometry(rotation, width, height);
    }

    public void SetCameraTextureName(int id){
        session.setCameraTextureName(id);
    }

    public void Update() throws CameraNotAvailableException, NotYetAvailableException, NoSuchElementException {
        frame = session.update();
        removeNextAnchorIfClose(frame.getCamera().getPose());

        if (anchorCapacityFull()) {
            return;
        }

        MotionEvent tap = tapHelper.poll();
        if (tap != null && IsTracking()){
            float[] blobCoords = getBlobCoordinates(frame);
            for (HitResult hit : frame.hitTest(blobCoords[0], blobCoords[1])) {
                if (checkHit(hit)){
                    addAnchor(hit.createAnchor());
                    break;
                }
            }
        }

        return;
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
        return Math.abs(relativeAngle - adjustment);
    }

    private static final int meterLength = 104;
    private static final int fov = 40;
    private static final char blank = '-';
    private static final char target = ':';
    public String GetDirectionMeter() {
        if (anchors.isEmpty()) {
            return "";
        }

        double absoluteAngle = AngleToNextAnchor();
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

    private void removeNextAnchorIfClose(Pose currentPose) {
        if (!anchors.isEmpty() && isAnchorClose(anchors.get(0).anchor, currentPose)) {
            anchors.get(0).anchor.detach();
            anchors.remove(0);
        }
    }

    private boolean isAnchorClose(Anchor a, Pose p) {
        return distanceBetweenPoses(a.getPose(), p) < closenessThreshold;
    }

    private float[] getBlobCoordinates(Frame f) throws NotYetAvailableException, NoSuchElementException {
        BlobFinder bf = new BlobFinder(f);
        List<Blob> blobs = bf.Find();
        Blob biggestBlob = Collections.max(blobs);
        return bf.ScaleCoordsToScreen(biggestBlob.GetCenter()[0], biggestBlob.GetCenter()[1]);
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

    private void addAnchor(Anchor a){
        if (anchorCapacityFull()) {
            return;
        }
        anchors.add(new ColoredAnchor(a));
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
}

