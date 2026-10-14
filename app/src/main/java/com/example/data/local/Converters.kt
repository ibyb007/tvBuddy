package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.model.PlaylistType

class Converters {
    @TypeConverter
    fun fromPlaylistType(value: PlaylistType): String = value.name

    @TypeConverter
    fun toPlaylistType(value: String): PlaylistType {
        return try {
            PlaylistType.valueOf(value)
        } catch (_: Exception) {
            PlaylistType.M3U
        }
    }
}
