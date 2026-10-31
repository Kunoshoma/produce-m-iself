package dev.kunoshoma.produce.ui.lesson

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kunoshoma.produce.data.db.ActiveLessonEntity
import dev.kunoshoma.produce.domain.ProduceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LessonViewModel @Inject constructor(repo: ProduceRepository) : ViewModel() {
    /** null = 読み込み中。Loaded(null) = 進行中のレッスンなし。 */
    val lesson: StateFlow<Loaded?> = repo.activeLessonFlow
        .map { Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    data class Loaded(val lesson: ActiveLessonEntity?)
}
