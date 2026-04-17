package com.github.jvsena42.eco.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.jvsena42.eco.ui.decks.DeckDetailRoute
import com.github.jvsena42.eco.ui.decks.DeckEditorRoute
import com.github.jvsena42.eco.ui.decks.EditCardRoute
import com.github.jvsena42.eco.ui.import_flow.PasteRoute
import com.github.jvsena42.eco.ui.import_flow.PublishDeckRoute
import com.github.jvsena42.eco.ui.onboarding.OnboardingRoute

@Composable
fun EchoNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                onNavigateHome = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.MAIN) {
            MainScreen(
                onNavigateDeckDetail = { deckId ->
                    navController.navigate(Routes.deckDetail(deckId))
                },
                onNavigateCreateDeck = {
                    navController.navigate(Routes.DECK_EDITOR_NEW)
                },
                onNavigateImport = {
                    navController.navigate(Routes.IMPORT_PASTE)
                },
            )
        }
        composable(
            route = Routes.DECK_DETAIL,
            arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val deckId = backStackEntry.arguments?.getString("deckId") ?: return@composable
            DeckDetailRoute(
                deckId = deckId,
                onBack = { navController.popBackStack() },
                onEditDeck = { id -> navController.navigate(Routes.deckEditor(id)) },
            )
        }
        composable(
            route = Routes.DECK_EDITOR,
            arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val deckId = backStackEntry.arguments?.getString("deckId")
            DeckEditorRoute(
                deckId = deckId,
                onBack = { navController.popBackStack() },
                onEditCard = { dId, cId -> navController.navigate(Routes.editCard(dId, cId)) },
                onSaved = { savedDeckId ->
                    navController.popBackStack()
                    navController.navigate(Routes.deckDetail(savedDeckId))
                },
            )
        }
        composable(Routes.DECK_EDITOR_NEW) {
            DeckEditorRoute(
                deckId = null,
                onBack = { navController.popBackStack() },
                onEditCard = { dId, cId -> navController.navigate(Routes.editCard(dId, cId)) },
                onSaved = { savedDeckId ->
                    navController.popBackStack()
                    navController.navigate(Routes.deckDetail(savedDeckId))
                },
            )
        }
        composable(Routes.IMPORT_PASTE) {
            PasteRoute(
                onCancel = { navController.popBackStack() },
                onNext = { navController.navigate(Routes.IMPORT_PUBLISH) },
            )
        }
        composable(Routes.IMPORT_PUBLISH) {
            PublishDeckRoute(
                onBack = { navController.popBackStack() },
                onPublished = { deckId ->
                    // Pop both import screens and navigate to deck detail
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                    navController.navigate(Routes.deckDetail(deckId))
                },
            )
        }
        composable(
            route = Routes.EDIT_CARD,
            arguments = listOf(
                navArgument("deckId") { type = NavType.StringType },
                navArgument("cardId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val deckId = backStackEntry.arguments?.getString("deckId") ?: return@composable
            val cardId = backStackEntry.arguments?.getString("cardId") ?: return@composable
            EditCardRoute(
                deckId = deckId,
                cardId = cardId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
