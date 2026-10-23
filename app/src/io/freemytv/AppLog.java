package io.freemytv;

import android.util.Log;

/** The app's log (adb logcat -s SaveYourBox). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("SaveYourBox", message);
    }
}
