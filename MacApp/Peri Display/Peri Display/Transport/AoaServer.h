//
//  AoaServer.h
//  Peri Display
//
//  AOA(USB) Bearer (Mac = USB 호스트). IOUSBHost로 Android 기기를 찾아 AOA 액세서리 모드로
//  전환하고, 재열거된 액세서리의 bulk IN/OUT 파이프를 열어 *신뢰·순서보장 바이트 파이프*를
//  제공한다. 프레이밍/암호화는 상위(Swift)가 담당 — 이 클래스는 raw 바이트만 read/write.
//
//  sendDeviceRequest/sendIORequestWithData가 NS_REFINED_FOR_SWIFT라 Swift 브리징이 불투명 →
//  IOUSBHost/IOKit C·ObjC API를 ObjC에서 직접 호출(PeriVirtualDisplay 선례).
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface PeriAoaServer : NSObject

/// 멀티 기기: 모든 콜백에 deviceID(USB locationID 기반, 포트별 안정 식별자)가 실린다.
/// 상위(Swift)는 deviceID별로 FrameReader/세션을 분리해 N대를 동시에 구동한다.

/// bulk IN에서 받은 raw 바이트 (기기별 전용 read 스레드에서 호출). 상위가 FrameReader로 재조립.
@property(nonatomic, copy, nullable) void (^onData)(NSString *deviceID, NSData *chunk);
/// 해당 기기의 액세서리 파이프가 열려 링크 준비됨. (재연결될 때마다 다시 호출될 수 있음)
@property(nonatomic, copy, nullable) void (^onConnected)(NSString *deviceID);
/// 해당 기기 링크 끊김(분리/에러). reason은 로그용. 감시는 계속되므로 재연결 시 onConnected 재호출.
@property(nonatomic, copy, nullable) void (^onDisconnected)(NSString *deviceID, NSString *reason);

/// USB 부착 상시 감시 시작. Android 후보 발견 → AOA 핸드셰이크(getProtocol/strings/start) →
/// 재열거 → bulk 파이프 오픈 → read 루프. 링크가 끊겨도 감시로 복귀해 자동 재연결.
/// 여러 기기를 동시에 링크한다(기기별 독립 링크·read/write 큐).
/// 백그라운드에서 진행. 이미 진행 중이면 무시.
- (void)start;

/// raw 바이트 송신 (해당 기기의 bulk OUT). 프레이밍은 호출자 책임.
- (void)sendData:(NSData *)data toDevice:(NSString *)deviceID;

- (void)stop;

#pragma mark - HID (AOAv2, EP0 제어 전송)

/// 이 기기가 보고한 AOA 프로토콜 버전. **HID는 2 이상에서만 동작한다.** 미링크/미확인은 0.
///
/// HID는 bulk 파이프를 타지 않고 EP0 제어 전송으로만 오간다 — 즉 우리 암호 세션 바깥이다.
/// 그래서 이 클래스가 프레임 계층과 무관하게 직접 제공한다.
- (int)protocolVersionOf:(NSString *)deviceID;

/// HID 기기 등록 — Req 54(REGISTER_HID) + Req 56(SET_HID_REPORT_DESC).
/// 디스크립터는 필요하면 여러 패킷으로 나눠 보낸다(wIndex = 오프셋).
///
/// ⚠ **Android는 이걸 거부할 수 없다** — 커널이 받는다. 페어링·TrustStore가 EP0를 막지 못하므로
/// 호출자가 "인증된 세션 뒤에만 등록"을 스스로 지켜야 한다. 세션이 끝나면 반드시 unregister.
- (BOOL)registerHid:(uint16_t)hidID descriptor:(NSData *)descriptor onDevice:(NSString *)deviceID;

/// HID 입력 리포트 송신 (Req 57). 기기별 전용 큐에서 직렬 실행 — 호출은 논블로킹.
/// bulk 송신 큐와 분리돼 있어 영상 송출을 막지 않는다.
- (void)sendHidEvent:(NSData *)report hid:(uint16_t)hidID toDevice:(NSString *)deviceID;

/// HID 기기 해제 (Req 55). 등록한 쪽이 반드시 짝을 맞춰 부른다.
- (void)unregisterHid:(uint16_t)hidID onDevice:(NSString *)deviceID;

@end

NS_ASSUME_NONNULL_END
