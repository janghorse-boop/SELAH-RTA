package kr.joa.selahrta.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.joa.selahrta.calibration.MeasuredProfile
import kr.joa.selahrta.calibration.ProfileStore
import kr.joa.selahrta.calibration.StoredProfile
import kr.joa.selahrta.dsp.CalibrationOutcome

/**
 * 프로파일 관리 화면의 상태(지시서 7장 「프로파일 관리」).
 *
 * **펼친 것 하나만 곡선을 읽는다.** 목록에 있는 모두의 곡선을 미리
 * 읽으면 여는 데만 한참 걸리고, 대개는 한 장도 보지 않는다.
 */
data class ProfilesUiState(
    val loading: Boolean = true,
    val items: List<StoredProfile> = emptyList(),
    /** 펼쳐 놓은 프로파일의 id. */
    val openedId: String? = null,
    val openedCurves: CalibrationOutcome? = null,
    /** 곡선을 못 읽었을 때의 까닭. **조용히 빈 그래프를 그리지 않는다.** */
    val openedErrorKo: String? = null,
    val noticeKo: String? = null,
) {
    val isEmpty: Boolean get() = !loading && items.isEmpty()
}

/**
 * 저장된 프로파일을 보여 주고, 켜고 끄고, 지운다.
 *
 * [CaptureViewModel] 에 넣지 않은 까닭은 그쪽이 이미 900줄이 넘고,
 * 여기서 하는 일(파일 몇 개 읽고 쓰기)이 소리 경로와 아무 관계가
 * 없기 때문이다.
 */
class ProfilesViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore.forApp(app)

    private val _state = MutableStateFlow(ProfilesUiState())
    val state: StateFlow<ProfilesUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val items = store.list()
            _state.update { it.copy(loading = false, items = items) }
        }
    }

    /**
     * 자동 적용을 켜고 끈다. **파일은 지우지 않는다.**
     *
     * 실패하면 화면에 적는다 — 껐다고 보여 놓고 파일은 그대로면, 앱을
     * 다시 켤 때 되살아나서 「왜 다시 켜졌지」가 된다.
     */
    fun setEnabled(profile: MeasuredProfile, on: Boolean) {
        viewModelScope.launch {
            store.setEnabled(profile, on).fold(
                onSuccess = { reload() },
                onFailure = { e ->
                    _state.update {
                        it.copy(noticeKo = "바꾸지 못했습니다: ${e.message ?: "알 수 없는 까닭"}")
                    }
                },
            )
        }
    }

    /** 지운다. 되돌릴 수 없으므로 확인은 **화면이** 받는다. */
    fun delete(fileName: String) {
        viewModelScope.launch {
            val gone = store.delete(fileName)
            if (!gone) {
                _state.update { it.copy(noticeKo = "지우지 못했습니다: $fileName") }
            }
            if (_state.value.openedId != null) close()
            reload()
        }
    }

    /** 펼친다. 이때 비로소 곡선을 읽는다. */
    fun open(profile: MeasuredProfile) {
        viewModelScope.launch {
            _state.update {
                it.copy(openedId = profile.id, openedCurves = null, openedErrorKo = null)
            }
            store.loadCurves(profile).fold(
                onSuccess = { o -> _state.update { it.copy(openedCurves = o) } },
                onFailure = { e ->
                    _state.update {
                        it.copy(openedErrorKo = e.message ?: "곡선을 읽지 못했습니다.")
                    }
                },
            )
        }
    }

    fun close() {
        _state.update { it.copy(openedId = null, openedCurves = null, openedErrorKo = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(noticeKo = null) }
    }
}
