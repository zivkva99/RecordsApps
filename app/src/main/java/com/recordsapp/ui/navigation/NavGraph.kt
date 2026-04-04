package com.recordsapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.recordsapp.ui.screens.addcopy.AddCopyScreen
import com.recordsapp.ui.screens.addeditalbum.AddEditAlbumScreen
import com.recordsapp.ui.screens.albumdetail.AlbumDetailScreen
import com.recordsapp.ui.screens.albumlist.AlbumListScreen

@Composable
fun RecordsNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.AlbumList.route
    ) {
        composable(Screen.AlbumList.route) {
            AlbumListScreen(
                onAlbumClick = { albumId ->
                    navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                },
                onAddAlbumClick = {
                    navController.navigate(Screen.AddAlbum.route)
                }
            )
        }

        composable(Screen.AddAlbum.route) {
            AddEditAlbumScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AlbumDetail.route,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType })
        ) {
            AlbumDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onEditAlbum = { albumId ->
                    navController.navigate(Screen.EditAlbum.createRoute(albumId))
                },
                onAddCopy = { albumId ->
                    navController.navigate(Screen.AddCopy.createRoute(albumId))
                }
            )
        }

        composable(
            route = Screen.EditAlbum.route,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType })
        ) {
            AddEditAlbumScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AddCopy.route,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType })
        ) {
            AddCopyScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
