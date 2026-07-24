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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.GridOccupancy;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class QuickSpaceLayoutPlannerTest {
  private static ItemInfo item(int id, int screen, int x, int y, int spanX, int spanY) {
    ItemInfo item = new ItemInfo();
    item.id = id;
    item.screenId = screen;
    item.cellX = x;
    item.cellY = y;
    item.spanX = spanX;
    item.spanY = spanY;
    return item;
  }

  @Test
  public void onlyCoveredItemsMove() {
    ItemInfo covered = item(1, 0, 0, 0, 1, 1);
    ItemInfo untouched = item(2, 0, 0, 2, 2, 1);
    List<ItemInfo> changes =
        QuickSpaceLayoutPlanner.reserveSpace(List.of(covered, untouched), 4, 5, 2);
    assertEquals(List.of(covered), changes);
    assertEquals(2, untouched.cellY);
    assertTrue(covered.cellY >= 2);
  }

  @Test
  public void fullScreenPreservesWidgetSpanAndId() {
    ItemInfo widget = item(42, 0, 0, 0, 4, 4);
    ItemInfo otherPage = item(2, 3, 0, 0, 4, 4);
    QuickSpaceLayoutPlanner.reserveSpace(List.of(widget, otherPage), 4, 4, 1);
    assertEquals(4, widget.screenId);
    assertEquals(42, widget.id);
    assertEquals(4, widget.spanX);
    assertEquals(4, widget.spanY);
    assertEquals(3, otherPage.screenId);
  }

  @Test
  public void disabledReservationDoesNotMoveAnything() {
    ItemInfo icon = item(1, 0, 0, 0, 1, 1);
    assertTrue(QuickSpaceLayoutPlanner.reserveSpace(List.of(icon), 4, 5, 0).isEmpty());
    assertEquals(0, icon.cellY);
  }

  @Test
  public void repeatedLoadIsIdempotent() {
    List<ItemInfo> icons = new ArrayList<>();
    for (int y = 0; y < 5; y++) {
      for (int x = 0; x < 4; x++) icons.add(item(y * 4 + x, 0, x, y, 1, 1));
    }
    assertEquals(12, QuickSpaceLayoutPlanner.reserveSpace(icons, 4, 5, 3).size());
    assertTrue(QuickSpaceLayoutPlanner.reserveSpace(icons, 4, 5, 3).isEmpty());
    assertVacantReservation(icons, 4, 5, 3);
  }

  @Test
  public void randomDenseLayoutsRetainAllItemsWithoutOverlap() {
    Random random = new Random(7635);
    for (int attempt = 0; attempt < 200; attempt++) {
      int columns = 2 + random.nextInt(5);
      int rows = 2 + random.nextInt(6);
      int reserved = 1 + random.nextInt(rows);
      List<ItemInfo> icons = new ArrayList<>();
      for (int screen = 0; screen < 3; screen++) {
        for (int y = 0; y < rows; y++) {
          for (int x = 0; x < columns; x++) {
            if (random.nextBoolean()) icons.add(item(icons.size(), screen, x, y, 1, 1));
          }
        }
      }
      int count = icons.size();
      QuickSpaceLayoutPlanner.reserveSpace(icons, columns, rows, reserved);
      assertEquals(count, icons.size());
      assertVacantReservation(icons, columns, rows, reserved);
    }
  }

  private static void assertVacantReservation(
      List<ItemInfo> items, int columns, int rows, int reserve) {
    java.util.Map<Integer, GridOccupancy> screens = new java.util.HashMap<>();
    GridOccupancy first = new GridOccupancy(columns, rows);
    first.markCells(0, 0, columns, reserve, true);
    screens.put(0, first);
    for (ItemInfo item : items) {
      GridOccupancy grid =
          screens.computeIfAbsent(item.screenId, id -> new GridOccupancy(columns, rows));
      assertTrue(grid.isRegionVacant(item.cellX, item.cellY, item.spanX, item.spanY));
      grid.markCells(item, true);
    }
  }

  @Test
  public void malformedStyleFallsBackToDefault() {
    for (String value : new String[] {null, "", "bad", "-1", "7", "999999999999999"}) {
      assertEquals(0, QuickSpacePreferences.parseStyle(value));
    }
    assertEquals(4, QuickSpacePreferences.parseStyle("4"));
  }

  @Test
  public void allDayUsesLocalMidnightIncludingDst() {
    long utcDate = Instant.parse("2026-03-09T00:00:00Z").toEpochMilli();
    assertEquals(
        Instant.parse("2026-03-09T07:00:00Z").toEpochMilli(),
        QuickSpaceCalendarDates.localBoundary(utcDate, ZoneId.of("America/Los_Angeles")));
    assertEquals(
        Instant.parse("2026-03-08T15:00:00Z").toEpochMilli(),
        QuickSpaceCalendarDates.localBoundary(utcDate, ZoneId.of("Asia/Tokyo")));
  }
}
