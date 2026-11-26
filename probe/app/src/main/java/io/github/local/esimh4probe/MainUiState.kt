package io.github.local.esimh4probe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * State of the Miuix Compose main screen ([MainScreen.kt]). [MainActivity] writes it from the
 * UI thread: the latest [DashboardModel], the busy flag and whether the Test tab is shown.
 *
 * @param initialPage page id ([MainActivity.PAGE_HOME] and so on) to open first.
 */
class MainUiState(showTester: Boolean, val initialPage: Int) {
    /** Null until the first probe report has been read. */
    var model by mutableStateOf<DashboardModel?>(null)
    var busy by mutableStateOf(false)
    var showTester by mutableStateOf(showTester)

    /** Page id of the selected tab, for onSaveInstanceState (not observed). */
    var currentPage: Int = initialPage
}
