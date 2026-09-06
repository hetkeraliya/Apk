package com.example.miband5.ui.dashboard

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.miband5.data.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DayData(
    val date: LocalDate,
    val steps: Int,
    val hrAvg: Int?,
    val sleep: Int
)

sealed interface DashboardUiState {
    object Loading : DashboardUiState
    data class Ready(val days: List<DayData>, val today: LocalDate) : DashboardUiState
}

/** Reads the current week from Room and exposes it to the dashboard. */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    var selectedIndex by mutableStateOf(6) // default = today (last of the week)
        private set
    var selectedTab by mutableStateOf(0) // 0 = Steps, 1 = Heart Rate, 2 = Sleep
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val start = today.minusDays(6)
            val rows = db.dailyStatsDao().getRange(start.toString(), today.toString())
            val byDate = rows.associateBy { it.date }
            val days = (0..6).map { i ->
                val d = start.plusDays(i.toLong())
                val row = byDate[d.toString()]
                DayData(
                    date = d,
                    steps = row?.steps ?: 0,
                    hrAvg = row?.heartRateAvg,
                    sleep = row?.sleepMinutes ?: 0
                )
            }
            _uiState.value = DashboardUiState.Ready(days, today)
        }
    }

    fun selectDay(index: Int) {
        selectedIndex = index
    }

    fun selectTab(tab: Int) {
        selectedTab = tab
    }
}
