package com.music.bitchord.data.model

object PromotedSongs {
    val ANNA_NAGAR_FIGURES = Song(
        videoId = "dWBf4bIX-uo",
        title = "RustBucket - ANNA NAGAR FIGURES [Official Lyrics Video]",
        artist = "RustBucket Official",
        thumbnailUrl = "https://i.ytimg.com/vi/dWBf4bIX-uo/hqdefault.jpg",
        durationText = "4:00",
        artistId = "UCE0gutp2J2OP40aIkrMagSA",
        artists = listOf(ArtistRef(name = "RustBucket Official", browseId = "UCE0gutp2J2OP40aIkrMagSA")),
        isVideo = false,
        isVideoOrigin = false,
    )

    fun matchesAnnaNagar(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val q = query.trim().lowercase()
        val compact = q.filter { it.isLetterOrDigit() }
        return compact.contains("annanagar") ||
            (q.contains("anna") && q.contains("nagar")) ||
            (q.contains("nagar") && q.contains("figure")) ||
            (q.contains("anna") && q.contains("figure")) ||
            q.contains("figurellan") ||
            (q.contains("rust") && q.contains("bucket"))
    }
}
