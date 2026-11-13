package com.example.updater

import android.content.Context
import androidx.core.content.edit
import com.example.BuildConfig

/** Settings and bookkeeping of the in-app updater (SharedPreferences, readable from the background job too). */
class UpdatePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tvbuddy_update", Context.MODE_PRIVATE)

    /** Release the user dismissed in the banner; it is not offered by the banner again. */
    var ignoredTag: String?
        get() = prefs.getString("ignored_release_tag", null)
        set(value) = prefs.edit { if (value == null) remove("ignored_release_tag") else putString("ignored_release_tag", value) }

    var lastCheckAtMs: Long
        get() = prefs.getLong("last_check_at_ms", 0L)
        set(value) = prefs.edit { putLong("last_check_at_ms", value) }

    var updateBannerEnabled: Boolean
        get() = prefs.getBoolean("update_banner_enabled", true)
        set(value) = prefs.edit { putBoolean("update_banner_enabled", value) }

    var autoInstallEnabled: Boolean
        get() = prefs.getBoolean("auto_install_enabled", false)
        set(value) = prefs.edit { putBoolean("auto_install_enabled", value) }

    var autoInstallNoticeEnabled: Boolean
        get() = prefs.getBoolean("auto_install_notice_enabled", true)
        set(value) = prefs.edit { putBoolean("auto_install_notice_enabled", value) }

    /** Release installed by the background job; the next launch says so once. */
    var autoInstalledTag: String?
        get() = prefs.getString("auto_installed_release_tag", null)
        set(value) = prefs.edit { if (value == null) remove("auto_installed_release_tag") else putString("auto_installed_release_tag", value) }

    /** Tag + path of the update APK that is already downloaded, so it survives the app being closed. */
    val downloadedUpdate: Pair<String, String>?
        get() {
            val tag = prefs.getString("downloaded_release_tag", null)
            val path = prefs.getString("downloaded_apk_path", null)
            return if (tag != null && path != null) tag to path else null
        }

    fun setDownloadedUpdate(tag: String?, apkPath: String?) = prefs.edit {
        if (tag == null || apkPath == null) {
            remove("downloaded_release_tag"); remove("downloaded_apk_path")
        } else {
            putString("downloaded_release_tag", tag); putString("downloaded_apk_path", apkPath)
        }
    }

    /** Saved channel; on first use a canary build starts on canary, anything else on stable. */
    fun updateChannel(): UpdateChannel {
        val saved = UpdateChannel.fromStoredValue(prefs.getString("update_channel", null))
        if (saved != null) return saved
        val default = UpdateChannel.defaultForVersion(BuildConfig.VERSION_NAME)
        prefs.edit { putString("update_channel", default.storedValue) }
        return default
    }

    fun setUpdateChannel(channel: UpdateChannel) = prefs.edit {
        putString("update_channel", channel.storedValue)
        remove("ignored_release_tag")
    }
}
