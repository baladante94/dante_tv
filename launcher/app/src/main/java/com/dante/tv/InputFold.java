package com.dante.tv;

import android.media.tv.TvInputManager;

/**
 * Which bare input rows fold behind "More inputs" (from CleanYourTV's inputs_connected_only). state is the row's
 * TvInputManager state, or -1 when it has none to trust. An AV jack has no hotplug detection, so it always folds; an
 * HDMI port stays only while it reports connected: this Philips says "standby" for an empty port. A named HDMI-CEC
 * device (a Fire TV) has a row of its own and never folds, even asleep in standby.
 */
final class InputFold {
    private InputFold() {}

    static boolean folds(boolean hdmi, int state) {
        if (!hdmi) return true;
        return state != -1 && state != TvInputManager.INPUT_STATE_CONNECTED;
    }
}
