package com.peri.display.transport

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "AoaClient"

/**
 * AOA(USB) Bearer (Android = 액세서리). Mac이 USB 호스트로 액세서리 모드를 시작하면
 * 하나의 신뢰·순서보장 bulk 파이프가 열린다 — LanClient과 동일한 프레임 인터페이스를 제공하되
 * 단일 스트림(제어+영상 모두 길이-프리픽스 프레임)이다. UDP 채널/손실 기전 없음.
 *
 * openAccessory → ParcelFileDescriptor → FileInputStream/OutputStream. 읽기는 기존
 * FrameAssembler 재사용(LanClient의 TCP 경로와 동일한 프레이밍). onFrame은 IO 스레드에서 호출
 * (LanClient과 동일), onConnected/onDisconnected는 Main.
 */
class AoaClient(private val scope: CoroutineScope, private val appContext: Context) : Bearer {

    override var onFrame: ((WireFrame) -> Unit)? = null
    override var onConnected: (() -> Unit)? = null
    override var onDisconnected: ((Throwable?) -> Unit)? = null

    @Volatile private var pfd: ParcelFileDescriptor? = null
    @Volatile private var output: FileOutputStream? = null
    private var readJob: Job? = null

    fun open(accessory: UsbAccessory) {
        val usb = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        val fd = usb?.openAccessory(accessory)
        if (fd == null) {
            // 권한 미부여(ATTACHED intent 전) 또는 다른 프로세스 점유. 조용히 리턴하면
            // 컨트롤러가 CONNECTING에 매달리므로 즉시 끊김으로 알려 폴백/재시도를 태운다.
            Log.e(TAG, "openAccessory null (권한 미부여/점유)")
            scope.launch(Dispatchers.Main) {
                onDisconnected?.invoke(IllegalStateException("openAccessory 실패 (권한/점유)"))
            }
            return
        }
        pfd = fd
        val input = FileInputStream(fd.fileDescriptor)
        output = FileOutputStream(fd.fileDescriptor)
        Log.i(TAG, "accessory opened")
        scope.launch(Dispatchers.Main) { onConnected?.invoke() }
        readJob = scope.launch(Dispatchers.IO) {
            val assembler = FrameAssembler()
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)          // 액세서리 fd 블로킹 read
                    if (n < 0) break
                    if (n > 0) {
                        assembler.append(buf, n)
                        while (true) { val f = assembler.next() ?: break; onFrame?.invoke(f) }
                    }
                }
                close(null)
            } catch (t: Throwable) {
                Log.e(TAG, "read error: ${t.message}")
                close(t)
            }
        }
    }

    /**
     * 송신 전용 단일 스레드.
     *
     * `Dispatchers.IO`를 그냥 쓰면 (1) 프레임 순서가 안 보장되고 (2) **닫기가 송신을 앞지른다.**
     * 실제로 그게 버그였다: USB로 '끊기'를 누르면 `sendBye`가 코루틴을 띄우고 곧바로
     * `disconnect()`가 fd를 닫아, 코루틴이 실행될 땐 `output`이 이미 null이라 Bye가 유실됐다.
     * Mac은 그걸 못 받아 8초 무응답 타임아웃으로 정리했다(LAN에서 먼저 겪은 것과 같은 버그).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val txScope = CoroutineScope(scope.coroutineContext + Dispatchers.IO.limitedParallelism(1))

    /** 프레임화된 바이트 송신 (제어/영상 공용). */
    override fun sendControl(frame: ByteArray) = sendControl(frame, onSent = null)

    override fun sendControl(frame: ByteArray, onSent: (() -> Unit)?) {
        txScope.launch {
            val out = output ?: run { Log.w(TAG, "aoa send 무시 — 파이프 없음"); return@launch }
            try {
                out.write(frame); out.flush()
                onSent?.invoke()
            } catch (t: Throwable) {
                // 신뢰 파이프에서 write 실패 = 죽은 링크. 특히 케이블 분리 후 accessoryList에
                // 남은 유령 항목은 open은 되지만 write가 ENODEV → 즉시 끊어 폴백을 태운다
                // (기존엔 로그만 남겨 8s 핸드셰이크 타임아웃까지 행).
                Log.e(TAG, "write error: ${t.message} → 링크 종료")
                close(t)
            }
        }
    }

    /**
     * 화면 공유 영상 — 제어와 **같은 신뢰 파이프**로 나간다.
     *
     * 조각화도 페이싱도 없다: 이 파이프는 손실도 재정렬도 구조적으로 없고 MTU도 없다.
     * 그래서 호출부는 완성된 프레임 하나를 통째로 넘긴다.
     *
     * 실패해도 링크를 죽이지 않는다 — 제어와 달리 영상 한 장이 못 나간 건 사고가 아니다.
     * 진짜로 파이프가 죽었으면 곧이어 keepalive가 잡아낸다.
     */
    override fun sendVideo(payload: ByteArray) {
        txScope.launch {
            val out = output ?: return@launch
            runCatching { out.write(payload); out.flush() }
        }
    }

    override fun disconnect() {
        readJob?.cancel()
        // ★ 닫기를 **송신 큐 뒤에 줄 세운다.** 앞서 넣은 Bye가 실제로 나간 뒤에 fd가 닫힌다.
        // 여기서 바로 닫으면 사용자가 누른 '끊기'가 상대에겐 그냥 침묵으로 보인다.
        txScope.launch { close(null) }   // fd close → 블로킹 read 해제
    }

    private fun close(error: Throwable?) {
        val f = pfd; pfd = null; output = null
        runCatching { f?.close() }
        scope.launch(Dispatchers.Main) { onDisconnected?.invoke(error) }
    }
}
