package com.sweetsamosa.universaldl.app

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.function.Consumer

/** Runs gallery-dl (pure Python, installed by Chaquopy) through app/src/main/python/gdl.py. */
object Gallery {
    private fun module(ctx: Context): PyObject {
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
        return Python.getInstance().getModule("gdl")
    }

    /** Saves everything at [url] into [dest]. Returns files saved, -1 if cancelled; throws on failure. */
    fun run(ctx: Context, url: String, dest: File, cookies: File?, onLine: (String) -> Unit): Int =
        module(ctx)
            .callAttr("run", url, dest.absolutePath, cookies?.absolutePath, Consumer<String> { onLine(it) })
            .toInt()

    /** Pulls the newest gallery-dl wheel from PyPI (no rebuild needed). Returns a status line. */
    fun update(ctx: Context): String = module(ctx).callAttr("update").toString()

    fun version(ctx: Context): String = runCatching { module(ctx).callAttr("version").toString() }.getOrDefault("Unknown")

    fun cancel() {
        if (Python.isStarted()) runCatching { Python.getInstance().getModule("gdl").callAttr("cancel") }
    }
}
