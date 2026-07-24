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

import static com.android.launcher3.WorkspaceLayoutManager.FIRST_SCREEN_ID;

import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.GridOccupancy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Relocates only items covered by QuickSpace, without dropping or resizing them. */
public final class QuickSpaceLayoutPlanner {
  private QuickSpaceLayoutPlanner() {}

  /** Mutates the supplied desktop items and returns the ones whose coordinates changed. */
  public static List<ItemInfo> reserveSpace(
      List<ItemInfo> items, int columns, int rows, int reservedRows) {
    List<ItemInfo> displaced = new ArrayList<>();
    if (columns <= 0 || rows <= 0 || reservedRows <= 0) return displaced;

    TreeMap<Integer, GridOccupancy> screens = new TreeMap<>();
    GridOccupancy firstScreen = new GridOccupancy(columns, rows);
    firstScreen.markCells(0, 0, columns, Math.min(reservedRows, rows), true);
    screens.put(FIRST_SCREEN_ID, firstScreen);
    for (ItemInfo item : items) {
      // Leave malformed DB entries to the loader's existing validation policy.
      if (item.screenId < FIRST_SCREEN_ID
          || item.cellX < 0
          || item.cellY < 0
          || item.spanX <= 0
          || item.spanY <= 0
          || item.cellX > columns - item.spanX
          || item.cellY > rows - item.spanY) {
        continue;
      }
      GridOccupancy occupied =
          screens.computeIfAbsent(item.screenId, id -> new GridOccupancy(columns, rows));
      if (item.screenId == FIRST_SCREEN_ID && item.cellY < reservedRows) {
        displaced.add(item);
      } else {
        occupied.markCells(item, true);
      }
    }

    int[] cell = new int[2];
    for (ItemInfo item : displaced) {
      boolean placed = false;
      for (Map.Entry<Integer, GridOccupancy> screen : screens.entrySet()) {
        if (screen.getValue().findVacantCell(cell, item.spanX, item.spanY)) {
          item.screenId = screen.getKey();
          item.cellX = cell[0];
          item.cellY = cell[1];
          screen.getValue().markCells(item, true);
          placed = true;
          break;
        }
      }
      if (!placed) {
        int screenId = screens.lastKey() + 1;
        GridOccupancy occupied = new GridOccupancy(columns, rows);
        item.screenId = screenId;
        item.cellX = 0;
        item.cellY = 0;
        occupied.markCells(item, true);
        screens.put(screenId, occupied);
      }
    }
    return displaced;
  }
}
