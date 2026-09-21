package kr.joa.selahrta.instrument

import kr.joa.selahrta.data.instrument.InstrumentCatalog
import kr.joa.selahrta.data.instrument.Sources
import kr.joa.selahrta.domain.instrument.InstrumentType
import kr.joa.selahrta.domain.instrument.ProcessorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카탈로그 데이터 검증(명세 §12.1 T11, §18.7).
 *
 * **기대값을 구현에서 다시 만들지 않는다**(명세 §12.1). 아래 다이내믹스
 * 표는 명세 §18.3·§18.4 를 **손으로 다시 옮겨 적은 것**이고, 카탈로그와
 * 대조한다. 옮겨 적다 틀리면 여기서 잡힌다.
 */
class InstrumentCatalogTest {

    // ------------------------------------------------------------------
    // T11 — 6악기와 모든 세부유형
    // ------------------------------------------------------------------

    @Test
    fun `6종 악기가 모두 있다`() {
        val types = InstrumentCatalog.profiles.map { it.type }.distinct()
        assertEquals("명세 §2 가 6종을 요구한다", InstrumentType.entries.size, types.size)
        InstrumentType.entries.forEach {
            assertTrue("${it.nameKo} 가 없다", InstrumentCatalog.byType(it).isNotEmpty())
        }
    }

    @Test
    fun `세부유형과 드럼 파트가 명세대로 있다`() {
        val expected = mapOf(
            InstrumentType.SYNTH to listOf("piano_ep", "pad_strings", "lead_brass", "synth_bass", "other"),
            InstrumentType.ACOUSTIC_GUITAR to listOf("strum", "fingerstyle"),
            InstrumentType.ELECTRIC_GUITAR to listOf("clean", "crunch", "high_gain"),
            InstrumentType.BASS_GUITAR to listOf("finger", "pick", "slap"),
            InstrumentType.ELECTRONIC_DRUMS to listOf("full_kit", "kick", "snare", "tom", "hihat", "cymbal"),
            InstrumentType.ACOUSTIC_DRUMS to listOf(
                "full_kit", "kick", "snare", "rack_tom", "floor_tom", "hihat", "overhead", "room",
            ),
        )
        expected.forEach { (type, subtypes) ->
            assertEquals(
                "${type.nameKo} 의 세부유형",
                subtypes,
                InstrumentCatalog.byType(type).map { it.subtypeId },
            )
        }
        assertEquals("전체 개수", expected.values.sumOf { it.size }, InstrumentCatalog.profiles.size)
    }

    @Test
    fun `stableId 가 겹치지 않는다`() {
        val ids = InstrumentCatalog.profiles.map { it.stableId }
        assertEquals("겹치는 열쇠가 있다: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}", ids.size, ids.distinct().size)
        ids.forEach { assertNotNull(InstrumentCatalog.find(it)) }
    }

    /**
     * 범위의 앞뒤·유한성은 자료형의 생성자가 막는다. 카탈로그를 한 번
     * 읽어 내는 것 자체가 그 검사다 — 여기서는 **실제로 읽혔는지**와
     * 빈 곳이 없는지를 본다(명세 §18.7 2번).
     */
    @Test
    fun `모든 영역이 값과 문구를 갖는다`() {
        InstrumentCatalog.profiles.forEach { p ->
            assertTrue("${p.stableId}: 영역이 없다", p.regions.isNotEmpty())
            p.regions.forEach { r ->
                assertTrue("${p.stableId}/${r.id}: 아래끝이 위끝보다 크다", r.range.lowHz < r.range.highHz)
                assertTrue("${p.stableId}/${r.id}: 20Hz 아래다", r.range.lowHz >= 20.0)
                assertTrue("${p.stableId}/${r.id}: 20kHz 위다", r.range.highHz <= 20_000.0)
                assertTrue("${p.stableId}/${r.id}: 주의사항이 비었다", r.cautionKo.isNotBlank())
                assertTrue("${p.stableId}/${r.id}: 들어볼 조건이 비었다", r.listenConditionKo.isNotBlank())
            }
            assertTrue("${p.stableId}: 출처가 없다", p.sourceIds.isNotEmpty())
            p.sourceIds.forEach {
                assertNotNull("${p.stableId}: 모르는 출처 $it", Sources.titles[it])
            }
        }
    }

    /** 필터 안내에는 **조건과 부작용이 반드시** 있다(명세 §15). */
    @Test
    fun `모든 필터 안내에 조건과 부작용이 있다`() {
        InstrumentCatalog.profiles.forEach { p ->
            listOf(p.hpf, p.lpf).forEach { f ->
                assertTrue("${p.stableId}/${f.filterType}: 조건이 비었다", f.conditionKo.isNotBlank())
                assertTrue("${p.stableId}/${f.filterType}: 부작용이 비었다", f.cautionKo.isNotBlank())
                assertTrue("${p.stableId}/${f.filterType}: 12dB/oct 가 없다", 12 in f.slopeOptionsDbOct)
            }
        }
    }

    // ------------------------------------------------------------------
    // §18.7 — 게이트·컴프레서 안내
    // ------------------------------------------------------------------

    @Test
    fun `6종 전부에 게이트와 컴프레서 안내가 있다`() {
        InstrumentCatalog.profiles.forEach { p ->
            assertNotNull("${p.stableId}: 게이트 안내가 없다", p.gate)
            assertNotNull("${p.stableId}: 컴프레서 안내가 없다", p.compressor)
            assertEquals(ProcessorType.COMPRESSOR, p.compressor.processorType)
        }
    }

    /** 쓰지 말라고 하는 자리는 **bypass 가 분명해야** 한다(§18.7 3번). */
    @Test
    fun `쓰지 않는 곳은 bypass 가 분명하다`() {
        val noNumbers = listOf(
            "synth.pad_strings", "edrums.full_kit", "drums.full_kit",
            "edrums.hihat", "edrums.cymbal", "drums.hihat", "drums.overhead", "drums.room",
        )
        noNumbers.forEach { id ->
            val p = InstrumentCatalog.find(id)!!
            assertTrue("$id: 게이트가 bypass 기본이어야 한다", p.gate.bypassDefault)
            assertFalse(
                "$id: 게이트 숫자 프리셋이 없어야 한다",
                p.gate.gate!!.hasNumbers,
            )
        }
    }

    /**
     * **Threshold 숫자를 주지 않는다.** 외부 믹서에서 맞추는 방법만 적는다(§18.7 4번).
     *
     * 자료형에 threshold **값** 자리가 아예 없다는 것이 진짜 보증이다
     * (`GateGuidance`·`CompressorGuidance` 에는 `thresholdProcedureKo` 뿐이다).
     * 여기서는 그 절차 문구가 **무엇을 말하는지**를 본다.
     *
     * 처음에는 「`dBFS 를 threshold` 라는 말이 없어야 한다」로 적었는데,
     * 그러면 **옮겨 적지 말라는 경고 문구 자체**가 걸린다. 금지문과
     * 지시문을 문자열로 가를 수 없어 단언을 뜻에 맞게 바꾼다.
     */
    @Test
    fun `Threshold 는 외부 믹서에서 맞추는 방법으로만 적는다`() {
        InstrumentCatalog.profiles.forEach { p ->
            val g = p.gate.gate!!.thresholdProcedureKo
            val c = p.compressor.compressor!!.thresholdProcedureKo
            assertTrue("${p.stableId}/게이트: 외부 믹서를 말하지 않는다", g.contains("믹서"))
            assertTrue("${p.stableId}/컴프레서: 외부 믹서를 말하지 않는다", c.contains("믹서"))
            assertTrue(
                "${p.stableId}/게이트: 약한 연주와 잡음을 번갈아 듣는 절차가 없다",
                g.contains("번갈아"),
            )
            assertTrue(
                "${p.stableId}/컴프레서: 앱의 dBFS 를 옮겨 적지 말라는 금지가 없다",
                c.contains("dBFS 를 threshold 로 옮겨 적지 않습니다"),
            )
            assertTrue(
                "${p.stableId}/컴프레서: GR 미터를 보라는 말이 없다",
                c.contains("GR 미터"),
            )
        }
    }

    /** GR 은 **참고 범위**일 뿐 실측값이 아니라고 적혀야 한다(§18.7 5번). */
    @Test
    fun `GR 은 실측값이 아니라고 적는다`() {
        InstrumentCatalog.profiles.forEach { p ->
            assertTrue(
                "${p.stableId}: GR 이 실측값이 아니라는 말이 없다",
                p.compressor.cautionsKo.any { it.contains("실측값이 아닙니다") },
            )
        }
    }

    /** 게이트는 **발진 방지 장치가 아니다**(§18.3 마지막 문단). */
    @Test
    fun `게이트를 발진 방지 장치로 설명하지 않는다`() {
        InstrumentCatalog.profiles.forEach { p ->
            assertTrue(
                "${p.stableId}: 게이트의 한계를 적지 않았다",
                p.gate.cautionsKo.any { it.contains("발진 방지") },
            )
        }
    }

    // ------------------------------------------------------------------
    // 명세 §18.3·§18.4 표를 손으로 옮겨 적어 대조한다
    // ------------------------------------------------------------------

    /** `(attack, hold, release, 감쇠량)` — null 이면 숫자 프리셋 없음. */
    private data class GateRow(
        val a: Pair<Double, Double>?, val h: Pair<Double, Double>?,
        val r: Pair<Double, Double>?, val att: Pair<Double, Double>?,
    )

    @Test
    fun `게이트 수치가 명세 표와 같다`() {
        val expected = mapOf(
            // §18.3 — 신디 피아노/EP | 1~5 / 80~150 / 200~500 | 6~12 dB
            "synth.piano_ep" to GateRow(1.0 to 5.0, 80.0 to 150.0, 200.0 to 500.0, 6.0 to 12.0),
            // 신디 패드/스트링 | 숫자 프리셋 비활성
            "synth.pad_strings" to GateRow(null, null, null, null),
            // 신디 리드/베이스 | 1~5 / 50~100 / 150~300 | 6~12 dB
            "synth.lead_brass" to GateRow(1.0 to 5.0, 50.0 to 100.0, 150.0 to 300.0, 6.0 to 12.0),
            "synth.bass" to GateRow(1.0 to 5.0, 50.0 to 100.0, 150.0 to 300.0, 6.0 to 12.0),
            // 어쿠스틱 기타 | 1~5 / 80~150 / 200~400 | 6~12 dB
            "acoustic_guitar.strum" to GateRow(1.0 to 5.0, 80.0 to 150.0, 200.0 to 400.0, 6.0 to 12.0),
            "acoustic_guitar.fingerstyle" to GateRow(1.0 to 5.0, 80.0 to 150.0, 200.0 to 400.0, 6.0 to 12.0),
            // 일렉기타 클린 | 1~3 / 50~100 / 150~300 | 6~12 dB
            "electric_guitar.clean" to GateRow(1.0 to 3.0, 50.0 to 100.0, 150.0 to 300.0, 6.0 to 12.0),
            // 일렉기타 하이게인 | 0.5~2 / 30~80 / 80~180 | 12~24 dB
            "electric_guitar.high_gain" to GateRow(0.5 to 2.0, 30.0 to 80.0, 80.0 to 180.0, 12.0 to 24.0),
            // 베이스기타 | 1~5 / 80~150 / 150~350 | 6~12 dB
            "bass_guitar.finger" to GateRow(1.0 to 5.0, 80.0 to 150.0, 150.0 to 350.0, 6.0 to 12.0),
            "bass_guitar.pick" to GateRow(1.0 to 5.0, 80.0 to 150.0, 150.0 to 350.0, 6.0 to 12.0),
            "bass_guitar.slap" to GateRow(1.0 to 5.0, 80.0 to 150.0, 150.0 to 350.0, 6.0 to 12.0),
            // 전자드럼 전체 키트 | 숫자 비활성
            "edrums.full_kit" to GateRow(null, null, null, null),
            // 전자드럼 개별 킥/스네어/탐 | 6~12 dB 부터, attack/hold/release 는 리얼드럼 참고(숫자 없음)
            "edrums.kick" to GateRow(null, null, null, 6.0 to 12.0),
            "edrums.snare" to GateRow(null, null, null, 6.0 to 12.0),
            "edrums.tom" to GateRow(null, null, null, 6.0 to 12.0),
            // 리얼드럼 킥 | 0.5~2 / 40~80 / 80~180 | 10~20 dB
            "drums.kick" to GateRow(0.5 to 2.0, 40.0 to 80.0, 80.0 to 180.0, 10.0 to 20.0),
            // 리얼드럼 스네어 | 0.5~2 / 50~100 / 100~200 | 6~12 dB
            "drums.snare" to GateRow(0.5 to 2.0, 50.0 to 100.0, 100.0 to 200.0, 6.0 to 12.0),
            // 리얼드럼 랙탐 | 0.5~3 / 80~150 / 150~300 | 10~20 dB
            "drums.rack_tom" to GateRow(0.5 to 3.0, 80.0 to 150.0, 150.0 to 300.0, 10.0 to 20.0),
            // 리얼드럼 플로어탐 | 0.5~3 / 100~200 / 200~450 | 10~20 dB
            "drums.floor_tom" to GateRow(0.5 to 3.0, 100.0 to 200.0, 200.0 to 450.0, 10.0 to 20.0),
            // 하이햇·심벌·오버헤드·룸 | 전자/리얼 모두 숫자 비활성
            "edrums.hihat" to GateRow(null, null, null, null),
            "edrums.cymbal" to GateRow(null, null, null, null),
            "drums.hihat" to GateRow(null, null, null, null),
            "drums.overhead" to GateRow(null, null, null, null),
            "drums.room" to GateRow(null, null, null, null),
            "drums.full_kit" to GateRow(null, null, null, null),
        )
        expected.forEach { (id, e) ->
            val g = InstrumentCatalog.find(id)!!.gate.gate!!
            assertEquals("$id attack", e.a, g.attackMs?.let { it.minMs to it.maxMs })
            assertEquals("$id hold", e.h, g.holdMs?.let { it.minMs to it.maxMs })
            assertEquals("$id release", e.r, g.releaseMs?.let { it.minMs to it.maxMs })
            assertEquals("$id 감쇠량", e.att, g.attenuationDb?.let { it.minDb to it.maxDb })
        }
        // 크런치는 명세에 수치가 없다. 임의로 채우지 않았는지 본다.
        val crunch = InstrumentCatalog.find("electric_guitar.crunch")!!.gate.gate!!
        assertFalse("크런치에 없는 숫자를 채우면 안 된다", crunch.hasNumbers)
    }

    /** `(ratio, attack, release, GR)`. */
    private data class CompRow(
        val ratio: Pair<Double, Double>?, val a: Pair<Double, Double>?,
        val r: Pair<Double, Double>?, val gr: Pair<Double, Double>?,
    )

    @Test
    fun `컴프레서 수치가 명세 표와 같다`() {
        val expected = mapOf(
            // §18.4
            "synth.piano_ep" to CompRow(2.0 to 3.0, 15.0 to 35.0, 100.0 to 250.0, 1.0 to 3.0),
            "synth.pad_strings" to CompRow(1.5 to 2.0, 30.0 to 80.0, 200.0 to 600.0, 0.0 to 2.0),
            "synth.lead_brass" to CompRow(2.0 to 3.0, 10.0 to 30.0, 80.0 to 200.0, 1.0 to 3.0),
            "synth.bass" to CompRow(2.0 to 4.0, 15.0 to 40.0, 100.0 to 250.0, 1.0 to 4.0),
            "acoustic_guitar.strum" to CompRow(2.0 to 3.0, 15.0 to 35.0, 80.0 to 180.0, 2.0 to 4.0),
            "acoustic_guitar.fingerstyle" to CompRow(2.0 to 3.0, 5.0 to 20.0, 100.0 to 250.0, 1.0 to 3.0),
            "electric_guitar.clean" to CompRow(2.0 to 3.0, 10.0 to 30.0, 80.0 to 200.0, 2.0 to 4.0),
            "electric_guitar.high_gain" to CompRow(1.5 to 2.0, 15.0 to 40.0, 100.0 to 200.0, 0.0 to 2.0),
            "bass_guitar.finger" to CompRow(3.0 to 4.0, 15.0 to 40.0, 80.0 to 200.0, 3.0 to 5.0),
            "bass_guitar.pick" to CompRow(3.0 to 4.0, 15.0 to 40.0, 80.0 to 200.0, 3.0 to 5.0),
            "bass_guitar.slap" to CompRow(3.0 to 5.0, 5.0 to 15.0, 60.0 to 150.0, 3.0 to 6.0),
            "edrums.full_kit" to CompRow(1.5 to 2.0, 20.0 to 40.0, 100.0 to 250.0, 0.0 to 2.0),
            // 전자드럼 개별 파트 | 리얼드럼 범위에서 약한 쪽, 파트별. GR 0~3
            "edrums.kick" to CompRow(null, null, null, 0.0 to 3.0),
            "edrums.snare" to CompRow(null, null, null, 0.0 to 3.0),
            "edrums.tom" to CompRow(null, null, null, 0.0 to 3.0),
            "drums.kick" to CompRow(3.0 to 4.0, 15.0 to 35.0, 60.0 to 150.0, 2.0 to 4.0),
            "drums.snare" to CompRow(3.0 to 4.0, 10.0 to 30.0, 80.0 to 180.0, 2.0 to 4.0),
            "drums.rack_tom" to CompRow(2.0 to 4.0, 15.0 to 35.0, 120.0 to 300.0, 2.0 to 4.0),
            "drums.floor_tom" to CompRow(2.0 to 4.0, 15.0 to 35.0, 120.0 to 300.0, 2.0 to 4.0),
            // 하이햇·오버헤드·심벌 | 1.5:1~2:1 | 20~40 | 150~350 | 0~2
            "edrums.hihat" to CompRow(1.5 to 2.0, 20.0 to 40.0, 150.0 to 350.0, 0.0 to 2.0),
            "edrums.cymbal" to CompRow(1.5 to 2.0, 20.0 to 40.0, 150.0 to 350.0, 0.0 to 2.0),
            "drums.hihat" to CompRow(1.5 to 2.0, 20.0 to 40.0, 150.0 to 350.0, 0.0 to 2.0),
            "drums.overhead" to CompRow(1.5 to 2.0, 20.0 to 40.0, 150.0 to 350.0, 0.0 to 2.0),
            // 리얼드럼 룸/전체 버스 | 1.5:1~2:1 | 20~40 | 100~300 | 1~3
            "drums.room" to CompRow(1.5 to 2.0, 20.0 to 40.0, 100.0 to 300.0, 1.0 to 3.0),
            "drums.full_kit" to CompRow(1.5 to 2.0, 20.0 to 40.0, 100.0 to 300.0, 1.0 to 3.0),
        )
        expected.forEach { (id, e) ->
            val c = InstrumentCatalog.find(id)!!.compressor.compressor!!
            assertEquals("$id ratio", e.ratio, c.ratio?.let { it.min to it.max })
            assertEquals("$id attack", e.a, c.attackMs?.let { it.minMs to it.maxMs })
            assertEquals("$id release", e.r, c.releaseMs?.let { it.minMs to it.maxMs })
            assertEquals("$id GR", e.gr, c.grReferenceDb?.let { it.minDb to it.maxDb })
        }
        val crunch = InstrumentCatalog.find("electric_guitar.crunch")!!.compressor.compressor!!
        assertFalse("크런치에 없는 숫자를 채우면 안 된다", crunch.hasNumbers)
    }

    /** HPF/LPF 시작점도 명세 §4 와 대조한다. */
    @Test
    fun `필터 시작점이 명세와 같다`() {
        fun hpfOf(id: String) = InstrumentCatalog.find(id)!!.hpf
        fun lpfOf(id: String) = InstrumentCatalog.find(id)!!.lpf

        // §4.1 피아노/EP: HPF 30~60, 70~120 / LPF bypass, 12~16k
        assertEquals(30.0 to 60.0, hpfOf("synth.piano_ep").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(70.0 to 120.0, hpfOf("synth.piano_ep").altRangeHz!!.let { it.lowHz to it.highHz })
        assertTrue(lpfOf("synth.piano_ep").bypassDefault)
        assertEquals(12000.0 to 16000.0, lpfOf("synth.piano_ep").rangeHz!!.let { it.lowHz to it.highHz })

        // §4.2 어쿠스틱: HPF 60~80, 80~120 / LPF 10~14k
        assertEquals(60.0 to 80.0, hpfOf("acoustic_guitar.strum").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(80.0 to 120.0, hpfOf("acoustic_guitar.strum").altRangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(10000.0 to 14000.0, lpfOf("acoustic_guitar.strum").rangeHz!!.let { it.lowHz to it.highHz })

        // §4.3 일렉: HPF 60~90, 80~120 / LPF 하이게인 6~10k, 클린 10~14k
        assertEquals(60.0 to 90.0, hpfOf("electric_guitar.clean").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(6000.0 to 10000.0, lpfOf("electric_guitar.high_gain").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(10000.0 to 14000.0, lpfOf("electric_guitar.clean").rangeHz!!.let { it.lowHz to it.highHz })

        // §4.4 베이스: HPF 4현 25~35, 5현 20~30 / LPF 핑거 4~8k, 피크·슬랩 8~12k
        assertEquals(25.0 to 35.0, hpfOf("bass_guitar.finger").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(20.0 to 30.0, hpfOf("bass_guitar.finger").altRangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(4000.0 to 8000.0, lpfOf("bass_guitar.finger").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(8000.0 to 12000.0, lpfOf("bass_guitar.slap").rangeHz!!.let { it.lowHz to it.highHz })

        // §4.5 전자드럼 킥 HPF 20~35 / 스네어 70~100 / 하이햇 150~300 / 심벌 100~250
        assertEquals(20.0 to 35.0, hpfOf("edrums.kick").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(70.0 to 100.0, hpfOf("edrums.snare").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(150.0 to 300.0, hpfOf("edrums.hihat").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(100.0 to 250.0, hpfOf("edrums.cymbal").rangeHz!!.let { it.lowHz to it.highHz })

        // §4.6 리얼드럼 킥 25~40 / 스네어 60~100 / 랙탐 40~60 / 플로어탐 25~40 / 오버헤드 60~100, 150~250
        assertEquals(25.0 to 40.0, hpfOf("drums.kick").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(60.0 to 100.0, hpfOf("drums.snare").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(40.0 to 60.0, hpfOf("drums.rack_tom").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(25.0 to 40.0, hpfOf("drums.floor_tom").rangeHz!!.let { it.lowHz to it.highHz })
        assertEquals(150.0 to 250.0, hpfOf("drums.overhead").altRangeHz!!.let { it.lowHz to it.highHz })

        // 전체 키트는 두 필터 모두 bypass 이고 숫자가 없다.
        listOf("drums.full_kit").forEach {
            assertTrue(hpfOf(it).bypassDefault && hpfOf(it).rangeHz == null)
            assertTrue(lpfOf(it).bypassDefault && lpfOf(it).rangeHz == null)
        }
    }

    /** 화면 문자열이 단위와 함께 나오는지(§18.7 2번). */
    @Test
    fun `범위 문자열에 단위가 붙는다`() {
        val p = InstrumentCatalog.find("drums.kick")!!
        assertEquals("45~100 Hz", p.regions.first().range.labelKo())
        assertEquals("0.5~2 ms", p.gate.gate!!.attackMs!!.labelKo())
        assertEquals("10~20 dB", p.gate.gate!!.attenuationDb!!.labelKo())
        assertEquals("3:1~4:1", p.compressor.compressor!!.ratio!!.labelKo())
        assertEquals("1k~2.5k Hz", InstrumentCatalog.find("acoustic_guitar.strum")!!.regions[3].range.labelKo())
    }

    @Test
    fun `카탈로그 판이 붙어 있다`() {
        assertTrue(InstrumentCatalog.VERSION.isNotBlank())
    }
}
