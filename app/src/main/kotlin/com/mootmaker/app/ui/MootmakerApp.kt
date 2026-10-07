package com.mootmaker.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mootmaker.app.AppContainer
import com.mootmaker.app.BuildConfig
import com.mootmaker.app.WebLinks
import com.mootmaker.app.openInCustomTab
import com.mootmaker.app.shareLink
import com.mootmaker.app.ui.about.AboutScreen
import com.mootmaker.app.ui.addmeeting.AddMeetingActions
import com.mootmaker.app.ui.addmeeting.AddMeetingScreen
import com.mootmaker.app.ui.addmeeting.AddMeetingViewModel
import com.mootmaker.app.ui.availability.AvailabilityActions
import com.mootmaker.app.ui.availability.AvailabilityScreen
import com.mootmaker.app.ui.availability.AvailabilityViewModel
import com.mootmaker.app.ui.calendar.CalendarActions
import com.mootmaker.app.ui.calendar.CalendarScreen
import com.mootmaker.app.ui.calendar.CalendarViewModel
import com.mootmaker.app.ui.home.HomeActions
import com.mootmaker.app.ui.home.HomeScreen
import com.mootmaker.app.ui.home.HomeViewModel
import com.mootmaker.app.ui.meeting.MeetingDetailsActions
import com.mootmaker.app.ui.meeting.MeetingDetailsScreen
import com.mootmaker.app.ui.meeting.MeetingDetailsViewModel
import com.mootmaker.app.ui.settings.SettingsActions
import com.mootmaker.app.ui.settings.SettingsScreen
import com.mootmaker.app.ui.settings.SettingsViewModel
import com.mootmaker.app.ui.settings.prepareAvatar
import com.mootmaker.app.ui.signin.SignInConfig
import com.mootmaker.app.ui.signin.SignInScreen
import com.mootmaker.app.ui.signin.SignInViewModel
import com.mootmaker.data.auth.ConfigState
import com.mootmaker.data.auth.SessionState
import com.mootmaker.data.meeting.meetingShareUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private object Routes {
    const val SIGN_IN = "signin"
    const val HOME = "home"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    const val AVAILABILITY = "availability/{date}"

    const val ADD_MEETING = "meetings/add/{date}"
    const val MEETING = "meeting/{id}"
    const val EDIT_MEETING = "meeting/{id}/edit"
    const val CALENDAR = "calendar/{personId}"

    fun availability(date: LocalDate) = "availability/$date"
    fun addMeeting(date: LocalDate) = "meetings/add/$date"
    fun meeting(id: String) = "meeting/$id"
    fun editMeeting(id: String) = "meeting/$id/edit"
    fun calendar(personId: String) = "calendar/$personId"
}

/**
 * The app's navigation. Which screen is the root follows the session: signing in replaces the
 * sign-in screen with home, and signing out (or a session that can no longer be refreshed) clears
 * the back stack back to sign-in, so Back can never return to a signed-in screen (use case B.13).
 */
@Composable
fun MootmakerApp(container: AppContainer) {
    val session = container.session
    val sessionState by session.state.collectAsStateWithLifecycle()
    val configState by session.config.collectAsStateWithLifecycle()

    if (sessionState == SessionState.Starting) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }

    val signedIn = sessionState is SessionState.SignedIn
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val links = WebLinks(configState.environment.siteUrl)
    val open: (String) -> Unit = { openInCustomTab(context, it) }

    // Live updates run only while signed in and in the foreground; leaving either closes the socket.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(signedIn, lifecycle) {
        if (signedIn) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { container.followLiveUpdates() }
    }

    LaunchedEffect(signedIn) { navController.resetTo(if (signedIn) Routes.HOME else Routes.SIGN_IN) }

    val avatarLoader = remember(container) { container.avatarLoader(context) }
    CompositionLocalProvider(LocalAvatarLoader provides avatarLoader) {
    NavHost(navController, startDestination = if (signedIn) Routes.HOME else Routes.SIGN_IN) {
        composable(Routes.SIGN_IN) {
            val viewModel = viewModel { SignInViewModel(session::signIn) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            val config = when (val current = configState) {
                is ConfigState.Loading -> SignInConfig.Loading
                is ConfigState.Failed -> SignInConfig.Failed(current.environment.name)
                is ConfigState.Ready -> SignInConfig.Ready(hasDemoUser = current.config.demoUserEmail != null)
            }
            LaunchedEffect(configState) {
                (configState as? ConfigState.Ready)?.config?.let { viewModel.prefill(it.demoUserEmail, it.demoUserPassword) }
            }
            SignInScreen(
                state = state,
                config = config,
                onEmailChange = viewModel::onEmailChange,
                onPasswordChange = viewModel::onPasswordChange,
                onSubmit = viewModel::submit,
                onRetryConfig = { scope.launch { session.retryConfig() } },
                onCreateAccount = { open(links.signUp()) },
                onForgotPassword = { open(links.forgotPassword()) },
                onAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.HOME) {
            val viewModel = viewModel { HomeViewModel(container.homeSource, container.meetingSource) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            val claims = (sessionState as? SessionState.SignedIn)?.claims
            LifecycleResumeEffect(viewModel) {
                viewModel.refresh()
                onPauseOrDispose { }
            }
            LaunchedEffect(viewModel) { container.liveEvents.collect { viewModel.refreshForLiveChange() } }
            HomeScreen(
                state = state,
                fallbackName = claims?.name ?: claims?.email,
                actions = HomeActions(
                    onCalendar = { claims?.personId?.let { navController.navigate(Routes.calendar(it)) } },
                    onRoomAvailabilityToday = { navController.navigate(Routes.availability(state.today)) },
                    onAddMeeting = { navController.navigate(Routes.addMeeting(state.today)) },
                    onRetry = viewModel::refresh,
                    onAbout = { navController.navigate(Routes.ABOUT) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onSignOut = { scope.launch { session.signOut() } },
                    onOpenMeeting = { navController.navigate(Routes.meeting(it)) },
                    onRespond = viewModel::respond,
                    onSearchFurtherAhead = viewModel::searchFurtherAhead,
                ),
            )
        }
        composable(Routes.AVAILABILITY, arguments = listOf(navArgument("date") { type = NavType.StringType })) { entry ->
            val date = entry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
            val viewModel = viewModel { AvailabilityViewModel(container.availabilitySource, date) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LifecycleResumeEffect(viewModel) {
                viewModel.refresh()
                onPauseOrDispose { }
            }
            LaunchedEffect(viewModel) { container.liveEvents.collect { viewModel.refresh() } }
            AvailabilityScreen(
                state = state,
                actions = AvailabilityActions(
                    onBack = { navController.popBackStack() },
                    onPreviousDay = viewModel::previousDay,
                    onNextDay = viewModel::nextDay,
                    onPickDate = viewModel::goTo,
                    onToggleRoom = viewModel::toggleExpanded,
                    onAddMeeting = { navController.navigate(Routes.addMeeting(state.date)) },
                    onOpenMeeting = { navController.navigate(Routes.meeting(it)) },
                    onRetry = viewModel::refresh,
                ),
            )
        }
        composable(Routes.ADD_MEETING, arguments = listOf(navArgument("date") { type = NavType.StringType })) { entry ->
            val date = entry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
            val viewModel = viewModel { AddMeetingViewModel(container.meetingFormSource, date) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel) { viewModel.load() }
            AddMeetingScreen(
                state = state,
                actions = AddMeetingActions(
                    onBack = { navController.popBackStack() },
                    onSubject = viewModel::setSubject,
                    onOrganiser = viewModel::setOrganiser,
                    onAttendees = viewModel::setAttendees,
                    onDate = viewModel::setDate,
                    onStart = viewModel::setStart,
                    onEnd = viewModel::setEnd,
                    onRoom = viewModel::setRoom,
                    onSuggestRoom = viewModel::suggestRoom,
                    onSave = viewModel::save,
                    onDismissErrors = viewModel::dismissErrors,
                    onRetry = viewModel::load,
                    onSaved = { meetingId ->
                        Toast.makeText(context, "Meeting was successfully scheduled.", Toast.LENGTH_SHORT).show()
                        // The form is replaced by the new meeting, so Back returns to where Add was opened.
                        navController.navigate(Routes.meeting(meetingId)) { popUpTo(Routes.ADD_MEETING) { inclusive = true } }
                    },
                ),
            )
        }
        composable(Routes.EDIT_MEETING, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val meetingId = entry.arguments?.getString("id").orEmpty()
            val viewModel = viewModel { AddMeetingViewModel(container.meetingFormSource, LocalDate.now(), editingMeetingId = meetingId) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel) { viewModel.load() }
            AddMeetingScreen(
                state = state,
                actions = AddMeetingActions(
                    onBack = { navController.popBackStack() },
                    onSubject = viewModel::setSubject,
                    onOrganiser = viewModel::setOrganiser,
                    onAttendees = viewModel::setAttendees,
                    onDate = viewModel::setDate,
                    onStart = viewModel::setStart,
                    onEnd = viewModel::setEnd,
                    onRoom = viewModel::setRoom,
                    onSuggestRoom = viewModel::suggestRoom,
                    onSave = viewModel::save,
                    onDismissErrors = viewModel::dismissErrors,
                    onRetry = viewModel::load,
                    onSaved = {
                        Toast.makeText(context, "Meeting was successfully updated.", Toast.LENGTH_SHORT).show()
                        // Back to the meeting, which reloads when it becomes visible.
                        navController.popBackStack()
                    },
                ),
            )
        }
        composable(Routes.MEETING, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val meetingId = entry.arguments?.getString("id").orEmpty()
            val viewModel = viewModel { MeetingDetailsViewModel(container.meetingSource, meetingId, isAdmin = (sessionState as? SessionState.SignedIn)?.claims?.isAdmin == true) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LifecycleResumeEffect(viewModel) {
                viewModel.refresh()
                onPauseOrDispose { }
            }
            LaunchedEffect(viewModel) { container.liveEvents.collect { viewModel.refreshForLiveChange() } }
            MeetingDetailsScreen(
                state = state,
                actions = MeetingDetailsActions(
                    onBack = { navController.popBackStack() },
                    onShare = { shareLink(context, it.subject, meetingShareUrl(configState.environment.siteUrl, it.id)) },
                    onOpenCalendar = { navController.navigate(Routes.calendar(it)) },
                    onRetry = viewModel::refresh,
                    onEdit = { navController.navigate(Routes.editMeeting(it)) },
                    onRespond = viewModel::respond,
                    onAskToCancel = viewModel::askToCancel,
                    onKeepMeeting = viewModel::keepMeeting,
                    onConfirmCancel = viewModel::confirmCancel,
                    onCancelled = {
                        Toast.makeText(context, "Meeting was cancelled.", Toast.LENGTH_SHORT).show()
                        navController.popBackStack()
                    },
                ),
                canEdit = viewModel.canEdit(),
            )
        }
        composable(Routes.CALENDAR, arguments = listOf(navArgument("personId") { type = NavType.StringType })) { entry ->
            val personId = entry.arguments?.getString("personId").orEmpty()
            val viewModel = viewModel { CalendarViewModel(container.calendarSource, personId) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LifecycleResumeEffect(viewModel) {
                viewModel.refresh()
                onPauseOrDispose { }
            }
            LaunchedEffect(viewModel) { container.liveEvents.collect { viewModel.refresh() } }
            CalendarScreen(
                state = state,
                actions = CalendarActions(
                    onBack = { navController.popBackStack() },
                    onPreviousWeek = viewModel::previousWeek,
                    onNextWeek = viewModel::nextWeek,
                    onThisWeek = viewModel::thisWeek,
                    onSelectPerson = viewModel::selectPerson,
                    onOpenMeeting = { navController.navigate(Routes.meeting(it)) },
                    onRetry = viewModel::refresh,
                ),
            )
        }
        composable(Routes.SETTINGS) {
            val viewModel = viewModel { SettingsViewModel(container.settingsSource) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel) { viewModel.load() }
            val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) {
                    scope.launch {
                        val prepared = withContext(Dispatchers.IO) { runCatching { prepareAvatar(context, uri) }.getOrNull() }
                        if (prepared == null) viewModel.avatarUnreadable() else viewModel.setAvatar(prepared.bytes, prepared.contentType)
                    }
                }
            }
            SettingsScreen(
                state = state,
                actions = SettingsActions(
                    onBack = { navController.popBackStack() },
                    onRetry = viewModel::load,
                    onName = viewModel::setName,
                    onSaveName = viewModel::saveName,
                    onDateFormat = viewModel::setDateFormat,
                    onTimeFormat = viewModel::setTimeFormat,
                    onSaveFormats = viewModel::saveFormats,
                    onChoosePhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemovePhoto = viewModel::removeAvatar,
                ),
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                environment = configState.environment,
                onSwitchEnvironment = { environment ->
                    scope.launch { session.switchEnvironment(environment) }
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
    }
}

private fun NavHostController.resetTo(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
