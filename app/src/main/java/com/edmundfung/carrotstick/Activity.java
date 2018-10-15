/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.edmundfung.carrotstick;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.edmundfung.common.controller.Bot;
import com.edmundfung.common.helpers.BluetoothPermissionHelper;
import com.edmundfung.common.helpers.CameraPermissionHelper;
import com.edmundfung.common.helpers.DisplayRotationHelper;
import com.edmundfung.common.helpers.FullScreenHelper;
import com.edmundfung.common.helpers.TapHelper;
import com.edmundfung.common.rendering.BackgroundRenderer;
import com.edmundfung.common.rendering.ObjectRenderer;
import com.edmundfung.common.rendering.PlaneRenderer;
import com.edmundfung.common.rendering.PointCloudRenderer;
import com.edmundfung.common.vision.ColoredAnchor;
import com.edmundfung.common.vision.Human;
import com.edmundfung.common.vision.TensorFlowPoseDetector;
import com.edmundfung.common.vision.Tracker;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.TrackingState;
import java.io.IOException;
import java.util.Locale;
import java.util.Vector;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3d model of the Android robot.
 */
public class Activity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = Activity.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;
  private DisplayRotationHelper displayRotationHelper;
  private TapHelper tapHelper;
  private TextView mainText;

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final ObjectRenderer virtualObject = new ObjectRenderer();
  private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
  private final PlaneRenderer planeRenderer = new PlaneRenderer();
  private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

  private final Bot bot = new Bot(this);
  private Tracker tracker;

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] anchorMatrix = new float[16];

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    // Set up tap listener.
    tapHelper = new TapHelper(/*context=*/ this);
    surfaceView.setOnTouchListener(tapHelper);
    tracker = new Tracker(this, getAssets());
    tracker.SetTapHelper(tapHelper);

    // Set up renderer.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

    mainText = findViewById(R.id.mainText);

  }

  @Override
  protected void onResume() {
    super.onResume();

    bot.Connect();
    tracker.Resume();

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (tracker.IsActive()) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      tracker.Pause();
    }
    bot.StopScan();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    bot.StopScan();
  }

  @Override
  public void onStop() {
    super.onStop();
    bot.StopScan();
  }


  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
    if (!BluetoothPermissionHelper.hasBluetoothPermissions(this)) {
      Toast.makeText(this, "Bluetooth permissions is needed to run this application", Toast.LENGTH_LONG)
              .show();
      if (!BluetoothPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        BluetoothPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(/*context=*/ this);
      planeRenderer.createOnGlThread(/*context=*/ this, "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(/*context=*/ this);

      virtualObject.createOnGlThread(/*context=*/ this, "models/andy.obj", "models/andy.png");
      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

      virtualObjectShadow.createOnGlThread(
          /*context=*/ this, "models/andy_shadow.obj", "models/andy_shadow.png");
      virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow);
      virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);

      tracker.SetCameraTextureName(backgroundRenderer.getTextureId());
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (!tracker.IsActive()) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(tracker);

    try {
      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      tracker.Update();
      tracker.Track();
      Frame frame = tracker.GetFrame();

      // Print distance
      runOnUiThread(
      new Runnable() {
        @Override
        public void run() {
            if(tracker.IsMoving() && tracker.IsTracking()){
              String msg = generateRobotControl(tracker);
              bot.SendRaw(msg);
            } else {
              bot.SendRaw(encodeMessage(0,0,0,0));
            }
            String log = String.format(Locale.ENGLISH,"%b \n%d \n%.2f \n%.2f", tracker.IsMoving(), tracker.GetAnchors().size(), tracker.GetNextScore(), tracker.AngleToNextAnchor());
            mainText.setText(log);
        }
      });

      // Draw background.
      backgroundRenderer.draw(frame);

      // Draw bitmap here

      // If not tracking, don't draw 3d objects.
      if (tracker.IsTracking()) {
        drawARObjects(frame);
      }
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  private String generateRobotControl(Tracker t) {
    // Format is csv
    // [L direction][L speed][R direction][R speed]
    // direction: 0 = stop, 1 = forward, 2 = backwards
    // speed: 0 to 255
    if(t.GetAnchors().isEmpty()) {
      return encodeMessage(0,0,0,0);
    }
    if(t.GetAnchors().size() == 1 && tracker.DistanceToNextAnchor() < .3) {
      return encodeMessage(0,0,0,0);
    }
    double offAngle = tracker.AngleToNextAnchor();
    if(Math.abs(offAngle) > 10){
      int speed = (int) Math.abs(offAngle) + 60;
      if (speed > 150) {
        speed = 150;
      }
      if(offAngle > 0){
        return encodeMessage(1,speed,2,speed);
      } else {
        return encodeMessage(2,speed,1,speed);
      }
    }
    return encodeMessage(1,125,1,125);
  }

  private String encodeMessage(int Rd, int Rs, int Ld, int Ls) {
    return String.format(Locale.ENGLISH, "%01d%03d%01d%03d", Rd, Rs, Ld, Ls);
  }

  private void drawARObjects(Frame frame){
    // Get projection matrix.
    float[] projmtx = new float[16];
    frame.getCamera().getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

    // Get camera matrix and draw.
    float[] viewmtx = new float[16];
    frame.getCamera().getViewMatrix(viewmtx, 0);

    // Compute lighting from average intensity of the image.
    // The first three components are color scaling factors.
    // The last one is the average pixel intensity in gamma space.
    final float[] colorCorrectionRgba = new float[4];
    frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

    // Visualize tracked points.
    PointCloud pointCloud = frame.acquirePointCloud();
    pointCloudRenderer.update(pointCloud);
    pointCloudRenderer.draw(viewmtx, projmtx);

    // Application is responsible for releasing the point cloud resources after
    // using it.
    pointCloud.release();

    // Visualize planes.
    planeRenderer.drawPlanes(
            tracker.GetAllTrackables(Plane.class), frame.getCamera().getDisplayOrientedPose(), projmtx);

    // Visualize anchors created by touch.
    float scaleFactor = 1.0f;
    for (ColoredAnchor coloredAnchor : tracker.GetAnchors()) {
      if (coloredAnchor.anchor.getTrackingState() != TrackingState.TRACKING) {
        continue;
      }
      // Get the current pose of an Anchor in world space. The Anchor pose is updated
      // during calls to session.update() as ARCore refines its estimate of the world.
      coloredAnchor.anchor.getPose().toMatrix(anchorMatrix, 0);

      // Update and draw the model and its shadow.
      virtualObject.updateModelMatrix(anchorMatrix, scaleFactor);
      virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor);
      virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color);
      virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color);
    }
  }
}
