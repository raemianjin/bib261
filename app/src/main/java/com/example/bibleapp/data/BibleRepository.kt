package com.example.bibleapp.data

import android.content.Context
import com.example.bibleapp.util.AppLogger
import org.json.JSONArray

object BibleRepository {

    private var booksKjv: List<Book> = emptyList()
    private var booksKrv: List<Book> = emptyList()
    val isLoaded: Boolean get() = booksKjv.isNotEmpty() || booksKrv.isNotEmpty()

    enum class Version(val label: String, val fileName: String) {
        KJV("영어 (KJV)", "bible_kjv.json"),
        KRV("개역개정", "bible_krv.json")
    }

    var currentVersion: Version = Version.KRV

    private val currentBooks: List<Book>
        get() {
            val b = if (currentVersion == Version.KRV) booksKrv else booksKjv
            return if (b.isEmpty()) if (booksKrv.isNotEmpty()) booksKrv else booksKjv else b
        }

    fun load(context: Context) {
        if (isLoaded) return
        booksKjv = loadFile(context, Version.KJV.fileName)
        booksKrv = loadFile(context, Version.KRV.fileName)
        AppLogger.i("Repo", "KJV=${booksKjv.size} KRV=${booksKrv.size}")
    }

    private fun loadFile(context: Context, fileName: String): List<Book> {
        return try {
            val json = context.assets.open(fileName).bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            ArrayList<Book>(arr.length()).also { list ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val chArr = o.getJSONArray("chapters")
                    val chapters = ArrayList<List<String>>(chArr.length())
                    for (c in 0 until chArr.length()) {
                        val vArr = chArr.getJSONArray(c)
                        chapters.add(ArrayList<String>(vArr.length()).also { vs ->
                            for (v in 0 until vArr.length()) vs.add(vArr.getString(v))
                        })
                    }
                    list.add(Book(i + 1, o.getString("name_en"), o.getString("name_ko"), chapters))
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Repo", "$fileName 실패: ${e.message}")
            emptyList()
        }
    }

    fun allBooks(): List<Book> = currentBooks
    fun bookByIndex(index: Int): Book? = currentBooks.getOrNull(index - 1)
    fun switchVersion(version: Version) { currentVersion = version }
    fun getKjvBook(bookIndex: Int): Book? = booksKjv.getOrNull(bookIndex - 1)
    fun getKrvBook(bookIndex: Int): Book? = booksKrv.getOrNull(bookIndex - 1)

    fun searchBooks(query: String): List<Book> {
        if (query.isBlank()) return currentBooks
        val q = query.trim().lowercase()
        return currentBooks.filter {
            it.nameKo.lowercase().contains(q) || it.nameEn.lowercase().contains(q)
        }
    }

    /** 전체 구절 텍스트 검색 (최대 100건, 빠른 응답) */
    fun searchVerses(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val results = mutableListOf<SearchResult>()
        for (book in currentBooks) {
            for ((ci, chapter) in book.chapters.withIndex()) {
                for ((vi, verse) in chapter.withIndex()) {
                    if (verse.contains(q, ignoreCase = true)) {
                        results.add(SearchResult(book.index, book.nameKo, ci + 1, vi + 1, verse))
                        if (results.size >= 5000) return results  // 안전 상한
                    }
                }
            }
        }
        return results
    }
}
