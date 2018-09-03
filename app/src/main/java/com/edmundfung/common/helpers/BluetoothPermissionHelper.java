package com.edmundfung.common.helpers;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

public class BluetoothPermissionHelper {
    public final static int REQUEST_ENABLE_BT = 1;
    private static final String BLUETOOTH_PERMISSION = Manifest.permission.BLUETOOTH;
//    private static final String BLUETOOTH_ADMIN_PERMISSION = Manifest.permission.BLUETOOTH_ADMIN;
//    private static final String LOCATION_PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION;


    /** Check to see we have the necessary permissions for this app. */
    public static boolean hasBluetoothPermissions(Activity activity) {
        return ContextCompat.checkSelfPermission(activity, BLUETOOTH_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;// &&
//                ContextCompat.checkSelfPermission(activity, BLUETOOTH_ADMIN_PERMISSION)
//                    == PackageManager.PERMISSION_GRANTED &&
//                ContextCompat.checkSelfPermission(activity, LOCATION_PERMISSION)
//                    == PackageManager.PERMISSION_GRANTED;
    }

    /** Check to see we have the necessary permissions for this app, and ask for them if we don't. */
    public static void requestBluetoothPermissions(Activity activity) {
        ActivityCompat.requestPermissions(
                activity, new String[] {BLUETOOTH_PERMISSION}, REQUEST_ENABLE_BT);
    }

    /** Check to see if we need to show the rationale for this permission. */
    public static boolean shouldShowRequestPermissionRationale(Activity activity) {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, BLUETOOTH_PERMISSION);
    }

    /** Launch Application Setting to grant permission. */
    public static void launchPermissionSettings(Activity activity) {
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
        activity.startActivity(intent);
    }
}
