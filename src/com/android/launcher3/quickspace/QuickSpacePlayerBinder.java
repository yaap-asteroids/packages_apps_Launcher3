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
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.util.MSMHProxy;
import com.android.launcher3.util.Themes;

/** Stateless presentation binding: no additional media subscription or polling. */
final class QuickSpacePlayerBinder {
  private QuickSpacePlayerBinder() {}

  static void bind(LinearLayout content, TextView text, QuickEventsController events) {
    Context context = content.getContext();
    int style;
    try {
      style = Integer.parseInt(LauncherPrefs.QUICKSPACE_PLAYER_STYLE.get(context));
    } catch (NumberFormatException e) {
      style = 0;
    }
    boolean card = style == 2;
    boolean twoLine = style == 1 || card;
    String title = events.getTitle();
    String artist = events.getActionTitle();
    text.setSingleLine(!twoLine);
    text.setMaxLines(twoLine ? 2 : 1);
    text.setEllipsize(TextUtils.TruncateAt.END);
    text.setText(TextUtils.isEmpty(artist) ? title : title + (twoLine ? "\n" : " · ") + artist);
    text.setTypeface(
        Typeface.create(twoLine ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    text.setTextColor(
        Themes.getAttrColor(
            context, card ? android.R.attr.textColorPrimary : R.attr.workspaceTextColor));
    content.setBackgroundResource(card ? R.drawable.bg_quickspace_player_card : 0);
    content.setPadding(
        card ? dp(context, 12) : 0,
        card ? dp(context, 6) : 0,
        card ? dp(context, 8) : 0,
        card ? dp(context, 6) : 0);
    content.setMinimumHeight(card ? dp(context, 48) : 0);
    // Keep long track metadata from drawing beyond the workspace width.
    text.setMaxWidth(
        Math.max(
            dp(context, 80),
            context.getResources().getDisplayMetrics().widthPixels
                - dp(context, card ? 160 : 112)));
    ImageView icon = content.findViewById(R.id.now_playing_icon);
    if (icon != null) {
      int size = dp(context, card ? 32 : 20);
      icon.getLayoutParams().width = size;
      icon.getLayoutParams().height = size;
      icon.setImageDrawable(events.getActionIcon());
      icon.setImageTintList(null);
      icon.requestLayout();
    }
    TextView control = content.findViewById(R.id.quickspace_player_control);
    if (card && control == null) {
      control = new TextView(context);
      control.setId(R.id.quickspace_player_control);
      control.setGravity(Gravity.CENTER);
      control.setText("Ⅱ");
      control.setTextSize(22);
      control.setContentDescription(context.getString(R.string.quickspace_player_play_pause));
      control.setBackgroundResource(android.R.drawable.list_selector_background);
      control.setOnClickListener(v -> MSMHProxy.INSTANCE(context).toggleMediaPlaybackState());
      content.addView(control, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
    }
    if (control != null) {
      control.setVisibility(card ? View.VISIBLE : View.GONE);
      control.setTextColor(Themes.getAttrColor(context, android.R.attr.textColorPrimary));
    }
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
