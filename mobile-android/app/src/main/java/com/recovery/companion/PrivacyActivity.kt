package com.recovery.companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Health Connect 권한 안내(ACTION_SHOW_PERMISSIONS_RATIONALE)와 권한 사용 보기의 입구.
 * 같은 ‘개인정보와 데이터’ 화면을 앱 안(나 › 개인정보와 데이터)에서 연다.
 */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("pitin_page", "privacy")
        })
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
