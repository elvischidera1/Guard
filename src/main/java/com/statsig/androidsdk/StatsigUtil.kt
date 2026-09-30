package com.statsig.androidsdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy

internal object StatsigUtil {
    private val gson by lazy {
        GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create()
    }

    internal fun getOrBuildGson(): Gson = gson
}
