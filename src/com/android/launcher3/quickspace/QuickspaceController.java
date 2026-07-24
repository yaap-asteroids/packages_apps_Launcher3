/*
 * Copyright (C) 2021-2025 crDroid Android Project
 * Copyright (C) 2025-2026 VoltageOS
 * Copyright (C) 2026 YAAP
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

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.MainThread;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.quickspace.weather.OmniJawsClient;
import com.android.launcher3.util.MSMHProxy;
import com.android.launcher3.util.MediaSessionManagerHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Per-view controller. Mutable state and subscriptions are owned by the main thread. */
@MainThread
public class QuickspaceController
    implements OmniJawsClient.OmniJawsObserver, MediaSessionManagerHelper.MediaMetadataListener {
  private static final String TAG = "QuickspaceController";
  private static final long PSA_UPDATE_DELAY_MS = 3 * 60 * 1000;
  private final Context mContext;
  // Never remove callbacks from Launcher's shared MAIN_EXECUTOR handler.
  private final Handler mHandler;
  private final List<OnDataListener> mListeners = new ArrayList<>();
  private final QuickEventsController mEventsController;
  private final QuickBatteryController mBatteryController;
  private final QuickCalendarController mCalendarController;
  private final Map<String, Integer> mConditionMap = new LinkedHashMap<>();
  private OmniJawsClient mWeatherClient;
  private OmniJawsClient.WeatherInfo mWeatherInfo;
  private Drawable mConditionImage;
  private boolean mOmniJawsEnabled;
  private boolean mMediaInitialized;
  private boolean mActive;
  private boolean mDestroyed;
  private boolean mWeatherQueryPending;
  private boolean mWeatherRefreshRequested;
  private int mGeneration;
  private long mLastPsaUpdate;

  private final Runnable mNotify = this::dispatchData;

  private void dispatchData() {
    if (mDestroyed || !mActive) return;
    for (OnDataListener listener : new ArrayList<>(mListeners)) listener.onDataUpdated();
  }

  private final Runnable mMediaUpdate = this::updateMediaInternal;

  private void updateMediaInternal() {
    if (mDestroyed || !mActive || !mMediaInitialized) return;
    MediaSessionManagerHelper helper = MSMHProxy.INSTANCE(mContext);
    MediaMetadata metadata = helper.getCurrentMediaMetadata();
    boolean playing = helper.isMediaPlaying() && metadata != null;
    mEventsController.setMediaInfo(
        playing ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE) : null,
        playing ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : null,
        playing);
    mEventsController.updateQuickEvents();
    notifyListeners();
  }

  private final Runnable mPsaUpdate =
      new Runnable() {
        @Override
        public void run() {
          if (!mActive || mDestroyed) return;
          mEventsController.updatePsonality();
          mLastPsaUpdate = SystemClock.uptimeMillis();
          notifyListeners();
          if (LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.get(mContext)) {
            mHandler.postDelayed(this, PSA_UPDATE_DELAY_MS);
          }
        }
      };
  private final BroadcastReceiver mTimeReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (!mActive) return;
          mEventsController.updateQuickEvents();
          notifyListeners();
        }
      };

  public interface OnDataListener {
    void onDataUpdated();
  }

  public QuickspaceController(Context context) {
    mContext = context;
    mHandler = new Handler(context.getMainLooper());
    mEventsController = new QuickEventsController(context);
    mEventsController.initQuickEvents();
    mBatteryController = new QuickBatteryController(context, this);
    mCalendarController = new QuickCalendarController(context, this::notifyListeners);
    mConditionMap.put("clouds", R.string.quick_event_weather_clouds);
    mConditionMap.put("rain", R.string.quick_event_weather_rain);
    mConditionMap.put("clear", R.string.quick_event_weather_clear);
    mConditionMap.put("storm", R.string.quick_event_weather_storm);
    mConditionMap.put("snow", R.string.quick_event_weather_snow);
    mConditionMap.put("wind", R.string.quick_event_weather_wind);
    mConditionMap.put("mist", R.string.quick_event_weather_mist);
  }

  public void addListener(OnDataListener listener) {
    if (mDestroyed || listener == null || mListeners.contains(listener)) return;
    mListeners.add(listener);
    if (!mActive) start();
    listener.onDataUpdated();
  }

  public void removeListener(OnDataListener listener) {
    mListeners.remove(listener);
    if (mListeners.isEmpty()) stop();
  }

  private void start() {
    mActive = true;
    mGeneration++;
    mEventsController.initQuickEvents();
    IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
    filter.addAction(Intent.ACTION_TIME_CHANGED);
    filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    mContext.registerReceiver(mTimeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    if (LauncherPrefs.SHOW_QUICKSPACE_WEATHER.get(mContext)) {
      try {
        mWeatherClient = OmniJawsClient.get();
        mWeatherClient.addObserver(mContext, this);
        queryAndUpdateWeather();
      } catch (RuntimeException e) {
        Log.w(TAG, "Weather provider unavailable", e);
        mWeatherClient = null;
      }
    }
    if (LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.get(mContext)) {
      mMediaInitialized = true;
      MSMHProxy.INSTANCE(mContext).addMediaMetadataListener(this);
      updateMediaController();
    }
    mBatteryController.onResume();
    mCalendarController.onResume();
    if (LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.get(mContext)) {
      long delay = Math.max(0, PSA_UPDATE_DELAY_MS - (SystemClock.uptimeMillis() - mLastPsaUpdate));
      mHandler.postDelayed(mPsaUpdate, mLastPsaUpdate == 0 ? 0 : delay);
    }
  }

  private void stop() {
    if (!mActive) return;
    mActive = false;
    mGeneration++;
    mHandler.removeCallbacksAndMessages(null);
    mContext.unregisterReceiver(mTimeReceiver);
    if (mWeatherClient != null) mWeatherClient.removeObserver(mContext, this);
    if (mMediaInitialized) MSMHProxy.INSTANCE(mContext).removeMediaMetadataListener(this);
    mMediaInitialized = false;
    mBatteryController.onPause();
    mCalendarController.onPause();
  }

  public void onDestroy() {
    if (mDestroyed) return;
    stop();
    mDestroyed = true;
    mCalendarController.onDestroy();
    mListeners.clear();
    mWeatherInfo = null;
    mConditionImage = null;
  }

  public boolean isQuickEvent() {
    return mEventsController.isQuickEvent();
  }

  public QuickEventsController getEventController() {
    return mEventsController;
  }

  public QuickBatteryController getBatteryController() {
    return mBatteryController;
  }

  public QuickCalendarController getCalendarController() {
    return mCalendarController;
  }

  public boolean isWeatherAvailable() {
    return mOmniJawsEnabled && mWeatherInfo != null;
  }

  public Drawable getWeatherIcon() {
    return mConditionImage;
  }

  public String getWeatherTemp() {
    if (mWeatherInfo == null) return null;
    StringBuilder text = new StringBuilder();
    if (LauncherPrefs.SHOW_QUICKSPACE_WEATHER_CITY.get(mContext) && mWeatherInfo.city != null) {
      text.append(mWeatherInfo.city).append(' ');
    }
    text.append(mWeatherInfo.temp).append(mWeatherInfo.tempUnits);
    if (LauncherPrefs.SHOW_QUICKSPACE_WEATHER_TEXT.get(mContext)
        && mWeatherInfo.condition != null) {
      text.append(" • ").append(getConditionText(mWeatherInfo.condition));
    }
    return text.toString();
  }

  public String getWeatherTemperature() {
    return mWeatherInfo == null ? null : mWeatherInfo.temp + mWeatherInfo.tempUnits;
  }

  public String getWeatherCondition() {
    return mWeatherInfo == null ? null : getConditionText(mWeatherInfo.condition);
  }

  public String getWeatherStateKey() {
    return mWeatherInfo == null
        ? null
        : mWeatherInfo.city
            + "|"
            + mWeatherInfo.temp
            + "|"
            + mWeatherInfo.tempUnits
            + "|"
            + mWeatherInfo.conditionCode
            + "|"
            + mWeatherInfo.condition;
  }

  private String getConditionText(String input) {
    if (input == null || input.isEmpty()) return "";
    String lower = input.toLowerCase(Locale.ROOT);
    Locale locale = mContext.getResources().getConfiguration().getLocales().get(0);
    if (!"en".equals(locale.getLanguage())) {
      for (Map.Entry<String, Integer> entry : mConditionMap.entrySet()) {
        if (lower.contains(entry.getKey())) return mContext.getString(entry.getValue());
      }
    }
    StringBuilder result = new StringBuilder();
    for (String word : lower.split("\\s+")) {
      if (word.isEmpty()) continue;
      if (result.length() > 0) result.append(' ');
      result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return result.toString();
  }

  @Override
  public void weatherUpdated() {
    queryAndUpdateWeather();
  }

  @Override
  public void updateSettings() {
    queryAndUpdateWeather();
  }

  @Override
  public void weatherError(int errorReason) {
    // The broadcast is only a hint. Trust the provider, not externally supplied error extras.
    queryAndUpdateWeather();
  }

  private void queryAndUpdateWeather() {
    if (!mActive || mDestroyed || mWeatherClient == null) return;
    if (mWeatherQueryPending) {
      mWeatherRefreshRequested = true;
      return;
    }
    mWeatherQueryPending = true;
    mWeatherRefreshRequested = false;
    int generation = mGeneration;
    OmniJawsClient client = mWeatherClient;
    Context context = mContext.getApplicationContext();
    MODEL_EXECUTOR.execute(
        () -> {
          boolean enabled = false;
          OmniJawsClient.WeatherInfo info = null;
          Drawable icon = null;
          try {
            enabled = client.isOmniJawsEnabled(context);
            client.queryWeather(context);
            info = client.getWeatherInfo();
            if (enabled && info != null)
              icon = client.getWeatherConditionImage(context, info.conditionCode);
          } catch (RuntimeException e) {
            Log.w(TAG, "Unable to load weather", e);
          }
          final boolean weatherEnabled = enabled;
          final OmniJawsClient.WeatherInfo weather = info;
          final Drawable conditionIcon = icon;
          MAIN_EXECUTOR.execute(
              () -> {
                mWeatherQueryPending = false;
                if (mDestroyed || !mActive) return;
                if (generation == mGeneration) {
                  mOmniJawsEnabled = weatherEnabled;
                  mWeatherInfo = weatherEnabled ? weather : null;
                  mConditionImage = conditionIcon;
                  notifyListeners();
                }
                if (mWeatherRefreshRequested || generation != mGeneration) queryAndUpdateWeather();
              });
        });
  }

  public void notifyListeners() {
    if (!mActive || mDestroyed) return;
    mHandler.removeCallbacks(mNotify);
    mHandler.post(mNotify);
  }

  private void updateMediaController() {
    if (!mActive || mDestroyed) return;
    mHandler.removeCallbacks(mMediaUpdate);
    mHandler.postDelayed(mMediaUpdate, 50);
  }

  @Override
  public void onMediaMetadataChanged() {
    updateMediaController();
  }

  @Override
  public void onPlaybackStateChanged() {
    updateMediaController();
  }

  public void triggerChargingEvent() {
    if (mDestroyed) return;
    mEventsController.triggerChargingEvent();
    notifyListeners();
  }

  public void triggerBatteryFullEvent() {
    if (mDestroyed) return;
    mEventsController.triggerBatteryFullEvent();
    notifyListeners();
  }

  public void triggerBatteryLowEvent(int level) {
    if (mDestroyed) return;
    mEventsController.triggerBatteryLowEvent(level);
    notifyListeners();
  }

  public void triggerBtBatteryEvent(String name, String address, int level) {
    if (mDestroyed) return;
    mEventsController.triggerBtBatteryEvent(name, address, level);
    notifyListeners();
  }

  public void clearBatteryContextualEvent() {
    if (mDestroyed) return;
    mEventsController.clearActiveContextualEvent(QuickEventsController.CONTEXT_EVENT_NONE);
    notifyListeners();
  }

  public void clearContextualEventForSource(String sourceKey) {
    if (mDestroyed) return;
    mEventsController.clearActiveContextualEventForSource(sourceKey);
    notifyListeners();
  }

  public int getActiveContextualTypeForSource(String sourceKey) {
    return mEventsController.getActiveContextualTypeForSource(sourceKey);
  }

  public int getActiveContextualType() {
    return mEventsController.getActiveContextualType();
  }
}
