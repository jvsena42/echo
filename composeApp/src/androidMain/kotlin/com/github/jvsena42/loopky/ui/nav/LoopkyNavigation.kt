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
                // Browsing, not an account. Discover, deck detail, profiles and a deck's cards
                // are all public records, so a visitor can be shown the app before being asked
                // for the most expensive thing on this screen. Every write past that point
                // raises a sign-in prompt from the action itself.
                onExplore = { navController.navigateTo(Routes.main(guest = true)) },
                onCreatePubky = { navController.navigateTo(Routes.signupStart()) },
                onRestore = { navController.navigateTo(Routes.RESTORE_START) },
                // Ring holds this key, so Loopky cannot register it — the screen offers the two
                // routes that keep it rather than a button that would mint a different one.
                onUnregistered = { pubky ->
                    navController.navigateTo(Routes.unregisteredKey(pubky, loopkyHoldsKey = false))
                },
                onNavigateHome = { navController.goHomeSignedIn() },
            )
        }
        composable(
            route = Routes.MAIN,
            arguments = listOf(
                navArgument("guest") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
            ),
        ) { entry ->
            MainScreen(
                isGuest = entry.arguments?.getString("guest").toBoolean(),
                // Pushed rather than replacing, so "Not now" and the back gesture both land the
                // visitor back where they were browsing. Everything that *completes* a sign-in
                // clears the whole stack — see [goHomeSignedIn] — so the guest shell underneath
                // can never be returned to once there is an account.
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
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
                onPreview = { id -> navController.navigateTo(Routes.studyPreview(id, author)) },
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
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
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
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
                navArgument("preview") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("author") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val deckId = backStackEntry.arguments?.getString("deckId")
            StudySessionRoute(
                deckId = deckId,
                isPreview = backStackEntry.arguments?.getString("preview").toBoolean(),
                previewAuthorPubky = backStackEntry.arguments?.getString("author"),
                onClose = { navController.popBackStack() },
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
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
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
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
/**
 * Land on the tabbed app with nothing behind it.
 *
 * Every path that ends in a session goes through here. `popUpTo(ONBOARDING)` is not enough on its
 * own any more: a visitor who signed in from inside the guest shell has a browsing `MAIN` *below*
 * the onboarding screen they signed in on, and popping to the nearest onboarding entry would
 * leave it there — signed in, one back gesture from a shell with no library in it. Clearing the
 * graph is also what makes the tab screens rebuild, which is how they pick up the new session.
 */
private fun NavHostController.goHomeSignedIn() {
    navigateTo(Routes.main()) { popUpTo(graph.id) { inclusive = true } }
}

private fun NavHostController.navigateToRedemption() {
    val entry = currentBackStack.value.firstOrNull { it.destination.route == Routes.SIGNUP_START }
    val redeemer = TokenRedeemer.fromNameOrRing(entry?.arguments?.getString("with"))
    // Carried from the door the user came through, so the terminal step knows whether it is
    // registering a key they confirmed or minting a new one.
    val adoptHeldKey = entry?.arguments?.getString("adopt").toBoolean()
    val target = when (redeemer) {
        TokenRedeemer.PubkyRing -> Routes.SIGNUP_HANDOFF
        TokenRedeemer.Loopky -> Routes.signupLocal(adoptHeldKey = adoptHeldKey)
    }
    navigateTo(target)
}

private fun NavGraphBuilder.backupDestinations(navController: NavHostController) {
    composable(Routes.BACKUP_START) {
        BackupStartRoute(
            onBack = { navController.popBackStack() },
            // Entered two ways, and they need opposite exits: from onboarding the back stack is
            // this screen alone, so leaving means going home; from Settings there is somewhere to
            // return to, and sending that user to MAIN would both lose their place and push a
            // second MAIN onto the stack.
            onDone = {
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else {
                    navController.navigateTo(Routes.MAIN) {
                        popUpTo(Routes.BACKUP_START) { inclusive = true }
                    }
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
            // `adopt`: the verification that follows must register *this* key, not mint a new
            // one. The user has just confirmed this pubky by name.
            onNeedsVerification = {
                navController.navigateTo(Routes.signupStart(TokenRedeemer.Loopky, adoptHeldKey = true))
            },
            onRegistered = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(navController.graph.id) { inclusive = true }
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
            // Via the backup menu, not straight home. A restored key is already backed up, so
            // nothing nags about it later — which meant Pubky Ring, the one thing still worth
            // offering, sat behind a Settings row the user had no reason to open. Ring is a
            // custody change rather than a backup, and this is the moment to offer it: someone
            // restoring has usually just lost or replaced a device. The menu is skippable.
            onRestored = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(navController.graph.id) { inclusive = true }
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
            // Via the backup menu, not straight home. A restored key is already backed up, so
            // nothing nags about it later — which meant Pubky Ring, the one thing still worth
            // offering, sat behind a Settings row the user had no reason to open. Ring is a
            // custody change rather than a backup, and this is the moment to offer it: someone
            // restoring has usually just lost or replaced a device. The menu is skippable.
            onRestored = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(navController.graph.id) { inclusive = true }
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
            navArgument("adopt") {
                type = NavType.StringType
                nullable = true
                defaultValue = "false"
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
    composable(
        route = Routes.SIGNUP_LOCAL,
        arguments = listOf(
            navArgument("adopt") {
                type = NavType.StringType
                nullable = true
                defaultValue = "false"
            },
        ),
    ) { entry ->
        LocalSignupRoute(
            registerHeldKey = entry.arguments?.getString("adopt").toBoolean(),
            onBack = { navController.popBackStack() },
            onStartOver = {
                navController.navigateTo(Routes.signupStart(TokenRedeemer.Loopky)) {
                    popUpTo(Routes.SIGNUP_LOCAL) { inclusive = true }
                }
            },
            // Straight to backup, not home: this is the only moment in the app where a key exists
            // that nobody has a copy of.
            onCreated = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            },
        )
    }
    // The three verification screens are identical for both spenders and are deliberately
    // untouched; only where their "done" lands differs, and that is nav-layer code. The redeemer
    // is read off the back stack rather than threaded through them.
    signupMethodDestinations(navController)
    signupRedemptionDestinations(navController)
}

/** The three ways to prove you are not a robot. Identical for both spenders. */
private fun NavGraphBuilder.signupMethodDestinations(navController: NavHostController) {
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
}

/** The two terminal steps: Ring's deeplink handoff, and Loopky's own redemption. */
private fun NavGraphBuilder.signupRedemptionDestinations(navController: NavHostController) {
    composable(Routes.SIGNUP_HANDOFF) {
        SignupHandoffRoute(
            onBack = { navController.popBackStack() },
            // The whole signup stack is dropped: coming "back" into a spent flow would offer to
            // redeem a token that no longer exists.
            onSignedUp = { navController.goHomeSignedIn() },
            onSignIn = {
                navController.navigateTo(Routes.ONBOARDING) {
                    popUpTo(navController.graph.id) { inclusive = true }
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
