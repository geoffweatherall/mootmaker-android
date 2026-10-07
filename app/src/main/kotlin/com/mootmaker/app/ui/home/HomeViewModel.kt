package com.mootmaker.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.HomeData
import com.mootmaker.data.api.HomeSource
import com.mootmaker.data.auth.SessionExpiredException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeState(
    val today: LocalDate,
    /** Null until the first load finishes. Kept on screen while a refresh runs. */
    val data: HomeData? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(
    private val source: HomeSource,
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState(today = clock()))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Refetches. Called whenever the screen becomes visible (design: refetch on visible until M6). */
    fun refresh() {
        if (loadJob?.isActive == true) return
        val today = clock()
        _state.update { it.copy(today = today, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val data = source.load(today)
                _state.update { it.copy(data = data, loading = false) }
            } catch (expired: SessionExpiredException) {
                // The session has signed out; navigation takes the user back to sign-in.
                _state.update { it.copy(loading = false) }
            } catch (failure: ApiException) {
                _state.update { it.copy(loading = false, error = failure.message) }
            }
        }
    }
}
