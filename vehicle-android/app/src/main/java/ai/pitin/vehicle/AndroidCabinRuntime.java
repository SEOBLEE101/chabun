package ai.pitin.vehicle;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Android side of {@link CabinActuator}: the main-thread scheduler, a small private ledger store and logcat. */
final class AndroidCabinRuntime {
    private AndroidCabinRuntime() { }

    static CabinActuator.Scheduler scheduler() {
        CabinActuator.log = line -> android.util.Log.i(CabinActuator.TAG, line);
        Handler main = new Handler(Looper.getMainLooper());
        return new CabinActuator.Scheduler() {
            public long now() { return SystemClock.uptimeMillis(); }
            public void post(Runnable task, long delayMs) { main.postDelayed(task, delayMs); }
            public void cancel(Runnable task) { main.removeCallbacks(task); }
        };
    }

    /** Excluded from backup and device transfer by the app's backup rules. */
    static CabinActuator.Store store(Context context, String name) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences("pitin_cabin_ledger", Context.MODE_PRIVATE);
        return new CabinActuator.Store() {
            public String load() { return prefs.getString(name, null); }
            public void save(String value) { prefs.edit().putString(name, value).apply(); }
        };
    }
}
