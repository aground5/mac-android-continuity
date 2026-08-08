package com.peri.display.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.peri.display.crypto.TrustStore

private const val TAG = "PeriPage"

/**
 * Mac의 호출 광고가 도착했을 때 OS가 깨우는 우편함.
 *
 * ## 하는 일이 적다 — 그게 설계다
 *
 * 토큰을 대조하고, 우리를 부른 게 맞으면 **광고를 켜는 것까지**다. 그다음은 예전과 똑같은
 * 경로다: 폰이 광고 → Mac이 central로 붙음 → 페어링(PAKE) 또는 재연결(STS) → LAN.
 *
 * ★ **여기서 포그라운드 서비스를 띄우지 않는다.** Android 12+는 백그라운드 브로드캐스트에서
 * 시작하는 FGS를 막는다(`ForegroundServiceStartNotAllowedException`). 이 앱엔
 * `SYSTEM_ALERT_WINDOW`가 있어 면제될 **수도** 있지만, 그건 사용자가 따로 허용해 준 권한에
 * 기대는 것이라 보장이 아니다.
 *
 * 그리고 애초에 필요가 없다 — **BLE 광고는 FGS 없이 된다.** FGS가 필요한 건 확장·공유가
 * 실제로 뜰 때이고, 그때는 사용자가 Mac에서 아이콘을 누른 뒤라 사용자 개시 맥락이 있다.
 * 리시버가 최소한만 하고 빠지면 실패 모드도 명확해진다.
 *
 * ## 대조에 실패하면 조용히 끝난다
 *
 * 접두만 걸린 필터라 **남을 부르는 광고에도 깨어난다.** 그게 정상이다 — 접두 4바이트로
 * 하드웨어가 거를 수 있는 건 "Peri 호출이다"까지고, "나를 부른다"는 여기서 판정한다.
 * 아니면 아무것도 안 하고 돌아간다.
 */
class PeriPageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PeriPageScanner.ACTION_PAGE) return

        val err = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (err != 0) {
            // 스캔이 시스템 쪽에서 죽었다. 앱이 다음에 뜰 때 다시 건다.
            Log.w(TAG, "스캔 오류 보고 (code=$err)")
            return
        }

        val results = scanResults(intent)
        if (results.isEmpty()) return

        val ctx = context.applicationContext
        val store = runCatching { TrustStore.get(ctx) }.getOrNull() ?: return
        val macs = runCatching { store.all() }.getOrDefault(emptyList())
        if (macs.isEmpty()) return   // 페어링한 Mac이 없으면 우리를 부를 사람도 없다

        for (r in results) {
            val uuids = r.scanRecord?.serviceUuids ?: continue
            for (p in uuids) {
                // 링크 키를 가진 Mac만 우리를 부를 수 있다 — 없는 상대는 resolve가 건너뛴다.
                val page = PeriPageToken.resolve(p.uuid, macs, { store.linkKey(it.pub) })
                    ?: continue
                val link = store.linkKey(page.mac.pub) ?: continue
                Log.i(TAG, "★ 호출 수신 — ${page.mac.name}")
                // 응답은 **부른 그 호출에 묶인다** — 소금이 그 호출의 nonce다.
                val answer = PeriPageToken.response(link, page.nonce)
                wake(ctx, answer)
                return
            }
        }
    }

    /**
     * 응답 광고를 띄워 Mac이 붙을 수 있게 한다.
     *
     * ★ 우리가 Mac에 붙지 않는다. GATT를 여는 쪽은 언제나 Mac이다 — 응답은 "여기 있다"까지고,
     * 연결은 Mac이 central로 건다.
     */
    private fun wake(ctx: Context, response: ByteArray) {
        runCatching { PeriPageResponder.onPaged(ctx, response) }
            .onFailure { Log.e(TAG, "호출 응답 실패: ${it.message}") }
    }

    private fun scanResults(intent: Intent): List<ScanResult> {
        val key = BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, ScanResult::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        } ?: emptyList()
    }
}

/**
 * 호출을 받았을 때 무엇을 할지 — 앱 쪽이 채워 넣는다.
 *
 * 리시버가 `PairingCoordinator`를 직접 만들면 프로세스가 깨어날 때마다 새 인스턴스가 생겨
 * 이미 떠 있는 것과 GATT 서버를 두고 경합한다(예전에 실제로 났던 문제). 그래서 살아 있는
 * 인스턴스가 자기를 등록해 두고, 없으면 액티비티를 띄우는 쪽으로 흐르게 한다.
 */
object PeriPageResponder {
    /** 살아 있는 코디네이터가 등록해 둔다. 프로세스가 죽어 있으면 null이다. */
    @Volatile var onPage: ((ByteArray) -> Unit)? = null

    /**
     * 프로세스가 죽어 있어 아직 못 내보낸 응답.
     *
     * ★ 이게 없으면 **가장 흔한 경우가 조용히 실패한다.** 호출의 요점은 앱이 죽어 있어도
     * 깨우는 것이고, 그때는 등록된 처리기가 없다. 응답을 버리고 액티비티만 띄우면 Mac은
     * 아무 대답도 못 듣는다. 창(±1 epoch) 안에 액티비티가 뜨면 이 값을 집어 내보낸다.
     */
    @Volatile var pending: ByteArray? = null

    fun onPaged(ctx: Context, response: ByteArray) {
        val handler = onPage
        if (handler != null) {
            handler(response)
            return
        }
        pending = response
        // 프로세스가 죽어 있었다 — 앱을 띄워 평소 경로로 들어가게 한다.
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) {
            runCatching { ctx.startActivity(launch) }
                .onFailure { Log.w(TAG, "액티비티 기동 실패(백그라운드 제한): ${it.message}") }
        }
    }

    /** 액티비티가 떴다 — 밀린 응답이 있으면 집어 간다(한 번만). */
    fun takePending(): ByteArray? {
        val p = pending
        pending = null
        return p
    }
}
