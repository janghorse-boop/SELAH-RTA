# Transfer Function 1차 — 문과 지연 찾기 구현 계획

> ## ⛔ 실행 중단 — 다시 쓸 때까지 이 계획으로 구현하지 않는다 (2026-10-01 저녁)
>
> **Codex 명세 검토 회신이 1차 MVP 를 `Delay + Clock Drift + Magnitude +
> Coherence` 로 넓혔다**(개발지시서 32장). 이 계획은 Delay 하나만 담고 있다.
>
> **Task 1(`SampleRing`)까지 끝났다** — 커밋 `4064631`, 시험 5/5 통과.
> 순수 자료구조라 Codex 의 10개 지적 어디에도 안 걸려 가지
> `feat/transfer-function-delay` 에 남겨 두었다. **Task 2 이후는 돌리지
> 않았다.**
>
> 개정된 명세를 Codex 가 최종 검토한 뒤 계획을 다시 쓴다. **지금도 그대로
> 쓸 수 있는 것**: Task 2(상호상관·PHAT) · Task 3(잠금 하나) · Task 4
> (`TappedSink` 와 `write()` 의 함정). **다시 봐야 할 것**: Task 5·6 의
> 화면 범위(Magnitude·Coherence 가 1차에 들어온다) · Task 7.
>
> 재개 기록: `.superpowers/sdd/2026-10-01-transfer-function-shell-and-delay/progress.md`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 분석 탭에 Transfer Function 으로 들어가는 문을 달고, 그 안에서
**기준 신호와 마이크 신호 사이의 지연(ms)** 을 재어 보여준다.

**Architecture:** 기준 신호는 `SignalPlayer` 의 `openSink` 공장에 덧씌우기를
끼워 **실제로 출력에 넘어간 표본**을 가로챈다. 측정 신호는 캡처 루프가
이미 넘기는 원시 PCM 블록에서 받는다. 둘을 **하나의 잠금 아래** 고리 버퍼에
쌓고, FFT 상호상관으로 시간차를 찾는다. 화면은 교정 마법사와 같은 방식으로
분석 위를 덮는다.

**Tech Stack:** Kotlin · Jetpack Compose · `dsp` 모듈(안드로이드 의존성 없음) ·
기존 `Fft` 클래스 · JUnit4

스펙: [`docs/superpowers/specs/2026-10-01-transfer-function-shell-and-delay-design.md`](../specs/2026-10-01-transfer-function-shell-and-delay-design.md)

## Global Constraints

- **`dsp` 모듈에는 안드로이드 의존성을 넣지 않는다.** 순수 Kotlin 만.
- **UI 문구는 한국어.** 모든 파일은 UTF-8.
- **`SignalPlayer`·`AudioTrackSink`·`ui/nav/Navigation.kt`·`AnalyzeModes`·설정
  화면을 고치지 않는다.** 아이콘도 더하지 않는다.
- **Magnitude·Coherence·Phase·정밀 모드·Multi-Point 는 이번에 만들지 않는다.**
- **「측정 신뢰도」 칸은 `○ 측정 중` 으로 고정한다.** coherence 가 없으므로
  신뢰도를 말할 수 없다. 빈칸으로 두거나 「좋음」을 띄우지 않는다.
- **진입 화면(간편/정밀 고르기)을 만들지 않는다.** 문을 누르면 간편 화면으로
  바로 간다. 「준비 중」으로 흐리게 띄우지도 않는다.
- 샘플레이트 상수: **48000**. 분석 길이 **32768** 표본. FFT 크기 **65536**.
  최대 탐색 지연 **24000** 표본(500 ms).
- 빌드 명령: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` 뒤
  `.\gradlew :dsp:test` 또는 `.\gradlew :app:testDebugUnitTest`
- 브랜치의 **마지막 커밋은 CHANGELOG** 다(Task 7).

---

## 파일 구조

| 파일 | 맡은 일 |
|---|---|
| `dsp/src/main/kotlin/kr/joa/selahrta/dsp/SampleRing.kt` | 모노 표본 고리 버퍼. **잠금은 밖에서** 잡는다 |
| `dsp/src/main/kotlin/kr/joa/selahrta/dsp/DelayEstimator.kt` | FFT 상호상관으로 지연을 찾는다. 또렷하지 않으면 **못 찾았다고 말한다** |
| `dsp/src/main/kotlin/kr/joa/selahrta/dsp/TransferEngine.kt` | 고리 둘 + **하나의 잠금**. 두 창을 같은 순간에 뜬다 |
| `app/src/main/java/kr/joa/selahrta/transfer/TappedSink.kt` | `SignalSink` 덧씌우기. 인터리브 칸 → 모노 |
| `app/src/main/java/kr/joa/selahrta/ui/TransferViewModel.kt` | 화면 상태와 재는 주기 |
| `app/src/main/java/kr/joa/selahrta/ui/screens/transfer/SimpleTransferScreen.kt` | 간편 화면(가로) |

**고치는 파일 둘**: `ui/screens/AnalyzeScreens.kt`(문 한 줄) ·
`ui/SelahApp.kt`(문 열고 닫기).

---

### Task 1: SampleRing — 모노 표본 고리 버퍼

**Files:**
- Create: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/SampleRing.kt`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/SampleRingTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces: `class SampleRing(capacity: Int)` ·
  `fun write(src: FloatArray, offset: Int, count: Int)` ·
  `fun snapshot(out: DoubleArray): Int` · `fun clear()` · `val written: Long`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SampleRingTest {

    @Test
    fun `가장 최근 것을 시간 순으로 돌려준다`() {
        val ring = SampleRing(4)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f), 0, 5)
        val out = DoubleArray(4)
        assertEquals(4, ring.snapshot(out))
        assertArrayEquals(doubleArrayOf(2.0, 3.0, 4.0, 5.0), out, 1e-9)
    }

    @Test
    fun `아직 덜 찼으면 채운 수를 돌려주고 앞을 0 으로 둔다`() {
        val ring = SampleRing(4)
        ring.write(floatArrayOf(7f, 8f), 0, 2)
        val out = DoubleArray(4)
        assertEquals(2, ring.snapshot(out))
        // 뒤쪽 두 칸에 자료가 오고 앞은 0 이다 — 뜬 창의 **끝**이 지금이다.
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 7.0, 8.0), out, 1e-9)
    }

    @Test
    fun `offset 을 지킨다`() {
        val ring = SampleRing(3)
        ring.write(floatArrayOf(9f, 1f, 2f, 3f), 1, 3)
        val out = DoubleArray(3)
        ring.snapshot(out)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), out, 1e-9)
    }

    @Test
    fun `들어온 총 개수를 센다`() {
        val ring = SampleRing(4)
        ring.write(FloatArray(10), 0, 10)
        assertEquals(10L, ring.written)
    }

    @Test
    fun `clear 하면 비고 센 수도 0 이 된다`() {
        val ring = SampleRing(4)
        ring.write(floatArrayOf(1f, 2f), 0, 2)
        ring.clear()
        val out = DoubleArray(4)
        assertEquals(0, ring.snapshot(out))
        assertEquals(0L, ring.written)
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
.\gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.SampleRingTest'
```
기대: 컴파일 실패 — `SampleRing` 이 없다.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.dsp

/**
 * 모노 표본을 담아 두는 고리 버퍼.
 *
 * **잠금을 스스로 잡지 않는다.** 기준과 측정 두 고리를 **같은 순간에**
 * 떠야 하는데, 고리마다 따로 잠그면 그 사이에 한쪽만 더 들어와
 * 시간차가 생긴다. 그 시간차가 바로 우리가 재려는 값이라 섞이면 안 된다.
 * 잠금은 [TransferEngine] 이 하나로 잡는다.
 */
class SampleRing(private val capacity: Int) {
    init { require(capacity > 0) { "capacity 는 1 이상이라야 한다: $capacity" } }

    private val buf = FloatArray(capacity)
    private var head = 0

    /** 지금까지 들어온 총 개수. 덮어쓴 것도 센다. */
    var written: Long = 0L
        private set

    fun write(src: FloatArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset + count <= src.size) {
            "offset=$offset count=$count 가 크기 ${src.size} 를 벗어난다"
        }
        for (i in 0 until count) {
            buf[head] = src[offset + i]
            head = (head + 1) % capacity
        }
        written += count
    }

    /**
     * 가장 최근 `out.size` 개를 **시간 순으로** 담는다.
     *
     * 아직 그만큼 안 들어왔으면 **앞쪽을 0 으로 두고 뒤에 채운다** —
     * 창의 **끝이 지금**이어야 상호상관의 지연이 뒤집히지 않는다.
     *
     * @return 실제로 채운 개수.
     */
    fun snapshot(out: DoubleArray): Int {
        val have = minOf(written, capacity.toLong()).toInt()
        val take = minOf(have, out.size)
        java.util.Arrays.fill(out, 0, out.size - take, 0.0)
        // head 는 **다음에 쓸 자리**다. 그 바로 앞이 가장 최근이다.
        var idx = ((head - take) % capacity + capacity) % capacity
        for (i in out.size - take until out.size) {
            out[i] = buf[idx].toDouble()
            idx = (idx + 1) % capacity
        }
        return take
    }

    fun clear() {
        java.util.Arrays.fill(buf, 0f)
        head = 0
        written = 0L
    }
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
.\gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.SampleRingTest'
```
기대: 5개 통과.

- [ ] **Step 5: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/SampleRing.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/SampleRingTest.kt
git commit -m "feat(전달함수): 모노 표본 고리 버퍼

잠금을 스스로 잡지 않는다 — 기준과 측정을 같은 순간에 떠야 하는데
고리마다 따로 잠그면 그 사이에 한쪽만 더 들어와 시간차가 생긴다.
그 시간차가 우리가 재려는 값이라 섞이면 안 된다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: DelayEstimator — FFT 상호상관

**Files:**
- Create: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/DelayEstimator.kt`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/DelayEstimatorTest.kt`

**Interfaces:**
- Consumes: 기존 `class Fft(size: Int)` 의 `fun transform(re: DoubleArray, im: DoubleArray)`
- Produces: `data class DelayResult(val samples: Int, val sharpness: Double, val found: Boolean)` ·
  `class DelayEstimator(analysisSize: Int = 32768, maxLagSamples: Int = 24000, minSharpness: Double = 2.0)` ·
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
 * 시험은 마지막 둘이다. 조용한 방에서 잡음에 걸린 값도 숫자로는 멀쩡해
 * 보인다.
 */
class DelayEstimatorTest {

    private val n = 8192
    private val rng = Random(20261001)

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
        // SNR 0dB — 신호만큼 큰 잡음을 더한다.
        val y = delayed(x, 500).mapIndexed { i, v -> v + noise(n)[i] }.toDoubleArray()
        val r = est().estimate(x, y)
        assertTrue(r.found)
        assertEquals(500, r.samples)
    }

    @Test
    fun `반사가 섞여도 본 신호를 고른다`() {
        val x = noise(n)
        val direct = delayed(x, 300)
        val reflect = delayed(x, 300 + 480, gain = 0.5)   // 10ms 뒤 -6dB
        val y = DoubleArray(n) { direct[it] + reflect[it] }
        val r = est().estimate(x, y)
        assertTrue(r.found)
        assertEquals(300, r.samples)
    }

    /**
     * **PHAT 가 정말 더 뾰족한가** — 말이 아니라 숫자로 본다.
     *
     * 잔향을 흉내 낸다: 본 신호 뒤에 반사 여럿을 깔고 점점 줄인다.
     * 잔향이 있는 공간이 이렇게 들린다.
     */
    @Test
    fun `잔향 속에서 PHAT 가 더 또렷하다`() {
        val x = noise(n)
        val y = DoubleArray(n)
        var gain = 1.0
        var lag = 400
        repeat(8) {
            val echo = delayed(x, lag, gain)
            for (i in 0 until n) y[i] += echo[i]
            lag += 170          // 3.5ms 간격
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

    /** **제일 중요한 시험.** 관계없는 잡음끼리는 「못 찾았다」라야 한다. */
    @Test
    fun `관계없는 잡음끼리는 못 찾았다고 말한다`() {
        val r = est().estimate(noise(n), noise(n))
        assertFalse("관계없는 잡음에서 지연을 찾았다고 말한다", r.found)
    }

    /** 탐색 범위 밖은 접어서 엉뚱한 답을 내지 않는다. */
    @Test
    fun `탐색 범위를 넘는 지연은 못 찾았다고 말한다`() {
        val x = noise(n)
        val r = DelayEstimator(analysisSize = n, maxLagSamples = 500)
            .estimate(x, delayed(x, 3000))
        assertFalse("범위 밖 지연을 찾았다고 말한다", r.found)
    }

    @Test
    fun `아무 소리도 없으면 못 찾았다고 말한다`() {
        val r = est().estimate(DoubleArray(n), DoubleArray(n))
        assertFalse(r.found)
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
.\gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.DelayEstimatorTest'
```
기대: 컴파일 실패 — `DelayEstimator` 가 없다.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.dsp

import kotlin.math.abs

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
 * 조용한 방에서는 **잡음끼리도 어딘가에서 가장 큰 값**이 나온다. 그 값을
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
     * 상호 스펙트럼의 **크기를 1 로 고르고 위상만 남긴다.** 잔향이 긴 공간에서는
     * 잔향이 긴 곳에서 봉우리가 훨씬 뾰족해진다 — 그래야 「또렷함」으로
     * 참·거짓을 가를 수 있다. 잔향 속 지연 추정의 표준 방법이다.
     *
     * **끌 수 있게 둔 까닭**: 끈 것과 견주는 시험이 있어야 「정말 더
     * 뾰족한가」를 말로가 아니라 숫자로 보일 수 있다.
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
                val mag = kotlin.math.sqrt(re * re + im * im)
                // **0 으로 나누지 않는다.** 소리가 없는 대역은 크기가 0 이다.
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

        // 0..maxLag 에서 으뜸과 버금을 찾는다. **버금은 으뜸 둘레를 뺀
        // 곳에서** 고른다 — 봉우리 바로 옆은 같은 봉우리의 어깨다.
        var best = -1
        var bestVal = 0.0
        for (t in 0..maxLagSamples) {
            val v = abs(xRe[t])
            if (v > bestVal) { bestVal = v; best = t }
        }
        if (best < 0 || bestVal <= 0.0) return DelayResult(0, 0.0, false)

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
     * 돌리고 다시 바꾸면 역변환이 된다.
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
.\gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.DelayEstimatorTest'
```
기대: 8개 통과. **실패하면 `minSharpness` 를 올려 맞추지 말 것** — 그러면
「관계없는 잡음」 시험이 통과하는 대신 「잡음 섞인」 시험이 깨진다. 둘 다
통과하는 값을 찾아야 한다(2.0 에서 시작해 1.5~3.0 사이를 본다).

- [ ] **Step 5: 변이로 그물을 확인한다**

`inverse` 의 `fft.transform(im, re)` 를 `fft.transform(re, im)` 으로 바꾼다
(실수·허수를 안 바꿈 = 역변환이 아님). 시험을 돌린다.

기대: **「여러 지연을 그대로 찾는다」가 실패해야 한다.** 통과하면 그 시험은
헛그물이다. 확인한 뒤 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/DelayEstimator.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/DelayEstimatorTest.kt
git commit -m "feat(전달함수): FFT 상호상관으로 지연을 찾는다

숫자만 내놓지 않는다. 조용한 방에서는 잡음끼리도 어딘가에서 가장 큰
값이 나오고, 그 값을 지연이라고 적으면 사람은 그 숫자로 스피커를
정렬한다. 또렷함(으뜸 ÷ 버금)이 문턱 아래면 못 찾았다고 말한다.

Fft 에는 정변환밖에 없어 실수·허수를 바꿔 역변환을 만든다. 그 자리를
변이로 확인했다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: TransferEngine — 두 창을 같은 순간에 뜬다

**Files:**
- Create: `dsp/src/main/kotlin/kr/joa/selahrta/dsp/TransferEngine.kt`
- Test: `dsp/src/test/kotlin/kr/joa/selahrta/dsp/TransferEngineTest.kt`

**Interfaces:**
- Consumes: `SampleRing` (Task 1) · `DelayEstimator` · `DelayResult` (Task 2)
- Produces: `class TransferEngine(sampleRate: Int = 48_000, analysisSize: Int = 32_768, maxLagSamples: Int = 24_000)` ·
  `fun offerReference(src: FloatArray, offset: Int, count: Int)` ·
  `fun offerMeasurement(src: FloatArray, offset: Int, count: Int)` ·
  `fun estimate(): DelayResult` · `fun delayMs(samples: Int): Double` · `fun reset()` ·
  `val referenceCount: Long` · `val measurementCount: Long`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TransferEngineTest {

    private val n = 8192

    private fun engine() = TransferEngine(sampleRate = 48_000, analysisSize = n, maxLagSamples = 2000)

    @Test
    fun `넣은 지연을 그대로 돌려준다`() {
        val rng = Random(7)
        val x = FloatArray(n) { (rng.nextDouble() * 2 - 1).toFloat() }
        val lag = 480
        val y = FloatArray(n) { if (it < lag) 0f else x[it - lag] }

        val e = engine()
        e.offerReference(x, 0, n)
        e.offerMeasurement(y, 0, n)

        val r = e.estimate()
        assertTrue(r.found)
        assertEquals(lag, r.samples)
    }

    @Test
    fun `표본을 ms 로 옮긴다`() {
        // 48000Hz 에서 480 표본은 10ms 다.
        assertEquals(10.0, engine().delayMs(480), 1e-9)
    }

    @Test
    fun `자료가 모자라면 못 찾았다고 말한다`() {
        val e = engine()
        e.offerReference(FloatArray(100), 0, 100)
        e.offerMeasurement(FloatArray(100), 0, 100)
        assertFalse(e.estimate().found)
    }

    @Test
    fun `들어온 개수를 따로 센다`() {
        val e = engine()
        e.offerReference(FloatArray(30), 0, 30)
        e.offerMeasurement(FloatArray(10), 0, 10)
        assertEquals(30L, e.referenceCount)
        assertEquals(10L, e.measurementCount)
    }

    @Test
    fun `reset 하면 둘 다 비운다`() {
        val e = engine()
        e.offerReference(FloatArray(30), 0, 30)
        e.offerMeasurement(FloatArray(30), 0, 30)
        e.reset()
        assertEquals(0L, e.referenceCount)
        assertEquals(0L, e.measurementCount)
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
.\gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.TransferEngineTest'
```
기대: 컴파일 실패.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.dsp

/**
 * 기준과 측정을 모아 두었다가 지연을 잰다.
 *
 * ## 잠금이 **하나**인 까닭
 *
 * 두 고리는 **서로 다른 스레드**가 채운다 — 기준은 재생 스레드, 측정은
 * 캡처 스레드다. 창을 따로따로 뜨면 그 사이에 한쪽만 더 들어와 **없던
 * 시간차가 생긴다.** 5ms 만 어긋나도 음속으로 1.7m 다 — 우리가 재려는
 * 값과 같은 자릿수다.
 *
 * 그래서 두 고리를 **한 잠금 아래** 두고 한 번에 뜬다.
 */
class TransferEngine(
    private val sampleRate: Int = 48_000,
    private val analysisSize: Int = 32_768,
    maxLagSamples: Int = 24_000,
) {
    private val lock = Any()
    private val refRing = SampleRing(analysisSize)
    private val measRing = SampleRing(analysisSize)
    private val estimator = DelayEstimator(analysisSize, maxLagSamples)

    private val refBuf = DoubleArray(analysisSize)
    private val measBuf = DoubleArray(analysisSize)

    val referenceCount: Long get() = synchronized(lock) { refRing.written }
    val measurementCount: Long get() = synchronized(lock) { measRing.written }

    fun offerReference(src: FloatArray, offset: Int, count: Int) =
        synchronized(lock) { refRing.write(src, offset, count) }

    fun offerMeasurement(src: FloatArray, offset: Int, count: Int) =
        synchronized(lock) { measRing.write(src, offset, count) }

    /**
     * 지금 쌓인 것으로 지연을 잰다.
     *
     * **둘 다 창을 가득 채우지 못했으면 재지 않는다.** 앞이 0 으로 채워진
     * 창끼리 견주면 그 0 구간이 상관에 끼어든다.
     */
    fun estimate(): DelayResult {
        synchronized(lock) {
            if (refRing.written < analysisSize || measRing.written < analysisSize) {
                return DelayResult(0, 0.0, false)
            }
            refRing.snapshot(refBuf)
            measRing.snapshot(measBuf)
        }
        return estimator.estimate(refBuf, measBuf)
    }

    fun delayMs(samples: Int): Double = samples * 1000.0 / sampleRate

    fun reset() = synchronized(lock) { refRing.clear(); measRing.clear() }
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
.\gradlew :dsp:test --tests 'kr.joa.selahrta.dsp.TransferEngineTest'
```
기대: 5개 통과.

- [ ] **Step 5: 커밋**

```bash
git add dsp/src/main/kotlin/kr/joa/selahrta/dsp/TransferEngine.kt dsp/src/test/kotlin/kr/joa/selahrta/dsp/TransferEngineTest.kt
git commit -m "feat(전달함수): 기준과 측정을 한 잠금 아래 모은다

두 고리를 서로 다른 스레드가 채운다 — 기준은 재생, 측정은 캡처다.
창을 따로 뜨면 그 사이에 한쪽만 더 들어와 없던 시간차가 생긴다.
5ms 만 어긋나도 음속으로 1.7m 라, 재려는 값과 같은 자릿수다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: TappedSink — 실제로 나간 표본만 가로챈다

**Files:**
- Create: `app/src/main/java/kr/joa/selahrta/transfer/TappedSink.kt`
- Test: `app/src/test/java/kr/joa/selahrta/transfer/TappedSinkTest.kt`

**Interfaces:**
- Consumes: 기존 `interface SignalSink`(`kr.joa.selahrta.audio`) — `open(sampleRate, frames, channels): Boolean` ·
  `write(buf, offset, frames): Int` · `stop()` · `release(): Boolean` · `SignalSink.ERROR_DEAD_OBJECT`
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
            for (i in 0 until n) got.add(buf[offset + i])
            return n
        }
        override fun stop() {}
        override fun release() = true
    }

    private fun collect(): Pair<MutableList<Float>, (FloatArray, Int, Int) -> Unit> {
        val out = mutableListOf<Float>()
        return out to { b, o, c -> for (i in 0 until c) out.add(b[o + i]) }
    }

    /** 스테레오 인터리브를 모노로 합친다. L+R 이다 — 상관은 크기에 무관하다. */
    @Test
    fun `인터리브를 모노로 합친다`() {
        val (mono, cb) = collect()
        val sink = TappedSink(PartialSink { it }, cb)
        // L,R,L,R — 프레임 둘
        sink.write(floatArrayOf(1f, 2f, 3f, 4f), 0, 4)
        assertArrayEquals(floatArrayOf(3f, 7f), mono.toFloatArray(), 1e-6f)
    }

    /** **이것이 이번에 막는 자리다.** */
    @Test
    fun `받아들여진 칸만 적는다`() {
        val (mono, cb) = collect()
        // 네 칸을 넘겨도 두 칸만 받아들인다 = 프레임 하나.
        val sink = TappedSink(PartialSink { 2 }, cb)
        sink.write(floatArrayOf(1f, 2f, 3f, 4f), 0, 4)
        assertArrayEquals(floatArrayOf(3f), mono.toFloatArray(), 1e-6f)
    }

    /** 홀수 칸으로 끊겨도 프레임 경계를 잃지 않는다. */
    @Test
    fun `홀수 칸으로 나누어 써도 같은 기준이 나온다`() {
        val (mono, cb) = collect()
        val sink = TappedSink(PartialSink { 1 }, cb)   // 한 칸씩만 받는다
        // L,R,L,R 을 한 칸씩 네 번 넘긴다
        val buf = floatArrayOf(1f, 2f, 3f, 4f)
        for (i in 0 until 4) sink.write(buf, i, 1)
        assertArrayEquals(floatArrayOf(3f, 7f), mono.toFloatArray(), 1e-6f)
    }

    @Test
    fun `오류면 아무것도 적지 않는다`() {
        val (mono, cb) = collect()
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
        val (mono, cb) = collect()
        val sink = TappedSink(PartialSink { 1 }, cb)
        sink.write(floatArrayOf(1f), 0, 1)          // L 만 들어감
        sink.open(48_000, 1024, 2)                   // 다시 염
        sink.write(floatArrayOf(10f, 20f), 0, 1)     // L=10
        sink.write(floatArrayOf(10f, 20f), 1, 1)     // R=20
        assertArrayEquals(floatArrayOf(30f), mono.toFloatArray(), 1e-6f)
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
.\gradlew :app:testDebugUnitTest --tests 'kr.joa.selahrta.transfer.TappedSinkTest'
```
기대: 컴파일 실패.

- [ ] **Step 3: 최소 구현**

```kotlin
package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.SignalSink

/**
 * 실제로 출력에 넘어간 표본을 가로채는 덧씌우기.
 *
 * **「나중에 다시 만든 값」을 기준으로 쓰지 않는다**(개발지시서 4장).
 * 같은 식으로 다시 그린 파형은 볼륨·리샘플·부분 쓰기를 지나지 않아
 * 실제로 나간 것과 다르다.
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
.\gradlew :app:testDebugUnitTest --tests 'kr.joa.selahrta.transfer.TappedSinkTest'
```
기대: 6개 통과.

- [ ] **Step 5: 변이로 그물을 확인한다**

`val wrote = inner.write(...)` 뒤의 `for (i in 0 until wrote)` 를
`for (i in 0 until frames)` 로 바꾼다. 시험을 돌린다.

기대: **「받아들여진 칸만 적는다」가 실패해야 한다.** 통과하면 헛그물이다.
확인한 뒤 되돌린다.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/transfer/TappedSink.kt app/src/test/java/kr/joa/selahrta/transfer/TappedSinkTest.kt
git commit -m "feat(전달함수): 실제로 나간 표본만 기준으로 가로챈다

두 가지가 조용히 틀리기 쉬운 자리다.

write() 는 일부만 쓸 수 있다. 넘긴 버퍼 전체를 적으면 기준이 실제보다
앞서 가고 지연이 작게 나온다 — 화면에는 아무 표시도 안 난다. 돌려받은
수만큼만 적고, 그 자리를 변이로 확인했다.

세는 단위가 프레임이 아니라 칸이다. 홀수 칸에서 끊기면 프레임 경계가
어긋나므로 반 프레임을 들고 있다가 짝이 오면 내보낸다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: TransferViewModel — 상태를 그래프보다 먼저 정한다

**Files:**
- Create: `app/src/main/java/kr/joa/selahrta/ui/TransferViewModel.kt`
- Test: `app/src/test/java/kr/joa/selahrta/ui/TransferStateTest.kt`

**Interfaces:**
- Consumes: `TransferEngine` · `DelayResult`(Task 2·3)
- Produces: `enum class TransferPhase { NotMeasuring, NoSignal, NotAudible, Searching, Found, NotFound }` ·
  `data class TransferUiState(phase, delayMs: Double?, sharpness: Double?, noticeKo: String?)` ·
  `fun transferPhase(measuring: Boolean, referenceCount: Long, measurementPeak: Double, result: DelayResult?, needed: Long): TransferPhase`

- [ ] **Step 1: 실패하는 시험을 쓴다**

```kotlin
package kr.joa.selahrta.ui

import kr.joa.selahrta.dsp.DelayResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **상태를 그래프보다 먼저 정한다.** 무엇이 없을 때 무엇을 적는지가
 * 정해져 있어야 「조용히 틀린 숫자」가 안 나온다.
 */
class TransferStateTest {

    private val needed = 32_768L

    @Test
    fun `측정이 안 돌면 측정 안 함이다`() {
        assertEquals(
            TransferPhase.NotMeasuring,
            transferPhase(false, 0L, 0.0, null, needed),
        )
    }

    @Test
    fun `기준이 안 나가면 신호 없음이다`() {
        assertEquals(
            TransferPhase.NoSignal,
            transferPhase(true, 0L, 0.0, null, needed),
        )
    }

    @Test
    fun `기준은 나가는데 마이크가 조용하면 소리 안 들림이다`() {
        assertEquals(
            TransferPhase.NotAudible,
            transferPhase(true, needed, 1e-6, null, needed),
        )
    }

    @Test
    fun `아직 덜 모였으면 찾는 중이다`() {
        assertEquals(
            TransferPhase.Searching,
            transferPhase(true, 100L, 0.1, null, needed),
        )
    }

    @Test
    fun `찾았으면 찾음이다`() {
        assertEquals(
            TransferPhase.Found,
            transferPhase(true, needed, 0.1, DelayResult(480, 5.0, true), needed),
        )
    }

    @Test
    fun `또렷하지 않으면 못 찾음이다`() {
        assertEquals(
            TransferPhase.NotFound,
            transferPhase(true, needed, 0.1, DelayResult(0, 1.1, false), needed),
        )
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
.\gradlew :app:testDebugUnitTest --tests 'kr.joa.selahrta.ui.TransferStateTest'
```
기대: 컴파일 실패.

- [ ] **Step 3: 최소 구현**

`TransferViewModel.kt` 에 상태와 판정을 함께 둔다.

```kotlin
package kr.joa.selahrta.ui

import kr.joa.selahrta.dsp.DelayResult

/** 간편 전달함수 화면이 지금 어느 자리에 있는가. */
enum class TransferPhase {
    NotMeasuring,
    NoSignal,
    NotAudible,
    Searching,
    Found,
    NotFound,
}

/**
 * **「측정 신뢰도」는 아직 말할 수 없다.** coherence 가 없기 때문이다.
 * 화면은 `○ 측정 중` 으로 고정해 적는다 — 빈칸도 「좋음」도 거짓말이다.
 */
data class TransferUiState(
    val phase: TransferPhase = TransferPhase.NotMeasuring,
    val delayMs: Double? = null,
    val sharpness: Double? = null,
)

/** 마이크가 「들린다」고 볼 최소 크기(선형 진폭). −60dBFS 쯤이다. */
const val AUDIBLE_PEAK = 1e-3

/**
 * 상태를 가른다. **순서가 뜻이다** — 바깥 조건부터 본다. 측정이 안 도는데
 * 「소리가 안 들린다」고 적으면 사람은 스피커를 의심하러 간다.
 */
fun transferPhase(
    measuring: Boolean,
    referenceCount: Long,
    measurementPeak: Double,
    result: DelayResult?,
    needed: Long,
): TransferPhase = when {
    !measuring -> TransferPhase.NotMeasuring
    referenceCount == 0L -> TransferPhase.NoSignal
    referenceCount >= needed && measurementPeak < AUDIBLE_PEAK -> TransferPhase.NotAudible
    result == null || referenceCount < needed -> TransferPhase.Searching
    result.found -> TransferPhase.Found
    else -> TransferPhase.NotFound
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
.\gradlew :app:testDebugUnitTest --tests 'kr.joa.selahrta.ui.TransferStateTest'
```
기대: 6개 통과.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/ui/TransferViewModel.kt app/src/test/java/kr/joa/selahrta/ui/TransferStateTest.kt
git commit -m "feat(전달함수): 상태를 그래프보다 먼저 정한다

무엇이 없을 때 무엇을 적는지가 정해져 있어야 조용히 틀린 숫자가
안 나온다. 순서가 뜻이다 — 측정이 안 도는데 「소리가 안 들린다」고
적으면 사람은 스피커를 의심하러 간다.

측정 신뢰도는 아직 말할 수 없다(coherence 가 없다). 화면은 「측정 중」
으로 고정한다 — 빈칸도 「좋음」도 거짓말이다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 화면과 문 — 분석에서 들어가고 뒤로가기로 나온다

**Files:**
- Create: `app/src/main/java/kr/joa/selahrta/ui/screens/transfer/SimpleTransferScreen.kt`
- Modify: `app/src/main/java/kr/joa/selahrta/ui/screens/AnalyzeScreens.kt` (문 한 줄)
- Modify: `app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt` (문 열고 닫기)
- Test: `app/src/androidTest/java/kr/joa/selahrta/ui/screens/TransferDoorTest.kt`

**Interfaces:**
- Consumes: `TransferUiState` · `TransferPhase`(Task 5)
- Produces: `@Composable fun SimpleTransferScreen(state: TransferUiState, onClose: () -> Unit)` ·
  `@Composable fun TransferDoor(onOpen: () -> Unit)`

- [ ] **Step 1: 실패하는 계측 시험을 쓴다**

```kotlin
package kr.joa.selahrta.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kr.joa.selahrta.ui.TransferPhase
import kr.joa.selahrta.ui.TransferUiState
import kr.joa.selahrta.ui.screens.transfer.SimpleTransferScreen
import kr.joa.selahrta.ui.theme.SelahTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TransferDoorTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `문을 누르면 열겠다고 알린다`() {
        var opened = false
        rule.setContent { SelahTheme { TransferDoor(onOpen = { opened = true }) } }
        rule.onNodeWithText("Transfer Function", substring = true).performClick()
        assertTrue(opened)
    }

    /** **못 찾았을 때 숫자를 띄우지 않는다.** */
    @Test
    fun `못 찾으면 지연 숫자가 없다`() {
        rule.setContent {
            SelahTheme {
                SimpleTransferScreen(
                    state = TransferUiState(phase = TransferPhase.NotFound),
                    onClose = {},
                )
            }
        }
        rule.onNodeWithText("지연을 찾지 못했습니다", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("ms", substring = true).fetchSemanticsNodes().let {
            assertTrue("못 찾았는데 ms 가 떠 있다", it.isEmpty())
        }
    }

    @Test
    fun `찾으면 지연을 ms 로 적는다`() {
        rule.setContent {
            SelahTheme {
                SimpleTransferScreen(
                    state = TransferUiState(TransferPhase.Found, delayMs = 23.4, sharpness = 6.1),
                    onClose = {},
                )
            }
        }
        rule.onNodeWithText("23.4 ms", substring = true).assertIsDisplayed()
    }

    /** 신뢰도 칸은 「측정 중」으로 고정이다 — 「좋음」을 띄우지 않는다. */
    @Test
    fun `신뢰도는 측정 중으로 고정이다`() {
        rule.setContent {
            SelahTheme {
                SimpleTransferScreen(
                    state = TransferUiState(TransferPhase.Found, delayMs = 23.4, sharpness = 6.1),
                    onClose = {},
                )
            }
        }
        rule.onNodeWithText("측정 중", substring = true).assertIsDisplayed()
    }
}
```

- [ ] **Step 2: 돌려서 실패를 확인한다**

```
adb shell am instrument -w -r -e class 'kr.joa.selahrta.ui.screens.TransferDoorTest' kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
```
기대: 컴파일 실패. **폰 화면을 켜 두어야 한다** — 꺼지면 계측 시험이 죽는다.

- [ ] **Step 3: 화면을 만든다**

`SimpleTransferScreen.kt` — 가로 두 칸(왼쪽 숫자, 오른쪽 자리).

```kotlin
package kr.joa.selahrta.ui.screens.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.ui.TransferPhase
import kr.joa.selahrta.ui.TransferUiState
import kr.joa.selahrta.ui.components.InfoBar
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 간편 전달함수 — **1차는 지연만 잰다.**
 *
 * Magnitude 자리는 비워 둔다. **흐리게 그려 두지 않는다** — 화면이 하지
 * 않는 일을 한다고 적는 것으로 이미 한 번 데었다(2026-09-30).
 */
@Composable
fun SimpleTransferScreen(state: TransferUiState, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Transfer Function · 간편", color = SelahColors.TextPrimary, fontSize = 14.sp)
            TextButton(onClick = onClose) { Text("닫기") }
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("지연", color = SelahColors.TextMuted, fontSize = 11.sp)
                if (state.phase == TransferPhase.Found && state.delayMs != null) {
                    Text(
                        "%.1f ms".format(state.delayMs),
                        color = SelahColors.TextPrimary,
                        fontSize = 28.sp,
                    )
                    state.sharpness?.let {
                        // **또렷함을 그대로 적는다.** 문턱값을 실측으로
                        // 정하려면 담당자가 실제 자리에서 무엇이 나오는지
                        // 봐야 한다.
                        Text("또렷함 %.1f".format(it), color = SelahColors.TextMuted, fontSize = 10.sp)
                    }
                } else {
                    Text("—", color = SelahColors.TextMuted, fontSize = 28.sp)
                }

                Text("측정 신뢰도", color = SelahColors.TextMuted, fontSize = 11.sp)
                // **고정이다.** coherence 가 없으므로 신뢰도를 말할 수 없다.
                Text("○ 측정 중", color = SelahColors.TextMuted, fontSize = 14.sp)
            }

            Column(Modifier.weight(1f)) {
                InfoBar(phaseNoticeKo(state.phase))
            }
        }
    }
}

internal fun phaseNoticeKo(phase: TransferPhase): String = when (phase) {
    TransferPhase.NotMeasuring -> "측정을 시작하면 잽니다. 「측정」 화면에서 측정 시작을 누르십시오."
    TransferPhase.NoSignal -> "테스트 신호가 나가고 있지 않습니다. 「도구」에서 신호를 트십시오."
    TransferPhase.NotAudible -> "스피커에서 소리가 들어오지 않습니다. 출력과 볼륨을 확인하십시오."
    TransferPhase.Searching -> "재는 중입니다."
    TransferPhase.Found -> "Magnitude 는 다음 판에 들어옵니다. 지금은 지연만 잽니다."
    TransferPhase.NotFound -> "지연을 찾지 못했습니다. 신호가 너무 작거나 반사가 심할 수 있습니다."
}
```

`AnalyzeScreens.kt` 에 문을 더한다. **차트 밖**, 화면 맨 아래 한 줄이다.

```kotlin
/**
 * 분석에서 Transfer Function 으로 들어가는 문.
 *
 * **차트 고르개에 넣지 않는다.** 그 넷은 「지금 들어오는 소리를 어떻게
 * 그릴까」이고, 이것은 **기준과 견주어 시스템을 재는** 다른 일이다.
 * 게다가 그 고르개는 평범한 Row 라 긴 이름을 더하면 잘린다.
 */
@Composable
fun TransferDoor(onOpen: () -> Unit) {
    TextButton(onClick = onOpen) {
        Text("Transfer Function ▸", color = SelahColors.Accent, fontSize = 12.sp)
    }
}
```

`SelahApp.kt` — 교정 마법사와 **같은 꼴**로 연다(635줄 둘레를 본보기로).

```kotlin
var transferOpen by rememberSaveable { mutableStateOf(false) }

// 분석 구역을 그리는 자리에서:
if (transferOpen) {
    BackHandler { transferOpen = false }
    SimpleTransferScreen(state = transferState, onClose = { transferOpen = false })
} else {
    // 기존 분석 화면 + 맨 아래
    TransferDoor(onOpen = { transferOpen = true })
}
```

- [ ] **Step 4: 돌려서 통과를 확인한다**

```
adb shell am instrument -w -r -e class 'kr.joa.selahrta.ui.screens.TransferDoorTest' kr.joa.selahrta.test/androidx.test.runner.AndroidJUnitRunner
```
기대: 4개 통과.

- [ ] **Step 5: 표와 빌드를 확인한다**

```
.\gradlew :app:assembleDebug
.\gradlew :dsp:test :app:testDebugUnitTest
```
기대: 빌드 통과 · JVM 시험 전부 통과(회귀 없음).

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/kr/joa/selahrta/ui/screens/transfer/SimpleTransferScreen.kt app/src/main/java/kr/joa/selahrta/ui/screens/AnalyzeScreens.kt app/src/main/java/kr/joa/selahrta/ui/SelahApp.kt app/src/androidTest/java/kr/joa/selahrta/ui/screens/TransferDoorTest.kt
git commit -m "feat(전달함수): 분석에 문을 달고 간편 화면을 만든다

차트 고르개에 넣지 않는다. 그 넷은 「지금 들어오는 소리를 어떻게
그릴까」이고 이것은 기준과 견주어 시스템을 재는 다른 일이다. 게다가
그 고르개는 평범한 Row 라 긴 이름을 더하면 잘린다.

교정 마법사와 같은 꼴로 연다 — rememberSaveable + BackHandler + 닫기.
네비게이션과 아이콘은 건드리지 않았다.

Magnitude 자리는 비워 둔다. 흐리게 그려 두지 않는다 — 화면이 하지
않는 일을 한다고 적는 것으로 이미 한 번 데었다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: 배선과 CHANGELOG — **마지막 커밋이다**

**Files:**
- Modify: `app/src/main/java/kr/joa/selahrta/ui/CaptureController.kt:729` 둘레 (측정 표본 넘기기)
- Modify: `app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt` (`TappedSink` 끼우기 · 재는 주기)
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: 앞의 모든 것
- Produces: 없음(배선)

- [ ] **Step 1: 측정 표본을 엔진에 넘긴다**

`CaptureController.kt` 의 블록 처리 자리(729줄 둘레, `session.rta.process(...)`
바로 뒤)에 한 줄을 더한다.

```kotlin
session.rta.process(block.samples, block.frames)
session.engine.process(block.samples, block.frames)
// **전달함수는 원시 표본을 그대로 받는다.** 가중·보정을 거치지 않는다 —
// 지연은 파형의 시간차이지 레벨 이야기가 아니다.
transferEngine?.offerMeasurement(block.samples, 0, block.frames)
```

- [ ] **Step 2: 기준 쪽에 덧씌우기를 끼운다**

`SignalPlayer` 를 만드는 자리에서 공장을 바꾼다. **`SignalPlayer` 자체는
고치지 않는다.**

```kotlin
SignalPlayer(
    openSink = {
        TappedSink(AudioTrackSink()) { buf, off, n ->
            transferEngine.offerReference(buf, off, n)
        }
    },
)
```

- [ ] **Step 3: 재는 주기를 건다**

1초에 한 번 `estimate()` 를 돌린다. **캡처 스레드에서 돌리지 않는다** —
65536점 FFT 를 거기서 돌리면 소리가 끊긴다.

```kotlin
// ViewModel 의 코루틴에서
while (isActive) {
    val r = withContext(Dispatchers.Default) { transferEngine.estimate() }
    _transferState.value = TransferUiState(
        phase = transferPhase(measuring, transferEngine.referenceCount, lastPeak, r, ANALYSIS_SIZE.toLong()),
        delayMs = if (r.found) transferEngine.delayMs(r.samples) else null,
        sharpness = if (r.found) r.sharpness else null,
    )
    delay(1_000)
}
```

- [ ] **Step 4: 빌드와 타입을 확인한다**

```
.\gradlew :app:assembleDebug
.\gradlew :dsp:test :app:testDebugUnitTest
```
기대: 전부 통과.

- [ ] **Step 5: 실기기에서 한 번 돌려 본다**

```
1. 폰에 USB-C 출력(스피커)을 꽂는다
2. 「측정」에서 측정 시작
3. 「도구」에서 핑크 노이즈를 튼다
4. 「분석」 → Transfer Function ▸
5. 지연 숫자와 또렷함이 나오는지 본다
6. 소리를 끄면 「소리가 들어오지 않습니다」로 바뀌는지 본다
```

**나온 값을 적어 둔다** — 또렷함 문턱을 실측으로 정할 근거다.

- [ ] **Step 6: CHANGELOG 를 쓰고 커밋한다**

`CHANGELOG.md` 의 맨 위 날짜 묶음에 더한다.

```markdown
### 2026-10-XX

**Transfer Function 1차 — 분석에 문을 달고 지연을 잰다**

기준 신호와 마이크 신호의 시간차를 FFT 상호상관으로 찾는다. Main/Delay
정렬에 바로 쓸 수 있다.

- **기준은 실제로 나간 표본이다.** 같은 식으로 다시 그린 파형이 아니다 —
  볼륨·부분 쓰기를 지나지 않아 실제와 다르다. `SignalPlayer` 가 출력
  장치를 공장으로 받으므로 덧씌우기 하나로 끝냈다. `SignalPlayer` 도
  `AudioTrackSink` 도 고치지 않았다.
- **조용히 틀리기 쉬운 자리 둘을 막았다.** `write()` 는 일부만 쓸 수 있어
  넘긴 버퍼 전체를 적으면 기준이 앞서 가고 지연이 작게 나온다. 그리고
  세는 단위가 프레임이 아니라 칸이라 홀수에서 끊기면 프레임 경계가
  어긋난다. 둘 다 변이로 확인했다.
- **못 찾는 것을 숫자로 내놓지 않는다.** 조용한 방에서는 잡음끼리도
  어딘가에서 가장 큰 값이 나온다. 또렷함이 문턱 아래면 「찾지
  못했습니다」라고 적는다.
- **네비게이션을 건드리지 않았다.** 교정 마법사와 같은 꼴로 분석 위를
  덮는다. 탭·아이콘·설정 화면 모두 그대로다.
- **Magnitude·Coherence·Phase 는 아직이다.** 전달함수의 평균 영역 정의가
  독립 검토 중이다. 답을 받기 전에 만들면 그 위에 화면과 저장 포맷이
  올라간다.

**아직 아닌 것**: 클럭 드리프트를 한 번도 재지 않았다. 또렷함 문턱은
실측으로 정한 값이 아니다.
```

```bash
git add CHANGELOG.md app/src/main/java/kr/joa/selahrta/ui/CaptureController.kt app/src/main/java/kr/joa/selahrta/ui/CaptureViewModel.kt
git commit -m "docs: CHANGELOG — Transfer Function 1차(문과 지연 찾기)

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## 자기 점검 (계획을 쓴 뒤 스펙과 맞대어 본 것)

**스펙 덮기**

| 스펙 | 어느 작업 |
|---|---|
| 1장 문과 간편 화면 | Task 6 |
| 1장 진입 화면 안 만듦 | Task 6 (문 → 간편 직행) |
| 2장 안 만드는 것 | 전역 제약 · Task 6 의 문구 |
| 3장 덧씌우기 · `wrote` 만 적기 | Task 4 |
| 3장 인터리브 → 모노 | Task 4 |
| 4장 상호상관 · 탐색 범위 · 또렷함 | Task 2 |
| 5장 상태 여섯 | Task 5 · Task 6 |
| 5장 신뢰도 「측정 중」 고정 | Task 5 · Task 6 |
| 6장 시험 목록 | Task 1·2·4 (변이 둘 포함) |
| 7장 파일 목록 | 위 「파일 구조」 |

**빠진 것을 찾아 채웠다**: 스펙 3장이 「두 창을 같은 순간에」를 말하지
않았는데, 고리 둘을 따로 뜨면 그 사이 시간차가 지연에 섞인다. Task 3 을
더해 **잠금 하나**로 묶었다.

**이름 일관성**: `offerReference`/`offerMeasurement`/`estimate`/`delayMs` 가
Task 3·5·7 에서 같은 이름으로 쓰인다. `DelayResult(samples, sharpness, found)`
가 Task 2·3·5 에서 같다. `TransferPhase` 여섯 값이 Task 5·6 에서 같다.
