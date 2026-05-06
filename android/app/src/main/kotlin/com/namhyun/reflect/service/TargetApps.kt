package com.namhyun.reflect.service

/**
 * Reflect 가 알림을 가로챌 메신저 패키지명 화이트리스트.
 * 추가/제거 시 여기만 수정.
 */
object TargetApps {
    val PACKAGES: Set<String> = setOf(
        "com.kakao.talk",                          // 카카오톡
        "com.instagram.android",                   // 인스타그램 (DM)
        "com.facebook.orca",                       // 페이스북 메신저
        "com.facebook.mlite",                      // Messenger Lite
        "com.google.android.apps.messaging",       // Messages by Google (문자)
        "com.samsung.android.messaging",           // 삼성 메시지
        "org.thunderdog.challegram",               // Telegram X
        "org.telegram.messenger",                  // Telegram
        "com.discord",                             // Discord
        "com.whatsapp",                            // WhatsApp
        "com.naver.line",                          // LINE
    )

    fun appKey(pkg: String): String = when (pkg) {
        "com.kakao.talk" -> "kakao"
        "com.instagram.android" -> "instagram"
        "com.facebook.orca", "com.facebook.mlite" -> "messenger"
        "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "sms"
        "org.telegram.messenger", "org.thunderdog.challegram" -> "telegram"
        "com.discord" -> "discord"
        "com.whatsapp" -> "whatsapp"
        "com.naver.line" -> "line"
        else -> pkg
    }
}
