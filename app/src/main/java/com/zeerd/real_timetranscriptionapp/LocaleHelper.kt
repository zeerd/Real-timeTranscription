package com.zeerd.real_timetranscriptionapp

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * 运行时语言切换工具。
 *
 * 通过把用户选择的语言持久化到 SharedPreferences，并在每次 [applyOverrideConfiguration]
 * （Activity，API 17+）或 [wrapContext]（Service）时套用，实现「设置内切换语言、立即生效」的效果。
 */
object LocaleHelper {
    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_EN = "en"
    const val LANGUAGE_ZH = "zh"

    private const val PREFS_NAME = "app_locale"
    private const val KEY_LANGUAGE = "language"

    private val supported = listOf(LANGUAGE_SYSTEM, LANGUAGE_EN, LANGUAGE_ZH)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLanguage(context: Context): String {
        val saved = prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
        return if (saved in supported) saved else LANGUAGE_SYSTEM
    }

    fun setLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply()
    }

    /** 把用户选择的语言解析为 Locale（system 表示跟随系统）。 */
    fun resolveLocale(context: Context): Locale {
        val lang = getLanguage(context)
        return when (lang) {
            LANGUAGE_EN -> Locale.ENGLISH
            LANGUAGE_ZH -> Locale.SIMPLIFIED_CHINESE
            else -> Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
        }
    }

    /** 在 ContextWrapper 链上套用语言配置，供 Service 的 attachBaseContext 调用。 */
    fun wrapContext(context: Context): Context {
        val locale = resolveLocale(context)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * 生成套用了所选语言的 [Configuration]，供 Activity 的 [android.app.Activity.applyOverrideConfiguration] 使用。
     * 注意：必须基于系统资源配置构造，避免继承已套用的语言导致叠加。
     */
    fun newConfiguration(context: Context): Configuration {
        val locale = resolveLocale(context)
        val config = Configuration(Resources.getSystem().configuration)
        config.setLocale(locale)
        return config
    }
}
