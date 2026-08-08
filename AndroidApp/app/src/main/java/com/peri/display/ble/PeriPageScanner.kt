package com.peri.display.ble

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "PeriPageScan"

/**
 * Mac의 **호출 광고**를 오프로드 스캔으로 기다린다.
 *
 * ## 왜 PendingIntent인가
 *
 * 앱이 콜백으로 스캔을 들고 있으면 앱을 끄거나 폰이 잠드는 순간 스캔이 멈춘다. 필터와
 * "깨워서 전달할 우편함"을 **OS와 블루투스 칩셋에 맡기면** 앱이 죽어 있어도 칩셋이 계속
 * 감시하고, 매치될 때만 우리를 깨운다. 그게 이 파일의 전부다.
 *
 * ## 반드시 챙겨야 하는 것 셋
 *
 * ① **필터 없는 스캔은 화면이 꺼지면 시스템이 결과를 안 준다.** 그래서 필터는 선택이 아니다.
 *    [PeriPageToken]의 접두 4바이트만 걸고 나머지 12바이트는 마스크로 열어 둔다 — 그래야
 *    토큰이 회전해도 필터를 다시 등록할 필요가 없다.
 *
 * ② **`FLAG_MUTABLE`**. OS가 스캔 결과를 인텐트에 채워 넣어야 하므로 불변이면 데이터가
 *    안 온다(31+에서는 둘 중 하나를 반드시 명시해야 하기도 하다).
 *
 * ③ **끌 때도 켤 때와 같은 규격의 PendingIntent**를 넘겨야 칩셋에서 깔끔히 꺼진다.
 *    그래서 생성이 [pendingIntent] 한 곳에만 있다 — 두 곳에서 만들면 언젠가 어긋난다.
 *
 * ## 능력 확인이 먼저다
 *
 * 배치 보고(`setReportDelay`)는 칩셋이 지원해야 한다. 안 되는 기기에 걸면 `startScan`이
 * `SCAN_FAILED_FEATURE_UNSUPPORTED`로 떨어져 **스캔이 아예 안 켜진다.** 그리고 오프로드
 * **필터링**이 없으면 필터가 소프트웨어로 적용돼 ①의 억제가 그대로 걸린다 — 그때는 이
 * 방식의 전제가 무너지므로 [isSupported]가 false를 내고 호출부가 기존 상시 광고로 돌아간다.
 */
object PeriPageScanner {

    /** 우편함 식별자. 끌 때 같은 값으로 다시 만들어야 한다. */
    private const val REQUEST_CODE = 0x50414745   // "PAGE"
    const val ACTION_PAGE = "com.peri.display.action.BLE_PAGE"

    /**
     * 스캔에 필요한 런타임 권한. **API로 갈린다.**
     *
     * 31+는 `BLUETOOTH_SCAN` 하나로 되지만, 29~30의 레거시 스캔은 위치 권한을 요구한다.
     * 화면 확장 앱이 위치를 묻는 게 이상해 보이는 건 맞는데, 그 구간에서는 우회로가 없다.
     */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermission(ctx: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 이 기기에서 오프로드 호출 대기가 성립하는가. false면 호출부가 상시 광고로 폴백한다. */
    fun isSupported(ctx: Context): Boolean {
        val adapter = adapter(ctx) ?: return false
        if (!adapter.isEnabled) return false
        // 필터를 칩셋이 못 걸면 화면이 꺼진 동안 결과가 안 온다 → 기다리는 의미가 없다.
        return adapter.isOffloadedFilteringSupported
    }

    /**
     * 스캔을 칩셋에 위임한다. 이미 걸려 있으면 같은 PendingIntent라 덮어써진다.
     *
     * @return 시작됐으면 true. 권한·능력·어댑터 중 하나라도 없으면 false다.
     */
    fun start(ctx: Context): Boolean {
        if (!hasPermission(ctx)) { Log.w(TAG, "스캔 권한 없음"); return false }
        if (!isSupported(ctx)) { Log.w(TAG, "오프로드 필터링 미지원 — 호출 대기 불가"); return false }
        val scanner = adapter(ctx)?.bluetoothLeScanner ?: return false

        val filter = ScanFilter.Builder()
            // 접두 4B만 매치. 뒤 12B(nonce+OTP)는 매번 바뀌므로 마스크로 열어 둔다.
            .setServiceUuid(ParcelUuid(PeriPageToken.filterUuid()),
                            ParcelUuid(PeriPageToken.filterMask()))
            .build()

        val settings = ScanSettings.Builder()
            // 전파를 쏘지 않고 듣기만 한다. 호출은 급하지 않다 — 몇 백 ms 늦어도 무방하고,
            // 그 대가로 배터리를 아낀다.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .apply {
                // ★ 지원 여부를 **반드시** 먼저 본다. 무조건 걸면 미지원 기기에서 스캔이
                // 통째로 실패한다(SCAN_FAILED_FEATURE_UNSUPPORTED).
                if (adapter(ctx)?.isOffloadedScanBatchingSupported == true) {
                    setReportDelay(BATCH_DELAY_MS)
                }
            }
            .build()

        val code = runCatching {
            scanner.startScan(listOf(filter), settings, pendingIntent(ctx))
        }.getOrElse { Log.e(TAG, "스캔 위임 실패: ${it.message}"); return false }

        // 0이 성공. 그 외는 오류 코드다.
        if (code != 0) { Log.e(TAG, "스캔 위임 거부 (code=$code)"); return false }
        Log.i(TAG, "호출 대기 시작 — 칩셋에 위임됨")
        return true
    }

    /** 켤 때와 **같은 규격**의 PendingIntent로 꺼야 칩셋에서 깔끔히 내려간다. */
    fun stop(ctx: Context) {
        if (!hasPermission(ctx)) return
        runCatching { adapter(ctx)?.bluetoothLeScanner?.stopScan(pendingIntent(ctx)) }
            .onFailure { Log.w(TAG, "스캔 중지 실패: ${it.message}") }
    }

    /**
     * 우편함. **생성은 여기 한 곳뿐이다** — start와 stop이 각자 만들면 규격이 어긋나
     * 스캔이 안 꺼지는 날이 온다.
     */
    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, PeriPageReceiver::class.java).setAction(ACTION_PAGE)
        // ★ MUTABLE이라야 OS가 스캔 결과를 채워 넣는다. IMMUTABLE이면 빈 인텐트가 온다.
        return PendingIntent.getBroadcast(
            ctx.applicationContext, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun adapter(ctx: Context): BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /**
     * 배치 보고 주기(ms).
     *
     * 0(실시간)이면 화면이 꺼졌을 때 결과 전달을 억제하는 구현이 있다. 1초로 묶으면 그
     * 구간을 지나가면서도 사람이 체감할 만큼 늦지는 않는다.
     */
    private const val BATCH_DELAY_MS = 1_000L
}
