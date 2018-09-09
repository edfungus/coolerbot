package com.edmundfung.common.controller;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;

import com.edmundfung.common.helpers.BluetoothPermissionHelper;
import com.edmundfung.common.helpers.SnackbarHelper;

import java.util.UUID;

public class Bot {
    private static final UUID serviceUUID =  UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID rxCharacteristicUUID =  UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static String botMACAddress = "30:AE:A4:73:B2:26";

    private Activity activity;
    private final SnackbarHelper snackbar = new SnackbarHelper();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getDevice().getAddress().equals(botMACAddress)) {
                scanner.stopScan(scanCallback);
                connectGatt(result.getDevice());
            }
        }
    };
    private BluetoothGattCharacteristic rxCharacteristic;


    public Bot(Activity a) {
        activity = a;
    }

    public Bot(Activity a, String macAddress) {
        this(a);
        botMACAddress = macAddress;
    }

    public void Connect() {
        snackbar.showMessageWithDismiss(activity, "waiting for bluetooth connection ...");
        checkPermissions();
        scanner.startScan(scanCallback);
    }

    public void StopScan(){
        scanner.stopScan(scanCallback);
    }

    public void SendRaw(String s) {
        if (gatt == null) {
            return;
        }
        if (rxCharacteristic == null) {
            BluetoothGattService service = gatt.getService(serviceUUID);
            if (service == null) {
                return;
            }
            rxCharacteristic = service.getCharacteristic(rxCharacteristicUUID);
            if(rxCharacteristic == null) {
                return;
            }
        }
        rxCharacteristic.setValue(s);
        gatt.writeCharacteristic(rxCharacteristic);
    }

    private void checkPermissions(){
        // Check permissions
        if (!BluetoothPermissionHelper.hasBluetoothPermissions(activity)) {
            BluetoothPermissionHelper.requestBluetoothPermissions(activity);
        }

        BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, BluetoothPermissionHelper.REQUEST_ENABLE_BT);
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
    }

    private void connectGatt(BluetoothDevice device) {
        gatt = device.connectGatt(activity, true,
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    super.onConnectionStateChange(gatt, status, newState);
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        snackbar.hide(activity);
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        gatt.discoverServices();
                    }
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        snackbar.showMessageWithDismiss(activity,"bluetooth disconnected!");
                    }
                }
            }
        );
    }
}
