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
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.presentation.signup.TokenRedeemer
import com.github.jvsena42.loopky.ui.backup.BackupFileRoute
import com.github.jvsena42.loopky.ui.backup.BackupPhraseRoute
import com.github.jvsena42.loopky.ui.backup.BackupQuizRoute
import com.github.jvsena42.loopky.ui.backup.BackupRingRoute
import com.github.jvsena42.loopky.ui.backup.BackupStartRoute
import com.github.jvsena42.loopky.ui.decks.DeckDetailRoute
import com.github.jvsena42.loopky.ui.decks.DeckEditorRoute
import com.github.jvsena42.loopky.ui.decks.EditCardRoute
import com.github.jvsena42.loopky.ui.identity.UnregisteredKeyRoute
import com.github.jvsena42.loopky.ui.importflow.BulkImportRoute
import com.github.jvsena42.loopky.ui.importflow.PasteRoute
import com.github.jvsena42.loopky.ui.importflow.PublishDeckRoute
import com.github.jvsena42.loopky.ui.importflow.TriageEditCardRoute
import com.github.jvsena42.loopky.ui.importflow.TriageRoute
import com.github.jvsena42.loopky.ui.onboarding.OnboardingRoute
import com.github.jvsena42.loopky.ui.profile.FollowListRoute
import com.github.jvsena42.loopky.ui.profile.FriendProfileRoute
import com.github.jvsena42.loopky.ui.restore.RestoreFileRoute
import com.github.jvsena42.loopky.ui.restore.RestorePhraseRoute
import com.github.jvsena42.loopky.ui.restore.RestoreStartRoute
import com.github.jvsena42.loopky.ui.search.SearchRoute
import com.github.jvsena42.loopky.ui.settings.SettingsRoute
import com.github.jvsena42.loopky.ui.signup.InviteCodeRoute
import com.github.jvsena42.loopky.ui.signup.LightningVerificationRoute
import com.github.jvsena42.loopky.ui.signup.LocalSignupRoute
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
                onCreatePubky = { navController.navigateTo(Routes.signupStart()) },
                onRestore = { navController.navigateTo(Routes.RESTORE_START) },
                // Ring holds this key, so Loopky cannot register it — the screen offers the two
                // routes that keep it rather than a button that would mint a different one.
                onUnregistered = { pubky ->
                    navController.navigateTo(Routes.unregisteredKey(pubky, loopkyHoldsKey = false))
                },
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
                onBackUpNow = { navController.navigateTo(Routes.BACKUP_START) },
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
        backupDestinations(navController)
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
/**
 * Where a finished verification goes: Ring's deeplink handoff, or Loopky's own redemption.
 *
 * Read off the back stack rather than passed down through the three method screens, so those stay
 * byte-identical between the two spenders.
 */
private fun NavHostController.navigateToRedemption() {
    val entry = currentBackStack.value.firstOrNull { it.destination.route == Routes.SIGNUP_START }
    val redeemer = TokenRedeemer.fromNameOrRing(entry?.arguments?.getString("with"))
    val target = when (redeemer) {
        TokenRedeemer.PubkyRing -> Routes.SIGNUP_HANDOFF
        TokenRedeemer.Loopky -> Routes.SIGNUP_LOCAL
    }
    navigateTo(target)
}

private fun NavGraphBuilder.backupDestinations(navController: NavHostController) {
    composable(Routes.BACKUP_START) {
        BackupStartRoute(
            onBack = { navController.popBackStack() },
            onDone = {
                navController.navigateTo(Routes.MAIN) {
                    popUpTo(Routes.BACKUP_START) { inclusive = true }
                }
            },
            onPhrase = { navController.navigateTo(Routes.BACKUP_PHRASE) },
            onFile = { navController.navigateTo(Routes.BACKUP_FILE) },
            onRing = { navController.navigateTo(Routes.BACKUP_RING) },
        )
    }
    composable(Routes.BACKUP_PHRASE) {
        BackupPhraseRoute(
            onBack = { navController.popBackStack() },
            onConfirm = { navController.navigateTo(Routes.BACKUP_QUIZ) },
        )
    }
    composable(Routes.BACKUP_QUIZ) {
        BackupQuizRoute(
            onBack = { navController.popBackStack() },
            // Back to the menu, not out: having done one method is not a reason to stop offering
            // the others, and the menu now shows this one ticked.
            onDone = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(Routes.BACKUP_START) { inclusive = true }
                }
            },
        )
    }
    composable(Routes.BACKUP_FILE) {
        BackupFileRoute(
            onBack = { navController.popBackStack() },
            onDone = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(Routes.BACKUP_START) { inclusive = true }
                }
            },
        )
    }
    composable(Routes.BACKUP_RING) {
        BackupRingRoute(
            onBack = { navController.popBackStack() },
            onDone = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(Routes.BACKUP_START) { inclusive = true }
                }
            },
        )
    }
}

private fun NavGraphBuilder.restoreDestinations(navController: NavHostController) {
    composable(Routes.RESTORE_START) {
        RestoreStartRoute(
            onBack = { navController.popBackStack() },
            onRestoreWithPhrase = { navController.navigateTo(Routes.RESTORE_PHRASE) },
            onRestoreWithFile = { navController.navigateTo(Routes.RESTORE_FILE) },
        )
    }
    unregisteredKeyDestination(navController)
    restoreFileDestination(navController)
}

/** A valid key with no account, reachable from both the restore path and a Ring sign-in. */
private fun NavGraphBuilder.unregisteredKeyDestination(navController: NavHostController) {
    composable(
        route = Routes.ACCOUNT_UNREGISTERED,
        arguments = listOf(
            navArgument("pubky") { type = NavType.StringType },
            navArgument("local") {
                type = NavType.StringType
                nullable = true
                defaultValue = "false"
            },
        ),
    ) { entry ->
        val pubky = entry.arguments?.getString("pubky").orEmpty()
        val loopkyHolds = entry.arguments?.getString("local").toBoolean()
        UnregisteredKeyRoute(
            pubky = pubky,
            // Only the pubky and "can we register it" cross the nav boundary; the custody object
            // carries no secret either way.
            custody = if (loopkyHolds) KeyCustody.Loopky(pubky = pubky) else KeyCustody.External,
            onBack = { navController.popBackStack() },
            onNeedsVerification = { navController.navigateTo(Routes.signupStart(TokenRedeemer.Loopky)) },
            onRegistered = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            },
            onRestoreWithPhrase = { navController.navigateTo(Routes.RESTORE_PHRASE) },
        )
    }
}

private fun NavGraphBuilder.restoreFileDestination(navController: NavHostController) {
    composable(Routes.RESTORE_FILE) {
        RestoreFileRoute(
            onBack = { navController.popBackStack() },
            onUnregistered = { pubky ->
                navController.navigateTo(Routes.unregisteredKey(pubky, loopkyHoldsKey = true))
            },
            onRestored = {
                navController.navigateTo(Routes.MAIN) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            },
        )
    }
    composable(Routes.RESTORE_PHRASE) {
        RestorePhraseRoute(
            onBack = { navController.popBackStack() },
            // Loopky holds the key by the time this fires: derivation succeeded, so it can be
            // registered here rather than needing Ring.
            onUnregistered = { pubky ->
                navController.navigateTo(Routes.unregisteredKey(pubky, loopkyHoldsKey = true))
            },
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
    composable(
        route = Routes.SIGNUP_START,
        arguments = listOf(
            navArgument("with") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        // Unknown values fall back to Ring — the recommended path — rather than silently choosing
        // the one that puts a key on the device.
        val redeemer = TokenRedeemer.fromNameOrRing(entry.arguments?.getString("with"))
        SignupStartRoute(
            redeemer = redeemer,
            onBack = { navController.popBackStack() },
            onSms = { navController.navigateTo(Routes.SIGNUP_PHONE) },
            onLightning = { navController.navigateTo(Routes.SIGNUP_LIGHTNING) },
            onInviteCode = { navController.navigateTo(Routes.SIGNUP_INVITE) },
            onCreateLocally = {
                navController.navigateTo(Routes.signupStart(TokenRedeemer.Loopky)) {
                    popUpTo(Routes.SIGNUP_START) { inclusive = true }
                }
            },
        )
    }
    composable(Routes.SIGNUP_LOCAL) {
        LocalSignupRoute(
            onBack = { navController.popBackStack() },
            // Straight to backup, not home: this is the only moment in the app where a key exists
            // that nobody has a copy of.
            onCreated = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            },
        )
    }
    // The three verification screens are identical for both spenders and are deliberately
    // untouched; only where their "done" lands differs, and that is nav-layer code. The redeemer
    // is read off the back stack rather than threaded through them.
    composable(Routes.SIGNUP_PHONE) {
        PhoneVerificationRoute(
            onBack = { navController.popBackStack() },
            onDone = { navController.navigateToRedemption() },
        )
    }
    composable(Routes.SIGNUP_LIGHTNING) {
        LightningVerificationRoute(
            onBack = { navController.popBackStack() },
            onDone = { navController.navigateToRedemption() },
        )
    }
    composable(Routes.SIGNUP_INVITE) {
        InviteCodeRoute(
            onBack = { navController.popBackStack() },
            onDone = { navController.navigateToRedemption() },
        )
    }
    signupRedemptionDestinations(navController)
}

/** The two terminal steps: Ring's deeplink handoff, and Loopky's own redemption. */
private fun NavGraphBuilder.signupRedemptionDestinations(navController: NavHostController) {
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
