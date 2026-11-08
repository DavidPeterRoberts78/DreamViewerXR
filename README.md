# DreamShell

A browser and video player for the Google Daydream View headset that works without the
discontinued Daydream app. It renders a virtual cinema screen in stereo, pre-distorts it for
the headset lenses, tracks your head with the phone's sensors, and talks to the Daydream
motion controller directly over Bluetooth LE.

Framework APIs only: no AndroidX, no third-party libraries. Kotlin, minSdk 33, targetSdk 36.

## Build and install

1. Open the `DreamShell` folder in a recent Android Studio and let Gradle sync.
   Accept any suggested Android Gradle Plugin upgrade.
2. Enable Developer options and USB debugging on the Pixel, plug it in, press **Run**.
   Or from a terminal: `./gradlew installDebug`.

## Connecting the controller

1. Open DreamShell, tap **Connect**, then hold the controller's Home button until it's found.
2. **The first time, Android asks to pair with "Daydream controller": tap Pair.** The
   controller only sends motion data over an encrypted (paired) link.
3. Check the **live test** under the Connect button: the status should read *Connected and
   receiving data*, the dot should follow the controller, and the buttons should light up.

If it says *Connected, waiting for data*:
- Press Home on the controller to wake it.
- Tap **Re-pair controller**. If Android won't let the app remove the old pairing, it opens
  Bluetooth settings: tap *Daydream controller* → *Forget*, come back, tap **Connect**.
- Turn on **Show connection log** for a step-by-step trace (it's selectable, so you can copy it).

If the dot moves the wrong way, use **Invert pointer left/right / up/down** under
*Controller*. If it moves sideways when you tilt the controller, try **Pointing axis** Y or X.

## In the headset

Put the phone in, point at the screen and press **Home** to centre the screen and pointer.
If the controller isn't sending data, a status line under the screen says what's wrong.

| Controller        | Browser                              | Video                    |
|-------------------|--------------------------------------|--------------------------|
| Point             | Move cursor                          | –                        |
| Click touchpad    | Tap (hold and move to drag/select)   | Play / pause             |
| Swipe touchpad    | Scroll                               | Scrub; seeks on release  |
| App button        | Back (then exit)                     | Exit                     |
| Home button       | Centre screen and pointer            | Centre                   |
| Volume +/–        | Media volume                         | Media volume             |

A Bluetooth gamepad or keyboard also works: D-pad moves the cursor (or seeks ±10 s in video),
A/Enter taps, B/Esc goes back, Y/Select centres.

## Lens settings

The lens model is the Cardboard SDK's, the same one CardboardXR ports. Each eye's view runs
from its lens axis to the screen edges, widened by the lens distortion and clamped by the
lens's own limit, so the two views are asymmetric. The virtual cameras sit exactly one lens
spacing apart.

Pick your headset under **Lenses → Headset**:

| Preset | k1 | k2 | k3 |
|---|---|---|---|
| Daydream View (2017), default | 0.4331 | −0.0856 | 1.4535 |
| Daydream View (2016) | 0.385 | 0.593 | 0 |

The distortion coefficients are Google's own for each headset ([googlevr/cardboard
issue #17](https://github.com/googlevr/cardboard/issues/17)). Google never published the
lens spacing (63.9 mm), screen-to-lens distance (39.3 mm) or phone position (centred), so those
are starting values.

To fine-tune, tap **Test lenses in headset**. It shows a flat grid wall with a blue centre
cross, and a grey line at the bottom centre of the phone screen.
1. Line up the grey line with the middle of the headset so the phone is centred.
2. **Lens separation**: adjust until the two crosses fuse into one without strain.
3. **k1/k2/k3**: if grid lines bow inward toward the edges (pincushion), raise k1; if they
   bow outward (barrel), lower it.
4. **Lens height nudge**: if one eye's image sits higher, or you look up or down to centre the
   cross, move it a millimetre at a time.

Settings save instantly and apply when you go back into the headset.

## How it works

| File | Role |
|---|---|
| `controller/DaydreamController.kt` | BLE: scan or use the paired device, pair if needed, discover `FE55`, enable notifications and check the result, re-subscribe if no data arrives, reconnect when the link drops. Keeps a connection log. |
| `controller/ControllerState.kt` | Decodes the 20-byte packets: orientation, accelerometer, gyro, touchpad, buttons. Bit layout from `daydream-controller.js`. |
| `controller/PointerMapper.kt` | Orientation relative to the Home pose → ray → point on the virtual screen. |
| `gl/LensModel.kt` | Per-eye field of view and screen-to-eye-image mapping (Cardboard SDK model). |
| `gl/StereoRenderer.kt` | Draws each eye with its own frustum, then pre-distorts it onto its half of the screen. Status line, calibration grid, alignment line. |
| `OffscreenLayout.kt` | Paints the WebView into a `Surface` that feeds the GL texture. |
| `VrActivity.kt` | Head tracking, controller input, page input injection, MediaPlayer. |
| `MainActivity.kt`, `ControllerTestView.kt` | Start screen: connection, live controller test, settings. |

## Known limits

- **Other apps can't be mirrored.** Android only lets an app place another app's window on a
  virtual display it owns if that app opts in, and capturing the whole screen would also
  capture the stereo view itself. So the browser and player are built in.
- **DRM video** (Netflix, Disney+ and similar) renders black on the virtual screen.
- **Text entry**: type the address on the start screen before putting the headset on.
- The controller's raw axis convention isn't documented. The default (point along Z) follows
  daydream2hid's mapping, and the invert/axis settings cover the rest.

## Credits

See `NOTICE`.
