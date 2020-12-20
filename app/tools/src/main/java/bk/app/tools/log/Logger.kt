@file:Suppress("unused")

package bk.app.tools.log

import android.util.Log

interface Logger {
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun e(tag: String, msg: String, tr: Throwable? = null)
}

var logger = object : Logger {

    override fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun e(tag: String, msg: String, tr: Throwable?) {
        Log.e(tag, msg, tr)
    }

}

fun Any.logI(msg: String) {
    logger.i(this::class.java.simpleName, msg)
}

fun Any.logW(msg: String) {
    logger.w(this::class.java.simpleName, msg)
}

fun Any.logD(msg: String) {
    logger.d(this::class.java.simpleName, msg)
}

fun Any.logE(msg: String, tr: Throwable? = null) {
    logger.e(this::class.java.simpleName, msg, tr)
}