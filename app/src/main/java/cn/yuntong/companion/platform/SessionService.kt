package cn.yuntong.companion.platform

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.yuntong.companion.MainActivity
import cn.yuntong.companion.YuntongApplication

class SessionService : Service() {
    private var usesLocation = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val vm = (application as YuntongApplication).controller
        if (intent?.action == "STOP") {
            vm.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            "PAUSE" -> vm.setNavigationPaused(true, refreshNotification = false)
            "RESUME" -> vm.setNavigationPaused(false, refreshNotification = false)
        }
        if (intent?.hasExtra("location") == true)
            usesLocation = intent.getBooleanExtra("location", false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("session", "正在使用云瞳", NotificationManager.IMPORTANCE_LOW)
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, SessionService::class.java).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val phase = vm.state.value.navigation.phase
        val pause =
            PendingIntent.getService(
                this,
                2,
                Intent(this, SessionService::class.java).setAction(if (phase == "paused") "RESUME" else "PAUSE"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val emergency = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).putExtra("page", "紧急求助")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification =
            NotificationCompat.Builder(this, "session")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("云瞳正在运行")
                .setContentText("导航或设备连接已开启，点按返回")
                .setContentIntent(open)
                .setOngoing(true)
                .apply {
                    if (phase == "active" || phase == "paused")
                        addAction(0, if (phase == "paused") "恢复导航" else "暂停导航", pause)
                }
                .addAction(0, "紧急求助", emergency)
                .addAction(0, "结束全部任务", stop)
                .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (usesLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(10, notification, type)
        } else startForeground(10, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
