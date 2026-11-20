package com.example.data.backup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Lives in its own process (":restart", see the manifest) so it survives the main process being killed:
 * it kills the app process, starts the launcher intent again and ends itself.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val next = intent.getParcelableExtra<Intent>(EXTRA_NEXT)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0) Process.killProcess(pid)
        if (next != null) startActivity(next)
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val EXTRA_NEXT = "next_intent"
        const val EXTRA_PID = "main_pid"
    }
}
