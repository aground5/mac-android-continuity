package com.peri.display.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.peri.display.crypto.PeriIdentity
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val TAG = "BlePeripheral"

/**
 * 호출 응답 광고를 유지하는 시간.
 *
 * Mac의 호출은 30초까지 살아 있고 그 안에 우리를 발견해 붙어야 한다. 이보다 짧으면 Mac이
 * 스캔 주기에 걸리기 전에 사라지고, 길면 응답이 사실상 비콘이 된다.
 */
private const val RESPONSE_MS = 20_000L

object PeriBleUuids {
    val SERVICE: UUID = UUID.fromString("D7F3A8E0-1B2C-4D5E-9F60-A1B2C3D4E5F6")
    val RX: UUID = UUID.fromString("D7F3A8E1-1B2C-4D5E-9F60-A1B2C3D4E5F6")   // central→peripheral (write)
    val TX: UUID = UUID.fromString("D7F3A8E2-1B2C-4D5E-9F60-A1B2C3D4E5F6")   // peripheral→central (notify)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * BLE 페어링 전송(2b) — Android = peripheral(광고 + GATT 서버). Mac=central.
 * 프로토콜 무관 불투명 프레임 파이프: 논리 메시지 = [2B BE 길이][payload], MTU 청크로 쪼개
 * TX(notify)로 보내고 RX(write)로 받아 길이프리픽스로 재조립. 위에 2c SAS 프로토콜이 얹힌다.
 *
 * 권한(BLUETOOTH_ADVERTISE/CONNECT, 31+)은 호출 측(MainActivity)에서 런타임 보장 →
 * 여기선 @SuppressLint("MissingPermission"). start() 전에 권한·BT 활성 확인 필요.
 */
@SuppressLint("MissingPermission")
class BlePeripheral(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var central: BluetoothDevice? = null
    @Volatile private var mtu = 23
    private val rxBuffer = ByteArrayOutputStream()

    /** 광고 토큰 계산용 신원 공개키 (서명엔 쓰지 않음 — HMAC 키로만). */
    private val identityPub: ByteArray by lazy { PeriIdentity.get(appContext).publicKey }
    private val refreshHandler = Handler(Looper.getMainLooper())

    /** 재조립된 논리 프레임 수신 콜백. */
    var onFrame: ((ByteArray) -> Unit)? = null
    /** 상태 문자열 콜백(UI 표시용). */
    var onStatus: ((String) -> Unit)? = null
    /** 붙어 있던 central(Mac)이 떨어졌다 — 페어링 라운드의 종료 신호. */
    var onCentralDisconnected: (() -> Unit)? = null

    /** 이 기기가 BLE peripheral(광고)을 지원하는지. */
    val isSupported: Boolean get() = adapter?.bluetoothLeAdvertiser != null

    /** 광고에 실을 플래그 비트(PeriAdvertToken.FLAG_*). 광고 재시작 시 반영된다. */
    @Volatile private var advertFlags: Byte = 0

    /** 호출 응답 모드면 그 [PeriAdvertToken.RESPONSE_BYTES]바이트. null이면 평소(신원 토큰) 광고다. */
    @Volatile private var pageResponse: ByteArray? = null

    /**
     * 재협상 요청 비트를 올리고/내리고 **즉시 재광고**한다.
     *
     * 광고 데이터는 `startAdvertising`에서만 만들어지므로 값을 바꾸는 것만으론 전파되지 않는다
     * — 스택에 이미 올라간 광고를 내렸다 다시 올려야 Mac 스캐너가 새 바이트를 본다.
     *
     * Mac이 이미 GATT로 붙어 있으면 재광고할 필요가 없다. 그 Mac은 곧 후보 교환을 시작할
     * 것이고, 붙어 있는 링크를 광고 재시작으로 건드릴 이유도 없다.
     */
    @Synchronized
    fun setIceRestartWanted(wanted: Boolean) {
        val next = if (wanted) (advertFlags.toInt() or PeriAdvertToken.FLAG_ICE_RESTART.toInt()).toByte()
                   else (advertFlags.toInt() and PeriAdvertToken.FLAG_ICE_RESTART.toInt().inv()).toByte()
        if (next == advertFlags) return
        advertFlags = next
        val adv = advertiser
        if (adv == null) {
            // 광고가 안 돌고 있으면 이 비트는 아무 데도 못 간다 = 재협상 신호 유실.
            // 조용히 넘어가면 "요청은 했는데 Mac이 안 온다"로 보여 진단이 어렵다.
            Log.e(TAG, "광고 재협상 비트 → $wanted 실패: 광고가 꺼져 있다 (신호 유실)")
            return
        }
        Log.i(TAG, "광고 재협상 비트 → $wanted")
        // 이미 붙어 있는 central은 광고를 다시 읽지 않는다(스캔이 아니라 링크 상태다).
        // 링크를 끊어 Mac을 스캔으로 되돌려야 새 광고의 요청 비트를 보고 다시 붙는다.
        // **비트를 올릴 때만** — 내릴 때는 Mac을 부를 이유가 없다.
        if (wanted && central != null) {
            Log.i(TAG, "central 연결 중 → 링크를 끊어 재광고로 알린다")
            runCatching { gattServer?.cancelConnection(central) }
        }
        // ★ 재광고는 **올릴 때도 내릴 때도** 반드시 한다.
        //
        // 예전엔 비트를 내릴 때 central이 붙어 있으면 여기서 빠져나갔는데, 그러면
        // `advertFlags`만 0이 되고 **실제 광고 데이터엔 켜진 비트가 그대로 남는다.**
        // Mac은 스캔으로 그 낡은 광고를 계속 보고 재협상을 요청 → 성공한 뒤에도 15초 주기로
        // 무한 반복했다(실기기 로그: 망 변경 없이 라운드가 끝없이 돌았다).
        runCatching { adv.stopAdvertising(advCallback) }
        startAdvertising()
    }

    /**
     * 광고 + GATT 서버 시작. **재진입 안전** — 이미 돌고 있으면 아무것도 하지 않는다.
     *
     * 두 번 부르면 GATT 서버를 다시 등록하면서 서비스가 잠깐 사라진다. 그 찰나에 central이
     * 붙으면 서비스 탐색이 빈손으로 끝나 그대로 멈춘다(실기기 로그: `onServerConnectionState
     * connected=true`가 `onServiceAdded`보다 4ms 먼저 찍히고 그 뒤 18초간 무반응).
     */
    fun start() {
        val adapter = adapter ?: run { onStatus?.invoke("Bluetooth 없음"); return }
        if (!adapter.isEnabled) { onStatus?.invoke("Bluetooth 꺼짐"); return }
        if (adapter.bluetoothLeAdvertiser == null) { onStatus?.invoke("BLE 광고 미지원 기기"); return }
        if (gattServer != null) { Log.i(TAG, "이미 광고 중 — start 무시"); return }
        startGattServer()
        startAdvertising()
    }

    /**
     * **호출에 대한 응답.** Mac의 호출 광고를 알아들었다고 잠깐 알린다.
     *
     * ★ 우리가 Mac에 붙지 않는다. GATT를 여는 쪽은 **언제나 Mac**이다 — 역할이 라운드마다
     * 뒤집히면 두 스택의 상태가 갈라지고, 그건 이 코드베이스가 이미 한 번 겪은 종류의 버그다.
     * 그래서 응답도 "붙어라"라는 신호일 뿐이고, 실제 연결은 Mac이 central로 건다.
     *
     * 평소 광고와 두 가지가 다르다:
     *  - **이름을 싣지 않는다.** 스캔 응답 자체를 비운다. 부른 Mac은 우리가 누군지 이미 알고,
     *    모르는 사람에게 이름을 알릴 이유는 더더욱 없다.
     *  - 상시가 아니다. [RESPONSE_MS] 뒤에 광고를 내린다(GATT 서버는 남긴다 — 그 사이 붙은
     *    링크를 끊으면 안 된다).
     */
    @Synchronized
    fun respondToPage(response: ByteArray) {
        val adapter = adapter ?: run { onStatus?.invoke("Bluetooth 없음"); return }
        if (!adapter.isEnabled) { onStatus?.invoke("Bluetooth 꺼짐"); return }
        if (adapter.bluetoothLeAdvertiser == null) { onStatus?.invoke("BLE 광고 미지원 기기"); return }
        if (gattServer == null) startGattServer()
        pageResponse = response
        runCatching { advertiser?.stopAdvertising(advCallback) }
        startAdvertising()
        Log.i(TAG, "★ 호출 응답 광고 시작 (${RESPONSE_MS / 1000}초)")
        refreshHandler.postDelayed({
            if (pageResponse == null) return@postDelayed
            pageResponse = null
            // 이미 Mac이 붙었으면 광고를 내려도 그 링크는 그대로다.
            runCatching { advertiser?.stopAdvertising(advCallback) }
            Log.i(TAG, "호출 응답 종료")
        }, RESPONSE_MS)
    }

    fun stop() {
        refreshHandler.removeCallbacksAndMessages(null)
        pageResponse = null
        runCatching { advertiser?.stopAdvertising(advCallback) }
        runCatching { gattServer?.close() }
        gattServer = null; advertiser = null; central = null; txChar = null
        rxBuffer.reset(); mtu = 23
    }

    /** 논리 프레임 송신 (2B 길이프리픽스 + (mtu-3) 청크 notify). */
    fun send(frame: ByteArray) {
        val server = gattServer ?: return
        val dev = central ?: return
        val tx = txChar ?: return
        val msg = ByteArray(2 + frame.size)
        msg[0] = ((frame.size ushr 8) and 0xFF).toByte()
        msg[1] = (frame.size and 0xFF).toByte()
        System.arraycopy(frame, 0, msg, 2, frame.size)
        // GATT 속성값 상한 512B(스펙) — MTU-3가 512를 넘어도 512로 캡(안 그러면 notify가 거부됨).
        val chunk = (mtu - 3).coerceIn(20, 512)
        var i = 0
        while (i < msg.size) {
            val end = minOf(i + chunk, msg.size)
            notify(server, dev, tx, msg.copyOfRange(i, end))
            i = end
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(server: BluetoothGattServer, dev: BluetoothDevice, tx: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(dev, tx, false, value)
        } else {
            tx.value = value
            server.notifyCharacteristicChanged(dev, tx, false)
        }
    }

    private fun startGattServer() {
        val server = manager.openGattServer(appContext, serverCallback) ?: run {
            onStatus?.invoke("GATT 서버 열기 실패"); return
        }
        gattServer = server
        val service = BluetoothGattService(PeriBleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            PeriBleUuids.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            PeriBleUuids.TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                PeriBleUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        server.addService(service)
        txChar = tx
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // ★ 31B 예산은 **한도에 딱 맞는다** — 남는 자리가 없다.
        //
        //     3  Flags               ← 우리가 안 쓰는데 스택이 붙인다(setConnectable(true))
        //    18  서비스 UUID(128비트)
        //    10  제조사 데이터 (머리 4 + 본문 6)
        //   ───
        //    31
        //
        // 그 Flags 3B를 세지 않아서 응답 광고가 34B가 됐던 적이 있다. 증상이 고약했다:
        // 스택이 `ADVERTISE_FAILED_DATA_TOO_LARGE`로 조용히 거절하므로 Mac 쪽에서는
        // "폰이 응답을 안 한다"로만 보였다. 그래서 산술을 `PeriAdvertToken`의 상수로 옮기고
        // `PeriAdvertBudgetTest`가 두 모드의 본문 길이를 실제로 센다.
        //
        // 본문 = 회전 신원 토큰 + 플래그 → Mac이 광고만 보고 "페어링된 그 기기"임을,
        // 그리고 그 기기가 재협상을 원하는지를 **연결 없이** 안다.
        // 호출 응답 모드에서는 그 자리에 응답이 들어가고, **이름을 아예 안 싣는다.**
        //
        // @Volatile 필드를 한 번만 읽어 지역에 묶는다 — 아래 세 곳이 같은 값을 봐야 한다.
        // 광고를 올리는 도중에 `respondToPage`가 끼어들면 본문은 응답인데 이름은 싣는,
        // 어느 쪽도 아닌 광고가 나갈 수 있다.
        val resp = pageResponse
        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(PeriBleUuids.SERVICE))
            .addManufacturerData(
                PeriAdvertToken.COMPANY_ID,
                if (resp != null) PeriAdvertToken.responsePayload(resp)
                else PeriAdvertToken.payload(identityPub, advertFlags),
            )
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(resp == null)
            .build()
        adv.startAdvertising(settings, advData, scanResponse, advCallback)
        // 응답 광고는 수명이 짧고 창을 넘길 일이 없다 — 굴릴 토큰도 없다.
        if (resp == null) scheduleTokenRefresh()
    }

    /**
     * epoch가 넘어가면 광고를 다시 올려 토큰을 굴린다. 안 하면 Mac의 ±1 구간 허용을 벗어나
     * (최대 30분 뒤) 해석이 끊긴다. GATT 연결과 광고는 별개라 재광고가 연결을 끊지 않지만,
     * 이미 central이 붙어 있으면 굳이 건드리지 않는다(그 Mac은 이미 우리를 안다).
     */
    private fun scheduleTokenRefresh() {
        refreshHandler.removeCallbacksAndMessages(null)
        val nowMs = System.currentTimeMillis()
        val periodMs = PeriAdvertToken.EPOCH_SECONDS * 1000L
        val nextEpochAtMs = (PeriAdvertToken.currentEpoch(nowMs) + 1) * periodMs
        refreshHandler.postDelayed({
            val adv = advertiser ?: return@postDelayed
            if (central != null) { scheduleTokenRefresh(); return@postDelayed }
            runCatching { adv.stopAdvertising(advCallback) }
            startAdvertising()   // 새 epoch 토큰으로 재광고 + 다음 갱신 예약
        }, (nextEpochAtMs - nowMs).coerceAtLeast(1_000L))
    }

    private fun ingest(data: ByteArray) {
        rxBuffer.write(data)
        val buf = rxBuffer.toByteArray()
        var consumed = 0
        while (buf.size - consumed >= 2) {
            val len = ((buf[consumed].toInt() and 0xFF) shl 8) or (buf[consumed + 1].toInt() and 0xFF)
            if (buf.size - consumed < 2 + len) break
            onFrame?.invoke(buf.copyOfRange(consumed + 2, consumed + 2 + len))
            consumed += 2 + len
        }
        if (consumed > 0) {
            val rest = buf.copyOfRange(consumed, buf.size)
            rxBuffer.reset(); rxBuffer.write(rest)
        }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising started")
            onStatus?.invoke("광고 중 · Mac에서 디스플레이로 검색됨")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "advertise failed: $errorCode")
            onStatus?.invoke("광고 실패: $errorCode")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    central = device
                    // ★ "연결됨"이라고 쓰지 않는다. 이 화면에서 '연결'은 화면 미러링을 뜻하는데
                    // 이건 BLE GATT 링크가 붙었다는 뜻일 뿐이다. 예전 문구 탓에 미러링을 끊은
                    // 뒤에도 "Mac 연결됨"이 남아 아직 연결된 것처럼 보였다.
                    onStatus?.invoke("BLE 링크 열림 — 페어링 교환 대기")
                    Log.i(TAG, "central connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasOurs = device.address == central?.address
                    if (wasOurs) {
                        central = null; rxBuffer.reset(); mtu = 23
                    }
                    onStatus?.invoke("BLE 링크 닫힘")
                    // 라운드가 돌던 중이면 기계가 알아야 한다 — 이게 없어서 페어링 기계가
                    // 종료 단계에 영원히 머물렀고, 그 문구가 화면에 잔재로 남았다.
                    if (wasOurs) onCentralDisconnected?.invoke()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            this@BlePeripheral.mtu = mtu
            Log.i(TAG, "mtu=$mtu")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid == PeriBleUuids.RX) ingest(value)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            // CCCD(notify 구독) write — 수락.
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }
}
