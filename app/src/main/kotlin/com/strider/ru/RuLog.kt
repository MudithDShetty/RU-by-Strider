package com.strider.ru

import android.util.Log

/** Debug-only logging — message lambda avoids string work in release when inlined. */
object RuLog {
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(tag, message())
    }

    inline fun v(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.v(tag, message())
    }
}
