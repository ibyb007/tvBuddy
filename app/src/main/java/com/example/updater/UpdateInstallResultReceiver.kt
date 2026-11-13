package com.example.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

private const val TAG = "UpdateInstall"

/**
 * Receives the outcome of an update install session. The confirmation screen is shown only for installs the user
 * started; a background install that still needs confirmation is dropped and the downloaded APK waits for the banner.
 * A failed install falls back to the system installer screen.
 */
class UpdateInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val background = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        val apkFile = intent.getStringExtra(EXTRA_APK_PATH)?.let(::File)
        Log.i(TAG, "Session $sessionId status=$status background=$background " +
            "message=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (background) {
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                } else {
                    confirmationIntent(intent)?.let { confirm ->
                        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure {
                                Log.w(TAG, "Install confirmation screen unavailable", it)
                                runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                                fallBack(context, apkFile, background = false)
                            }
                    }
                }
            }
            else -> fallBack(context, apkFile, background)
        }
    }

    private fun fallBack(context: Context, apkFile: File?, background: Boolean) {
        if (background || apkFile == null || !apkFile.exists()) return
        runCatching { ApkInstaller.launchSystemInstaller(context, apkFile) }
            .onFailure { Log.w(TAG, "System installer failed", it) }
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val EXTRA_APK_PATH = "com.example.updater.APK_PATH"
        private const val EXTRA_BACKGROUND = "com.example.updater.BACKGROUND"

        fun statusReceiver(context: Context, sessionId: Int, apkFile: File, background: Boolean): PendingIntent {
            val intent = Intent(context, UpdateInstallResultReceiver::class.java)
                .putExtra(EXTRA_APK_PATH, apkFile.absolutePath)
                .putExtra(EXTRA_BACKGROUND, background)
            // The installer adds the status extras, so the intent has to stay mutable.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        }
    }
}
