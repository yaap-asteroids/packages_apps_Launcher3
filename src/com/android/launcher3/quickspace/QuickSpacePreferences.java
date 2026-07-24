/*
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

import android.content.Context;
import com.android.launcher3.LauncherPrefs;

/** Shared interpretation of persisted QuickSpace settings for both the model and the view. */
public final class QuickSpacePreferences {
  private QuickSpacePreferences() {}

  public static int getStyle(Context context) {
    return parseStyle(LauncherPrefs.QUICKSPACE_UI_STYLE.get(context));
  }

  public static int parseStyle(String value) {
    try {
      int style = Integer.parseInt(value);
      return style >= 0 && style <= 6 ? style : 0;
    } catch (NumberFormatException e) {
      // A restored or externally edited preference must not crash Home or its loader.
      return 0;
    }
  }
}
