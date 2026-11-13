package com.example.updater

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "UpdateJobs"
private const val JOB_ID_CHECK = 10_020
private const val JOB_ID_INSTALL = 10_021
private const val JOB_ID_CHECK_NOW = 10_022
private const val CHECK_INTERVAL_MS = 12 * 60 * 60_000L

/**
 * Background update work. The periodic job checks for a new release and downloads it, so the banner can offer an
 * install without waiting for the download. On Android 12+, with automatic installs enabled, a second job installs the
 * downloaded update while the device is idle, without a confirmation screen.
 */
class UpdateJobService : JobService() {

    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val updater = Updater.get(applicationContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope = scope
        scope.launch {
            // A failed check, download or install asks for a retry with backoff instead of waiting for the next run.
            var retry = false
            try {
                when (params.jobId) {
                    JOB_ID_CHECK, JOB_ID_CHECK_NOW -> checkAndDownload(updater)
                    JOB_ID_INSTALL -> installDownloaded(updater)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoEligibleUpdateException) {
                Log.d(TAG, e.message.orEmpty())
            } catch (e: Exception) {
                Log.w(TAG, "Update job ${params.jobId} failed", e)
                retry = true
            } finally {
                jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobScope?.cancel()
        return true // reschedule on stop
    }

    private suspend fun checkAndDownload(updater: Updater) {
        val prefs = updater.preferences
        if (!prefs.updateBannerEnabled && !prefs.autoInstallEnabled) return

        updater.downloadStore.clearIfInstalled()
        val update = updater.repository.getLatestUpdate(prefs.updateChannel()).getOrThrow()
        prefs.lastCheckAtMs = System.currentTimeMillis()
        if (!VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)) return
        if (update.tag == prefs.ignoredTag) return

        updater.downloadStore.download(update).getOrThrow()
        Log.d(TAG, "Downloaded ${update.tag} in the background")
        if (prefs.autoInstallEnabled) scheduleIdleInstall(applicationContext)
    }

    private fun installDownloaded(updater: Updater) {
        val prefs = updater.preferences
        if (!canInstallSilently(applicationContext) || !prefs.autoInstallEnabled) return
        val (tag, apkFile) = updater.downloadStore.pendingInstall() ?: return
        if (tag == prefs.ignoredTag) return
        Log.d(TAG, "Installing $tag while the device is idle")
        prefs.autoInstalledTag = tag
        ApkInstaller.install(applicationContext, apkFile, background = true)
    }

    companion object {
        /** Silent self-updates need Android 12+ and the "install unknown apps" permission. */
        fun canInstallSilently(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ApkInstaller.canRequestPackageInstalls(context)

        fun schedulePeriodicCheck(context: Context) {
            val scheduler = context.jobScheduler()
            if (scheduler.hasPending(JOB_ID_CHECK)) return
            val job = JobInfo.Builder(JOB_ID_CHECK, ComponentName(context, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(CHECK_INTERVAL_MS)
                .setPersisted(true)
                .build()
            scheduler.scheduleSafely(job)
        }

        /** Downloads a newer release found by the in-app check without waiting for the next periodic run. */
        fun scheduleCheckNow(context: Context) {
            val job = JobInfo.Builder(JOB_ID_CHECK_NOW, ComponentName(context, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            context.jobScheduler().scheduleSafely(job)
        }

        fun cancelPeriodicCheck(context: Context) = context.jobScheduler().cancel(JOB_ID_CHECK)

        fun scheduleIdleInstall(context: Context) {
            if (!canInstallSilently(context)) return
            val job = JobInfo.Builder(JOB_ID_INSTALL, ComponentName(context, UpdateJobService::class.java))
                .setRequiresDeviceIdle(true)
                .setPersisted(true)
                .build()
            context.jobScheduler().scheduleSafely(job)
        }

        fun cancelIdleInstall(context: Context) = context.jobScheduler().cancel(JOB_ID_INSTALL)

        private fun Context.jobScheduler(): JobScheduler =
            getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

        private fun JobScheduler.hasPending(id: Int): Boolean = runCatching { getPendingJob(id) != null }.getOrDefault(false)

        private fun JobScheduler.scheduleSafely(job: JobInfo): Boolean =
            runCatching { schedule(job) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
    }
}
