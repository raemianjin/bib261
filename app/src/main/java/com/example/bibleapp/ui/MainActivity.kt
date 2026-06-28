package com.example.bibleapp.ui

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.bibleapp.R
import com.example.bibleapp.data.BibleRepository
import com.example.bibleapp.data.BookmarkStore
import com.example.bibleapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val bibleFragment    by lazy { BibleFragment() }
    private val bookmarkFragment by lazy { BookmarkFragment() }
    private val noteFragment     by lazy { NoteFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    private var qtItem: MenuItem? = null
    private var currentTitleRes = R.string.tab_bible
    private val QT_ITEM_ID = 0x7100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BibleRepository.load(applicationContext)
        if (savedInstanceState == null) showFrag(bibleFragment, R.string.tab_bible)

        setupQtToggle()

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_bible     -> { showFrag(bibleFragment,    R.string.tab_bible);    true }
                R.id.nav_bookmark  -> { showFrag(bookmarkFragment, R.string.tab_bookmark); true }
                R.id.nav_note      -> { showFrag(noteFragment,     R.string.tab_note);     true }
                R.id.nav_settings  -> { showFrag(settingsFragment, R.string.tab_settings); true }
                else -> false
            }
        }
    }

    // ── QT 모드 토글 (성경 탭에서만 상단바 우측에 노출) ──
    private fun setupQtToggle() {
        qtItem = binding.toolbar.menu.add(Menu.NONE, QT_ITEM_ID, Menu.NONE, "QT").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == QT_ITEM_ID) { toggleQt(); true } else false
        }
        refreshQtItem()
    }

    private fun toggleQt() {
        val on = !BookmarkStore.isQtMode(this)
        BookmarkStore.setQtMode(this, on)
        refreshQtItem()
        Toast.makeText(
            this,
            if (on) "QT 모드 · 장을 선택하면 묵상 화면이 열립니다" else "QT 모드 해제 · 일반 성경 보기",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshQtItem() {
        val item = qtItem ?: return
        item.isVisible = (currentTitleRes == R.string.tab_bible)
        val on = BookmarkStore.isQtMode(this)
        val label = if (on) "QT ●" else "QT"
        val color = if (on) Color.parseColor("#E8C766") else Color.WHITE
        item.title = SpannableString(label).apply {
            setSpan(ForegroundColorSpan(color), 0, length, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshQtItem()
    }

    private fun showFrag(fragment: Fragment, titleRes: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment).commit()
        binding.toolbar.setTitle(titleRes)
        currentTitleRes = titleRes
        refreshQtItem()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val cur = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (cur is BibleFragment && cur.isSearchOpen()) {
            cur.closeSearch(); return
        }
        AlertDialog.Builder(this)
            .setTitle("앱 종료")
            .setMessage("한영Bible을 종료하시겠습니까?")
            .setPositiveButton("종료") { _, _ -> finish() }
            .setNegativeButton("취소", null).show()
    }
}
