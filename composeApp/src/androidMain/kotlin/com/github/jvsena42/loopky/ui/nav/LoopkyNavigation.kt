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
import com.github.jvsena42.loopky.ui.restore.RestorePhraseRoute
import com.github.jvsena42.loopky.ui.restore.RestoreStartRoute
import com.github.jvsena42.loopky.ui.search.SearchRoute
import com.github.jvsena42.loopky.ui.settings.SettingsRoute
import com.github.jvsena42.loopky.ui.signup.InviteCodeRoute
import com.github.jvsena42.loopky.ui.signup.LightningVerificationRoute
import com.github.jvsena42.loopky.ui.signup.PhoneVerificationRoute
import com.github.jvsena42.loopky.ui.signup.SignupHandoffRoute
import com.github.jvsena42.loopky.ui.signup.SignupStartRoute
import com.github.jvsena42.loopky.ui.study.StudySessionRoute
import com.github.jvsena42.loopky.ui.tagbrowse.TagBrowseRoute

/**
 * [pendingOpen] is what the app was opened with — a `pubky://` address or a deck file, if any. It
 * is held rather than navigated to immediately: a cold start lands on onboarding while the
 * session is restored, and pushing a profile on top of that would put a screen the user cannot
 * use behind the sign-in flow. [onPendingOpenHandled] fires once it has been consumed, so a
 * second tap on the same link still opens it.
 */
@Composable
internal fun LoopkyNavHost(
    pendingOpen: PendingOpen? = null,
    onPendingOpenHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val currentPendingHandled by rememberUpdatedState(onPendingOpenHandled)

    LaunchedEffect(pendingOpen, currentRoute) {
        if (pendingOpen == null || currentRoute == null || currentRoute == Routes.ONBOARDING) {
            return@LaunchedEffect
        }
        when (pendingOpen) {
            is PendingOpen.Link -> {
                when (val link = pendingOpen.link) {
                    is PubkyLink.Profile -> navController.navigateTo(Routes.friendProfile(link.pubky))
                    is PubkyLink.Deck ->
                        navController.navigateTo(Routes.deckDetail(link.deckId, link.pubky))
                }
                currentPendingHandled()
            }
            // Not cleared here: navigating is only half of it, and the file still has to reach
            // the screen. BulkImportRoute clears it once the ViewModel has taken it. navigateTo
            // dedups the current destination, so the re-run this effect gets when the route
            // changes is a no-op.
            is PendingOpen.File -> navController.navigateTo(Routes.IMPORT_BULK)
        }
    }

    NavHost(navController = navController, startDestination = Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                onCreatePubky = { navController.navigateTo(Routes.SIGNUP_START) },
                onRestore = { navController.navigateTo(Routes.RESTORE_START) },
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
                    // Deck creation always starts at the Paste import flow.
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
                    navController.navigateTo(Routes.settings())
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
        composable(
            route = Routes.SETTINGS,
            arguments = listOf(
                navArgument("focus") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onSignedOut = {
                    navController.navigateTo(Routes.ONBOARDING) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
                focus = entry.arguments?.getString("focus"),
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
                onEditCard = { dId, cId -> navController.navigateTo(Routes.editCard(dId, cId)) },
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
                onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
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
                incoming = (pendingOpen as? PendingOpen.File)?.state,
                onIncomingHandled = currentPendingHandled,
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
                onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
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
                onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
            )
        }
        signupDestinations(navController)
        restoreDestinations(navController)
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
/**
 * The signup flow: obtain a token, then hand it to Pubky Ring.
 *
 * Grouped like [cardEditorDestinations] and flat like the import flow — the token in flight lives
 * in `SignupRepository`, so no step needs a nav argument and every back press is a plain pop.
 */
private fun NavGraphBuilder.restoreDestinations(navController: NavHostController) {
    composable(Routes.RESTORE_START) {
        RestoreStartRoute(
            onBack = { navController.popBackStack() },
            onRestoreWithPhrase = { navController.navigateTo(Routes.RESTORE_PHRASE) },
        )
    }
    composable(Routes.RESTORE_PHRASE) {
        RestorePhraseRoute(
            onBack = { navController.popBackStack() },
            // The whole restore stack goes: coming "back" into it after signing in would offer to
            // restore an account the user is already using.
            onRestored = {
                navController.navigateTo(Routes.MAIN) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            },
        )
    }
}

private fun NavGraphBuilder.signupDestinations(navController: NavHostController) {
    composable(Routes.SIGNUP_START) {
        SignupStartRoute(
            onBack = { navController.popBackStack() },
            onSms = { navController.navigateTo(Routes.SIGNUP_PHONE) },
            onLightning = { navController.navigateTo(Routes.SIGNUP_LIGHTNING) },
            onInviteCode = { navController.navigateTo(Routes.SIGNUP_INVITE) },
        )
    }
    composable(Routes.SIGNUP_PHONE) {
        PhoneVerificationRoute(
            onBack = { navController.popBackStack() },
            onDone = { navController.navigateTo(Routes.SIGNUP_HANDOFF) },
        )
    }
    composable(Routes.SIGNUP_LIGHTNING) {
        LightningVerificationRoute(
            onBack = { navController.popBackStack() },
            onDone = { navController.navigateTo(Routes.SIGNUP_HANDOFF) },
        )
    }
    composable(Routes.SIGNUP_INVITE) {
        InviteCodeRoute(
            onBack = { navController.popBackStack() },
            onDone = { navController.navigateTo(Routes.SIGNUP_HANDOFF) },
        )
    }
    composable(Routes.SIGNUP_HANDOFF) {
        SignupHandoffRoute(
            onBack = { navController.popBackStack() },
            // The whole signup stack is dropped: coming "back" into a spent flow would offer to
            // redeem a token that no longer exists.
            onSignedUp = {
                navController.navigateTo(Routes.MAIN) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            },
            onSignIn = {
                navController.navigateTo(Routes.ONBOARDING) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            },
        )
    }
}

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
            onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
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
            onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
        )
    }
}
