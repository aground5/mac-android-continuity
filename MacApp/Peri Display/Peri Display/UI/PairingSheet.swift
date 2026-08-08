//
//  PairingSheet.swift
//  Peri Display
//
//  페어링 3단계 시트: 탐색 → PIN 입력 → 완료.
//  기기당 평생 한 번 하는 흐름이라 메인 화면 붙박이가 아니라 시트로 꺼냈다.
//  상태 기계는 PairingCoordinator.Phase를 그대로 쓴다 — UI만 새로 입힌다.
//

import SwiftUI

struct PairingSheet: View {
    @ObservedObject var pairing: PairingCoordinator
    @Environment(\.dismiss) private var dismiss
    /// 사용자가 입력 중인 PIN.
    @State private var pinInput = ""

    var body: some View {
        VStack(spacing: 18) {
            stepIndicator
            content
        }
        .padding(24)
        .frame(width: 420)
        .onAppear {
            // 유휴/실패 상태로 열렸으면 탐색부터. (사이드바 '페어링…'으로 열린 경우는 이미 진행 중)
            switch pairing.phase {
            case .idle, .failed: pairing.startScan()
            default: break
            }
        }
    }

    // MARK: 단계 표시

    private enum Step: Int { case scan = 0, confirm = 1, done = 2 }

    /// 세 단계 표시는 기계의 열 단계를 접은 것이다. `offering`이 `.scan`에 남는 게 중요하다 —
    /// 아직 기기 화면에 PIN이 뜨지 않은 구간이라 입력 단계로 넘어갔다고 말하면 안 된다.
    private var step: Step {
        switch pairing.phase {
        // `.stsConfirming`은 이 시트에 사실상 오지 않는다 — 재연결은 링크 키가 있어야 하고,
        // 링크 키가 있다는 건 이 시트(첫 페어링)를 이미 통과했다는 뜻이다. 그래도 갈래를
        // 비워 두지는 않는다: 빠뜨린 단계가 화면에서 조용히 멈추는 게 옛 버그의 모양이었다.
        case .idle, .scanning, .linking, .offering, .stsConfirming, .failed: return .scan
        case .enteringPin, .proving: return .confirm
        case .identifying, .awaitingCandidates, .punching, .done: return .done
        }
    }

    private var stepIndicator: some View {
        HStack(spacing: 8) {
            stepLabel("탐색", .scan)
            stepDivider
            stepLabel("PIN 입력", .confirm)
            stepDivider
            stepLabel("완료", .done)
        }
        .font(.caption)
    }

    private func stepLabel(_ title: String, _ s: Step) -> some View {
        Text(title)
            .fontWeight(step == s ? .bold : .regular)
            .foregroundStyle(step == s ? Color.accentColor : Color.secondary)
    }

    private var stepDivider: some View {
        Rectangle().fill(.quaternary).frame(width: 16, height: 1)
    }

    // MARK: 단계별 내용

    @ViewBuilder
    private var content: some View {
        switch pairing.phase {
        case .idle, .scanning:
            scanContent
        case .linking, .offering, .proving, .identifying, .stsConfirming:
            progressContent
        case .enteringPin:
            pinContent
        case .awaitingCandidates, .punching, .done:
            pairedContent
        case .failed:
            failedContent
        }
    }

    /// 탐색: BLE로 발견된 기기 목록.
    private var scanContent: some View {
        VStack(spacing: 14) {
            VStack(spacing: 4) {
                Text("주변의 Android 기기").font(.headline)
                Text("Android에서 Peri Display를 열어 두세요.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if pairing.peers.isEmpty {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("찾는 중…").font(.callout).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 20)
            } else {
                VStack(spacing: 6) {
                    ForEach(pairing.peers) { peer in
                        HStack(spacing: 10) {
                            Image(systemName: "iphone.gen3")
                                .foregroundStyle(.secondary)
                            Text(peer.name).fontWeight(.medium)
                            Spacer()
                            Text("\(peer.rssi) dBm")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                            Button("페어링") { pairing.connect(peer) }
                                .controlSize(.small)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
            HStack {
                Spacer()
                Button("닫기") { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
        }
    }

    /// 연결/키합의/PIN 검증 진행 중.
    private var progressContent: some View {
        VStack(spacing: 14) {
            ProgressView().controlSize(.large)
            Text(pairing.status)
                .font(.callout)
                .foregroundStyle(.secondary)
            Button("취소") { pairing.cancel() }
                .keyboardShortcut(.cancelAction)
        }
        .padding(.vertical, 12)
    }

    /// PIN 입력 — 기기 화면의 숫자를 옮겨 적는다.
    ///
    /// 예전엔 여기에 두 가지 화면(비교용 SAS / 입력용 패스코드)이 있었고, 어느 쪽을 쓸지
    /// 양쪽이 합의해야 했다. 그 합의가 어긋나면 두 기기가 서로 다른 프레임을 기다리며 멈췄다.
    ///
    /// 지금은 하나뿐이고, 이 숫자는 **세션 키의 재료**다(PAKE). 비교해서 사람이 승인하는
    /// 값이 아니라 키가 그 위에서 만들어지는 값이라, 이걸 모르는 상대는 어느 쪽과도 채널을
    /// 세우지 못한다 — 옛 SAS를 뚫던 능동 중간자가 원리적으로 불가능해진다.
    private var pinContent: some View {
        VStack(spacing: 14) {
            VStack(spacing: 4) {
                Text("기기 화면의 PIN을 입력하세요").font(.headline)
                Text("Android 화면에 \(PeriBleHandshake.pinDigits)자리 숫자가 표시됩니다.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            TextField(String(repeating: "·", count: PeriBleHandshake.pinDigits), text: $pinInput)
                .textFieldStyle(.plain)
                .font(.system(size: 34, weight: .semibold, design: .monospaced))
                .kerning(8)
                .multilineTextAlignment(.center)
                .frame(width: 200)
                .onSubmit(submitPin)
            HStack(spacing: 10) {
                Button("취소") { pairing.cancel() }
                    .keyboardShortcut(.cancelAction)
                Button("확인", action: submitPin)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(!pinReady)
            }
            Text("기회는 한 번입니다 — 틀리면 처음부터 다시 페어링해야 합니다.")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
    }

    private var pinReady: Bool {
        pinInput.trimmingCharacters(in: .whitespaces).count == PeriBleHandshake.pinDigits
    }

    private func submitPin() {
        guard pinReady else { return }
        let code = pinInput.trimmingCharacters(in: .whitespaces)
        pinInput = ""
        pairing.submitPin(code)
    }

    /// 완료.
    private var pairedContent: some View {
        VStack(spacing: 14) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 40))
                .foregroundStyle(.green)
            VStack(spacing: 4) {
                Text("\(pairing.pairedName ?? "기기") 페어링 완료").font(.headline)
                Text("이제 케이블을 꽂거나 같은 Wi-Fi에 있으면 자동으로 연결됩니다.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
            }
            Button("완료") { dismiss() }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
        }
        .padding(.vertical, 8)
    }

    /// 실패 — 원인과 재시도.
    private var failedContent: some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 34))
                .foregroundStyle(.orange)
            Text(pairing.status)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            HStack(spacing: 10) {
                Button("닫기") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("다시 검색") { pairing.startScan() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(.vertical, 8)
    }
}
