package com.peri.display.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peri.display.vm.ConnDiagnostics
import com.peri.display.vm.LinkDiagnostics
import com.peri.display.vm.PairDiagnostics

/**
 * 진단 화면 — Mac 인스펙터(⌥⌘I)와 같은 구성.
 *
 * 위에서 아래로 추상이 낮아진다:
 *
 *  1. **연결 상태 기계** — 화면의 모든 문구가 여기서 파생된다.
 *  2. **페어링 상태 기계** — 연결과 **독립된 축**이다. 둘을 나란히 두는 게 이 화면의 요점:
 *     예전엔 이 둘을 한 문구로 섞어 보여줘서 미러링을 끊어도 "연결됨"이 남았다.
 *  3. **링크 원시 수치** — 기계가 모르는 것들(디코더·망·피드백).
 *
 * 1이 맨 위인 이유는 Mac과 같다 — 증상을 볼 때 제일 먼저 알아야 할 게 "기계는 지금 뭐라고
 * 믿고 있나"이기 때문이다.
 */
@Composable
fun DiagnosticsSheet(
    conn: ConnDiagnostics,
    pair: PairDiagnostics,
    link: LinkDiagnostics,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Section("연결 상태 기계")
        Row_("요약(파생)", conn.summary)
        Row_("상태", conn.conn.name)
        Row_("의도", conn.intent)
        Row_("세션", conn.sess)
        Row_("경로", conn.path)
        Row_("재협상", conn.nego)
        conn.queued?.let { Row_("예약된 재협상", it) }
        if (conn.switch != "None") Row_("전환", conn.switch)
        Row_("재시도", "${conn.retries}회")
        Row_("전송", conn.via)
        conn.mismatch?.let { Warn(it) }

        Section("신원 · 라운드")
        // Mac 로그의 `dev[…]`와 같은 값 — 두 로그를 grep 하나로 붙이는 키다.
        Row_("기기 지문", conn.fingerprint)
        Row_("라운드", if (conn.negoRound > 0) "r:${conn.negoRound}" else "—")

        Section("페어링 상태 기계")
        Row_("요약(파생)", pair.summary)
        Row_("단계", pair.phase.toString())
        // v2는 방식 축이 없다 — 모든 라운드가 PIN을 거치므로 협상할 게 없다.
        Row_("PIN 표시 중", yesNo(pair.showsPin))
        Row_("단계 마감", pair.deadlineMs?.let { "${it / 1000}s" } ?: "없음(대기 가능)")

        Section("링크 · 망")
        Row_("망 종류", link.transport.label)
        if (link.demotedAddresses > 0) {
            Row_("후순위 주소", "${link.demotedAddresses}개 (셀룰러)")
        }
        Row_("왕복(RTT)", ms(link.rttMs))
        Row_(
            "마지막 Pong",
            if (link.sincePongMs < 0) "—" else "${link.sincePongMs}ms 전",
            // keepalive는 3초 무응답이면 링크를 끊는다. 그 문턱이 다가오는 걸 보이게.
            warn = link.sincePongMs > 2_000,
        )
        Row_("손실률", "${link.lossPct}% (${link.fbReceived}/${link.fbSpan})",
             warn = link.lossPct >= 5)

        Section("링크 · 디코더")
        // 기계가 STREAMING이라 해도 디코더가 없으면 화면은 안 나온다 — 그 어긋남이 보이게.
        Row_("디코더", if (link.decoderUp) "동작 중" else "없음",
             warn = !link.decoderUp && conn.conn == com.peri.display.vm.ConnState.STREAMING)
        if (!link.decoderUp && conn.conn == com.peri.display.vm.ConnState.STREAMING) {
            Warn("기계는 송출 중이라는데 디코더가 없다 — 화면이 나오지 않는다.")
        }
        Row_("재생 버퍼", "${link.playoutBufferMs} ms")
        Row_("도착 즉시 렌더", yesNo(link.renderOnArrival))
        Row_("지터버퍼 큐 지연", ms(link.queueDelayMs))

        Section("링크 · 지연 구간")
        if (link.hasLatency) {
            Row_("네트워크+재조립", ms(link.pipeNetMs))
            Row_("수신 버퍼", ms(link.pipeJbufMs))
            Row_("디코드", ms(link.pipeDecMs))
            Row_("합계", ms(link.totalMs))
        } else {
            Text(
                "아직 보고 없음 — 클럭 동기 전이거나 영상이 흐르지 않는다.",
                style = MaterialTheme.typography.bodySmall, color = Color.White,
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Column(modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
    }
}

@Composable
private fun Row_(key: String, value: String, warn: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (warn) Color(0xFFFFB74D) else Color.White,
        )
    }
}

@Composable
private fun Warn(text: String) {
    Text(
        "⚠ $text",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFFFFB74D),
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun yesNo(b: Boolean) = if (b) "예" else "아니오"
private fun ms(v: Int) = if (v > 0) "$v ms" else "—"
