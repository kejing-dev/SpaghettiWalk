package io.embodit.spaghettiwalk.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.spaghettiwalk.platform.WalkLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.spaghettiwalk.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.spaghettiwalk.DEBUG -p io.embodit.spaghettiwalk --es cmd 'walk a' --ez fast true"
 *
 * Extras: `cmd` = command name with its arguments after it, or `a` = comma-separated numbers and `s` = text;
 * `fast` (boolean) plays `walk` eight times faster.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val a = intent.getStringExtra("a")
        val s = intent.getStringExtra("s")
        val fast = intent.getBooleanExtra("fast", false)
        WalkLog.i("CMD recv '$cmd' a='$a' s='$s' fast=$fast")
        // the Stage's state is main-thread only
        android.os.Handler(android.os.Looper.getMainLooper()).post { DebugCommands.run(context.applicationContext, cmd, a, s, fast) }
    }
}
