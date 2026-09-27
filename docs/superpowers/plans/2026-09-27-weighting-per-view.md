# A/C/Z 가중치 화면별 설정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 가중치 이름·단위를 한 꼴로 통일하고, 음압·PEAK·주파수분석 셋이 각자의 가중치를 갖게 하며, 분석 화면의 가중치는 표기만이 아니라 **FFT 칸마다 실제로** 걸리게 한다.

**Architecture:** DSP 는 이미 IEC 61672-1 대로 biquad 로 가중을 구현하고 있고 `MultiWeightEngine` 이 A·C·Z 를 동시에 돌린다. 그래서 음압·PEAK 쪽은 「어느 결과를 집어 오느냐」의 문제이고, 새로 만드는 계산은 (1) 가중 필터 **뒤**의 PEAK 누적 하나와 (2) 가중의 **칸별 선형 이득 배열** 하나뿐이다. 후자는 `RtaEngine` 이 이미 갖고 있는 `binCorrection`(마이크 곡선)과 **미리 곱해 하나로 합쳐** 두므로 뜨거운 반복문의 곱셈 수가 늘지 않는다.

**Tech Stack:** Kotlin · Android (Compose) · JUnit4 · Gradle. 모듈은 `dsp`(순수 Kotlin, 안드로이드 의존성 금지)와 `app`.

**스펙:** `docs/superpowers/specs/2026-09-27-weighting-per-view-design.md`
**지시서:** `docs/instructions/2026-09-27-weighting-acz-guide.md`

## Global Constraints

- **이름은 `A-weighting` · `C-weighting` · `Z-weighting` 셋뿐이다.** 「무가중」·「가중 없음」·「Flat」·「Z 가중」을 화면 문구에 쓰지 않는다. 평탄하다는 사실은 이름이 아니라 설명 줄에서 말한다.
- **단위는 `dB(A)` · `dB(C)` · `dB(Z)`.** 괄호 없는 `dBA`/`dBC` 를 새로 쓰지 않는다.
- **표기만 바뀌고 값이 그대로인 일이 없어야 한다**(지시서 §18). 분석 가중을 A 로 바꾸면 RTA 저역 막대가 실제로 내려가야 한다.
- **`dsp` 모듈에 안드로이드 의존성을 넣지 않는다.** `android.*` import 금지.
- **소스 편집은 Edit/Write 도구만.** PowerShell `Set-Content`/`Out-File` 은 한글 UTF-8 을 깨뜨린다.
- **`git add` 에 디렉터리를 넘기지 않는다.** 파일을 하나씩 적는다.
- **커밋 메시지는 한국어.** 마지막 줄에 `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.
- **변이 시험(고친 것을 되돌려 시험이 깨지는지 보기)에는 반드시 `--rerun-tasks`.** 캐시된 결과가 통과로 나온다.
- **빌드 명령**: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` 를 먼저 설정한 뒤 `.\gradlew.bat`.
- **클리핑 판정을 손대지 않는다.** 클리핑은 ADC 에서 일어나는 일이라 가중 **전** 파형으로 재야 한다.
- **하울링 탐지(`SpectrumSink`)에 가중을 걸지 않는다.** 봉우리가 둘레보다 얼마나 솟았는지를 보는 것이라 기울기를 씌우면 판정이 흔들린다.

---

## File Structure

| 파일 | 맡은 일 |
|---|---|
| `dsp/.../Weighting.kt` | 가중 이름·단위, L 기호 조립, **칸별 선형 이득 배열** |
| `dsp/.../SplEngine.kt` | 가중 필터 **뒤** PEAK 누적 하나 추가 |
| `dsp/.../RtaEngine.kt` | 가중 배열을 `binCorrection` 과 미리 곱해 합침 |
| `app/.../settings/MeterSettings.kt` | 가중 셋, `LeqWindow.Session`, FFT 크기 |
| `app/.../ui/CaptureViewModel.kt` | 셋을 골라 화면 상태로, Session Leq 배선 |
| `app/.../ui/screens/HistorySettingsScreens.kt` | 세 줄 + 설명 줄 + 「기본값으로」 |
| `app/.../ui/screens/MeasureScreen.kt` | L 기호 표기, PEAK 가중 |
| `app/.../ui/screens/AnalyzeScreens.kt` | 차트 아래 한 줄, 세로축 기본 자동 |
| `app/.../ui/screens/FrScreen.kt` | `dB(Z) 고정` 표시 |
| `app/.../recording/SessionMeta.kt` | `peakWeighting` · `analysisWeighting` |
| `app/.../recording/MeasurementReport.kt` | 「어떤 잣대로 쟀나」에 세 줄 |

---

## Task 1: 이름과 단위를 한 꼴로

**Files:**
- Modify: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/Weighting.kt:11-20`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightingLabelTest.kt` (새로 만듦)

**Interfaces:**
- Produces: `Weighting.labelKo`(`"A-weighting"`/`"C-weighting"`/`"Z-weighting"`), `Weighting.unitSuffix`(`"dB(A)"`/`"dB(C)"`/`"dB(Z)"`)

- [ ] **Step 1: 실패하는 시험을 쓴다**

`dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightingLabelTest.kt` 를 만든다:

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **같은 것을 네 가지로 부르지 않는다**(담당자 지시 2026-09-27).
 *
 * 「Z 가중」·「무가중」·「가중 없음」·「Flat」이 섞여 쓰이던 탓에
 * 「고정된 Z 와 무가중이 다른 것인가」라는 물음이 실제로 나왔다.
 * 이름은 하나이고, 평탄하다는 사실은 설명 줄이 말한다.
 */
class WeightingLabelTest {

    @Test
    fun `이름이 -weighting 꼴이다`() {
        assertEquals("A-weighting", Weighting.A.labelKo)
        assertEquals("C-weighting", Weighting.C.labelKo)
        assertEquals("Z-weighting", Weighting.Z.labelKo)
    }

    /** dBA 만 괄호가 없던 어긋남이 이 시험이 있는 까닭이다. */
    @Test
    fun `단위가 모두 같은 꼴이다`() {
        assertEquals("dB(A)", Weighting.A.unitSuffix)
        assertEquals("dB(C)", Weighting.C.unitSuffix)
        assertEquals("dB(Z)", Weighting.Z.unitSuffix)
    }

    @Test
    fun `모든 가중의 단위가 dB 괄호 한 글자 꼴이다`() {
        Weighting.entries.forEach { w ->
            assertTrue(
                "${w.name} 의 단위가 꼴에 맞지 않는다: ${w.unitSuffix}",
                Regex("""^dB\([ACZ]\)$""").matches(w.unitSuffix),
            )
        }
    }

    /** 이름 자리에 설명을 끼워 넣으면 다시 두 가지가 된다. */
    @Test
    fun `이름에 무가중 같은 옛말이 섞이지 않는다`() {
        val banned = listOf("무가중", "가중 없", "가중없", "Flat", "flat")
        Weighting.entries.forEach { w ->
            banned.forEach { b ->
                assertTrue(
                    "${w.name} 의 이름에 「$b」이 들어 있다: ${w.labelKo}",
                    !w.labelKo.contains(b),
                )
            }
        }
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

```
.\gradlew.bat :dsp:test --tests '*WeightingLabelTest*' --rerun-tasks
```

기대: `이름이 -weighting 꼴이다` 가 `expected:<A-weighting> but was:<A 가중>` 로 실패.

- [ ] **Step 3: 최소한으로 고친다**

`Weighting.kt` 의 enum 본문을 바꾼다:

```kotlin
enum class Weighting(val labelKo: String, val unitSuffix: String) {
    /** 사람 귀의 감도를 흉내 낸다. 음압 규제와 청력 기준이 쓰는 가중이다. */
    A("A-weighting", "dB(A)"),

    /** 저역을 덜 깎는다. 큰 소리와 피크를 볼 때 쓴다. */
    C("C-weighting", "dB(C)"),

    /**
     * 깎지도 올리지도 않는다(평탄).
     *
     * **이름은 「Z-weighting」 하나다.** 「무가중」·「가중 없음」·「Flat」을
     * 이름 자리에 쓰지 않는다 — 같은 것을 네 가지로 부르던 탓에 「고정된
     * Z 와 무가중이 다른 것인가」라는 물음이 실제로 나왔다(2026-09-27).
     * 평탄하다는 사실은 고를 때 나오는 설명 줄에서 말한다.
     */
    Z("Z-weighting", "dB(Z)"),
}
```

- [ ] **Step 4: 통과를 확인하고, 옛 표기를 쓰던 시험을 고친다**

```
.\gradlew.bat :dsp:test :app:testDebugUnitTest --rerun-tasks
```

`dBA`/`dBC` 를 문자열로 기대하던 시험이 있으면 새 표기로 고친다. **화면 문구를 옛 표기에 맞추지 않는다** — 시험 쪽을 고친다.

- [ ] **Step 5: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/Weighting.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightingLabelTest.kt
git commit -m "..."
```

메시지:
```
refactor(가중): 이름을 A-weighting · C-weighting · Z-weighting 으로 통일

「Z 가중」·「무가중」·「가중 없음」·「Flat」이 섞여 쓰이던 탓에 「고정된
Z 와 무가중이 다른 것인가」라는 물음이 나왔다(담당자, 2026-09-27).
단위도 dBA·dBC 만 괄호가 없어 같이 맞췄다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 2: L 기호 표기 (LAeq · LZpeak)

**Files:**
- Modify: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/Weighting.kt` (파일 끝에 덧붙임)
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightingLabelTest.kt` (Task 1 이 만든 파일에 덧붙임)

**Interfaces:**
- Consumes: Task 1 의 `Weighting`
- Produces: `fun Weighting.leqLabel(windowKo: String): String`, `fun Weighting.peakLabel(): String`

- [ ] **Step 1: 실패하는 시험을 쓴다**

`WeightingLabelTest.kt` 안에 덧붙인다:

```kotlin
    // ── L 기호 ──────────────────────────────────────────

    /**
     * **계산에 쓴 가중에서 글자가 나온다**(지시서 §10).
     *
     * 화면이 손으로 "LAeq" 를 적으면 가중을 C 로 바꾼 뒤에도 A 라고
     * 적혀 있게 된다 — 표기와 실제가 어긋나는 바로 그 사고다.
     */
    @Test
    fun `Leq 이름이 가중 글자를 따른다`() {
        assertEquals("LAeq 1분", Weighting.A.leqLabel("1분"))
        assertEquals("LCeq 1분", Weighting.C.leqLabel("1분"))
        assertEquals("LZeq 전체", Weighting.Z.leqLabel("전체"))
    }

    @Test
    fun `PEAK 이름이 가중 글자를 따른다`() {
        assertEquals("LApeak", Weighting.A.peakLabel())
        assertEquals("LCpeak", Weighting.C.peakLabel())
        assertEquals("LZpeak", Weighting.Z.peakLabel())
    }
```

- [ ] **Step 2: 실패를 확인한다**

```
.\gradlew.bat :dsp:test --tests '*WeightingLabelTest*' --rerun-tasks
```

기대: 컴파일 실패 — `leqLabel` 이 없다.

- [ ] **Step 3: 최소한으로 고친다**

`Weighting.kt` 파일 끝에 덧붙인다:

```kotlin
/**
 * `LAeq 1분` 처럼 L 기호 꼴로 적는다(지시서 §10).
 *
 * **화면이 글자를 손으로 적지 않게 하려고 여기 둔다.** 손으로 적으면
 * 가중을 바꾼 뒤에도 옛 글자가 남아, 표기와 계산이 어긋난다.
 */
fun Weighting.leqLabel(windowKo: String): String = "L${name}eq $windowKo"

/** `LZpeak` 처럼 적는다. PEAK 은 창이 없어 뒤에 붙는 말이 없다. */
fun Weighting.peakLabel(): String = "L${name}peak"
```

- [ ] **Step 4: 통과를 확인한다**

```
.\gradlew.bat :dsp:test --tests '*WeightingLabelTest*' --rerun-tasks
```

기대: PASS.

- [ ] **Step 5: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/Weighting.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightingLabelTest.kt
git commit -m "..."
```

메시지:
```
feat(가중): LAeq · LZpeak 꼴 이름을 가중에서 뽑아낸다

화면이 손으로 "LAeq" 를 적으면 가중을 바꾼 뒤에도 A 라고 적혀 있게
된다. 지시서 §10 이 「계산에 실제 적용된 가중과 UI 표기가 반드시
일치해야 한다」고 못박은 자리다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 3: 가중의 칸별 선형 이득

**Files:**
- Modify: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/Weighting.kt` (파일 끝에 덧붙임)
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightBinGainTest.kt` (새로 만듦)

**Interfaces:**
- Consumes: Task 1 의 `Weighting`, 기존 `weightingResponseDb(w, hz)`
- Produces: `fun weightBinGain(w: Weighting, fftSize: Int, sampleRate: Int): DoubleArray?`
  - 길이 `fftSize / 2 + 1`. 칸 `i` 의 중심 주파수는 `i * sampleRate / fftSize`.
  - **전력에 곱할 값**이라 진폭비의 제곱이다.
  - `Weighting.Z` 이면 `null` — 곱할 것이 없다는 뜻이다.

> **먼저 확인할 것**: `Weighting.kt` 에 주파수 하나의 가중 응답을 dB 로 내는
> 함수가 이미 있는지 본다(`WeightingTest` 가 규격과 견주는 데 쓰고 있다).
> 이름이 `weightingResponseDb` 가 아니면 **그 이름을 쓴다** — 새로 만들지 않는다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightBinGainTest.kt` 를 만든다:

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * **가중을 FFT 칸마다 건다**(지시서 §18).
 *
 * 밴드 중심값 하나를 대역 전체에 걸면 저역에서 1dB 가까이 어긋난다 —
 * 1/3 옥타브 대역은 ±11.6% 폭이고, 저역에서 A-weighting 의 기울기는
 * 옥타브당 12dB 에 가까워 한 대역 안에서 4dB 가 달라지기 때문이다.
 *
 * 여기서 돌려주는 것은 **전력에 곱할 값**이다. 진폭비가 아니다 —
 * 헷갈리면 모든 가중이 정확히 두 배로 세진다.
 */
class WeightBinGainTest {

    private val fftSize = 4096
    private val sampleRate = 48_000

    private fun binOf(hz: Double) = Math.round(hz * fftSize / sampleRate).toInt()

    /** 전력 이득을 dB 로 되돌린다. 전력이므로 10log10 이다. */
    private fun gainDb(g: Double) = 10.0 * log10(g)

    @Test
    fun `Z 는 곱할 것이 없다`() {
        assertNull(weightBinGain(Weighting.Z, fftSize, sampleRate))
    }

    @Test
    fun `길이가 칸 수와 같다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        assertEquals(fftSize / 2 + 1, g.size)
    }

    /** 규격이 정한 기준점이다. 여기가 틀리면 모든 값이 통째로 밀린다. */
    @Test
    fun `1kHz 칸은 이득이 1 이다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        assertEquals(0.0, gainDb(g[binOf(1000.0)]), 0.05)
    }

    /**
     * **전력 이득이다.** 진폭비를 그대로 돌려주면 dB 가 절반으로 나온다 —
     * A-weighting 의 100Hz 는 -19.1dB 인데 -9.6dB 로 보이게 된다.
     */
    @Test
    fun `전력 이득이라 100Hz 에서 규격과 같은 dB 가 나온다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        // IEC 61672-1: A-weighting 100Hz = -19.1 dB
        assertEquals(-19.1, gainDb(g[binOf(100.0)]), 0.3)
    }

    @Test
    fun `C 는 100Hz 를 거의 깎지 않는다`() {
        val g = weightBinGain(Weighting.C, fftSize, sampleRate)!!
        // IEC 61672-1: C-weighting 100Hz = -0.3 dB
        assertEquals(-0.3, gainDb(g[binOf(100.0)]), 0.2)
    }

    /** 0Hz 칸은 log 가 무너지는 자리다. 셈이 NaN 으로 새면 화면이 빈다. */
    @Test
    fun `0Hz 칸도 유한한 값이다`() {
        Weighting.entries.filter { it != Weighting.Z }.forEach { w ->
            val g = weightBinGain(w, fftSize, sampleRate)!!
            assertTrue("${w.name} 의 0Hz 칸이 ${g[0]} 이다", g[0].isFinite())
            assertTrue("${w.name} 의 0Hz 칸이 음수다: ${g[0]}", g[0] >= 0.0)
        }
    }

    /**
     * **이것이 이 파일의 한가운데다.** 밴드 중심 하나를 대역에 거는 것과
     * 칸마다 거는 것이 실제로 다름을 못박는다 — 「대충 걸어도 같다」는
     * 생각이 들 때 이 시험이 막는다.
     */
    @Test
    fun `칸마다 거는 것이 밴드 중심에 거는 것과 다르다`() {
        val g = weightBinGain(Weighting.A, fftSize, sampleRate)!!
        val band = ThirdOctave.BANDS.indexOfFirst { abs(it - 63.0) < 1.0 }
        assertTrue("63Hz 대역을 못 찾았다", band >= 0)

        val lo = ThirdOctave.lowerEdge(band)
        val hi = ThirdOctave.upperEdge(band)
        val loGainDb = gainDb(g[binOf(lo)])
        val hiGainDb = gainDb(g[binOf(hi)])

        // 한 대역 안에서 2dB 넘게 달라진다 — 중심값 하나로 뭉갤 수 없다.
        assertTrue(
            "63Hz 대역 안의 가중 차이가 ${abs(hiGainDb - loGainDb)}dB 뿐이다",
            abs(hiGainDb - loGainDb) > 2.0,
        )
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

```
.\gradlew.bat :dsp:test --tests '*WeightBinGainTest*' --rerun-tasks
```

기대: 컴파일 실패 — `weightBinGain` 이 없다.

- [ ] **Step 3: 최소한으로 고친다**

`Weighting.kt` 파일 끝에 덧붙인다:

```kotlin
/**
 * 가중의 **칸별 전력 이득**. RTA·Spectrum 이 칸마다 곱한다.
 *
 * ## 왜 칸마다인가
 *
 * 1/3 옥타브 대역은 ±11.6% 폭이다. 저역에서 A-weighting 의 기울기는
 * 옥타브당 12dB 에 가까워 **한 대역 안에서 4dB 가 달라진다.** 대역
 * 중심값 하나를 대역 전체에 걸면 대역 안의 소리 모양에 따라 1dB 가까이
 * 어긋난다. 칸마다 걸고 나서 묶으면 근사가 아니다.
 *
 * ## 전력 이득이다
 *
 * 돌려주는 값은 **전력에 곱하는 것**이라 진폭비의 제곱이다. 진폭비를
 * 그대로 쓰면 dB 가 절반으로 나온다 — 100Hz 에서 -19.1dB 이어야 할
 * A-weighting 이 -9.6dB 로 보인다.
 *
 * @return Z 이면 null — **곱할 것이 없다**는 뜻이다. 1 로 채운 배열을
 *   돌려주면 아무 일도 안 하는 곱셈을 칸마다 하게 된다.
 */
fun weightBinGain(w: Weighting, fftSize: Int, sampleRate: Int): DoubleArray? {
    require(fftSize > 0) { "FFT 길이가 0 이하다: $fftSize" }
    require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
    if (w == Weighting.Z) return null

    val binCount = fftSize / 2 + 1
    val binWidth = sampleRate.toDouble() / fftSize
    return DoubleArray(binCount) { bin ->
        val hz = bin * binWidth
        // **0Hz 는 응답을 셈할 수 없다.** A·C 둘 다 0Hz 에서 이득이 0 으로
        // 가므로 0 을 준다. log 를 그대로 태우면 NaN 이 번져 화면이 빈다.
        if (hz <= 0.0) {
            0.0
        } else {
            val db = weightingResponseDb(w, hz)
            // dB → 전력비. 진폭비(10^(db/20))의 제곱이다.
            Math.pow(10.0, db / 10.0)
        }
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

```
.\gradlew.bat :dsp:test --tests '*WeightBinGainTest*' --rerun-tasks
```

기대: 7개 모두 PASS.

- [ ] **Step 5: 변이로 확인한다**

`Math.pow(10.0, db / 10.0)` 을 `Math.pow(10.0, db / 20.0)` 으로 바꿔 돌린다.
기대: `전력 이득이라 100Hz 에서 규격과 같은 dB 가 나온다` 가 **실패**.
확인한 뒤 되돌린다.

```
.\gradlew.bat :dsp:test --tests '*WeightBinGainTest*' --rerun-tasks
```

- [ ] **Step 6: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/Weighting.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightBinGainTest.kt
git commit -m "..."
```

메시지:
```
feat(가중): 가중의 칸별 전력 이득 — 분석 화면이 칸마다 곱할 것

밴드 중심값 하나를 대역 전체에 걸면 저역에서 1dB 가까이 어긋난다.
1/3 옥타브 대역은 ±11.6% 폭이고 저역에서 A-weighting 의 기울기는
옥타브당 12dB 에 가까워, 한 대역 안에서 4dB 가 달라지기 때문이다.

전력 이득이라 진폭비의 제곱이다. 진폭비를 그대로 쓰면 dB 가 절반으로
나온다 — 변이 시험으로 못박았다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 4: 분석 화면에 가중을 실제로 건다

**Files:**
- Modify: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/RtaEngine.kt:166`(필드), `:190-192`(`setCurve`), `:250`(`toBandPower` 부르는 줄), `:285`(`updateSpectrum`)
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/RtaWeightingTest.kt` (새로 만듦)

**Interfaces:**
- Consumes: Task 3 의 `weightBinGain(w, fftSize, sampleRate): DoubleArray?`
- Produces: `RtaEngine.setAnalysisWeighting(w: Weighting)`

**핵심 설계**: 곡선 배열과 가중 배열을 **미리 곱해 하나(`combined`)로 합쳐** 둔다. 뜨거운 반복문에 들어가는 곱셈 수가 지금과 같다. 둘 다 없으면 `combined` 가 null 이라 곱셈 자체가 없다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`dsp/src/test/kotlin/kr/joa/selahrta/dsp/RtaWeightingTest.kt` 를 만든다:

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * **표기만 바뀌고 값이 그대로인 일이 없어야 한다**(지시서 §18).
 *
 * 가중치를 고르는 기능을 만들 때 가장 쉬운 실패는, 화면의 단위 글자만
 * 바꾸고 DSP 는 그대로 두는 것이다. 그러면 「A 로 봐도 C 로 봐도 같은
 * 숫자」가 되어 기능 자체가 거짓말이 된다.
 */
class RtaWeightingTest {

    private val sampleRate = 48_000
    private val fftSize = 4096

    /** 그 주파수의 순음을 [seconds] 초만큼 만든다. */
    private fun tone(hz: Double, seconds: Double): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { i ->
            (0.2 * sin(2.0 * PI * hz * i / sampleRate)).toFloat()
        }
    }

    /** 순음을 흘려 그 대역의 값을 읽는다. */
    private fun bandDbOf(w: Weighting, hz: Double): Double {
        val e = RtaEngine(sampleRate)
        e.setAnalysisWeighting(w)
        val s = tone(hz, 1.0)
        e.process(s, s.size)
        val f = e.latest ?: error("프레임이 없다")
        val band = ThirdOctave.BANDS.indexOfFirst { kotlin.math.abs(it - hz) < hz * 0.06 }
        assertTrue("${hz}Hz 대역을 못 찾았다", band >= 0)
        return f.bandsDbfs[band]
    }

    /** **이것이 이 파일의 한가운데다.** 지시서 §18 그 자체다. */
    @Test
    fun `A 를 고르면 저역 대역이 실제로 내려간다`() {
        val z = bandDbOf(Weighting.Z, 63.0)
        val a = bandDbOf(Weighting.A, 63.0)
        // IEC 61672-1: A-weighting 63Hz = -26.2 dB
        assertEquals("63Hz 에서 A 가중이 걸리지 않았다", -26.2, a - z, 1.5)
    }

    @Test
    fun `C 를 고르면 저역이 A 보다 덜 깎인다`() {
        val z = bandDbOf(Weighting.Z, 63.0)
        val c = bandDbOf(Weighting.C, 63.0)
        // IEC 61672-1: C-weighting 63Hz = -0.8 dB
        assertEquals("63Hz 에서 C 가중이 걸리지 않았다", -0.8, c - z, 1.0)
    }

    /** 1kHz 는 규격의 기준점이다. 어느 가중에서도 움직이면 안 된다. */
    @Test
    fun `1kHz 는 가중을 바꿔도 그대로다`() {
        val z = bandDbOf(Weighting.Z, 1000.0)
        assertEquals(z, bandDbOf(Weighting.A, 1000.0), 0.3)
        assertEquals(z, bandDbOf(Weighting.C, 1000.0), 0.3)
    }

    /** **회귀 방지.** Z 는 지금까지의 동작과 한 치도 달라지면 안 된다. */
    @Test
    fun `Z 는 가중을 걸기 전과 똑같다`() {
        val before = RtaEngine(sampleRate)
        val s1 = tone(250.0, 1.0)
        before.process(s1, s1.size)
        val expected = before.latest!!.bandsDbfs

        val after = RtaEngine(sampleRate)
        after.setAnalysisWeighting(Weighting.Z)
        val s2 = tone(250.0, 1.0)
        after.process(s2, s2.size)
        val actual = after.latest!!.bandsDbfs

        expected.indices.forEach { i ->
            assertEquals("밴드 $i 가 달라졌다", expected[i], actual[i], 1e-9)
        }
    }

    /**
     * **하울링 탐지는 가중 전 스펙트럼을 본다.**
     *
     * 봉우리가 둘레보다 얼마나 솟았는지를 보는 것이라, 기울기를 씌우면
     * 솟은 정도가 달라져 판정이 흔들린다.
     */
    @Test
    fun `싱크가 받는 스펙트럼은 가중을 타지 않는다`() {
        fun sinkPowerAt(w: Weighting): DoubleArray {
            val e = RtaEngine(sampleRate)
            e.setAnalysisWeighting(w)
            var got: DoubleArray? = null
            e.addSpectrumSink { p -> if (got == null) got = p.copyOf() }
            val s = tone(63.0, 1.0)
            e.process(s, s.size)
            return got ?: error("싱크가 받지 못했다")
        }
        val z = sinkPowerAt(Weighting.Z)
        val a = sinkPowerAt(Weighting.A)
        z.indices.forEach { i ->
            assertEquals("칸 $i 가 가중을 탔다", z[i], a[i], 1e-12)
        }
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

```
.\gradlew.bat :dsp:test --tests '*RtaWeightingTest*' --rerun-tasks
```

기대: 컴파일 실패 — `setAnalysisWeighting` 이 없다.

- [ ] **Step 3: 최소한으로 고친다**

`RtaEngine.kt` 에서 `binCorrection` 필드(166줄 근처)를 셋으로 늘린다:

```kotlin
    /** 마이크 곡선의 칸별 보정. 곡선이 없으면 null. */
    private var curveCorrection: DoubleArray? = null

    /** 분석 가중의 칸별 전력 이득. Z 면 null. */
    private var weightCorrection: DoubleArray? = null

    /**
     * 위 둘을 **미리 곱해 둔 것**. 뜨거운 반복문은 이것만 본다.
     *
     * 곱셈을 반복문 안에서 두 번 하지 않으려고 합쳐 둔다 — 칸마다
     * 초당 수만 번 도는 자리다. 둘 다 없으면 null 이라 곱셈 자체가 없다.
     */
    private var binCorrection: DoubleArray? = null

    /** 지금 걸린 분석 가중. 화면이 물어볼 수 있게 둔다. */
    @Volatile
    var analysisWeighting: Weighting = Weighting.Z
        private set
```

`setCurve` 를 고치고 `setAnalysisWeighting` 과 합치는 함수를 더한다:

```kotlin
    fun setCurve(curve: CalibrationCurve?) {
        curveCorrection = curve?.binCorrectionLinear(fftSize, sampleRateHz, CURVE_REFERENCE_HZ)
        rebuildCorrection()
        // (이 아래의 세대 증가·평활 초기화는 지금 코드 그대로 둔다)
    }

    /**
     * 분석 화면의 가중을 바꾼다.
     *
     * **화면의 글자만 바꾸지 않는다**(지시서 §18). 칸별 이득을 새로
     * 만들어 곡선과 합친다.
     */
    fun setAnalysisWeighting(w: Weighting) {
        if (w == analysisWeighting) return
        analysisWeighting = w
        weightCorrection = weightBinGain(w, fftSize, sampleRateHz)
        rebuildCorrection()
    }

    /**
     * 곡선과 가중을 하나로 합친다. **둘 다 없으면 null 이다** — 1 로 채운
     * 배열을 두면 아무 일도 안 하는 곱셈을 칸마다 하게 된다.
     */
    private fun rebuildCorrection() {
        val c = curveCorrection
        val w = weightCorrection
        binCorrection = when {
            c == null && w == null -> null
            c == null -> w
            w == null -> c
            else -> DoubleArray(c.size) { c[it] * w[it] }
        }
    }
```

`toBandPower` 를 부르는 줄과 `updateSpectrum()` 은 **고치지 않는다** — 둘 다 이미 `binCorrection` 을 본다.

- [ ] **Step 4: 통과를 확인한다**

```
.\gradlew.bat :dsp:test --rerun-tasks
```

기대: `RtaWeightingTest` 5개 모두 PASS, 기존 `dsp` 시험 전부 PASS.

- [ ] **Step 5: 변이로 확인한다**

`setAnalysisWeighting` 의 `weightCorrection = weightBinGain(...)` 줄을
`weightCorrection = null` 로 바꿔 돌린다(=이름만 바꾸고 DSP 는 그대로인 상태).
기대: `A 를 고르면 저역 대역이 실제로 내려간다` 가 **실패**.
확인한 뒤 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/RtaEngine.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/RtaWeightingTest.kt
git commit -m "..."
```

메시지:
```
feat(분석): RTA·Spectrum 에 가중을 칸마다 실제로 건다

지시서 §18 이 못박은 자리다 — 화면의 단위 글자만 바꾸고 DSP 는 그대로
두면 「A 로 봐도 C 로 봐도 같은 숫자」가 되어 기능이 거짓말이 된다.
이름만 바꾸는 상태로 되돌려 시험이 깨지는 것을 확인했다.

곡선과 가중을 미리 곱해 하나로 합쳐 둔다. 뜨거운 반복문의 곱셈 수가
지금과 같고, 둘 다 없으면 null 이라 곱셈 자체가 없다.

하울링 탐지는 지금처럼 가중 전 스펙트럼을 받는다. 봉우리가 둘레보다
얼마나 솟았는지를 보는 것이라 기울기를 씌우면 판정이 흔들린다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 5: 가중 뒤의 PEAK

**Files:**
- Modify: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/SplEngine.kt`(`SplFrame` 에 필드 하나, `process` 에 누적 하나, `reset` 에 초기화 하나)
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightedPeakTest.kt` (새로 만듦)

**Interfaces:**
- Consumes: Task 1 의 `Weighting`
- Produces: `SplFrame.weightedPeakDbfs: Dbfs`
  - `peakDbfs`(가중 전, 클리핑 판정용)는 **이름도 뜻도 그대로 둔다**.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightedPeakTest.kt` 를 만든다:

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * **PEAK 은 두 가지다**(지시서 §5, §16).
 *
 * 하나는 클리핑을 잡는 가중 **전** 파형의 최고값이고, 다른 하나는
 * 화면에 적는 가중 **뒤** 값이다. 하나로 뭉뚱그리면 둘 중 하나가 틀린다 —
 * 가중 뒤 값으로 클리핑을 재면 저역이 깎여 ADC 가 포화한 것을 놓친다.
 */
class WeightedPeakTest {

    private val sampleRate = 48_000

    private fun tone(hz: Double, amp: Double, seconds: Double): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { i -> (amp * sin(2.0 * PI * hz * i / sampleRate)).toFloat() }
    }

    private fun frameOf(w: Weighting, hz: Double, amp: Double): SplFrame {
        val e = SplEngine(sampleRate, w, TimeWeight.Fast)
        val s = tone(hz, amp, 1.0)
        return e.process(s, s.size)
    }

    /** Z 는 통과 필터다. 두 값이 같아야 지금 동작이 안 바뀐다. */
    @Test
    fun `Z 에서는 가중 전후 PEAK 이 같다`() {
        val f = frameOf(Weighting.Z, 1000.0, 0.5)
        assertEquals(f.peakDbfs.value, f.weightedPeakDbfs.value, 0.1)
    }

    /** **이것이 이 파일의 한가운데다.** A 는 저역을 크게 깎는다. */
    @Test
    fun `A 에서는 저역 PEAK 이 가중 전보다 낮다`() {
        val f = frameOf(Weighting.A, 63.0, 0.5)
        assertTrue(
            "가중 뒤 PEAK(${f.weightedPeakDbfs.value})이 " +
                "가중 전(${f.peakDbfs.value})보다 낮지 않다",
            f.weightedPeakDbfs.value < f.peakDbfs.value - 15.0,
        )
    }

    @Test
    fun `C 는 A 보다 저역 PEAK 을 덜 깎는다`() {
        val a = frameOf(Weighting.A, 63.0, 0.5)
        val c = frameOf(Weighting.C, 63.0, 0.5)
        assertTrue(
            "C(${c.weightedPeakDbfs.value})가 A(${a.weightedPeakDbfs.value})보다 크지 않다",
            c.weightedPeakDbfs.value > a.weightedPeakDbfs.value + 10.0,
        )
    }

    /**
     * **클리핑은 ADC 에서 일어나는 일이다.**
     *
     * 가중 뒤 값으로 재면 저역이 깎여 포화한 것을 놓친다. 63Hz 를 풀스케일로
     * 넣으면 A 가중 뒤에는 한참 낮아지지만, 클리핑은 **찍혀야** 한다.
     */
    @Test
    fun `A 가중에서도 클리핑을 놓치지 않는다`() {
        val f = frameOf(Weighting.A, 63.0, 1.0)
        assertTrue("풀스케일 63Hz 에서 클리핑이 안 잡혔다", f.peakClipped)
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

```
.\gradlew.bat :dsp:test --tests '*WeightedPeakTest*' --rerun-tasks
```

기대: 컴파일 실패 — `weightedPeakDbfs` 가 없다.

- [ ] **Step 3: 최소한으로 고친다**

`SplFrame` 에서 `peakClipped` 바로 아래에 필드를 더한다:

```kotlin
    /** 그 피크가 풀스케일에 닿았는가. 닿았으면 그 값은 하한일 뿐이다. */
    val peakClipped: Boolean,
    /**
     * 가중 **뒤** 파형의 최고값. 화면의 PEAK 타일이 이것을 쓴다.
     *
     * **[peakDbfs] 와 나누어 둔 까닭**: [peakDbfs] 는 가중 전이라
     * 클리핑을 잡을 수 있다 — 클리핑은 ADC 에서 일어나는 일이라 저역이
     * 깎인 뒤 값으로 재면 포화한 것을 놓친다. 화면에 적는 값은 반대로
     * 「사람이 고른 잣대로 순간 음압이 얼마였나」이므로 가중을 거쳐야 한다.
     *
     * Z 에서는 통과 필터라 두 값이 같다.
     */
    val weightedPeakDbfs: Dbfs,
```

`SplEngine` 안에 누적 변수를 더한다(`peakAbs` 옆):

```kotlin
    private var peakAbs = 0.0
    private var peakClipped = false

    /** 가중 뒤 파형의 최고값. 화면의 PEAK 이 쓴다. */
    private var weightedPeakAbs = 0.0
```

`process` 안, `filter.processInPlace(work, frames)` **바로 다음 줄**에 넣는다:

```kotlin
        filter.processInPlace(work, frames)

        // **가중 뒤 최고값은 여기서 잰다.** 위의 [peakAbs] 는 가중 전이라
        // 클리핑을 잡고, 이쪽은 화면에 적는 값이다.
        for (i in 0 until frames) {
            val a = kotlin.math.abs(work[i])
            if (a > weightedPeakAbs) weightedPeakAbs = a
        }
```

`SplFrame(...)` 을 만드는 자리에 값을 넣는다:

```kotlin
            peakDbfs = amplitudeToDbfs(peakAbs),
            peakClipped = peakClipped,
            weightedPeakDbfs = amplitudeToDbfs(weightedPeakAbs),
```

`reset()` 에 초기화를 더한다:

```kotlin
        peakAbs = 0.0
        peakClipped = false
        weightedPeakAbs = 0.0
```

- [ ] **Step 4: 통과를 확인한다**

```
.\gradlew.bat :dsp:test --rerun-tasks
```

기대: `WeightedPeakTest` 4개 PASS, 기존 `dsp` 시험 전부 PASS.

- [ ] **Step 5: 변이로 확인한다**

`weightedPeakDbfs = amplitudeToDbfs(weightedPeakAbs)` 를
`weightedPeakDbfs = amplitudeToDbfs(peakAbs)` 로 바꿔 돌린다.
기대: `A 에서는 저역 PEAK 이 가중 전보다 낮다` 가 **실패**.
확인한 뒤 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/SplEngine.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/WeightedPeakTest.kt
git commit -m "..."
```

메시지:
```
feat(음압): 가중 뒤 PEAK 을 따로 잰다 — 클리핑 판정은 그대로

지금까지 PEAK 은 가중 필터 앞에서만 쟀다(「클리핑은 입력단의 사건이다」).
그래서 A·C·Z 세 엔진의 PEAK 값이 전부 같았고, LCpeak 을 낼 길이 없었다.

둘을 나눈다. peakDbfs 는 가중 전이라 클리핑을 잡고, weightedPeakDbfs 는
화면에 적는다. 가중 뒤 값으로 클리핑을 재면 저역이 깎여 ADC 가 포화한
것을 놓친다 — 풀스케일 63Hz 를 A 가중으로 넣는 시험으로 못박았다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 6: 설정 — 가중 셋, Session Leq, 응답 속도 기본값

**Files:**
- Modify: `app/src/main/java/kr/joa/selahrta/settings/MeterSettings.kt`
- Test: `app/src/test/java/kr/joa/selahrta/settings/MeterSettingsWeightingTest.kt` (새로 만듦)

**Interfaces:**
- Consumes: Task 1 의 `Weighting`
- Produces:
  - `MeterSettings.splWeighting: Weighting`(기본 `A`), `.peakWeighting: Weighting`(기본 `Z`), `.analysisWeighting: Weighting`(기본 `Z`)
  - `LeqWindow.Session`, `const val SESSION_MILLIS = -1L`
  - `MeterSettingsStore.setSplWeighting/setPeakWeighting/setAnalysisWeighting(w)`
  - `MeterSettingsStore.resetSplWeighting/resetPeakWeighting/resetAnalysisWeighting()`
  - `MeterSettings.timeWeight` 의 기본값이 **`TimeWeight.Slow`**(담당자 지시 2026-09-27)
  - `MeterSettings.leqWindow` 의 기본값은 **`LeqWindow.OneMinute`** — **이미 그렇다. 바꾸지 않는다.**

> **저장 열쇠**: `splWeighting` 은 **지금 쓰던 열쇠 `"weighting"` 을 그대로**
> 쓴다. 쓰던 사람이 C 로 맞춰 뒀다면 그 설정이 남아야 한다. 나머지 둘은
> 새 열쇠 `"peakWeighting"`·`"analysisWeighting"` 이다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`app/src/test/java/kr/joa/selahrta/settings/MeterSettingsWeightingTest.kt` 를 만든다:

```kotlin
package kr.joa.selahrta.settings

import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **가중이 셋으로 갈라진다**(담당자 결정 2026-09-27).
 *
 * 음압·PEAK·주파수분석이 각자의 잣대를 갖는다. 한 값이 셋을 덮어쓰면
 * 「RTA 만 A 로 보기」가 불가능해지고, 반대로 음압을 C 로 보려다 RTA 까지
 * 기울어진다.
 */
class MeterSettingsWeightingTest {

    @Test
    fun `기본값이 음압 A · PEAK Z · 분석 Z 다`() {
        val s = MeterSettings()
        assertEquals(Weighting.A, s.splWeighting)
        assertEquals(Weighting.Z, s.peakWeighting)
        assertEquals(Weighting.Z, s.analysisWeighting)
    }

    /** 셋이 서로 독립이어야 한다. 하나를 바꿔도 나머지가 그대로. */
    @Test
    fun `하나를 바꿔도 나머지가 그대로다`() {
        val s = MeterSettings().copy(analysisWeighting = Weighting.A)
        assertEquals(Weighting.A, s.analysisWeighting)
        assertEquals(Weighting.A, s.splWeighting)
        assertEquals(Weighting.Z, s.peakWeighting)
    }

    @Test
    fun `Session 이 목록에 있다`() {
        assertTrue(LeqWindow.entries.any { it == LeqWindow.Session })
        assertEquals("전체", LeqWindow.Session.labelKo)
    }

    /**
     * **Session 의 millis 는 창 길이가 아니다.**
     *
     * 다른 창과 같은 값이면 저장한 뒤 읽을 때 엉뚱한 것으로 풀린다
     * (저장 코드가 millis 를 열쇠로 쓴다).
     */
    @Test
    fun `Session 의 표시값이 다른 창과 겹치지 않는다`() {
        val others = LeqWindow.entries.filter { it != LeqWindow.Session }
        others.forEach {
            assertNotEquals(
                "${it.name} 과 Session 의 millis 가 같다",
                it.millis,
                LeqWindow.Session.millis,
            )
        }
    }

    /**
     * **-1 을 엔진 창 길이로 넘기면 엔진이 상한다.**
     *
     * Session 은 창이 아니라 누적 합계라 창 길이가 필요 없다. 엔진에는
     * 기본 창을 주고, 화면에 적을 값만 세션 Leq 에서 가져온다.
     */
    @Test
    fun `Session 일 때 엔진에 줄 창은 양수다`() {
        assertTrue(LeqWindow.Session.engineMillis > 0)
        assertEquals(LeqWindow.OneMinute.millis, LeqWindow.Session.engineMillis)
        // 나머지는 자기 값 그대로.
        assertEquals(LeqWindow.TenSeconds.millis, LeqWindow.TenSeconds.engineMillis)
    }

    // ── 기본값 ──────────────────────────────────────────

    /**
     * **응답 속도 기본은 Slow 다**(담당자 지시 2026-09-27).
     *
     * 예배당에서 보는 것은 「지금 이 순간이 얼마나 센가」가 아니라
     * 「이만한 크기로 얼마나 이어지나」다. Fast 는 말소리의 자음 하나에도
     * 숫자가 튀어, 화면을 보는 사람이 그 튐을 쫓게 된다.
     */
    @Test
    fun `응답 속도 기본이 Slow 다`() {
        assertEquals(TimeWeight.Slow, MeterSettings().timeWeight)
    }

    /** **이미 1분이다.** 바꾸지 않았다는 것을 못박아 둔다. */
    @Test
    fun `Leq 시간 기본이 1분이다`() {
        assertEquals(LeqWindow.OneMinute, MeterSettings().leqWindow)
    }
}
```

`import kr.joa.selahrta.dsp.TimeWeight` 를 파일 위에 더한다.

- [ ] **Step 2: 실패를 확인한다**

```
.\gradlew.bat :app:testDebugUnitTest --tests '*MeterSettingsWeightingTest*' --rerun-tasks
```

기대: 컴파일 실패 — `splWeighting` 이 없다.

- [ ] **Step 3: 최소한으로 고친다**

`LeqWindow` 를 고친다:

```kotlin
/**
 * 측정 시작부터 지금까지를 뜻하는 표시값.
 *
 * **창 길이가 아니다.** 다른 창의 millis 와 겹치지 않게 음수를 쓴다 —
 * 저장 코드가 millis 를 열쇠로 쓰기 때문이다.
 */
const val SESSION_MILLIS = -1L

/** 화면에 보여줄 긴 Leq 의 길이(명세 14장). */
enum class LeqWindow(val labelKo: String, val millis: Long) {
    TenSeconds("10초", 10_000),
    OneMinute("1분", 60_000),
    FiveMinutes("5분", 300_000),

    /**
     * 측정 시작부터 지금까지의 누적 Leq(지시서 §4).
     *
     * 값은 이미 `SplFrame.leqSessionDbfs` 로 나오고 있었다 — 받는 곳이
     * 없었을 뿐이다.
     */
    Session("전체", SESSION_MILLIS),
    ;

    /**
     * **엔진에 넘길 창 길이.** [millis] 와 다를 수 있다.
     *
     * Session 의 [millis] 는 -1 이라 그대로 넘기면 엔진이 상한다. 세션
     * Leq 는 창이 아니라 누적 합계이므로 창 길이가 필요 없고, 엔진에는
     * 기본 창을 준 뒤 화면에 적을 값만 세션 쪽에서 가져온다.
     */
    val engineMillis: Long get() = if (this == Session) OneMinute.millis else millis
}
```

`MeterSettings` 의 `weighting` 필드를 셋으로 가른다:

```kotlin
data class MeterSettings(
    /** 음압(SPL 큰 숫자·Leq·MIN·MAX)의 가중. 권장 범위 판정이 이 값을 본다. */
    val splWeighting: Weighting = Weighting.A,
    /**
     * PEAK 의 가중. **음압과 따로 둔다**(지시서 §16).
     *
     * 킥·스네어의 저역이 A-weighting 에 깎여 순간 음압을 놓치는 일을
     * 막자는 것이다.
     */
    val peakWeighting: Weighting = Weighting.Z,
    /**
     * RTA·Spectrum·Spectrogram 의 가중.
     *
     * 셋을 묶는 까닭: 같은 FFT 장을 나눠 쓴다. 따로 두면 RTA 의 63Hz
     * 막대와 Spectrum 의 63Hz 봉우리가 다른 값이 된다.
     */
    val analysisWeighting: Weighting = Weighting.Z,
    val timeWeight: TimeWeight = TimeWeight.Fast,
    // ... 나머지 필드는 지금 그대로
```

`MeterSettingsStore` 에서 열쇠를 더한다:

```kotlin
    // **지금 쓰던 열쇠를 그대로 쓴다.** 쓰던 사람이 C 로 맞춰 뒀다면
    // 그 설정이 남아야 한다.
    private val splWeightingKey = stringPreferencesKey("weighting")
    private val peakWeightingKey = stringPreferencesKey("peakWeighting")
    private val analysisWeightingKey = stringPreferencesKey("analysisWeighting")
```

읽는 자리에서 셋을 읽는다(모르는 이름이면 각자의 기본값):

```kotlin
    /** 저장된 이름이 알 수 없는 것이면 기본값으로 돌아간다. */
    private fun Preferences.weightingOr(
        key: Preferences.Key<String>,
        fallback: Weighting,
    ): Weighting = this[key]?.let { n ->
        Weighting.entries.firstOrNull { it.name == n }
    } ?: fallback
```

```kotlin
                splWeighting = p.weightingOr(splWeightingKey, Weighting.A),
                peakWeighting = p.weightingOr(peakWeightingKey, Weighting.Z),
                analysisWeighting = p.weightingOr(analysisWeightingKey, Weighting.Z),
```

`leqWindow` 읽는 자리는 그대로 둔다 — `millis` 로 찾으므로 `Session`(-1)도 그대로 풀린다.

**응답 속도 기본값을 Slow 로 바꾼다.** 두 곳이다:

```kotlin
    val timeWeight: TimeWeight = TimeWeight.Slow,   // 43줄 근처
```

```kotlin
                timeWeight = p[timeWeightKey]?.let { n ->
                    TimeWeight.entries.firstOrNull { it.name == n }
                } ?: TimeWeight.Slow,                // 157줄 근처
```

> **`SessionMeta.kt:368` 의 `timeWeight ?: TimeWeight.Fast` 는 건드리지 않는다.**
> 그 줄은 **옛 기록을 읽는** 자리다. 그때 쟀던 것은 실제로 Fast 였으므로
> Slow 로 바꾸면 옛 기록에 없던 설정을 적어 넣는 셈이 된다.

**`leqWindow` 의 기본값은 손대지 않는다** — 이미 `OneMinute` 다.

쓰는 함수와 되돌리는 함수를 더한다(기존 `setWeighting` 은 지운다):

```kotlin
    suspend fun setSplWeighting(w: Weighting) = write { it[splWeightingKey] = w.name }
    suspend fun setPeakWeighting(w: Weighting) = write { it[peakWeightingKey] = w.name }
    suspend fun setAnalysisWeighting(w: Weighting) = write { it[analysisWeightingKey] = w.name }

    /** 그 줄만 기본값으로 되돌린다. 옆 줄은 건드리지 않는다. */
    suspend fun resetSplWeighting() = write { it.remove(splWeightingKey) }
    suspend fun resetPeakWeighting() = write { it.remove(peakWeightingKey) }
    suspend fun resetAnalysisWeighting() = write { it.remove(analysisWeightingKey) }
```

- [ ] **Step 4: 부르는 곳을 고친다**

`meterSettings.weighting` 을 쓰던 자리가 컴파일 오류로 드러난다. 각 자리에서 **어느 가중인지 보고** 바꾼다:

- `MeasureScreen` 의 큰 숫자·Leq·MIN·MAX → `splWeighting`
- `MeasureScreen` 의 PEAK 타일 → `peakWeighting`
- `CalibrationCard:173`(「소음계를 … 로 맞추고 재십시오」) → `splWeighting`
- 권장 범위 판정 게이트 → `splWeighting`
- `HistoryScreen` 의 기록 표시 → `SessionMeta.weighting`(설정이 아니라 기록에 적힌 값)

- [ ] **Step 5: 통과를 확인한다**

```
.\gradlew.bat assembleDebug :app:testDebugUnitTest :dsp:test --rerun-tasks
```

기대: BUILD SUCCESSFUL.

- [ ] **Step 6: 변이로 확인한다**

`splWeightingKey` 를 `stringPreferencesKey("splWeighting")` 으로 바꾼다(=옛 설정을 버리는 상태).
`MeterSettingsStore` 시험이 있으면 깨지는지 보고, 없으면 **Step 1 의 시험에 한 줄 더한다**:

```kotlin
    /** 쓰던 사람의 설정이 날아가지 않아야 한다. */
    @Test
    fun `음압 가중은 지금 쓰던 저장 열쇠를 이어받는다`() {
        // 열쇠 이름을 시험이 직접 본다 — 이름이 바뀌면 여기서 걸린다.
        assertEquals("weighting", MeterSettingsStore.SPL_WEIGHTING_KEY_NAME)
    }
```

이 시험을 넣으려면 `MeterSettingsStore` 의 companion 에 이름을 노출한다:

```kotlin
    companion object {
        /** 지금 쓰던 열쇠. **바꾸면 쓰던 사람의 설정이 날아간다.** */
        const val SPL_WEIGHTING_KEY_NAME = "weighting"
    }
```

그리고 `private val splWeightingKey = stringPreferencesKey(SPL_WEIGHTING_KEY_NAME)` 로 고친다.
변이를 되돌린다.

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/settings/MeterSettings.kt app/src/test/java/kr/joa/selahrta/settings/MeterSettingsWeightingTest.kt
git commit -m "..."
```

(부르는 곳을 고친 파일도 함께 적는다 — 하나씩.)

메시지:
```
feat(설정): 가중 셋·Session Leq·응답 속도 기본을 Slow 로

한 값이 셋을 덮어쓰던 탓에 「RTA 만 A 로 보기」가 불가능했고, 음압을
C 로 보려다 RTA 까지 기울었다. 지시서 §16 이 「Peak 를 SPL Weighting 에
무조건 종속시키지 않는다」고 못박은 자리이기도 하다.

음압은 지금 쓰던 저장 열쇠("weighting")를 그대로 이어받는다 — C 로
맞춰 두신 설정이 날아가지 않아야 한다. 시험이 열쇠 이름을 직접 본다.

Session 의 millis 는 -1 이라 엔진 창으로 넘기면 상한다. engineMillis 를
따로 두어 엔진에는 기본 창을 준다 — 세션 Leq 는 창이 아니라 누적이다.

응답 속도 기본을 Slow 로 바꿨다(담당자 지시). 예배당에서 보는 것은
「지금 이 순간이 얼마나 센가」가 아니라 「이만한 크기로 얼마나 이어지나」
라서, Fast 는 자음 하나에도 숫자가 튄다. 옛 기록을 읽는 자리
(SessionMeta)는 Fast 그대로 둔다 — 그때 쟀던 것은 실제로 Fast 였다.

Leq 시간 기본은 이미 1분이었다. 바꾸지 않았고, 시험으로 못박았다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 7: 배선 — 셋을 엔진과 화면으로

**Files:**
- Modify: `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt`(가중 적용·Session Leq 배선), `app/src/main/java/kr/joa/selahrta/ui/CaptureController.kt:272`(엔진 창)
- Test: 기기 확인(이 작업은 배선이라 단위 시험보다 실기기가 낫다)

**Interfaces:**
- Consumes: Task 4 의 `RtaEngine.setAnalysisWeighting(w)`, Task 5 의 `SplFrame.weightedPeakDbfs`, Task 6 의 세 설정 값
- Produces: `CaptureUiState` 가 세 가중과 세션 Leq 를 들고 있음

- [ ] **Step 1: 분석 가중을 엔진에 흘린다**

`CaptureViewModel` 에서 설정 흐름을 보는 자리에 더한다. **설정이 바뀔 때마다** 부른다:

```kotlin
        // **엔진을 새로 만들지 않는다.** 가중만 갈아 끼우면 Leq·MAX 가
        // 살아 있다. 예배 중에 잠깐 A 로 보고 돌아와도 그동안의 평균이
        // 사라지지 않는다.
        controller.postToCapture { session ->
            session.rta.setAnalysisWeighting(settings.analysisWeighting)
        }
```

- [ ] **Step 2: 음압·PEAK 을 골라 화면 상태로 옮긴다**

`MultiWeightFrame.of(w)` 로 두 번 집는다:

```kotlin
    // **두 번 집는다.** 음압과 PEAK 의 잣대가 다를 수 있다.
    val splFrame = multi.of(settings.splWeighting)
    val peakFrame = multi.of(settings.peakWeighting)
```

- 큰 숫자·Leq·MIN·MAX → `splFrame`
- PEAK → `peakFrame.weightedPeakDbfs`
- 클리핑 표시 → `peakFrame.peakClipped`(가중 전 값이라 그대로)

- [ ] **Step 3: Session Leq 를 배선한다**

`leqWindow` 가 `Session` 이면 화면에 적을 값을 세션 쪽에서 가져온다:

```kotlin
    // **Session 은 창이 아니라 누적이다.** 엔진에는 기본 창을 주고
    // (engineMillis) 여기서 값만 갈아 낀다.
    val leqLong = if (settings.leqWindow == LeqWindow.Session) {
        splFrame.leqSessionDbfs
    } else {
        splFrame.leqLongDbfs
    }
```

`leqLongFull` 도 맞춘다 — Session 은 「가득 찬다」는 개념이 없으므로 측정이 시작됐으면 `true` 다.

- [ ] **Step 4: 엔진 창에 engineMillis 를 넘긴다**

`CaptureController.kt:272` 와 `CaptureViewModel.kt:432` 의 `MultiWeightEngine(...)` 에서 `leqLongMs` 로 넘기는 값을 `leqWindow.millis` → **`leqWindow.engineMillis`** 로 바꾼다.

**이 줄을 놓치면 `-1` 이 엔진 창으로 들어가 측정이 통째로 망가진다.** `leqWindow.millis` 를 `grep` 해 남은 곳이 없는지 본다:

```
grep -rn "leqWindow.millis\|leqWindow\.millis" app/src/main --include=*.kt
```

기록에 적는 `leqWindowMs` 는 **`millis`(-1) 를 그대로** 쓴다 — 리포트가 「전체」로 읽는다.

- [ ] **Step 5: 빌드와 시험**

```
.\gradlew.bat assembleDebug :app:testDebugUnitTest :dsp:test --rerun-tasks
```

기대: BUILD SUCCESSFUL.

- [ ] **Step 6: 기기에서 확인한다**

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

확인할 것:
1. 측정을 시작하고 설정에서 **분석 가중을 A 로** 바꾼다 → RTA 저역 막대가 **실제로 내려간다**
2. 음압 가중을 C 로 바꾼다 → 큰 숫자가 올라가고 **RTA 는 그대로다**
3. Leq 시간을 **전체**로 바꾼다 → 값이 나오고 엔진이 멈추지 않는다
4. PEAK 가중을 C 로 바꾼다 → PEAK 값이 바뀌고 **음압 숫자는 그대로다**

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt app/src/main/java/kr/joa/selahrta/ui/CaptureController.kt
git commit -m "..."
```

메시지:
```
feat(배선): 세 가중을 엔진과 화면에 흘린다

분석 가중을 바꿔도 엔진을 새로 만들지 않는다 — 가중만 갈아 끼우면
Leq·MAX 가 살아 있어, 예배 중에 잠깐 A 로 보고 돌아와도 그동안의
평균이 사라지지 않는다.

엔진 창에 leqWindow.engineMillis 를 넘긴다. millis 를 넘기면 Session 의
-1 이 창 길이로 들어가 측정이 통째로 망가진다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 8: 설정 화면 — 세 줄과 설명

**Files:**
- Modify: `app/src/main/java/kr/joa/selahrta/ui/screens/HistorySettingsScreens.kt:221-245`(측정 설정 구역), `app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt:470`(콜백 배선)
- Test: 기기 확인

**Interfaces:**
- Consumes: Task 6 의 세 설정과 `reset*` 함수, Task 1 의 `Weighting.labelKo`

- [ ] **Step 1: 설명 문구를 한 곳에 둔다**

`HistorySettingsScreens.kt` 에 더한다:

```kotlin
/**
 * 가중을 고를 때 그 자리에 나오는 설명.
 *
 * **세 줄이 같은 문구를 쓴다.** 줄마다 다른 설명을 적으면 「A 가 여기서는
 * 이 뜻이고 저기서는 저 뜻인가」로 읽힌다.
 *
 * **평탄하다는 사실은 여기서 말한다** — 이름 자리에 「무가중」을 끼워
 * 넣으면 같은 것을 두 가지로 부르게 된다.
 */
private fun weightingHelpKo(w: Weighting): String = when (w) {
    Weighting.A ->
        "A-weighting — 사람 귀가 저음에 둔한 것을 흉내 냅니다. " +
            "소음 규제·청력 기준이 쓰는 잣대이고, 권장 범위 판정은 A 에서만 합니다."
    Weighting.C ->
        "C-weighting — 저음을 거의 깎지 않습니다. 킥·베이스가 실제로 얼마나 " +
            "센지 볼 때 씁니다. A 와의 차이가 크면 저음이 많다는 뜻입니다."
    Weighting.Z ->
        "Z-weighting — 깎지도 올리지도 않습니다. 들어온 소리 그대로라, " +
            "어느 대역에 에너지가 몰렸는지 보는 화면에는 이것이 기본입니다."
}
```

- [ ] **Step 2: 가중 줄을 그리는 조각을 만든다**

```kotlin
/**
 * 가중 한 줄. 고른 칸 **바로 아래** 설명이 바뀐다.
 *
 * 창을 띄우지 않는다 — 고르면서 읽어야 뜻이 있다.
 */
@Composable
private fun WeightingRow(
    label: String,
    whereKo: String,
    selected: Weighting,
    isCustom: Boolean,
    onPick: (Weighting) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(SelahColors.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SelahColors.Outline, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = SelahColors.TextPrimary, fontSize = 13.sp)
                Text(whereKo, color = SelahColors.TextMuted, fontSize = 10.sp)
            }
            // **고친 줄에만 띄운다.** 늘 띄우면 「기본값인데 되돌리기가
            // 있네」로 읽혀 무엇이 바뀐 상태인지 흐려진다.
            if (isCustom) {
                TextButton(onClick = onReset) {
                    Text("기본값으로", color = SelahColors.TextMuted, fontSize = 11.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Weighting.entries.forEach { w ->
                val on = w == selected
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (on) SelahColors.Accent else SelahColors.SurfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onPick(w) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        w.unitSuffix,
                        color = if (on) Color(0xFF00201C) else SelahColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Text(
            weightingHelpKo(selected),
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}
```

- [ ] **Step 3: 「측정 설정」 구역의 가중 줄을 셋으로 바꾼다**

지금의 `ChoiceRow("가중치 (Weighting)", ...)` 하나를 지우고 셋을 넣는다:

```kotlin
        SectionTitle("측정 설정")

        WeightingRow(
            label = "음압 가중",
            whereKo = "SPL 큰 숫자 · Leq · MIN · MAX",
            selected = capture.meterSettings.splWeighting,
            isCustom = capture.meterSettings.splWeighting != Weighting.A,
            onPick = onSplWeighting,
            onReset = onResetSplWeighting,
        )
        WeightingRow(
            label = "순간최고(PEAK) 가중",
            whereKo = "PEAK 타일",
            selected = capture.meterSettings.peakWeighting,
            isCustom = capture.meterSettings.peakWeighting != Weighting.Z,
            onPick = onPeakWeighting,
            onReset = onResetPeakWeighting,
        )
        WeightingRow(
            label = "주파수 분석 가중",
            whereKo = "RTA · Spectrum · Spectrogram",
            selected = capture.meterSettings.analysisWeighting,
            isCustom = capture.meterSettings.analysisWeighting != Weighting.Z,
            onPick = onAnalysisWeighting,
            onReset = onResetAnalysisWeighting,
        )

        // FR 은 고를 까닭이 없어 목록에 없다. 숨기지 않고 그 사실을 적는다.
        InfoBar(
            "주파수 응답(FR)은 늘 dB(Z) 로 잽니다. 예배당의 응답 자체를 " +
                "재는 화면이라, A 를 걸면 저역이 깎인 곡선이 나와 " +
                "「이 공간은 저음이 부족하다」고 잘못 읽게 됩니다.",
        )
```

`SettingsScreen` 시그니처에 콜백 여섯을 더하고, 기존 `onWeighting` 을 지운다. `SelahApp.kt:470` 에서 배선한다.

- [ ] **Step 4: FFT 크기 줄을 더한다**

Task 6 에서 `MeterSettings.fftSize: Int = 4096` 과 `setFftSize`/`resetFftSize` 를 함께 만들어 두었어야 한다. 만들지 않았으면 여기서 만든다(열쇠 `"fftSize"`, 허용값 2048·4096·8192, 모르는 값이면 4096).

```kotlin
        ChoiceRow(
            "FFT 크기",
            "크면 저역이 또렷하고, 작으면 반응이 빠릅니다.",
            listOf(2048, 4096, 8192),
            capture.meterSettings.fftSize,
            { it.toString() },
            onFftSize,
        )
```

- [ ] **Step 5: 빌드하고 기기에서 본다**

```
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

확인할 것:
1. 설정에 **세 줄**이 있고 각각 `dB(A)` `dB(C)` `dB(Z)` 칸이 보인다
2. 칸을 누르면 **아래 설명 줄이 바뀐다**
3. 기본값이 아닌 줄에만 **「기본값으로」**가 보이고, 누르면 그 줄만 돌아간다
4. FR 안내가 보인다

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/ui/screens/HistorySettingsScreens.kt app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt
git commit -m "..."
```

메시지:
```
feat(설정): 가중 세 줄과 고를 때 바뀌는 설명

창을 띄우지 않고 고른 칸 바로 아래에 설명을 둔다 — 고르면서 읽어야
뜻이 있다. 세 줄이 같은 문구를 쓴다: 줄마다 다르게 적으면 「A 가
여기서는 이 뜻이고 저기서는 저 뜻인가」로 읽힌다.

「기본값으로」는 고친 줄에만 띄운다. 늘 띄우면 무엇이 바뀐 상태인지
흐려진다.

FR 은 고를 까닭이 없어 목록에 없다. 숨기지 않고 그 사실을 적는다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 9: 측정 화면 — L 기호와 PEAK 가중

**Files:**
- Modify: `app/src/main/java/kr/joa/selahrta/ui/screens/MeasureScreen.kt`(267·327·352·376·386·417·630·639·646줄 근처)
- Test: 기기 확인

**Interfaces:**
- Consumes: Task 2 의 `leqLabel`·`peakLabel`, Task 6·7 의 세 가중

- [ ] **Step 1: Leq 타일 이름을 L 기호로**

`"Leq (${window.labelKo})"` 처럼 적던 자리를 바꾼다:

```kotlin
    // **손으로 적지 않는다.** 가중에서 뽑아야 바꾼 뒤에도 어긋나지 않는다.
    splWeighting.leqLabel(capture.meterSettings.leqWindow.labelKo)
```

- [ ] **Step 2: PEAK 타일 이름과 단위를 PEAK 가중으로**

630줄 근처의

```kotlin
val unit = if (metric == Metric.Peak) "dB · 가중없음" else weighting.unitSuffix
```

을 바꾼다:

```kotlin
// **PEAK 은 제 가중을 쓴다.** 음압 가중을 따라가면 「LZpeak 인데 dB(A)」
// 같은 어긋남이 생긴다.
val unit = if (metric == Metric.Peak) peakWeighting.unitSuffix else splWeighting.unitSuffix
```

PEAK 타일의 이름도 `peakWeighting.peakLabel()` 로 바꾼다.

- [ ] **Step 3: 범위 판정 게이트가 splWeighting 을 보게 한다**

116줄 근처의 주석과 조건을 바꾼다:

```kotlin
    // **참고 범위는 dB(A) 기준이다.** 음압 가중이 A 일 때만 견준다 —
    // C 나 Z 값을 A 범위와 견주면 저음이 큰 찬양에서 늘 빨강이 된다.
    val judge = splWeighting == Weighting.A && ...
```

352줄의 문구도 이름이 바뀌어 자연스럽게 읽힌다: 「지금은 C-weighting 라 범위와 견주지 않습니다」 → 조사를 고쳐 **「지금은 C-weighting 이라 범위와 견주지 않습니다」**.

- [ ] **Step 4: 「가중없음」이 화면에 남지 않았는지 훑는다**

```
grep -rn "가중없음\|가중 없음\|무가중" app/src/main --include=*.kt
```

주석에 설명으로 남은 것은 괜찮다. **화면 문구(`Text(...)` 안의 문자열)에 남아 있으면 안 된다.**

- [ ] **Step 5: 빌드하고 기기에서 본다**

확인할 것:
1. Leq 타일이 **`LAeq 1분`** 으로 보인다
2. 음압 가중을 C 로 바꾸면 **`LCeq 1분` · `dB(C)`** 로 함께 바뀐다
3. PEAK 타일이 **`LZpeak` · `dB(Z)`**
4. PEAK 가중만 C 로 바꾸면 **`LCpeak` · `dB(C)`** 가 되고 음압 타일은 그대로
5. Leq 시간을 「전체」로 하면 **`LAeq 전체`**

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/ui/screens/MeasureScreen.kt
git commit -m "..."
```

메시지:
```
feat(측정): Leq·PEAK 이름을 L 기호로, PEAK 은 제 가중을 쓴다

이름을 가중에서 뽑는다. 손으로 "LAeq" 를 적으면 가중을 바꾼 뒤에도
A 라고 적혀 있게 된다 — 지시서 §10 이 막으라고 한 어긋남이다.

PEAK 타일이 제 가중을 쓴다. 음압 가중을 따라가면 「LZpeak 인데 dB(A)」
같은 상태가 생긴다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 10: 분석·FR 화면 — 잣대를 적고 축 기본값을 바꾼다

**Files:**
- Modify: `app/src/main/java/kr/joa/selahrta/ui/screens/AnalyzeScreens.kt`(75·229·326·422줄 근처), `app/src/main/java/kr/joa/selahrta/ui/screens/FrScreen.kt`
- Test: 기기 확인

**Interfaces:**
- Consumes: Task 6·7 의 `analysisWeighting`, 기존 `AxisMode`

- [ ] **Step 1: 차트 아래 한 줄을 만든다**

`AnalyzeScreens.kt` 에 더한다:

```kotlin
/**
 * 차트 아래에 **늘** 적는 한 줄.
 *
 * 이 값들이 없으면 「이 그림이 무슨 잣대로 그려졌나」를 화면에서 알 수
 * 없다 — 스크린샷을 남겼을 때 특히 그렇다.
 */
@Composable
private fun AnalysisFootnote(capture: CaptureUiState) {
    Text(
        "FFT ${capture.meterSettings.fftSize} · Hann 창 · " +
            "${capture.opened?.sampleRate ?: 48_000} Hz · " +
            capture.meterSettings.analysisWeighting.unitSuffix,
        color = SelahColors.TextMuted,
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
    )
}
```

RTA·Spectrum·Spectrogram 세 화면의 차트 바로 아래에 넣는다(가로 모드에서는 넣지 않는다 — 차트만 남기는 모드다).

- [ ] **Step 2: RTA 「그 레벨」 단위를 분석 가중으로**

229줄 근처의

```kotlin
"dB · 가중 없음",
```

를 바꾼다:

```kotlin
// 위의 큰 숫자(음압)와 다른 잣대일 수 있으므로 단위에 그 사실을 적는다.
capture.meterSettings.analysisWeighting.unitSuffix,
```

- [ ] **Step 3: Spectrum·Spectrogram 의 축 기본값을 자동으로**

`SpectrumScreen`(326줄 근처)과 `SpectrogramScreen`(422줄 근처)의

```kotlin
var axisMode by remember { mutableStateOf(AxisMode.Fixed) }
```

를 바꾼다:

```kotlin
// **자동이 기본이다**(담당자 지시 2026-09-27). 봉우리가 몇 Hz 인지 찾는
// 화면이라 축이 값을 따라가야 보인다. RTA·SPL 은 권장 범위 띠와 견주는
// 화면이라 「고정」을 유지한다 — 축이 움직이면 판정이 흔들린다.
var axisMode by remember { mutableStateOf(AxisMode.Auto) }
```

`RtaScreen` 은 **건드리지 않는다.**

- [ ] **Step 4: FR 화면에 dB(Z) 고정을 적는다**

`FrScreen.kt` 의 차트 근처에 더한다:

```kotlin
        Text(
            "dB(Z) 고정 — 예배당의 응답 자체를 재는 화면이라 가중을 걸지 않습니다.",
            color = SelahColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
```

- [ ] **Step 5: 빌드하고 기기에서 본다**

확인할 것:
1. RTA·Spectrum·Spectrogram 차트 아래에 **`FFT 4096 · Hann 창 · 48000 Hz · dB(Z)`**
2. 분석 가중을 A 로 바꾸면 그 줄이 **`dB(A)`** 로 바뀌고 **막대도 실제로 내려간다**
3. Spectrum 을 열면 축이 **자동**으로 시작한다
4. RTA 축은 **고정**으로 시작한다
5. FR 화면에 **`dB(Z) 고정`** 이 보인다

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/ui/screens/AnalyzeScreens.kt app/src/main/java/kr/joa/selahrta/ui/screens/FrScreen.kt
git commit -m "..."
```

메시지:
```
feat(분석): 차트 아래 잣대를 적고 Spectrum 축 기본을 자동으로

차트만 보고는 무슨 잣대로 그려졌는지 알 수 없었다 — 스크린샷을
남겼을 때 특히 그렇다. FFT 크기·창·샘플레이트·가중을 늘 적는다.

Spectrum·Spectrogram 은 봉우리가 몇 Hz 인지 찾는 화면이라 축이 값을
따라가야 보인다. RTA·SPL 은 권장 범위 띠와 견주므로 고정을 유지한다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 11: 기록에 세 가중을 남긴다

**Files:**
- Modify: `app/src/main/java/kr/joa/selahrta/recording/SessionMeta.kt`, `app/src/main/java/kr/joa/selahrta/recording/MeasurementReport.kt`
- Test: `app/src/test/java/kr/joa/selahrta/recording/SessionMetaTest.kt`(기존 파일에 덧붙임)

**Interfaces:**
- Consumes: Task 1 의 `Weighting`
- Produces: `SessionMeta.peakWeighting: Weighting?`, `SessionMeta.analysisWeighting: Weighting?`
  - **null 을 허용한다** — 옛 기록에는 없다. 「짐작해서 Z 라고 적지 않는다」가 이 nullable 의 뜻이다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`SessionMetaTest.kt` 에 덧붙인다:

```kotlin
    // ── 세 가중 ─────────────────────────────────────────

    /** 셋이 따로 왕복해야 PEAK 이 어느 잣대인지 알 수 있다. */
    @Test
    fun `세 가중이 왕복한다`() {
        val m = sample().copy(
            weighting = Weighting.C,
            peakWeighting = Weighting.Z,
            analysisWeighting = Weighting.A,
        )
        val back = decodeSessionMeta(encodeSessionMeta(m)).getOrThrow()
        assertEquals(Weighting.C, back.weighting)
        assertEquals(Weighting.Z, back.peakWeighting)
        assertEquals(Weighting.A, back.analysisWeighting)
    }

    /**
     * **옛 기록을 짐작으로 채우지 않는다.**
     *
     * 없는 것을 Z 라고 적으면 「이 기록의 PEAK 은 Z 로 쟀다」는 거짓말이
     * 된다 — 실제로는 무엇이었는지 아무도 모른다.
     */
    @Test
    fun `옛 기록에는 새 가중이 없다`() {
        val text = encodeSessionMeta(sample())
            .lineSequence()
            .filterNot { it.startsWith("peakWeighting=") }
            .filterNot { it.startsWith("analysisWeighting=") }
            .joinToString("\n")
        val back = decodeSessionMeta(text).getOrThrow()
        assertNull(back.peakWeighting)
        assertNull(back.analysisWeighting)
        // 나머지는 멀쩡히 읽혀야 한다 — 새 열쇠가 없다고 통째로 거절하면
        // 옛 기록이 전부 못 읽는 것이 된다.
        assertEquals(sample().id, back.id)
    }
```

- [ ] **Step 2: 실패를 확인한다**

```
.\gradlew.bat :app:testDebugUnitTest --tests '*SessionMetaTest*' --rerun-tasks
```

기대: 컴파일 실패 — `peakWeighting` 이 없다.

- [ ] **Step 3: 최소한으로 고친다**

`SessionMeta` 에 필드 둘을 더한다:

```kotlin
    /**
     * PEAK 을 잰 가중. **옛 기록에는 없다**(null).
     *
     * 없는 것을 Z 라고 적으면 거짓말이 된다 — 그때 무엇이었는지 아무도
     * 모른다. 리포트는 null 을 「기록 없음」으로 적는다.
     */
    val peakWeighting: Weighting? = null,
    /** 분석 화면을 그린 가중. 옛 기록에는 없다(null). */
    val analysisWeighting: Weighting? = null,
```

쓰는 자리(`encodeSessionMeta`)에 두 줄을 더하되 **null 이면 적지 않는다.**

읽는 자리는 **`enumOrNull`** 로 읽는다 — 없는 것이 정상이므로 `missing` 으로 세면 안 된다(이 저장소가 이미 겪은 사고다).

- [ ] **Step 4: 리포트에 세 줄을 적는다**

`MeasurementReport.kt` 의 「어떤 잣대로 쟀나」 마당에 넣는다:

```kotlin
    line("음압 가중", m.weighting.labelKo)
    line("PEAK 가중", m.peakWeighting?.labelKo ?: NOT_RECORDED_KO)
    line("분석 가중", m.analysisWeighting?.labelKo ?: NOT_RECORDED_KO)
```

- [ ] **Step 5: 저장할 때 세 값을 넣는다**

`CaptureViewModel` 에서 `SessionMeta` 를 만드는 자리에 설정의 세 가중을 넣는다:

```kotlin
    weighting = settings.splWeighting,
    peakWeighting = settings.peakWeighting,
    analysisWeighting = settings.analysisWeighting,
```

- [ ] **Step 6: 통과를 확인한다**

```
.\gradlew.bat assembleDebug :app:testDebugUnitTest :dsp:test --rerun-tasks
```

- [ ] **Step 7: 변이로 확인한다**

`peakWeighting = enumOrNull(...) ?: Weighting.Z` 로 바꿔(=짐작으로 채우는 상태) 돌린다.
기대: `옛 기록에는 새 가중이 없다` 가 **실패**. 확인한 뒤 되돌린다.

- [ ] **Step 8: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/recording/SessionMeta.kt app/src/main/java/kr/joa/selahrta/recording/MeasurementReport.kt app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt app/src/test/java/kr/joa/selahrta/recording/SessionMetaTest.kt
git commit -m "..."
```

메시지:
```
feat(기록): 세 가중을 겉장에 남긴다 — 옛 기록은 「기록 없음」

가중이 셋으로 갈라지면 겉장에 하나만 적혀서는 PEAK 이 어느 잣대인지
알 수 없다. 셋을 다 적는다.

옛 기록에는 새 두 값이 없다. 없는 것을 Z 라고 채우면 「이 기록의
PEAK 은 Z 로 쟀다」는 거짓말이 된다 — 실제로는 아무도 모른다.
짐작으로 채우는 상태로 되돌려 시험이 깨지는 것을 확인했다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 12: 옛말이 화면에 남지 않았는지 훑는 시험

**Files:**
- Test: `app/src/test/java/kr/joa/selahrta/ui/WeightingWordingTest.kt` (새로 만듦)

**Interfaces:**
- Consumes: Task 1 의 `Weighting`

**이 작업이 따로 있는 까닭**: 사람의 기억으로는 다시 섞인다. 다음에 누가 화면 문구를 쓸 때 「무가중」이라고 적는 것을 **기계가** 막아야 한다.

- [ ] **Step 1: 시험을 쓴다**

`app/src/test/java/kr/joa/selahrta/ui/WeightingWordingTest.kt` 를 만든다:

```kotlin
package kr.joa.selahrta.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **옛말이 화면에 다시 섞여 들지 않게 막는다**(담당자 지시 2026-09-27).
 *
 * 「Z 가중」·「무가중」·「가중 없음」·「Flat」이 같은 것을 가리키며 섞여
 * 쓰이던 탓에 「고정된 Z 와 무가중이 다른 것인가」라는 물음이 실제로
 * 나왔다. 사람의 기억으로는 다시 섞이므로 기계가 본다.
 *
 * **주석은 보지 않는다.** 설명하는 글에서는 「가중을 걸지 않는다」가
 * 자연스럽다. 막으려는 것은 **화면에 뜨는 글자**다.
 */
class WeightingWordingTest {

    private val banned = listOf("무가중", "가중 없음", "가중없음")

    /** 화면 문구만 고른다 — 주석 줄과 빈 줄을 뺀다. */
    private fun uiStringsOf(f: File): List<Pair<Int, String>> =
        f.readLines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                null
            } else {
                i + 1 to line
            }
        }

    @Test
    fun `화면 문구에 옛말이 없다`() {
        val root = File("src/main/java/kr/joa/selahrta")
        assertTrue("소스 폴더를 못 찾았다: ${root.absolutePath}", root.isDirectory)

        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            uiStringsOf(f).forEach { (no, line) ->
                banned.forEach { b ->
                    if (line.contains("\"") && line.contains(b)) {
                        hits += "${f.name}:$no  $line"
                    }
                }
            }
        }

        assertTrue(
            "화면 문구에 옛말이 남아 있다. Z-weighting 으로 바꾸십시오:\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }
}
```

- [ ] **Step 2: 돌려서 남은 것을 찾는다**

```
.\gradlew.bat :app:testDebugUnitTest --tests '*WeightingWordingTest*' --rerun-tasks
```

실패하면 **실패 메시지가 가리키는 줄을 고친다.** 시험을 느슨하게 만들지 않는다.

- [ ] **Step 3: 통과를 확인한다**

```
.\gradlew.bat :app:testDebugUnitTest --tests '*WeightingWordingTest*' --rerun-tasks
```

기대: PASS.

- [ ] **Step 4: 변이로 확인한다**

아무 화면 파일의 `Text(...)` 안에 `"무가중"` 을 잠깐 넣고 돌린다.
기대: **실패**하며 그 파일·줄을 가리킨다. 확인한 뒤 되돌린다.

- [ ] **Step 5: 커밋**

```bash
git add app/src/test/java/kr/joa/selahrta/ui/WeightingWordingTest.kt
git commit -m "..."
```

메시지:
```
test(가중): 옛말이 화면 문구에 다시 섞이지 않게 막는다

「Z 가중」·「무가중」·「가중 없음」·「Flat」이 같은 것을 가리키며 섞여
쓰이던 탓에 「고정된 Z 와 무가중이 다른 것인가」라는 물음이 나왔다.
사람의 기억으로는 다시 섞이므로 기계가 본다.

주석은 보지 않는다 — 설명하는 글에서는 「가중을 걸지 않는다」가
자연스럽다. 막으려는 것은 화면에 뜨는 글자다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

---

## Task 13: 실기기 확인과 문서

**Files:**
- Modify: `CHANGELOG.md`(있으면)
- Test: 실기기(S23 Ultra)

- [ ] **Step 1: 전체 시험**

```
.\gradlew.bat assembleDebug :app:testDebugUnitTest :dsp:test --rerun-tasks
```

기대: BUILD SUCCESSFUL.

- [ ] **Step 2: 기기에 올린다**

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: 지시서 §18 을 실기기에서 확인한다**

**이것이 이 작업의 합격 기준이다.** 화면만 바뀌고 값이 그대로면 실패다.

| # | 할 것 | 그래야 하는 결과 |
|---|---|---|
| 1 | 측정 시작 → RTA 를 보며 **분석 가중을 Z→A** | 저역 막대가 **눈에 띄게 내려간다**. 차트 아래 줄이 `dB(A)` |
| 2 | **음압 가중을 A→C** | 큰 숫자가 **올라가고**, 단위가 `dB(C)`, Leq 이름이 `LCeq`. **RTA 는 그대로** |
| 3 | **PEAK 가중을 Z→C** | PEAK 이름이 `LCpeak`, 단위 `dB(C)`, 값이 바뀜. **음압 숫자는 그대로** |
| 4 | **Leq 시간을 전체로** | `LAeq 전체` 가 뜨고 값이 나온다. 측정이 멈추지 않는다 |
| 5 | 큰 소리를 내 **클리핑을 일으킨다**(분석·PEAK 가중 A 로 둔 채) | 클리핑 경고가 **뜬다** |
| 6 | **Spectrum 을 연다** | 축이 자동으로 시작한다 |
| 7 | **FR 화면** | `dB(Z) 고정` 이 보인다 |
| 8 | 기록을 남기고 **리포트를 본다** | 「어떤 잣대로 쟀나」에 세 줄이 있다 |
| 9 | **옛 기록**(이 작업 전에 남긴 것)을 연다 | PEAK·분석 가중이 **「기록 없음」**. 앱이 죽지 않는다 |
| 10 | 앱을 껐다 켠다 | 세 가중이 **그대로 남아 있다** |
| 11 | **앱 자료를 지우고** 처음 연다 | 응답 속도가 **Slow**, Leq 시간이 **1분** |

> **11번을 자료 삭제 없이 보면 안 된다.** 기본값은 **저장된 값이 없을 때만**
> 쓰인다. 이미 쓰던 기기에는 「Fast」가 저장돼 있어 새 기본값이 보이지
> 않는다 — 그게 맞는 동작이다(쓰던 사람의 설정을 빼앗지 않는다).
> 확인하려면:
>
> ```
> adb shell pm clear kr.joa.selahrta
> ```
>
> **쓰던 기기에서 Slow 로 보고 싶으면** 설정에서 직접 Slow 를 고른다.

- [ ] **Step 4: 문서**

`CHANGELOG.md` 가 있으면 항목을 더한다. 없으면 이 단계를 건너뛴다.

- [ ] **Step 5: 커밋과 PR**

```bash
git add CHANGELOG.md
git commit -m "docs: CHANGELOG — A/C/Z 가중치 화면별 설정"
git push origin feat/weighting-per-view
gh pr create --title "..." --body "..."
```

PR 본문에 **Step 3 표의 실제 결과**를 적는다 — 「했다」가 아니라 「그렇게 되었다」를 적는다.

---

## 자체 점검 결과

**스펙 대응:**

| 스펙 | 작업 |
|---|---|
| §2 용어 통일 | Task 1, 12 |
| §3 표기(dB(A)·L 기호) | Task 1, 2, 9 |
| §4 세 줄 + 설명 + FR 제외 | Task 6, 8 |
| §5 PEAK 가중 | Task 5, 9 |
| §6 분석 DSP | Task 3, 4 |
| §7 Session Leq | Task 6, 7 |
| §8 축 기본값·FFT 크기·Hann 표시 | Task 8, 10 |
| §9 기록 | Task 11 |
| (스펙 밖, 2026-09-27 추가 지시) 응답 속도 기본 Slow | Task 6 |
| (스펙 밖, 2026-09-27 추가 지시) Leq 시간 기본 1분 | Task 6 — **이미 그러함. 시험으로 못박기만 함** |
| §10 시험 15가지 | Task 1·3·4·5·6·11·12 의 시험 + Task 13 의 실기기 |

**빠진 것 없음.** §10 의 「Session Leq 가 에너지 평균이다」는 기존
`EnergyAverage` 시험이 이미 덮고 있어 새로 만들지 않는다.

**이름 일관성 확인:**
`weightBinGain`(Task 3) → `setAnalysisWeighting`(Task 4) → `analysisWeighting`(Task 6) →
`MeasurementTap` 은 건드리지 않음. `weightedPeakDbfs`(Task 5) → `peakWeighting`(Task 6·9·11).
`engineMillis`(Task 6) → `MultiWeightEngine` 에 넘김(Task 7). 어긋남 없음.
