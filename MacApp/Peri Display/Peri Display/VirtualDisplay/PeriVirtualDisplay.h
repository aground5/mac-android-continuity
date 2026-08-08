//
//  PeriVirtualDisplay.h
//  Peri Display
//
//  비공개 CoreGraphics CGVirtualDisplay API를 감싼 깔끔한 래퍼.
//  가상 디스플레이를 만들면 데스크톱이 그쪽으로 "확장"되고, 그 디스플레이를
//  ScreenCaptureKit으로 캡처해 Android로 보낸다.
//
//  주의: CGVirtualDisplay는 비공개 API다. 동작하지만 향후 macOS에서 바뀔 수 있다.
//

#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

@interface PeriVirtualDisplay : NSObject

/// 우리 가상 디스플레이의 정체성 상수 — CGDisplayVendorNumber/CGDisplayModelNumber와 비교해
/// 임의의 displayID가 우리 것인지 판별할 때 쓴다 (배치 미니맵 등).
@property (class, nonatomic, readonly) uint32_t vendorID;
@property (class, nonatomic, readonly) uint32_t productID;

/// 생성된 가상 디스플레이의 displayID. 생성 전/실패 시 0.
/// ⚠️ 등록이 끝나기 전엔 이 값이 *다른(또는 방금 파괴된)* 디스플레이의 ID일 수 있다 —
/// CGDirectDisplayID는 재사용되기 때문. 캡처 대상으로 쓰려면 confirmedDisplayID를 써라.
@property (nonatomic, readonly) CGDirectDisplayID displayID;

/// vendor/product/serial이 *이 객체의 정체성과 일치*할 때만 displayID를 반환(아니면 0).
/// 캡처는 반드시 이 값으로 대상을 특정해야 남의 화면을 잡는 사고를 막는다.
- (CGDirectDisplayID)confirmedDisplayID;

/// 현재 모드의 backing 픽셀 크기 (DisplayManager가 재사용 시 resize 필요 여부 판단).
@property (nonatomic, readonly) uint32_t pixelWidth;
@property (nonatomic, readonly) uint32_t pixelHeight;

/// HDR(확장 휘도) 디스플레이로 선언할지. 모드 목록을 만들 때 읽으므로 **create/resize 전에** 설정해야 한다.
/// YES면 각 모드를 transferFunction=1로 선언한다(0=SDR).
/// ⚠️ 선언한다고 macOS가 반드시 헤드룸을 주는 건 아니다 — 반드시 potentialHeadroom으로 확인할 것.
@property (nonatomic) BOOL hdr;

/// EDR 헤드룸 계측 (SkyLight 비공개 API). 1.0 = SDR, >1.0 = HDR 여유분. 조회 불가 시 0.
/// hdr=YES로 만든 뒤 이 값이 1.0이면 **선언만 먹고 실제로는 SDR**이라는 뜻이다.
- (float)potentialHeadroom;
- (float)currentHeadroom;

/// macOS가 이 디스플레이에 HDR 모드 토글을 제공하는지 / 그 토글이 켜져 있는지.
- (BOOL)supportsHDRMode;
- (BOOL)hdrModeEnabled;

/// 위 네 값을 한 줄로 (로그용).
- (NSString *)hdrDiagnostics;

/// "지금 시스템 어딘가에 HDR 콘텐츠가 떠 있는가"를 재는 값 —
/// **물리** 디스플레이들의 *현재* EDR 헤드룸 최대치. 1.0이면 HDR 콘텐츠 없음.
///
/// 실측으로 확인한 성질:
///  · current 헤드룸은 밝기가 아니라 **수요**로 움직인다. HDR 콘텐츠가 없으면 potential이
///    16이어도 current는 1.0에 머문다(HDR 영상 재생 시 1.0 → 9.4로 상승).
///  · **가상 디스플레이는 제외한다.** 백라이트 전력 제약이 없어 macOS가 헤드룸을 항상 최대로
///    열어두므로(실측: current == potential == 5.0 고정) 수요 신호가 되지 못한다.
///
/// ⚠️ **디스플레이를 구분하지 못한다.** 내장 화면에서만 HDR을 재생해도 올라간다.
/// 즉 거짓 양성이 있고, 그 대가는 SDR 화면을 10비트로 보내는 대역폭 낭비다(화질 손상은 없다).
/// 반대로 거짓 음성은 없다 — 1.0이면 어디에도 HDR이 없다는 뜻이라 안전하게 SDR로 갈 수 있다.
///
/// 조회 불가 시 0.
+ (float)systemHDRContentHeadroom;

/// serialNum: 이 가상 디스플레이의 정체성(serial). macOS는 vendor/product/serial 조합으로
/// 디스플레이를 식별·배치저장하므로, 기기마다 고유 serial을 주면 (a) 동시에 여러 가상
/// 디스플레이가 충돌 없이 등록되고 (b) 기기별 배치/스케일이 재연결에도 유지된다. 0이면 거부될 수 있음.
/// maxPixelDim: 가상 디스플레이 framebuffer 픽셀 상한(긴 변). 전체 디스플레이 긴 변을 주면
/// 분할/팝업/회전 등 어떤 창 크기로도 resize-in-place 가능. 0이면 max(width,height)로 폴백.
- (instancetype)initWithName:(NSString *)name
                   serialNum:(uint32_t)serialNum
                       width:(uint32_t)width
                      height:(uint32_t)height
                         ppi:(double)ppi
                 refreshRate:(double)refreshRate
                       hiDPI:(BOOL)hiDPI
                 maxPixelDim:(uint32_t)maxPixelDim;

/// 가상 디스플레이 생성. 성공 시 YES, displayID 설정됨.
- (BOOL)create;

/// 디스플레이 인스턴스를 유지한 채 해상도/DPI만 교체 (resize-in-place).
/// 회전·분할/팝업 등으로 송출 영역이 바뀔 때 destroy 없이 모드만 갈아끼운다 → displayID 동일.
/// 호출 후 activatePreferredMode로 2x 재활성화 필요. 성공 시 YES.
- (BOOL)resizeToWidth:(uint32_t)width
               height:(uint32_t)height
                  ppi:(double)ppi
          refreshRate:(double)refreshRate
                hiDPI:(BOOL)hiDPI;

/// 사용 가능한 모드 중 우리 backing 픽셀폭과 일치하는 최고 백킹스케일(HiDPI) 모드를 활성화.
/// 디스플레이가 등록된 후(캡처 시작 뒤) 호출해야 함. 2x 모드를 켰으면 YES.
- (BOOL)activatePreferredMode;

/// 미러링 상태면 확장으로 강제 전환(세션 한정, 저장배치 영구변경 안 함). 이미 확장이면 즉시 YES.
/// 디스플레이가 온라인(등록 완료)일 때만 의미 있음 — 캡처 시작 후 호출.
- (BOOL)forceExtended;

/// 디스플레이가 온라인(데스크톱 배치에 포함)인지. presentation/"윈도우 또는 앱" 모드로 빠지면 NO.
- (BOOL)isOnline;

/// 다른 디스플레이를 미러링 중이면 YES.
- (BOOL)isMirrored;

/// 가상 디스플레이 제거 (데스크톱 배치 원복).
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
