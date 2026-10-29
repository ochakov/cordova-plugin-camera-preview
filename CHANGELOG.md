# Changelog

## MASTER BRANCH (RECOMMENDED) - UNRELEASED
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.14.0...master)
- **Android: migrated from the deprecated Camera API to Camera2.** Requires Android 6.0 (API 23) or newer.
- Added `switchFocalLength([zoomFactor])`, `getCurrentFocalLength()` and `getAvailableFocalLengths()` to switch between the ultra wide / wide / telephoto lenses of multi-camera devices (Android and iOS). Lenses are identified by their zoom factor relative to the main camera (e.g. `0.5`, `1`, `2`) on both platforms. The last selected lens is restored by the next `startCamera()`.
- Added the `fullScreen` option to `startCamera()`.
- Android: `startCamera`, `switchCamera` and `switchFocalLength` now report success only once the camera is actually streaming, and report an error (instead of crashing) when the camera cannot be opened or a lens cannot be switched to. A failed lens switch falls back to the previous lens.
- Android: tap to focus now focuses and meters on the tapped point and reports completion once autofocus has settled.
- Android: flash `auto` / `on` captures run the exposure precapture sequence; switching away from `torch` turns the torch off.
- Android: color effects, focus modes, white balance modes, exposure lock and exposure compensation are implemented with Camera2 controls and validated against what the device supports.
- Android: `setZoom` accepts fractional multipliers; `getZoom` / `getMaxZoom` return numbers.
- Android: video recording no longer breaks zoom, flash, focus and picture taking after it stops; the video size follows the requested `width`/`height` or the device camcorder profile.
- Android: front camera pictures are mirrored to match the preview in every orientation.
- Android: removed the `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` permissions (re-introduced by mistake) and the bogus exported `CameraActivity` manifest entry.
- Android: fixed leaked camera background threads, image reader crashes when switching cameras right after taking a picture, and null pointer crashes in the plugin callbacks.
- Android: the preview is centered in its area and its size is chosen for that area, independently of the camera that was open before. `switchCamera()`, `switchFocalLength()` and a later `startCamera()` now show the same framing, and the cover-mode crop is split evenly instead of cutting off the right/bottom part of the frame.
- Android: fixed a preview that was stretched on every other camera start, or failed to start with "Failed to configure camera session", on devices whose camera opens within a frame (seen on a Samsung front camera). Resizing the preview view resets its buffer size to the view size, and when that happened while the session was being created the camera streamed the view size rounded to another supported size (for example 4:3 into a 16:9 view). The camera is now opened, and sessions are recreated, only after the view's layout pass, the preview buffer size is restored right after any view resize, a failed session is retried once, and failures of replaced sessions are ignored.
- Android: the preview uses a 4:3 or 16:9 size for which the camera also has a picture size, and the default and requested picture sizes keep the preview's aspect ratio, so the streams of a session always have the same shape. The preview view follows the size its session was configured with, and a recording session no longer picks up the view's size as its preview size.
- Android: stopping the camera while a lens or camera switch is still opening no longer freezes the app for seconds or leaves the camera unusable until the app is restarted. Cameras are opened and closed on the camera thread instead of the UI thread, `stopCamera()` no longer waits for a camera that is still opening and closes it as soon as it arrives, the camera thread is kept so such late callbacks are always delivered, only a lens that showed a preview is remembered for the next `startCamera()`, a remembered lens that cannot be opened falls back to the main camera, events of a stopped preview no longer reach the next one, and a camera that is still being released is retried for a few seconds instead of failing as "in use".
- Android: a lens on a hidden camera that the system refuses to open (seen for the telephoto of a Samsung phone: "No camera device with ID available") no longer switches back to the previous lens. The lens is shown with digital zoom on the main camera, and the refusal is remembered until the next system update. `switchFocalLength()` without a zoom factor no longer gets stuck trying the same lens when a lens cannot be opened.
- iOS: the camera starts faster. The capture session is configured in one batch and started once it is complete, instead of being started empty when the preview view appears and restarted for every input and output added afterwards (and once more for a remembered lens). The `startCamera()` success callback now comes once the session is running.
- iOS: the preview and pictures are upright in landscape. The interface orientation is read from the window scene instead of the deprecated `statusBarOrientation`, the capture orientation and the fullscreen preview frame follow rotations through `viewWillTransitionToSize:` instead of the deprecated `willRotateToInterfaceOrientation:`, and front camera pictures taken in landscape are mirrored instead of turned upside down.
- JS: `onBackButton` uses Cordova's `backbutton` event and the listener is removed by `stopCamera()`.

## v0.14.0 - LATEST RELEASED VERSION
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.13.0...v0.14.0)
- [#709](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/709) - Remove unused permissions `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEOS`
- [#707](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/707) - Fixes to prevent null pointer and runtime errors
- [#701](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/701) - Fix Camera preview not going to back in Ionic Capacitor

## v0.13.0 - July 23, 2024 - LATEST RELEASED VERSION
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.12.3...v0.13.0)
- [#699](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/commit/21961100181c97dcbf59e72749d6cf2add7f05ef) - Null pointer exception fixes
- [#689](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/689) - Improve error handling for Android
- [#688](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/688) - Fix exceptions thrown around flash types when calling setParameters on Android. We now check supported flash mode types and only enable the modes that are found.
- [#687](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/687) - Add Android 33+ required permissions for media image and media video permissions
- [#676](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/676) - Ensure `toBack` behaves correctly on Android

## v0.12.3
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.12.2...v0.12.3)
- [#654](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/654) - Fix memory leak when taking multiple snapshots

## v0.12.2 - Apr 15, 2021
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.12.1...v0.12.2)
- [#639](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/639) - Typescript definition fix
- [#633](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/633) - Fix app crash with `startRecordVideo` after allowing the `RECORD_AUDIO` permission prompt.
- [#630](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/630) - For Android, make both camera and autofocus non-required for the library. This is an application level concern.

## v0.12.1 - Oct 21, 2020
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.12.0...v0.12.1)
- [#624](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/issues/624) - Remove unused coefficient argument from the `calculateTapArea` method of the Android code
- [#619](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/619) - Fix compiler errors in startRecordVideo by declaring final variables
- [#615](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/615) - Correctly handle torch mode in getFlashMode

## v0.12.0 - May 4, 2020
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.11.2...v0.12.0)
- [#605](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/605) - Improve memory usage when capturing multiple photos for both Android and iOS
- [#587](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/587) - Add video recording functionality for Android
- [#599](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/599) - Use androidx package instead of legacy android support package
- [#606](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/606) - Remove `android:required` from manifest to avoid conflict with other plugins

## v0.11.2 - February 12, 2020
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.11.1...v0.11.2)
- [#582](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/582) - Add support for Android devices without Autofocus which can increase the amount of devices for which app installation is allowed by about (~4k at time of)
- [#583](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/583) - Fix typescript error CameraPreview.d.ts is not a module

## v0.11.1 - November 19, 2019
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.11.0...v0.11.1)
- [#573](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/573) - Fetch number of cameras immediately before switching cameras in Android
- [#428](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/issues/428) - Fix mispelling of `continuous` within iOS source code for focus modes (was `cotinuous` before)
- [#568](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/568), [PR #570](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/570) - Resolves plugin interaction issues when toBack is set and other plugins like cordova-plugin-googlemaps are changing the layout

## v0.11.0 - May 20, 2019
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.10.0...v0.11.0)
- [#525](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/525) - Add function `takeSnapshot` for quick image captures
- [#441](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/441) - Add android only option `storeToFile` for storage in temporary file instead of base64
- [#524](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/524) - Add iOS support for `storeToFile`
- [#396](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/pull/396) - Add function `getCameraCharacteristics`
- Allow `startCamera` to allow no options object when only callback is provided
- Add Changelog

## v0.10.0 - June 13, 2018
- [View Diff](https://github.com/cordova-plugin-camera-preview/cordova-plugin-camera-preview/compare/v0.9.0...v0.10.0)
- Merge in features and fixes from various forks

## v0.9.0 - May 9, 2017

## v0.0.8 - March 30, 2015

## v0.0.6 - February 4, 2015

## v0.0.3 - January 12, 2015

## v0.0.2 - January 7, 2015
