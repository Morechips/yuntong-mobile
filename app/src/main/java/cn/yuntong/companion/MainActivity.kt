package cn.yuntong.companion

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import cn.yuntong.companion.data.LegacyMigration
import cn.yuntong.companion.ui.YuntongApp

class MainActivity : ComponentActivity() {
    private val requestedPage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedPage.value = intent.getStringExtra("page")
        enableEdgeToEdge()
        setContent { YuntongApp((application as YuntongApplication).controller, requestedPage.value) {
            requestedPage.value = null
        } }
        if (!getSharedPreferences("migration", MODE_PRIVATE).getBoolean("v1", false)) {
            runCatching {
                LegacyMigration.read(this) {
                    (application as YuntongApplication).controller.migrate(it)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedPage.value = intent.getStringExtra("page")
    }
}
