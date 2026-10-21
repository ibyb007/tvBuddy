package com.example.ui

import com.example.data.model.ImportCounts

/** Progress of adding a playlist, as the Add playlist dialog shows it: form -> Processing -> "Playlist is processed". */
sealed class ImportUiState {
    data object Idle : ImportUiState()
    /** [message] is the live progress line ("Movies: 12,400 ..."); blank until the first step reports. */
    data class Processing(val message: String = "") : ImportUiState()
    data class Done(val playlistId: Long, val name: String, val counts: ImportCounts) : ImportUiState()
    data class Failed(val message: String) : ImportUiState()
}
