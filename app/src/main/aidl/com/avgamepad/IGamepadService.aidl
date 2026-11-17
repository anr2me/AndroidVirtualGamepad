package com.avgamepad;

import com.avgamepad.IRumbleCallback;

// Runs in a privileged process (root or Shizuku shell). All data is JSON text.
// A "slot" is one virtual gamepad (0, 1, 2 ...). Each slot has its own engine.
interface IGamepadService {
    void destroy() = 16777114;          // Shizuku requires this transaction id
    int getUid() = 1;
    void init(String nativeLibDir) = 2;
    String listDevices() = 3;
    String probe() = 4;
    String start(int slot, String configJson) = 5;
    void stop(int slot) = 6;
    String status() = 7;                // {"slots":[{...}, ...]}
    void updateConfig(int slot, String configJson) = 8;
    String learn(String devicePath, int timeoutMs) = 9;
    void stopAll() = 10;
    void setRumbleCallback(IRumbleCallback callback) = 11;
}
