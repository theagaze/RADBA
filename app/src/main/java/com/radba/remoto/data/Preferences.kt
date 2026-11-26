package com.radba.remoto.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.radbaDataStore: DataStore<Preferences> by preferencesDataStore(name = "radba_prefs")

private object Keys {
    val HOST = stringPreferencesKey("host")
    val DIM = intPreferencesKey("dim")
    val MIRROR = booleanPreferencesKey("mirror")
    val BASE_BRIGHTNESS = intPreferencesKey("base_brightness")
    val BASE_CONTRAST = intPreferencesKey("base_contrast")
    val BASE_SATURATION = intPreferencesKey("base_saturation")
    val ROOT_FIX_VIDEO = booleanPreferencesKey("root_fix_video")
    val V_BRIGHT = intPreferencesKey("v_bright")
    val V_WARM = intPreferencesKey("v_warm")
    val NIGHT_MODE = booleanPreferencesKey("night_mode")
    val NIGHT_BRIGHT = intPreferencesKey("night_bright")
    val NIGHT_WARM = intPreferencesKey("night_warm")
    val VERBOSE_ERRORS = booleanPreferencesKey("verbose_errors")
    val FAVORITES = stringSetPreferencesKey("favorites")
}

/** Keys removed in RADB 0.2; they are dropped on the first write after migration. */
private object LegacyKeys {
    val VIDEO_ENCODER = stringPreferencesKey("video_encoder")
    val FPS_MODE = stringPreferencesKey("fps_mode")
    val RES_MODE = stringPreferencesKey("res_mode")
    val HW_ACCEL = booleanPreferencesKey("hwaccel")
    val AUDIO_PC = booleanPreferencesKey("audio_pc")
    val KB = booleanPreferencesKey("kb")
}

class AppPreferences(context: Context) {
    private val dataStore = context.applicationContext.radbaDataStore

    val prefs: Flow<Prefs> = dataStore.data.map { p -> read(p) }
    val favorites: Flow<Set<String>> = dataStore.data.map { p -> p[Keys.FAVORITES] ?: emptySet() }

    suspend fun current(): Prefs = prefs.first()

    suspend fun currentFavorites(): Set<String> = favorites.first()

    /** Removes obsolete keys once at startup, even if the user never changes a setting. */
    suspend fun migrate() {
        dataStore.edit { p -> dropLegacy(p) }
    }

    suspend fun update(transform: (Prefs) -> Prefs) {
        dataStore.edit { p -> write(p, transform(read(p)).validated()) }
    }

    suspend fun setFavorites(values: Set<String>) {
        dataStore.edit {
            it[Keys.FAVORITES] = values
            dropLegacy(it)
        }
    }

    private fun read(p: Preferences): Prefs = Prefs(
        host = p[Keys.HOST] ?: Prefs().host,
        dim = p[Keys.DIM] ?: Prefs().dim,
        mirror = p[Keys.MIRROR] ?: Prefs().mirror,
        base = PictureBase(
            brightness = p[Keys.BASE_BRIGHTNESS] ?: 50,
            contrast = p[Keys.BASE_CONTRAST] ?: 50,
            saturation = p[Keys.BASE_SATURATION] ?: 50,
        ),
        rootFixVideo = p[Keys.ROOT_FIX_VIDEO] ?: Prefs().rootFixVideo,
        vBright = p[Keys.V_BRIGHT] ?: Prefs().vBright,
        vWarm = p[Keys.V_WARM] ?: Prefs().vWarm,
        nightMode = p[Keys.NIGHT_MODE] ?: Prefs().nightMode,
        nightBright = p[Keys.NIGHT_BRIGHT] ?: Prefs().nightBright,
        nightWarm = p[Keys.NIGHT_WARM] ?: Prefs().nightWarm,
        verboseErrors = p[Keys.VERBOSE_ERRORS] ?: Prefs().verboseErrors,
    ).validated()

    private fun write(p: MutablePreferences, prefs: Prefs) {
        p[Keys.HOST] = prefs.host
        p[Keys.DIM] = prefs.dim
        p[Keys.MIRROR] = prefs.mirror
        p[Keys.BASE_BRIGHTNESS] = prefs.base.brightness
        p[Keys.BASE_CONTRAST] = prefs.base.contrast
        p[Keys.BASE_SATURATION] = prefs.base.saturation
        p[Keys.ROOT_FIX_VIDEO] = prefs.rootFixVideo
        p[Keys.V_BRIGHT] = prefs.vBright
        p[Keys.V_WARM] = prefs.vWarm
        p[Keys.NIGHT_MODE] = prefs.nightMode
        p[Keys.NIGHT_BRIGHT] = prefs.nightBright
        p[Keys.NIGHT_WARM] = prefs.nightWarm
        p[Keys.VERBOSE_ERRORS] = prefs.verboseErrors
        dropLegacy(p)
    }

    private fun dropLegacy(p: MutablePreferences) {
        p.remove(LegacyKeys.VIDEO_ENCODER)
        p.remove(LegacyKeys.FPS_MODE)
        p.remove(LegacyKeys.RES_MODE)
        p.remove(LegacyKeys.HW_ACCEL)
        p.remove(LegacyKeys.AUDIO_PC)
        p.remove(LegacyKeys.KB)
    }

    private fun Prefs.validated(): Prefs = copy(
        dim = dim.coerceIn(0, 100),
        base = base.copy(
            brightness = base.brightness.coerceIn(0, 100),
            contrast = base.contrast.coerceIn(0, 100),
            saturation = base.saturation.coerceIn(0, 100),
        ),
        vBright = vBright.coerceIn(10, 100),
        vWarm = vWarm.coerceIn(0, 100),
        nightBright = nightBright.coerceIn(10, 99),
        nightWarm = nightWarm.coerceIn(0, 100),
    )
}
