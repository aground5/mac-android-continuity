//
//  Peri-Display-Bridging-Header.h
//  Peri Display
//
//  ObjC → Swift 노출.
//

#import "VirtualDisplay/PeriVirtualDisplay.h"
#import "Transport/AoaServer.h"

// BLE 페어링의 PAKE. Android와 **같은 C**를 돌려 상호운용이 구현 차이로 깨지지 않게 한다.
// (헤더 경로는 HEADER_SEARCH_PATHS — ThirdParty/sodium/include, ThirdParty/spake2-ee)
#import <sodium.h>
#import "crypto_spake.h"
