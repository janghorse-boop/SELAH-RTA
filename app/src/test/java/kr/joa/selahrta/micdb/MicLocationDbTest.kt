package kr.joa.selahrta.micdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **갤럭시 마이크 위치 DB** — 앱에 실린 그 파일을 그대로 읽어 본다.
 *
 * 흉내 낸 표본이 아니라 실제 자산 파일을 읽는다. 표본으로 시험하면
 * 「파서가 돈다」까지만 알 수 있고, **자료가 바뀌었을 때 깨지지 않는다** —
 * 자료 개정이 이 저장소의 정상 절차라 그쪽이 더 중요하다.
 *
 * 지키는 것은 자료 제공자가 「개발 완료 기준」(README 7장)으로 적어 준
 * 그대로다.
 */
class MicLocationDbTest {

    private val db: MicLocationDb by lazy {
        val f = listOf(
            File("src/main/assets/galaxy-mic-locations.json"),
            File("app/src/main/assets/galaxy-mic-locations.json"),
        ).firstOrNull { it.exists() }
        assertNotNull("자산 파일을 찾지 못했다 — 경로가 바뀌었는가", f)
        val parsed = MicLocationDbParser.parse(f!!.readText(Charsets.UTF_8))
        assertNotNull("실린 자료를 읽지 못한다", parsed)
        parsed!!
    }

    // ── 자료가 온전한가 (README 7장) ────────────────────────

    @Test
    fun `실린 자료를 읽는다`() {
        assertEquals("1.0.0", db.schemaVersion)
        assertEquals("2026-09-26", db.checkedOn)
        assertEquals("확인된 기종 수가 다르다", 25, db.models.size)
        assertEquals("조사대기 기종 수가 다르다", 16, db.pendingModelIds.size)
        assertEquals("출처 수가 다르다", 10, db.sources.size)
    }

    @Test
    fun `휴대폰 15종 태블릿 10종이다`() {
        assertEquals(15, db.models.count { it.category == MicDeviceCategory.Phone })
        assertEquals(10, db.models.count { it.category == MicDeviceCategory.Tablet })
    }

    @Test
    fun `위치 행이 67개다`() {
        assertEquals(67, db.models.sumOf { it.locations.size })
    }

    @Test
    fun `셈과 목록의 길이가 같다`() {
        db.models.forEach {
            assertEquals(
                "${it.modelId}: 적힌 수와 목록 길이가 다르다",
                it.documentedLocationCount,
                it.locations.size,
            )
        }
    }

    @Test
    fun `기종 제품명 위치 열쇠에 겹침이 없다`() {
        val ids = db.models.map { it.modelId }
        assertEquals("기종 id 가 겹친다", ids.size, ids.toSet().size)
        val keys = db.models.flatMap { m -> m.locations.map { it.locationKey } }
        assertEquals("위치 열쇠가 겹친다", keys.size, keys.toSet().size)
        val codes = db.models.flatMap { it.documentedModelCodes }
        assertEquals("모델코드가 두 기종에 걸쳐 있다", codes.size, codes.toSet().size)
    }

    @Test
    fun `모든 위치의 출처가 실제로 있다`() {
        db.models.flatMap { it.locations }.forEach { loc ->
            assertNotNull(
                "${loc.locationKey}: 출처 ${loc.sourceId} 를 찾을 수 없다",
                db.sourceById(loc.sourceId),
            )
        }
    }

    @Test
    fun `모든 기종의 기준 방향이 정의돼 있다`() {
        db.models.forEach {
            assertTrue(
                "${it.modelId}: 기준 방향 ${it.orientationId} 이 없다",
                db.orientations.containsKey(it.orientationId),
            )
        }
    }

    // ── 정확히 일치할 때만 (README 5B) ──────────────────────

    @Test
    fun `문서에서 코드까지 대조한 기종은 자동으로 찾는다`() {
        val m = lookupMicLocations(db, "samsung", "SM-X710")
        assertTrue("$m", m is MicLocationMatch.Documented)
        assertEquals("tab_s9", (m as MicLocationMatch.Documented).model.modelId)
        assertEquals(2, m.model.documentedLocationCount)
    }

    @Test
    fun `앞뒤 공백과 소문자는 다듬는다`() {
        val m = lookupMicLocations(db, "Samsung", "  sm-x710 ")
        assertTrue("$m", m is MicLocationMatch.Documented)
    }

    /**
     * **이것이 이 파일의 한가운데다.** 접미사를 떼거나 앞머리로 묶으면
     * 다른 세대·다른 나라 기기의 자리를 이 기기의 것이라고 말하게 된다.
     */
    @Test
    fun `국내 코드는 확인되지 않았으므로 모른다고 말한다`() {
        val m = lookupMicLocations(db, "samsung", "SM-S918N")
        assertTrue("접미사를 떼고 억지로 맞췄다: $m", m is MicLocationMatch.Unknown)
    }

    @Test
    fun `앞머리로 여러 세대를 묶지 않는다`() {
        assertTrue(lookupMicLocations(db, "samsung", "SM-S9") is MicLocationMatch.Unknown)
        assertTrue(lookupMicLocations(db, "samsung", "SM-S921") is MicLocationMatch.Unknown)
    }

    @Test
    fun `S24 는 문서 코드가 있고 국내형은 없다`() {
        assertTrue(lookupMicLocations(db, "samsung", "SM-S921B") is MicLocationMatch.Documented)
        assertTrue(lookupMicLocations(db, "samsung", "SM-S921N") is MicLocationMatch.Unknown)
    }

    @Test
    fun `S23 세 기종은 자동으로 찾지 않는다`() {
        listOf("s23", "s23_plus", "s23_ultra").forEach { id ->
            val m = db.modelById(id)
            assertNotNull(id, m)
            assertFalse("$id: 코드 대조가 없는데 자동 조회를 연다", m!!.autoLookupAllowed)
        }
    }

    @Test
    fun `삼성이 아니면 안내하지 않는다`() {
        assertTrue(lookupMicLocations(db, "Google", "SM-X710") is MicLocationMatch.Unknown)
        assertTrue(lookupMicLocations(db, "", "SM-X710") is MicLocationMatch.Unknown)
    }

    @Test
    fun `모델코드를 모르면 안내하지 않는다`() {
        assertTrue(lookupMicLocations(db, "samsung", "  ") is MicLocationMatch.Unknown)
    }

    /** 조사대기 기종은 표에 없으므로 어떤 코드로도 걸리지 않는다. */
    @Test
    fun `조사대기 기종은 자동 조회에 없다`() {
        val ids = db.models.map { it.modelId }.toSet()
        db.pendingModelIds.forEach {
            assertFalse("조사대기 $it 이 확인 목록에 섞였다", it in ids)
        }
        assertTrue("S22 가 확인 목록에 있다", db.models.none { it.modelId.startsWith("s22") })
    }

    // ── 수동 선택은 참고일 뿐이다 ──────────────────────────

    @Test
    fun `사람이 고르면 참고 도면이다`() {
        val m = manualMicLocations(db, "s23_ultra")
        assertTrue("$m", m is MicLocationMatch.ManualReference)
        assertEquals("Galaxy S23 Ultra", (m as MicLocationMatch.ManualReference).model.marketingName)
        assertEquals(3, m.model.documentedLocationCount)
    }

    @Test
    fun `없는 제품을 고르면 모른다고 한다`() {
        assertTrue(manualMicLocations(db, "s22_ultra") is MicLocationMatch.Unknown)
    }

    // ── 도면을 세대 간에 베끼지 않았는가 (README 7장) ──────

    @Test
    fun `Tab S8 기본형과 Plus 의 상단 자리가 서로 다르다`() {
        val base = db.modelById("tab_s8")!!.locations.first { it.surface == MicSurface.TopEdge }
        val plus = db.modelById("tab_s8_plus")!!.locations.first { it.surface == MicSurface.TopEdge }
        assertTrue("기본형 상단이 카메라 오른쪽이 아니다", base.descriptionKo.contains("오른쪽"))
        assertTrue("Plus 상단이 카메라 왼쪽이 아니다", plus.descriptionKo.contains("왼쪽"))
    }

    @Test
    fun `Tab S8 기본형과 Plus 에 후면 자리를 붙이지 않았다`() {
        listOf("tab_s8", "tab_s8_plus").forEach { id ->
            assertTrue(
                "$id: Ultra 의 후면 자리가 딸려 왔다",
                db.modelById(id)!!.locations.none { it.surface.key.startsWith("rear") },
            )
        }
        assertEquals(3, db.modelById("tab_s8_ultra")!!.documentedLocationCount)
    }

    @Test
    fun `S6 Lite 만 세로 기준이다`() {
        assertEquals(
            "TABLET_PORTRAIT_USB_BOTTOM",
            db.modelById("tab_s6_lite_2022")!!.orientationId,
        )
        assertTrue(
            "휴대폰이 태블릿 기준을 쓰고 있다",
            db.models.filter { it.category == MicDeviceCategory.Phone }
                .all { it.orientationId == "PHONE_PORTRAIT" },
        )
    }

    // ── 교정과 섞이지 않는다 (README 5E) ───────────────────

    /**
     * 이 DB 에는 음압 보정값이 **없다.** 원본이 전 기종 `null` 로 두었고,
     * 우리 모델에도 그 자리를 만들지 않았다 — 자리를 만들어 두면 언젠가
     * 누가 채운다.
     */
    @Test
    fun `위치 자료에는 음압 보정값이 들어올 자리가 없다`() {
        val fields = MicModel::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(
            "위치 자료에 교정 비슷한 자리가 생겼다: $fields",
            fields.none { it.contains("spl") || it.contains("offset") || it.contains("calib") },
        )
        val locFields = MicLocation::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(
            "위치에 안드로이드 마이크 id 자리가 생겼다: $locFields",
            locFields.none { it.contains("androidmicrophone") },
        )
    }

    // ── 못 읽는 자료 ───────────────────────────────────────

    @Test
    fun `모르는 판은 읽지 않는다`() {
        assertNull(MicLocationDbParser.parse("""{"schema_version":"2.0.0","models":[]}"""))
    }

    @Test
    fun `깨진 파일은 빈 자료가 아니라 없음이다`() {
        assertNull(MicLocationDbParser.parse("이건 JSON 이 아니다"))
        assertNull(MicLocationDbParser.parse(""))
    }

    @Test
    fun `셈과 목록이 어긋난 기종은 버린다`() {
        val text = """
            {"schema_version":"1.0.0","content_version":"t","checked_on":"2026-09-26",
             "models":[{"model_id":"x","marketing_name":"X","category":"phone",
               "documented_model_codes":["SM-X"],"documented_mic_location_count":3,
               "orientation_id":"PHONE_PORTRAIT","source_ids":["S"],
               "locations":[{"location_key":"x:L01","surface":"top_edge",
                 "description_ko":"상단","evidence":{"source_id":"S"}}]}],
             "pending_models":[],"sources":[],"orientation_conventions":{}}
        """.trimIndent()
        val parsed = MicLocationDbParser.parse(text)
        assertNotNull(parsed)
        assertTrue("3곳이라 적고 하나만 든 기종을 들였다", parsed!!.models.isEmpty())
    }
}
