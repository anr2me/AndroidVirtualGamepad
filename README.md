# Android Virtual Gamepad

Android Virtual Gamepad changes the buttons and the sticks of a game controller. The application sends the changed events to a virtual gamepad. It operates on Android TV and on regular Android devices.

## 1 Description

### 1.1 Function

The application does these steps:

1. It reads raw events from an input device, for example `/dev/input/event5`. The input device can be the adapter of a PS2 controller. The application can do this for up to four input devices at the same time.
2. It changes the events with a mapping.
3. It sends the changed events to one output.

### 1.2 Outputs

The application has these outputs:

| Output | Description |
|---|---|
| Virtual gamepad | The application creates a new gamepad through `/dev/uinput`. The gamepad has the buttons A, B, X, Y, L1, R1, Select, Start, Mode, the stick buttons and the D-pad. It has two analog sticks. L2 and R2 are analog triggers, digital buttons or both (see section 6.3). The gamepad can have the identity of an Xbox 360, PlayStation 3 or PlayStation 4 gamepad, or a custom identity. |
| Existing device | You select an existing input device. The application writes the events to its `/dev/input/eventX` node. |
| Key injection | The application sends Android key events and joystick events to the focused application. |
| Keyboard keys | The application sends keyboard keys (letters, digits, arrows, Enter, Esc, function keys and others) when you press a controller button. This output is separate from the gamepad output. You can use both at the same time, or use only the keyboard keys (see section 6.5). |

In the mode **Auto**, the application tries the outputs in this sequence: virtual gamepad, existing device, key injection. It uses the first output that operates.

### 1.3 Pads

A pad is one virtual gamepad. Each pad has its own source device, mapping, identity and output. The application supports up to four pads (Pad 1 to Pad 4). All pads operate in the same helper process.

### 1.4 Access modes

An Android application cannot read `/dev/input` or write to `/dev/uinput` without more permission. The application has two access modes. A third mode, Accessibility, is optional. The default build does not have it (see section 3.3).

| Mode | Requirement | Read `/dev/input` | Virtual gamepad | Existing device | Key injection |
|---|---|---|---|---|---|
| Root | A root manager (`su`) | Yes | Yes | Yes | Yes |
| Shizuku | Shizuku is in operation | Yes | Depends on the device (note 1) | Depends on the device (note 1) | Yes |
| Accessibility (optional) | None | No | No | No | No (note 2) |

Keyboard keys and rumble need Root or Shizuku mode. Accessibility mode does not support them.

**NOTE 1:** In Shizuku mode, the helper process has the shell user ID. On some devices, SELinux prevents the shell user from writing to `/dev/uinput`. Use the button **Reconnect** to see the result of the test on your device. The test result shows in the section **Access mode**.

**NOTE 2:** In Accessibility mode, the application receives only key events. Android does not let an accessibility service create gamepad events. In this mode, the application can run an Android action (Back, Home, Recents, Notifications, Quick settings, Power dialog), tap a screen position, or block a key.

### 1.5 Architecture

```
 UI app (Compose)  --Binder-->  Helper process (root or shell)
                                 |
   /dev/input/eventX --read-->  Remapper --> /dev/uinput        (virtual gamepad)
   (PS2 adapter)                         --> /dev/input/eventY  (existing device)
                                         --> InputManager       (key injection)
   (one such pipeline for each pad)
```

The helper process contains the complete event loop. The loop has low latency because the events do not go through the UI application. The helper process loads the native library `libagp.so`. This library does the `ioctl` calls that Java does not have.

## 2 Requirements

- Android 7.0 (API 24) or later.
- One of these:
  - A rooted device.
  - [Shizuku](https://shizuku.rikka.app/) in operation (Android 11 or later: wireless debugging. Other versions: ADB).
  - Neither of these (Accessibility mode, limited function). This mode needs a special build (see section 3.3).
- To build the project: Android Studio (Jellyfish or later), JDK 17, Android SDK 34, Android NDK 26.3.11579264 and CMake 3.22.1. Install the NDK and CMake with the SDK Manager.

## 3 Build

1. Open the folder `AndroidVirtualGamepad` in Android Studio.
2. Let Gradle synchronize. Gradle downloads the libraries from Google, Maven Central and JitPack.
3. Select **Build > Build APK(s)**.

To build in the command line, do these steps:

1. Go to the project folder.
2. Make the Gradle wrapper: `gradle wrapper --gradle-version 8.6`
3. Build the APK: `./gradlew assembleDebug`

The APK is in `app/build/outputs/apk/debug/`.

### 3.1 Build with GitHub Actions

The file `.github/workflows/build.yml` builds the release APK. You start it by hand.

1. Open the tab **Actions** of your repository.
2. Select the workflow **Build APK**.
3. Select **Run workflow** and select the branch.
4. Optional: enter a **version**, for example `1.2.0`. If you leave this field empty, the workflow uses the value of `avg.versionName` in `gradle.properties`.
5. To make a GitHub release, set **Publish the APK as a GitHub release** to true.
6. To add Accessibility mode, set **Add Accessibility mode** to true (see section 3.3).

The workflow always saves the APK as a workflow artifact. It makes a release only if you set the option **publish**.

The version that you enter (or the default) is the base version. The workflow adds the branch name and the run number if the branch is not the default branch. The APK, the file name and the release tag use the same final version.

| Branch | Version of the APK | Release type and tag |
|---|---|---|
| The default branch of the repository | `1.2.0` | Release (marked as latest). Tag: `v1.2.0`. |
| Any other branch, or a tag | `1.2.0-<branch>.<run number>` | Pre-release. Tag: `v1.2.0-<branch>.<run number>`. |

The version must look like `1.2.0` or `1.2.0-beta1`. A leading `v` is removed. The `versionCode` of the APK is the run number of the workflow. After the build, the workflow reads the version from the APK with `aapt2` and stops if it is not the same as the tag. The release fails if the tag already exists. Use a new version for each release.

If the repository has the four secrets of section 3.1.1, the workflow signs the APK with your key. Without these secrets, the workflow signs the APK with the debug key. Android installs this APK, but you cannot update it later with an APK that has a different key.

**NOTE:** The project uses the Android Gradle Plugin 8.4.2. This version needs Gradle 8.6 or later, and it supports compile SDK 34. This is compatible with the Gradle 8.6 of the GitHub runner images. CodeQL default setup can build the project with that Gradle version.

**NOTE:** The repository has no Gradle wrapper. The workflow uses Gradle 8.6 from the setup action.

#### 3.1.1 Make the signing key

To sign the APK with your own key, do these steps:

1. Make a keystore with the Java `keytool`. Keep the file `release.jks` in a safe place. Do not add it to the repository.

   ```
   keytool -genkeypair -v -keystore release.jks -alias avg -keyalg RSA -keysize 4096 -validity 10000
   ```

   Enter a password for the keystore. Keytool then asks for the key details. If keytool asks for a separate key password, press Enter to use the keystore password.

2. Encode the keystore file with base64. The result must be one line, without line breaks.

   | System | Command |
   |---|---|
   | Linux | `base64 -w 0 release.jks > release.jks.b64` |
   | macOS | `base64 -i release.jks -o release.jks.b64` |
   | Windows (PowerShell) | `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) \| Set-Content -NoNewline release.jks.b64` |

3. Open the repository on GitHub. Select **Settings > Secrets and variables > Actions > New repository secret**.
4. Add these four secrets:

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | The complete content of the file `release.jks.b64` |
   | `KEYSTORE_PASSWORD` | The keystore password |
   | `KEY_ALIAS` | The alias, for example `avg` |
   | `KEY_PASSWORD` | The key password. This is the same as the keystore password if you did not set a separate key password. |

   With the GitHub CLI, you can set the first secret with this command: `gh secret set KEYSTORE_BASE64 < release.jks.b64`

5. Delete the file `release.jks.b64`. It contains your private key.

To check the keystore, use this command: `keytool -list -v -keystore release.jks`

**WARNING:** Make a backup of `release.jks` and of the passwords. If you lose them, you cannot update an installed application with a new APK. Anyone who has the keystore and the passwords can sign APKs as you.

### 3.2 Code scanning with CodeQL

The CodeQL default setup cannot build an Android project with its automatic build. The build fails. Use the workflow `.github/workflows/codeql.yml`:

1. Open **Settings > Code security** of your repository.
2. Disable the CodeQL default setup. The default setup and a workflow file cannot operate together.
3. Commit the workflow file. It runs on pushes and pull requests to `main` or `master`, every week, and by hand.

The workflow builds the debug APK with the command `gradle clean :app:assembleDebug`. It analyzes the Kotlin/Java code (`java-kotlin`), the native code (`c-cpp`) and the workflow files (`actions`). If you do not need the native analysis, remove `c-cpp` from the matrix. The workflow uses the Android SDK that is already in the GitHub runner image. It does not use the action `android-actions/setup-android`, because that action fails on the current runner image (`Failed to find package 'tools'`). Change the branch names in the workflow if your default branch has a different name.

To build one CPU architecture only, add `-Pavg.abis=arm64-v8a` to the Gradle command.

### 3.3 Accessibility mode (optional)

The default build does not have Accessibility mode. The APK has no accessibility service. Google Play Protect can block the installation of an APK that declares an accessibility service. The message says that the application can request access to sensitive data.

To add Accessibility mode, set the Gradle property `avg.accessibility` to `true`:

```
./gradlew assembleDebug -Pavg.accessibility=true
```

The build then adds the files in `app/src/accessibility/` to the application. These files are the service, its manifest entry and its resources.

In the GitHub workflow **Build APK**, set **Add Accessibility mode** to true. The name of the APK file then ends with `-accessibility`. The CodeQL workflow always builds with this option, so CodeQL also analyzes the service.

**NOTE:** The tag of a release does not have the suffix `-accessibility`. Use a different version when you publish the two kinds of APK.

## 4 Installation

1. Copy the APK to the device.
2. Install the APK.
3. On Android TV, use ADB: `adb install app-debug.apk`
4. Start **Android Virtual Gamepad** from the launcher. On Android TV, the application is in the row of the applications.

## 5 Use

### 5.1 Root mode

1. Connect the controller (or the adapter) to the device.
2. Start the application.
3. In the section **Access mode**, select **Root**.
4. Give root access to the application when the root manager asks.
5. Wait for the message **Connected. Helper runs as root.**
6. In the section **Source device**, select the controller.
7. Set the mapping (see section 6).
8. Select **Start**.

### 5.2 Shizuku mode

1. Start Shizuku.
2. Start the application.
3. In the section **Access mode**, select **Shizuku**.
4. Give Shizuku permission to the application when Shizuku asks.
5. Wait for the message **Connected. Helper runs as shell**.
6. Read the line that starts with **Helper uid**. It tells you which outputs are available.
7. Do steps 6 to 8 of section 5.1.

**NOTE:** On some devices, the Shizuku user service does not start. This occurs on some MediaTek and Transsion devices with Android 15. The application then starts the helper process directly through Shizuku. The first connection can take 10 seconds longer. The application remembers the start method that operates.

### 5.3 Accessibility mode

This mode is only in a build that you make with `avg.accessibility=true` (see section 3.3). The default build does not have it.

1. In the section **Access mode**, select **Accessibility**.
2. Select **Open accessibility settings**.
3. Enable the service **Android Virtual Gamepad**.
4. Select **Add key**.
5. Press a button on the gamepad.
6. Select the action for the key.
7. Select **Save**.

**NOTE:** If a TV remote control sends the same key code, the service also changes the key of the remote control. Do not map the D-pad keys if you use the remote control.

### 5.4 Start at boot

The application can start the pads when the device starts.

1. In the section **Virtual gamepads**, enable **Start pads at boot**.
2. Select each pad that must start. Enable **Start Pad N at boot** for it. A pad needs a source device to start at boot.
3. Restart the device to test.

What happens at boot:

1. Android sends the boot message to the application. The application starts its foreground service and shows its notification.
2. The service connects to the helper process (see the table below).
3. The service starts each pad that you selected. A pad starts even if its source device is not connected. The pad then waits, and it starts to work when the controller appears.
4. If no pad starts, the service stops.

| Mode | Requirement at boot |
|---|---|
| Root | The root manager must allow the application without a question. Select the option to remember the choice in the root manager. The application tries for 60 seconds. |
| Shizuku | Shizuku must be in operation. The application waits for up to 10 minutes. It does not ask for the Shizuku permission at boot. Give the permission before you restart. |
| Accessibility | The application does nothing. Android starts the accessibility service by itself when it is enabled in the system settings. |

**NOTE:** Shizuku does not start by itself on most devices. Shizuku has its own options to start at boot. Which options are available depends on your device and on the Shizuku version. Refer to the Shizuku application.

**NOTE:** The application starts after the first unlock of the device. It cannot start before the unlock.

**NOTE:** Some device makers stop applications at boot, or they have an "autostart" permission. Allow autostart for the application and disable battery optimization for it.

**NOTE:** The application also starts the pads again after you update the application. The update stops the helper process.

**NOTE:** A pad that waits for its source device already exists as a virtual gamepad. Android lists it as a connected gamepad that sends no input.

## 6 Mapping

### 6.1 Principle

A mapping is a list of rules. Each rule connects one source control to one virtual control.

| Source | Virtual control | Operation |
|---|---|---|
| Button | Button | The virtual button is pressed when the source button is pressed. |
| Button | Axis or trigger | The axis goes to its maximum value when the button is pressed. In digital trigger mode, L2 or R2 is pressed. |
| Axis | Axis or trigger | The value is scaled to the range of the virtual axis. A dead zone is applied to the sticks. |
| Axis | Button or D-pad | The button is pressed when the axis goes past the threshold (50 percent). |

If more than one rule connects to the same control, a button is pressed when any rule is active. For an axis, the values are added.

### 6.2 Set a mapping

1. Select a preset: **standard** (the source uses standard Linux gamepad codes) or **PS2 example** (a typical layout of PS2 adapters).
2. For each virtual control, select **Learn**.
3. Press the button or move the stick on the source device in the next 8 seconds.
4. Examine the name that shows below the control, for example `BTN_SOUTH`.
5. Select **Invert** for a stick if the direction is not correct.

**NOTE:** The layout of the buttons is different for each PS2 adapter. The preset **PS2 example** is a start point. Use **Learn** to make it correct for your adapter.

**NOTE:** Learn does not operate while the remapper operates. Select **Stop** first.

Changes to the rules apply immediately when the remapper operates. Changes to the output need a **Stop** and a **Start**.

### 6.3 Virtual gamepad settings

#### Identity

Android selects the key layout of a gamepad from its vendor ID and product ID. Applications also use the ID to show the correct button symbols. Select an identity in the section **Output**.

The application sends the button codes that this key layout expects. The PlayStation 3 and PlayStation 4 identities use button codes that are different from the Xbox 360 codes.

The mapping does not depend on the identity. When you select a different identity, you do not need to use **Learn** again.

| Profile | ID | Name | Axes | D-pad | L2/R2 |
|---|---|---|---|---|---|
| Xbox 360 | `045e:028e` | Microsoft X-Box 360 pad | Right stick `ABS_RX`/`ABS_RY`, triggers `ABS_Z`/`ABS_RZ` | Hat | Analog |
| Xbox 360 v2 | `045e:028f` | Microsoft X-Box 360 pad v2 | Same as Xbox 360 | Hat | Analog |
| Xbox 360 wireless | `045e:02a1` | Xbox 360 Wireless Controller | Same as Xbox 360 | Hat | Analog |
| PlayStation 3 | `054c:0268` | Sony PLAYSTATION(R)3 Controller | Right stick `ABS_Z`/`ABS_RZ`, triggers `0x30`/`0x31` | Buttons | Digital |
| PlayStation 4 | `054c:09cc` | Wireless Controller | Right stick `ABS_Z`/`ABS_RZ`, triggers `ABS_RX`/`ABS_RY` | Hat | Both |
| Generic | `1209:0001` | Android Virtual Gamepad | Right stick `ABS_Z`/`ABS_RZ`, triggers `ABS_BRAKE`/`ABS_GAS` | Hat | Analog |
| Custom | You set it | You set it | You select Xbox, PlayStation or Generic axes | Setting | Setting |

When you select a profile, the application sets the D-pad and L2/R2 columns of that profile. You can change them after that.

**NOTE:** RetroArch treats pads that have the same vendor ID and product ID as one controller, even if the names are different. Select a different identity for each pad, for example Xbox 360 for Pad 1 and PlayStation 3 for Pad 2. For more than one Xbox-style pad, use Xbox 360, Xbox 360 v2 and Xbox 360 wireless. Android has a key layout file with the same mapping for these three IDs (Android 11 and later). The Generic identity changes its product ID for each pad by itself: `1209:0001` for Pad 1, `1209:0011` for Pad 2 and `1209:0021` for Pad 3.

In the PlayStation profiles, the Android names of the face buttons are: A = Cross, B = Circle, X = Square, Y = Triangle. Select = Share (PS4) or Select (PS3), Start = Options (PS4) or Start (PS3), Mode = PS button.

**NOTE:** The application does not simulate the touchpad, the motion sensors, the light bar or the rumble of the PlayStation gamepads.

**NOTE:** The key layouts that Android uses for these IDs can be different between Android versions and device makers. Use `adb shell dumpsys input` to see the device. If a button or an axis is wrong, select another axis layout in the Custom profile.

**NOTE:** Android has more key layout files for some versions of the PlayStation gamepads (for example `Vendor_054c_Product_0268_Version_8111.kl`). These files use different button codes. The virtual gamepad has the version `0x0110`, so Android uses the files without a version. The application sends the codes for these files.

**NOTE:** The virtual gamepad can have the same identity as a real gamepad. The application ignores a device with the identity of its own virtual gamepad when it looks for the source device.

#### L2/R2 output

| Setting | Description |
|---|---|
| Analog axes | L2 and R2 are trigger axes. The virtual gamepad has no L2 or R2 button. |
| Digital buttons | L2 and R2 are buttons. The identity gives the button codes (`BTN_TL2` and `BTN_TR2` for Xbox 360 and Generic). The virtual gamepad has no trigger axes. The button is pressed at 50 percent of the trigger range. |
| Both | The virtual gamepad has trigger axes and buttons. A real PlayStation 4 gamepad does this. Some applications use both and can react two times. |

**NOTE:** The key layout of Android for the Xbox 360 identity can ignore the L2/R2 buttons. For digital L2/R2, use a PlayStation profile or the Generic profile.

#### Other settings

| Setting | Description |
|---|---|
| D-pad as hat axis | The D-pad uses the axes `ABS_HAT0X` and `ABS_HAT0Y`. If you disable this setting, the D-pad uses buttons. The Xbox 360 and Generic identities use `BTN_DPAD_UP`, `BTN_DPAD_DOWN`, `BTN_DPAD_LEFT` and `BTN_DPAD_RIGHT`. The PlayStation 3 identity uses the codes `0x124` to `0x127`. The key layout of the PlayStation 4 identity has no D-pad buttons. Enable this setting for the PlayStation 4 identity. |
| Grab source | The application takes sole control of the source device (`EVIOCGRAB`). The original events do not go to Android. Keep this setting enabled to prevent double input. |

### 6.4 Use more than one pad

1. In the section **Virtual gamepads**, select **Add pad**.
2. Select **Pad 2**. All settings below the section belong to the selected pad.
3. Select the source device of Pad 2, set its mapping and set its identity.
4. Select **Start pad 2**.

Each pad starts and stops by itself. Select **Stop all** to stop all pads.

| Rule | Description |
|---|---|
| One source for one pad | A source device can feed only one pad. The helper refuses to start a pad if another pad uses the same source device. |
| Identity | Two pads can have the same identity, for example two Xbox 360 gamepads. Android gives each pad its own device ID. Some applications, for example RetroArch, then see only one controller. Select a different identity for each pad (see section 6.3). |
| Name | Pad 2 and later get a number in the name (for example `Microsoft X-Box 360 pad #2`). Pad 1 keeps the name of the identity. The Custom profile uses the name that you set, as it is. |
| Output | Several pads need the virtual gamepad output, or an existing-device output with a different target device for each pad. Key injection cannot separate the pads. |
| Dual-port adapters | A dual-port PS2 adapter usually shows two `/dev/input/eventX` nodes. Use one node for each pad. |

**NOTE:** A real gamepad and a pad of this application can have the same identity. The application then does not use that real gamepad as a source device.

The section **Source device** shows the text **Identity of the virtual gamepad of Pad N** below each device that has the identity of a virtual gamepad. You cannot select these devices.

### 6.5 Keyboard keys

Use this function to send keyboard keys from the controller. For example, you can map the D-pad to the arrow keys, or a button to Enter or Esc.

1. Select the pad in the section **Virtual gamepads**.
2. In the section **Keyboard keys**, select **Add key mapping**.
3. Press a button or move a stick on the source device in the next 8 seconds.
4. Select the key from the list. Select a group (for example Letters or Arrows) to see the keys.
5. Select **Start**.

You can map one source control to more than one key. You can map more than one source control to the same key. To send a key combination, map the modifier key (Left Shift, Left Ctrl, Left Alt, Left Meta) to a separate source control.

You can use keyboard keys together with the gamepad output. To send only keyboard keys, select the output **Keyboard only**. The application then does not create a virtual gamepad. This mode needs at least one key mapping.

| Setting | Description |
|---|---|
| Auto | The application tries a virtual keyboard first. If this is not possible, it uses key injection. |
| Virtual keyboard | The application creates a keyboard device with `/dev/uinput`. Android sees a physical keyboard. Android can hide the on-screen keyboard while the device exists. Applications that read raw keyboard events can see the keys. |
| Key injection | The application sends Android key events to the focused application. The on-screen keyboard stays available. Android does not repeat a key that you hold. |

**NOTE:** The keys that the application sends use the key layout of the device for the characters. A different keyboard layout can give different characters on the screen.

### 6.6 Rumble

When a game asks the virtual gamepad to rumble, Android sends the request to the application. The application sends the request to the real controller, to the phone vibrator, or to both.

| Setting | Description |
|---|---|
| Off | The virtual gamepad does not advertise rumble. |
| Controller | The application sends the rumble to the source device. The source device must support `FF_RUMBLE`, and the helper must have write access to it. |
| Phone | The application vibrates the phone. The strength is the larger of the two motor values. |
| Both | The application does the two actions. |

The status line of the pad shows which rumble targets operate. The status line shows one of these texts for the controller:

| Text | Meaning |
|---|---|
| `controller` | The source device supports rumble and the helper can write to it. |
| `controller not writable` | The helper cannot open the source device for writing. This can occur in Shizuku mode (SELinux). Use Root mode, or use **Phone**. |
| `controller not supported` | The driver of the source device does not offer rumble (`FF_RUMBLE`). Many generic adapters do this. |

The application opens the source device for reading and writing, and it uses the same file handle for the grab and for the rumble. The Linux kernel ignores rumble commands from a different handle of a grabbed device.

**NOTE:** Rumble operates only with the virtual gamepad output. It does not operate with key injection or in Accessibility mode. With the output **Existing device**, the target device can have its own rumble.

**NOTE:** Many PS2 adapters do not give rumble to Linux. Use the setting **Phone** with these adapters.

**NOTE:** An Android TV has no vibrator. The setting **Phone** has no effect on an Android TV.

**NOTE:** Some Android versions stop vibration from an application that is in the background. The application uses vibration types that Android allows in the background. Test this on your device.

## 7 Android TV

- The application has a Leanback launcher entry and a banner.
- The UI operates with the D-pad and the select button of a remote control.
- A touch screen is not necessary.
- Use ADB to install the application and to start Shizuku, if the TV has no file manager.

## 8 Limits

- Key injection sends events from a virtual keyboard device. Some games ignore these events. Use the virtual gamepad output for these games.
- A virtual keyboard can hide the on-screen keyboard.
- Key injection uses hidden Android APIs. A new Android version can break it.
- The direct start of the helper process in Shizuku mode uses hidden Android APIs too. A new Android version can break it.
- Rumble does not support the effects of other types than rumble. Android sends rumble effects only.
- The application does not support motion sensors or touchpads.
- The application supports a maximum of four pads.
- Accessibility mode has no pads. It supports one mapping list.
- The helper process stops when Android stops the application. The application uses a foreground service to prevent this. Disable battery optimization for the application on devices that stop it.
- The application starts the pads at boot only in Root and Shizuku mode. The helper process must be available (see section 5.4).

## 9 Troubleshooting

| Symptom | Cause | Remedy |
|---|---|---|
| The device list is empty | The helper cannot read `/dev/input` | Use Root mode, or check that Shizuku operates. |
| The message says `/dev/uinput: EACCES` | SELinux or file permissions prevent access | Use Root mode, select an existing device as output, or use key injection. |
| The message says `/dev/uinput: ENOENT` | The kernel has no uinput module | Select an existing device or use key injection. |
| The message says `... is already used by pad N` | Two pads use the same source device | Select a different source device for one of the pads. |
| Each press shows twice in a game | The original events and the remapped events both reach the game | Enable **Grab source**. |
| A stick moves in the wrong direction | The axis polarity is different | Select **Invert** for that stick. |
| The D-pad does not operate | The adapter reports the D-pad as `ABS_X` and `ABS_Y` | Use **Learn** for each D-pad direction and move the D-pad. |
| The game does not rumble | The game does not use the Android vibration API, or the output is not the virtual gamepad | Use the output **Virtual gamepad**. Set **Rumble** to **Controller**, **Phone** or **Both**. |
| The status shows `controller not supported` | The source device has no rumble, or the helper cannot write to it | Use **Phone**, or use Root mode. |
| The on-screen keyboard does not show | The virtual keyboard is active | Select **Key injection** for the keyboard keys, or stop the pad. |
| The message stays on `Starting Shizuku helper...`, or says `Shizuku helper did not start` | The helper process stopped before it connected to the application | Select **Reconnect**. If the message stays, select **Diagnostics**. Look for the lines with the tag `ShizukuServiceStarter`. |
| The pads do not start at boot | Root is not allowed without a question, Shizuku does not operate, or the device maker stopped the application | Do the steps of section 5.4. Look at the log with the tag `AVG-boot`. |
| The remapper stops after a short time | Android stopped the application | Disable battery optimization for the application. |
| The status shows `source lost` | The controller is disconnected | Connect the controller again. The remapper reconnects automatically. |
| Google Play Protect blocks the installation | The APK has an accessibility service | Use the default build. It has no accessibility service. |

To see the log of the application and of its helper process, select **Diagnostics** in the section **Access mode**. Select **Copy** or **Share** to send the text.

The window shows only the lines of this application. The log of the helper process needs Shizuku or Root. Without them, the window shows only the connection history and the lines of the UI process.

The window also shows each virtual gamepad as Android sees it: the device class, the ID, and the key layout file with its mapping. This needs Shizuku or Root. Start the pads before you select **Diagnostics**.

To check what an application receives from a virtual gamepad, select **Input test** in the section **Access mode**. Press a button on the controller. The window shows the key that Android gives to the application, the scan code that the device sent, and the device name.

## 10 Project structure

```
app/src/main/
  aidl/                  IGamepadService.aidl (UI <-> helper interface)
  cpp/                   agp_native.cpp (evdev read, uinput create, event write)
  java/com/avgamepad/
    model/               Controls, rules, configuration, presets
    engine/              Remapper, gamepad and keyboard sinks, rumble bridge, device scanner, event loop
    service/             Helper implementation, Shizuku and root wrappers
    backend/             Connection to the helper (root or Shizuku), diagnostics
    accessibility/       State of the accessibility service
    ui/                  Compose screens (phone and TV), pad selection
    data/                Saved settings (one configuration for each pad)
app/src/accessibility/   Optional: accessibility service, its manifest entry and its resources
```

## 11 Security

**WARNING:** The helper process has root or shell permission. Install the application only from a source that you trust.

In a build with Accessibility mode, the accessibility service reads only key events of gamepads. It does not read the content of the screen.

## 12 Licence

MIT. Refer to the file `LICENSE`.
