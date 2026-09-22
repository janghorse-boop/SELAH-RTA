package kr.joa.selahrta.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kr.joa.selahrta.MainActivity
import kr.joa.selahrta.R

/**
 * 화면이 뒤로 가도 **측정을 이어 가게** 붙들어 두는 서비스.
 *
 * **왜 필요한가.** 안드로이드는 앱이 뒤로 가면 마이크를 끊는다. 예배는
 * 두 시간이고 그동안 담당자는 다른 앱을 본다. 포그라운드 서비스를
 * `microphone` 형으로 띄워 두어야 그 사이에도 마이크가 살아 있다.
 *
 * **이 서비스는 측정을 하지 않는다.** 재는 것은 여전히
 * `CaptureController` 이고, 이것은 **프로세스를 앞에 세워 두는 일**만
 * 한다. 둘이 같은 상태를 만지지 않게 하려는 것이다 — 이 저장소에서
 * 거듭 틀렸던 자리가 「두 쪽이 같은 공용 상태를 만지는 곳」이었다.
 *
 * **되살아나지 않는다**(`START_NOT_STICKY`). 사용자가 시작한 측정만
 * 돈다. 시스템이 죽였다가 혼자 되살려 놓으면, 아무도 보고 있지 않은
 * 마이크가 켜진 채로 남는다.
 */
class CaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 알림에서 누른 「측정 종료」. 재는 쪽에 알리고 스스로 내려간다.
            // **둘 다 한다** — 듣는 쪽이 이미 사라졌어도 서비스는 내려가야
            // 하고, 살아 있으면 측정도 멈춰야 한다.
            CaptureServiceBridge.requestStop()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        // **승격을 삼키지 않는다.** `startForeground` 는 알림 권한이 없거나
        // 마이크 권한이 회수되면 던진다. 그걸 그대로 두면 안드로이드가
        // 5초 뒤에 앱을 죽인다 — 측정 중에 앱이 통째로 사라진다.
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        }.isSuccess
        if (!promoted) {
            // 붙들어 둘 수 없다. 재는 쪽에 알리고 스스로 내려간다.
            // **측정을 멈추지는 않는다** — 화면을 보고 있는 동안은
            // 그대로 재고, 뒤로 가면 끊길 수 있다는 것만 알린다.
            CaptureServiceBridge.reportUnavailable()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * **최근 앱에서 밀어냈다.** 측정도 서비스도 끝낸다.
     *
     * `onCleared` 하나에 기대지 않는 까닭(독립 검증 지적): 액티비티가
     * 정상적으로 끝나면 ViewModel 이 정리되지만, 최근 앱에서 밀어내는
     * 경로는 그 보장을 주지 않는다. 그러면 **아무도 보고 있지 않은
     * 마이크가 알림만 달고 남는다.**
     *
     * **재는 중에도 끝낸다.** 최근 앱에서 밀어내는 것은 「이 앱을 닫는다」는
     * 분명한 뜻이고, 이 서비스의 방침(`START_NOT_STICKY` — 사용자가 시작한
     * 측정만 돈다)과 같다. 음악 앱이라면 반대로 두겠지만, 이것은 **사람이
     * 값을 보려고 켜 둔 계기**다.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // **이 줄이 있어야 확인된다.** 없으면 「밀어냈는데 서비스가
        // 없다」는 사실이 **여기가 한 일인지 프로세스가 죽은 덕인지** 가려지지
        // 않는다 — 기기에서 둘 다 사라졌고, 이 줄로서야 불린 것을 봤다.
        Log.i(TAG, "최근 앱에서 밀어냈다 — 측정과 서비스를 끝낸다")
        CaptureServiceBridge.requestStop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                // 낮은 중요도 — 소리도 진동도 내지 않는다. 예배 중이다.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.capture_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_measure)
            .setContentTitle(getString(R.string.capture_notice_title))
            // **숫자를 적지 않는다.** 알림을 초마다 고쳐 쓰면 배터리를 먹고,
            // 잠금화면에 음압이 계속 뜨는 것도 바라지 않는 일이다. 값은
            // 앱에서 본다.
            .setContentText(getString(R.string.capture_notice_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.capture_notice_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "kr.joa.selahrta.STOP_CAPTURE"

        /**
         * 띄운다. **측정을 시작하기 전에** 불러야 한다.
         *
         * 안드로이드 14부터 `microphone` 형 서비스는 **앱이 앞에 있을 때만**
         * 띄울 수 있다. 사용자가 「측정 시작」을 누른 그 순간이 그 자리다.
         */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureService::class.java),
            )
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}

/**
 * 알림의 「측정 종료」가 재는 쪽에 닿는 **한 방향 통로**.
 *
 * 서비스는 화면·ViewModel 을 알지 못하고, 알 필요도 없다. 여기로
 * 한 번 알리면 듣고 있던 쪽이 멈춘다.
 *
 * **한 방향이다.** 쓰는 곳은 [requestStop] 하나뿐이고, 어느 쪽도 상대의
 * 상태를 만지지 않는다. 프로세스에 하나뿐인 물건이라 그 점을 적어 둔다 —
 * 이 저장소에서 거듭 틀렸던 것이 「둘이 같은 상태를 만지는 자리」였다.
 *
 * 듣는 쪽이 없으면 아무 일도 일어나지 않는다. 그때는 서비스가 스스로
 * 내려가는 것으로 끝난다.
 */
object CaptureServiceBridge {

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val stopRequests: SharedFlow<Unit> = _stopRequests

    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }

    private val _unavailable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * 백그라운드로 붙들어 두기에 실패했다.
     *
     * [stopRequests] 와 따로 둔다 — 하나는 「멈춰 달라」, 하나는
     * 「붙들지 못한다」다. 같은 통로로 보내면 서비스가 실패한 것과
     * 사용자가 누른 것이 구별되지 않아 측정이 엉뚱하게 멈춘다.
     */
    val unavailable: SharedFlow<Unit> = _unavailable

    fun reportUnavailable() {
        _unavailable.tryEmit(Unit)
    }
}
