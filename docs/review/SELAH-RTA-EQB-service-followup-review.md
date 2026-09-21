# EQ-B 후속·포그라운드 서비스·녹음 풀 독립 검토

2026-09-22 / HEAD f60ca09
주 요청 범위: 1cf0a98..d60bc59. 첨부 화면의 녹음 3단계 f60ca09도 추가 검토했다. 요청서의 「다음 단계: 녹음 3단계 미착수」는 현재 HEAD보다 오래된 설명이다.

## 판정

**Critical/High 발견 없음. 신규 Medium 3건.** EQB01·EQB02는 해결 확인. EQ-B 카탈로그를 EQ-C에 사용하는 것은 가능하나, 이번 백그라운드 측정 변경의 완료 승인과 녹음 풀의 실제 연결 승인은 아래 문제를 수정할 때까지 보류한다.

## FS01 — Medium: 캡처 실패·자동 종료에서 포그라운드 서비스가 남음

위치:
- app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt:319–325,453–461
- app/src/main/java/kr/joa/selahrta/ui/CaptureController.kt:334–355,358–410
- app/src/main/java/kr/joa/selahrta/audio/CaptureService.kt:40–62

근거: ViewModel.start는 서비스를 요청한 뒤 Controller.start를 호출한다. 입력 없음, open 실패는 Controller 내부에서 Failed로 끝나며 서비스를 내리지 않는다. read 오류, USB 분리의 Pause 정책, 자동 재시도 한도 초과도 Controller 내부 stop으로 끝난다. onStoppedHook은 보정 구독만 해제한다. 서비스 종료는 ViewModel.stop/onCleared에만 있다. 따라서 컨트롤러가 정상적으로 실패를 처리해도 서비스는 「재고 있습니다」 알림과 함께 남는다. Android의 started service는 단순히 캡처가 멎었다는 이유로 종료되지 않는다. [공식 서비스 수명주기](https://developer.android.com/develop/background-work/services)

재현 절차/논리: 서비스 시작 성공 → mic.open이 OpenResult.Failed 반환 → 화면은 Failed, 서비스 stop 호출 0회. 또는 Running → onCaptureEnded(ReadError) → Controller.stop → Failed. 역시 서비스 stop 호출 없음. 이번에는 실제 Android에서 이 경로를 실행하지 않았으며, 호출 경로의 정적 확인에 근거한다.

사용자 영향: 측정은 끝났는데 백그라운드에서는 측정 중인 것처럼 보이고 불필요한 포그라운드 상태가 지속된다. 캡처가 계속 살아 있다는 주장이나 새로운 마이크 누수 판정은 아니다.

권장 수정: 서비스와 캡처의 최종 상태를 한 수명주기 조정자에서 맞춘다. 시작 실패/최종 종료는 서비스를 해제하고, 자동 기기 전환의 일시 stop→start 동안은 서비스가 유지되도록 한다. 기존 onStoppedHook에 무조건 서비스 stop만 추가하면 백그라운드 자동 전환에서 서비스 보호를 잃을 수 있으므로 피한다. 서비스 시작 요청/승격 실패도 사용자에게 실패 상태로 전달하고 캡처와 함께 정리한다.

회귀 시험: 입력 없음, open 실패, read 오류, USB Pause, fallback 성공/실패/한도 초과, 수동 stop, onCleared 각각에서 캡처와 서비스 최종 상태가 일치할 것. Fake service boundary를 실제 ViewModel/조정자에 주입해 검사하고 Android에서는 서비스 존재·알림 상태도 확인한다.

## FS02 — Medium: 알림 권한은 선언했지만 요청하지 않아 신규 설치의 종료 알림이 보이지 않음

위치:
- app/src/main/AndroidManifest.xml:23
- app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:101–124
- app/src/main/java/kr/joa/selahrta/audio/CaptureService.kt:88–108
- app/build.gradle.kts:targetSdk=36

근거: app/src 전체에서 POST_NOTIFICATIONS는 manifest 선언만 있다. 런타임 권한 launcher는 RECORD_AUDIO만 요청한다. Android 13 이상 신규 설치는 알림이 기본 off이며, target 33 이상에서는 앱이 요청 시점을 정한다. 선언이나 채널 생성만으로 허용 대화상자가 뜨지 않는다. [Android 알림 권한 문서](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

재현 조건: Android 13+ 신규 설치, 설정에서 알림을 따로 허용하지 않음 → 마이크 권한 허용 → 측정 시작 → 홈. 포그라운드 서비스 자체는 시작할 수 있지만 알림 서랍의 「측정 종료」 액션은 표시되지 않는다. 이는 요청서의 「사용자가 거부한 경우」보다 넓으며, 허용 여부를 묻지도 않은 기본 경로다. 실제 기기 신규 설치 실험은 이번에 하지 않았다.

사용자 영향: 백그라운드 측정을 종료하려면 앱으로 돌아와야 한다. 시스템의 활성 앱/Task Manager 표시와 앱 알림의 종료 버튼은 다르므로 「종료 방법이 전혀 없다」는 뜻은 아니다.

권장 수정: 백그라운드 측정 시작 맥락에서 알림 권한을 요청하고, 거부해도 측정은 허용한다는 정책을 유지한다. 거부/채널 차단 때는 알림 버튼이 없으며 앱에서 종료할 수 있음을 안내한다. 기존 허용 사용자에게 반복 요청하지 않는다.

회귀 시험: 신규 설치·허용·거부·대화상자 취소·설정에서 차단·이미 허용을 구분한다. 허용 시 알림 액션이 캡처/서비스를 모두 종료하고, 미허용 시 화면 복귀 후 종료가 가능함을 확인한다.

## REC01 — Medium: 실패·닫힘 반환 경로가 풀의 단일 반환자 전제를 깨뜨림

위치:
- app/src/main/java/kr/joa/selahrta/recording/PacketPipe.kt:43,65–70
- app/src/test/java/kr/joa/selahrta/recording/PacketPipeTest.kt:47–61,278

BufferPool.release는 `g = given; free[g % count] = index; given = g + 1`이며 단일 writer 반환을 전제로 한다. 그런데 녹음 결합 방법을 보여 주는 offer는 복사 실패 또는 admission close면 **오디오 생산자에서 release**한다. 이전에 게시한 버퍼를 writer가 반환 중이면 두 스레드가 같은 given을 읽고 같은 칸을 덮는다. Volatile은 가시성을 보장하지만 이 복합 갱신을 원자화하지 않는다.

독립 재현: 원본 BufferPool을 그대로 사용. 버퍼 두 개를 acquire한 뒤 writer 반환과 생산자 취소 반환에 해당하는 두 스레드를 barrier에서 동시에 출발시켜 각각 다른 버퍼를 release했다. **30,000회 중 1회, 두 반환이 모두 끝났는데 inUse=1**이었다. 호출 사이 공유 변수의 가시성은 barrier로 맞췄다. 정상 SPSC 사용 자체의 실패가 아니라, 문서화된 실패/닫힘 경로가 요구하는 사용 방식의 실패다.

추가 확인: 두 버퍼가 빌려진 상태에서 같은 번호를 두 번 release하면 현재 「두 번 반환」 검사도 통과하고 이후 acquire가 `0,0`을 반환한다. 이것은 잘못된 호출에 대한 방어 검사 공백이며, 올바른 단일 반환자가 저절로 같은 번호를 반환한다는 뜻은 아니다.

사용자 영향: 현재 PacketPipe는 실제 캡처/writer에 연결되지 않아 기존 녹음 파일이 손상됐다고 판단하지 않는다. 이 offer 순서대로 연결하면 종료·실패 시 버퍼가 유실돼 풀이 마르거나, 이후 소유권 오류가 생길 수 있다. 연결 전 차단 항목이다.

권장 수정: 생산자의 미게시 버퍼 취소를 writer의 free-ring 반환과 분리한다. 예를 들어 생산자가 보유한 미게시 버퍼를 별도 producer-owned 자리에서 재사용하고 free-ring에는 writer만 반환하게 한다. 종료 시 인수인계 규약까지 정할 것. 단순 given.incrementAndGet 치환만으로는 슬롯 게시 순서가 해결되지 않는다. 실제 offer/취소 로직을 production 경계로 옮겨 같은 코드를 시험한다.

회귀 시험: writer가 이전 버퍼를 반환하는 바로 그 순간 copy 실패/close 거절을 주입한다. 모든 번호가 정확히 한 번 회수되고 중복 대여가 없으며 모든 스레드 종료 후 inUse=0임을 확인한다. 번호 발급 후 close는 게시 완료 후 drain까지 확인한다. 고부하 반복 결과는 보조 증거이며, 임계 갱신 사이를 고정한 결정적 시험을 추가하는 것이 좋다.

## 기존 지적과 질문에 대한 답

- **EQB01 해결:** 7개 성격 대역은 유지했고 별도 harsh 대역이 명세 범위로 분리됐다. 기존 probe의 `SPEC_BOUNDARY_MISMATCHES=7/7` 출력은 옛 attack/rhythm 대역을 증상 기대값과 비교하는 흔적이다. 이제 그 영역의 symptomTags는 비어 있고 `STILL_MERGED=0/7`; 새 harsh 기대값도 CatalogBoundaryTest가 확인하므로 이 출력은 미수정 증거가 아니다. 보고용 출력은 혼동되지 않게 바꾸는 편이 좋다.
- **EQB02 해결:** 27개 프로필의 필터/대체/bypass와 영역 경계, Hysteresis, 키 집합, 거절 입력 시험을 확인했다. 이번에는 구현자가 보고한 변이 실행을 다시 하지 않았으며 현재 소스와 기대값 및 시험 통과를 독립 확인했다.
- Kotlin 결정의 생성 시점 검증 한계가 ADR에 추가됐고, 크런치 문구·다이내믹스 출처·FlowRow 수정도 확인했다.
- **SignalPlayer 단언 변경 수용:** release가 먼저 실행되면 stopSink가 stop을 생략하는 현재 계약과 맞는다. release 단언은 여전히 남아 있어 stop 단언을 없앤 것이 자원 해제 확인까지 제거한 것은 아니다. 이번 200회 반복에서도 stopSkipped=2, notReleased=0이었다. 다만 `released` 확인 뒤의 `stopped || released`는 논리적으로 중복이다. fake sink 통과를 실제 스피커의 무음 입증으로 표현하지 말 것. 200회 반복도 모든 스케줄의 증명은 아니며, release 선행 순서를 latch로 고정한 사례가 더 명확하다.
- **색만 표시:** 요청서는 별도 사용자 결정이라고 보고한다. 이번 검토가 그 선택을 되돌리는 작업 승인은 아니다. 화면 배지 제거와 접근성 의미 제거는 구분할 수 있으므로 TalkBack에는 상태 설명을 유지하는 편이 낫다. 명세의 색 이외 표현 요구와 실제 제품 결정은 ADR/명세에서 일치시켜야 한다. A 가중치 조건과 참고값 안내가 설정에 남는 것은 확인했지만, 현장 청감/색각 접근성을 승인하지 않는다.
- **EQ-C 착수:** 카탈로그 문제는 닫혔다. EQ-C 작업 자체는 가능하나 서비스 변경이 안전하다는 판정과 묶지 말 것. BandPowerSink 연결 시 가이드 on/off 결과 동일성, 평활 전 파워, raw/corrected 분리, 시간·세대, 유실 정책을 별도로 검증해야 한다.

## 실행 범위와 남은 위험

- Kotlin/JVM 독립 컴파일: 카탈로그 23건 + 이식 경계 probe 1건 + PacketPipe 10건 = **34건 통과**.
- SignalPlayerTest 9건 + StopContractTest 1건 = **10건 통과**. 실제 AudioTrack 대신 기존 fake sink 사용. Android API는 로컬 단위시험용 jar를 사용했으며 실제 장치 출력 검증이 아니다.
- 위 합계 **고유 44건 통과**. 전체 374건·clean build·lint는 이번에 재실행하지 않았다.
- 독립 PoolProbe: 동시 반환 30,000회 중 반환 유실 1회. 두 대여 중 중복 반환 허용/같은 번호 재대여도 재현.
- 서비스는 코드와 공식 Android 계약을 대조했다. 실기기·에뮬레이터에서 홈/화면 꺼짐/2시간/절전/권한 회수/알림 액션을 재실행하지 않았다. 서비스 경계에 대한 신규 자동 시험도 저장소에는 없다.
- 서비스 시작은 비동기 요청이고 controller.start는 그 직후 실행된다. 실제 foreground 승격 실패·예외 전달과 순서를 시험해야 한다. foreground 서비스는 두 시간 동안 CPU/마이크가 끊기지 않는다는 실측 보증이 아니다.
- onCleared만으로 최근 앱 제거 및 모든 프로세스 종료 경로를 보증하지 말 것. 서비스 onTaskRemoved 정책, 구성 변경 중 ViewModel 유지, 강제 종료/프로세스 사망을 나눠 확인해야 한다. [Service API 수명주기](https://developer.android.com/reference/android/app/Service)
- CaptureServiceBridge의 SharedFlow는 replay=0이고 tryEmit 결과를 버린다. 등록된 단일 ViewModel의 정상 경로에는 적합하지만, 구독자 없음·대기 중 새 세션·다중 owner에서 종료 신호의 소유권은 시험되지 않았다. 단순 singleton이라는 이유만으로 새로운 결함으로 세지는 않았다.
- ANCHOR_LOG=false는 로그만 생략한다. anchor 수집 계약 전체를 제거한 변경은 아니다.
- 저장소 production/test 파일, 기기 권한과 설정은 변경하지 않았다. 종료 시 working tree clean.
