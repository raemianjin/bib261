package com.example.bibleapp.util

import android.content.Context
import android.content.res.Configuration

/**
 * 폰 / 패드(태블릿) 구분 유틸.
 * smallestScreenWidthDp 가 600dp 이상이면 태블릿으로 간주한다.
 * (단일 APK 로 폰·패드를 동시에 지원하기 위한 런타임 분기용)
 */
object DeviceUtil {

    const val SW_TABLET = 600

    /** 태블릿(패드)인지 여부 */
    fun isTablet(ctx: Context): Boolean =
        ctx.resources.configuration.smallestScreenWidthDp >= SW_TABLET

    /** 현재 가로 모드인지 */
    fun isLandscape(ctx: Context): Boolean =
        ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 화면 너비(dp) */
    fun screenWidthDp(ctx: Context): Int =
        ctx.resources.configuration.screenWidthDp

    /** 패드 + 가로 모드 (2열·와이드 레이아웃에 적합한 조건) */
    fun isWide(ctx: Context): Boolean = isTablet(ctx) && screenWidthDp(ctx) >= 720
}
