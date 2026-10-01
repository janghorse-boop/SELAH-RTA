# 내장 마이크 + USB-C 오디오 출력 동시 사용 — 검증 결과

**판정: PASS** (단, 60분과 시각 드리프트는 안 쟀다 — 아래 「안 본 것」)

지시서: `inbox/SELAH_RTA_내장마이크_USB-C_오디오출력_동시사용_검증지시서.md`
잰 날: 2026-10-01 · 잰 것: `UsbOutBuiltInMicTest`(계측 시험)

---

## 1. 최종 판정

**PASS.** 지시서 13장의 PASS 조건을 **하나도 빼지 않고** 만족했다.

| 조건 | 결과 |
|---|---|
| USB-C 출력 정상 | ADAM Audio D3V 로 나감(`routedDevice` 로 확인) |
| 내장 마이크 입력 정상 | 30분 내내 장이 끊김 없이 들어옴 |
| 입출력 동시 사용 안정 | 84,705장 중 무음 1장(첫 장) |
| 내장 마이크 route 유지 | 경로 변경 **0회** |
| USB 연결 시 입력 강제전환 없음 | 꽂기 전후 모두 `BuiltIn/bottom` |
| 5분 이상 dropout 없음 | 5분·30분 둘 다 통과 |
| AudioRecord 불필요 재시작 없음 | 캡처 끝남 **0회** |
| Signal Generator 정상 | 켬/끔 차이 **25.8dB** 로 마이크가 실제로 들음 |

## 2. Galaxy S23 (SM-S918N, 안드로이드 16) 결과

| | 5분 | 30분 |
|---|---|---|
| 장 | 14,354 | 84,705 |
| 무음 장 | 1 (첫 장) | 1 (첫 장) |
| 입력 경로 변경 | 0 | 0 |
| 캡처 끊김 | 0 | 0 |
| RMS 흔들림 | −34.7 ~ −35.0 | −34.6 ~ −35.0 |

## 3. 실제 Input Device

```
내장마이크 / SM-S918N / 주소 'bottom'
AudioSource=VoiceRecognition / 48,000Hz / mono / Float
routeConfirmed=true (녹음 시작 뒤 routedDevice 로 확인)
AGC·NS·AEC 모두 꺼짐 확인 (stillOn=[])
```

**USB-C 를 꽂기 전과 후가 같다.** 끝날 때까지도 같다.

## 4. 실제 Output Device

```
USB헤드셋 / USB-Audio - ADAM Audio D3V / 주소 'card=1;device=0'
32,000 / 48,000 / 44,100Hz · 스테레오
AudioTrack.routedDevice 로 확인
```

## 5. 쓴 Android API

`AudioManager.getDevices` · `AudioDeviceInfo` · `AudioRecord`(+`setPreferredDevice`,
`routedDevice`, `addOnRoutingChangedListener`) · `AudioTrack`(같음) ·
`AcousticEchoCanceler` · `AutomaticGainControl` · `NoiseSuppressor`.

**새 구조를 만들지 않았다**(지시서 5장) — 기존 `MicSource`·`SignalPlayer`·
`AudioTrackSink`·`InputDeviceScanner` 를 그대로 썼다. 그래서 이 결과는
**앱이 실제로 쓰는 길**의 결과다.

## 6. USB-C 연결 전/후 Routing 변화

| | 꽂기 전 | 꽂은 뒤 |
|---|---|---|
| 출력 기기 | 3개(수화부·폰스피커·통화) | 4개 — **D3V 추가** |
| 입력 기기 | 4개 | 4개 — **그대로** |
| **USB 입력** | **0개** | **0개** |

**D3V 는 출력 전용이다.** 안드로이드가 입력을 끌고 갈 대상 자체가 없다 —
이번 결과가 깨끗한 가장 큰 까닭이고, **다른 기기로 일반화할 수 없는
까닭**이기도 하다(아래 13장).

## 7·8. AudioRecord / AudioTrack 상태

재시작 0 · 예외 0 · 크래시 0. 출력은 한 번 열려 30분을 버텼다.

## 9. Sample Rate / Format 변화

48,000Hz · Float · mono 로 열려 **끝까지 같았다.**

## 10. 5분 이상 동시 실행 결과

5분·30분 모두 통과. 30분 동안 **RMS 가 0.4dB 안에서만 움직였다.**

배터리 97% → 92%(30분에 5%p) · 온도 31.9℃ → 34.3℃(+2.4℃). 발열 문제 없음.
**충전 중이 아니었다** — USB-C 를 D3V 가 쓰기 때문이다(60분을 하려면 전원
있는 허브가 필요하다).

## 11. Dropout / Underrun / Error

무음 장 **1개**(첫 장, 0.0012%). 읽기 오류 0 · 재시작 0.

> **첫 장이 비는 것은 정상이다.** 기준선(소리 없이 5초)에서도 똑같이
> 한 장이 비었다. 그래서 시험은 「무음 장이 하나라도 있으면 실패」가
> 아니라 **비율(<1%)과 중앙값**으로 본다 — 막으려는 것은 UMC404HD 에서
> 겪은 **입력이 통째로 죽는 것**(266,240 표본 전부 0)이다.

## 12. USB-C 분리 / 재연결 결과

```
[30초] 뽑힘      출력 → 폰 스피커로 fallback. 입력 그대로, 캡처 안 끊김
                RMS −40.9 → −15.9dBFS (스피커가 마이크 바로 옆이라 커짐)
[56초] 다시 꽂힘  출력 → D3V 로 자동 복귀. RMS −40.9 로 돌아옴
```

착탈 전 구간에서 **입력 경로 변경 0 · 캡처 끊김 0**, 뽑힌 뒤에도 2,818장이
계속 들어왔다. 입력은 끝까지 `BuiltIn/SM-S918N/48000`.

### 이 시험에서 찾은 결함 (고쳤다)

다시 꽂은 뒤에도 화면이 **「고른 곳과 다릅니다」**를 계속 적었다 — 실제로는
D3V 로 맞게 나가고 있는데도. 까닭은 **기기를 `id` 로 견뎠기** 때문이다.
안드로이드는 꽂을 때마다 **새 id** 를 준다(546 → 849).

**멀쩡한데 틀렸다고 말하는 경고는 경고를 죽인다** — 다음에 진짜로 어긋났을
때 사람이 안 믿는다. **종류·이름·주소**로 견주도록 고치고 시험으로 못박았다
(`SameOutputKeyTest`).

## 13. Android / Samsung 특이사항

- **입력 경로가 `UNPROCESSED` 가 아니라 `VoiceRecognition`** 이다. 이 폰이
  `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED=false` 로 답한다. 지시서 11장
  대로 **실제 경로를 표시**하고 있고, **AGC·NS·AEC 는 셋 다 꺼진 것을
  확인**했다. 다만 **하드웨어 안쪽의 가공까지는 알 수 없다.**
- **D3V 가 출력 전용이라 쉬웠다.** 입출력을 함께 가진 USB 기기
  (UMC404HD·iMM-6C 같은)에서는 **다른 이야기가 된다** — 실제로 UMC404HD
  에서는 같은 카드로 넣고 빼자 **입력이 완전한 디지털 무음**이 됐다
  (2026-09-30). 이번 PASS 를 그쪽으로 옮겨 읽으면 안 된다.

## 14. 간편 Transfer Function 모드에 쓸 수 있는가

**라우팅 쪽에는 걸림돌이 없다.** 지시서가 묻는 다섯 질문이 모두 「된다」로
나왔고, 30분 동안 한 번도 흔들리지 않았다.

**다만 TF 가 되려면 아직 한 가지를 못 봤다 — 아래.**

## 15. 다음 단계에서 필요한 개발 항목

**먼저 재야 할 것 (아직 안 본 것)**

- **시각 드리프트.** 지시서 8장이 진단 항목으로 적어 둔 `Timestamp Drift`
  를 **안 쟀다.** 출력은 **D3V 의 클럭**, 입력은 **폰 ADC 의 클럭**이라
  둘은 서로 다른 시계다. 30분 동안 RMS 가 안 흔들린 것은 **레벨이
  안정했다**는 뜻이지 **시각이 안 어긋났다**는 뜻이 아니다. TF 는 기준과
  측정을 시간으로 맞춰야 하므로 **이것이 다음 관문이다.**
- **60분.** 30분까지만 했다. 전원 있는 USB-C 허브가 있어야 한다.
- **입출력을 함께 가진 USB 기기**(UMC404HD·iMM-6C)에서 같은 시험. 위 13장.

**그 뒤에 만들 것**

지연 찾기 → 클럭 드리프트 보정 → Magnitude → Coherence → Phase.
**이 순서를 뒤집지 않는다** — 드리프트를 안 잡고 Magnitude 를 그리면
그럴듯하게 틀린 그림이 나온다.

---

## 어떻게 쟀나 (다시 돌리는 법)

```
# 진단 · 기준선 · 입력 유지 (소리 없음)
adb shell am instrument -w -r \
  -e class 'kr.joa.selahrta.audio.UsbOutBuiltInMicTest#진단_입출력_기기를_모두_적는다' \
  kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner

# 동시 사용 (소리 남). 길이와 세기를 밖에서 준다
adb shell am instrument -w -r -e minutes 30 -e amplitude 0.2 \
  -e class 'kr.joa.selahrta.audio.UsbOutBuiltInMicTest#사례3_USB로_내보내며_내장마이크로_잰다' \
  kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner

# 착탈 (사람이 뽑았다 꽂는다)
adb shell am instrument -w -r -e minutes 1.5 -e amplitude 0.1 \
  -e class 'kr.joa.selahrta.audio.UsbOutBuiltInMicTest#사례4_5_USB를_뽑았다_다시_꽂는다' \
  kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
```

기록은 `adb logcat -s USBOUT` 에 남는다.

**오래 도는 시험은 떼어내서 돌린다** — 무선 디버깅이 끊겨도 폰에서 계속
돌고, 나중에 다시 붙어 기록을 읽으면 된다:

```
adb shell "nohup am instrument -w -r ... > /sdcard/out.log 2>&1 &"
```

**화면 꺼짐을 늘려 둔다**(계측 시험은 화면이 꺼지면 죽는다). 끝나면
되돌린다 — 이번에는 600000 으로 돌려놓았다.
