package com.github.jvsena42.loopky.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.ui.decks.DeckDetailRoute
import com.github.jvsena42.loopky.ui.decks.DeckEditorRoute
import com.github.jvsena42.loopky.ui.decks.EditCardRoute
import com.github.jvsena42.loopky.ui.importflow.BulkImportRoute
import com.github.jvsena42.loopky.ui.importflow.PasteRoute
import com.github.jvsena42.loopky.ui.importflow.PublishDeckRoute
import com.github.jvsena42.loopky.ui.importflow.TriageEditCardRoute
import com.github.jvsena42.loopky.ui.importflow.TriageRoute
import com.github.jvsena42.loopky.ui.onboarding.OnboardingRoute
import com.github.jvsena42.loopky.ui.profile.FollowListRoute
import com.github.jvsena42.loopky.ui.profile.FriendProfileRoute
import com.github.jvsena42.loopky.ui.search.SearchRoute
import com.github.jvsena42.loopky.ui.settings.SettingsRoute
import com.github.jvsena42.loopky.ui.study.StudySessionRoute
import com.github.jvsena42.loopky.ui.tagbrowse.TagBrowseRoute

/**
 * [deepLink] is the `pubky://` address the app was opened with, if any. It is held rather than
 * navigated to immediately: a cold start lands on onboarding while the session is restored, and
 * pushing a profile on top of that would put a screen the user cannot use behind the sign-in
 * flow. [onDeepLinkHandled] fires once it has been consumed, so a second tap on the same link
 * still opens it.
 */
@Composable
fun LoopkyNavHost(
    deepLink: PubkyLink? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val currentDeepLinkHandled by rememberUpdatedState(onDeepLinkHandled)

    LaunchedEffect(deepLink, currentRoute) {
        if (deepLink == null || currentRoute == null || currentRoute == Routes.ONBOARDING) {
            return@LaunchedEffect
        }
        when (deepLink) {
            is PubkyLink.Profile -> navController.navigateTo(Routes.friendProfile(deepLink.pubky))
            is PubkyLink.Deck ->
                navController.navigateTo(Routes.deckDetail(deepLink.deckId, deepLink.pubky))
        }
        currentDeepLinkHandled()
    }

    NavHost(navController = navController, startDestination = Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                onNavigateHome = {
                    navController.navigateTo(Routes.MAIN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.MAIN) {
            MainScreen(
                onNavigateDeckDetail = { deckId, author ->
                    navController.navigateTo(Routes.deckDetail(deckId, author))
                },
                onNavigateCreateDeck = {
                    // Deck creation always starts at the Paste import flow (design node h9wya).
                    navController.navigateTo(Routes.IMPORT_PASTE)
                },
                onNavigateImport = {
                    navController.navigateTo(Routes.IMPORT_PASTE)
                },
                onNavigateImportFile = {
                    navController.navigateTo(Routes.IMPORT_BULK)
                },
                onNavigateStudy = { deckId ->
                    navController.navigateTo(Routes.study(deckId))
                },
                onNavigateProfile = { pubky ->
                    navController.navigateTo(Routes.friendProfile(pubky))
                },
                onNavigateSettings = {
                    navController.navigateTo(Routes.SETTINGS)
                },
                onNavigateSearch = {
                    navController.navigateTo(Routes.SEARCH)
                },
                onNavigateFollows = { pubky, source ->
                    navController.navigateTo(Routes.followList(pubky, source))
                },
                onSignOut = {
                    navController.navigateTo(Routes.ONBOARDING) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onSignedOut = {
                    navController.navigateTo(Routes.ONBOARDING) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.DECK_DETAIL,
            arguments = listOf(
                navArgument("deckId") { type = NavType.StringType },
                navArgument("author") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val deckId = backStackEntry.arguments?.getString("deckId") ?: return@composable
            val author = backStackEntry.arguments?.getString("author")
            DeckDetailRoute(
                deckId = deckId,
                authorPubky = author,
                onBack = { navController.popBackStack() },
                onEditDeck = { id -> navController.navigateTo(Routes.deckEditor(id)) },
                onStudy = { id -> navController.navigateTo(Routes.study(id)) },
                onOpenTag = { tag -> navController.navigateTo(Routes.tagBrowse(tag)) },
                onOpenProfile = { pubky -> navController.navigateTo(Routes.friendProfile(pubky)) },
                // The clone is what the user now owns, so replace the source in the back stack
                // rather than stacking a near-identical screen on top of it.
                onOpenClone = { id ->
                    navController.popBackStack()
                    navController.navigateTo(Routes.deckDetail(id))
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchRoute(
                onBack = { navController.popBackStack() },
                onOpenProfile = { pubky -> navController.navigateTo(Routes.friendProfile(pubky)) },
                onOpenDeck = { deckId, deckAuthor ->
                    navController.navigateTo(Routes.deckDetail(deckId, deckAuthor))
                },
            )
        }
        composable(
            route = Routes.TAG_BROWSE,
            arguments = listOf(navArgument("tag") { type = NavType.StringType }),
        ) { backStackEntry ->
            val tag = backStackEntry.arguments?.getString("tag") ?: return@composable
            TagBrowseRoute(
                tag = tag,
                onBack = { navController.popBackStack() },
                onOpenProfile = { pubky -> navController.navigateTo(Routes.friendProfile(pubky)) },
                onOpenDeck = { deckId, deckAuthor ->
                    navController.navigateTo(Routes.deckDetail(deckId, deckAuthor))
                },
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
                onEditCard = { dId, cId -> navController.navigateTo(Routes.editCard(dId, cId)) },
                onNewCard = { dId -> navController.navigateTo(Routes.newCard(dId)) },
                onSaved = { savedDeckId ->
                    navController.popBackStack()
                    navController.navigateTo(Routes.deckDetail(savedDeckId))
                },
            )
        }
        composable(Routes.IMPORT_PASTE) {
            PasteRoute(
                onCancel = { navController.popBackStack() },
                onNext = { navController.navigateTo(Routes.IMPORT_TRIAGE) },
            )
        }
        composable(Routes.IMPORT_BULK) {
            // Straight to the shared commit screen: a file import is confirmed once on the
            // summary, not card-by-card through triage.
            BulkImportRoute(
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigateTo(Routes.IMPORT_PUBLISH) },
            )
        }
        composable(Routes.IMPORT_TRIAGE) {
            TriageRoute(
                onBack = { navController.popBackStack() },
                onEditCard = { rowIndex -> navController.navigateTo(Routes.triageEditCard(rowIndex)) },
                onNext = { navController.navigateTo(Routes.IMPORT_PUBLISH) },
            )
        }
        composable(
            route = Routes.IMPORT_TRIAGE_EDIT,
            arguments = listOf(navArgument("rowIndex") { type = NavType.IntType }),
        ) { backStackEntry ->
            val rowIndex = backStackEntry.arguments?.getInt("rowIndex") ?: return@composable
            TriageEditCardRoute(
                rowIndex = rowIndex,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.IMPORT_PUBLISH) {
            PublishDeckRoute(
                onBack = { navController.popBackStack() },
                onPublished = { deckId ->
                    // Pop both import screens and navigate to deck detail
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                    navController.navigateTo(Routes.deckDetail(deckId))
                },
            )
        }
        cardEditorDestinations(navController)
        composable(
            route = Routes.STUDY,
            arguments = listOf(
                navArgument("deckId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val deckId = backStackEntry.arguments?.getString("deckId")
            StudySessionRoute(
                deckId = deckId,
                onClose = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.FRIEND_PROFILE,
            arguments = listOf(navArgument("pubky") { type = NavType.StringType }),
        ) { backStackEntry ->
            val pubky = backStackEntry.arguments?.getString("pubky") ?: return@composable
            FriendProfileRoute(
                pubky = pubky,
                onBack = { navController.popBackStack() },
                onOpenDeck = { deckId -> navController.navigateTo(Routes.deckDetail(deckId, author = pubky)) },
                onOpenFollows = { person, source ->
                    navController.navigateTo(Routes.followList(person, source))
                },
            )
        }
        composable(
            route = Routes.FOLLOW_LIST,
            arguments = listOf(
                navArgument("pubky") { type = NavType.StringType },
                navArgument("source") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val pubky = backStackEntry.arguments?.getString("pubky") ?: return@composable
            // An unrecognised source is a route nobody builds — Routes.followList writes it from
            // the enum — so fall back rather than dropping the destination on the floor.
            val source = FollowSource.entries
                .firstOrNull { it.name.equals(backStackEntry.arguments?.getString("source"), ignoreCase = true) }
                ?: FollowSource.FOLLOWING
            FollowListRoute(
                pubky = pubky,
                source = source,
                onBack = { navController.popBackStack() },
                onOpenProfile = { person -> navController.navigateTo(Routes.friendProfile(person)) },
            )
        }
    }
}

/**
 * The card editor, reached either on an existing card or on one that does not exist yet.
 *
 * Two routes rather than a flag on one: the "new" route has a segment fewer, so the two patterns
 * cannot both match the same URL.
 */
private fun NavGraphBuilder.cardEditorDestinations(navController: NavHostController) {
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
    composable(
        route = Routes.NEW_CARD,
        arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val deckId = backStackEntry.arguments?.getString("deckId") ?: return@composable
        // Blank card id: the editor mints one and appends the card on save.
        EditCardRoute(
            deckId = deckId,
            cardId = "",
            onBack = { navController.popBackStack() },
        )
    }
}
