# Transfer Function 1차 MVP — DSP 핵심 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transfer Function 1차 MVP 의 **DSP 핵심**을 만든다 — 지연 찾기,
클럭 드리프트, Magnitude(H1), Coherence. 전부 **JVM 시험으로 검증**한다.

**Architecture:** 기준 신호는 `SignalSink` 덧씌우기로 **실제로 나간 표본**을
가로챈다. 기준과 측정을 **하나의 잠금 아래** 고리 버퍼에 쌓고, 상호상관
(PHAT)으로 지연을 찾아 **시간 정렬한 뒤** Hann 창 50% 겹침으로 `Sxx`·
`Syy`·`Sxy` 를 평균한다. 거기서 `H1 = Sxy/Sxx` 와 `γ² = |Sxy|²/(Sxx·Syy)`
를 낸다.

**Tech Stack:** Kotlin · `dsp` 모듈(안드로이드 의존성 없음) · 기존 `Fft` ·
기존 `HannWindow` · JUnit4

명세: [`docs/review/SELAH_RTA_간편_정밀_Transfer_Function_개발지시서.md`](../../review/SELAH_RTA_간편_정밀_Transfer_Function_개발지시서.md)
(2026-10-01 2차 개정본 · 검토 통과) · 기준 `main` = `3d6cdf6`

## 이 계획이 다루지 않는 것

**화면과 배선은 다음 계획이다.** 계측 시험과 실기기 확인에 **폰이 필요한데
지금 없다**(2026-10-01 저녁 분리). 이 계획은 **폰 없이 끝낼 수 있는 데까지**
간다 — 그래도 **시험으로 검증된 DSP 라이브러리**라는 독립된 결과물이 된다.

**Phase · 정밀 모드 · Multi-Point 는 범위 밖이다**(명세 32장).

## Global Constraints

- **`dsp` 모듈에 안드로이드 의존성을 넣지 않는다.** 순수 Kotlin 만.
- 모든 파일 **UTF-8**, 주석·문구는 **한국어**.
- **PowerShell `Set-Content`/`Out-File` 로 소스를 쓰지 않는다**(한글이 깨진다).
  Write/Edit 도구만 쓴다.
- **`git add` 에 디렉터리를 넘기지 않는다.** 파일을 하나씩 적는다.
- 값은 명세가 정한 그대로: **FFT 8192 · Hann · 50% 겹침 · 48000 Hz**.
- **유효 평균 1~7 숨김 / 8~15 안정화 중 / 16+ 표시** — 이 정책의 **판정에
  쓸 평균 수**를 DSP 가 내놓는다(화면은 다음 계획).
- **Reference 가 약한 bin 은 Magnitude·Coherence 를 **둘 다** 무효로 표시한다.
- 빌드: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` 뒤
  `./gradlew :dsp:test` (PowerShell 이면 `$env:JAVA_HOME=...` · `.\gradlew`)
- 커밋 끝줄: `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`

## 이미 있는 것

`SampleRing`(모노 고리 버퍼)이 가지 `feat/tf-mvp` 에 있다 — 커밋 `4064631`
을 새 기준에 다시 얹었고 **시험 5/5 통과**를 확인했다. Task 1 이 여기에
기능을 하나 더한다.

## 파일 구조

| 파일 | 맡은 일 |
|---|---|
| `dsp/.../SampleRing.kt` | 모노 고리 버퍼. **(Task 1) 지연만큼 거슬러 뜨기** |
| `dsp/.../DelayEstimator.kt` | PHAT 상호상관. 또렷하지 않으면 **못 찾았다고 말한다** |
| `app/.../transfer/TappedSink.kt` | `SignalSink` 덧씌우기. 인터리브 칸 → 모노 |
| `dsp/.../SpectralAverager.kt` | Hann · 50% 겹침으로 `Sxx`·`Syy`·`Sxy` 를 모은다 |
| `dsp/.../TransferFunction.kt` | `H1` · `γ²` · **유효 bin 판정** |
| `dsp/.../ClockDrift.kt` | 타임스탬프 짝 → ppm. 못 주면 **`Unavailable`** |
| `dsp/.../TransferEngine.kt` | 고리 둘 + **잠금 하나** + 조립 |

---

### Task 1: SampleRing — 지연만큼 거슬러 뜨기

**Files:**
- Modify: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/SampleRing.kt`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/SampleRingTest.kt` (기존 파일에 더함)

**Interfaces:**
- Consumes: 기존 `SampleRing(capacity)` · `write(src, offset, count)` ·
  `snapshot(out): Int` · `clear()` · `written: Long`
- Produces: `fun snapshot(out: DoubleArray, lagBack: Int = 0): Int`

**왜 필요한가**: 측정이 기준보다 `D` 표본 늦게 들어온다. 그대로 두 창을
뜨면 `D` 만큼 어긋난 채로 스펙트럼을 곱하게 되고, **Coherence 가 통째로
무너진다.** 기준 쪽을 `D` 만큼 거슬러 떠서 시간을 맞춘다.

- [ ] **Step 1: 실패하는 시험을 더한다**

`SampleRingTest.kt` 끝에 더한다(기존 다섯은 건드리지 않는다).

```kotlin
    @Test
    fun `lagBack 만큼 거슬러 뜬다`() {
        val ring = SampleRing(10)
        ring.write(FloatArray(10) { (it + 1).toFloat() }, 0, 10)   // 1..10
        val out = DoubleArray(4)

        ring.snapshot(out, lagBack = 0)
        assertArrayEquals(doubleArrayOf(7.0, 8.0, 9.0, 10.0), out, 1e-9)

        ring.snapshot(out, lagBack = 2)
        assertArrayEquals(doubleArrayOf(5.0, 6.0, 7.0, 8.0), out, 1e-9)
    }

    @Test
    fun `거슬러 뜰 자료가 모자라면 채운 수가 줄어든다`() {
        val ring = SampleRing(10)
        ring.write(FloatArray(5) { (it + 1).toFloat() }, 0, 5)     // 1..5
        val out = DoubleArray(4)
        // 5개뿐인데 3 거슬러 가면 쓸 수 있는 것은 2개다.
        assertEquals(2, ring.snapshot(out, lagBack = 3))
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0, 2.0), out, 1e-9)
    }

    @Test
    fun `lagBack 이 음수면 막는다`() {
        val ring = SampleRing(4)
        try {
            ring.snapshot(DoubleArray(2), lagBack = -1)
            fail("음수 lagBack 이 통과했다")
        } catch (e: IllegalArgumentException) {
            // 기대한 대로
        }
    }
```

맨 위 import 에 `org.junit.Assert.fail` 을 더한다.

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.SampleRingTest'
```
기대: 컴파일 실패 — `snapshot` 에 `lagBack` 이 없다.

- [ ] **Step 3: 최소 구현**

`snapshot` 을 아래로 바꾼다. KDoc 도 함께 고친다.

```kotlin
    /**
     * 가장 최근에서 [lagBack] 만큼 거슬러 올라간 자리를 끝으로 하여,
     * `out.size` 개를 **시간 순으로** 담는다.
     *
     * 아직 그만큼 안 들어왔으면 **앞쪽을 0 으로 두고 뒤에 채운다** —
     * 창의 **끝이 기준 시각**이어야 상호상관의 지연이 뒤집히지 않는다.
     *
     * **[lagBack] 이 있는 까닭**: 측정은 기준보다 늦게 들어온다. 두 창을
     * 그냥 뜨면 그 지연만큼 어긋난 채로 스펙트럼을 곱하게 되고
     * **Coherence 가 통째로 무너진다.** 기준 쪽을 지연만큼 거슬러 떠서
     * 시간을 맞춘다.
     *
     * @return 실제로 채운 개수.
     */
    fun snapshot(out: DoubleArray, lagBack: Int = 0): Int {
        require(lagBack >= 0) { "lagBack 은 0 이상이라야 한다: $lagBack" }
        val have = minOf(written, capacity.toLong()).toInt()
        val usable = (have - lagBack).coerceAtLeast(0)
        val take = minOf(usable, out.size)
        java.util.Arrays.fill(out, 0, out.size - take, 0.0)
        // head 는 **다음에 쓸 자리**다. 거기서 lagBack + take 만큼 거슬러 간다.
        var idx = ((head - lagBack - take) % capacity + capacity) % capacity
        for (i in out.size - take until out.size) {
            out[i] = buf[idx].toDouble()
            idx = (idx + 1) % capacity
        }
        return take
    }
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.SampleRingTest'
```
기대: **8개 통과**(기존 5 + 새 3).

- [ ] **Step 5: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/SampleRing.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/SampleRingTest.kt
git commit -m "feat(전달함수): 고리에서 지연만큼 거슬러 뜬다

측정은 기준보다 늦게 들어온다. 두 창을 그냥 뜨면 그 지연만큼 어긋난
채로 스펙트럼을 곱하게 되고 Coherence 가 통째로 무너진다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: DelayEstimator — PHAT 상호상관

**Files:**
- Create: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/DelayEstimator.kt`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/DelayEstimatorTest.kt`

**Interfaces:**
- Consumes: 기존 `class Fft(size: Int)` 의 `fun transform(re: DoubleArray, im: DoubleArray)`
- Produces: `data class DelayResult(val samples: Int, val sharpness: Double, val found: Boolean)` ·
  `class DelayEstimator(analysisSize: Int = 32_768, maxLagSamples: Int = 24_000, minSharpness: Double = 2.0, phat: Boolean = true)` ·
  `fun estimate(reference: DoubleArray, measurement: DoubleArray): DelayResult`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * **지연을 못 찾는 것을 숫자로 내놓지 않는가** — 이 묶음에서 제일 중요한
 * 시험은 「관계없는 잡음」과 「범위 밖」이다. 조용한 자리에서는 잡음끼리도
 * 어딘가에서 가장 큰 값이 나오고, 그 값을 지연이라고 적으면 사람은 그
 * 숫자로 스피커를 정렬한다.
 */
class DelayEstimatorTest {

    private val n = 8192
    private val rng = Random(20261002)

    private fun noise(size: Int) = DoubleArray(size) { rng.nextDouble() * 2 - 1 }

    /** [src] 를 [lag] 만큼 뒤로 민 것. 앞은 0 으로 채운다. */
    private fun delayed(src: DoubleArray, lag: Int, gain: Double = 1.0) =
        DoubleArray(src.size) { if (it < lag) 0.0 else src[it - lag] * gain }

    private fun est() = DelayEstimator(analysisSize = n, maxLagSamples = 2000)

    @Test
    fun `지연 0 을 찾는다`() {
        val x = noise(n)
        val r = est().estimate(x, x)
        assertTrue(r.found)
        assertEquals(0, r.samples)
    }

    @Test
    fun `여러 지연을 그대로 찾는다`() {
        val x = noise(n)
        for (lag in listOf(1, 48, 240, 960, 1920)) {
            val r = est().estimate(x, delayed(x, lag))
            assertTrue("lag=$lag 를 못 찾았다", r.found)
            assertEquals("lag=$lag", lag, r.samples)
        }
    }

    @Test
    fun `잡음을 섞어도 찾는다`() {
        val x = noise(n)
        val bg = noise(n)
        val d = delayed(x, 500)
        val y = DoubleArray(n) { d[it] + bg[it] }   // SNR 0dB
        val r = est().estimate(x, y)
        assertTrue(r.found)
        assertEquals(500, r.samples)
    }

    @Test
    fun `반사가 섞여도 본 신호를 고른다`() {
        val x = noise(n)
        val direct = delayed(x, 300)
        val reflect = delayed(x, 300 + 480, gain = 0.5)
        val y = DoubleArray(n) { direct[it] + reflect[it] }
        val r = est().estimate(x, y)
        assertTrue(r.found)
        assertEquals(300, r.samples)
    }

    /** **PHAT 가 정말 더 또렷한가** — 말이 아니라 숫자로 본다. */
    @Test
    fun `잔향 속에서 PHAT 가 더 또렷하다`() {
        val x = noise(n)
        val y = DoubleArray(n)
        var gain = 1.0
        var lag = 400
        repeat(8) {
            val echo = delayed(x, lag, gain)
            for (i in 0 until n) y[i] += echo[i]
            lag += 170
            gain *= 0.72
        }
        val withPhat = DelayEstimator(n, 2000, phat = true).estimate(x, y)
        val plain = DelayEstimator(n, 2000, phat = false).estimate(x, y)

        assertTrue("PHAT 가 본 신호를 놓쳤다", withPhat.found)
        assertEquals("PHAT 가 엉뚱한 지연을 골랐다", 400, withPhat.samples)
        assertTrue(
            "PHAT 또렷함 ${withPhat.sharpness} 가 평범한 상관 ${plain.sharpness} 보다 크지 않다",
            withPhat.sharpness > plain.sharpness,
        )
    }

    /** **제일 중요한 시험.** */
    @Test
    fun `관계없는 잡음끼리는 못 찾았다고 말한다`() {
        assertFalse(est().estimate(noise(n), noise(n)).found)
    }

    @Test
    fun `탐색 범위를 넘는 지연은 못 찾았다고 말한다`() {
        val x = noise(n)
        assertFalse(DelayEstimator(n, 500).estimate(x, delayed(x, 3000)).found)
    }

    @Test
    fun `아무 소리도 없으면 못 찾았다고 말한다`() {
        assertFalse(est().estimate(DoubleArray(n), DoubleArray(n)).found)
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.DelayEstimatorTest'
```
기대: 컴파일 실패.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 지연을 찾은 결과.
 *
 * @param samples 표본 단위 지연. [found] 가 false 면 뜻이 없다.
 * @param sharpness 가장 큰 봉우리 ÷ 그 다음 봉우리. **클수록 또렷하다.**
 * @param found 믿을 만한가.
 */
data class DelayResult(val samples: Int, val sharpness: Double, val found: Boolean)

/** PHAT 로 나눌 때 **0 으로 나누지 않기 위한 바닥.** 소리 없는 대역은 크기가 0 이다. */
private const val PHAT_FLOOR = 1e-12

/**
 * 기준과 측정의 시간차를 **상호상관**으로 찾는다.
 *
 * ```
 * R(τ) = Σ x[n] · y[n+τ]      가장 큰 τ 가 지연이다
 * ```
 *
 * ## 숫자만 내놓지 않는다
 *
 * 조용한 자리에서는 **잡음끼리도 어딘가에서 가장 큰 값**이 나온다. 그 값을
 * 지연이라고 적으면 사람은 그 숫자로 스피커를 정렬한다. 그래서 **또렷함**
 * (으뜸 봉우리 ÷ 버금 봉우리)을 함께 보고, 문턱 아래면 **못 찾았다고
 * 말한다.**
 *
 * **문턱값 [minSharpness] 는 실측으로 정한 값이 아니다.** 실제 공간에서
 * 재어 보고 정해야 한다. 그때까지 화면은 이 값을 함께 적는다.
 *
 * ## 분수 표본은 보지 않는다
 *
 * 48kHz 에서 한 표본이 0.021ms 다. 정렬에는 충분하다. 분수 표본은 위상을
 * 할 때 필요하고, 그건 이번 범위가 아니다.
 */
class DelayEstimator(
    private val analysisSize: Int = 32_768,
    private val maxLagSamples: Int = 24_000,
    private val minSharpness: Double = 2.0,
    /**
     * **PHAT 가중**을 쓸 것인가(기본: 쓴다).
     *
     * 상호 스펙트럼의 **크기를 1 로 고르고 위상만 남긴다.** 잔향이 긴
     * 공간에서 봉우리가 훨씬 뾰족해진다 — 그래야 「또렷함」으로 참·거짓을
     * 가를 수 있다. 잔향 속 지연 추정의 표준 방법이다.
     *
     * **끌 수 있게 둔 까닭**: 끈 것과 견주는 시험이 있어야 「정말 더
     * 뾰족한가」를 말이 아니라 숫자로 보일 수 있다.
     */
    private val phat: Boolean = true,
) {
    init {
        require(analysisSize > 0) { "analysisSize 는 1 이상: $analysisSize" }
        require(maxLagSamples in 1 until analysisSize) {
            "maxLagSamples 는 1..${analysisSize - 1} 이라야 한다: $maxLagSamples"
        }
    }

    /** **원형으로 감기지 않도록** 분석 길이의 두 배를 쓴다. */
    private val fftSize = Integer.highestOneBit(analysisSize * 2 - 1) * 2
    private val fft = Fft(fftSize)

    private val xRe = DoubleArray(fftSize)
    private val xIm = DoubleArray(fftSize)
    private val yRe = DoubleArray(fftSize)
    private val yIm = DoubleArray(fftSize)

    fun estimate(reference: DoubleArray, measurement: DoubleArray): DelayResult {
        val n = minOf(reference.size, measurement.size, analysisSize)
        load(reference, n, xRe, xIm)
        load(measurement, n, yRe, yIm)

        fft.transform(xRe, xIm)
        fft.transform(yRe, yIm)

        // conj(X) · Y — 그리고 **PHAT 가중**(크기를 고르게, 위상만 남김)
        for (i in 0 until fftSize) {
            val re = xRe[i] * yRe[i] + xIm[i] * yIm[i]
            val im = xRe[i] * yIm[i] - xIm[i] * yRe[i]
            if (phat) {
                val mag = sqrt(re * re + im * im)
                if (mag > PHAT_FLOOR) {
                    xRe[i] = re / mag
                    xIm[i] = im / mag
                } else {
                    xRe[i] = 0.0
                    xIm[i] = 0.0
                }
            } else {
                xRe[i] = re
                xIm[i] = im
            }
        }

        inverse(xRe, xIm)

        var best = -1
        var bestVal = 0.0
        for (t in 0..maxLagSamples) {
            val v = abs(xRe[t])
            if (v > bestVal) { bestVal = v; best = t }
        }
        if (best < 0 || bestVal <= 0.0) return DelayResult(0, 0.0, false)

        // **버금은 으뜸 둘레를 뺀 곳에서** 고른다 — 봉우리 바로 옆은
        // 같은 봉우리의 어깨다.
        val guard = 8
        var second = 0.0
        for (t in 0..maxLagSamples) {
            if (abs(t - best) <= guard) continue
            val v = abs(xRe[t])
            if (v > second) second = v
        }

        val sharp = if (second <= 0.0) Double.MAX_VALUE else bestVal / second
        return DelayResult(best, sharp, sharp >= minSharpness)
    }

    private fun load(src: DoubleArray, n: Int, re: DoubleArray, im: DoubleArray) {
        java.util.Arrays.fill(re, 0.0)
        java.util.Arrays.fill(im, 0.0)
        System.arraycopy(src, 0, re, 0, n)
    }

    /**
     * 역변환. **[Fft] 에는 정변환밖에 없다** — 실수와 허수를 바꿔 정변환을
     * 돌리고 크기로 나누면 역변환이 된다.
     */
    private fun inverse(re: DoubleArray, im: DoubleArray) {
        fft.transform(im, re)
        val scale = 1.0 / fftSize
        for (i in re.indices) {
            re[i] *= scale
            im[i] *= scale
        }
    }
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.DelayEstimatorTest'
```
기대: **8개 통과.** 실패하면 **`minSharpness` 를 올려 맞추지 말 것** —
그러면 「관계없는 잡음」은 통과하지만 「잡음 섞인」이 깨진다. 둘 다
통과하는 값을 찾아야 한다(2.0 에서 1.5~3.0 사이를 본다).

- [ ] **Step 5: 변이로 그물을 확인한다**

`inverse` 의 `fft.transform(im, re)` 를 `fft.transform(re, im)` 으로 바꾼다
(실수·허수를 안 바꿈 = 역변환이 아님). 시험을 돌린다.

기대: **「여러 지연을 그대로 찾는다」가 실패해야 한다.** 통과하면 그 시험은
헛그물이다. 확인한 뒤 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/DelayEstimator.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/DelayEstimatorTest.kt
git commit -m "feat(전달함수): PHAT 상호상관으로 지연을 찾는다

숫자만 내놓지 않는다. 조용한 자리에서는 잡음끼리도 어딘가에서 가장 큰
값이 나오고, 그 값을 지연이라고 적으면 사람은 그 숫자로 스피커를
정렬한다. 또렷함이 문턱 아래면 못 찾았다고 말한다.

PHAT 는 잔향이 긴 공간에서 봉우리를 뾰족하게 한다. 끌 수 있게 두어
끈 것과 견주는 시험으로 그 효과를 숫자로 보였다.

Fft 에는 정변환밖에 없어 실수·허수를 바꿔 역변환을 만든다. 그 자리를
변이로 확인했다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: TappedSink — 실제로 나간 표본만 가로챈다

**Files:**
- Create: `app/src/main/java/kr/joa/selahrta/transfer/TappedSink.kt`
- Test: `app/src/test/java/kr/joa/selahrta/transfer/TappedSinkTest.kt`

**Interfaces:**
- Consumes: 기존 `interface SignalSink`(`kr.joa.selahrta.audio`) —
  `open(sampleRate: Int, frames: Int, channels: Int): Boolean` ·
  `write(buf: FloatArray, offset: Int, frames: Int): Int` · `stop()` ·
  `release(): Boolean` · `SignalSink.ERROR_DEAD_OBJECT`
- Produces: `class TappedSink(inner: SignalSink, onMono: (FloatArray, Int, Int) -> Unit) : SignalSink`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.SignalSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **조용히 틀리기 쉬운 자리를 못박는다.**
 *
 * `write()` 는 일부만 쓸 수 있다. 넘긴 버퍼 전체를 기준으로 적으면
 * 기준이 실제보다 앞서 가고, 그만큼 지연이 **작게** 나온다. 화면에는
 * 아무 표시도 안 난다.
 */
class TappedSinkTest {

    /** 요청의 일부만 받아들이는 가짜 출력. */
    private class PartialSink(private val accept: (Int) -> Int) : SignalSink {
        val got = mutableListOf<Float>()
        override fun open(sampleRate: Int, frames: Int, channels: Int) = true
        override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
            val n = accept(frames)
            for (i in 0 until maxOf(n, 0)) got.add(buf[offset + i])
            return n
        }
        override fun stop() {}
        override fun release() = true
    }

    private fun collector(): Pair<MutableList<Float>, (FloatArray, Int, Int) -> Unit> {
        val out = mutableListOf<Float>()
        return out to { b, o, c -> for (i in 0 until c) out.add(b[o + i]) }
    }

    /** 스테레오 인터리브를 모노로 합친다. L+R 이다 — 상관은 크기에 무관하다. */
    @Test
    fun `인터리브를 모노로 합친다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { it }, cb)
        sink.write(floatArrayOf(1f, 2f, 3f, 4f), 0, 4)
        assertArrayEquals(floatArrayOf(3f, 7f), mono.toFloatArray(), 1e-6f)
    }

    /** **이것이 이번에 막는 자리다.** */
    @Test
    fun `받아들여진 칸만 적는다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { 2 }, cb)
        sink.write(floatArrayOf(1f, 2f, 3f, 4f), 0, 4)
        assertArrayEquals(floatArrayOf(3f), mono.toFloatArray(), 1e-6f)
    }

    /** 홀수 칸으로 끊겨도 프레임 경계를 잃지 않는다. */
    @Test
    fun `홀수 칸으로 나누어 써도 같은 기준이 나온다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { 1 }, cb)
        val buf = floatArrayOf(1f, 2f, 3f, 4f)
        for (i in 0 until 4) sink.write(buf, i, 1)
        assertArrayEquals(floatArrayOf(3f, 7f), mono.toFloatArray(), 1e-6f)
    }

    @Test
    fun `오류면 아무것도 적지 않는다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { SignalSink.ERROR_DEAD_OBJECT }, cb)
        sink.write(floatArrayOf(1f, 2f), 0, 2)
        assertTrue(mono.isEmpty())
    }

    @Test
    fun `나머지 호출을 그대로 넘긴다`() {
        val inner = PartialSink { it }
        val sink = TappedSink(inner) { _, _, _ -> }
        assertTrue(sink.open(48_000, 1024, 2))
        sink.write(floatArrayOf(5f, 6f), 0, 2)
        assertEquals(listOf(5f, 6f), inner.got)
        sink.stop()
        assertTrue(sink.release())
    }

    /** 멈췄다 다시 열면 반 프레임이 남아 있으면 안 된다. */
    @Test
    fun `다시 열면 남은 반 프레임을 버린다`() {
        val (mono, cb) = collector()
        val sink = TappedSink(PartialSink { 1 }, cb)
        sink.write(floatArrayOf(1f), 0, 1)
        sink.open(48_000, 1024, 2)
        sink.write(floatArrayOf(10f, 20f), 0, 1)
        sink.write(floatArrayOf(10f, 20f), 1, 1)
        assertArrayEquals(floatArrayOf(30f), mono.toFloatArray(), 1e-6f)
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
./gradlew :app:testDebugUnitTest --tests 'kr.joa.selahrta.transfer.TappedSinkTest'
```
기대: 컴파일 실패.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.SignalSink

/**
 * 실제로 출력에 넘어간 표본을 가로채는 덧씌우기.
 *
 * **「나중에 다시 만든 값」을 기준으로 쓰지 않는다**(명세 4장). 같은 식으로
 * 다시 그린 파형은 볼륨·부분 쓰기를 지나지 않아 실제로 나간 것과 다르다.
 *
 * ## 두 가지가 조용히 틀리기 쉽다
 *
 * 1. **`write()` 는 일부만 쓸 수 있다.** 넘긴 버퍼 전체를 적으면 기준이
 *    실제보다 앞서 가고 지연이 **작게** 나온다. 화면에는 아무 표시도
 *    안 난다. 그래서 **돌려받은 수만큼만** 적는다.
 * 2. **세는 단위가 프레임이 아니라 「칸」이다**(`SignalSink.write` 문서).
 *    스테레오면 한 프레임이 두 칸이라, 홀수 칸에서 끊기면 프레임 경계가
 *    어긋난다. 반 프레임을 들고 있다가 짝이 오면 그때 내보낸다.
 *
 * @param onMono 모노로 합친 표본. **재생 스레드에서 불린다** — 여기서
 *   무거운 일을 하면 소리가 끊긴다.
 */
class TappedSink(
    private val inner: SignalSink,
    private val onMono: (FloatArray, Int, Int) -> Unit,
) : SignalSink {

    /** 짝을 기다리는 왼쪽 칸. */
    private var pendingLeft = 0f
    private var hasPending = false

    private var mono = FloatArray(1024)

    override fun open(sampleRate: Int, frames: Int, channels: Int): Boolean {
        // 지난 판의 반 프레임을 끌고 오지 않는다.
        hasPending = false
        return inner.open(sampleRate, frames, channels)
    }

    override fun write(buf: FloatArray, offset: Int, frames: Int): Int {
        val wrote = inner.write(buf, offset, frames)
        if (wrote <= 0) return wrote

        if (mono.size < (wrote + 1) / 2) mono = FloatArray((wrote + 1) / 2)
        var out = 0
        for (i in 0 until wrote) {
            val v = buf[offset + i]
            if (hasPending) {
                mono[out++] = pendingLeft + v
                hasPending = false
            } else {
                pendingLeft = v
                hasPending = true
            }
        }
        if (out > 0) onMono(mono, 0, out)
        return wrote
    }

    override fun stop() = inner.stop()

    override fun release(): Boolean {
        hasPending = false
        return inner.release()
    }
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
./gradlew :app:testDebugUnitTest --tests 'kr.joa.selahrta.transfer.TappedSinkTest'
```
기대: 6개 통과.

- [ ] **Step 5: 변이로 그물을 확인한다**

`for (i in 0 until wrote)` 를 `for (i in 0 until frames)` 로 바꾼다.
기대: **「받아들여진 칸만 적는다」가 실패해야 한다.** 확인한 뒤 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/transfer/TappedSink.kt app/src/test/java/kr/joa/selahrta/transfer/TappedSinkTest.kt
git commit -m "feat(전달함수): 실제로 나간 표본만 기준으로 가로챈다

write() 는 일부만 쓸 수 있다. 넘긴 버퍼 전체를 적으면 기준이 실제보다
앞서 가고 지연이 작게 나온다 — 화면에는 아무 표시도 안 난다. 돌려받은
수만큼만 적고, 그 자리를 변이로 확인했다.

세는 단위가 프레임이 아니라 칸이다. 홀수 칸에서 끊기면 프레임 경계가
어긋나므로 반 프레임을 들고 있다가 짝이 오면 내보낸다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: SpectralAverager — Sxx · Syy · Sxy 를 모은다

**Files:**
- Create: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/SpectralAverager.kt`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/SpectralAveragerTest.kt`

**Interfaces:**
- Consumes: 기존 `Fft` · 기존 `HannWindow.create(n)`
- Produces: `class SpectralAverager(val fftSize: Int = 8192)` ·
  `val bins: Int` · `val count: Int` · `fun reset()` ·
  `fun addBlock(ref: DoubleArray, refOffset: Int, meas: DoubleArray, measOffset: Int)` ·
  `val sxx: DoubleArray` · `val syy: DoubleArray` · `val sxyRe: DoubleArray` · `val sxyIm: DoubleArray`

> **창 보정 계수를 쓰지 않는다.** `H1 = Sxy/Sxx` 에서 기준과 측정에 **같은
> 창**이 걸리므로 보정이 서로 상쇄된다. 넣으면 한 번은 곱하고 한 번은
> 나누는 일을 하게 된다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SpectralAveragerTest {

    private val n = 1024

    private fun tone(size: Int, bin: Int, amp: Double = 1.0) =
        DoubleArray(size) { amp * sin(2.0 * PI * bin * it / size) }

    @Test
    fun `칸 수는 FFT 길이의 절반 더하기 하나다`() {
        assertEquals(n / 2 + 1, SpectralAverager(n).bins)
    }

    @Test
    fun `블록을 더하면 수가 는다`() {
        val a = SpectralAverager(n)
        val x = tone(n, 64)
        assertEquals(0, a.count)
        a.addBlock(x, 0, x, 0)
        a.addBlock(x, 0, x, 0)
        assertEquals(2, a.count)
    }

    @Test
    fun `reset 하면 수와 누적이 비워진다`() {
        val a = SpectralAverager(n)
        val x = tone(n, 64)
        a.addBlock(x, 0, x, 0)
        a.reset()
        assertEquals(0, a.count)
        assertEquals(0.0, a.sxx.sum(), 1e-12)
        assertEquals(0.0, a.sxyRe.sum(), 1e-12)
    }

    /** 순음은 제 칸에 가장 큰 에너지를 남긴다. */
    @Test
    fun `순음이 제 칸에 앉는다`() {
        val a = SpectralAverager(n)
        val x = tone(n, 64)
        a.addBlock(x, 0, x, 0)
        val peak = a.sxx.indices.maxByOrNull { a.sxx[it] }
        assertEquals(64, peak)
    }

    /** 같은 신호를 넣으면 Sxx 와 Syy 가 같고, Sxy 는 실수축에 선다. */
    @Test
    fun `같은 신호면 Sxx 와 Syy 가 같고 Sxy 는 실수다`() {
        val a = SpectralAverager(n)
        val x = tone(n, 64)
        a.addBlock(x, 0, x, 0)
        for (i in 0 until a.bins) {
            assertEquals("bin $i", a.sxx[i], a.syy[i], 1e-9)
            assertEquals("bin $i 의 Sxy 허수", 0.0, a.sxyIm[i], 1e-9)
            assertTrue("bin $i 의 Sxy 실수가 음수다", a.sxyRe[i] >= -1e-12)
        }
    }

    /** 측정이 두 배면 Sxy 도 두 배, Syy 는 네 배다. */
    @Test
    fun `크기가 곱해지면 누적도 그만큼 는다`() {
        val x = tone(n, 64)
        val y = DoubleArray(n) { x[it] * 2.0 }

        val one = SpectralAverager(n).apply { addBlock(x, 0, x, 0) }
        val two = SpectralAverager(n).apply { addBlock(x, 0, y, 0) }

        val b = 64
        assertEquals(one.sxx[b], two.sxx[b], 1e-9)
        assertEquals(one.sxyRe[b] * 2.0, two.sxyRe[b], 1e-6)
        assertEquals(one.syy[b] * 4.0, two.syy[b], 1e-6)
    }

    /** offset 을 지킨다 — 긴 버퍼에서 조각을 떠서 넣는다. */
    @Test
    fun `offset 을 지킨다`() {
        val long = DoubleArray(n * 2)
        val x = tone(n, 64)
        System.arraycopy(x, 0, long, n, n)

        val direct = SpectralAverager(n).apply { addBlock(x, 0, x, 0) }
        val sliced = SpectralAverager(n).apply { addBlock(long, n, long, n) }

        for (i in 0 until direct.bins) {
            assertEquals("bin $i", direct.sxx[i], sliced.sxx[i], 1e-9)
        }
    }

    /** 관계없는 잡음끼리는 Sxy 가 Sxx·Syy 에 견주어 작다. */
    @Test
    fun `관계없는 잡음끼리는 상호 스펙트럼이 작다`() {
        val rng = Random(11)
        val a = SpectralAverager(n)
        repeat(32) {
            val x = DoubleArray(n) { rng.nextDouble() * 2 - 1 }
            val y = DoubleArray(n) { rng.nextDouble() * 2 - 1 }
            a.addBlock(x, 0, y, 0)
        }
        val b = n / 4
        val gamma2 = (a.sxyRe[b] * a.sxyRe[b] + a.sxyIm[b] * a.sxyIm[b]) /
            (a.sxx[b] * a.syy[b])
        assertTrue("관계없는 잡음인데 상관이 높다: $gamma2", gamma2 < 0.3)
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.SpectralAveragerTest'
```
기대: 컴파일 실패.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.dsp

/**
 * 전달함수에 쓸 **자기·상호 스펙트럼을 블록마다 모은다.**
 *
 * ```
 * Sxx = <X* · X>      기준의 자기 스펙트럼
 * Syy = <Y* · Y>      측정의 자기 스펙트럼
 * Sxy = <X* · Y>      상호 스펙트럼 — **복소수로** 모은다
 * ```
 *
 * ## 왜 H 를 먼저 내지 않는가
 *
 * 블록마다 `Y/X` 를 내서 그 결과를 평균하면 **편향되고**, Coherence 를
 * 낼 재료가 사라진다. **스펙트럼을 먼저 모으고 그 뒤에 나눈다**(명세 8장).
 *
 * **상호 스펙트럼은 크기만 평균하면 안 된다** — 위상이 지워져 Coherence 가
 * 늘 1 에 가까워진다. 실수·허수를 따로 모은다.
 *
 * ## 창 보정 계수를 쓰지 않는다
 *
 * 기준과 측정에 **같은 Hann 창**이 걸리므로 `H1 = Sxy/Sxx` 에서 보정이
 * 서로 상쇄된다. 넣으면 곱했다 나누는 일을 하게 된다.
 */
class SpectralAverager(val fftSize: Int = 8192) {
    init {
        require(fftSize > 0 && Integer.bitCount(fftSize) == 1) {
            "fftSize 는 2의 거듭제곱이라야 한다: $fftSize"
        }
    }

    /** 0Hz 부터 나이키스트까지. */
    val bins: Int = fftSize / 2 + 1

    val sxx = DoubleArray(bins)
    val syy = DoubleArray(bins)
    val sxyRe = DoubleArray(bins)
    val sxyIm = DoubleArray(bins)

    /** 모은 블록 수. **Coherence 를 보일지 말지가 이 값에 달려 있다.** */
    var count: Int = 0
        private set

    private val fft = Fft(fftSize)
    private val window = HannWindow.create(fftSize)

    private val xRe = DoubleArray(fftSize)
    private val xIm = DoubleArray(fftSize)
    private val yRe = DoubleArray(fftSize)
    private val yIm = DoubleArray(fftSize)

    fun reset() {
        java.util.Arrays.fill(sxx, 0.0)
        java.util.Arrays.fill(syy, 0.0)
        java.util.Arrays.fill(sxyRe, 0.0)
        java.util.Arrays.fill(sxyIm, 0.0)
        count = 0
    }

    /**
     * 블록 한 쌍을 더한다. **이미 시간 정렬된 쌍**이라야 한다.
     *
     * @param refOffset [ref] 에서 읽기 시작할 자리.
     * @param measOffset [meas] 에서 읽기 시작할 자리.
     */
    fun addBlock(ref: DoubleArray, refOffset: Int, meas: DoubleArray, measOffset: Int) {
        require(refOffset >= 0 && refOffset + fftSize <= ref.size) {
            "ref 에서 $refOffset 부터 $fftSize 개를 읽을 수 없다 (크기 ${ref.size})"
        }
        require(measOffset >= 0 && measOffset + fftSize <= meas.size) {
            "meas 에서 $measOffset 부터 $fftSize 개를 읽을 수 없다 (크기 ${meas.size})"
        }

        for (i in 0 until fftSize) {
            xRe[i] = ref[refOffset + i] * window[i]
            xIm[i] = 0.0
            yRe[i] = meas[measOffset + i] * window[i]
            yIm[i] = 0.0
        }

        fft.transform(xRe, xIm)
        fft.transform(yRe, yIm)

        for (i in 0 until bins) {
            val xr = xRe[i]; val xi = xIm[i]
            val yr = yRe[i]; val yi = yIm[i]
            sxx[i] += xr * xr + xi * xi
            syy[i] += yr * yr + yi * yi
            // conj(X) · Y
            sxyRe[i] += xr * yr + xi * yi
            sxyIm[i] += xr * yi - xi * yr
        }
        count++
    }
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.SpectralAveragerTest'
```
기대: 8개 통과.

- [ ] **Step 5: 변이로 그물을 확인한다**

`sxyIm[i] += xr * yi - xi * yr` 를 `sxyIm[i] += 0.0` 으로 바꾼다(위상을
버림). 시험을 돌린다.

기대: **「관계없는 잡음끼리는 상호 스펙트럼이 작다」가 실패해야 한다** —
위상을 버리면 상관이 거짓으로 높아진다. 확인한 뒤 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/SpectralAverager.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/SpectralAveragerTest.kt
git commit -m "feat(전달함수): 자기·상호 스펙트럼을 블록마다 모은다

블록마다 Y/X 를 내서 평균하면 편향되고 Coherence 를 낼 재료가 사라진다.
스펙트럼을 먼저 모으고 그 뒤에 나눈다(명세 8장).

상호 스펙트럼은 크기만 평균하면 안 된다 — 위상이 지워져 상관이 거짓으로
높아진다. 실수·허수를 따로 모으고, 그 자리를 변이로 확인했다.

창 보정 계수는 쓰지 않는다. 기준과 측정에 같은 Hann 창이 걸리므로
H1 = Sxy/Sxx 에서 서로 상쇄된다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: TransferFunction — H1 · Coherence · 유효 bin

**Files:**
- Create: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/TransferFunction.kt`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/TransferFunctionTest.kt`

**Interfaces:**
- Consumes: `SpectralAverager`(Task 4) — `bins` · `count` · `sxx` · `syy` · `sxyRe` · `sxyIm`
- Produces: `data class TransferResult(val magnitudeDb: DoubleArray, val coherence: DoubleArray, val valid: BooleanArray, val averages: Int)` ·
  `fun transferFunction(avg: SpectralAverager, refFloorDb: Double = -40.0): TransferResult` ·
  `enum class CoherenceDisplay { Hidden, Stabilizing, Shown }` ·
  `fun coherenceDisplay(averages: Int): CoherenceDisplay`

**명세가 정한 것**(8·9장):
- `H1 = Sxy / Sxx` · `γ² = |Sxy|² / (Sxx · Syy)`
- **Reference 가 약한 bin 은 Magnitude·Coherence 를 둘 다 무효로.**
- 유효 평균 **1~7 숨김 / 8~15 안정화 중 / 16+ 표시**.

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class TransferFunctionTest {

    private val n = 1024
    private val rng = Random(20261002)

    private fun noise(size: Int) = DoubleArray(size) { rng.nextDouble() * 2 - 1 }
    private fun tone(size: Int, bin: Int) =
        DoubleArray(size) { sin(2.0 * PI * bin * it / size) }

    /** 기준과 측정이 같으면 0dB 이고 상관은 1 이다. */
    @Test
    fun `같은 신호는 0dB 이고 상관이 1 이다`() {
        val a = SpectralAverager(n)
        repeat(16) { val x = noise(n); a.addBlock(x, 0, x, 0) }
        val r = transferFunction(a)

        val b = n / 4
        assertTrue("bin $b 가 무효다", r.valid[b])
        assertEquals(0.0, r.magnitudeDb[b], 0.01)
        assertEquals(1.0, r.coherence[b], 0.001)
    }

    /** 측정이 두 배면 +6.02dB 다. */
    @Test
    fun `두 배는 6dB 이다`() {
        val a = SpectralAverager(n)
        repeat(16) {
            val x = noise(n)
            a.addBlock(x, 0, DoubleArray(n) { i -> x[i] * 2.0 }, 0)
        }
        val r = transferFunction(a)
        assertEquals(6.0206, r.magnitudeDb[n / 4], 0.01)
    }

    /** 관계없는 잡음은 상관이 낮다. */
    @Test
    fun `관계없는 잡음은 상관이 낮다`() {
        val a = SpectralAverager(n)
        repeat(32) { a.addBlock(noise(n), 0, noise(n), 0) }
        val r = transferFunction(a)
        assertTrue("상관이 ${r.coherence[n / 4]} 로 높다", r.coherence[n / 4] < 0.3)
    }

    /** **이번에 새로 막는 자리.** 기준이 없는 칸은 무효다. */
    @Test
    fun `기준이 약한 칸은 무효다`() {
        val a = SpectralAverager(n)
        // 한 칸에만 에너지가 있는 순음을 기준으로 쓴다.
        repeat(16) { val x = tone(n, 64); a.addBlock(x, 0, x, 0) }
        val r = transferFunction(a, refFloorDb = -40.0)

        assertTrue("순음이 있는 칸이 무효다", r.valid[64])
        // 에너지가 없는 먼 칸은 막혀야 한다.
        assertFalse("기준이 없는 칸이 유효로 나온다", r.valid[300])
    }

    @Test
    fun `무효인 칸의 상관은 0 으로 둔다`() {
        val a = SpectralAverager(n)
        repeat(16) { val x = tone(n, 64); a.addBlock(x, 0, x, 0) }
        val r = transferFunction(a, refFloorDb = -40.0)
        assertEquals(0.0, r.coherence[300], 1e-12)
    }

    @Test
    fun `평균 수를 그대로 들고 나온다`() {
        val a = SpectralAverager(n)
        repeat(9) { val x = noise(n); a.addBlock(x, 0, x, 0) }
        assertEquals(9, transferFunction(a).averages)
    }

    /** 명세 9장의 표시 정책. */
    @Test
    fun `평균 수에 따라 상관을 보일지 가른다`() {
        assertEquals(CoherenceDisplay.Hidden, coherenceDisplay(0))
        assertEquals(CoherenceDisplay.Hidden, coherenceDisplay(1))
        assertEquals(CoherenceDisplay.Hidden, coherenceDisplay(7))
        assertEquals(CoherenceDisplay.Stabilizing, coherenceDisplay(8))
        assertEquals(CoherenceDisplay.Stabilizing, coherenceDisplay(15))
        assertEquals(CoherenceDisplay.Shown, coherenceDisplay(16))
        assertEquals(CoherenceDisplay.Shown, coherenceDisplay(100))
    }

    /** **단일 블록은 상관이 수식상 1 이다** — 그래서 숨긴다. */
    @Test
    fun `한 블록만 모으면 상관이 1 이고 그래서 숨긴다`() {
        val a = SpectralAverager(n)
        a.addBlock(noise(n), 0, noise(n), 0)      // 관계없는 잡음인데도
        val r = transferFunction(a)
        assertEquals(1.0, r.coherence[n / 4], 1e-9)
        assertEquals(CoherenceDisplay.Hidden, coherenceDisplay(r.averages))
    }

    @Test
    fun `아무것도 안 모으면 전부 무효다`() {
        val r = transferFunction(SpectralAverager(n))
        assertEquals(0, r.averages)
        assertFalse(r.valid.any { it })
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.TransferFunctionTest'
```
기대: 컴파일 실패.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.dsp

import kotlin.math.log10

/**
 * 전달함수 한 판.
 *
 * @param magnitudeDb 칸마다의 `|H1|` (dB). [valid] 가 false 인 칸은 뜻이 없다.
 * @param coherence 칸마다의 `γ²` (0~1). 무효인 칸은 0 이다.
 * @param valid **기준이 그 칸에 쓸 만큼 있었는가.** false 면 그리지 않는다.
 * @param averages 모은 블록 수. 상관을 보일지 말지가 여기 달려 있다.
 */
data class TransferResult(
    val magnitudeDb: DoubleArray,
    val coherence: DoubleArray,
    val valid: BooleanArray,
    val averages: Int,
)

/** 상관을 화면에 어떻게 둘 것인가(명세 9장). */
enum class CoherenceDisplay {
    /** 1~7 — 숨긴다. 「측정 중」으로 적는다. */
    Hidden,

    /** 8~15 — 보이되 **안정화 중**임을 함께 적는다. */
    Stabilizing,

    /** 16 이상 — 기본 표시. */
    Shown,
}

/**
 * **8 은 보일 수 있는 최소, 16 이 기본 목표다.**
 *
 * 이 숫자는 **초기 운영값이지 통계적 신뢰구간이 아니다.** 50% 겹친 블록은
 * 독립 표본이 아니므로 「8이면 충분」처럼 읽으면 안 된다. 실측으로 조정한다.
 */
fun coherenceDisplay(averages: Int): CoherenceDisplay = when {
    averages < 8 -> CoherenceDisplay.Hidden
    averages < 16 -> CoherenceDisplay.Stabilizing
    else -> CoherenceDisplay.Shown
}

/**
 * 모아 둔 스펙트럼에서 **H1 과 Coherence** 를 낸다.
 *
 * ```
 * H1(f) = Sxy / Sxx
 * γ²(f) = |Sxy|² / (Sxx · Syy)
 * ```
 *
 * ## 기준이 약한 칸을 통째로 막는다
 *
 * `H1 = Sxy/Sxx` 이므로 **기준 에너지가 거의 없는 칸에서는 `Sxx` 가 작아져
 * 결과가 크게 튄다.** 그 값은 시스템의 응답이 아니라 **0 에 가까운 수로
 * 나눈 자국**이다. 음악이나 프로그램 신호를 기준으로 쓰면 바로 걸린다.
 *
 * 그래서 **가장 센 칸에 견주어** [refFloorDb] 아래인 칸은 무효로 둔다.
 * 절대값이 아니라 **상대값**으로 보는 까닭은, 사람이 볼륨을 올리고 내려도
 * 판정이 따라 움직이지 않게 하려는 것이다.
 *
 * **[refFloorDb] 는 실측으로 정할 값이다.** −40dB 은 출발점일 뿐이다.
 */
fun transferFunction(avg: SpectralAverager, refFloorDb: Double = -40.0): TransferResult {
    val bins = avg.bins
    val mag = DoubleArray(bins)
    val coh = DoubleArray(bins)
    val valid = BooleanArray(bins)

    if (avg.count == 0) return TransferResult(mag, coh, valid, 0)

    var peak = 0.0
    for (i in 0 until bins) if (avg.sxx[i] > peak) peak = avg.sxx[i]
    if (peak <= 0.0) return TransferResult(mag, coh, valid, avg.count)

    val floor = peak * Math.pow(10.0, refFloorDb / 10.0)

    for (i in 0 until bins) {
        val sxx = avg.sxx[i]
        if (sxx < floor) continue          // 무효 — 그리지 않는다

        val re = avg.sxyRe[i]
        val im = avg.sxyIm[i]
        val crossMagSq = re * re + im * im

        valid[i] = true
        mag[i] = 10.0 * log10(crossMagSq / (sxx * sxx))

        val denom = sxx * avg.syy[i]
        coh[i] = if (denom > 0.0) (crossMagSq / denom).coerceIn(0.0, 1.0) else 0.0
    }

    return TransferResult(mag, coh, valid, avg.count)
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.TransferFunctionTest'
```
기대: 9개 통과.

- [ ] **Step 5: 변이로 그물을 확인한다**

`if (sxx < floor) continue` 를 지운다(기준이 약한 칸도 그림).

기대: **「기준이 약한 칸은 무효다」가 실패해야 한다.** 확인한 뒤 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/TransferFunction.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/TransferFunctionTest.kt
git commit -m "feat(전달함수): H1 과 Coherence, 그리고 유효 칸 판정

H1 = Sxy/Sxx 이므로 기준 에너지가 거의 없는 칸에서는 Sxx 가 작아져
결과가 크게 튄다. 그 값은 시스템의 응답이 아니라 0 에 가까운 수로 나눈
자국이다 — 음악이나 프로그램 신호를 기준으로 쓰면 바로 걸린다. 가장
센 칸에 견주어 문턱 아래면 무효로 두고 Magnitude 도 Coherence 도
내지 않는다.

상대값으로 보는 까닭은 사람이 볼륨을 올리고 내려도 판정이 따라 움직이지
않게 하려는 것이다.

평균이 1회면 상관이 수식상 1.00 이라, 그것을 시험으로 못박고 표시
정책(1~7 숨김 / 8~15 안정화 중 / 16+ 표시)을 함께 넣었다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: ClockDrift — 타임스탬프 짝에서 ppm 을 낸다

**Files:**
- Create: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/ClockDrift.kt`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/ClockDriftTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces: `data class ClockSample(val frames: Long, val nanos: Long)` ·
  `sealed interface DriftResult { data class Ppm(val value: Double) : DriftResult; data object Unavailable : DriftResult }` ·
  `fun estimateDrift(outFirst: ClockSample, outLast: ClockSample, inFirst: ClockSample, inLast: ClockSample): DriftResult`

> **안드로이드 API 는 여기 들어오지 않는다.** `getTimestamp()` 를 부르는
> 일은 앱 쪽이고, 이 함수는 **짝지은 숫자만** 받는다. 그래야 폰 없이
> 시험할 수 있다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockDriftTest {

    private val sec = 1_000_000_000L

    /** 60초 동안 [rate] Hz 로 흐른 것처럼 짝을 만든다. */
    private fun pair(rate: Double, seconds: Long = 60): Pair<ClockSample, ClockSample> =
        ClockSample(0L, 0L) to
            ClockSample((rate * seconds).toLong(), seconds * sec)

    @Test
    fun `두 시계가 같으면 0 ppm 이다`() {
        val (o1, o2) = pair(48_000.0)
        val (i1, i2) = pair(48_000.0)
        val r = estimateDrift(o1, o2, i1, i2)
        assertTrue(r is DriftResult.Ppm)
        assertEquals(0.0, (r as DriftResult.Ppm).value, 0.5)
    }

    @Test
    fun `출력이 빠르면 양수 ppm 이다`() {
        val (o1, o2) = pair(48_000.0 * (1 + 20e-6))   // +20ppm
        val (i1, i2) = pair(48_000.0)
        val r = estimateDrift(o1, o2, i1, i2) as DriftResult.Ppm
        assertEquals(20.0, r.value, 1.0)
    }

    @Test
    fun `출력이 느리면 음수 ppm 이다`() {
        val (o1, o2) = pair(48_000.0 * (1 - 50e-6))   // -50ppm
        val (i1, i2) = pair(48_000.0)
        val r = estimateDrift(o1, o2, i1, i2) as DriftResult.Ppm
        assertEquals(-50.0, r.value, 1.0)
    }

    /** **못 재는 경우를 0 으로 돌려주지 않는다.** */
    @Test
    fun `시간이 안 흘렀으면 못 쟀다고 말한다`() {
        val s = ClockSample(0L, 0L)
        assertEquals(DriftResult.Unavailable, estimateDrift(s, s, s, s))
    }

    @Test
    fun `프레임이 안 늘었으면 못 쟀다고 말한다`() {
        val o1 = ClockSample(0L, 0L)
        val o2 = ClockSample(0L, 60 * sec)       // 시간은 흘렀는데 프레임이 그대로
        val (i1, i2) = pair(48_000.0)
        assertEquals(DriftResult.Unavailable, estimateDrift(o1, o2, i1, i2))
    }

    @Test
    fun `시간이 거꾸로 가면 못 쟀다고 말한다`() {
        val o1 = ClockSample(0L, 60 * sec)
        val o2 = ClockSample(48_000L * 60, 0L)
        val (i1, i2) = pair(48_000.0)
        assertEquals(DriftResult.Unavailable, estimateDrift(o1, o2, i1, i2))
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.ClockDriftTest'
```
기대: 컴파일 실패.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.dsp

/**
 * 한 시점의 「프레임 수와 그때의 시각」.
 *
 * **시각은 반드시 같은 시간축이라야 한다.** 안드로이드에서는
 * `AudioTrack` 쪽이 `MONOTONIC` 이고 `AudioRecord` 는 고를 수 있으므로,
 * 부르는 쪽이 `TIMEBASE_MONOTONIC` 을 명시해야 한다(명세 7장). 어긋나면
 * 드리프트가 아니라 **두 시계의 기준점 차이**를 재게 된다.
 */
data class ClockSample(val frames: Long, val nanos: Long)

/** 드리프트를 쟀는가. */
sealed interface DriftResult {
    /** 백만분율. 양수면 출력이 빠르다. */
    data class Ppm(val value: Double) : DriftResult

    /**
     * **못 쟀다.**
     *
     * 0 ppm 으로 돌려주지 않는다 — 그러면 「드리프트 없음」으로 읽힌다.
     * 안드로이드는 route 에 따라 타임스탬프를 아예 안 줄 수 있다.
     */
    data object Unavailable : DriftResult
}

/**
 * 출력과 입력의 **표본 속도 비**로 드리프트를 낸다.
 *
 * ```
 * outputRate = Δframes / Δtime
 * inputRate  = Δframes / Δtime
 * ppm = (outputRate / inputRate - 1) × 1,000,000
 * ```
 *
 * **음향이 전혀 필요 없다.** 스피커를 울리지 않고 조용히 한 시간을 잴 수
 * 있다. 음향으로 재면 클럭 드리프트와 방·온도 변화가 섞인다 — 음속은
 * 1℃ 에 약 0.17% 변하고, 8m 에서 1℃ 면 0.04ms 라 찾으려는 값과 같은
 * 자릿수다.
 */
fun estimateDrift(
    outFirst: ClockSample,
    outLast: ClockSample,
    inFirst: ClockSample,
    inLast: ClockSample,
): DriftResult {
    val outRate = rateOrNull(outFirst, outLast) ?: return DriftResult.Unavailable
    val inRate = rateOrNull(inFirst, inLast) ?: return DriftResult.Unavailable
    if (inRate <= 0.0) return DriftResult.Unavailable
    return DriftResult.Ppm((outRate / inRate - 1.0) * 1_000_000.0)
}

private fun rateOrNull(first: ClockSample, last: ClockSample): Double? {
    val dFrames = last.frames - first.frames
    val dNanos = last.nanos - first.nanos
    // 시간이 거꾸로 가거나 멈춰 있거나, 프레임이 안 늘었으면 잴 수 없다.
    if (dNanos <= 0L || dFrames <= 0L) return null
    return dFrames * 1_000_000_000.0 / dNanos
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.ClockDriftTest'
```
기대: 6개 통과.

- [ ] **Step 5: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/ClockDrift.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/ClockDriftTest.kt
git commit -m "feat(전달함수): 타임스탬프 짝에서 클럭 드리프트를 낸다

음향이 전혀 필요 없다. 음향으로 재면 클럭 드리프트와 방·온도 변화가
섞인다 — 음속은 1℃ 에 0.17% 변하고 8m 에서 1℃ 면 0.04ms 라 찾으려는
값과 같은 자릿수다.

못 재는 경우를 0 ppm 으로 돌려주지 않는다. 그러면 「드리프트 없음」으로
읽힌다 — 안드로이드는 route 에 따라 타임스탬프를 아예 안 준다.
Unavailable 로 분명히 말한다.

안드로이드 API 는 여기 넣지 않았다. getTimestamp 를 부르는 일은 앱
쪽이고 이 함수는 짝지은 숫자만 받는다 — 그래야 폰 없이 시험할 수 있다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: TransferEngine — 고리 둘, 잠금 하나, 조립

**Files:**
- Create: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/TransferEngine.kt`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/TransferEngineTest.kt`

**Interfaces:**
- Consumes: `SampleRing`(Task 1) · `DelayEstimator`·`DelayResult`(Task 2) ·
  `SpectralAverager`(Task 4) · `transferFunction`·`TransferResult`(Task 5)
- Produces: `class TransferEngine(sampleRate: Int = 48_000, fftSize: Int = 8192, averages: Int = 16, maxLagSamples: Int = 24_000)` ·
  `fun offerReference(src: FloatArray, offset: Int, count: Int)` ·
  `fun offerMeasurement(src: FloatArray, offset: Int, count: Int)` ·
  `fun measure(): TransferMeasurement?` · `fun delayMs(samples: Int): Double` ·
  `fun reset()` · `val referenceCount: Long` · `val measurementCount: Long` ·
  `data class TransferMeasurement(val delay: DelayResult, val transfer: TransferResult?)`

**왜 잠금이 하나인가**: 두 고리를 **서로 다른 스레드**가 채운다 — 기준은
재생, 측정은 캡처다. 창을 따로 뜨면 그 사이에 한쪽만 더 들어와 **없던
시간차가 생긴다.** 5ms 면 음속으로 1.7m 로, 재려는 값과 같은 자릿수다.

**겹침 계산**: 평균 `A` 회를 50% 겹침으로 모으려면
`fftSize + (A - 1) × fftSize/2` 표본이 필요하다. `A=16`·`fftSize=8192` 면
**69,632 표본**(1.45초)이다. 고리는 거기에 최대 지연(24,000)을 더해 넉넉히
**131,072** 로 잡는다.

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TransferEngineTest {

    private val fft = 256
    private val avgs = 8
    private val rng = Random(20261002)

    private fun engine() = TransferEngine(
        sampleRate = 48_000,
        fftSize = fft,
        averages = avgs,
        maxLagSamples = 500,
    )

    private fun noise(n: Int) = FloatArray(n) { (rng.nextDouble() * 2 - 1).toFloat() }

    @Test
    fun `자료가 모자라면 아무것도 안 돌려준다`() {
        val e = engine()
        e.offerReference(noise(100), 0, 100)
        e.offerMeasurement(noise(100), 0, 100)
        assertNull(e.measure())
    }

    @Test
    fun `들어온 개수를 따로 센다`() {
        val e = engine()
        e.offerReference(noise(30), 0, 30)
        e.offerMeasurement(noise(10), 0, 10)
        assertEquals(30L, e.referenceCount)
        assertEquals(10L, e.measurementCount)
    }

    @Test
    fun `표본을 ms 로 옮긴다`() {
        assertEquals(10.0, engine().delayMs(480), 1e-9)
    }

    @Test
    fun `reset 하면 둘 다 비운다`() {
        val e = engine()
        e.offerReference(noise(30), 0, 30)
        e.offerMeasurement(noise(30), 0, 30)
        e.reset()
        assertEquals(0L, e.referenceCount)
        assertEquals(0L, e.measurementCount)
    }

    /** **이 시험이 전체를 꿰뚫는다** — 지연을 찾고, 그만큼 맞춰 떠서, 0dB 을 낸다. */
    @Test
    fun `지연이 있어도 맞춰 떠서 0dB 과 상관 1 을 낸다`() {
        val e = engine()
        val lag = 300
        val total = 20_000
        val x = noise(total)

        // 측정은 기준보다 lag 만큼 늦게 들어온다.
        val y = FloatArray(total) { if (it < lag) 0f else x[it - lag] }

        e.offerReference(x, 0, total)
        e.offerMeasurement(y, 0, total)

        val m = e.measure()
        assertNotNull("아무것도 안 나왔다", m)
        m!!

        assertTrue("지연을 못 찾았다", m.delay.found)
        assertEquals("지연이 틀렸다", lag, m.delay.samples)

        val t = m.transfer
        assertNotNull("전달함수가 없다", t)
        t!!
        assertEquals("평균 수가 다르다", avgs, t.averages)

        val b = t.magnitudeDb.size / 4
        assertTrue("bin $b 가 무효다", t.valid[b])
        assertEquals("0dB 이 아니다", 0.0, t.magnitudeDb[b], 0.5)
        assertTrue("상관이 ${t.coherence[b]} 로 낮다", t.coherence[b] > 0.95)
    }

    /** 지연을 못 찾으면 전달함수를 내지 않는다 — 시간이 안 맞은 값은 뜻이 없다. */
    @Test
    fun `지연을 못 찾으면 전달함수를 내지 않는다`() {
        val e = engine()
        val total = 20_000
        e.offerReference(noise(total), 0, total)
        e.offerMeasurement(noise(total), 0, total)   // 관계없는 잡음

        val m = e.measure()
        assertNotNull(m)
        assertTrue("관계없는데 지연을 찾았다", !m!!.delay.found)
        assertNull("시간이 안 맞았는데 전달함수를 냈다", m.transfer)
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.TransferEngineTest'
```
기대: 컴파일 실패.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.dsp

/** 한 번 잰 결과. [transfer] 는 **지연을 찾았을 때만** 있다. */
data class TransferMeasurement(val delay: DelayResult, val transfer: TransferResult?)

/**
 * 기준과 측정을 모아 두었다가 **지연 → 시간 정렬 → 전달함수** 순으로 잰다.
 *
 * ## 잠금이 **하나**인 까닭
 *
 * 두 고리는 **서로 다른 스레드**가 채운다 — 기준은 재생 스레드, 측정은
 * 캡처 스레드다. 창을 따로따로 뜨면 그 사이에 한쪽만 더 들어와 **없던
 * 시간차가 생긴다.** 5ms 만 어긋나도 음속으로 1.7m 다 — 우리가 재려는
 * 값과 같은 자릿수다.
 *
 * ## 순서를 뒤집지 않는다
 *
 * **지연을 못 찾으면 전달함수를 내지 않는다.** 시간이 안 맞은 채로 곱한
 * 스펙트럼은 그럴듯한 그림을 내놓지만 **Coherence 가 통째로 무너진** 값이다
 * (명세 3장: 시간축이 맞지 않은 상태에서 그럴듯한 그래프를 만들지 않는다).
 */
class TransferEngine(
    private val sampleRate: Int = 48_000,
    private val fftSize: Int = 8192,
    /** 50% 겹침으로 모을 블록 수. 명세 9장의 기본 목표가 16 이다. */
    private val averages: Int = 16,
    maxLagSamples: Int = 24_000,
) {
    init {
        require(sampleRate > 0) { "sampleRate 는 1 이상: $sampleRate" }
        require(averages > 0) { "averages 는 1 이상: $averages" }
    }

    private val hop = fftSize / 2

    /** 평균 [averages] 회를 50% 겹침으로 모으는 데 필요한 표본 수. */
    private val span = fftSize + (averages - 1) * hop

    /** 지연 보정까지 담을 만큼 넉넉히. */
    private val capacity = Integer.highestOneBit(span + maxLagSamples) * 2

    private val lock = Any()
    private val refRing = SampleRing(capacity)
    private val measRing = SampleRing(capacity)

    private val estimator = DelayEstimator(
        analysisSize = minOf(span, 32_768),
        maxLagSamples = maxLagSamples,
    )
    private val averager = SpectralAverager(fftSize)

    private val refWindow = DoubleArray(span)
    private val measWindow = DoubleArray(span)
    private val refAligned = DoubleArray(span)

    val referenceCount: Long get() = synchronized(lock) { refRing.written }
    val measurementCount: Long get() = synchronized(lock) { measRing.written }

    fun offerReference(src: FloatArray, offset: Int, count: Int) =
        synchronized(lock) { refRing.write(src, offset, count) }

    fun offerMeasurement(src: FloatArray, offset: Int, count: Int) =
        synchronized(lock) { measRing.write(src, offset, count) }

    /**
     * 지금 쌓인 것으로 한 번 잰다.
     *
     * **둘 다 창을 가득 채우지 못했으면 재지 않는다** — 앞이 0 으로 채워진
     * 창끼리 견주면 그 0 구간이 상관에 끼어든다.
     */
    fun measure(): TransferMeasurement? {
        val needed = (span + estimatorLag()).toLong()
        synchronized(lock) {
            if (refRing.written < needed || measRing.written < span) return null
            refRing.snapshot(refWindow)
            measRing.snapshot(measWindow)
        }

        val delay = estimator.estimate(refWindow, measWindow)
        if (!delay.found) return TransferMeasurement(delay, null)

        // **기준을 지연만큼 거슬러 뜬다** — 측정은 그만큼 늦게 들어왔다.
        synchronized(lock) {
            if (refRing.snapshot(refAligned, lagBack = delay.samples) < span) {
                return TransferMeasurement(delay, null)
            }
        }

        averager.reset()
        for (k in 0 until averages) {
            val at = k * hop
            averager.addBlock(refAligned, at, measWindow, at)
        }
        return TransferMeasurement(delay, transferFunction(averager))
    }

    fun delayMs(samples: Int): Double = samples * 1000.0 / sampleRate

    fun reset() = synchronized(lock) { refRing.clear(); measRing.clear() }

    /** 기준은 지연만큼 더 쌓여 있어야 거슬러 뜰 수 있다. */
    private fun estimatorLag(): Int = 0
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
./gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.TransferEngineTest'
```
기대: 6개 통과.

**「지연이 있어도 맞춰 떠서 0dB」이 실패하면** 정렬 방향을 의심한다.
`lagBack` 을 기준이 아니라 측정에 걸면 지연이 **두 배**가 되어 상관이
무너진다. 측정이 늦게 들어오므로 **기준을 거슬러 뜨는 것**이 맞다.

- [ ] **Step 5: 변이로 그물을 확인한다**

`refRing.snapshot(refAligned, lagBack = delay.samples)` 의 `lagBack` 을
`0` 으로 바꾼다(시간 정렬을 안 함).

기대: **「지연이 있어도 맞춰 떠서 0dB 과 상관 1 을 낸다」가 실패해야 한다.**
통과하면 그 시험은 정렬을 보고 있지 않은 것이다. 확인한 뒤 되돌린다.

- [ ] **Step 6: 전체 시험을 돌린다 (회귀 확인)**

```
./gradlew :dsp:test :app:testDebugUnitTest
```
기대: **전부 통과.** 기존 시험이 깨지지 않았는지 본다.

- [ ] **Step 7: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/TransferEngine.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/TransferEngineTest.kt
git commit -m "feat(전달함수): 지연을 찾고 시간을 맞춰 전달함수를 낸다

두 고리를 서로 다른 스레드가 채운다 — 기준은 재생, 측정은 캡처다.
창을 따로 뜨면 그 사이에 한쪽만 더 들어와 없던 시간차가 생긴다. 5ms 면
음속으로 1.7m 라 재려는 값과 같은 자릿수다. 잠금 하나로 묶었다.

순서를 뒤집지 않는다 — 지연을 못 찾으면 전달함수를 내지 않는다. 시간이
안 맞은 채로 곱한 스펙트럼은 그럴듯한 그림을 내놓지만 Coherence 가
통째로 무너진 값이다.

측정이 늦게 들어오므로 기준을 그만큼 거슬러 떠서 맞춘다. 그 자리를
변이로 확인했다 — lagBack 을 0 으로 두면 정렬 시험이 깨진다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## 자기 점검 (계획을 쓴 뒤 명세와 맞대어 본 것)

**명세 덮기**

| 명세 | 어느 작업 |
|---|---|
| 8장 H1 = Sxy/Sxx · 스펙트럼 먼저 평균 | Task 4 · 5 |
| 8장 **기준 약한 bin 무효** | Task 5 |
| 9장 γ² = \|Sxy\|²/(Sxx·Syy) | Task 5 |
| 9장 **1~7 숨김 / 8~15 안정화 / 16+ 표시** | Task 5 (`coherenceDisplay`) |
| 9장 8/16 은 초기 운영값 | Task 5 의 KDoc |
| 7장 AudioTimestamp · **UNAVAILABLE** | Task 6 |
| 7장 `TIMEBASE_MONOTONIC` | Task 6 의 KDoc (부르는 쪽 계약) |
| 29장 FFT 8192 · Hann · 50% 겹침 | Task 4 · 7 |
| 4장 기준은 **실제로 나간 표본** | Task 3 |
| 3장 시간축 안 맞으면 그래프 안 그림 | Task 7 |
| 6장 Delay Finder | Task 2 |

**이 계획이 안 덮는 것**(다음 계획): 화면 · 배선 · `상대 비교 모드` 표시 ·
`AudioTimestamp` 를 실제로 부르는 앱 코드 · 실기기 확인. **폰이 필요하다.**

**이름 일관성**: `DelayResult(samples, sharpness, found)` 가 Task 2·7 에서
같다. `TransferResult(magnitudeDb, coherence, valid, averages)` 가 Task 5·7
에서 같다. `SpectralAverager(bins, count, sxx, syy, sxyRe, sxyIm)` 가
Task 4·5 에서 같다. `snapshot(out, lagBack)` 이 Task 1·7 에서 같다.

**변이 확인을 다섯 자리에 넣었다** — Task 2(역변환) · 3(`wrote`) ·
4(상호 스펙트럼의 허수) · 5(유효 bin) · 7(시간 정렬). 바꿔치기했을 때
시험이 실패하지 않으면 그 시험은 헛그물이다.
