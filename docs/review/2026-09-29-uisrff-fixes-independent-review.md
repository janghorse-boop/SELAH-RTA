# UISRFF-01~03 수정본 독립 재검증

검토일: 2026-09-29 · 검토자: Codex

**판정: 이번 수정 범위의 코드 승인. Critical 0 / High 0 / Medium 0. 출시 문서 Low 1건은 아래 권고와 함께 남긴다.**

보류 원인이었던 3초 창 경계에서의 잘못된 보정 저장이 제품 코드에서 해결됐다. 빈 입력 감시도 정상 동작한다. 앞선 UISRFF-03의 AAB/APK 검사 대상 혼동은 해결됐으나 새 서명 안내에 과도한 거절 조건이 있어 별도 Low로 기록한다. 이 판정은 실제 교정기 정확도·장시간 Android 성능·Play 출시 승인까지 뜻하지 않는다.

## 1. 대상과 검증 경계

- 요청서: `2026-09-29-uisrff-fixes-reverification-request.md`, 추가 실기기 관측 §6-1 포함.
- 기준: `9cbbdadc1aba582ef6bf0c031160c8a5411212f5`.
- 고정 대상: **`955cf6c9f454b03181ea160820fdbb33c573181b`**. PR #58, 구현 커밋 `b5f5bee`, `19b4300`, `1f91494`, `c19d3bb`와 후속 문서.
- 전체 diff 19파일 +1298/-49에는 이전 검토 보고서·probe·제안 패치도 들어 있다. 모두 새 제품 변경으로 세지 않았다.
- 시작 작업 트리는 clean. 제품 파일은 수정하지 않았다. `build/independent-review/uisrff-20260929/source`에 대상 커밋을 추출해 시험했다. 변이와 제안 시험은 격리 복사본/별도 클래스에만 적용했다.
- 이번에는 실제 기기·USB·음향 교정·브라우저 클릭·서명 키 생성·Play Console을 조작하지 않았다. 요청서의 Galaxy S23/UMC404HD 관측은 구현자 보고로 구분한다.

## 2. 기존 지적의 결론

| 항목 | 이번 독립 관측 | 결론 |
|---|---|---|
| UISRFF-01: 실제 창이 3.0~3.1초여서 잘못 저장 | 1kHz, 진폭 0.9로 5.02초 → 0.009로 3.02초. 실제 저장값 −43.925447977dBFS, offset 137.925447977dB, 이후 표시 **93.999998206dB**. 이전 75.725648331dB 오류 해소 | **닫힘** |
| UISRFF-01: 표본율·경계 위상 | 제품에 편입된 독립 회귀 실행. 44.1/48kHz·10~90ms 위상·혼합 블록·A/C/Z 출력의 최근 N표본 제곱합 비교 통과 | **닫힘** |
| UISRFF-02: 빈 입력이 마지막 유효 입력 시각을 갱신 | 정상 입력 뒤 400ms 간격의 빈 블록 5회 → age=**2000ms**, 저장 근거 null, 입력 중단 경고 | **닫힘** |
| UISRFF-02의 누락 시험 | 첫 정상 입력 없이 빈 블록 25회/10초 → age=**10000ms**, cleanSpl=null, 경고 있음. 이후 실제 PCM 20ms → age=0, cleanMs=20 | **독립 실행으로 확인**. 정규 회귀 편입 권장 |
| UISRFF-03: AAB 생성 후 옛 APK 확인 | 같은 AAB를 jarsigner/keytool로 검사하도록 변경. APK는 별도 절차로 구분. 업로드 키 재설정 설명도 구분 | **원 지적 닫힘**. 새 문구는 §4 |
| 기존 lint 오류 | 실제 BOM 문자를 `"\uFEFF"`로 치환. lint 재실행 0 errors | **해결** |

저장 probe는 실제 Controller·DSP·ViewModel 저장 메서드·CalibrationStore를 호출하고 DataStore 파일 생성까지 확인한다. 하드웨어와 시계/Main dispatcher를 대체했으며 Android ViewModel 생성자만 우회했다. 전체 Android 서비스/화면 배선이나 새 프로세스에서의 저장 재개방을 검증했다는 뜻은 아니다.

이전 회귀도 같은 probe로 실행했다:

- 클리핑 5초 뒤 정상 소리 3.1초의 보정 잔류 약 1.63e−8dB.
- 큰 소리 후 3.1초 콜백 공백, 정상 20ms만 들어오면 Gate=Reject.
- 첫 콜백 전 10초 및 정상 입력 뒤 1.6초 중단을 제품의 실제 감시 코루틴이 표시; 회복 시 age=0.
- 입구를 통과한 옛 callback을 barrier로 멈췄다가 새 세션 시작 뒤 재개해도 새 보정 근거 유지.
- Controller가 방출한 125개 A/C/Z live 프레임을 별도 기존 엔진과 비교해 동일. Fast/Slow 설정에서 보정값 동일.
- 기록 시작 I/O가 Main 복귀를 기다리는 동안 구간이 바뀌는 세 경우의 기록 이벤트 유지.

## 3. 정확 창·메모리·장시간 질문

### 계산과 초기화

[TimeWeighting.kt:191](D:/Cowork/SELAH-RTA/dsp/src/main/kotlin/kr/joa/selahrta/dsp/TimeWeighting.kt:191)의 옵트인은 첫 입력 전에만 허용된다. `ExactEnergyWindow`는 원형 배열에서 밀려난 제곱값을 합에서 빼고 새 제곱값을 더한다. 초기화 시 배열을 새로 할당하지 않고 head/size/sum/compensation을 초기화한다. size가 0에서 다시 차기 때문에 예전 배열 값이 재사용되어 평균에 들어가는 문제는 보이지 않았다.

여기서 정확한 3초는 **가중 필터를 통과한 최근 N표본의 에너지 평균**이다. A/C IIR 필터의 물리적인 과도나 낮은 SNR의 편향까지 없어졌다는 뜻은 아니다. 숫자 3초는 그 자체로 음향 정확도 인증이 아니다.

### 2시간 분량의 산술 실험

48kHz 단일 정확 창에 **345,600,000표본**을 넣었다. 30초마다 진폭을 80dB 바꾸며 매초 현재 링 전체를 양수 제곱합으로 다시 더한 값과 비교했다. 체크 7,197회. 같은 표본으로 단순 가감 합산도 비교했다.

| 계산 | 이 입력에서 최대 dB 차이 |
|---|---:|
| 현재 Kahan 합산 | 약 **4.40e−8dB** |
| 비교용 단순 가감 합산 | 약 **2.34e−5dB** |

실험은 이 PC에서 약 6.87초에 끝났다. 실제 시간 2시간을 기다린 것도, Android에서 A/C/Z 필터·AudioRecord·파일 I/O·UI를 2시간 함께 돌린 것도 아니다. Kahan이 이 입력에서 수치 오차를 줄이는 것은 확인했지만, **없으면 실용 정확도가 깨진다는 증거는 아니다**. 단순 합산도 이 실험에서는 매우 작은 오차였다. 비용이 낮고 이미 검증된 Kahan을 지금 걷어낼 이유는 없다.

### 3.3MiB를 한 가중으로 줄일 필요가 있는가

48kHz 기준 세 double 링은 총 **3,456,000 bytes(약 3.30MiB)**이고, 44.1kHz는 3,175,200 bytes다. 세션마다 처음 정상 PCM을 처리할 때 생성되고 길이에 따라 증가하지 않는다. clip/gap에서는 재사용한다.

**지금은 세 가중을 유지하는 편이 낫다.** 하나만 유지하면 가중 전환 시 창을 다시 채우거나 상태를 재구성해야 한다. 그 복잡성을 먼저 추가하기보다 실제 기기의 메모리/오디오 deadline/GC 지연을 측정한 뒤 결정한다. 3.30MiB만으로 문제없다고 보증하는 것도 피한다.

추가로 같은 JVM에서 입력 60초분의 CleanWindow를 처리했다. 워밍업 뒤 단일 측정에서 변경 전 약 0.415초/186.50MB 할당, 변경 후 약 0.464초/186.59MB 할당이었다. 이 수치는 **누적 할당량**이며 유지 메모리가 186MB라는 뜻은 아니다. 두 버전 모두 상당한 임시 할당이 있으므로 이를 모두 새 링의 비용으로 돌릴 수 없다. PC·JIT·한 번의 비교라 Android 처리 여유의 보증이나 정확한 성능 회귀율로 사용하지 않는다.

실기기 후속 시험은 같은 PCM/설정의 변경 전후, 화면 켬/끔, A/C/Z 변경, clip/gap 반복, 녹음 동시 실행에서 p95/p99 처리 시간·read 누락·GC pause·유지 heap을 비교하는 것이 유용하다. 이 미측정을 이번 수치 수정의 재보류 사유로 삼지는 않는다.

## 4. UISRFFF-01 — Low — 자체 서명의 신뢰 경고까지 무조건 실패로 분류

**위치:** [release-signing.md:179](D:/Cowork/SELAH-RTA/docs/release-signing.md:179).

문서는 `certificate chain is not validated`가 보이면 통과가 아니라고 단정한다. 그런데 같은 문서의 `keytool -genkeypair`는 별도 signer 없이 **자체 서명 인증서**를 만든다. JVM의 기본 CA 신뢰 경로를 검증하지 못하는 경고는, 등록한 Android 업로드 인증서로 정상 서명된 파일에서도 나올 수 있다. 이를 payload 무결성 실패나 업로드 인증서 지문 불일치와 같은 것으로 처리하면 정상 산출물까지 거절할 수 있다.

**근거:** JDK 문서는 `-genkeypair`의 기본 자체 서명 동작과 jarsigner의 chain/self-signed 경고를 구분해 설명한다. Android 업로드에서는 등록된 업로드 인증서가 서명자의 기준이다. 실제 배포 키로 재현하거나 Play에 업로드한 것은 아니며, 문서 및 도구 계약에 근거한 지적이다. [JDK keytool](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html), [JDK jarsigner](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jarsigner.html), [Android 앱 서명](https://developer.android.com/studio/publish/app-signing).

**사용자 영향:** 출시 담당자가 정상 번들을 실패로 판단하거나 불필요하게 키/인증서를 다시 만들 수 있다. 현재 앱 런타임에는 영향이 없다.

**권장 수정:** 모든 경고를 무시하라는 뜻이 아니다. 서명 검증 실패·unsigned payload·잘못된 지문은 거절하고, 자체 서명/신뢰 체인 경고는 원인을 확인한 뒤 같은 AAB의 무결성·payload 서명 여부·인증서 유효기간·등록된 업로드 SHA-256 일치로 판정한다. [작은 문서 수정 patch](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrff-signing-doc-proposed.patch)를 제공한다. 제품/원문에는 적용하지 않았으며 `git apply --check`는 통과했다.

**필요한 검증:** 배포용 키와 분리된 시험 fixture로 정상 자체 서명, 다른 인증서, 서명 후 변조, unsigned 항목 추가를 나누어 판정한다. 이번 회차에는 키를 만들지 않아 이 fixture 시험은 수행하지 않았다.

## 5. 정책 링크 시험의 의미와 제안

현재 코드에서 개인정보처리방침/약관 버튼이 각각 맞는 URL로 연결되고 `openUrl=false`이면 주소를 표시하는 것은 확인했다. 주입 지점을 만든 것도 유효하다. 새 문자열 시험은 원래 제시했던 **호출부 삭제** 변이를 검출한다.

그러나 여전히 화면 동작 시험은 아니다. 격리된 소스에 다음 변이를 넣어 실제 `PolicyLinkTest`를 실행했다:

| 변이 | 결과 |
|---|---|
| PolicyLinks 호출 삭제 | **실패 1개**: 개선 확인 |
| 같은 호출 줄을 `//` 주석 처리 | **6개 모두 통과** |
| 개인정보 버튼을 `TERMS_URL`로 연결 | **6개 모두 통과** |
| `failedUrl` 갱신 제거 | **6개 모두 통과** |

이 결과를 현재 제품에서 링크가 사라졌거나 잘못 연결됐다는 결함으로 세지는 않는다. **「소스를 읽어 실제로 그려지는지 본다」는 시험 설명은 여전히 과하다.** 주석 안 문자열과 실행 코드를 구분하지 못한다. 정규식 조건을 계속 늘리는 것보다 클릭 결과를 검증하는 편이 낫다.

[Compose 클릭 시험 3개 제안](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrff-policy-links-proposed-test.kt)을 만들었다. 개인정보/약관의 목적 URL과 실패 주소 표시를 검사한다. 격리 복사본의 androidTest 경로에서 **`:app:compileDebugAndroidTestKotlin` 성공**을 확인했다. 기기/에뮬레이터 실행은 하지 않았다.

이 세 시험은 PolicyLinks 컴포넌트를 검증한다. SettingsScreen에서 컴포넌트 호출 자체를 없애는 변이까지 잡으려면 **설정 화면 전체를 띄워 두 버튼이 존재함을 검사하는 한 시험**이 추가로 필요하다. 런처를 가짜로 주입한 시험은 실제 Intent adapter나 외부 브라우저 동작도 대신하지 않는다. 요청서는 Compose 시험이 androidTest에 있다고 표현했지만, 대상 커밋에는 해당 의존성만 있고 `app/src/androidTest` 소스 디렉터리는 없었다.

## 6. 서명 검사 코드를 지금 넣어야 하는가

키가 없어도 **없을 때 실패하는 경로와 debug 허용 경로**는 지금 시험할 수 있다. 키가 있어야만 검사 코드를 만들 수 있는 것은 아니다. 다만 이번 변경에는 실제 build.gradle.kts 배선이 없으므로 release 서명 차단이 구현됐다고 승인하지 않는다.

권고는 별도 작은 변경으로 검증 task를 넣는 것이다:

1. 속성 네 개를 `orNull`로 읽어 debug 구성 단계에서 불필요하게 `.get()`로 실패하지 않게 한다. 현재 문서 예시는 파일 속성만 있고 비밀번호 등이 없으면 구성 중 `.get()`가 실패할 수 있다.
2. release 전용 검증 task에서 파일 존재와 필수 속성을 검사한다. 비밀값 자체는 로그에 쓰지 않는다.
3. 검증 task를 실제 release 패키징/서명 task의 **선행 의존성**으로 연결한다. `assembleRelease.doFirst`는 그 task의 의존 작업보다 먼저 실행된다는 뜻이 아니다.
4. 모두 없음·일부만 있음·파일 없음·debug 빌드 허용을 먼저 검사하고, 이후 시험 키로 정상/틀린 암호 및 실제 인증서 지문을 검사한다.

문서의 「설정 누락 시 자동으로 debug 서명으로 떨어질 수 있다」는 설명도 실제 증거와 구분해야 한다. 현재 앱의 release에는 signingConfig가 없고 debug 서명으로의 자동 대체 코드를 추가한 적도 없다. 여기서 지켜야 할 계약은 **명시적으로 올바른 서명을 갖춘 release만 허용한다**는 것이다. 이번에는 서명 예시를 실제 Gradle에 적용하거나 release 산출물을 만들지 않았다.

## 7. 실행 결과와 전달 파일

정규 검증 명령(대상 커밋의 격리 복사본):

```powershell
.\gradlew.bat --offline --no-build-cache --rerun-tasks `
  :dsp:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

- **DSP 620 + app 848 = 1,468개**, failure/error/skipped 모두 0. XML 집계. 58 tasks 모두 executed.
- 디버그 APK 생성 성공. 설치/실행은 하지 않았다.
- lint 성공: **0 errors / 11 warnings / 4 hints**. 변이 시험 종료·원본 복구 후 lint 분석/보고 task를 다시 실행해 재확인했다.
- 수동 JVM 부분집합도 실행했지만 위 정규 시험 수에 더하지 않았다.
- 정확 창 활성화를 제거하는 별도 클래스 변이에서 **16개 중 4개 실패**: 편입한 독립 회귀 3개와 새 CleanWindow 경계 회귀가 모두 검출했다. 제품 소스에는 이 변이를 적용하지 않았다.

전달 파일:

- [통합 probe](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrff-independent-probe.kt): 이전 회귀에 첫 입력 전 빈 블록 반복/회복을 추가. 이번 실행은 `expect.safety.fixed`, `expect.window.fixed`, `expect.valid.clock.fixed` 모두 true.
- [2시간 표본 산술 실험](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrff-window-soak.kt).
- [할당/처리 시간 비교 실험](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrff-window-allocation.kt).
- [정책 링크 시험 제안](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrff-policy-links-proposed-test.kt), [서명 문서 수정 제안](D:/Cowork/SELAH-RTA/docs/review/2026-09-29-uisrff-signing-doc-proposed.patch).

로그는 `build/independent-review/uisrff-20260929/`의 `gradle-full-results.txt`, `lint-confirm.txt`, `probe-output.txt`, `soak-output.txt`, `allocation-current.txt`, `allocation-previous.txt`, `mutation-disable-exact.txt`, `policy-*.txt`에 보관했다. proposal 컴파일은 `policy-proposal-compile.txt`다.

**이제 이번 코드 수정은 승인으로 전환할 수 있다.** 문서 Low와 시험 보강 권고를 별도 후속 작업으로 관리하고, 교정기 부재를 이유로 이미 검증한 수치/상태 수정까지 다시 보류할 필요는 없다. 음향 교정과 기기 장시간 부하는 실제 측정 정확도·출시 준비의 별도 확인 항목으로 남는다.
