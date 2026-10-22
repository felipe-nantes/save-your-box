package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, this app was updated, or the sleep timer ran out. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            RemoteServer.start(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
            RemoteServer.start(context);
        } else if (RemoteServer.SLEEP.equals(action)) {
            final PendingResult pending = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    RemoteServer.setTimer(app, 0);
                    RemoteInput.sleep(app);
                } catch (java.io.IOException e) {
                    AppLog.w("sleep timer: " + e.getMessage());
                } finally {
                    pending.finish();
                }
            }, "sleep-timer").start();
        }
    }
}
