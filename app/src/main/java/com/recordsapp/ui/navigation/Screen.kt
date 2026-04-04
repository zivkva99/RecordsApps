package com.recordsapp.ui.navigation

sealed class Screen(val route: String) {
    data object AlbumList : Screen("album_list")
    data object AddAlbum : Screen("add_album")
    data object AlbumDetail : Screen("album_detail/{albumId}") {
        fun createRoute(albumId: Long) = "album_detail/$albumId"
    }
    data object EditAlbum : Screen("edit_album/{albumId}") {
        fun createRoute(albumId: Long) = "edit_album/$albumId"
    }
    data object AddCopy : Screen("add_copy/{albumId}") {
        fun createRoute(albumId: Long) = "add_copy/$albumId"
    }
}
