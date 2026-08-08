//
//  Components.swift
//  Peri Display
//
//  공용 시각 요소 + 레이아웃 상수. 화면마다 카드·여백을 따로 정하면 톤이 벌어진다.
//
//  수치는 Apple HIG macOS 레이아웃 규약을 따른다:
//   - 창 가장자리 여백 20pt, 그룹 박스 내부 여백 16pt, 그룹 사이 12~24pt.
//   - "center equalization" = 좌우 시각 무게의 균형이지 *좁은 칼럼의 가운데 정렬*이 아니다.
//     그래서 내용 폭 상한은 넉넉히 잡고(maxContentWidth), 카드가 남는 폭을 실제로 채우게 한다.
//
//  배경 대비가 중요하다: 상세 pane은 한 단계 눌린 색(underPageBackground), 카드는 그보다
//  밝은 controlBackground. 둘 다 흰색이면 카드 경계가 사라져 화면이 납작해 보인다.
//

import SwiftUI

enum Metrics {
    /// 창 가장자리 여백 (HIG: 20pt).
    static let margin: CGFloat = 20
    /// 큰 구획 사이 (HIG: 12~24pt).
    static let sectionGap: CGFloat = 16
    /// 나란히 놓인 카드 사이.
    static let cardGap: CGFloat = 12
    /// 카드 내부 여백 (HIG 그룹 박스: 16pt).
    static let cardPadding: CGFloat = 16
    static let cardRadius: CGFloat = 10
    /// 내용 폭 상한. 초광폭 창에서 카드가 우스꽝스럽게 늘어나는 것만 막는 선이고,
    /// 예전처럼 좁게(680) 묶어 좌우를 비우지 않는다.
    static let maxContentWidth: CGFloat = 1100
}

/// 상세 pane 배경 — 카드보다 한 단계 눌린 색.
///
/// `windowBackgroundColor`(밝은 회색) 위에 `controlBackgroundColor`(흰색) 카드 = 시스템 설정과
/// 같은 "그룹 카드" 대비. `underPageBackgroundColor`는 Preview의 *페이지 뒤* 용도라 훨씬 짙어서
/// 카드가 떠 보이는 게 아니라 화면이 무거워진다(실제로 써 보고 되돌림).
struct RecessedBackground: ViewModifier {
    func body(content: Content) -> some View {
        content.background(Color(nsColor: .windowBackgroundColor))
    }
}

extension View {
    func recessedBackground() -> some View { modifier(RecessedBackground()) }
}

/// 카드 컨테이너 — 시스템 GroupBox(각진 회색 상자)가 macOS 26 톤과 안 맞아 직접 정의한다.
struct Card<Content: View>: View {
    var title: String? = nil
    var systemImage: String? = nil
    /// 제목 줄 오른쪽에 놓을 보조 요소(버튼·값 등).
    var accessory: AnyView? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title {
                HStack(spacing: 6) {
                    Group {
                        if let systemImage {
                            Label(title, systemImage: systemImage)
                        } else {
                            Text(title)
                        }
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    if let accessory { accessory }
                }
            }
            content
        }
        .padding(Metrics.cardPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(nsColor: .controlBackgroundColor),
                    in: RoundedRectangle(cornerRadius: Metrics.cardRadius))
        .overlay {
            RoundedRectangle(cornerRadius: Metrics.cardRadius)
                .strokeBorder(.separator.opacity(0.7), lineWidth: 0.5)
        }
    }
}

/// 한눈에 읽는 수치 한 칸. 라벨(작게) → 값(크게) → 부연(아주 작게)의 3단 위계.
/// 값은 rounded 숫자체 — Apple이 배터리·피트니스 같은 계기판에 쓰는 그 서체다.
struct MetricTile: View {
    let label: String
    let systemImage: String
    let value: String
    var unit: String? = nil
    var detail: String? = nil
    var tint: Color = .primary

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(label, systemImage: systemImage)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)

            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(.system(.title, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(tint)
                if let unit {
                    Text(unit)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                }
            }
            .lineLimit(1)
            .minimumScaleFactor(0.6)

            Text(detail ?? " ")            // 항상 한 줄 차지 — 타일 높이가 서로 어긋나지 않게
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Metrics.cardPadding)
        .background(Color(nsColor: .controlBackgroundColor),
                    in: RoundedRectangle(cornerRadius: Metrics.cardRadius))
        .overlay {
            RoundedRectangle(cornerRadius: Metrics.cardRadius)
                .strokeBorder(.separator.opacity(0.7), lineWidth: 0.5)
        }
    }
}

/// 빈 상태·오프라인 화면의 아이콘 — 원형 틴트 배경. 맨 아이콘 하나보다 시선이 멈출 자리가 생겨
/// 아래 텍스트 블록과 무게가 맞는다.
struct IconBadge: View {
    let systemImage: String
    var tint: Color = .secondary
    var size: CGFloat = 64

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.40, weight: .medium))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(tint.opacity(0.12), in: Circle())
    }
}

/// 지연 구간의 색. 막대와 범례가 **같은 상수**를 쓰게 한 곳에 모은다.
///
/// accentColor는 여기 쓰지 않는다: AccentColor 에셋이 비어 있으면 시스템 강조색(기본 파랑)으로
/// 떨어져 `.blue`와 같은 색이 되고, 그러면 인코딩·전송 두 구간이 한 덩어리로 보여 "어느 구간이
/// 병목인가"라는 이 차트의 존재 이유가 사라진다. 사용자가 강조색을 바꾸면 또 달라지므로
/// 범주 색은 강조색과 무관하게 고정한다.
enum LatencyPalette {
    static let encode = Color(red: 0.42, green: 0.36, blue: 0.90)   // 보라 — Mac 인코딩
    static let network = Color(red: 0.00, green: 0.60, blue: 0.90)  // 청록 — 전송
    static let buffer = Color(red: 0.55, green: 0.57, blue: 0.62)   // 회색 — 수신 버퍼
    static let decode = Color(red: 0.95, green: 0.55, blue: 0.10)   // 주황 — 수신 디코딩
}

/// 상태를 색으로 말하는 작은 점. 송출 중이면 은은히 맥동한다.
struct StatusDot: View {
    var color: Color = .green
    var pulsing: Bool = false
    @State private var on = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 7, height: 7)
            .overlay {
                if pulsing {
                    Circle()
                        .stroke(color.opacity(0.55), lineWidth: 3)
                        .scaleEffect(on ? 2.4 : 1)
                        .opacity(on ? 0 : 0.8)
                }
            }
            .onAppear {
                guard pulsing else { return }
                withAnimation(.easeOut(duration: 1.6).repeatForever(autoreverses: false)) {
                    on = true
                }
            }
    }
}
