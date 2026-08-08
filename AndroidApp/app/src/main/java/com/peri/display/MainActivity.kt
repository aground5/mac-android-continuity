package com.peri.display

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.util.Log
import com.peri.display.ble.PairingCoordinator
import com.peri.display.ble.PeriPageResponder
import com.peri.display.ble.PeriPageScanner
import com.peri.display.ui.DiagnosticsSheet
import com.peri.display.ui.theme.PeriDisplayTheme
import com.peri.display.notify.NotifyAccess
import com.peri.display.notify.ShareNotifyPrompt
import com.peri.display.share.ScreenShare
import com.peri.display.vm.ConnState
import com.peri.display.vm.MirrorController
import kotlinx.coroutines.delay

private const val ACTION_USB_PERMISSION = "com.peri.display.USB_PERMISSION"
/** 권한 거부/무시 시 재요청 간격 — 대화상자 도배 방지. */
private const val PERMISSION_RETRY_MS = 30_000L

/** 액세서리가 보이는 동안의 확인 주기. 케이블이 꽂혀 있으니 충전 중이고, 반응이 빨라야 한다. */
private const val ACCESSORY_POLL_MS = 3_000L
/** 케이블이 없을 때. 여긴 안전망일 뿐이라 느려도 되고, 이 상태가 대부분의 시간이다. */
private const val IDLE_ACCESSORY_POLL_MS = 15_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DisplayProbe.requestMaxRefresh(this)   // 패널을 최대 주사율로 → 스트리밍 고fps가 실제 표시
        val displaySpec = DisplayProbe.probe(this)
        setContent {
            PeriDisplayTheme {
                MirrorApp(displaySpec)
            }
        }
    }

    /**
     * 화면 캡처 동의 대화상자는 **앞에 있는 Activity만** 띄울 수 있다. 그래서 이 플래그가
     * [ScreenShare]가 "지금 바로 물어봐도 되는가"를 판단하는 유일한 근거다.
     * 뒤에 있으면 알림으로 사람을 부르고, 그 알림을 눌러 들어오면 여기서 이어 붙는다.
     */
    override fun onResume() {
        super.onResume()
        ScreenShare.activityResumed = true
        ScreenShare.resumeConsentIfPending()
    }

    override fun onPause() {
        super.onPause()
        ScreenShare.activityResumed = false
    }

    companion object {
        /** 동의 요청 알림이 이 Activity를 부를 때 쓰는 액션. */
        const val ACTION_SHARE_CONSENT = "com.peri.display.SHARE_CONSENT"
    }
}

@Composable
private fun MirrorApp(initialSpec: DisplaySpec) {
    val context = LocalContext.current
    val controller = remember { MirrorController(context) }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    val configuration = LocalConfiguration.current
    val view = LocalView.current

    // 전체 디스플레이 기준값(밀도/주사율/max framebuffer) — 창 크기와 무관. 구성 변경 시 갱신.
    val base = remember(configuration) {
        (context as? Activity)?.let { DisplayProbe.probe(it) } ?: initialSpec
    }
    // SurfaceView 실제 크기 = 앱이 차지하는 영역 (전체화면/회전/분할/팝업/프리폼 모두 반영).
    var surfaceSize by remember { mutableStateOf<IntSize?>(null) }

    // 현재 창 기준 spec: 해상도=surface, 밀도/max=base. surface 미정이면 base(전체) 폴백.
    val currentSpec = surfaceSize?.takeIf { it.width > 0 && it.height > 0 }?.let {
        base.copy(widthPx = it.width, heightPx = it.height, isLandscape = it.width >= it.height)
    } ?: base

    // surface 크기 변화 → 디바운스(250ms) 후 재협상. 연속 프리폼 리사이즈 폭주 방지.
    LaunchedEffect(surfaceSize) {
        val sz = surfaceSize ?: return@LaunchedEffect
        delay(250)
        val spec = base.copy(
            widthPx = sz.width, heightPx = sz.height, isLandscape = sz.width >= sz.height,
        )
        android.util.Log.i("MirrorApp", "surface ${sz.width}x${sz.height} → onDisplayChanged")
        controller.onDisplayChanged(spec)
    }

    // 케이블 (재)삽입 → singleTask라 ATTACHED intent가 onNewIntent로 전달됨.
    // 진짜 열거 신호이므로 AOA 전환 쿨다운을 즉시 해제 (분리 후 남는 유령 항목과 구분).
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val listener = androidx.core.util.Consumer<android.content.Intent> { intent ->
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
                controller.onAccessoryAttached()
            }
        }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

    // AOA(USB) 자동 연결/전환 폴링 (USB 우선 정책): 액세서리가 붙어 있으면
    //  - 유휴/에러 → AOA 연결 (Mac 앱 재시작·타임아웃·끊김 모두 재플러그 없이 복구)
    //  - LAN 스트리밍 중 → AOA로 자동 전환 (판단은 controller.onAccessoryTick)
    // '끊기'(userClosed)면 재시도 중단 — 재플러그나 앱 재실행으로 다시 활성화.
    val latestSpec by rememberUpdatedState(currentSpec)

    // 액세서리 권한 부여 브로드캐스트 → 즉시 연결 시도(다음 폴링 틱까지 기다리지 않음).
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                @Suppress("DEPRECATION")
                val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
                Log.i("AOA", "accessory 권한 결과: granted=$granted")
                if (granted && acc != null) controller.onAccessoryTick(acc, latestSpec)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(Unit) {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        var lastPermissionReqAt = 0L
        while (true) {
            usb?.accessoryList?.firstOrNull()?.let { accessory ->
                if (usb.hasPermission(accessory)) {
                    controller.onAccessoryTick(accessory, latestSpec)
                } else {
                    // 권한이 없으면 예전엔 ATTACHED intent만 기다리다 영원히 멈췄다(앱이 이미 실행 중이면
                    // 그 intent가 안 올 수 있음) → ClientHello를 못 보내 Mac은 '세션 대기'로 무한 대기.
                    // 이제 직접 요청해서 부트스트랩을 우리가 진행시킨다(도배 방지 위해 주기 제한).
                    val now = System.currentTimeMillis()
                    if (now - lastPermissionReqAt > PERMISSION_RETRY_MS) {
                        lastPermissionReqAt = now
                        Log.i("AOA", "accessory 권한 요청")
                        val pi = PendingIntent.getBroadcast(
                            context, 0,
                            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                        runCatching { usb.requestPermission(accessory, pi) }
                            .onFailure { Log.e("AOA", "권한 요청 실패: ${it.message}") }
                    }
                }
            }
            // 케이블이 없으면 느리게 본다.
            //
            // 이 순환은 `USB_ACCESSORY_ATTACHED`가 안 오는 경우를 위한 **안전망**이다. 그런데
            // 액세서리가 하나도 없는 상태 — 세션이 Wi-Fi로만 도는 평소 — 에서도 3초마다
            // USB 서비스를 두드리고 있었다. 아무 일도 없는 시간이 대부분이라 그게 그대로
            // 배터리 비용이 된다.
            //
            // 반대로 액세서리가 **보이는** 동안은 빠르게 둔다: 권한을 기다리거나 붙이는
            // 중이라 반응이 빨라야 하고, 그때는 케이블이 꽂혀 있으니 충전 중이다.
            delay(if (usb?.accessoryList.isNullOrEmpty()) IDLE_ACCESSORY_POLL_MS
                  else ACCESSORY_POLL_MS)
        }
    }

    // 화면 공유 동의 — Mac이 요청하면 여기로 온다.
    //
    // 결과를 [ScreenShare]에 그대로 넘기고 끝이다. projection을 여기서 만들지 않는 건
    // Android 14+가 mediaProjection 포그라운드 서비스를 **먼저** 요구하기 때문이다.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> ScreenShare.onConsentResult(result.resultCode, result.data) }

    DisposableEffect(Unit) {
        ScreenShare.attach(context)
        ScreenShare.onNeedConsentUi = {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as? android.media.projection.MediaProjectionManager
            if (mgr == null) {
                ScreenShare.onConsentResult(Activity.RESULT_CANCELED, null)
            } else {
                runCatching { consentLauncher.launch(mgr.createScreenCaptureIntent()) }
                    .onFailure { ScreenShare.onConsentResult(Activity.RESULT_CANCELED, null) }
            }
        }
        onDispose { ScreenShare.onNeedConsentUi = null }
    }

    // 알림 권한(13+). 앱이 뒤에 있을 때 동의를 물어보는 **유일한 통로**라서, 거부되면
    // 공유는 앱을 직접 열어야만 시작된다. 막지는 않는다 — 확장은 그와 무관하게 잘 돈다.
    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 결과를 붙들 필요는 없다 — 없으면 알림 경로만 못 쓴다 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 세션이 붙어 있는가. **앱이 떠 있는가와는 다른 축이다.** */
    val connected = controller.state == ConnState.STREAMING
    /** 확장 앱이 떠서 실제로 Mac 화면이 오고 있는가 — 전체화면으로 갈 조건. */
    val streaming = connected && controller.extendRunning

    // BLE 페어링/시그널링 — **앱 수명 내내** 산다.
    //
    // 예전엔 이 코디네이터가 `SetupOverlay`(비스트리밍 UI) 안에서 remember됐다. 그래서 세션이
    // 붙는 순간 `onDispose { pairing.stop() }`가 돌아 **광고가 통째로 꺼졌고**, 정작 핸드오버
    // 재협상 신호가 필요한 시간 내내 죽어 있었다(실기기 로그: 망 전환에 `광고 재협상 비트 → true`는
    // 찍혔지만 `advertiser`가 null이라 재광고가 안 나감). 스트리밍이 끝나면 코디네이터가 새로
    // 만들어져 플래그도 잃고, 되살아난 GATT 서버가 그 순간 붙는 central과 경합하기까지 했다.
    val pairing = remember { PairingCoordinator(context) }
    var bleStatus by remember { mutableStateOf("준비 중…") }
    var pin by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) {
        pairing.onStatus = { bleStatus = it }
        pairing.onPin = { pin = it }
        // Mac이 호출하면 광고를 켠다. **살아 있는 이 인스턴스**가 처리해야 한다 — 리시버가
        // 코디네이터를 새로 만들면 GATT 서버를 두고 둘이 경합한다(예전에 실제로 났던 문제).
        PeriPageResponder.onPage = { resp -> pairing.respondToPage(resp) }
        // 프로세스가 죽어 있는 사이에 온 호출 — 리시버가 액티비티를 띄우고 응답을 맡겨 뒀다.
        // 이게 실제로 가장 흔한 경로다(앱이 안 떠 있을 때 부르는 것이 호출의 존재 이유다).
        PeriPageResponder.takePending()?.let { pairing.respondToPage(it) }
        // ICE — Mac이 준 토큰을 그대로 실어 내 후보를 만들고, Mac 후보가 오면 펀칭을 시작한다.
        // STUN은 미디어에 쓸 그 소켓에서 나가야 하므로 수집은 controller(전송 소유자)가 한다.
        pairing.candidateProvider = { remote -> controller.gatherCandidates(remote) }
        pairing.onRemoteCandidates = { bundle -> controller.beginPunch(bundle, latestSpec) }
        // 핸드오버(LTE↔Wi-Fi)·USB 분리: 광고에 재협상 비트를 세워 Mac을 부르고,
        // 라운드가 끝나면(성공·실패 무관) 내린다. IP에 독립적인 유일한 신호 경로가 BLE다.
        controller.onIceRestartNeeded = { pairing.requestIceRestart() }
        controller.onRestartFinished = { pairing.clearIceRestart() }
        onDispose {
            // 호출 대기(오프로드 스캔)는 **끄지 않는다.** 앱이 사라져도 칩셋이 계속 감시하는
            // 게 이 방식의 요점이다. 지우는 건 이 인스턴스로 향하던 화살표뿐이고, 그 뒤의
            // 호출은 [PeriPageResponder]가 액티비티를 띄우는 쪽으로 흐른다.
            PeriPageResponder.onPage = null
            pairing.stop()
        }
    }
    val blePerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // 광고 권한이 있으면 일단 시작한다. 스캔은 별개로 거절될 수 있는데, 그래도 앱이 떠
        // 있는 동안의 페어링은 되어야 한다 — 호출 대기만 못 걸릴 뿐이다.
        if (result[Manifest.permission.BLUETOOTH_ADVERTISE] != false) pairing.startAdvertising()
        else bleStatus = "BLE 권한 거부됨 — 설정에서 허용 필요"
        PeriPageScanner.start(context)
    }
    // 앱 열면 자동 광고 시작(권한은 첫 실행 1회 요청). 스트리밍 중에도 계속 광고한다.
    LaunchedEffect(Unit) {
        val needPerms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val granted = !needPerms || (
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            )
        // ★ 스캔 권한은 **API로 갈린다** — 31+는 BLUETOOTH_SCAN, 29~30은 위치 권한이다.
        // 목록은 [PeriPageScanner.requiredPermissions]가 정하고 여기선 합치기만 한다.
        val wants = buildList {
            if (needPerms) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!PeriPageScanner.hasPermission(context)) {
                addAll(PeriPageScanner.requiredPermissions())
            }
        }
        if (granted && wants.isEmpty()) {
            pairing.startAdvertising()
            PeriPageScanner.start(context)
        } else {
            blePerms.launch(wants.toTypedArray())
        }
    }

    // 진짜 전체화면(멀티윈도우 아님)일 때만 시스템바 숨김. 분할/팝업에선 숨길 수 없으므로 스킵.
    LaunchedEffect(streaming, configuration) {
        val activity = view.context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, view)
        val multiWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode
        if (streaming && !multiWindow) {
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        if (streaming) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    var showControls by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 전체화면 영상 Surface. 가상 디스플레이가 기기 종횡비와 동일하므로
        // fillMaxSize로 레터박스 없이 꽉 찬다.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) =
                            controller.setSurface(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                            // 앱이 차지하는 실제 렌더 영역 → 협상 해상도의 출처
                            surfaceSize = IntSize(w, ht)
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) =
                            controller.setSurface(null)
                    })
                }
            },
        )

        if (!connected) {
            SetupOverlay(controller, currentSpec, bleStatus, pin,
                         showDiagnostics = showDiagnostics,
                         onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                         pairing = pairing)
        } else if (!streaming) {
            // 붙었지만 확장은 안 떴다. **이게 정상 상태다** — 세션이 붙었다고 화면이
            // 오는 게 아니라 Mac에서 아이콘을 눌러야 온다. 그 사실을 말해 주지 않으면
            // 검은 화면만 남아 "연결이 안 됐다"로 읽힌다.
            IdleOverlay(
                sharing = controller.shareRunning,
                // 알림 앱을 켰는데 권한이 없으면 **한 건도 안 온다.** 그 사실을 여기서만
                // 말할 수 있다 — Mac은 "켰다"까지만 알고 왜 조용한지는 모른다.
                needsNotifyAccess = controller.notifyRunning && !NotifyAccess.isGranted(context),
                onGrantNotifyAccess = { context.startActivity(NotifyAccess.settingsIntent()) },
                // 알림 접근이 켜진 **뒤에만** 묻는다. 한 번에 두 권한을 들이밀면 뭐가 뭔지
                // 모른다 — 이건 없어도 알림 수신·답장·지우기가 전부 되는 부가 기능이다.
                needsOverlay = controller.notifyRunning && NotifyAccess.isGranted(context) &&
                               !NotifyAccess.canStartFromBackground(context),
                onGrantOverlay = { context.startActivity(NotifyAccess.overlaySettingsIntent(context)) },
                // 잠긴 화면을 켜서 잠금 해제를 청하려면 필요하다. 없으면 알림은 뜨되
                // 화면이 안 켜져서, 폰을 집어 봐야 그제서야 안다.
                needsWakeScreen = controller.notifyRunning && NotifyAccess.isGranted(context) &&
                                  !ShareNotifyPrompt.canWakeScreen(context),
                onGrantWakeScreen = {
                    context.startActivity(ShareNotifyPrompt.wakeScreenSettingsIntent(context))
                },
                onDisconnect = { controller.disconnect() },
                onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
            )
            if (showDiagnostics) {
                DiagnosticsScrim(
                    conn = controller.connDiagnostics(),
                    pair = pairing.diagnostics(),
                    link = controller.linkDiagnostics(),
                    onDismiss = { showDiagnostics = false },
                )
            }
        } else {
            // 스트리밍 중: 화면을 탭하면 끊기 버튼을 잠깐 노출.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showControls = !showControls },
            )
            if (showControls) {
                Button(
                    onClick = { controller.disconnect(); showControls = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .systemBarsPadding()
                        .padding(16.dp),
                ) { Text("끊기") }
                Button(
                    onClick = { showDiagnostics = !showDiagnostics },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .systemBarsPadding()
                        .padding(16.dp),
                ) { Text("진단") }
            }
            // 스트리밍 중이 **진단이 가장 필요한 때**다 — 멈춤·화질 문제를 그 순간 봐야 한다.
            if (showDiagnostics) {
                DiagnosticsScrim(
                    conn = controller.connDiagnostics(),
                    pair = pairing.diagnostics(),
                    link = controller.linkDiagnostics(),
                    onDismiss = { showDiagnostics = false },
                )
            }
        }
    }
}

/** 진단 시트를 덮개로 띄운다. 스트리밍 중이든 대기 중이든 같은 자리에 같은 모양으로. */
@Composable
private fun DiagnosticsScrim(
    conn: com.peri.display.vm.ConnDiagnostics,
    pair: com.peri.display.vm.PairDiagnostics,
    link: com.peri.display.vm.LinkDiagnostics,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .systemBarsPadding()
            .clickable { onDismiss() },
    ) {
        DiagnosticsSheet(conn = conn, pair = pair, link = link)
    }
}

/**
 * 붙어는 있는데 확장 앱은 안 뜬 상태.
 *
 * Peri OS의 홈 화면은 **Mac에 있다.** 이 기기에는 누를 아이콘이 없고, 여기 있는 건
 * "지금 무엇을 하는 중인가"뿐이다 — 그래서 화면이 이렇게 조용하다.
 */
@Composable
private fun BoxScope.IdleOverlay(
    sharing: Boolean,
    needsNotifyAccess: Boolean,
    onGrantNotifyAccess: () -> Unit,
    needsOverlay: Boolean,
    onGrantOverlay: () -> Unit,
    needsWakeScreen: Boolean,
    onGrantWakeScreen: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleDiagnostics: () -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (sharing) "화면을 공유하는 중" else "Mac에 연결됨",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Text(
            if (sharing) "이 화면이 Mac 창에 표시되고 있습니다"
            else "Mac에서 앱을 실행하면 여기에 나타납니다",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.65f),
        )
        if (needsNotifyAccess) {
            Text(
                "알림을 Mac으로 보내려면 이 앱에 알림 접근을 허용해야 합니다",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCC66),
            )
            Button(onClick = onGrantNotifyAccess) { Text("알림 접근 허용") }
        }
        if (needsOverlay) {
            // 없어도 알림은 다 온다 — 막히는 건 "Mac에서 눌러 폰에서 열기" 하나뿐이라
            // 그렇게 적는다. 안 그러면 왜 켜야 하는지 알 수 없는 무서운 권한이 된다.
            Text(
                "Mac에서 알림을 눌러 이 폰의 앱을 열려면 '다른 앱 위에 표시'가 필요합니다 "
                    + "(알림 받기·답장·지우기는 이것 없이도 됩니다)",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
            OutlinedButton(onClick = onGrantOverlay) { Text("'다른 앱 위에 표시' 허용") }
        }
        if (needsWakeScreen) {
            // 이것도 부가 기능이다 — 없으면 알림은 그대로 뜨고 화면만 안 켜진다.
            Text(
                "폰이 잠겨 있을 때 화면을 켜서 알려 주려면 '전체 화면 알림'이 필요합니다 "
                    + "(없어도 알림은 뜹니다)",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
            OutlinedButton(onClick = onGrantWakeScreen) { Text("'전체 화면 알림' 허용") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onToggleDiagnostics) { Text("진단") }
            OutlinedButton(onClick = onDisconnect) { Text("끊기") }
        }
    }
}

/// 비스트리밍 상태 화면 — **표시 전용**. BLE 코디네이터의 수명은 `MirrorApp`이 쥔다
/// (여기서 remember하면 세션이 붙는 순간 광고가 꺼진다 — 그쪽 주석 참조).
@Composable
private fun BoxScope.SetupOverlay(
    controller: MirrorController,
    displaySpec: DisplaySpec,
    bleStatus: String,
    pin: String?,
    showDiagnostics: Boolean,
    onToggleDiagnostics: () -> Unit,
    pairing: PairingCoordinator,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Peri Display", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(
            "이 기기: ${displaySpec.widthPx}×${displaySpec.heightPx} · " +
                "${displaySpec.densityDpi}dpi · ${displaySpec.refreshRate.toInt()}Hz",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
        )
        Text(
            "Mac에서 이 기기를 선택하면 자동으로 연결됩니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        // ★ **연결 상태가 먼저다.** 예전엔 이 화면에 페어링/BLE 문구만 있어서, 사용자가
        // 미러링을 끊어도 "Mac 연결됨"(= BLE 링크 얘기)이 남아 아직 연결된 것처럼 보였다.
        // 두 축은 독립이다 — BLE는 시그널링용이고 미러링은 LAN/USB로 흐른다.
        Text(
            controller.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Text("BLE · $bleStatus", style = MaterialTheme.typography.bodySmall, color = Color.White)
        OutlinedButton(onClick = onToggleDiagnostics) {
            Text(if (showDiagnostics) "진단 닫기" else "진단")
        }
        if (showDiagnostics) {
            DiagnosticsSheet(
                conn = controller.connDiagnostics(),
                pair = pairing.diagnostics(),
                link = controller.linkDiagnostics(),
            )
        }
        // ★ PIN — 이 화면을 **볼 수 있는 사람만** 통과한다.
        //
        // 예전엔 여기에 두 가지(비교용 SAS / 입력용 OTP)가 있었고 어느 쪽을 쓸지 양쪽이
        // 합의해야 했다. 그 합의가 어긋나면 두 기기가 서로 다른 프레임을 기다리며 멈췄다.
        // 지금은 하나뿐이라 어긋날 게 없다.
        //
        // 그리고 이 값은 **세션 키의 재료**다(PAKE). 예전 SAS처럼 "보여주고 사람이 확인"에
        // 그치지 않으므로, 이 숫자를 모르는 상대는 어느 쪽과도 채널을 세우지 못한다.
        pin?.let { code ->
            Text(
                "이 PIN을 Mac에 입력하세요 (기회 1회)",
                style = MaterialTheme.typography.bodySmall, color = Color.White,
            )
            Text(code, style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(
                "틀리면 페어링이 취소되고 처음부터 다시 해야 합니다.",
                style = MaterialTheme.typography.bodySmall, color = Color.White,
            )
        }
    }
}
