# SELAH RTA Phase 4–8 독립 검증

검증일: 2026-09-21 · 검증자: Codex · 구현자: Claude Code

**판정: 승인 보류. 지정 범위에서 High 3건, Medium 9건을 확인했다. 별도로 이전 단계에서 이어진 High 수명주기 결함 1건을 확인했다. Critical은 확인하지 못했다. 명세 24.1에 따라 High 수정 및 재검증 전에는 다음 Phase 진행 게이트를 통과시키지 않는다.**

검증 기준은 `dc4e474~1..392c3ec`, 즉 `8276bac6fb1805ea5a960a3b2c88ccc6d63e3b31` 이후부터 `392c3ec`까지다. 5개 구현 커밋, 38파일, +5118/−207을 확인했다. 검사 당시 원본 HEAD는 `49826fc`이며, 대상 이후 변경은 검증 요청 문서 추가뿐이었다. 원본 working tree는 시작·마지막 확인 시 모두 clean이었다. production 소스는 수정하지 않았다.

검증 요청서와 마스터 명세 v1.2를 읽고, 대상 커밋을 별도 사본으로 추출하여 DSP 소스·테스트를 실행했다. 아래 위치는 모두 **392c3ec의 저장소 상대 경로와 줄 번호**다. `app/…/`는 `app/src/main/java/kr/joa/selahrta/`, `dsp/…/`는 `dsp/src/main/kotlin/kr/joa/selahrta/dsp/`를 뜻한다.

## 실행 근거와 검증 한계

| 검증 | 결과 |
|---|---|
| 대상 커밋의 기존 DSP 단위 테스트 | **140/140 통과**. Kotlin 2.2.20, JDK 21, JVM target 17, JUnit 4.13.2로 소스부터 독립 컴파일·실행 |
| 추가 수학 회귀 테스트 | **4개 중 3개 실패**, 창을 적용한 신호에 대한 Parseval 검증 1개 통과 |
| 추가 수치 탐색 | A/C 응답 44.1/48 kHz, 저역 밴드 손실, 보정 저장 파일명 충돌, 시작 위상 차이 확인 |
| 구현자 보고의 168회 테스트 | DSP 140 + 입력 선택 14 × debug/release 2회로 기존 XML과 일치. **168개의 서로 다른 테스트가 아님** |
| Android 앱 테스트·전체 Gradle build/lint 재실행 | 수행하지 못함. SDK `android.jar` 읽기가 차단됐고 읽기 권한 부여 후에도 OS 접근 거부가 지속됨 |
| 실제 USB/내장 라우팅, Android lifecycle, Compose 렌더링 | 실기기 재현 없음. 코드 경로와 공식 Android API 계약으로 검토 |
| 60분 부하·열·메모리·capture dropout, 기준 소음계 비교 | 미실행. 이번 보고서는 해당 항목의 통과를 의미하지 않음 |

첨부 `ReviewProbe.kt`는 실제 대상 DSP 클래스를 호출한다. `verification-log.txt`에 수치와 실패 메시지를 보존했다. `run-verification.ps1`은 지정 커밋을 별도 폴더에 추출하고 DSP 테스트와 재현 코드를 실행한다. 현 구현에서 추가 회귀 테스트 실패로 종료 코드 1이 나오는 것이 예상 결과다. 전체 Android build 성공으로 해석하면 안 된다.

## 지정 범위의 결함

### R01 · High — 실제 라우팅을 확인하기 전에 보정 기기를 확정한다

**위치:** `app/…/audio/MicSource.kt:166–189, 201–214`; `app/…/ui/CaptureViewModel.kt:253–258, 325, 378`.

**근거/재현:** `tryOpen()`은 `startRecording()` 이전에 `rec.routedDevice`를 읽는다. Android는 녹음 중이 아닐 때 이 값이 null이라고 명시한다. 따라서 `routedInfo`가 없으면 요청한 target의 identity, kind, label을 실제 열린 값처럼 저장한다. `setPreferredDevice()`는 실제 라우팅을 보장하지 않는다. USB를 요청했으나 내장으로 연결되거나 하단 대신 후면이 선택되면 요청한 장치의 보정값으로 측정한다. [Android AudioRecord 계약](https://developer.android.com/reference/android/media/AudioRecord#getRoutedDevice()).

시작 후 listener도 이 문제를 해결하지 못한다. `deviceLabel`에는 `SM-S918N (하단)` 같은 표시명이 들어가지만 비교 대상은 순수 `productName`이므로 같은 경로도 변경으로 오인한다. 같은 이름의 서로 다른 마이크는 반대로 식별할 수 없다. 변경을 감지해도 ViewModel은 안내문만 쓰고 기존 format·보정·누적 DSP를 유지한다. disconnect 감시도 실제 장치가 아니라 `openingKey`를 본다.

**사용자 영향:** 화면에 표시한 마이크와 실제 PCM 소스가 다르고, 서로 다른 감도의 마이크에 기존 SPL offset/주파수 곡선을 적용할 수 있다. 장치 제거 정책도 잘못된 장치를 감시할 수 있다.

**권장 수정:** 녹음 시작 후 확인된 route identity로 format을 확정한다. route가 미확정이면 보정된 측정값 발행을 보류한다. 표시명이 아닌 id/주소 등 canonical identity를 비교하고, 실제 route 변경 시 캡처 세대·DSP·프로필을 원자적으로 교체하거나 측정을 멈춘다.

**회귀 테스트:** preferred A/actual B, preferred 요청 실패, 최초 route null, 이름이 같은 하단·후면, 동일 이름 USB 두 대, 녹음 중 강제 reroute, 실제 장치 제거. 각 프레임의 device/profile identity가 일치해야 한다.

### R02 · High — 캡처 스레드가 최신 설정·보정을 과거 StateFlow 값으로 덮어쓴다

**위치:** `app/…/ui/CaptureViewModel.kt:173–177, 189–196, 406–440, 455–466, 570–580`.

**논리적 재현:** 캡처 스레드가 `prev = _state.value`를 읽는다 → main collector가 새 보정/설정을 저장한다 → 캡처가 `prev.copy(...)`를 전체 `_state.value`에 대입한다. 새 보정/설정이 소실된다. StateFlow의 개별 get/set이 안전해도 이 read-copy-write 조합은 원자적이지 않다. DataStore가 같은 값을 다시 emit하지 않으면 원복 상태가 계속될 수 있다.

또한 main에서 engine 참조를 교체하거나 `resetPeaks()`를 호출하는 동안 캡처가 같은 가변 DSP 객체를 처리한다. `curveBandGains`도 별도 mutable 필드라 state.curve와 한 snapshot으로 묶이지 않는다.

**사용자 영향:** 사용자가 보정했는데 옛 offset으로 복귀하거나, UI의 Fast/Slow·Leq 설정과 실제 엔진 설정이 달라진다. 일회성 화면 깜박임에 한정되지 않는다.

**권장 수정:** 캡처/DSP 소유 스레드를 하나로 정하고 설정·reset을 명령으로 전달한다. UI는 immutable measurement snapshot을 받아 main에서 합성한다. 부분 갱신에 `update`/CAS를 쓰더라도 값 계산에 사용한 프로필·설정과 결과의 세대를 함께 검증해야 한다.

**회귀 테스트:** barrier/latch로 위 interleaving을 강제한다. 보정 저장, A↔C, Fast↔Slow, Leq 창 변경, reset, stop/restart와 frame publication을 교차시켜 변경 소실과 오래된 세대의 프레임이 없는지 확인한다.

### R03 · High — 다른 마이크로 다시 시작할 때 이전 보정이 남아 있다

**위치:** `app/…/ui/CaptureViewModel.kt:345–379, 452–467, 606–616`.

**논리적 재현:** A 마이크에 global offset과 curve 적용 → stop → B로 start. stop은 watcher를 취소하지만 `calibration`, `curve`, `curveBandGains`를 지우지 않는다. start도 이 필드를 보존한 상태로 새 source를 시작하고, B 프로필은 비동기로 읽는다. B의 DataStore/file 읽기를 지연시키면 첫 PCM들은 A 보정으로 발행된다. R02의 lost update가 없어도 발생한다.

**사용자 영향:** USB→내장 fallback 직후 이전 USB 보정값으로 잘못된 음압을 보정된 값처럼 표시한다. 두 watcher의 응답 시점이 달라 global과 frequency profile이 서로 다른 장치의 조합이 될 수도 있다.

**권장 수정:** 장치 변경 시 이전 profile/gains를 즉시 무효화하고, 동일 장치·입력 구성의 global+curve snapshot을 준비한 후 측정을 발행한다. 로딩 중은 명시적으로 표시하고 generation token으로 오래된 응답을 폐기한다.

**회귀 테스트:** offset 차이 20 dB인 A/B, A에만 curve가 있는 경우, B profile 읽기 지연·실패, 연속 A→B→A 전환. 새 장치 프레임에 이전 프로필이 단 한 번도 붙지 않아야 한다.

### R04 · Medium — 주파수 보정을 광대역 SPL에서 제외한 정확도 근거가 성립하지 않는다

**위치:** `app/…/calibration/CurveStore.kt:38–56`; `app/…/ui/CaptureViewModel.kt:420–439, 640–650`.

**근거/재현:** 주파수 응답 보정은 RTA 표시값에서만 빼고 A/C/Z, Leq, MAX, Peak 계산에는 들어가지 않는다. 이는 명세 6장의 calibration correction → weighting 순서와 다르다. “±2 dB 마이크이면 광대역 영향 <0.5 dB, global offset이 흡수”는 일반적으로 틀리다. 1 kHz 응답 0 dB, 125 Hz 응답 +2 dB인 마이크를 1 kHz로 global 보정한 뒤 125 Hz 위주 신호를 재면 잔여 오차는 +2 dB다. 보정 기준 대역 −2 dB/측정 대역 +2 dB이면 차이는 4 dB까지 된다.

**사용자 영향:** 주파수 파일을 가져와도 큰 SPL와 Leq는 계속 스펙트럼 의존 오차를 가진다. C−A 역시 global offset만 상쇄할 뿐 마이크 응답 영향까지 없어지는 것은 아니다.

**권장 수정:** 보정 전용 FIR뿐 아니라 안정적인 IIR fitting도 검토할 수 있다. FFT/bin별 역응답 전력 보정은 Leq/대역 에너지의 대안이지만, 현재 31밴드 snapshot을 재합산하는 것만으로 waveform Peak나 정확한 sample-wise Fast/Slow를 재현한다고 주장하지 않는다. 현재 범위를 유지하려면 정확도 범위·미보정 지표를 명세/ADR에 명시하고 측정 화면에서도 알린다.

**회귀 테스트:** 기준 주파수와 측정 주파수가 다른 ±2 dB 응답, 주파수 혼합비 변화, 동일 PCM의 corrected/uncorrected SPL·Leq·RTA. global calibration과 frequency curve의 기준점이 중복 적용되지 않는지도 검증한다.

### R05 · Medium — 밴드 보정이 실제 신호 분포 대신 세 지점 응답의 평균을 뺀다

**위치:** `dsp/…/CalibrationCurve.kt:68–76`; `app/…/ui/CaptureViewModel.kt:645–649`.

**실행 재현:** 1 kHz 밴드의 아래끝/중심/위끝 응답을 −6/0/+6 dB로 둔다. 중심 1 kHz 순음의 실제 보정량은 0 dB인데 구현의 `bandGainsDb()`는 **+2.415681 dB**를 만들어 뺀다. 진폭 0.5 순음의 기대 −9.030900 dBFS가 **−11.446582 dBFS**가 된다. `narrowToneMustUseItsOwnFrequencyCorrection` 실패.

수학적으로 필요한 것은 `Σ Pmeasured[k] × 10^(−g(fk)/10)`이다. `Σ Pmeasured[k] / mean(10^(g/10))`는 일반적으로 같지 않다. 단순히 dB 평균을 전력 평균으로 바꿨다는 사실로 올바른 역응답 보정이 되지 않는다.

**사용자 영향:** 가파른 응답이나 notch가 있는 곡선에서 보정 기능 자체가 새로운 dB 오차를 만든다.

**권장 수정:** FFT bin 주파수에서 응답을 보간해 bin power를 보정한 후 밴드 합산한다. 급격한 곡선과 FFT 누설에 따른 분해능 한계는 별도 오차 예산으로 둔다.

**회귀 테스트:** 밴드 중심 및 좌우 순음, 같은 밴드 내 두 순음, 평탄/기울기/notch 곡선. 기준답은 알려진 마이크 전달함수와 원음 에너지로 만든다. 현재 “중심값과 다르다”는 테스트는 정확성 기준이 아니다.

### R06 · Medium — MAX가 블록 마지막 시간가중 값만 저장한다

**위치:** `dsp/…/SplEngine.kt:89–90`; `dsp/…/TimeWeighting.kt:55–59`.

**실행 재현:** 48 kHz, Fast, Z, 1024 샘플 중 index 1만 1.0이고 나머지 0인 PCM을 넣는다. 한 블록 처리 MAX는 **−38.521623 dBFS**, 같은 PCM을 1샘플씩 처리하면 **−37.781874 dBFS**다. **0.739748 dB 과소평가**. `maximumMustNotDependOnBlockPartition` 실패.

**사용자 영향:** 박수·충격음·짧은 transient의 MAX가 블록 경계 위치에 따라 달라진다. waveform Peak를 별도로 보존해도 시간가중 MAX 정의의 오류는 해결되지 않는다.

**권장 수정:** 모든 sample의 시간가중 에너지 중 최대를 추적하거나 `pushBlock`이 마지막 값과 블록 내 최대를 함께 반환하게 한다.

**회귀 테스트:** 동일 PCM을 1/128/1024 및 불규칙 크기로 나누어 A/C/Z·Fast/Slow의 MAX가 동일한지 확인한다. transient를 블록 안에서 이동시킨다.

### R07 · Medium — 시간가중 초기화가 첫 샘플의 제곱을 전체 상태로 주입한다

**위치:** `dsp/…/TimeWeighting.kt:41–50`.

**실행 재현:** Slow 48 kHz의 cold start에서 첫 sample 1.0을 넣으면 상태가 1.0이 된다. 초기 에너지 0의 지수 적분 정의라면 `1−exp(−1/48000) = 0.0000208331163`이다. 첫 sample만 비교하면 **46.81 dB** 차이다. 실제 1 kHz, 진폭 0.5의 첫 1024샘플에서도 시작 위상 0과 π/2에 따라 −25.7756/−6.0668 dBFS로 **19.71 dB** 달라졌다. 정상상태 RMS는 −9.0309 dBFS다.

**사용자 영향:** 시작·재시작·Slow 전환 직후 한 sample에 좌우되는 값이 나오고, 그 과대값이 MAX에 남을 수 있다. 기존 step test는 먼저 무음을 넣어 이 경로를 피한다.

**권장 수정:** 초기 에너지를 0으로 정의하고 settling 상태를 노출한다. 초기 추정값을 쓰려면 구간 에너지로 계산하고 warm-up 값을 MAX/보정 기준에 포함하지 않는 등 별도 정책을 정의한다.

**회귀 테스트:** 최초 sample이 0/최대/음수인 입력, 사인파 시작 위상 sweep, reset 직후, Fast/Slow 초기 응답, 초기 과대 MAX 잔류 여부.

### R08 · Medium — 63/80 Hz를 충분히 분해된 밴드로 표시하지만 순음 에너지가 크게 빠진다

**위치:** `dsp/…/BandAnalyzer.kt:31–54`; `app/…/ui/screens/AnalyzeScreens.kt:47, 63–66`.

**실행 재현:** 4096-point Hann, 48 kHz에서 63 Hz 순음의 해당 밴드 오차는 **−2.187537 dB**, 80 Hz는 **−1.121973 dB**인데 둘 다 `bandResolved=true`다. 화면은 63 Hz 아래만 불확실하다고 알린다. 한 FFT bin보다 넓다는 조건은 Hann의 주엽/누설을 고려한 정확도 기준이 아니다.

**사용자 영향:** 저역 밸런스·보정을 판단하는 주요 밴드를 충분히 분해된 수치로 받아들이지만 중심 순음부터 약 2.2 dB 낮게 나온다.

**권장 수정:** 폭 기반 true/false 대신 창의 주파수 응답과 허용오차에 근거한 품질 표시를 한다. 저역 정확도가 필요하면 긴 FFT, multirate 분석 또는 octave filter bank를 검토한다. 현 알고리즘을 유지하면 63/80 Hz까지의 정확도 제한을 명시한다.

**회귀 테스트:** 20–200 Hz 중심 순음, 밴드 경계 sweep, 시작 위상, pink/white noise 장기 평균. 밴드 합 보존뿐 아니라 각 밴드의 오차를 확인한다.

### R09 · Medium — 서로 다른 장치의 주파수 곡선 파일명이 충돌한다

**위치:** `app/…/calibration/CurveStore.kt:74–75, 104, 118`.

**재현:** storage key의 비영숫자를 모두 `_`로 치환한다. `cal|Usb|Mic A|usb:1|Unprocessed`와 `cal|Usb|Mic_A|usb:1|Unprocessed`는 동일한 `cal_Usb_Mic_A_usb_1_Unprocessed.cal`이 된다. 서로 다른 한글 이름도 길이가 같으면 충돌할 수 있다. Preferences의 메타데이터 key는 다르지만 실제 파일은 같다.

**사용자 영향:** B의 곡선 저장으로 A의 곡선을 덮어쓰거나, 한 장치의 보정 삭제로 다른 장치 보정 파일을 지운다. 파일 이름/점 개수 메타데이터와 실제 곡선이 달라질 수 있다.

**권장 수정:** canonical key의 SHA-256 등 충돌 저항 식별자를 파일명으로 사용하고 원래 key를 메타데이터에 보존한다. 임시 파일→atomic rename으로 저장한다.

**회귀 테스트:** 공백/underscore, colon/slash, Unicode, 동일 product name·다른 주소의 key를 저장·읽기·삭제해 파일 격리를 확인한다.

### R10 · Medium — 무가중 waveform Peak를 dBA/dBC로 표시한다

**위치:** `dsp/…/SplEngine.kt:74–80, 106`; `app/…/ui/screens/MeasureScreen.kt:252–255`.

**근거/재현:** Peak는 모든 엔진에서 가중 전 PCM 최대값이다. 화면은 현재 선택한 `weighting.unitSuffix`를 붙인다. 125 Hz 순음을 입력하고 A/C로 바꾸면 Peak 숫자는 동일한데 단위만 dBA/dBC가 된다. A 응답은 약 −16.1 dB라 A-weighted peak와 원신호 peak를 혼동할 수 없다.

**사용자 영향:** MAX, Leq, Peak가 같은 weighting의 수치라고 오해한다. 특히 설정 화면의 “순간 피크 … dBA” 참고범위와 의미가 맞지 않는다.

**권장 수정:** 현재 구현을 유지하면 `무가중 waveform peak`/적절한 Z 표기를 고정한다. C-weighted Peak가 필요하면 별도 filtered waveform maximum을 계산하되 clipping 판정은 원본에서 유지한다.

**회귀 테스트:** 125 Hz/1 kHz에서 A/C/Z 전환 시 수치와 단위가 함께 정의에 맞는지 DSP+UI 검사. clipping 하한 표시는 독립적으로 확인한다.

### R11 · Medium — 입력 선택과 자동 전환 UI가 실제 실행 정책을 반영하지 않는다

**위치:** `app/…/ui/CaptureViewModel.kt:173–177, 205–206, 214–248, 261–266`; `app/…/ui/screens/HistorySettingsScreens.kt:95–108, 271–278, 335`.

**재현:** 내장으로 측정 중 USB를 직접 선택하면 저장된 preferred key와 “사용 중” 표시는 USB로 바뀌지만, `needsEngineRestart`는 입력 변경을 보지 않고 source도 교체하지 않는다. 또 “외부 기기 자동 사용”을 켜고 USB를 꽂아도 `onDeviceListChanged()`는 설정값을 확인하지 않고 재시작 안내만 한다. 명세의 자동 전환과 UI 설명을 충족하지 않는다.

**사용자 영향:** 선택한 입력을 사용한다고 생각하면서 기존 입력을 계속 측정한다. 자동 전환 설정이 기대와 다르게 동작한다. FallBack도 `start()`의 일반 자동 선택 규칙을 그대로 사용하므로 다른 외부 입력이 남아 있으면 “내장 마이크로 전환” 정책 대신 다른 외부 기기를 고를 수 있다.

**권장 수정:** 명시적 선택/자동 연결/분리 fallback을 각각 정의한 상태 전이로 구현한다. 현재 세션에서 전환하지 않는 제품 정책이면 “다음 시작에 사용”으로 표시하고 명세·ADR을 수정한다. “사용 중”은 실제 route만 기준으로 표시한다.

**회귀 테스트:** running/idle 각각의 수동 선택, 자동설정 on/off, 사용자가 내장을 고정한 경우, 외부 두 대 중 하나 분리, Pause/FallBack 정책. 현재 14개 선택 함수 테스트는 실제 ViewModel 전환을 검증하지 않는다.

### R12 · Medium — USB 캡처에서도 상단이 PHONE MIC로 고정된다

**위치:** `app/…/ui/SelahApp.kt:220–224`.

**재현/근거:** `TopBrandBar`가 `capture.opened.micKind` 대신 `MicKind.BuiltIn.badgeKo`를 항상 사용한다. USB format을 주입해도 PHONE MIC가 나온다. 원래 내장 전용 UI가 Phase 6의 USB 지원과 연결되지 않은 통합 결함이다.

**사용자 영향:** 측정 화면에서 입력 종류를 즉시 확인할 수 없다. 명세 11장의 PHONE MIC/USB MIC 명확한 표시 요구를 위반한다.

**권장 수정:** 확인된 active input의 kind/label과 세션 상태로 badge를 그린다. 아직 route가 확인되지 않은 상태와 마지막 측정 장치 표시를 구분한다.

**회귀 테스트:** BuiltIn/USB/미개방/route 미확정/stop의 Compose 상태별 텍스트 검증.

## 이전 단계에서 이어진 수명주기 결함

### L01 · High — read 오류 후 캡처가 끝나도 Running과 마지막 숫자가 유지된다

**위치:** `app/…/audio/MicSource.kt:256–269`; `app/…/ui/CaptureViewModel.kt:382–398, 436, 311–312`.

**범위 구분:** read 오류 처리와 종료 경로는 `8276bac`의 BuiltInMicSource에도 존재한다. Phase 4–8이 새로 만든 회귀라고 보고하지 않는다. 다만 요청한 AudioRecord lifecycle 검증에서 현재 남아 있는 High 결함이다.

**논리적 재현:** `read()`가 `ERROR_DEAD_OBJECT` 같은 음수를 반환 → zero-frame block 한 번 통지 → worker는 break. running flag/record가 정리되지 않고 ViewModel도 실패 상태를 받지 않는다. meter는 `prev.meter`를 유지한다. 에러 block이 UI throttle 안에서 발생하면 diagnostics조차 갱신되지 않을 수 있다. 새 start는 source가 남아 있어 무시된다. Android는 dead object 시 객체 재생성이 필요하다고 명시한다. [공식 오류 정의](https://developer.android.com/reference/android/media/AudioRecord#ERROR_DEAD_OBJECT).

**사용자 영향:** 실제 입력이 끊겼는데 “재고 있습니다”와 마지막 측정값이 계속 보인다. 마이크/효과 객체도 명시적 stop까지 보유한다.

**권장 수정:** sample delivery와 terminal error event를 분리한다. worker의 finally 및 단일 lifecycle owner에서 상태·자원 정리를 보장하고, stale reading임을 표시한다. 재시작 정책을 적용하더라도 새 세대 측정으로 시작한다.

**회귀 테스트:** 마지막 정상 frame 직후/emit 직전 read 오류, ERROR_DEAD_OBJECT, startRecording 실패, 처리 callback 예외. Failed/Idle 전이, release 1회, 후속 start 성공을 확인한다.

## 구현자가 질문한 다섯 곳의 판정

### 1. A/C 고역 오차와 IEC 비교

48 kHz의 A 응답 오차는 요청서 수치와 일치했다. C도 비슷하며 44.1 kHz에서는 더 커진다. 이것만으로 Class 1/2 미달이라고 단정하면 부정확하다.

| Hz | 구현 A 오차 @48 kHz | 구현 C 오차 @48 kHz | 비교 자료의 Class 1 주파수 허용범위 |
|---:|---:|---:|---:|
| 8,000 | −0.591 | −0.594 | −2.5 ~ +1.5 dB |
| 10,000 | −1.208 | −1.224 | −3.0 ~ +2.0 dB |
| 12,500 | −2.625 | −2.649 | −5.0 ~ +2.0 dB |
| 16,000 | −6.540 | −6.570 | −16.0 ~ +2.5 dB |
| 20,000 | −15.889 | −15.922 | 하한 없음 ~ +3.0 dB |

이 표의 허용범위는 제조사 실측 보고서가 BS EN 61672-1:2013 주파수 시험용으로 제시한 값이다. **확인한 다섯 점은 이 범위 안**이다. 공식 규격 원문 전체와 측정불확도 예산을 검사한 인증 판단은 아니다. Class 2도 고역의 감쇠 허용이 느슨하므로 “20 kHz에서 −15.9 dB ⇒ Class 2 탈락” 같은 추론은 하지 않는다. [Turnkey 시험 보고서, p.2](https://turnkey-instruments.com/wp-content/uploads/2018/06/Performance-of-iDB-Sound-Processor.pdf).

동일 계열 bilinear weighting 연구도 필터 응답과 장치 전체의 규격 적합성을 구분할 근거가 된다. **이 앱 또는 휴대폰+마이크 전체가 Class 1/2라는 뜻은 아니다.** [Rimell 등, Industrial Health 2015](https://www.jniosh.johas.go.jp/en/indu_hel/doc/IH_53_1_21.pdf).

설교·찬양 스펙트럼 두 함수는 선택한 가정에서만 작은 오차를 보인다는 검증이다. 현장 대표성이나 최악 오차를 입증하지 못한다. 예컨대 speech 함수는 8 kHz에서 500 Hz 대비 약 −689 dB를 만들어 고역 기여를 사실상 제거한다. “실제 예배 소리에서는 <0.15 dB”라는 test 이름/주장은 축소해야 한다. 단일 고역 순음이나 고역 우세 신호의 오차는 표의 값 그대로다.

**판정:** 지금 증거만으로 pre-warping/oversampling을 의무화하거나 High로 분류하지 않는다. 먼저 제품의 측정대역별 오차 목표를 정한다. 단일 주파수 pre-warping은 전체 대역을 동시에 펴지 못한다. 넓은 대역에서 작은 오차가 필요하면 oversampling 또는 보정된 IIR 설계를 검토하고, 현재 오차값과 동일해야 통과하는 테스트를 “허용오차 이내” 테스트로 바꾼다. C 및 지원 sample rate도 포함해야 한다.

### 2. 주파수 보정을 RTA에만 적용

R04가 해당한다. UI 문구는 필요한 고지지만 명세 변경 승인이나 <0.5 dB 오차 보증을 대신하지 않는다. FIR만 가능한 것은 아니다. 광대역 Leq와 순간 waveform 기반 지표의 요구를 분리해서 설계해야 한다. 적용 중인 profile의 실제 보정 범위를 측정 화면과 향후 저장 metadata에도 남겨야 한다.

### 3. C−A 경계 4/10/16 dB

차이 계산 자체는 같은 PCM·동일 시간창의 A/C 결과를 빼므로 타당하다. 그러나 4/10/16은 현장 데이터로 검증된 분류기가 아니다. 1 kHz 순음과 고역 위주 잡음도 낮은 C−A를 내며 “설교·기도에 어울리는 균형”을 보장하지 않는다. 무음은 두 값의 floor가 같아 C−A=0으로 “말소리에 가까움”이 될 수도 있다.

**Low 위험:** `dsp/…/MultiWeightEngine.kt:88–103`의 “균형 잡힘”, “저역을 줄이면 명료도” 문구는 색을 쓰지 않아도 권고로 읽힌다. 수치와 중립적인 상대 분류로 제한하고 임계값을 임시값으로 고지한다. 회귀 테스트는 무음/저역 순음/1 kHz/고역 순음/다른 스펙트럼이 같은 C−A를 갖는 경우를 포함한다. 말 5 dB·찬양 15 dB를 일반 사실로 승인하지 않는다.

### 4. 밴드 경계 bin 선형 분배

겹치는 폭 비율은 piecewise-constant PSD 적분 근사로서 일관성이 있다. FFT power에는 이미 Hann window의 spectral spreading이 반영돼 있으므로 Hann 모양을 다시 곱한다고 일반적으로 해결되지 않는다. **에너지 보존과 밴드 정확도는 별개**다.

현재 실행 결과: 중심 순음 해당 밴드 오차는 20/25/31.5/40/50/63/80/100/125 Hz에서 각각 약 −6.29/−4.91/−4.63/−4.16/−2.88/−2.19/−1.12/−0.39/−0.25 dB다. 20 Hz 입력의 최대 막대는 25 Hz였다. 20–50 Hz에는 기존 불확실성 표시가 있으므로 그 부분을 새 결함으로 중복 집계하지 않았다. R08은 63/80 Hz의 지나치게 낙관적인 resolved 표시를 지적한다.

### 5. FFT scaling, Hann 보정, 2% tolerance

한쪽 스펙트럼의 DC/Nyquist 제외 ×2와 Hann 에너지 보정은 대역 전력 추정 목적에 맞는다. FFT 자체의 큰 scaling 오류는 발견하지 못했다.

다만 정확한 등식은 `sum(P[k]) = sum(x[n]^2 * w[n]^2) / sum(w[n]^2)`다. 임의의 한 프레임에 대해 원신호 `mean(x²)`와 항상 같다는 주석은 틀리다. 이번 impulse 검증은 이 **창 가중 에너지 등식**을 1e−14 절대오차 내에서 통과했다. 창 끝의 impulse는 0이 되고 중심 impulse는 원프레임 평균 전력의 약 2.667배가 된다. 이는 FFT 버그가 아니라 창 가중의 결과다.

기존 1 kHz 정상 사인파 테스트는 기대 0.5에 대해 실제 0.5000000000015539였다. 상대오차 약 3.1e−12이므로 이 벡터에 2%는 매우 느슨하다(레벨 약 0.086 dB). Parseval 수치오차 검증은 엄격하게, noise의 통계적 오차는 충분한 프레임/seed와 별도 허용범위로, transient의 시간 위치/overlap 영향은 별도 테스트로 나누는 것이 맞다.

## 남은 위험과 필요한 검증

- **종료 순서:** `MicSource.close():272–287`은 blocking read를 stop하기 전에 main thread에서 최대 500 ms join하며, timeout 뒤 worker 종료 확인 없이 release한다. 느린 read/DSP에서 이전 callback이 새 capture의 field를 건드릴 가능성까지 검증해야 한다. 이 경로는 이전 단계부터 존재한다. 권장 순서는 새 callback 차단 → read 해제 → worker 종료 확인 → 효과/record release이며 실제 Android 동작에 맞춰 off-main에서 처리한다.
- **시작 실패:** `startRecording()` 예외 처리와 recording state 확인이 없다. open 성공 후 시작 실패를 주입해 자원 해제·실패 UI를 검사해야 한다. 장시간 leak이 없다고 판정하지 않았다.
- **프로필 key:** `CalibrationKey`에는 deviceKey/source만 있고 sample rate/encoding/channel 등 input config가 없다. 현재 요청은 48 kHz mono지만 명세 8장의 확장 요구에 부족하다. API 26/27은 address가 없어 동명 내장 마이크가 합쳐진다. USB address를 재연결 후 영구 identity로 사용할 수 있는지도 장치별 검증이 필요하다.
- **파일/성능:** `importCurveFrom()`은 임의 크기 파일을 `readText()`하고, CurveStore.watch의 파일 읽기/파싱은 collector context에서 실행된다. 파일 크기·점 개수 상한과 IO dispatcher, cancellation, 손상/부분 저장 시험이 필요하다. crash나 ANR 발생을 실측했다고 주장하지 않는다.
- **Leq:** 구현은 full bucket 창에 partial bucket을 더하므로 실효 창은 최대 약 100 ms 길어진다. 문서화된 bucket 근사이며 exact rolling window가 아니다. 큰 transient가 경계를 지날 때 오차가 작다고 단정할 수 없다. session Leq는 DSP에 존재하지만 ViewModel/UI에는 노출되지 않는다.
- **미연결 기능:** RTA smoothing/Peak Hold의 설정 UI 연결, reset 동작, 정지 후 stale reading/판정 표시, 파일 선택기로 돌아온 뒤 watcher 상태, persisted church mode와 상단 chip 일치에 대한 Compose 통합 검증이 없다.
- **capture 부하:** FFT와 DSP를 capture thread에서 동기 실행한다. PCM 버퍼 재사용은 확인했지만 프레임 객체·RTA 배열 할당은 남아 있다. processing time과 누적 lag만으로 dropped frame=0을 증명할 수 없다. 하드웨어 timestamp/frame position 및 부하 시험을 추가해야 한다.
- **범위 밖:** Feedback Phase 9, 세션 DB/리포트, Recording A–F의 미구현을 이번 변경의 결함으로 집계하지 않았다.

## 수정 후 게이트

1. R01–R03과 L01을 우선 수정하고 route/profile/state/lifecycle 통합 테스트를 추가한다.
2. 첨부 수학 실패 3건(R05–R07)을 재현 후 수정한다. R04/R08 등 오차 범위의 제한을 유지한다면 명세·ADR에 구체적 수치와 허용 근거를 남긴다.
3. clean build, DSP+앱 전체 단위 테스트, debug/release 검증을 재실행한다. 시험 수는 실행 횟수와 서로 다른 test case 수를 나눠 보고한다.
4. 실제 Galaxy 내장/USB에서 시작·수동 선택·hot plug·분리 정책·background·재시작·보정 import를 확인한다. frame identity와 표시값을 함께 검증한다.
5. 수정 커밋 범위와 결과를 독립 재검증한다. 이번 결과는 Phase 4–8 승인이나 실기기 정확도 인증이 아니다.
