package com.macedonga.visor.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the home shows recent photos: the moments and the glasses' own previews. On unless turned off. */
@Singleton
class HomePhotos @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val _shown = MutableStateFlow(prefs.getBoolean(KEY, true))
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    fun set(on: Boolean) {
        _shown.value = on
        prefs.edit().putBoolean(KEY, on).apply()
    }

    private companion object {
        const val KEY = "recent_photos"
    }
}
