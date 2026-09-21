# USB 오디오 지시서 — 지금 코드와 대조한 결과

대상: [SELAH_RTA_UMC404HD_EMM6_USB_AUDIO_REQUIREMENTS.md](SELAH_RTA_UMC404HD_EMM6_USB_AUDIO_REQUIREMENTS.md)
기준 커밋: `a45f23e` · 2026-09-22

지시서 19장이 **「기존 구조를 먼저 분석한 후 최소 침습적으로 변경한다」**
고 적었다. 그래서 구현 전에 한 줄씩 대조했다. 이 문서는 그 결과이고,
**계획이 아니라 사실 확인**이다.

요약: 필수 기능 13개 중 **9개는 이미 있거나 거의 있다**(3-1·3-3·3-4·3-7·
3-8·3-9·3-11·3-12·3-13). 없는 것은 넷이다 — **3-5 채널 선택**,
**3-6 채널별 레벨 표시**, **3-10 보정 ON/OFF**, 그리고 **3-2 USB 권한**
(— 이것은 **필요 없을 가능성이 크다**. 2.2 참고). 그 밖에 지시서와
**어긋나는 것이 둘, 지시서가 말하지 않아 정해야 하는 것이 넷** 있다.

---

## 1. 이미 있는 것 — 다시 만들면 안 된다

| 지시서 | 이미 있는 것 | 자리 |
|---|---|---|
| 3-1 USB 연결/해제 감지 | `InputDeviceScanner.watch()` — `AudioDeviceCallback` | `audio/InputDeviceScanner.kt` |
| 3-3 내장/USB 선택 | `chooseInput` + `preferredInputKey` + `autoPreferExternal` | `audio/InputDevices.kt` |
| 3-4 장치명·스트림 정보 | `OpenedFormat`(열린 값) / `ActiveInput` | `audio/AudioFormatSpec.kt` |
| 3-7 클리핑 감지 | `BlockStats.clipped`, `CLIP_THRESHOLD = 0.999` | `dsp/BlockStats.kt` |
| 3-8 실제 포맷 기준 DSP | `RequestedFormat` ↔ `OpenedFormat` 를 **일부러 갈라 놓았다** | 같은 파일 |
| 3-9 캘리브레이션 파일 불러오기 | SAF `OpenDocument` → `importCurveFrom(uri)` | `ui/CaptureViewModel.kt:641` |
| 3-11 보간·적용 | `CalibrationCurve.gainDbAt` — **log 주파수 보간, 밖은 끝점 유지** | `dsp/CalibrationCurve.kt` |
| 3-12 설정 저장 | `MeterSettingsStore` · `CurveStore`(기기 열쇠별) | `data/`·`calibration/` |
| 3-13 분리 시 안전 종료 | `DisconnectPolicy.Pause` → `applyDisconnectPolicy` | `ui/CaptureController.kt` |
| 12 입력 추상화 | `AudioSource` 인터페이스 + `MicSource` 구현 | `audio/AudioSource.kt` |

지시서 9.5 의 보간 요구(「log10 축에서 선형 보간」, 「범위 밖에서 무리한
외삽을 하지 않는다」)는 **글자 그대로 이미 구현돼 있다.** 9.3 의 파서
요구(공백/Tab/쉼표, 주석, CRLF, 건너뛴 줄 보고)도 `CalibrationFile.parse`
가 이미 한다 — 심지어 **Dayton iMM-6C 파일 머리글을 예시로 달아 두었다.**

---

## 2. 지시서와 **어긋나는** 것 둘 — 먼저 정해야 한다

### 2.1 보정 부호를 이미 정해 놓았다 (지시서 9.4)

지시서: *「두 번째 열을 무조건 더할 correction 이라고 가정하지 않는다.
… 불명확하면 임의 부호로 측정값을 변경하지 않는다.」*

그런데 `CalibrationCurve` 의 주석은 이렇게 적혀 있다:

> 널리 쓰이는 .cal/.frd 는 「마이크의 응답」을 적고 프로그램이 빼는
> 쪽이다. 우리도 그렇게 한다 — `gainDbAt` 이 돌려주는 값을 **빼면**
> 보정된 값이 된다.

**즉 이미 부호를 골랐다.** 지시서가 금지한 「추측」인지, 근거 있는 관례
채택인지는 **실제 EMM-6 파일을 봐야 갈린다.**

- iMM-6C 계열은 `*` 주석 뒤 `Frequency, SPL, Phase` 로 **응답(SPL)** 을
  적는다 — 그렇다면 지금 부호가 맞다.
- EMM-6 는 제조사가 시리얼별 파일을 주는데, 그 파일이 `Correction` 열을
  주는 형식이면 **부호가 반대**다.

**막힌 항목이다.** 실제 EMM-6 파일 한 장(머리글 포함 앞 20줄)이 없으면
정할 수 없다. 추측해서 고치면 측정값이 통째로 틀어진다.

부호가 반대일 때를 알리는 자리가 있긴 하다(`CalibrationFile.kt:112` —
*「부호 규약이 반대이거나 다른 종류의 파일일 수 있습니다」*). **그러나
그것을 믿으면 안 된다** — 보정량이 ±30 dB 를 넘어야 뜨는 경고이고,
실제 EMM-6 파일은 대개 ±5 dB 안쪽이다. **부호가 반대여도 조용히
통과한다.** 파일 한 장을 보는 것 말고 확인할 방법이 없다.

### 2.2 USB 권한이 필요 없을 수 있다 (지시서 3-2, 14)

지시서: *「Android USB 사용 권한 처리」*, *「권한 거부: USB 오디오 장치를
사용하려면 연결 권한이 필요합니다.」*

그러나 지금 앱은 **`UsbManager` 를 쓰지 않는다.** `AudioManager` 가
USB Audio Class 장치를 `TYPE_USB_DEVICE`/`TYPE_USB_HEADSET` 로 내주고,
`AudioRecord.setPreferredDevice` 로 그리 연다. 이 경로에는 `RECORD_AUDIO`
외에 **따로 받을 권한이 없다.**

`UsbManager.requestPermission` 이 필요한 것은 **raw USB 로 직접 말할
때**(제조사 전용 프로토콜, UAC 를 직접 구현할 때)다. 지금 구조에서
그 대화상자를 띄우면 **쓰지도 않을 권한을 묻는 것**이 된다.

→ **권고**: 3-2 와 14 의 「권한 거부」 문구는 **`RECORD_AUDIO` 거부**로
읽는다. USB 전용 권한 흐름은 넣지 않는다. 실기기에서
`AudioManager.getDevices` 가 UMC404HD 를 못 볼 때에만 다시 본다.

---

## 3. 진짜로 없는 것 — 여기가 일감이다

### 3.1 채널 선택 (지시서 5) — **P0, 가장 큰 구멍**

```kotlin
data class RequestedFormat(
    val sampleRate: Int = 48_000,
    val channelMask: Int = AudioFormat.CHANNEL_IN_MONO,  // ← 늘 모노
)
```

그리고 `OpenedFormat` 에는 **`channelCount` 가 아예 없다.** 즉:

- 4채널 인터페이스를 꽂아도 **무엇이 열렸는지 기록이 없다.**
- Input 1 을 골랐는지 Input 3 이 열렸는지 **앱도 모르고 사람도 모른다.**
- 안드로이드가 다운믹스했는지 첫 채널만 집었는지도 모른다.

이것은 지시서 17 의 DoD *「선택 채널이 FFT 에 정확히 매핑됨」* 을 지금은
**증명할 방법조차 없다**는 뜻이다.

필요한 변경:

1. `OpenedFormat` 에 `channelCount` 를 더한다 — **열린 값**이다.
   `sampleRate` 를 그렇게 다룬 까닭과 같다.
2. `RequestedFormat` 에 `channelIndex`(고른 채널)를 더한다.
3. 여러 채널로 열었으면 **인터리브를 풀어 고른 채널만** 뽑는다.
   `MicSource` 의 읽기 루프에 들어간다.
4. `InputDeviceInfo` 에 `channelCounts`(기기가 알린 값)를 더해 화면이
   **실제 값으로** 라디오 버튼을 그린다 — 4채널 하드코딩 금지(지시서 5).

**주의**: 안드로이드가 알려주는 `AudioDeviceInfo.channelCounts` 는
「열 수 있다고 주장하는 값」이지 열리는 값이 아니다. 이 저장소에서 거듭
데인 자리(요청 ≠ 열림)라, **연 뒤에 다시 읽어 기록한다.**

### 3.2 레벨/클리핑 표시 (지시서 3-6, 6) — P1

재료는 다 있다(`BlockStats.peakAbs`, `rms`, `clipped`,
`CaptureDiagnostics.lastPeakAbs`, `clippedBlocks`). 없는 것은:

- **dBFS 로 바꿔 보여 주는 화면**(지금은 dBA 만 보여 준다)
- 채널별 막대(지시서 5 의 `Input 1 ███████░ -18.2 dBFS`) — 이건
  **채널 선택(3.1)이 먼저** 있어야 한다. 채널별로 재려면 여러 채널로
  열어 놓고 동시에 통계를 내야 하기 때문이다.
- 클리핑 문구(지시서 6·14) — 지금 진단 문구는 있지만 「GAIN 을
  낮추라」는 외부 인터페이스 맥락의 말이 없다.

### 3.3 진단 화면 (지시서 8) — P2

`CaptureDiagnostics` 는 이미 모으고 있다. 화면이 없다.

### 3.4 캘리브레이션 ON/OFF (지시서 11) — P1

`CurveStore` 는 넣고 빼기는 되지만 **「파일은 두고 적용만 끄기」** 가
없다. 저장소에 `enabled` 를 더하는 작은 일이다.

### 3.5 EMM-6 프로파일·팬텀전원 안내 (지시서 7, 13) — P1~P2

**`CalibrationProfile` 이라는 클래스는 없다.** 같은 이름의 파일은 있지만
안에 든 것은 `CalibrationKey`·`GlobalCalibration`·`ActiveCalibration` 이고,
지시서 9.2 가 요구한 `microphoneManufacturer`/`Model`/`serialNumber` 는
**하나도 없다.** 지금 보정은 「어느 기기의 보정인가」만 알고 「어느
마이크인가」는 모른다. 팬텀전원 안내와 「측정 시작 전 체크」도 새 화면이다.

**지시서가 스스로 못박은 것**: *「팬텀전원은 앱이 실제 확인한 것처럼
✓ 표시하지 말고 사용자 확인 항목으로 둔다.」* 이 프로젝트의 규칙과 같다 —
확인하지 않은 것을 확인한 것처럼 적지 않는다.

---

## 4. 지시서가 말하지 않아 정해야 하는 것

| 물음 | 왜 문제인가 | 제안 |
|---|---|---|
| 채널을 고르면 **보정도 채널별**인가 | 지금 보정은 `stableKey`(기기) 단위다. Input 1 에 EMM-6, Input 2 에 다른 마이크를 꽂으면 같은 보정이 걸린다 | 열쇠에 채널을 더한다: `…\|ch1`. 지시서 9.2 의 「UMC404HD / Input 1 / EMM-6」 분리와 같은 뜻 |
| 여러 채널을 **동시에 열어 둘 것**인가 | 채널별 레벨 막대(지시서 5)를 그리려면 그래야 한다. 그러면 CPU·버퍼가 채널 수만큼 는다 | P0 은 **고른 채널만** 연다. 레벨 막대는 P1 에서 「여러 채널 열기」와 함께 본다 |
| 분리 뒤 **재연결**하면 자동으로 다시 여는가 | 지시서 4 는 「임의 전환하지 말고 종료」라 하고, 지금 `DisconnectPolicy` 에는 `FallBack`(내장으로 전환)이 있다 | USB 를 고른 상태에서는 `Pause` 를 기본으로 한다. 지시서가 명시적으로 금지한 동작이다 |
| 광대역 음압에도 주파수 보정을 거는가 | `FREQUENCY_SCOPE_NOTE` 가 **지금은 안 건다**고 적어 두었다(역응답 필터가 없어서) | 이번 작업 범위 밖. 지시서 10 의 순서는 RTA 경로를 말하는 것으로 읽는다 |

---

## 5. 다음에 할 일 (이 문서가 정한 것은 여기까지)

1. **막힌 것**: 실제 Dayton EMM-6 캘리브레이션 파일 한 장(앞 20줄이면
   충분). 없으면 9.4 의 부호 검증을 못 한다 — 그리고 **부호를 모른 채
   2번 이후를 해도 DoD 를 못 채운다.**
2. **P0 착수 가능**: 채널 선택(3.1). 부호와 무관하고, 없으면 나머지가
   다 헛돈다.
3. P1: 레벨/클리핑 표시, 캘리브레이션 ON/OFF.
4. P2: 진단 화면, 프로파일 칸 보강.

1번을 기다리는 동안 2번을 한다 — 서로 걸리지 않는다.

---

## 6. 실기기로 확인하지 않은 것

이 대조는 **코드를 읽어서** 한 것이다. 아래는 UMC404HD 를 꽂아 봐야
갈린다. 지금은 장비가 없다.

- 안드로이드가 UMC404HD 를 어느 형으로 주는가 — `classifyInput` 은
  `TYPE_USB_DEVICE`·`TYPE_USB_HEADSET`·`TYPE_USB_ACCESSORY` 를 모두 `Usb`
  로 받으므로 **어느 쪽이든 목록에는 뜬다.** 화면에 적힐 이름만 달라진다
- `AudioDeviceInfo.channelCounts` 가 4 를 알리는가
- `CHANNEL_IN_MONO` 로 열면 **어느 채널**이 오는가
- 4채널로 열리기는 하는가(안드로이드는 대개 2채널까지만 연다)
- 3m USB 케이블에서 드롭이 나는가

**「4채널로 열리기는 하는가」가 특히 중요하다.**
안드로이드의 USB 오디오 입력은 기기마다 2채널로
1~4 선택」은 **앱이 아니라 인터페이스 쪽에서** 풀어야 한다. 이것은
추측이므로, 꽂아 보기 전에는 설계를 그쪽으로 기울이지 않는다.
