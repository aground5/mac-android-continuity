//
//  PeriHome.swift
//  Peri Display
//
//  **Peri OS 홈 화면.** 연결된 기기 하나가 폰 모양 틀로 서고, 그 안에 앱 아이콘이 깔린다.
//  누르면 앱이 뜬다.
//
//  왜 폰 모양인가: 이 앱들은 전부 "저 기기에서 벌어지는 일"이다. 창 어딘가의 토글이 아니라
//  기기의 화면으로 보여야 무엇을 켜고 껐는지가 한눈에 읽힌다.
//
//  기기 화면 자체는 여기 안 나온다 — 화면 공유는 **실물 크기 전용 창**을 따로 연다
//  (`ShareWindow`). 홈 화면은 "무엇이 켜져 있는가"만 말한다.
//
//  이 파일은 앱을 하나도 모른다. `AppHost.allDescriptors`가 주는 것만 그리고, 누를 수 있는지는
//  `blockedReason`에게 묻는다. 새 앱이 늘어도 여기는 0줄 바뀐다(DESIGN.md 원칙 2).
//

import SwiftUI

struct PeriHome: View {
    @ObservedObject var apps: AppHost
    /// 공유 앱은 프레임 도착 여부를 따로 발행한다(1Hz 통계가 아니라 상태 전이).
    @ObservedObject var share: ShareService

    var body: some View {
        VStack(spacing: 10) {
            // ★ 공유 화면은 **여기 없다.** 실물 크기 전용 창(`ShareWindow`)이 그린다.
            //
            // 둘 다 그리려 하면 안 된다: `CALayer`는 superlayer를 하나만 가지므로, 나중에
            // `addSublayer`한 쪽이 앞의 것에서 레이어를 빼앗는다. 한쪽은 반드시 검게 남고
            // 어느 쪽이 이길지는 순서에 달린다 — 조용히 어긋나는 종류의 버그다.
            PhoneFrame(aspect: Self.homeAspect) {
                AppGrid(apps: apps)
            }
            caption
        }
        .frame(maxWidth: .infinity)
    }

    /// 홈 화면의 폰 비율 — 요즘 폰의 대략치(20:9).
    private static let homeAspect = CGSize(width: 9, height: 19.5)

    @ViewBuilder
    private var caption: some View {
        if share.isReceiving {
            Label("별도 창에서 실물 크기로 보는 중", systemImage: "macwindow.on.rectangle")
                .font(.caption).foregroundStyle(.secondary)
        } else if !apps.linked {
            Text("연결되면 앱을 실행할 수 있습니다")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

// MARK: - 폰 틀

/// 내용을 폰 모양으로 감싼다. 비율은 밖에서 온다(홈 화면 vs 실제 공유 프레임).
private struct PhoneFrame<Content: View>: View {
    let aspect: CGSize
    @ViewBuilder let content: Content

    /// 틀의 높이 상한. 창을 키운다고 폰이 한없이 커지면 옆의 카드들이 밀려난다.
    private static var maxHeight: CGFloat { 420 }

    var body: some View {
        content
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .aspectRatio(aspect.width / max(aspect.height, 1), contentMode: .fit)
            .frame(maxHeight: Self.maxHeight)
            .padding(5)
            .background {
                RoundedRectangle(cornerRadius: 27, style: .continuous)
                    .fill(Color.black.opacity(0.85))
                    .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
            }
            .overlay {
                RoundedRectangle(cornerRadius: 27, style: .continuous)
                    .strokeBorder(.white.opacity(0.14), lineWidth: 1)
            }
    }
}

// MARK: - 홈 화면 (아이콘 격자)

private struct AppGrid: View {
    @ObservedObject var apps: AppHost

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.13, green: 0.15, blue: 0.24),
                                    Color(red: 0.07, green: 0.08, blue: 0.13)],
                           startPoint: .top, endPoint: .bottom)

            VStack(spacing: 0) {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3),
                          spacing: 16) {
                    ForEach(apps.allDescriptors, id: \.id) { desc in
                        AppIcon(desc: desc, apps: apps)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.top, 26)
                Spacer(minLength: 12)
            }
        }
    }
}

private struct AppIcon: View {
    let desc: AppDescriptor
    @ObservedObject var apps: AppHost

    private var app: (any PeriApp)? { apps.app(desc.id) }
    private var running: Bool { app?.isRunning ?? false }
    private var blocked: String? { apps.blockedReason(desc.id) }

    var body: some View {
        Button {
            if running {
                Task { await apps.quit(desc.id, reason: "사용자가 종료") }
            } else {
                apps.launch(desc.id)
            }
        } label: {
            VStack(spacing: 5) {
                ZStack {
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .fill(tileFill)
                    Image(systemName: desc.icon)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(.white)
                }
                .frame(width: 50, height: 50)
                // 실행 중 표시는 아이콘 **아래 점** — 진짜 홈 화면의 관용이고, 아이콘 자체를
                // 물들이면 무엇이 켜졌는지가 앱 색과 섞여 안 읽힌다.
                .overlay(alignment: .bottom) {
                    if running {
                        Circle().fill(.white).frame(width: 4, height: 4).offset(y: 8)
                    }
                }

                Text(desc.name)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(0.9))
                    .lineLimit(1)

                if let status = app?.statusText {
                    Text(status)
                        .font(.system(size: 8))
                        .foregroundStyle(.white.opacity(0.55))
                        .lineLimit(1)
                }
            }
            .padding(.bottom, running ? 4 : 0)
            .opacity(blocked == nil || running ? 1 : 0.38)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // 실행 중인 앱은 언제나 끌 수 있어야 한다 — 막힘은 **켜는 쪽**에만 걸린다.
        .disabled(blocked != nil && !running)
        .help(blocked ?? desc.blurb)
    }

    private var tileFill: LinearGradient {
        let base: Color = desc.comingSoon ? .gray : (running ? .accentColor : Color(white: 0.42))
        return LinearGradient(colors: [base.opacity(0.95), base.opacity(0.65)],
                              startPoint: .top, endPoint: .bottom)
    }
}
