package me.mavenried.Ryde.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object UserPrefs {
    private const val PREFS_NAME = "user_prefs"
    private const val KEY_WEIGHT_KG = "weight_kg"
    private const val KEY_BIKE_WEIGHT_KG = "bike_weight_kg"
    private const val KEY_USE_LBS = "use_lbs"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_THEME = "theme"
    private const val KEY_USE_METRIC = "use_metric"
    private const val KEY_WEEKLY_NOTIFICATION = "weekly_notification"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_LIGHT_MODE_RIDING = "light_mode_riding"
    private const val KEY_HR_DEVICE_MAC = "hr_device_mac"
    private const val KEY_HR_DEVICE_NAME = "hr_device_name"
    private const val KEY_TTS_VOICE_NAME = "tts_voice_name"

    private val _themeFlow = MutableStateFlow("system")
    val themeFlow: StateFlow<String> = _themeFlow.asStateFlow()

    private val _metricsFlow = MutableStateFlow(true)
    val metricsFlow: StateFlow<Boolean> = _metricsFlow.asStateFlow()

    fun initTheme(context: Context) {
        _themeFlow.value = getTheme(context)
    }

    fun initMetric(context: Context) {
        _metricsFlow.value = isMetric(context)
    }

    fun getTheme(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "system") ?: "system"

    fun setTheme(context: Context, theme: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme).apply()
        _themeFlow.value = theme
    }

    fun isMetric(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_METRIC, true)

    fun setMetric(context: Context, metric: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_METRIC, metric).apply()
        _metricsFlow.value = metric
    }

    fun getWeightKg(context: Context): Double =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_WEIGHT_KG, 70f).toDouble()

    fun setWeightKg(context: Context, kg: Double) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_WEIGHT_KG, kg.toFloat()).apply()

    fun getBikeWeightKg(context: Context): Double =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_BIKE_WEIGHT_KG, 10f).toDouble()

    fun setBikeWeightKg(context: Context, kg: Double) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_BIKE_WEIGHT_KG, kg.toFloat()).apply()

    fun useLbs(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_LBS, false)

    fun setUseLbs(context: Context, useLbs: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_LBS, useLbs).apply()

    fun isOnboarded(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDED, true).apply()

    fun isWeeklyNotificationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WEEKLY_NOTIFICATION, true)

    fun setWeeklyNotificationEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WEEKLY_NOTIFICATION, enabled).apply()

    fun isAutoStartEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START, true)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_START, enabled).apply()

    fun isKeepScreenOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()

    fun isLightModeRiding(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIGHT_MODE_RIDING, false)

    fun setLightModeRiding(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LIGHT_MODE_RIDING, enabled).apply()

    fun getHrDeviceMac(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HR_DEVICE_MAC, null)

    fun getHrDeviceName(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HR_DEVICE_NAME, null)

    fun setHrDevice(context: Context, mac: String?, name: String?) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_HR_DEVICE_MAC, mac)
            .putString(KEY_HR_DEVICE_NAME, name)
            .apply()

    fun getTtsVoiceName(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TTS_VOICE_NAME, null)

    fun setTtsVoiceName(context: Context, voiceName: String?) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TTS_VOICE_NAME, voiceName).apply()

    fun kgToLbs(kg: Double) = kg * 2.20462
    fun lbsToKg(lbs: Double) = lbs / 2.20462

    // Unit conversion helpers
    fun kmToMi(km: Double) = km * 0.621371
    fun kmhToMph(kmh: Double) = kmh * 0.621371
    fun minPerKmToMinPerMi(minPerKm: Double) = minPerKm * 1.60934

    fun formatDistance(km: Double, isMetric: Boolean): String =
        if (isMetric) "%.2f km".format(km) else "%.2f mi".format(kmToMi(km))

    fun formatSpeed(kmh: Double, isMetric: Boolean): String =
        if (isMetric) "%.1f kmph".format(kmh) else "%.1f mph".format(kmhToMph(kmh))

    fun formatPace(minPerKm: Double, isMetric: Boolean): String {
        if (minPerKm <= 0 || minPerKm > 60) return "--:--"
        val pace = if (isMetric) minPerKm else minPerKmToMinPerMi(minPerKm)
        val unit = if (isMetric) "/km" else "/mi"
        val m = pace.toInt()
        val s = ((pace - m) * 60).toInt().coerceIn(0, 59)
        return "%d:%02d %s".format(m, s, unit)
    }

    fun speedUnitLabel(isMetric: Boolean) = if (isMetric) "kmph" else "mph"
    fun distanceUnitLabel(isMetric: Boolean) = if (isMetric) "km" else "mi"
}
