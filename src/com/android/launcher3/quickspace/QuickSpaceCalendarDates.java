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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** CalendarContract all-day timestamps encode dates in UTC, not local instants. */
public final class QuickSpaceCalendarDates {
  private QuickSpaceCalendarDates() {}

  public static long localBoundary(long utcDate, ZoneId zone) {
    return Instant.ofEpochMilli(utcDate)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli();
  }
}
