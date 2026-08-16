package com.dimroom.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dimroom.ui.editor.EditorScreen
import com.dimroom.ui.library.LibraryScreen
import com.dimroom.ui.settings.SettingsScreen
import com.dimroom.ui.settings.SettingsViewModel

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{photoId}"

    fun editor(photoId: String) = "editor/$photoId"
}

@Composable
fun DimroomNavHost(settingsViewModel: SettingsViewModel) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenPhoto = { photoId -> navController.navigate(Routes.editor(photoId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("photoId") { type = NavType.StringType }),
        ) {
            EditorScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
