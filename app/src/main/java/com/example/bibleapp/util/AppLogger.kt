package com.example.bibleapp.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 용량 제한 로테이션 로그.
 * - log.txt 가 MAX_SIZE 초과 시 log.1.txt 로 밀고 새로 시작
 * - 최대 2개 파일 유지 (총 약 512KB)
 * - 처리되지 않은 예외(크래시) 자동 기록
 */
object AppLogger {

    private const val MAX_SIZE = 256 * 1024
    private const val FILE_MAIN = "log.txt"
    private const val FILE_OLD  = "log.1.txt"

    private var logDir: File? = null
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            write("CRASH", "스레드 ${thread.name}\n$sw")
            prev?.uncaughtException(thread, error)
        }
        write("INFO", "===== 앱 시작 =====")
    }

    fun i(tag: String, msg: String) = write("INFO", "[$tag] $msg")
    fun e(tag: String, msg: String) = write("ERROR", "[$tag] $msg")

    @Synchronized
    private fun write(level: String, msg: String) {
        val dir = logDir ?: return
        try {
            val main = File(dir, FILE_MAIN)
            if (main.exists() && main.length() > MAX_SIZE) {
                val old = File(dir, FILE_OLD)
                if (old.exists()) old.delete()
                main.renameTo(old)
            }
            File(dir, FILE_MAIN).appendText("${ts.format(Date())} $level $msg\n")
        } catch (_: Exception) { }
    }

    fun readAll(): String {
        val dir = logDir ?: return "로그 없음"
        return buildString {
            File(dir, FILE_OLD).takeIf { it.exists() }?.let { append(it.readText()) }
            File(dir, FILE_MAIN).takeIf { it.exists() }?.let { append(it.readText()) }
        }.ifBlank { "로그 없음" }
    }

    fun clear() {
        val dir = logDir ?: return
        File(dir, FILE_MAIN).delete()
        File(dir, FILE_OLD).delete()
    }
}
