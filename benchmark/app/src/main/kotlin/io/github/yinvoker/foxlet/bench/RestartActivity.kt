package io.github.yinvoker.foxlet.bench

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Restarts the benchmark in a fresh process to isolate measured runs from
 * previous model residency and process caches. This activity runs in :bounce,
 * relaunches MainActivity after the main process exits, then exits itself.
 * The SDK supports creating another client after shutdown completes; process
 * restart is a benchmark isolation policy.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("autorun", true)
            .putExtra("threads", intent.getIntExtra("threads", 1))
            .putExtra("workspace", intent.getIntExtra("workspace", 128))
        window.decorView.postDelayed({
            startActivity(target)
            finishAndRemoveTask()
            Runtime.getRuntime().exit(0)
        }, 400)
    }
}
