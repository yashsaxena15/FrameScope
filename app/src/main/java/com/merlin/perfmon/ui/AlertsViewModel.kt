package com.merlin.perfmon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.merlin.perfmon.data.model.AlertCondition
import com.merlin.perfmon.data.model.AlertRule
import com.merlin.perfmon.data.model.MetricType
import com.merlin.perfmon.data.repository.AlertsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val alertsRepo: AlertsRepository,
) : ViewModel() {

    val rules: StateFlow<List<AlertRule>> = alertsRepo.rulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addRule(
        metric:    MetricType,
        condition: AlertCondition,
        threshold: Float,
        label:     String = "",
    ) = viewModelScope.launch {
        alertsRepo.addRule(
            AlertRule(metric = metric, condition = condition, threshold = threshold, label = label)
        )
    }

    fun removeRule(id: String) = viewModelScope.launch {
        alertsRepo.removeRule(id)
    }

    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        alertsRepo.setRuleEnabled(id, enabled)
    }
}
