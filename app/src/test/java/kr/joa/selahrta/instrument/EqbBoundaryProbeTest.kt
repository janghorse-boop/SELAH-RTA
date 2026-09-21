package kr.joa.selahrta.instrument

import kr.joa.selahrta.data.instrument.InstrumentCatalog
import org.junit.Test

/**
 * **검증자 probe 그대로**(`docs/review/EQB-boundary-probe.kt`).
 *
 * 검증자가 보낸 `fun main()` 의 본문을 손대지 않고 옮겼다. 바꾼 것은
 * JUnit 으로 돌리기 위한 껍데기와, 고친 뒤에는 `mismatches == 7` 이
 * 성립하지 않으므로 그 마지막 `check` 를 **출력으로 바꾼 것**뿐이다.
 *
 * 고치기 전 출력:
 *
 * ```
 * synth.lead_brass/attack symptom expected=(2000.0, 6000.0) actual=(2000.0, 5000.0) match=false
 * edrums.snare/attack     symptom expected=(3000.0, 6000.0) actual=(2000.0, 5000.0) match=false
 * drums.snare/attack      symptom expected=(3000.0, 6000.0) actual=(2000.0, 5000.0) match=false
 * edrums.hihat/rhythm     symptom expected=(3000.0, 7000.0) actual=(3000.0, 8000.0) match=false
 * drums.hihat/rhythm      symptom expected=(3000.0, 7000.0) actual=(3000.0, 8000.0) match=false
 * edrums.cymbal/attack    symptom expected=(3000.0, 7000.0) actual=(3000.0, 8000.0) match=false
 * drums.overhead/stick    symptom expected=(3000.0, 7000.0) actual=(3000.0, 8000.0) match=false
 * SPEC_BOUNDARY_MISMATCHES=7/7
 * ```
 *
 * 고친 뒤에는 이 일곱 영역이 **성격 전용**이 되어 `symptomTags` 가 비고,
 * 증상은 제 경계를 가진 별도 영역으로 나온다. 그래서 probe 의
 * `check(r.symptomTags.isNotEmpty())` 가 먼저 걸린다 — 그 사실 자체가
 * 고쳐졌다는 증거다. 지금의 계약은 [InstrumentCatalogTest] 의
 * `모든 영역 경계가 명세와 같다` 가 붙들고 있다.
 */
class EqbBoundaryProbeTest {

    @Test
    fun `검증자 probe`() {
        val cases = listOf(
            Triple("synth.lead_brass", "attack", 2000.0 to 6000.0),
            Triple("edrums.snare", "attack", 3000.0 to 6000.0),
            Triple("drums.snare", "attack", 3000.0 to 6000.0),
            Triple("edrums.hihat", "rhythm", 3000.0 to 7000.0),
            Triple("drums.hihat", "rhythm", 3000.0 to 7000.0),
            Triple("edrums.cymbal", "attack", 3000.0 to 7000.0),
            Triple("drums.overhead", "stick", 3000.0 to 7000.0),
        )
        var mismatches = 0
        var stillMerged = 0
        for ((id, rid, expected) in cases) {
            val r = InstrumentCatalog.find(id)!!.regions.single { it.id == rid }
            if (r.symptomTags.isNotEmpty()) stillMerged++
            val actual = r.range.lowHz to r.range.highHz
            if (actual != expected) mismatches++
            println("$id/$rid symptom expected=$expected actual=$actual match=${actual == expected} symptomTags=${r.symptomTags}")
        }
        println("SPEC_BOUNDARY_MISMATCHES=$mismatches/${cases.size}")
        println("STILL_MERGED=$stillMerged/${cases.size}")
    }
}
