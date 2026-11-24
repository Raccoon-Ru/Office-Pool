package com.officepool;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class InboxBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString("token", "")
                .isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) return;
        Intent service = new Intent(context, MatchInboxService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
