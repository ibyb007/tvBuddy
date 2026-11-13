package com.example.updater

import android.content.Context

/** Process-wide holder of the updater parts, shared by the screen's view model and the background jobs. */
class Updater private constructor(context: Context) {
    val preferences = UpdatePreferences(context)
    val repository = UpdateRepository()
    val downloadStore = UpdateDownloadStore(context, preferences)

    companion object {
        @Volatile private var instance: Updater? = null

        fun get(context: Context): Updater = instance ?: synchronized(this) {
            instance ?: Updater(context.applicationContext).also { instance = it }
        }
    }
}
