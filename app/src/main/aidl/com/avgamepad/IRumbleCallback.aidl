package com.avgamepad;

// Implemented by the UI app. The helper calls it when Android asks the virtual gamepad to rumble.
// strong and weak are 0..65535. durationMs 0 with strong = weak = 0 means stop.
oneway interface IRumbleCallback {
    void onRumble(int slot, int strong, int weak, int durationMs);
}
