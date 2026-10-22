package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One `<channel>` element of an imported XMLTV guide: its id, display names and icon. Programmes are matched to
 * playlist channels through this table (by id first, then by normalised display name) and its icon fills in logos
 * the playlist did not have, like IPTVnator does with the imported guide's channel metadata.
 */
@Entity(
    tableName = "epg_channels",
    indices = [Index(value = ["lowerId"])]
)
data class EpgChannelEntity(
    @PrimaryKey val channelId: String,
    val lowerId: String,
    /** First display name as written in the guide. */
    val displayName: String,
    /** All display names, normalised (see EpgMatcher.normalize) and joined by a tab. */
    val normNames: String,
    val iconUrl: String? = null
)
