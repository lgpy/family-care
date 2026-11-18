package io.github.lgpy.familycare.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What Today shows until the dashboard modules arrive (M3 onwards). */
data class TodayUiState(
    val circleName: String? = null,
    val displayName: String? = null,
    val role: MemberRole? = null,
    val patientCount: Int = 0,
)

/**
 * Today, correct for the real circle while the dashboard is still a placeholder:
 * the circle's name, the signed-in member's name and role, and how many people
 * are in the family.
 */
class TodayViewModel(
    careCircleRepository: CareCircleRepository,
    profileRepository: ProfileRepository,
    patientRepository: PatientRepository,
) : ViewModel() {

    val uiState: StateFlow<TodayUiState> = combine(
        careCircleRepository.circle,
        profileRepository.myProfile,
        careCircleRepository.myRole,
        patientRepository.patients,
    ) { circle, profile, role, patients ->
        TodayUiState(
            circleName = circle?.name,
            displayName = profile?.displayName,
            role = role,
            patientCount = patients.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayUiState(),
    )
}
