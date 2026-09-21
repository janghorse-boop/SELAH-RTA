package kr.joa.selahrta.instrument

import kr.joa.selahrta.data.instrument.InstrumentCatalog
import kr.joa.selahrta.domain.instrument.DbRange
import kr.joa.selahrta.domain.instrument.HzRange
import kr.joa.selahrta.domain.instrument.MsRange
import kr.joa.selahrta.domain.instrument.RatioRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **명세 §4 의 경계를 전수 대조한다**(독립 검증 EQB01·EQB02).
 *
 * 앞선 시험은 「범위가 유효한가」와 몇몇 표본만 보았고, 그래서
 * **성격과 증상의 경계가 다른데 하나로 합친 일곱 곳을 놓쳤다.**
 * 검증자가 그것을 찾았다(예: 스네어 어택 2~5k 인데 날카로움은 3~6k).
 *
 * 그리고 검증자가 원본을 건드리지 않은 복사본에서 패드 HPF 를
 * `100~200 → 110~210`, 공통 Hysteresis 를 `2~6 → 20~60` 으로 바꿔도
 * **기존 15건이 모두 통과**하는 것을 보였다. 그 빈틈을 여기서 닫는다.
 *
 * 아래 표는 **명세 §4·§18 을 손으로 다시 옮겨 적은 것**이다. 구현에서
 * 만들지 않는다(명세 §12.1).
 */
class CatalogBoundaryTest {

    /** `(low, high)` — 명세가 적은 Hz. */
    private infix fun Int.to2(h: Int) = this.toDouble() to h.toDouble()

    /**
     * 모든 영역의 경계. `stableId` → (`regionId` → 대역).
     *
     * 성격과 증상이 **다른 대역**이면 명세가 표의 다른 칸에 적은 것이므로
     * 영역도 따로 있어야 한다.
     */
    private val expected: Map<String, Map<String, Pair<Double, Double>>> = mapOf(
        // ---------------- §4.1 신디사이저 ----------------
        // 피아노/EP  주요 60~200 바디 · 200~500 온기 · 1~3k 명료도 · 2~5k 어택 · 8~12k 개방감
        //            문제 150~500 먹먹함 · 500~1k 상자 느낌 · 2~5k 딱딱함
        "synth.piano_ep" to mapOf(
            "body" to (60 to2 200), "warmth" to (200 to2 500), "clarity" to (1000 to2 3000),
            "attack" to (2000 to2 5000), "air" to (8000 to2 12000),
            "muddy" to (150 to2 500), "boxy" to (500 to2 1000),
        ),
        // 패드/스트링 주요 150~500 두께 · 1~3k 존재감 · 6~12k 밝기/공기감
        //             문제 150~400 저중역 누적 · 1~4k 보컬 충돌 · 6~10k 거친 질감
        "synth.pad_strings" to mapOf(
            "thickness" to (150 to2 500), "presence" to (1000 to2 3000), "brightness" to (6000 to2 12000),
            "lowmid_pileup" to (150 to2 400), "vocal_clash" to (1000 to2 4000), "harsh" to (6000 to2 10000),
        ),
        // 리드/브라스 주요 150~600 바디 · 700~2k 음형 · 2~5k 어택/존재감 · 8~12k 밝은 배음
        //             문제 300~800 답답함/콧소리 · **2~6k 날카로움**  ← 어택과 경계가 다르다
        "synth.lead_brass" to mapOf(
            "body" to (150 to2 600), "shape" to (700 to2 2000), "attack" to (2000 to2 5000),
            "bright_harmonics" to (8000 to2 12000),
            "nasal" to (300 to2 800), "harsh" to (2000 to2 6000),
        ),
        // 신스 베이스 주요 25~80 서브 · 60~150 무게/펀치 · 150~350 바디 · 700~2k 음정 · 2~5k 질감
        //             문제 40~120 붕붕거림 · 150~400 먹먹함
        "synth.bass" to mapOf(
            "sub" to (25 to2 80), "weight" to (60 to2 150), "body" to (150 to2 350),
            "pitch" to (700 to2 2000), "texture" to (2000 to2 5000),
            "boom" to (40 to2 120), "muddy" to (150 to2 400),
        ),
        // 기타/복합 — 명세가 「패치에 따라 전대역, 범용 정상 곡선 없음」만 적는다.
        "synth.other" to mapOf("full" to (20 to2 20000)),

        // ---------------- §4.2 어쿠스틱 기타 ----------------
        // 한 칸짜리 표다. 성격과 증상이 같은 대역을 쓴다.
        "acoustic_guitar.strum" to acousticGuitarBands(),
        "acoustic_guitar.fingerstyle" to acousticGuitarBands(),

        // ---------------- §4.3 일렉기타 ----------------
        "electric_guitar.clean" to electricGuitarBands(),
        "electric_guitar.crunch" to electricGuitarBands(),
        "electric_guitar.high_gain" to electricGuitarBands(),

        // ---------------- §4.4 베이스기타 ----------------
        "bass_guitar.finger" to bassBands(),
        "bass_guitar.pick" to bassBands(),
        "bass_guitar.slap" to bassBands(),

        // ---------------- §4.5 전자드럼 ----------------
        // 전체 키트 주요 50~120 · 150~300 · 2~5k · 8~14k | 문제 200~500 뭉침 · 3~8k 자극
        "edrums.full_kit" to mapOf(
            "weight" to (50 to2 120), "body" to (150 to2 300), "attack" to (2000 to2 5000),
            "air" to (8000 to2 14000), "clutter" to (200 to2 500), "harsh" to (3000 to2 8000),
        ),
        // 킥 주요 35~90 · 90~180 · 2~5k | 문제 180~450 (지나친 클릭은 대역이 없다)
        "edrums.kick" to mapOf(
            "foundation" to (35 to2 90), "body" to (90 to2 180), "click" to (2000 to2 5000),
            "boxy" to (180 to2 450),
        ),
        // 스네어 주요 120~250 · 1~3k · 2~5k · 6~12k | 문제 300~800 · **3~6k 날카로움**
        "edrums.snare" to mapOf(
            "body" to (120 to2 250), "clarity" to (1000 to2 3000), "attack" to (2000 to2 5000),
            "snap" to (6000 to2 12000), "boxy" to (300 to2 800), "harsh" to (3000 to2 6000),
        ),
        // 탐 주요 50~200 · 2~5k | 문제 200~600
        "edrums.tom" to mapOf(
            "pitch_body" to (50 to2 200), "attack" to (2000 to2 5000), "clutter" to (200 to2 600),
        ),
        // 하이햇 주요 3~8k 리듬/어택 · 8~14k 밝기 | 문제 **3~7k 거침**
        "edrums.hihat" to mapOf(
            "rhythm" to (3000 to2 8000), "bright" to (8000 to2 14000), "harsh" to (3000 to2 7000),
        ),
        // 심벌 주요 300~1k · 3~8k · 8~16k | 문제 **3~7k 자극**, 긴 잔향 누적
        "edrums.cymbal" to mapOf(
            "metal_body" to (300 to2 1000), "attack" to (3000 to2 8000), "air" to (8000 to2 16000),
            "harsh" to (3000 to2 7000),
        ),

        // ---------------- §4.6 리얼드럼 ----------------
        "drums.full_kit" to mapOf("full" to (30 to2 16000)),
        // 킥 주요 45~100 · 100~200 · 2~5k | 문제 200~500
        "drums.kick" to mapOf(
            "weight" to (45 to2 100), "body" to (100 to2 200), "attack" to (2000 to2 5000),
            "boxy" to (200 to2 500),
        ),
        // 스네어 주요 120~250 · 1~3k · 2~5k · 6~12k | 문제 300~900 · **3~6k 자극**
        "drums.snare" to mapOf(
            "body" to (120 to2 250), "clarity" to (1000 to2 3000), "attack" to (2000 to2 5000),
            "wires" to (6000 to2 12000), "ring" to (300 to2 900), "harsh" to (3000 to2 6000),
        ),
        // 랙탐 주요 80~180 · 2~5k | 문제 250~600
        "drums.rack_tom" to mapOf(
            "body" to (80 to2 180), "stick" to (2000 to2 5000), "clutter" to (250 to2 600),
        ),
        // 플로어탐 주요 50~120 · 2~4k | 문제 150~400
        "drums.floor_tom" to mapOf(
            "body" to (50 to2 120), "attack" to (2000 to2 4000), "boom" to (150 to2 400),
        ),
        // 하이햇 주요 3~8k 리듬 · 8~14k 밝기 | 문제 **3~7k 날카로움**
        "drums.hihat" to mapOf(
            "rhythm" to (3000 to2 8000), "bright" to (8000 to2 14000), "harsh" to (3000 to2 7000),
        ),
        // 오버헤드/심벌 주요 200~800 · 3~8k · 8~16k | 문제 **3~7k 자극**, 다른 파트 누음
        "drums.overhead" to mapOf(
            "kit_body" to (200 to2 800), "stick" to (3000 to2 8000), "air" to (8000 to2 16000),
            "harsh" to (3000 to2 7000),
        ),
        // 룸 — 전대역. 「저중역 잔향」에는 명세가 숫자를 주지 않는다.
        "drums.room" to mapOf("space" to (30 to2 16000)),
    )

    private fun acousticGuitarBands() = mapOf(
        "low_string" to (80 to2 150), "fullness" to (150 to2 350), "box" to (350 to2 800),
        "clarity" to (1000 to2 2500), "pick_attack" to (2000 to2 5000),
        "string_noise" to (6000 to2 10000), "air" to (10000 to2 14000),
    )

    private fun electricGuitarBands() = mapOf(
        "low_string" to (80 to2 180), "body" to (150 to2 400), "midrange" to (400 to2 900),
        "clarity" to (800 to2 2000), "pick_attack" to (2000 to2 4000),
        "fizz" to (4000 to2 8000), "openness" to (8000 to2 12000),
    )

    private fun bassBands() = mapOf(
        "lowest" to (30 to2 60), "weight" to (60 to2 120), "body" to (120 to2 250),
        "lowmid" to (250 to2 500), "pitch" to (700 to2 1500), "attack" to (2000 to2 5000),
        "slap_bright" to (5000 to2 8000),
    )

    /** **표에서 빠진 프로필·영역이 없어야 한다.** 새 프로필이 검사를 건너뛰지 못하게. */
    @Test
    fun `기대표가 카탈로그를 빠짐없이 덮는다`() {
        assertEquals(
            "프로필 집합이 다르다",
            InstrumentCatalog.profiles.map { it.stableId }.toSet(),
            expected.keys,
        )
        InstrumentCatalog.profiles.forEach { p ->
            assertEquals(
                "${p.stableId}: 영역 집합이 다르다",
                p.regions.map { it.id }.toSet(),
                expected.getValue(p.stableId).keys,
            )
        }
    }

    @Test
    fun `모든 영역 경계가 명세와 같다`() {
        InstrumentCatalog.profiles.forEach { p ->
            val want = expected.getValue(p.stableId)
            p.regions.forEach { r ->
                assertEquals(
                    "${p.stableId}/${r.id}",
                    want.getValue(r.id),
                    r.range.lowHz to r.range.highHz,
                )
            }
        }
    }

    /**
     * **성격과 증상의 경계가 다른 곳은 영역이 따로 있어야 한다**(EQB01).
     *
     * 검증자가 찾은 일곱 곳을 이름으로 못박는다. 하나로 합치면 증상
     * 카드의 숫자가 성격의 숫자로 바뀐다.
     */
    @Test
    fun `경계가 다른 성격과 증상은 따로 있다`() {
        val split = listOf(
            // (프로필, 성격 영역, 성격 대역, 증상 영역, 증상 대역)
            Quint("synth.lead_brass", "attack", 2000 to2 5000, "harsh", 2000 to2 6000),
            Quint("edrums.snare", "attack", 2000 to2 5000, "harsh", 3000 to2 6000),
            Quint("drums.snare", "attack", 2000 to2 5000, "harsh", 3000 to2 6000),
            Quint("edrums.hihat", "rhythm", 3000 to2 8000, "harsh", 3000 to2 7000),
            Quint("drums.hihat", "rhythm", 3000 to2 8000, "harsh", 3000 to2 7000),
            Quint("edrums.cymbal", "attack", 3000 to2 8000, "harsh", 3000 to2 7000),
            Quint("drums.overhead", "stick", 3000 to2 8000, "harsh", 3000 to2 7000),
        )
        split.forEach { (id, charId, charBand, sympId, sympBand) ->
            val p = InstrumentCatalog.find(id)!!
            val c = p.regions.single { it.id == charId }
            val s = p.regions.single { it.id == sympId }
            assertEquals("$id/$charId 대역", charBand, c.range.lowHz to c.range.highHz)
            assertEquals("$id/$sympId 대역", sympBand, s.range.lowHz to s.range.highHz)
            assertTrue("$id/$charId 는 성격 전용이어야 한다", c.symptomTags.isEmpty())
            assertTrue("$id/$charId 에 성격이 있어야 한다", c.characterTags.isNotEmpty())
            assertTrue("$id/$sympId 는 증상 전용이어야 한다", s.characterTags.isEmpty())
            assertTrue("$id/$sympId 에 증상이 있어야 한다", s.symptomTags.isNotEmpty())
        }
    }

    /** 「증상만」이 고르는 것이 **증상용 대역**인지. 화면의 거르기와 같은 식이다. */
    @Test
    fun `증상만 고르면 증상용 대역이 나온다`() {
        val want = mapOf(
            "edrums.snare" to setOf(300 to2 800, 3000 to2 6000),
            "drums.hihat" to setOf(3000 to2 7000),
            "edrums.cymbal" to setOf(3000 to2 7000),
        )
        want.forEach { (id, bands) ->
            val got = InstrumentCatalog.find(id)!!.regions
                .filter { it.symptomTags.isNotEmpty() }
                .map { it.range.lowHz to it.range.highHz }
                .toSet()
            assertEquals(id, bands, got)
        }
    }

    // ------------------------------------------------------------------
    // EQB02 — 시험이 덮지 않던 곳
    // ------------------------------------------------------------------

    /** 모든 필터: bypass 기본값·시작점·대체 범위를 전수 대조한다. */
    @Test
    fun `모든 필터 시작점이 명세와 같다`() {
        data class F(val bypass: Boolean, val range: Pair<Double, Double>?, val alt: Pair<Double, Double>? = null)

        val hpf = mapOf(
            "synth.piano_ep" to F(false, 30 to2 60, 70 to2 120),
            "synth.pad_strings" to F(false, 100 to2 200),
            "synth.lead_brass" to F(false, 70 to2 150),
            "synth.bass" to F(true, 20 to2 30),
            "synth.other" to F(true, null),
            "acoustic_guitar.strum" to F(false, 60 to2 80, 80 to2 120),
            "acoustic_guitar.fingerstyle" to F(false, 60 to2 80, 80 to2 120),
            "electric_guitar.clean" to F(false, 60 to2 90, 80 to2 120),
            "electric_guitar.crunch" to F(false, 60 to2 90, 80 to2 120),
            "electric_guitar.high_gain" to F(false, 60 to2 90, 80 to2 120),
            "bass_guitar.finger" to F(false, 25 to2 35, 20 to2 30),
            "bass_guitar.pick" to F(false, 25 to2 35, 20 to2 30),
            "bass_guitar.slap" to F(false, 25 to2 35, 20 to2 30),
            "edrums.full_kit" to F(true, 20 to2 30),
            "edrums.kick" to F(false, 20 to2 35),
            "edrums.snare" to F(false, 70 to2 100),
            "edrums.tom" to F(false, 30 to2 50, 40 to2 70),
            "edrums.hihat" to F(false, 150 to2 300),
            "edrums.cymbal" to F(false, 100 to2 250),
            "drums.full_kit" to F(true, null),
            "drums.kick" to F(false, 25 to2 40),
            "drums.snare" to F(false, 60 to2 100),
            "drums.rack_tom" to F(false, 40 to2 60),
            "drums.floor_tom" to F(false, 25 to2 40),
            "drums.hihat" to F(false, 150 to2 300),
            "drums.overhead" to F(false, 60 to2 100, 150 to2 250),
            "drums.room" to F(false, 30 to2 60),
        )
        val lpf = mapOf(
            "synth.piano_ep" to F(true, 12000 to2 16000),
            "synth.pad_strings" to F(true, 8000 to2 14000),
            "synth.lead_brass" to F(true, 8000 to2 12000),
            "synth.bass" to F(true, 3000 to2 8000),
            "synth.other" to F(true, null),
            "acoustic_guitar.strum" to F(true, 10000 to2 14000),
            "acoustic_guitar.fingerstyle" to F(true, 10000 to2 14000),
            "electric_guitar.clean" to F(true, 10000 to2 14000),
            // 크런치는 명세에 수치가 없다. **임의로 채우지 않았다.**
            "electric_guitar.crunch" to F(true, null),
            "electric_guitar.high_gain" to F(false, 6000 to2 10000),
            "bass_guitar.finger" to F(true, 4000 to2 8000),
            "bass_guitar.pick" to F(true, 8000 to2 12000),
            "bass_guitar.slap" to F(true, 8000 to2 12000),
            "edrums.full_kit" to F(true, null),
            "edrums.kick" to F(true, 6000 to2 10000),
            "edrums.snare" to F(true, 10000 to2 14000),
            "edrums.tom" to F(true, 8000 to2 12000),
            "edrums.hihat" to F(true, 12000 to2 16000),
            "edrums.cymbal" to F(true, 12000 to2 16000),
            "drums.full_kit" to F(true, null),
            "drums.kick" to F(true, 8000 to2 12000),
            "drums.snare" to F(true, 12000 to2 16000),
            "drums.rack_tom" to F(true, 8000 to2 12000),
            "drums.floor_tom" to F(true, 8000 to2 12000),
            "drums.hihat" to F(true, 12000 to2 16000),
            "drums.overhead" to F(true, null),
            "drums.room" to F(true, null),
        )

        assertEquals("HPF 표가 프로필을 다 덮지 않는다", expected.keys, hpf.keys)
        assertEquals("LPF 표가 프로필을 다 덮지 않는다", expected.keys, lpf.keys)

        InstrumentCatalog.profiles.forEach { p ->
            val h = hpf.getValue(p.stableId)
            assertEquals("${p.stableId} HPF bypass", h.bypass, p.hpf.bypassDefault)
            assertEquals("${p.stableId} HPF 범위", h.range, p.hpf.rangeHz?.let { it.lowHz to it.highHz })
            assertEquals("${p.stableId} HPF 대체", h.alt, p.hpf.altRangeHz?.let { it.lowHz to it.highHz })
            val l = lpf.getValue(p.stableId)
            assertEquals("${p.stableId} LPF bypass", l.bypass, p.lpf.bypassDefault)
            assertEquals("${p.stableId} LPF 범위", l.range, p.lpf.rangeHz?.let { it.lowHz to it.highHz })
            assertEquals("${p.stableId} LPF 대체", l.alt, p.lpf.altRangeHz?.let { it.lowHz to it.highHz })
        }
    }

    /** Hysteresis 는 §18.3 이 2~6 dB 라고 적는다. 숫자가 없는 카드에는 없어야 한다. */
    @Test
    fun `Hysteresis 가 명세와 같다`() {
        InstrumentCatalog.profiles.forEach { p ->
            val g = p.gate.gate!!
            if (g.attenuationDb == null) {
                assertNull("${p.stableId}: 숫자 없는 카드에 Hysteresis 가 있다", g.hysteresisDb)
            } else {
                assertEquals(
                    "${p.stableId} Hysteresis",
                    2.0 to 6.0,
                    g.hysteresisDb?.let { it.minDb to it.maxDb },
                )
            }
        }
    }

    /** 잘못된 값은 **만들어지지 않는다.** 자료형이 진짜로 막는지 본다. */
    @Test
    fun `말이 안 되는 값은 자료형이 거절한다`() {
        assertNotNull(runCatching { HzRange(200.0, 100.0) }.exceptionOrNull())
        assertNotNull(runCatching { HzRange(0.0, 100.0) }.exceptionOrNull())
        assertNotNull(runCatching { HzRange(-10.0, 100.0) }.exceptionOrNull())
        assertNotNull(runCatching { HzRange(Double.NaN, 100.0) }.exceptionOrNull())
        assertNotNull(runCatching { HzRange(20.0, Double.POSITIVE_INFINITY) }.exceptionOrNull())
        assertNotNull(runCatching { MsRange(-1.0, 5.0) }.exceptionOrNull())
        assertNotNull(runCatching { MsRange(5.0, 1.0) }.exceptionOrNull())
        assertNotNull(runCatching { MsRange(Double.NaN, 1.0) }.exceptionOrNull())
        assertNotNull(runCatching { DbRange(-1.0, 5.0) }.exceptionOrNull())
        assertNotNull(runCatching { DbRange(10.0, 5.0) }.exceptionOrNull())
        assertNotNull(runCatching { RatioRange(0.5, 2.0) }.exceptionOrNull())
        assertNotNull(runCatching { RatioRange(4.0, 2.0) }.exceptionOrNull())
        assertNotNull(runCatching { RatioRange(1.0, Double.NaN) }.exceptionOrNull())
    }

    private data class Quint(
        val id: String, val charId: String, val charBand: Pair<Double, Double>,
        val sympId: String, val sympBand: Pair<Double, Double>,
    )
}
