//
//  AoaServer.m
//  Peri Display
//
//  AOA(USB) 호스트 구현. IOKit first-match 알림으로 USB 부착을 상시 감시하다가 Android를
//  액세서리 모드로 전환하고 bulk 파이프를 연다. 링크가 끊기면(케이블 분리/IO 에러) USB 객체를
//  destroy로 해제(Seize 소유권 반납)하고 감시로 복귀 — 재플러그 없이 자동 재연결된다.
//  모든 sendDeviceRequest/sendIORequest는 동기(완료 타임아웃) — 전용 백그라운드 큐에서 블로킹.
//

#import "AoaServer.h"
#import <IOUSBHost/IOUSBHost.h>
#import <IOKit/IOKitLib.h>
#import <IOKit/usb/AppleUSBDefinitions.h>

// AOA 벤더 제어 요청 (Android Open Accessory 프로토콜)
static const uint8_t kAoaGetProtocol = 51;
static const uint8_t kAoaSendString  = 52;
static const uint8_t kAoaStart       = 53;
// AOAv2 HID — 전용 USB 인터페이스 없이 EP0 제어 전송만으로 동작한다.
static const uint8_t kAoaRegisterHid      = 54;   // wValue=HID id, wIndex=디스크립터 총 길이
static const uint8_t kAoaUnregisterHid    = 55;   // wValue=HID id
static const uint8_t kAoaSetHidReportDesc = 56;   // wValue=HID id, wIndex=오프셋, data=디스크립터
static const uint8_t kAoaSendHidEvent     = 57;   // wValue=HID id, data=HID 리포트
static const uint16_t kGoogleVID     = 0x18D1;   // 액세서리 모드 VID

// 리포트 디스크립터 1회 전송 상한. 디스크립터가 이보다 길면 wIndex(오프셋)로 나눠 보낸다.
static const NSUInteger kHidDescChunk = 256;

// 접촉 불량(케이블 흔들림) 대응: bulk 엔드포인트는 타임아웃 외 IO 에러를 만나면 Halted로 전이되고,
// ClearStall(=CLEAR_FEATURE(ENDPOINT_HALT), data toggle 리셋)을 해야 이후 IO가 산다(SDK 문서).
// 이 횟수까지는 링크를 죽이지 않고 제자리 복구 → 짧은 글리치에 세션이 살아남는다.
static const int kMaxStallRecoveries = 3;

// 같은 기기 재프로브 최소 간격(초). 2초 sweep이 미링크 기기를 Seize→getProtocol→액세서리 전환으로
// 계속 두드리면, 폰이 액세서리 세션을 세우는 도중에 방해받아 ClientHello까지 못 간다.
static const NSTimeInterval kProbeCooldown = 10.0;

// AOA 프로브 후보 허용목록 (주요 Android OEM VID). 상시 감시가 임의 USB 기기(저장장치·키보드)를
// Seize로 열면 해당 드라이버가 분리되는 부작용이 있으므로, 폰일 가능성이 높은 VID만 프로브한다.
static const uint16_t kAndroidVIDs[] = {
    0x18D1,  // Google
    0x04E8,  // Samsung
    0x2717,  // Xiaomi
    0x22B8,  // Motorola
    0x12D1,  // Huawei
    0x0FCE,  // Sony
    0x0BB4,  // HTC
    0x1004,  // LG
    0x22D9,  // Oppo
    0x2A70,  // OnePlus
    0x2D95,  // Vivo
    0x0B05,  // Asus
};

static BOOL PeriIsAndroidVendor(uint16_t vid) {
    for (size_t i = 0; i < sizeof(kAndroidVIDs) / sizeof(kAndroidVIDs[0]); i++)
        if (kAndroidVIDs[i] == vid) return YES;
    return NO;
}

/// 한 기기의 링크 상태 (USB 객체 + 전용 read/write 큐). 기기마다 하나씩 — 큐를 분리해야
/// 한 기기의 블로킹 read/write가 다른 기기를 막지 않는다.
@interface PeriAoaLink : NSObject
@property(nonatomic, copy)   NSString *deviceID;
@property(nonatomic, strong, nullable) IOUSBHostDevice    *device;
@property(nonatomic, strong, nullable) IOUSBHostInterface *iface;
@property(nonatomic, strong, nullable) IOUSBHostPipe      *inPipe;
@property(nonatomic, strong, nullable) IOUSBHostPipe      *outPipe;
@property(nonatomic, strong) dispatch_queue_t readQ;
@property(nonatomic, strong) dispatch_queue_t writeQ;
/// HID 제어 전송 전용 큐. bulk 송신(writeQ)과 분리해야 터치 주입이 영상 송출을 막지 않는다.
@property(nonatomic, strong) dispatch_queue_t hidQ;
/// 이 기기가 보고한 AOA 프로토콜 버전 (HID는 2 이상 필요). 0 = 미확인.
@property(atomic) int protocolVersion;
@property(atomic) BOOL alive;
@end

@implementation PeriAoaLink
@end

@interface PeriAoaServer ()
@property(atomic) BOOL running;
- (void)handleMatches:(io_iterator_t)iter;
@end

/// IOKit first-match 콜백 (_connectQ에서 호출됨). 이터레이터를 소진해야 알림이 재장전된다.
static void PeriAoaDeviceMatched(void *refcon, io_iterator_t iter) {
    PeriAoaServer *server = (__bridge PeriAoaServer *)refcon;
    [server handleMatches:iter];
}

@implementation PeriAoaServer {
    NSMutableDictionary<NSString *, PeriAoaLink *> *_links;  // deviceID → 링크 (_linksLock 보호)
    NSLock             *_linksLock;   // sendData가 임의 스레드에서 조회하므로 락 필요
    NSMutableDictionary<NSString *, NSDate *> *_lastProbe;   // deviceID → 마지막 프로브 (_connectQ 전용)
    dispatch_queue_t    _connectQ;    // 감시/연결/해제 상태의 직렬화 지점
    IONotificationPortRef _notifyPort;
    io_iterator_t       _matchIter;
    BOOL                _retryScheduled; // _connectQ에서만 변경
}

- (instancetype)init {
    if ((self = [super init])) {
        _connectQ  = dispatch_queue_create("com.peri.display.aoa.connect", DISPATCH_QUEUE_SERIAL);
        _links     = [NSMutableDictionary dictionary];
        _linksLock = [[NSLock alloc] init];
        _lastProbe = [NSMutableDictionary dictionary];
    }
    return self;
}

// MARK: - 링크 레지스트리

- (PeriAoaLink *)linkForDevice:(NSString *)deviceID {
    [_linksLock lock];
    PeriAoaLink *l = _links[deviceID];
    [_linksLock unlock];
    return l;
}

- (BOOL)isLinked:(NSString *)deviceID { return [self linkForDevice:deviceID] != nil; }

- (void)putLink:(PeriAoaLink *)link {
    [_linksLock lock];
    _links[link.deviceID] = link;
    [_linksLock unlock];
}

- (PeriAoaLink *)takeLink:(NSString *)deviceID {
    [_linksLock lock];
    PeriAoaLink *l = _links[deviceID];
    if (l) [_links removeObjectForKey:deviceID];
    [_linksLock unlock];
    return l;
}

- (NSArray<PeriAoaLink *> *)allLinks {
    [_linksLock lock];
    NSArray *v = _links.allValues;
    [_linksLock unlock];
    return v;
}

- (void)start {
    if (self.running) return;
    self.running = YES;
    dispatch_async(_connectQ, ^{ [self armWatch]; });
}

- (void)stop {
    self.running = NO;
    dispatch_async(_connectQ, ^{
        // 의도적 종료 — 리셋 불필요(기기는 정상 상태로 남겨둔다).
        for (PeriAoaLink *l in [self allLinks]) [self unlinkLocked:l.deviceID reason:@"stop" reset:NO];
        if (self->_matchIter) { IOObjectRelease(self->_matchIter); self->_matchIter = 0; }
        if (self->_notifyPort) { IONotificationPortDestroy(self->_notifyPort); self->_notifyPort = NULL; }
    });
}

// MARK: - 감시 (USB attach 알림 + 재연결 폴링)

/// _connectQ: first-match 알림 장전. 초기 이터레이터 드레인이 현재 부착된 기기들도 전달한다.
- (void)armWatch {
    if (_notifyPort) return;
    _notifyPort = IONotificationPortCreate(kIOMainPortDefault);
    if (!_notifyPort) { NSLog(@"[AOA] notification port 생성 실패"); return; }
    IONotificationPortSetDispatchQueue(_notifyPort, _connectQ);
    kern_return_t kr = IOServiceAddMatchingNotification(
        _notifyPort, kIOFirstMatchNotification, IOServiceMatching("IOUSBHostDevice"),
        PeriAoaDeviceMatched, (__bridge void *)self, &_matchIter);
    if (kr != KERN_SUCCESS) { NSLog(@"[AOA] matching notification 실패: 0x%x", kr); return; }
    NSLog(@"[AOA] USB 감시 시작");
    [self handleMatches:_matchIter];   // 기존 부착 기기 처리 + 알림 재장전
}

/// _connectQ: 부착 이벤트(또는 초기 스캔). 액세서리면 즉시 오픈, Android 후보면 모드 전환 시도.
/// 전환하면 기기가 재열거되어 액세서리 PID로 새 first-match 이벤트가 다시 들어온다.
/// 열거 직후엔 macOS 쪽(usbd/ptpcamerad MTP 오픈)이 기기를 잠깐 배타 점유할 수 있어
/// 250ms 정착 후 시도하고, 그래도 실패하면 폴링 재시도가 이어받는다.
- (void)handleMatches:(io_iterator_t)iter {
    io_service_t svc;
    while ((svc = IOIteratorNext(iter))) {
        if (!self.running) { IOObjectRelease(svc); continue; }  // 소진만 (재장전 목적)
        NSString *devID = [self deviceIDOf:svc];
        if (devID && ![self isLinked:devID]) {   // 이미 링크된 기기는 건너뜀 (다른 기기는 계속 수용)
            uint16_t vid = [self regU16:svc key:CFSTR("idVendor")];
            uint16_t pid = [self regU16:svc key:CFSTR("idProduct")];
            uint16_t cls = [self regU16:svc key:CFSTR("bDeviceClass")];
            if (vid == kGoogleVID && pid >= 0x2D00 && pid <= 0x2D05) {
                usleep(250 * 1000);
                [self openAccessory:svc deviceID:devID];
            } else if (PeriIsAndroidVendor(vid) && cls != 9 /*hub*/) {
                usleep(250 * 1000);
                [self probeCandidate:svc vid:vid deviceID:devID];
            }
        }
        IOObjectRelease(svc);
    }
}

/// _connectQ: 2초 주기 재시도(감시 보완). 아직 링크 안 된 액세서리 모드 기기는 재오픈
/// (앱 재시작·세션 끊김 — 이미 액세서리 모드라 attach 이벤트가 다시 안 온다),
/// 일반 모드 Android 후보는 다시 프로브 (부착 직후 배타 점유 경합이 풀린 뒤 성공).
/// 멀티 기기: 매 틱마다 미링크 기기 전부를 훑는다(이미 링크된 기기는 영향 없음).
- (void)scheduleRetry {
    if (_retryScheduled || !self.running) return;
    _retryScheduled = YES;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC), _connectQ, ^{
        self->_retryScheduled = NO;
        if (!self.running) return;
        [self sweepRegistry];
        [self scheduleRetry];
    });
}

/// _connectQ: 레지스트리 전체를 훑어 미링크 기기를 처리 — 액세서리면 오픈, 일반 후보면 프로브.
- (void)sweepRegistry {
    io_iterator_t it = 0;
    if (IOServiceGetMatchingServices(kIOMainPortDefault,
            IOServiceMatching("IOUSBHostDevice"), &it) != KERN_SUCCESS) return;
    io_service_t svc;
    while ((svc = IOIteratorNext(it))) {
        if (!self.running) { IOObjectRelease(svc); continue; }
        NSString *devID = [self deviceIDOf:svc];
        if (devID && ![self isLinked:devID]) {
            uint16_t vid = [self regU16:svc key:CFSTR("idVendor")];
            uint16_t pid = [self regU16:svc key:CFSTR("idProduct")];
            uint16_t cls = [self regU16:svc key:CFSTR("bDeviceClass")];
            if (vid == kGoogleVID && pid >= 0x2D00 && pid <= 0x2D05) {
                [self openAccessory:svc deviceID:devID];
            } else if (PeriIsAndroidVendor(vid) && cls != 9 /*hub*/) {
                [self probeCandidate:svc vid:vid deviceID:devID];
            }
        }
        IOObjectRelease(svc);
    }
    IOObjectRelease(it);
}

// MARK: - 액세서리 모드 전환

/// _connectQ: 후보 기기에 AOA getProtocol을 시도하고 지원하면 액세서리 모드로 전환.
/// 같은 기기를 연달아 두드리지 않도록 쿨다운(재열거·앱 기동에 시간이 필요).
- (void)probeCandidate:(io_service_t)svc vid:(uint16_t)vid deviceID:(NSString *)deviceID {
    NSDate *last = deviceID ? _lastProbe[deviceID] : nil;
    if (last && [[NSDate date] timeIntervalSinceDate:last] < kProbeCooldown) return;
    if (deviceID) _lastProbe[deviceID] = [NSDate date];
    NSError *err = nil;
    // 기기가 macOS 드라이버(MTP 등)에 잡혀 있으므로 Seize로 소유권 요청해야 open 성공.
    IOUSBHostDevice *dev = [[IOUSBHostDevice alloc] initWithIOService:svc
                                options:IOUSBHostObjectInitOptionsDeviceSeize queue:nil
                                  error:&err interestHandler:nil];
    if (!dev) {
        // 실제 IOReturn 구분: notPermitted(0xE00002E2/-536870174)=샌드박스, exclusiveAccess(0xE00002C5)=점유
        // 부착 직후엔 macOS(usbd/ptpcamerad)가 잠깐 배타 점유 → 폴링 재시도로 풀린 뒤 성공한다.
        NSLog(@"[AOA] candidate vid=0x%04x open 실패: domain=%@ code=%ld (%@) info=%@",
              vid, err.domain, (long)err.code, err.localizedDescription, err.userInfo);
        [self scheduleRetry];
        return;
    }
    BOOL switched = [self switchToAccessory:dev];
    [dev destroy];   // 소유  권 반납 — 전환 성공 시 어차피 재열거로 무효화됨
    if (switched) [self scheduleRetry];   // 재열거 이벤트를 놓쳐도 폴링으로 잡는다
}

- (BOOL)switchToAccessory:(IOUSBHostDevice *)dev {
    int proto = [self getProtocol:dev];
    if (proto < 1) return NO;
    NSLog(@"[AOA] AOA proto=%d 지원 → 액세서리 전환", proto);
    [self sendString:dev index:0 str:@"Peri Display"];
    [self sendString:dev index:1 str:@"Mac Screen Extension"];
    [self sendString:dev index:2 str:@"Peri Display screen extension"];
    [self sendString:dev index:3 str:@"1.0"];
    [self sendString:dev index:4 str:@"https://peri.display"];
    [self sendString:dev index:5 str:@"0001"];
    IOUSBDeviceRequest r;
    r.bmRequestType = IOUSBHostDeviceRequestType(kIOUSBDeviceRequestDirectionValueOut,
                                                 kIOUSBDeviceRequestTypeValueVendor,
                                                 kIOUSBDeviceRequestRecipientValueDevice);
    r.bRequest = kAoaStart; r.wValue = 0; r.wIndex = 0; r.wLength = 0;
    NSError *err = nil; NSUInteger n = 0;
    return [dev sendDeviceRequest:r data:nil bytesTransferred:&n completionTimeout:1.0 error:&err];
}

- (int)getProtocol:(IOUSBHostDevice *)dev {
    IOUSBDeviceRequest r;
    r.bmRequestType = IOUSBHostDeviceRequestType(kIOUSBDeviceRequestDirectionValueIn,
                                                 kIOUSBDeviceRequestTypeValueVendor,
                                                 kIOUSBDeviceRequestRecipientValueDevice);
    r.bRequest = kAoaGetProtocol; r.wValue = 0; r.wIndex = 0; r.wLength = 2;
    NSMutableData *d = [dev ioDataWithCapacity:2 error:NULL];   // ioData는 length 고정(=capacity)
    NSError *err = nil; NSUInteger n = 0;
    if (!d || ![dev sendDeviceRequest:r data:d bytesTransferred:&n completionTimeout:1.0 error:&err] || n < 2)
        return 0;
    const uint8_t *b = d.bytes;
    return b[0] | (b[1] << 8);
}

- (void)sendString:(IOUSBHostDevice *)dev index:(uint16_t)idx str:(NSString *)s {
    NSData *utf8 = [s dataUsingEncoding:NSUTF8StringEncoding];
    NSMutableData *payload = [NSMutableData dataWithData:utf8];
    [payload appendBytes:"\0" length:1];   // null 종단
    IOUSBDeviceRequest r;
    r.bmRequestType = IOUSBHostDeviceRequestType(kIOUSBDeviceRequestDirectionValueOut,
                                                 kIOUSBDeviceRequestTypeValueVendor,
                                                 kIOUSBDeviceRequestRecipientValueDevice);
    r.bRequest = kAoaSendString; r.wValue = 0; r.wIndex = idx; r.wLength = (uint16_t)payload.length;
    NSMutableData *io = [dev ioDataWithCapacity:payload.length error:NULL];
    if (!io) return;
    memcpy(io.mutableBytes, payload.bytes, payload.length);
    NSError *err = nil; NSUInteger n = 0;
    [dev sendDeviceRequest:r data:io bytesTransferred:&n completionTimeout:1.0 error:&err];
}

// MARK: - 액세서리 오픈

/// _connectQ: 액세서리 모드 기기의 bulk IN/OUT 파이프를 연다. 성공 시 이 기기의 링크 등록.
- (void)openAccessory:(io_service_t)svc deviceID:(NSString *)deviceID {
    if ([self isLinked:deviceID]) return;
    NSError *err = nil;
    IOUSBHostDevice *dev = [[IOUSBHostDevice alloc] initWithIOService:svc
                                options:IOUSBHostObjectInitOptionsDeviceSeize queue:nil
                                  error:&err interestHandler:nil];
    if (!dev) {
        NSLog(@"[AOA][%@] 액세서리 오픈 실패: domain=%@ code=%ld (%@)",
              deviceID, err.domain, (long)err.code, err.localizedDescription);
        [self scheduleRetry];
        return;
    }
    PeriAoaLink *link = [[PeriAoaLink alloc] init];
    link.deviceID = deviceID;
    link.device = dev;
    link.readQ = dispatch_queue_create("com.peri.display.aoa.read", DISPATCH_QUEUE_SERIAL);
    link.writeQ = dispatch_queue_create("com.peri.display.aoa.write", DISPATCH_QUEUE_SERIAL);
    link.hidQ = dispatch_queue_create("com.peri.display.aoa.hid", DISPATCH_QUEUE_SERIAL);

    // 액세서리 모드에서도 AOA 핸들러는 살아 있으므로 여기서 다시 묻는다. 전환 때 값을 넘겨받지
    // 않는 이유: 이미 액세서리 모드로 부착된 기기는(앱 재시작 등) probeCandidate를 거치지 않고
    // 곧장 이 경로로 들어와, 넘겨받을 값이 애초에 없다.
    link.protocolVersion = [self getProtocol:dev];

    const IOUSBConfigurationDescriptor *cfg = dev.configurationDescriptor;
    NSUInteger cfgVal = cfg ? cfg->bConfigurationValue : 1;
    [dev configureWithValue:cfgVal error:&err];   // 이미 설정돼 있으면 에러 무시

    io_service_t ifaceSvc = 0;
    for (int i = 0; i < 20 && self.running && !ifaceSvc; i++) {   // 인터페이스 nub 대기 ~1s
        ifaceSvc = [self firstInterfaceOf:svc];
        if (!ifaceSvc) usleep(50 * 1000);
    }
    if (!ifaceSvc) { [self openFailed:link reason:@"인터페이스 없음"]; return; }
    IOUSBHostInterface *iface = [[IOUSBHostInterface alloc] initWithIOService:ifaceSvc queue:nil
                                                                        error:&err interestHandler:nil];
    IOObjectRelease(ifaceSvc);
    if (!iface) { [self openFailed:link reason:[NSString stringWithFormat:@"인터페이스 오픈 실패: %@", err.localizedDescription]]; return; }
    link.iface = iface;

    uint8_t inAddr = 0, outAddr = 0;
    [self findBulkEndpoints:iface in:&inAddr out:&outAddr];
    if (!inAddr || !outAddr) { [self openFailed:link reason:@"bulk 엔드포인트 없음"]; return; }
    link.inPipe  = [iface copyPipeWithAddress:inAddr error:&err];
    link.outPipe = [iface copyPipeWithAddress:outAddr error:&err];
    if (!link.inPipe || !link.outPipe) {
        [self openFailed:link reason:[NSString stringWithFormat:@"파이프 오픈 실패: %@", err.localizedDescription]];
        return;
    }
    link.alive = YES;
    [self putLink:link];
    NSLog(@"[AOA][%@] 링크 준비됨 in=0x%02x out=0x%02x proto=%d%@ (총 %lu대)",
          deviceID, inAddr, outAddr, link.protocolVersion,
          link.protocolVersion >= 2 ? @" (HID 가능)" : @" (HID 불가)",
          (unsigned long)[self allLinks].count);
    if (self.onConnected) self.onConnected(deviceID);
    [self startReadLoop:link];
}

/// _connectQ: 오픈 도중 실패 — 객체 해제 후 폴링 재시도 (등록 전이므로 onDisconnected 없음).
- (void)openFailed:(PeriAoaLink *)link reason:(NSString *)reason {
    NSLog(@"[AOA][%@] 오픈 실패: %@", link.deviceID, reason);
    [self releaseUsbObjectsOf:link reset:NO];
    [self scheduleRetry];
}

- (io_service_t)firstInterfaceOf:(io_service_t)dev {
    io_iterator_t it = 0;
    if (IORegistryEntryGetChildIterator(dev, kIOServicePlane, &it) != KERN_SUCCESS) return 0;
    io_service_t child, found = 0;
    while ((child = IOIteratorNext(it))) {
        if (IOObjectConformsTo(child, "IOUSBHostInterface")) { found = child; break; }
        IOObjectRelease(child);
    }
    IOObjectRelease(it);
    return found;
}

- (void)findBulkEndpoints:(IOUSBHostInterface *)iface in:(uint8_t *)inAddr out:(uint8_t *)outAddr {
    const IOUSBConfigurationDescriptor *cfg = iface.configurationDescriptor;
    const IOUSBInterfaceDescriptor *idesc = iface.interfaceDescriptor;
    if (!cfg || !idesc) return;
    const IOUSBEndpointDescriptor *ep = NULL;
    while ((ep = IOUSBGetNextEndpointDescriptor(cfg, idesc, (const IOUSBDescriptorHeader *)ep))) {
        if (IOUSBGetEndpointType(ep) != kIOUSBEndpointTypeBulk) continue;
        if (IOUSBGetEndpointDirection(ep) == kIOUSBEndpointDirectionIn) *inAddr = ep->bEndpointAddress;
        else *outAddr = ep->bEndpointAddress;
    }
}

// MARK: - I/O

/// 기기별 전용 read 큐 — 한 기기의 블로킹 read가 다른 기기를 막지 않는다.
- (void)startReadLoop:(PeriAoaLink *)link {
    dispatch_async(link.readQ, ^{
        int stalls = 0;
        while (self.running && link.alive) {
            @autoreleasepool {
                IOUSBHostDevice *dev = link.device;
                IOUSBHostPipe *pipe = link.inPipe;
                if (!dev || !pipe) break;
                NSMutableData *buf = [dev ioDataWithCapacity:16384 error:NULL];
                if (!buf) break;
                NSError *err = nil; NSUInteger n = 0;
                BOOL ok = [pipe sendIORequestWithData:buf bytesTransferred:&n
                                    completionTimeout:0 error:&err];
                if (!ok) {
                    // 접촉 불량 등 IO 에러 → 엔드포인트 Halted. ClearStall로 halt 해제 + data toggle
                    // 리셋을 해야 이후 IO가 산다(안 하면 기기를 재오픈해도 계속 "Unable to send IO").
                    // 기기가 정말 빠졌으면 ClearStall도 실패 → 아래 링크 해제로 떨어진다.
                    NSError *se = nil;
                    if (link.alive && stalls < kMaxStallRecoveries && [pipe clearStallWithError:&se]) {
                        stalls++;
                        NSLog(@"[AOA][%@] IO 에러 → ClearStall 복구 %d/%d (%@)", link.deviceID,
                              stalls, kMaxStallRecoveries, err.localizedDescription);
                        usleep(100 * 1000);   // 접촉 안정화 대기 후 재시도 (세션은 유지)
                        continue;
                    }
                    [self unlinkDevice:link.deviceID
                                reason:[NSString stringWithFormat:@"read err: %@", err.localizedDescription]
                                 reset:YES];
                    break;
                }
                stalls = 0;   // 정상 수신 → 복구 카운터 초기화
                if (n > 0 && self.onData)
                    self.onData(link.deviceID, [buf subdataWithRange:NSMakeRange(0, n)]);
            }
        }
    });
}

- (void)sendData:(NSData *)data toDevice:(NSString *)deviceID {
    PeriAoaLink *link = [self linkForDevice:deviceID];
    if (!link || data.length == 0) return;
    dispatch_async(link.writeQ, ^{
        IOUSBHostPipe *out = link.outPipe;
        IOUSBHostDevice *dev = link.device;
        if (!out || !dev || !link.alive) return;
        NSMutableData *d = [dev ioDataWithCapacity:data.length error:NULL];
        if (!d) return;
        memcpy(d.mutableBytes, data.bytes, data.length);
        NSError *err = nil; NSUInteger n = 0;
        if ([out sendIORequestWithData:d bytesTransferred:&n completionTimeout:2.0 error:&err]) return;
        // OUT도 IO 에러 시 Halted → ClearStall 없이는 이후 모든 송신이 죽는다(IN은 멀쩡한데 영상만 정지).
        NSError *se = nil;
        if (link.alive && [out clearStallWithError:&se] &&
            [out sendIORequestWithData:d bytesTransferred:&n completionTimeout:2.0 error:&err]) {
            NSLog(@"[AOA][%@] write stall → ClearStall 후 재송신 성공", deviceID);
            return;
        }
        NSLog(@"[AOA][%@] write err: %@ → 링크 재설정", deviceID, err.localizedDescription);
        [self unlinkDevice:deviceID reason:@"write err" reset:YES];
    });
}

// MARK: - HID (AOAv2, EP0)

- (int)protocolVersionOf:(NSString *)deviceID {
    PeriAoaLink *link = [self linkForDevice:deviceID];
    return link ? link.protocolVersion : 0;
}

/// 벤더 제어 전송(호스트→기기). data가 nil이면 wLength=0. 호출자 스레드에서 블로킹한다.
- (BOOL)vendorOut:(uint8_t)req value:(uint16_t)value index:(uint16_t)index
             data:(NSData *)data toDevice:(IOUSBHostDevice *)dev error:(NSError **)outErr {
    IOUSBDeviceRequest r;
    r.bmRequestType = IOUSBHostDeviceRequestType(kIOUSBDeviceRequestDirectionValueOut,
                                                 kIOUSBDeviceRequestTypeValueVendor,
                                                 kIOUSBDeviceRequestRecipientValueDevice);
    r.bRequest = req; r.wValue = value; r.wIndex = index;
    r.wLength = (uint16_t)(data ? data.length : 0);

    NSMutableData *io = nil;
    if (data.length > 0) {
        io = [dev ioDataWithCapacity:data.length error:outErr];
        if (!io) return NO;
        memcpy(io.mutableBytes, data.bytes, data.length);
    }
    NSUInteger n = 0;
    return [dev sendDeviceRequest:r data:io bytesTransferred:&n completionTimeout:1.0 error:outErr];
}

- (BOOL)registerHid:(uint16_t)hidID descriptor:(NSData *)descriptor onDevice:(NSString *)deviceID {
    PeriAoaLink *link = [self linkForDevice:deviceID];
    if (!link || !link.alive || !link.device) {
        NSLog(@"[AOA][%@] HID 등록 실패: 링크 없음", deviceID);
        return NO;
    }
    if (link.protocolVersion < 2) {
        NSLog(@"[AOA][%@] HID 등록 거부: AOA proto=%d (2 이상 필요)", deviceID, link.protocolVersion);
        return NO;
    }
    if (descriptor.length == 0 || descriptor.length > UINT16_MAX) return NO;

    // 제어 전송은 hidQ에서 직렬화한다(이벤트 송신과 순서 보장). 등록은 결과를 알아야 하므로 동기 대기.
    __block BOOL ok = NO;
    dispatch_sync(link.hidQ, ^{
        IOUSBHostDevice *dev = link.device;
        if (!dev || !link.alive) return;
        NSError *err = nil;

        // Req 54: 이 id로 HID 기기를 만들라 + 뒤따를 디스크립터의 총 길이를 미리 알린다.
        if (![self vendorOut:kAoaRegisterHid value:hidID index:(uint16_t)descriptor.length
                        data:nil toDevice:dev error:&err]) {
            NSLog(@"[AOA][%@] REGISTER_HID(54) 실패: %@", deviceID, err.localizedDescription);
            return;
        }
        // Req 56: 디스크립터 본문. 길면 오프셋을 옮겨 가며 나눠 보낸다.
        NSUInteger off = 0;
        while (off < descriptor.length) {
            NSUInteger len = MIN(kHidDescChunk, descriptor.length - off);
            NSData *slice = [descriptor subdataWithRange:NSMakeRange(off, len)];
            if (![self vendorOut:kAoaSetHidReportDesc value:hidID index:(uint16_t)off
                            data:slice toDevice:dev error:&err]) {
                NSLog(@"[AOA][%@] SET_HID_REPORT_DESC(56) 실패 off=%lu: %@",
                      deviceID, (unsigned long)off, err.localizedDescription);
                // 반쪽 등록을 남기지 않는다 — 커널이 디스크립터를 다 못 받으면 기기가 뜨지 않는다.
                [self vendorOut:kAoaUnregisterHid value:hidID index:0 data:nil toDevice:dev error:NULL];
                return;
            }
            off += len;
        }
        ok = YES;
        NSLog(@"[AOA][%@] HID 등록됨 id=%u desc=%luB (proto=%d)",
              deviceID, hidID, (unsigned long)descriptor.length, link.protocolVersion);
    });
    return ok;
}

- (void)sendHidEvent:(NSData *)report hid:(uint16_t)hidID toDevice:(NSString *)deviceID {
    PeriAoaLink *link = [self linkForDevice:deviceID];
    if (!link || report.length == 0) return;
    dispatch_async(link.hidQ, ^{
        IOUSBHostDevice *dev = link.device;
        if (!dev || !link.alive) return;
        NSError *err = nil;
        if (![self vendorOut:kAoaSendHidEvent value:hidID index:0
                        data:report toDevice:dev error:&err]) {
            // EP0 실패는 bulk와 달리 링크를 죽이지 않는다 — 영상은 멀쩡할 수 있으므로 조작만 포기한다.
            NSLog(@"[AOA][%@] SEND_HID_EVENT(57) 실패: %@", deviceID, err.localizedDescription);
        }
    });
}

- (void)unregisterHid:(uint16_t)hidID onDevice:(NSString *)deviceID {
    PeriAoaLink *link = [self linkForDevice:deviceID];
    if (!link) return;
    dispatch_async(link.hidQ, ^{
        IOUSBHostDevice *dev = link.device;
        if (!dev || !link.alive) return;
        NSError *err = nil;
        if ([self vendorOut:kAoaUnregisterHid value:hidID index:0 data:nil toDevice:dev error:&err])
            NSLog(@"[AOA][%@] HID 해제됨 id=%u", deviceID, hidID);
        else
            NSLog(@"[AOA][%@] UNREGISTER_HID(55) 실패: %@", deviceID, err.localizedDescription);
    });
}

// MARK: - 해제

- (void)unlinkDevice:(NSString *)deviceID reason:(NSString *)reason reset:(BOOL)reset {
    dispatch_async(_connectQ, ^{ [self unlinkLocked:deviceID reason:reason reset:reset]; });
}

/// _connectQ: 한 기기의 링크 해제 — IO 중단, USB 객체 destroy(Seize 반납 → 재오픈 가능), 감시 복귀.
/// 다른 기기의 링크는 그대로 유지된다. reset=YES면 포트 리셋으로 재열거를 강제(IO 에러로 죽은 경우).
- (void)unlinkLocked:(NSString *)deviceID reason:(NSString *)reason reset:(BOOL)reset {
    PeriAoaLink *link = [self takeLink:deviceID];
    if (link) {
        link.alive = NO;
        [self releaseUsbObjectsOf:link reset:reset];
        NSLog(@"[AOA][%@] 링크 해제: %@ (남은 %lu대)", deviceID, reason, (unsigned long)[self allLinks].count);
        if (self.onDisconnected) self.onDisconnected(deviceID, reason);
    }
    if (self.running) [self scheduleRetry];
}

/// 파이프 abort → (필요시 포트 리셋) → iface/device destroy → nil.
/// reset: IO 에러로 죽은 링크는 엔드포인트 halt/toggle이 남아 재오픈해도 IO가 계속 실패할 수 있다.
/// 포트 리셋은 재열거를 강제해 상태를 확실히 초기화하고, **Android도 진짜 detach/attach를 보게 되어**
/// ACTION_USB_ACCESSORY_ATTACHED로 AOA 전환 쿨다운이 풀린다 — 리셋이 없으면 Mac만 재링크되고
/// Android는 쿨다운/유령 액세서리에 갇혀 ClientHello를 안 보내 세션이 영영 안 살아난다.
- (void)releaseUsbObjectsOf:(PeriAoaLink *)link reset:(BOOL)reset {
    NSError *e = nil;
    link.alive = NO;
    [link.inPipe abortWithError:&e];
    [link.outPipe abortWithError:&e];
    if (reset && link.device) {
        NSError *re = nil;
        NSLog(@"[AOA][%@] 포트 리셋 → 재열거 %@", link.deviceID,
              [link.device resetWithError:&re] ? @"요청됨" : @"실패(기기 없음)");
    }
    [link.iface destroy];
    [link.device destroy];
    link.inPipe = nil; link.outPipe = nil; link.iface = nil; link.device = nil;
}

// MARK: - helpers

/// 기기 식별자 = USB locationID(포트 경로 기반, 액세서리 모드 재열거에도 동일 유지).
/// 같은 폰을 다른 포트에 꽂으면 다른 ID지만, 세션 신원은 상위 cert fingerprint가 담당하므로 무방.
- (nullable NSString *)deviceIDOf:(io_service_t)svc {
    uint32_t loc = 0;
    CFTypeRef p = IORegistryEntryCreateCFProperty(svc, CFSTR("locationID"), kCFAllocatorDefault, 0);
    if (p) {
        if (CFGetTypeID(p) == CFNumberGetTypeID())
            CFNumberGetValue((CFNumberRef)p, kCFNumberSInt32Type, &loc);
        CFRelease(p);
    }
    return loc ? [NSString stringWithFormat:@"usb-%08x", loc] : nil;
}

- (uint16_t)regU16:(io_service_t)svc key:(CFStringRef)key {
    uint16_t v = 0;
    CFTypeRef p = IORegistryEntryCreateCFProperty(svc, key, kCFAllocatorDefault, 0);
    if (p) {
        if (CFGetTypeID(p) == CFNumberGetTypeID()) {
            int iv = 0; CFNumberGetValue((CFNumberRef)p, kCFNumberIntType, &iv); v = (uint16_t)iv;
        }
        CFRelease(p);
    }
    return v;
}

@end
