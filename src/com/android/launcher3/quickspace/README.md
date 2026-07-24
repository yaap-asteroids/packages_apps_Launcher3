# QuickSpace for YAAP

QuickSpace is opt-in: `pref_quickspace` defaults to `false` in Home settings and
`LauncherPrefs`. When disabled, Workspace does not inflate the view and the model
does not reserve its rows. Existing user choices, including restored preferences,
are preserved; this is not a forced reset of an enabled setting.

## Provenance

Adapted from [Launcher3: Add QuickSpace](https://github.com/fruity-aospa/android_packages_apps_Launcher3/commit/2c98ec650feb1f61dfbd2ef395067b8e0248f448),
authored by Pranav Vashi <neobuddy89@gmail.com> on 2026-07-24, with integration by
fuzailmansuri <mfm9875@gmail.com>.

That port cites these VoltageOS commits:

- https://github.com/VoltageOS/packages_apps_Launcher3/commit/27bd32a18067c6667c9885a8be2536278a550c59
- https://github.com/VoltageOS/packages_apps_Launcher3/commit/330dcfe4a5f4a03f109f5f7eb48ccd8b98abf8ae

Upstream co-authors: Chris <chris@cypheros.co>, El Dainosor
<eldainosor@gmail.com>, Ali B <abittin@gmail.com>, Till Kottmann
<me@deletescape.ch>, minaripenguin <minaripenguin@users.noreply.github.com>, rmp22
<195054967+rmp22@users.noreply.github.com>, Dmitrii <bankersenator@gmail.com>, and
fuzailmansuri <mfm9875@gmail.com>. Imported source files retain their license
headers. The media helper also retains risingOS and AxionAOSP copyright notices.

## YAAP integration

- Keeps existing default workspace layouts and YAAP settings/restart behavior.
- Relocates only desktop items covered by QuickSpace before overlap validation,
  updating coordinates transactionally without deleting or resizing those items.
  Turning QuickSpace off frees the rows; relocated items are not automatically
  moved back. The same reserved span is used by the UI, loader, grid migration,
  new-item placement, and wallpaper preview.
- Owns controllers per view, with main-thread state and visible-Home subscriptions.
  Weather queries and media icon loading run off the main thread. Late results
  are generation-checked. Previews do not start live subscriptions.
- Uses provider notifications for OmniJaws and the actual legacy YAAP schema, not
  untrusted weather-error broadcast extras or unsupported hourly fields.
- Uses protected platform Bluetooth events instead of an unprotected custom
  battery broadcast. Bluetooth and calendar permissions are requested only when
  their respective options are enabled. Phone battery still works if Bluetooth
  permission is denied.
- Converts all-day calendar dates from UTC date encoding to local-day boundaries.
- Adds Editorial and Split clock layouts alongside the five upstream layouts.
- Adds Inline, Two-line, and Media card player presentation in Bold, Bold paged,
  Editorial, and Split layouts. Media card includes a pause control; paused media
  falls back to ordinary QuickSpace content under the existing now-playing policy.

## Validation

`QuickSpaceLayoutPlannerTest` covers covered-item relocation, full-page widget
preservation, disabled reservation, idempotence, randomized dense layouts,
malformed style fallback, and all-day timezone/DST conversion.

Focused host checks can exercise the real planner, grid utility, style parser,
and date conversion with lightweight `ItemInfo`/preference dependency stubs.
Those checks are not a substitute for the Android test modules or a full Soong
build. Likewise, resource compilation alone does not establish resource linking,
Dagger generation, APK packaging, or runtime correctness.

## Device test checklist

1. Fresh settings: QuickSpace stays off; no reserved rows or live subscriptions.
2. Enable each layout on a populated first page; verify shortcuts, folders and
   widgets are retained, then repeat with a full grid and grid-size changes.
3. Change layout/minimal mode, disable, re-enable, rotate, and restart Home.
4. Check each player style with long metadata, player switches, playback stop,
   and missing application icons; test the Media card pause control.
5. Deny/grant/revoke calendar and Bluetooth permission; test no provider/device,
   a Bluetooth disconnect, phone charging, and device selection.
6. Check today's all-day event near local midnight in positive/negative offsets
   and across daylight-saving transitions.
7. Open another app, lock/unlock, show the drawer, and rebind the first page;
   verify animations resume correctly and inactive Home does not keep receivers,
   provider observers, or media subscriptions alive.
8. Open wallpaper/grid previews; verify static rendering without live listeners.
9. Check RTL, accessibility, large font scale, and small/large grids visually.
