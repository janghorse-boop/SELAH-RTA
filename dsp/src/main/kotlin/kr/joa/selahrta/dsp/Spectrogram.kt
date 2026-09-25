package kr.joa.selahrta.dsp

/**
 * 스펙트로그램에 쌓인 장들의 **시각**을 담는 고리.
 *
 * ## 왜 시각만 담는가
 *
 * 레벨 값 자체는 그림(비트맵)이 이미 들고 있다. 값을 한 벌 더 들면
 * 720장 × 256칸 × 4바이트 = 737KB 인데, **읽는 곳이 없다.** 나중에 색
 * 눈금을 바꿔 다시 칠할 일이 생기면 그때 되살리면 된다.
 *
 * 시각은 다르다. 가로축에 「−30초」라고 적으려면 화면에 든 장들이 실제로
 * 언제 들어왔는지 알아야 하는데, 장이 고르게 들어온다는 보장이 없다 —
 * 화면 갱신 간격이 FFT 간격과 다르고 기기가 바쁘면 건너뛴다. 칸 수로
 * 나눠 셈하면 바쁜 구간에서 어긋난다.
 */
class SpectrogramTimeline(
    /** 담아 둘 장 수. */
    val capacity: Int,
) {
    init {
        require(capacity > 0) { "용량이 0 이하다: $capacity" }
    }

    private val times = LongArray(capacity)

    /** 다음에 쓸 자리. 그림도 같은 자리에 쓴다. */
    var head: Int = 0
        private set

    /** 지금 담겨 있는 장 수. [capacity] 를 넘지 않는다. */
    var size: Int = 0
        private set

    fun push(atMs: Long) {
        times[head] = atMs
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** [age] 0 이 가장 최근. */
    fun timeAt(age: Int): Long {
        require(age in 0 until size) { "age=$age 가 범위를 벗어난다(size=$size)" }
        return times[(head - 1 - age + capacity * 2) % capacity]
    }

    /** 화면에 든 시간 폭(ms). 두 장이 안 되면 0. */
    val spanMs: Long get() = if (size < 2) 0L else timeAt(0) - timeAt(size - 1)

    fun clear() {
        head = 0
        size = 0
    }
}

/**
 * 레벨(dB)을 색으로 옮긴다. `0xAARRGGBB`.
 *
 * ## 색 지도는 장식이 아니다
 *
 * 이 그림에서 색은 **유일한 숫자**다. 높이도 자리도 이미 시간과 주파수가
 * 쓰고 있으므로, 「얼마나 큰가」를 말할 자리가 색밖에 없다. 거기서 두 가지가
 * 따라온다 — **레벨마다 색이 달라야 하고**(같은 색이 두 레벨을 뜻하면 읽은
 * 것을 되돌릴 수 없다), **붉은 기운은 자라기만 해야 한다**(사람은 이 그림에서
 * 빨간 자리를 먼저 찾으므로, 되돌아가면 더 큰 소리가 덜 눈에 띈다).
 * [SpectrogramTest] 가 101곳에서 둘 다 확인한다.
 *
 * ## 왜 이 색인가
 *
 * 음향 계측 도구가 오래 써 온 「jet」 계열이다(담당자가 보여 준
 * PsySound3 그림과 같은 계열). 파랑이 조용함, 빨강이 큼 — 처음 보는
 * 사람도 설명 없이 읽는다.
 *
 * **[floorDb] 아래와 [ceilDb] 위는 양 끝 색으로 막는다.** 자르지 않으면
 * 색이 되돌아 감겨, 아주 큰 소리가 조용한 색으로 나온다.
 */
fun spectrogramColor(db: Double, floorDb: Double, ceilDb: Double): Int {
    require(ceilDb > floorDb) { "색 범위가 뒤집혔다: $floorDb ~ $ceilDb" }
    val t = ((db - floorDb) / (ceilDb - floorDb)).coerceIn(0.0, 1.0)

    // 마디를 찾아 그 사이를 곧게 잇는다.
    var i = 0
    while (i < JET_STOPS.size - 2 && t > JET_AT[i + 1]) i++
    val span = JET_AT[i + 1] - JET_AT[i]
    val f = if (span <= 0.0) 0.0 else (t - JET_AT[i]) / span

    val a = JET_STOPS[i]
    val b = JET_STOPS[i + 1]
    val r = lerp8(a shr 16, b shr 16, f)
    val g = lerp8(a shr 8, b shr 8, f)
    val bl = lerp8(a, b, f)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

private fun lerp8(a: Int, b: Int, f: Double): Int {
    val x = a and 0xFF
    val y = b and 0xFF
    return (x + (y - x) * f).toInt().coerceIn(0, 255)
}

/** 색 마디가 놓이는 자리(0..1). */
private val JET_AT = doubleArrayOf(0.0, 0.15, 0.40, 0.65, 1.0)

/**
 * 색 마디. 짙은 남색 → 파랑 → 청록 → 노랑 → 빨강.
 *
 * **원래 jet 의 꼬리(검붉은색)를 뺐다.** 그대로 두면 가장 큰 자리가
 * 오히려 어두워져, 빨강보다 더 큰 소리가 **덜 눈에 띈다** — 가장 급한
 * 것을 가장 잘 안 보이게 만드는 셈이다. 빨강에서 끝내면 붉은 기운이
 * 끝까지 자라기만 한다([SpectrogramTest] 가 101곳에서 확인한다).
 */
private val JET_STOPS = intArrayOf(
    0x000080, 0x0000FF, 0x00FFFF, 0xFFFF00, 0xFF0000,
)

/**
 * 색 눈금의 바닥·천장(dB).
 *
 * **자동으로 맞추지 않는다.** 스펙트로그램은 지나간 장을 그대로 들고
 * 있는 그림이라, 범위가 움직이면 **이미 칠한 색의 뜻이 바뀐다** — 30초
 * 전의 노랑과 지금의 노랑이 다른 레벨이 되어, 「그때보다 커졌는가」를
 * 물을 수 없게 된다. 색 눈금 막대가 이 값을 그대로 적는다.
 *
 * 미보정 상태에서도 값은 SPL 이므로(짐작한 만재 음압을 더한다) 예배당에서
 * 나오는 범위를 넉넉히 덮는다.
 */
const val SPECTROGRAM_FLOOR_DB: Double = 10.0
const val SPECTROGRAM_CEIL_DB: Double = 90.0
