/*
 * Copyright (C) 2026 VoltageOS
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

package com.android.launcher3.quickspace;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import com.android.launcher3.LauncherPrefs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QuickBatteryController {

  private static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000;

  private final Context mContext;
  private final QuickspaceController mController;
  private boolean mRegistered = false;

  public static class BatteryDevice {
    public String name;
    public int level;
    public boolean isAudio;
    public String address;

    public boolean isCharging;

    public BatteryDevice(
        String name, int level, boolean isAudio, String address, boolean isCharging) {
      this.name = name;
      this.level = level;
      this.isAudio = isAudio;
      this.address = address;
      this.isCharging = isCharging;
    }
  }

  private final List<BatteryDevice> mDevices = new ArrayList<>();
  private BatteryDevice mPhoneDevice;
  private final List<BatteryDevice> mBtDevices = new ArrayList<>();
  private String mCachedPhoneName = null;

  private String mCurrentDeviceAddress = "device_phone";
  private int mCurrentIndex = 0;
  private long mLastInteractionTime = 0;
  private final Set<String> mAlertedDevices = new HashSet<>();

  private boolean mInitialStickyReceived = false;
  private boolean mLastPhoneCharging = false;
  private int mLastPhoneLevel = -1;
  private boolean mPhoneFullTriggered = false;
  private boolean mPhoneLowTriggered = false;
  private final Set<String> mKnownBtDevices = new HashSet<>();
  private final Set<String> mLowAlertedBtDevices = new HashSet<>();
  private final java.util.Map<String, Integer> mBtLevels = new java.util.HashMap<>();

  private final BroadcastReceiver mReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          String action = intent.getAction();
          if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            if (!updatePhoneBattery(intent)) return;
          } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                != BluetoothAdapter.STATE_ON) {
              clearBluetoothDevices();
            } else {
              loadConnectedDevices();
            }
          } else if (hasBluetoothPermission()) {
            BluetoothDevice device =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device == null) return;
            try {
              if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                removeBluetoothDevice(device.getAddress());
              } else {
                updateBluetoothDevice(
                    device,
                    intent.getIntExtra(
                        BluetoothDevice.EXTRA_BATTERY_LEVEL, device.getBatteryLevel()));
              }
            } catch (SecurityException ignored) {
              clearBluetoothDevices();
            }
          }
          refreshDeviceList();
        }
      };

  private void ensurePhoneContextualEvent(boolean isCharging, int level) {
    int activeType =
        mController != null
            ? mController.getActiveContextualTypeForSource(QuickEventsController.SOURCE_PHONE)
            : QuickEventsController.CONTEXT_EVENT_NONE;

    if (isCharging && level >= 100) {
      if (activeType != QuickEventsController.CONTEXT_EVENT_BATTERY_FULL) {
        mController.triggerBatteryFullEvent();
      }
      mPhoneFullTriggered = true;
      mPhoneLowTriggered = false;
    } else if (isCharging) {
      if (activeType != QuickEventsController.CONTEXT_EVENT_CHARGING) {
        mController.triggerChargingEvent();
      }
      mPhoneFullTriggered = false;
      mPhoneLowTriggered = false;
    } else if (level <= 15) {
      if (activeType != QuickEventsController.CONTEXT_EVENT_BATTERY_LOW
          || level != mLastPhoneLevel) {
        mController.triggerBatteryLowEvent(level);
      }
      mPhoneFullTriggered = false;
      mPhoneLowTriggered = true;
    } else {
      if (activeType != QuickEventsController.CONTEXT_EVENT_NONE) {
        mController.clearContextualEventForSource(QuickEventsController.SOURCE_PHONE);
      }
      mPhoneFullTriggered = false;
      mPhoneLowTriggered = false;
    }
  }

  private boolean updatePhoneBattery(Intent intent) {
    int rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
    if (rawLevel < 0 || scale <= 0) return false;
    int level = Math.min(100, (int) (100L * rawLevel / scale));
    int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
    boolean isCharging =
        status == BatteryManager.BATTERY_STATUS_CHARGING
            || status == BatteryManager.BATTERY_STATUS_FULL;

    ensurePhoneContextualEvent(isCharging, level);

    mLastPhoneCharging = isCharging;
    mLastPhoneLevel = level;
    mInitialStickyReceived = true;

    if (mPhoneDevice != null
        && mPhoneDevice.level == level
        && mPhoneDevice.isCharging == isCharging) {
      return false;
    }

    String name = getPhoneName();
    mPhoneDevice = new BatteryDevice(name, level, false, "device_phone", isCharging);
    return true;
  }

  private boolean hasBluetoothPermission() {
    return mContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void loadConnectedDevices() {
    clearBluetoothDevices();
    if (!hasBluetoothPermission()) return;
    BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null) return;
    try {
      for (BluetoothDevice device : adapter.getBondedDevices()) {
        if (device.isConnected()) updateBluetoothDevice(device, device.getBatteryLevel());
      }
    } catch (SecurityException ignored) {
      clearBluetoothDevices();
    }
  }

  private void updateBluetoothDevice(BluetoothDevice device, int level) {
    String address = device.getAddress();
    if (TextUtils.isEmpty(address)) return;
    if (level < 0 || level > 100) {
      removeBluetoothDevice(address);
      return;
    }
    String name = device.getName();
    if (TextUtils.isEmpty(name)) name = address;
    BluetoothClass btClass = device.getBluetoothClass();
    boolean audio =
        btClass != null && btClass.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
    mBtDevices.removeIf(existing -> address.equals(existing.address));
    mBtDevices.add(new BatteryDevice(name, level, audio, address, false));
    Integer previous = mBtLevels.put(address, level);
    mKnownBtDevices.add(address);
    if (level <= 20) {
      if (previous == null || previous != level || !mLowAlertedBtDevices.contains(address)) {
        mController.triggerBtBatteryEvent(name, address, level);
      }
      mLowAlertedBtDevices.add(address);
    } else {
      mLowAlertedBtDevices.remove(address);
      mController.clearContextualEventForSource(address);
    }
  }

  private void removeBluetoothDevice(String address) {
    mBtDevices.removeIf(device -> address.equals(device.address));
    mKnownBtDevices.remove(address);
    mLowAlertedBtDevices.remove(address);
    mBtLevels.remove(address);
    mAlertedDevices.remove(address);
    mController.clearContextualEventForSource(address);
  }

  private void clearBluetoothDevices() {
    for (String address : new HashSet<>(mKnownBtDevices)) removeBluetoothDevice(address);
    mBtDevices.clear();
  }

  private void refreshDeviceList() {
    mDevices.clear();
    if (mPhoneDevice != null) {
      mDevices.add(mPhoneDevice);
    }
    mDevices.addAll(mBtDevices);

    updateCurrentIndex();
    mController.notifyListeners();
  }

  private void updateCurrentIndex() {
    if (mDevices.isEmpty()) {
      mCurrentIndex = 0;
      mCurrentDeviceAddress = null;
      return;
    }

    if (SystemClock.elapsedRealtime() - mLastInteractionTime > SESSION_TIMEOUT_MS) {
      if (mPhoneDevice != null) {
        mCurrentDeviceAddress = mPhoneDevice.address;
      } else if (!mDevices.isEmpty()) {
        mCurrentDeviceAddress = mDevices.get(0).address;
      }
    }

    int newIndex = 0;
    if (mCurrentDeviceAddress != null) {
      for (int i = 0; i < mDevices.size(); i++) {
        if (mDevices.get(i).address.equals(mCurrentDeviceAddress)) {
          newIndex = i;
          break;
        }
      }
    }
    mCurrentIndex = newIndex;

    for (int i = 0; i < mDevices.size(); i++) {
      BatteryDevice d = mDevices.get(i);

      if (d.level > 20) {
        mAlertedDevices.remove(d.address);
      }

      if (d.isCharging) {
        mCurrentIndex = i;
        mCurrentDeviceAddress = d.address;
        mLastInteractionTime = SystemClock.elapsedRealtime();
        return;
      }

      if (d.level <= 20 && !mAlertedDevices.contains(d.address)) {
        mCurrentIndex = i;
        mCurrentDeviceAddress = d.address;
        mAlertedDevices.add(d.address);
        mLastInteractionTime = SystemClock.elapsedRealtime();
        return;
      }
    }
  }

  public QuickBatteryController(Context context, QuickspaceController controller) {
    mContext = context;
    mController = controller;
  }

  public void onResume() {
    if (LauncherPrefs.SHOW_QUICKSPACE_BATTERY.get(mContext)) {
      Intent stickyIntent = registerReceiver();
      loadConnectedDevices();
      if (stickyIntent != null) {
        mReceiver.onReceive(mContext, stickyIntent);
      }
    } else {
      unRegisterReceiver();
      clearData();
    }
  }

  public void onPause() {
    unRegisterReceiver();
  }

  private Intent registerReceiver() {
    if (mRegistered) return null;
    IntentFilter filter = new IntentFilter();
    filter.addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED);
    filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
    filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
    filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
    filter.addAction(Intent.ACTION_BATTERY_CHANGED);
    Intent sticky = null;
    try {
      sticky = mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
      mRegistered = true;
    } catch (Exception e) {
      mRegistered = false;
    }
    return sticky;
  }

  private void unRegisterReceiver() {
    if (!mRegistered) return;
    try {
      mContext.unregisterReceiver(mReceiver);
    } catch (Exception e) {
      // ignore
    }
    mRegistered = false;
  }

  private String getPhoneName() {
    if (mCachedPhoneName != null) {
      return mCachedPhoneName;
    }

    String name = SystemProperties.get("ro.product.marketname", "");
    if (TextUtils.isEmpty(name)) {
      name = Settings.Global.getString(mContext.getContentResolver(), Settings.Global.DEVICE_NAME);
    }
    if (TextUtils.isEmpty(name)) {
      name = android.os.Build.MODEL;
    }

    mCachedPhoneName = name;
    return name;
  }

  private void clearData() {
    clearBluetoothDevices();
    mPhoneDevice = null;
    mCurrentIndex = 0;
    mDevices.clear();
    mBtDevices.clear();
    mKnownBtDevices.clear();
    mLowAlertedBtDevices.clear();
    mBtLevels.clear();
    mAlertedDevices.clear();
    mPhoneFullTriggered = false;
    mPhoneLowTriggered = false;
    mInitialStickyReceived = false;
    mLastPhoneLevel = -1;
    mCurrentDeviceAddress = null;
    if (mController != null) {
      mController.clearContextualEventForSource(QuickEventsController.SOURCE_PHONE);
    }
    mController.notifyListeners();
  }

  /** Cycles to the next device in the list. Called when the user taps the battery pill. */
  public void advanceIndex() {
    if (mDevices.isEmpty()) return;
    mCurrentIndex = (mCurrentIndex + 1) % mDevices.size();
    mCurrentDeviceAddress = mDevices.get(mCurrentIndex).address;
    mLastInteractionTime = SystemClock.elapsedRealtime();
  }

  public BatteryDevice getCurrentDevice() {
    if (mDevices.isEmpty()) return null;
    if (mCurrentIndex >= mDevices.size()) mCurrentIndex = 0;
    return mDevices.get(mCurrentIndex);
  }

  public int getDeviceCount() {
    return mDevices.size();
  }

  public int getCurrentIndex() {
    return mCurrentIndex;
  }

  public String getDeviceName() {
    BatteryDevice d = getCurrentDevice();
    return d != null ? d.name : null;
  }

  public int getBatteryLevel() {
    BatteryDevice d = getCurrentDevice();
    return d != null ? d.level : -1;
  }

  public boolean isAudioDevice() {
    BatteryDevice d = getCurrentDevice();
    return d != null ? d.isAudio : false;
  }

  public boolean isCharging() {
    BatteryDevice d = getCurrentDevice();
    return d != null ? d.isCharging : false;
  }

  public String getCurrentDeviceAddress() {
    BatteryDevice d = getCurrentDevice();
    return d != null ? d.address : null;
  }

  public void launchBatterySettings() {
    BatteryDevice d = getCurrentDevice();
    if (d == null) return;

    try {
      Intent intent;
      if (d.address.equals("device_phone")) {
        intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
      } else {
        intent = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
      }

      if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
      }
    } catch (Exception e) {
    }
  }
}
