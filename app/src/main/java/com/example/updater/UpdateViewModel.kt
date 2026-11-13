package com.example.updater

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "UpdateInstall"

data class UpdateUiState(
    val isChecking: Boolean = false,
    /** Newer release than the installed one, or null. */
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showBanner: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    /** One-shot toast text (e.g. "You are on the latest version"). */
    val feedbackMessage: String? = null,
    val updateBannerEnabled: Boolean = true,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val autoInstallEnabled: Boolean = false,
    val autoInstallSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    val autoInstallNoticeEnabled: Boolean = true,
    /** Release installed automatically since the last launch, shown in the banner until dismissed. */
    val installedUpdate: AppUpdate? = null
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val updater = Updater.get(application)
    private val prefs = updater.preferences
    private val store = updater.downloadStore

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
    private var updateCheckJob: Job? = null

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                _uiState.value.copy(
                    updateBannerEnabled = prefs.updateBannerEnabled,
                    updateChannel = prefs.updateChannel(),
                    autoInstallEnabled = prefs.autoInstallEnabled,
                    autoInstallNoticeEnabled = prefs.autoInstallNoticeEnabled
                ).also { store.clearIfInstalled() }
            }
            _uiState.update {
                it.copy(
                    updateBannerEnabled = loaded.updateBannerEnabled,
                    updateChannel = loaded.updateChannel,
                    autoInstallEnabled = loaded.autoInstallEnabled,
                    autoInstallNoticeEnabled = loaded.autoInstallNoticeEnabled
                )
            }
            consumeAutoInstalledUpdate()
            syncBackgroundJobs()
            if (loaded.updateBannerEnabled && !BuildConfig.DEBUG) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
        }
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!force && !_uiState.value.updateBannerEnabled) return

        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            val channel = _uiState.value.updateChannel
            _uiState.update {
                it.copy(
                    isChecking = true,
                    showBanner = false,
                    showUnknownSourcesDialog = false,
                    errorMessage = null,
                    feedbackMessage = null
                )
            }

            val dismissedTag = prefs.ignoredTag
            val result = updater.repository.getLatestUpdate(channel)
            prefs.lastCheckAtMs = System.currentTimeMillis()

            result
                .onSuccess { update ->
                    val remoteNewer = VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)
                    val downloadedFile = if (remoteNewer) store.downloadedFileFor(update) else null
                    // The release just installed carries the notes the "updated" banner can show.
                    val installedUpdate = _uiState.value.installedUpdate?.let { installed ->
                        if (installed.tag == update.tag) update else installed
                    }
                    if (remoteNewer && downloadedFile == null && update.tag != dismissedTag && !BuildConfig.DEBUG) {
                        UpdateJobService.scheduleCheckNow(context)
                    }
                    val shouldShow = remoteNewer && (force ||
                        (_uiState.value.updateBannerEnabled && dismissedTag != update.tag))

                    _uiState.update { state ->
                        state.copy(
                            isChecking = false,
                            update = update.takeIf { remoteNewer },
                            isUpdateAvailable = remoteNewer,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = downloadedFile?.absolutePath
                                ?: state.downloadedApkPath.takeIf { remoteNewer && state.update?.tag == update.tag },
                            showBanner = shouldShow || installedUpdate != null,
                            installedUpdate = installedUpdate,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (showNoUpdateFeedback && !remoteNewer) noUpdateFeedback(channel) else null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = null,
                            isUpdateAvailable = false,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            showBanner = it.installedUpdate != null,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (!showNoUpdateFeedback) null
                            else if (error is NoEligibleUpdateException) noUpdateFeedback(channel)
                            else error.message ?: "Could not check for updates"
                        )
                    }
                }
        }
    }

    private fun noUpdateFeedback(channel: UpdateChannel): String =
        if (channel == UpdateChannel.STABLE && VersionUtils.isCanary(BuildConfig.VERSION_NAME)) {
            "You are on a canary build. Waiting for the next stable release"
        } else {
            "You are on the latest version"
        }

    fun dismissBanner() {
        val tag = _uiState.value.update?.tag
        _uiState.update {
            it.copy(showBanner = false, showUnknownSourcesDialog = false, errorMessage = null, installedUpdate = null)
        }
        if (tag != null) viewModelScope.launch(Dispatchers.IO) { prefs.ignoredTag = tag }
    }

    fun dismissUnknownSourcesDialog() {
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
    }

    fun consumeFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    fun setUpdateBannerEnabled(enabled: Boolean) {
        val changed = _uiState.value.updateBannerEnabled != enabled
        _uiState.update {
            it.copy(
                updateBannerEnabled = enabled,
                showBanner = if (enabled) it.showBanner else false,
                showUnknownSourcesDialog = if (enabled) it.showUnknownSourcesDialog else false
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prefs.updateBannerEnabled = enabled }
            syncBackgroundJobs()
            if (enabled && changed && !BuildConfig.DEBUG) checkForUpdates(force = false, showNoUpdateFeedback = false)
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        if (_uiState.value.updateChannel == channel) return

        updateCheckJob?.cancel()
        UpdateJobService.cancelIdleInstall(context)
        _uiState.update {
            it.copy(
                updateChannel = channel,
                isChecking = false,
                update = null,
                isUpdateAvailable = false,
                isDownloading = false,
                downloadProgress = null,
                downloadedApkPath = null,
                showBanner = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
                feedbackMessage = null
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.clear()
                prefs.setUpdateChannel(channel)
            }
            // Switching channel shows what that channel offers right away.
            checkForUpdates(force = true, showNoUpdateFeedback = false)
        }
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return
        if (_uiState.value.isDownloading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f, errorMessage = null, showBanner = true) }

            val result = withContext(Dispatchers.IO) {
                store.download(update) { downloaded, total ->
                    val progress = total?.takeIf { it > 0 }?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) }
                    _uiState.update { it.copy(downloadProgress = progress) }
                }
            }

            result
                .onSuccess { file ->
                    _uiState.update {
                        it.copy(isDownloading = false, downloadProgress = null, downloadedApkPath = file.absolutePath, errorMessage = null)
                    }
                    installUpdateOrRequestPermission()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            errorMessage = error.message ?: "Download failed",
                            showBanner = true
                        )
                    }
                }
        }
    }

    fun installUpdateOrRequestPermission() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            _uiState.update { it.copy(errorMessage = "The downloaded update is missing. Try again", downloadedApkPath = null, showBanner = true) }
            return
        }

        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(showUnknownSourcesDialog = true, showBanner = true) }
            return
        }

        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ApkInstaller.install(context, apkFile, background = false) }
                .onFailure {
                    Log.w(TAG, "Session install failed, falling back to the system installer", it)
                    runCatching { ApkInstaller.launchSystemInstaller(context, apkFile) }
                        .onFailure { error -> Log.w(TAG, "System installer failed", error) }
                }
        }
    }

    // Shows the "updated" banner once after the idle job installed a release, then forgets it.
    private suspend fun consumeAutoInstalledUpdate() {
        val tag = withContext(Dispatchers.IO) { prefs.autoInstalledTag } ?: return
        // Still older than the installed version means the install has not happened yet.
        if (VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) return
        withContext(Dispatchers.IO) { prefs.autoInstalledTag = null }
        if (VersionUtils.isRemoteNewer(BuildConfig.VERSION_NAME, tag)) return
        if (!_uiState.value.autoInstallNoticeEnabled) return
        val installed = AppUpdate(
            tag = tag, title = tag, notes = "", releaseUrl = null,
            assetName = "", assetUrl = "", assetSizeBytes = null
        )
        _uiState.update { it.copy(installedUpdate = installed, showBanner = true) }
    }

    fun setAutoInstallNoticeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoInstallNoticeEnabled = enabled) }
        viewModelScope.launch(Dispatchers.IO) { prefs.autoInstallNoticeEnabled = enabled }
    }

    fun setAutoInstallEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoInstallEnabled = enabled) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prefs.autoInstallEnabled = enabled }
            syncBackgroundJobs()
            if (enabled && withContext(Dispatchers.IO) { store.pendingInstall() } != null) {
                UpdateJobService.scheduleIdleInstall(context)
            }
        }
    }

    // Background checks run while the banner or automatic installs are on; the idle install job only with the latter.
    private fun syncBackgroundJobs() {
        if (BuildConfig.DEBUG) return
        val state = _uiState.value
        if (state.updateBannerEnabled || state.autoInstallEnabled) {
            UpdateJobService.schedulePeriodicCheck(context)
        } else {
            UpdateJobService.cancelPeriodicCheck(context)
        }
        if (!state.autoInstallEnabled) UpdateJobService.cancelIdleInstall(context)
    }

    fun openUnknownSourcesSettings() {
        listOfNotNull(
            ApkInstaller.buildUnknownSourcesSettingsIntent(context),
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).any { runCatching { context.startActivity(it) }.isSuccess }
    }
}
