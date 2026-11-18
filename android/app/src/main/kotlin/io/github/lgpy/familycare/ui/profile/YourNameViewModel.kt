package io.github.lgpy.familycare.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.Profile
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the name could not be saved. */
enum class YourNameError {
    InvalidName,
    Offline,
    NotSynced,
    NotAllowed,
    Unknown,
}

/** The name form: the current value, the edit, and what went wrong. */
data class YourNameUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: YourNameError? = null,
)

/**
 * More → Your name: the display name the other members see
 * ([ProfileRepository.updateDisplayName]).
 *
 * The write is local first, so the new name shows at once and uploads with the
 * next sync; the row itself is created server-side, so an account whose profile
 * has not synced yet gets "not available yet" rather than a silent no-op.
 */
class YourNameViewModel(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YourNameUiState())
    val uiState: StateFlow<YourNameUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = profileRepository.myProfile.first()
            _uiState.update {
                it.copy(
                    loaded = true,
                    name = profile?.displayName.orEmpty(),
                )
            }
        }
    }

    fun onNameChanged(value: String) {
        _uiState.update {
            it.copy(name = value, error = null, saved = false)
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.saving) return

        val name = Profile.normalizeDisplayName(state.name)
        if (!Profile.isValidDisplayName(name)) {
            _uiState.update { it.copy(error = YourNameError.InvalidName) }
            return
        }

        _uiState.update { it.copy(saving = true, error = null, saved = false) }
        viewModelScope.launch {
            when (val result = profileRepository.updateDisplayName(name)) {
                is CoreResult.Success -> _uiState.update {
                    it.copy(saving = false, saved = true, name = name)
                }

                is CoreResult.Failure -> _uiState.update { current ->
                    current.copy(
                        saving = false,
                        error = result.error.toYourNameError(),
                    )
                }
            }
        }
    }
}

/** The [CoreError] to [YourNameError] mapping for the name form. */
fun CoreError.toYourNameError(): YourNameError = when (this) {
    is CoreError.Invalid -> YourNameError.InvalidName
    CoreError.Offline -> YourNameError.Offline
    CoreError.NotFound, CoreError.NoCircle -> YourNameError.NotSynced
    CoreError.NotAllowed -> YourNameError.NotAllowed
    else -> YourNameError.Unknown
}
