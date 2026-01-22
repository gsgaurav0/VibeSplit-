package com.example.dualaudio.data

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri
)
