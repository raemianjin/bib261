package com.example.bibleapp.ui

/** 구절에 가한 제스처 종류 */
enum class VerseGesture { SINGLE, DOUBLE, TRIPLE, LONG }

/**
 * 제스처에 매핑할 수 있는 동작.
 * 순서(ordinal)가 설정 저장값이므로 중간에 끼워넣지 말 것.
 */
enum class VerseAction(val label: String) {
    NONE("없음"),
    MENU("팝업 메뉴"),
    MULTICOPY("다중 복사"),
    BOOKMARK("북마크"),
    COPY_ONE("한 구절 복사");

    companion object {
        fun from(ordinal: Int): VerseAction = values().getOrElse(ordinal) { NONE }
    }
}
