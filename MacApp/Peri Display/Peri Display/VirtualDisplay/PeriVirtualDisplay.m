//
//  PeriVirtualDisplay.m
//  Peri Display
//

#import "PeriVirtualDisplay.h"
#import <math.h>
#import <dlfcn.h>

// 우리 가상 디스플레이의 고정 정체성. serial만 기기별로 다르다(DisplaySerialStore).
static const uint32_t kPeriVendorID  = 0x3456;
static const uint32_t kPeriProductID = 0x1234;

// macOS가 가상 디스플레이를 정상 수용하려면 물리 '가로폭'이 최소 ~166mm 이상이어야 한다
// (실측 2026-06-28: 폭 166mm 수용/165mm 거부, 밀도·해상도 무관, 미만이면 825mm·72dpi로 폴백→TV 분류
//  →"윈도우 또는 앱" UI). 마진 둬서 200mm를 최소 가로폭으로 보장한다.
static const double kPeriMinPhysicalWidthMM = 200.0;

// =============================================================================
// 비공개 CoreGraphics CGVirtualDisplay API 선언 (역공학, Chromium 레퍼런스 기준)
// 클래스는 CoreGraphics에 존재하며 ObjC 런타임으로 인스턴스화된다.
// =============================================================================

@interface CGVirtualDisplayDescriptor : NSObject
@property(nonatomic) unsigned int vendorID;
@property(nonatomic) unsigned int productID;
@property(nonatomic) unsigned int serialNum;
@property(strong, nonatomic) NSString *name;
@property(nonatomic) CGSize sizeInMillimeters;
@property(nonatomic) unsigned int maxPixelsWide;
@property(nonatomic) unsigned int maxPixelsHigh;
@property(strong, nonatomic) id queue;
@property(copy, nonatomic) id terminationHandler;
// 이 디스플레이가 표현 가능하다고 **선언하는** 색역 (CIE xy). 기본값은 Rec.709/sRGB다.
// macOS는 이 값으로 디스플레이 ICC 프로파일을 만들고, 데스크톱 합성이 그 색역으로 눌린다.
@property(nonatomic) CGPoint redPrimary;
@property(nonatomic) CGPoint greenPrimary;
@property(nonatomic) CGPoint bluePrimary;
@property(nonatomic) CGPoint whitePoint;
@end

// Display P3 원색 + D65 백색점.
// 기본값(Rec.709: R 0.64/0.33, G 0.30/0.60, B 0.15/0.06)으로 두면 **우리가 색역 상한이 된다** —
// 폰이 뭘 표시할 수 있든, 709를 넘는 색은 애초에 캡처에 도달하지 않는다.
static const CGPoint kPeriP3Red   = { 0.680, 0.320 };
static const CGPoint kPeriP3Green = { 0.265, 0.690 };
static const CGPoint kPeriP3Blue  = { 0.150, 0.060 };
static const CGPoint kPeriD65     = { 0.3127, 0.3290 };

@interface CGVirtualDisplayMode : NSObject
- (instancetype)initWithWidth:(unsigned int)width
                       height:(unsigned int)height
                  refreshRate:(double)refreshRate;
/// designated init. 3인자 판은 `w4=0` 후 이쪽으로 꼬리호출할 뿐이라 완전히 동일하다(디스어셈블 확인).
/// transferFunction = 모드의 EOTF. applySettings가 이 값을 `CDVirtualDisplayModeEOTF` 키로
/// CoreDisplay에 넘기고, SkyLight `SLVirtualDisplayMode.eotf`가 받는다 — 무시되는 값이 아니다.
- (instancetype)initWithWidth:(unsigned int)width
                       height:(unsigned int)height
                  refreshRate:(double)refreshRate
             transferFunction:(unsigned int)transferFunction;
@end

// transferFunction 값. WindowServer 안에서 해석돼 정적으로는 안 나오지만, BetterDisplay의
// "HDR 지원 활성화" 분기를 디스어셈블해 확정했다 — 동일한 모드 생성 호출이 두 벌 있고
// HDR 플래그(`tbz w8,#0`)에 따라 4번째 인자만 `mov w4,#0` / `mov w4,#1`로 갈린다.
static const unsigned int kPeriTransferFunctionSDR = 0;
static const unsigned int kPeriTransferFunctionHDR = 1;

@interface CGVirtualDisplaySettings : NSObject
@property(strong, nonatomic) NSArray *modes;
@property(nonatomic) unsigned int hiDPI;
@end

@interface CGVirtualDisplay : NSObject
@property(readonly, nonatomic) unsigned int displayID;
- (instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@end

// =============================================================================
// SkyLight 비공개 HDR 계측 API
//
// 시그니처는 디스어셈블로 확정했다. SLS 함수 관례와 달리 **첫 인자가 연결ID(cid)가 아니라
// CGDirectDisplayID다** — x0이 그대로 `SLSDisplayGetPresetCount(x0)`와
// `realtime_data_for_display(int, bool)`로 들어간다.
//
// 이건 우리가 뭔가를 바꾸는 API가 아니라 **판정용 계측기**다. HDR 모드를 선언해도 macOS가
// 실제로 헤드룸을 줬는지는 이 값으로만 알 수 있다(내장 디스플레이 실측 potential=16.0,
// SDR 가상 디스플레이 실측 1.0).
// =============================================================================

typedef bool  (*PeriDisplayBoolFn)(CGDirectDisplayID);
typedef float (*PeriDisplayFloatFn)(CGDirectDisplayID);

static PeriDisplayBoolFn  sSupportsHDRMode;
static PeriDisplayBoolFn  sIsHDRModeEnabled;
static PeriDisplayFloatFn sPotentialHeadroom;
static PeriDisplayFloatFn sCurrentHeadroom;

static void PeriLoadSkyLightHDR(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        void *h = dlopen("/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight", RTLD_LAZY);
        if (!h) {
            NSLog(@"[VD] SkyLight dlopen 실패 — HDR 계측 불가");
            return;
        }
        sSupportsHDRMode   = (PeriDisplayBoolFn)dlsym(h, "SLSDisplaySupportsHDRMode");
        sIsHDRModeEnabled  = (PeriDisplayBoolFn)dlsym(h, "SLSDisplayIsHDRModeEnabled");
        sPotentialHeadroom = (PeriDisplayFloatFn)dlsym(h, "SLSDisplayGetPotentialHeadroom");
        sCurrentHeadroom   = (PeriDisplayFloatFn)dlsym(h, "SLSDisplayGetCurrentHeadroom");
        if (!sPotentialHeadroom) {
            NSLog(@"[VD] SLSDisplayGetPotentialHeadroom 해석 실패 — HDR 판정 불가");
        }
    });
}

// =============================================================================

@interface PeriVirtualDisplay ()
@property(nonatomic, strong, nullable) CGVirtualDisplay *display;
@property(nonatomic, copy) NSString *displayName;
@property(nonatomic) uint32_t w;
@property(nonatomic) uint32_t h;
@property(nonatomic) uint32_t maxBound;
@property(nonatomic) double ppiValue;
@property(nonatomic) double refresh;
@property(nonatomic) BOOL hidpi;
@property(nonatomic) uint32_t serial;
@end

@implementation PeriVirtualDisplay

+ (uint32_t)vendorID  { return kPeriVendorID; }
+ (uint32_t)productID { return kPeriProductID; }

- (instancetype)initWithName:(NSString *)name
                   serialNum:(uint32_t)serialNum
                       width:(uint32_t)width
                      height:(uint32_t)height
                         ppi:(double)ppi
                 refreshRate:(double)refreshRate
                       hiDPI:(BOOL)hiDPI
                 maxPixelDim:(uint32_t)maxPixelDim {
    if ((self = [super init])) {
        _displayName = [name copy];
        _serial = serialNum ? serialNum : (arc4random() | 0x1u);  // 0 회피(거부 방지)
        _w = width;
        _h = height;
        _maxBound = maxPixelDim;
        _ppiValue = (ppi > 0.0) ? ppi : 110.0;
        _refresh = (refreshRate > 0.0) ? refreshRate : 60.0;
        _hidpi = hiDPI;
    }
    return self;
}

- (CGDirectDisplayID)displayID {
    return self.display ? self.display.displayID : 0;
}

- (uint32_t)pixelWidth { return self.w; }
- (uint32_t)pixelHeight { return self.h; }

/// CGDirectDisplayID는 디스플레이가 사라지면 *재사용*된다. 생성 직후 등록이 끝나기 전에
/// displayID를 읽으면 방금 파괴된 디스플레이의 ID나 다른 세션이 쓰는 ID가 나올 수 있고,
/// 그 ID로 캡처하면 **다른 기기의 화면을 송출**하게 된다(멀티 기기 화면 뒤바뀜의 원인).
/// vendor/product/serial이 우리 정체성과 일치할 때만 유효한 ID로 인정한다.
- (CGDirectDisplayID)confirmedDisplayID {
    CGDirectDisplayID vid = self.displayID;
    if (vid == 0) return 0;
    uint32_t vendor = CGDisplayVendorNumber(vid);
    uint32_t model  = CGDisplayModelNumber(vid);
    uint32_t serial = CGDisplaySerialNumber(vid);
    if (vendor == kPeriVendorID && model == kPeriProductID && serial == self.serial) return vid;
    NSLog(@"[VD] displayID=%u 정체성 불일치 (vendor=0x%x/0x%x model=0x%x/0x%x serial=%u/%u) — 등록 대기",
          vid, vendor, kPeriVendorID, model, kPeriProductID, serial, self.serial);
    return 0;
}

- (BOOL)create {
    if (self.display) return YES;

    CGVirtualDisplayDescriptor *desc = [[CGVirtualDisplayDescriptor alloc] init];
    desc.name = self.displayName;
    // maxPixels는 생성 후 변경 불가(readonly)이고 모드 픽셀폭의 상한이 된다.
    // 분할/팝업/회전 등 어떤 창 크기로도 destroy 없이 resize-in-place 하려면, 전체 디스플레이
    // 긴 변(maxBound)을 정사각 bound로 잡는다. (실제 프레임버퍼는 활성 모드 크기만큼만 사용)
    uint32_t selfMax = self.w > self.h ? self.w : self.h;
    uint32_t bound = self.maxBound > selfMax ? self.maxBound : selfMax;
    desc.maxPixelsWide = bound;
    desc.maxPixelsHigh = bound;
    // 물리 크기 = DPI로 역산. 단 폰 실제밀도(예 480)를 그대로 쓰면 가로폭이 너무 작아
    // (세로 1080폭@480 → 57mm) macOS가 크기를 거부하고 825mm/72dpi로 폴백 → TV 분류
    // → "윈도우 또는 앱" UI(0Mbps 깨짐의 발생원). 실측상 기준은 밀도가 아니라 '가로폭 ≥166mm'다
    // (세로 방향에서 특히 잘 걸림). → 어떤 해상도/방향에서도 가로폭이 kPeriMinPhysicalWidthMM
    // 이상이 되도록 ppi를 필요한 만큼만 낮춘다. 물리크기는 송출 픽셀/hiDPI 2x와 무관 — macOS
    // 디스플레이 분류에만 영향. (sizeInMillimeters는 생성 후 불변이라 초기 방향 기준으로 고정되며,
    //  회전해도 가로폭은 그대로 ≥최소치라 계속 수용된다.)
    double widthCapPPI = 25.4 * (double)self.w / kPeriMinPhysicalWidthMM;  // 폭=최소치가 되는 ppi
    double physPPI = (self.ppiValue > widthCapPPI) ? widthCapPPI : self.ppiValue;  // 폭 보장 위해서만 낮춤
    desc.sizeInMillimeters = CGSizeMake(25.4 * self.w / physPPI,
                                        25.4 * self.h / physPPI);
    // 색역: HDR일 때만 P3로 넓힌다.
    //
    // SDR 경로를 같이 넓히면 **회귀가 난다**: SDR 캡처는 BGRA를 색공간 태그 없이 보내고
    // 수신측은 Rec.709로 가정하므로, 디스플레이만 P3가 되면 P3 좌표를 709로 해석해
    // 색이 과포화된다. HDR 경로는 캡처 색공간을 ITUR_2100_PQ로 못박아 두었으므로
    // (macOS가 P3→2020 변환을 수행) 넓혀도 태그와 어긋나지 않는다.
    if (self.hdr) {
        desc.redPrimary   = kPeriP3Red;
        desc.greenPrimary = kPeriP3Green;
        desc.bluePrimary  = kPeriP3Blue;
        desc.whitePoint   = kPeriD65;
    }
    desc.productID = kPeriProductID;
    desc.vendorID = kPeriVendorID;
    desc.serialNum = self.serial;   // 기기별 고유 serial(Swift DisplaySerialStore가 영구 관리)
    desc.queue = dispatch_get_main_queue();
    desc.terminationHandler = ^(id a, id b) {};

    CGVirtualDisplay *disp = [[CGVirtualDisplay alloc] initWithDescriptor:desc];

    if (![disp applySettings:[self buildSettings]]) {
        return NO;
    }
    self.display = disp;
    return YES;
}

// 현재 w/h/hiDPI/refresh로 설정 객체를 만든다 (create/resize 공용).
// 네이티브(1x) 모드 + (hiDPI면) 절반 크기 모드. maxPixels가 절반 모드의 2배를 수용하므로
// macOS가 (w/2 × h/2 논리)/(w × h 픽셀)의 2x 모드를 생성 → activatePreferredMode로 활성화.
- (CGVirtualDisplaySettings *)buildSettings {
    CGVirtualDisplaySettings *settings = [[CGVirtualDisplaySettings alloc] init];
    settings.hiDPI = self.hidpi ? 1 : 0;
    unsigned int tf = self.hdr ? kPeriTransferFunctionHDR : kPeriTransferFunctionSDR;
    NSMutableArray *modeList = [NSMutableArray array];
    [modeList addObject:[[CGVirtualDisplayMode alloc] initWithWidth:self.w
                                                             height:self.h
                                                        refreshRate:self.refresh
                                                   transferFunction:tf]];
    if (self.hidpi && self.w >= 2 && self.h >= 2) {
        [modeList addObject:[[CGVirtualDisplayMode alloc] initWithWidth:self.w / 2
                                                                 height:self.h / 2
                                                            refreshRate:self.refresh
                                                       transferFunction:tf]];
    }
    settings.modes = modeList;
    return settings;
}

#pragma mark - HDR 계측

- (float)potentialHeadroom {
    PeriLoadSkyLightHDR();
    CGDirectDisplayID vid = self.displayID;
    return (vid && sPotentialHeadroom) ? sPotentialHeadroom(vid) : 0.0f;
}

- (float)currentHeadroom {
    PeriLoadSkyLightHDR();
    CGDirectDisplayID vid = self.displayID;
    return (vid && sCurrentHeadroom) ? sCurrentHeadroom(vid) : 0.0f;
}

- (BOOL)supportsHDRMode {
    PeriLoadSkyLightHDR();
    CGDirectDisplayID vid = self.displayID;
    return (vid && sSupportsHDRMode) ? sSupportsHDRMode(vid) : NO;
}

- (BOOL)hdrModeEnabled {
    PeriLoadSkyLightHDR();
    CGDirectDisplayID vid = self.displayID;
    return (vid && sIsHDRModeEnabled) ? sIsHDRModeEnabled(vid) : NO;
}

+ (float)systemHDRContentHeadroom {
    PeriLoadSkyLightHDR();
    if (!sCurrentHeadroom) return 0.0f;
    CGDirectDisplayID ids[16];
    uint32_t n = 0;
    if (CGGetActiveDisplayList(16, ids, &n) != kCGErrorSuccess) return 0.0f;
    float best = 0.0f;
    for (uint32_t i = 0; i < n; i++) {
        CGDirectDisplayID d = ids[i];
        // 우리 가상 디스플레이는 헤드룸이 고정이라 수요를 반영하지 않는다 → 제외.
        if (CGDisplayVendorNumber(d) == kPeriVendorID && CGDisplayModelNumber(d) == kPeriProductID) continue;
        float h = sCurrentHeadroom(d);
        if (h > best) best = h;
    }
    return best;
}

- (NSString *)hdrDiagnostics {
    float pot = [self potentialHeadroom];
    // macOS가 이 디스플레이에 붙인 색공간 — 선언한 원색이 반영됐는지 확인용.
    NSString *csName = @"?";
    CGDirectDisplayID vid = self.displayID;
    if (vid) {
        CGColorSpaceRef cs = CGDisplayCopyColorSpace(vid);
        if (cs) {
            CFStringRef n = CGColorSpaceCopyName(cs);
            if (n) { csName = (__bridge_transfer NSString *)n; }
            CFRelease(cs);
        }
    }
    return [NSString stringWithFormat:
            @"선언=%@ headroom(current/potential)=%.2f/%.2f supportsToggle=%d toggleOn=%d colorSpace=%@ → %@",
            self.hdr ? @"HDR/P3" : @"SDR/709",
            [self currentHeadroom], pot,
            [self supportsHDRMode], [self hdrModeEnabled], csName,
            pot > 1.0f ? @"HDR 실제 동작" : @"SDR (헤드룸 없음)"];
}

- (BOOL)resizeToWidth:(uint32_t)width
               height:(uint32_t)height
                  ppi:(double)ppi
          refreshRate:(double)refreshRate
                hiDPI:(BOOL)hiDPI {
    if (!self.display) return NO;
    self.w = width;
    self.h = height;
    self.ppiValue = (ppi > 0.0) ? ppi : 110.0;
    self.refresh = (refreshRate > 0.0) ? refreshRate : 60.0;
    self.hidpi = hiDPI;
    return [self.display applySettings:[self buildSettings]];  // destroy 없이 모드만 교체
}

- (BOOL)activatePreferredMode {
    CGDirectDisplayID vid = self.displayID;
    if (vid == 0) return NO;
    NSDictionary *opt = @{ (__bridge NSString *)kCGDisplayShowDuplicateLowResolutionModes : @YES };
    CFArrayRef modes = CGDisplayCopyAllDisplayModes(vid, (__bridge CFDictionaryRef)opt);
    if (!modes) return NO;

    // backing 픽셀폭이 우리 인코딩폭(self.w)과 같은 모드 중 최고 백킹스케일 선택.
    // 동률이면 *목표 주사율에 가장 가까운* 모드를 고른다 — macOS가 같은 해상도에 여러 주사율 모드를
    // 합성하므로, 주사율을 안 보면 120Hz 가상 디스플레이인데 60Hz 모드를 활성화할 수 있고 그러면
    // 실제 타이밍(8.33ms)과 모드가 어긋나 AppKit이 "Invalid new timing data"를 쏟아낸다.
    CGDisplayModeRef best = NULL;
    double bestScale = 1.0;
    double bestRefreshErr = 0;
    for (CFIndex i = 0; i < CFArrayGetCount(modes); i++) {
        CGDisplayModeRef m = (CGDisplayModeRef)CFArrayGetValueAtIndex(modes, i);
        if (CGDisplayModeGetPixelWidth(m) != self.w) continue;
        size_t lw = CGDisplayModeGetWidth(m);
        double scale = lw ? (double)CGDisplayModeGetPixelWidth(m) / (double)lw : 1.0;
        double rr = CGDisplayModeGetRefreshRate(m);
        double refreshErr = fabs((rr > 0 ? rr : self.refresh) - self.refresh);
        if (scale > bestScale || (best && scale == bestScale && refreshErr < bestRefreshErr)) {
            bestScale = scale; bestRefreshErr = refreshErr; best = m;
        }
    }
    BOOL ok = (best != NULL) && (CGDisplaySetDisplayMode(vid, best, NULL) == kCGErrorSuccess);
    CFRelease(modes);
    return ok;
}

- (BOOL)isOnline {
    CGDirectDisplayID vid = self.displayID;
    return vid != 0 && CGDisplayIsOnline(vid);
}

- (BOOL)isMirrored {
    CGDirectDisplayID vid = self.displayID;
    return vid != 0 && CGDisplayMirrorsDisplay(vid) != kCGNullDirectDisplay;
}

// 우리 가상 디스플레이가 (주로 신규 등록 시) 미러로 들어오면 확장으로 되돌린다.
// kCGConfigureForSession: 지금 세션에만 적용 → 사용자가 시스템 설정에 저장한 배치를 영구 덮어쓰지 않음
// (랜덤-안정 serial이라 정체성은 유지되므로 다음 실행에도 우리 코드가 다시 확장 보장).
- (BOOL)forceExtended {
    CGDirectDisplayID vid = self.displayID;
    if (vid == 0) return NO;
    if (CGDisplayMirrorsDisplay(vid) == kCGNullDirectDisplay) return YES;  // 이미 확장
    CGDisplayConfigRef cfg;
    if (CGBeginDisplayConfiguration(&cfg) != kCGErrorSuccess) return NO;
    CGConfigureDisplayMirrorOfDisplay(cfg, vid, kCGNullDirectDisplay);     // 미러 해제
    if (CGCompleteDisplayConfiguration(cfg, kCGConfigureForSession) != kCGErrorSuccess) {
        CGCancelDisplayConfiguration(cfg);
        return NO;
    }
    return CGDisplayMirrorsDisplay(vid) == kCGNullDirectDisplay;
}

- (void)destroy {
    self.display = nil;  // 해제 시 가상 디스플레이 제거됨
}

@end
