package com.example.bibleapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.bibleapp.R
import com.example.bibleapp.data.AiStore
import com.example.bibleapp.data.BookmarkStore
import com.example.bibleapp.databinding.ActivityBulletinAiBinding
import com.example.bibleapp.util.AiClient
import java.io.ByteArrayOutputStream

/**
 * 주보 URL을 인앱 WebView로 열고, 현재 화면(전체 문서)을 그대로 캡처해
 * Gemini 비전 모델에 보낸다. 모델이 '가장 크고 가운데 있는 본문 말씀'을 인식해
 * 사용자 주보 요약 프롬프트대로 정리/요약한다.
 *
 * HTML 구조가 사이트마다 달라 텍스트 스크래핑은 불안정하므로, 화면 자체를
 * 이미지로 보내는 방식(가장 견고한 폴백)을 기본 경로로 사용한다.
 */
class BulletinAiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBulletinAiBinding
    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBulletinAiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = BookmarkStore.getBulletinUrl(this)
        if (url.isBlank()) {
            Toast.makeText(this, "설정에서 주보 URL을 먼저 입력하세요.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        binding.bulletinBack.setOnClickListener { finish() }

        binding.bulletinWeb.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    pageLoaded = true
                }
            }
            loadUrl(url)
        }

        binding.btnBulletinAi.setOnClickListener { runBulletinAi() }
    }

    private fun runBulletinAi() {
        if (AiStore.getKey(this, "gemini").isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Gemini 키 필요")
                .setMessage("주보 이미지 인식은 Gemini 키가 필요합니다.\n설정 > AI 설정에서 Gemini 키를 입력하세요.")
                .setPositiveButton("확인", null).show()
            return
        }
        val base64 = runCatching { captureWebViewJpegBase64() }.getOrNull()
        if (base64 == null) {
            Toast.makeText(this, "화면 캡처에 실패했습니다. 잠시 후 다시 시도하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = "다음은 교회 주보를 캡처한 이미지입니다. 이미지에서 가장 크고 가운데에 있는 " +
            "성경 본문 말씀(설교 본문 구절)을 찾아내고, 먼저 그 본문(책 장:절)을 알려준 뒤, " +
            "아래 지시대로 처리해줘.\n\n[지시]\n" + AiStore.getBulletinPrompt(this)

        val progress = AlertDialog.Builder(this)
            .setMessage("주보를 AI가 읽는 중…").setCancelable(false).create()
        progress.show()
        Thread {
            val result = runCatching { AiClient.completeWithImage(this, prompt, base64) }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess { showResult(it) }
                    .onFailure {
                        AlertDialog.Builder(this).setTitle("AI 오류")
                            .setMessage(it.message ?: "알 수 없는 오류")
                            .setPositiveButton("확인", null).show()
                    }
            }
        }.start()
    }

    /** WebView 전체 문서를 비트맵으로 그린 뒤 폭을 줄이고 JPEG base64로 인코딩 */
    private fun captureWebViewJpegBase64(): String {
        val web = binding.bulletinWeb
        val density = resources.displayMetrics.density
        val w = if (web.width > 0) web.width else resources.displayMetrics.widthPixels
        val contentH = (web.contentHeight * density).toInt()
        val h = contentH.coerceIn(web.height.coerceAtLeast(1), 9000)
        var bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        web.draw(canvas)

        // 폭이 너무 크면 축소 (전송량/토큰 절감)
        val maxW = 1080
        if (w > maxW) {
            val ratio = maxW.toFloat() / w
            val scaled = Bitmap.createScaledBitmap(bmp, maxW, (h * ratio).toInt(), true)
            bmp.recycle(); bmp = scaled
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        bmp.recycle()
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun readableTextColor(): Int {
        val t = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, t, true)
        return if (t.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT)
            t.data else getColor(R.color.note_text)
    }

    private fun showResult(text: String) {
        val dp = resources.displayMetrics.density
        val pad = (18 * dp).toInt()
        val tv = TextView(this).apply {
            setText(text); textSize = 16f
            isFocusable = true; isFocusableInTouchMode = true; setTextIsSelectable(true)
            setLineSpacing(5 * dp, 1f); setPadding(pad, (8 * dp).toInt(), pad, 0)
            setTextColor(readableTextColor())
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("주보 성경 구절 요약")
            .setView(scroll)
            .setPositiveButton("복사") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("bulletin_ai", text))
                Toast.makeText(this, "복사됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
