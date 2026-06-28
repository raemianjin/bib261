package com.example.bibleapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.bibleapp.data.VerseNote
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 메모 "장표"를 여러 종류의 디자인 템플릿으로 렌더링하고,
 * 폰용 / 패드용 공유 이미지 크기에 맞춰 카드 뷰를 만들어 주는 엔진.
 *
 * - 화면 미리보기: ShareFormat.SCREEN (기기 밀도 기준)
 * - 공유 이미지   : ShareFormat.PHONE(1080px) / ShareFormat.TABLET(1600px)
 *   → 고정 픽셀 폭으로 그려 기기와 무관하게 일정한 결과물을 만든다.
 */
object NoteCardRenderer {

    // ── 공유 형식 ────────────────────────────────────────
    enum class ShareFormat(val targetWidthPx: Int, val refWidthDp: Float) {
        SCREEN(0, 0f),            // 화면 미리보기 (기기 밀도 사용)
        PHONE(1080, 384f),        // 세로형 (카톡/인스타 스토리 등에 적합)
        TABLET(1600, 900f)        // 가로형 와이드 (메일/문서/발표용)
    }

    // ── 템플릿 정의 ──────────────────────────────────────
    data class Style(
        val bgColors: IntArray,          // 1색=단색, 2색 이상=그라데이션
        val bgAngle: Int,                // 그라데이션 각도
        val accent: Int,                 // 강조색(라인·일시)
        val refColor: Int,               // 구절 참조
        val verseColor: Int,             // 성경 본문
        val memoColor: Int,              // 메모 본문
        val footerColor: Int,            // 푸터
        val serifVerse: Boolean,         // 본문 세리프 여부
        val italicVerse: Boolean,        // 본문 이탤릭 여부
        val centered: Boolean,           // 중앙 정렬 여부
        val verseEmphasis: Float,        // 본문 강조 배율(1.0=보통)
        val cornerDp: Float,             // 카드 모서리
        val strokeColor: Int             // 테두리(없으면 0)
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    enum class Template(val label: String) {
        CLASSIC("클래식"),
        PAPER("종이"),
        MINIMAL("미니멀"),
        NIGHT("나이트"),
        SKY("하늘"),
        FOCUS("구절강조");

        val style: Style
            get() = when (this) {
                CLASSIC -> Style(
                    bgColors = intArrayOf(0xFF1A1D33.toInt(), 0xFF13152A.toInt()), bgAngle = 135,
                    accent = 0xFFC2A263.toInt(), refColor = 0xFFF4F1E8.toInt(),
                    verseColor = 0xFFD8D5CC.toInt(), memoColor = 0xFFF4F1E8.toInt(),
                    footerColor = 0x77AAB0C5.toInt(), serifVerse = true, italicVerse = true,
                    centered = false, verseEmphasis = 1.0f, cornerDp = 18f,
                    strokeColor = 0xFF2A2D4A.toInt()
                )
                PAPER -> Style(
                    bgColors = intArrayOf(0xFFFBF4E2.toInt(), 0xFFF3E6C8.toInt()), bgAngle = 135,
                    accent = 0xFFB08433.toInt(), refColor = 0xFF4A3A20.toInt(),
                    verseColor = 0xFF5B4A2E.toInt(), memoColor = 0xFF332B1C.toInt(),
                    footerColor = 0x99917A4E.toInt(), serifVerse = true, italicVerse = true,
                    centered = false, verseEmphasis = 1.05f, cornerDp = 14f,
                    strokeColor = 0xFFE3D2A6.toInt()
                )
                MINIMAL -> Style(
                    bgColors = intArrayOf(0xFFFFFFFF.toInt()), bgAngle = 0,
                    accent = 0xFFB0B0B0.toInt(), refColor = 0xFF1A1A1A.toInt(),
                    verseColor = 0xFF555555.toInt(), memoColor = 0xFF222222.toInt(),
                    footerColor = 0xFFBBBBBB.toInt(), serifVerse = false, italicVerse = false,
                    centered = false, verseEmphasis = 1.0f, cornerDp = 10f,
                    strokeColor = 0xFFE6E6E6.toInt()
                )
                NIGHT -> Style(
                    bgColors = intArrayOf(0xFF0A0A0C.toInt(), 0xFF141017.toInt()), bgAngle = 160,
                    accent = 0xFFD4AF37.toInt(), refColor = 0xFFEDE6D0.toInt(),
                    verseColor = 0xFFCFC8B6.toInt(), memoColor = 0xFFF2ECDD.toInt(),
                    footerColor = 0x66D4AF37.toInt(), serifVerse = true, italicVerse = false,
                    centered = true, verseEmphasis = 1.1f, cornerDp = 16f,
                    strokeColor = 0xFF3A3320.toInt()
                )
                SKY -> Style(
                    bgColors = intArrayOf(0xFF5B7CE0.toInt(), 0xFF7C5BD0.toInt(), 0xFF4E5388.toInt()),
                    bgAngle = 315,
                    accent = 0xFFFFE9A8.toInt(), refColor = 0xFFFFFFFF.toInt(),
                    verseColor = 0xFFEFEFFF.toInt(), memoColor = 0xFFFFFFFF.toInt(),
                    footerColor = 0x88FFFFFF.toInt(), serifVerse = false, italicVerse = true,
                    centered = true, verseEmphasis = 1.05f, cornerDp = 20f,
                    strokeColor = 0
                )
                FOCUS -> Style(
                    bgColors = intArrayOf(0xFF16182E.toInt(), 0xFF0E1020.toInt()), bgAngle = 135,
                    accent = 0xFFC2A263.toInt(), refColor = 0xFFC2A263.toInt(),
                    verseColor = 0xFFF6F3EA.toInt(), memoColor = 0xFFAEB4C8.toInt(),
                    footerColor = 0x66AAB0C5.toInt(), serifVerse = true, italicVerse = true,
                    centered = true, verseEmphasis = 1.5f, cornerDp = 18f,
                    strokeColor = 0xFF2A2D4A.toInt()
                )
            }
    }

    // ── 카드 빌드 ────────────────────────────────────────
    /**
     * @param forCapture true면 사진을 원본 비트맵 그대로(고화질) 싣고, false면 썸네일 다운샘플.
     */
    fun build(
        ctx: Context,
        note: VerseNote,
        verseText: String,
        template: Template,
        format: ShareFormat,
        forCapture: Boolean,
        footerText: String = "한영Bible"
    ): View {
        val st = template.style
        val density = ctx.resources.displayMetrics.density

        // dp→px, sp 적용 함수 (화면용 vs 캡처용)
        val pxScale: Float = if (format == ShareFormat.SCREEN) density
        else format.targetWidthPx / format.refWidthDp
        fun px(dp: Float): Int = (dp * pxScale).toInt()
        // 텍스트 크기: 화면용은 DIP, 캡처용은 PX(고정폭 기준)로 적용
        fun setText(tv: TextView, sizeDp: Float) {
            if (format == ShareFormat.SCREEN)
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizeDp)
            else
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizeDp * pxScale)
        }

        val tablet = format == ShareFormat.TABLET
        val pad = if (tablet) 40f else 28f
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(pad), px(pad), px(pad), px(pad))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(st.cornerDp).toFloat()
                if (st.bgColors.size == 1) setColor(st.bgColors[0])
                else {
                    colors = st.bgColors
                    orientation = angleToOrientation(st.bgAngle)
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                }
                if (st.strokeColor != 0) setStroke(px(1f), st.strokeColor)
            }
        }
        val gravH = if (st.centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        fun lp(top: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top; gravity = gravH }

        // 1) 장식 라인
        card.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(px(44f), px(3f)).apply {
                bottomMargin = px(18f); gravity = gravH
            }
            setBackgroundColor(st.accent)
        })

        // 2) 구절 참조
        card.addView(TextView(ctx).apply {
            text = note.reference
            setTextColor(st.refColor)
            setText(this, if (tablet) 24f else 22f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = gravH
            setTextIsSelectable(format == ShareFormat.SCREEN)
            layoutParams = lp()
        })

        // 3) 일시
        val dtFmt = SimpleDateFormat("yyyy년 MM월 dd일 HH:mm", Locale.KOREA)
        card.addView(TextView(ctx).apply {
            text = dtFmt.format(Date(note.updatedAt))
            setTextColor(st.accent)
            setText(this, 12f)
            letterSpacing = 0.05f
            gravity = gravH
            setTextIsSelectable(format == ShareFormat.SCREEN)
            layoutParams = lp(px(8f))
        })

        // 4) 구분선
        card.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1f))
                .apply { topMargin = px(18f); bottomMargin = px(18f) }
            setBackgroundColor((st.accent and 0x00FFFFFF) or 0x33000000)
        })

        // 5) 성경 본문
        if (verseText.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = "\u201C$verseText\u201D"
                setTextColor(st.verseColor)
                setText(this, (16f * st.verseEmphasis))
                if (st.serifVerse)
                    typeface = Typeface.create(Typeface.SERIF,
                        if (st.italicVerse) Typeface.ITALIC else Typeface.NORMAL)
                else if (st.italicVerse) setTypeface(typeface, Typeface.ITALIC)
                setLineSpacing(px(6f).toFloat(), 1f)
                gravity = if (st.centered) Gravity.CENTER else Gravity.START
                setTextIsSelectable(format == ShareFormat.SCREEN)
                layoutParams = lp()
            })
        }

        // 6) 메모 본문
        if (note.text.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = note.text
                setTextColor(st.memoColor)
                setText(this, 16f)
                setLineSpacing(px(7f).toFloat(), 1f)
                gravity = if (st.centered) Gravity.CENTER else Gravity.START
                setTextIsSelectable(format == ShareFormat.SCREEN)
                layoutParams = lp(px(22f))
            })
        }

        // 7) 사진
        val valid = note.photoPaths.filter { File(it).exists() }
        if (valid.isNotEmpty()) {
            val columns = if (tablet) 2 else 1
            card.addView(buildPhotos(ctx, valid, columns, pxScale, forCapture).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = px(22f)
            })
        }

        // 8) 푸터
        card.addView(TextView(ctx).apply {
            text = footerText
            setTextColor(st.footerColor)
            setText(this, 11f)
            letterSpacing = 0.1f
            gravity = if (st.centered) Gravity.CENTER else Gravity.END
            setTextIsSelectable(format == ShareFormat.SCREEN)
            layoutParams = lp(px(28f))
        })

        return card
    }

    /** 사진 그리드 (열 수 가변) */
    private fun buildPhotos(
        ctx: Context, paths: List<String>, columns: Int,
        pxScale: Float, forCapture: Boolean
    ): LinearLayout {
        fun px(dp: Float): Int = (dp * pxScale).toInt()
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        var i = 0
        while (i < paths.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = px(10f) }
            }
            for (c in 0 until columns) {
                val idx = i + c
                if (idx >= paths.size) {
                    if (columns > 1) row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    continue
                }
                val iv = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (c < columns - 1) marginEnd = px(10f)
                    }
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    try {
                        val bmp = if (forCapture) BitmapFactory.decodeFile(paths[idx])
                        else decodeSampled(paths[idx], 1000)
                        bmp?.let { setImageBitmap(it) }
                    } catch (_: Exception) {}
                }
                row.addView(iv)
            }
            wrap.addView(row)
            i += columns
        }
        return wrap
    }

    private fun decodeSampled(path: String, reqW: Int): Bitmap? {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opt)
        var inSample = 1
        while (opt.outWidth / inSample > reqW) inSample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = inSample })
    }

    private fun angleToOrientation(angle: Int): GradientDrawable.Orientation = when (((angle % 360) + 360) % 360) {
        in 23..67   -> GradientDrawable.Orientation.BL_TR
        in 68..112  -> GradientDrawable.Orientation.BOTTOM_TOP
        in 113..157 -> GradientDrawable.Orientation.BR_TL
        in 158..202 -> GradientDrawable.Orientation.RIGHT_LEFT
        in 203..247 -> GradientDrawable.Orientation.TR_BL
        in 248..292 -> GradientDrawable.Orientation.TOP_BOTTOM
        in 293..337 -> GradientDrawable.Orientation.TL_BR
        else        -> GradientDrawable.Orientation.LEFT_RIGHT
    }

    // ── 비트맵으로 캡처 (고정 픽셀 폭) ─────────────────────
    fun renderToBitmap(card: View, format: ShareFormat): Bitmap {
        val targetW = if (format == ShareFormat.SCREEN) card.width.coerceAtLeast(1)
        else format.targetWidthPx
        val wSpec = View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        card.measure(wSpec, hSpec)
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        val bmp = Bitmap.createBitmap(
            card.measuredWidth.coerceAtLeast(1),
            card.measuredHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)   // 투명 방지(라이트 템플릿 대비)
        card.draw(canvas)
        return bmp
    }
}
