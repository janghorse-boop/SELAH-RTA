package kr.joa.selahrta.micdb

import org.json.JSONArray
import org.json.JSONObject

/**
 * 마이크 위치 DB 를 읽는다(`app/src/main/assets/galaxy-mic-locations.json`).
 *
 * **JSON 이 원본이다.** 자료를 고칠 때는 그 파일을 갈아 끼우고
 * `docs/data/galaxy-mic-location-db/validate_db.py` 를 돌린다 — 코드를
 * 다시 만들 일이 없게 하려고 표를 Kotlin 으로 옮겨 적지 않았다.
 *
 * ## 모르는 값을 채워 넣지 않는다
 *
 * 원본의 규약이 `null_means_not_verified_not_zero_or_false` 다. 여기서도
 * 없는 값은 null 로 두고, **읽을 수 없는 항목은 통째로 버린다** — 반쯤
 * 읽어 들인 기종이 화면에 뜨면 그것이 확인된 자료처럼 보인다.
 */
object MicLocationDbParser {

    /** 이 앱이 읽을 수 있는 판. 다르면 읽지 않는다. */
    const val SUPPORTED_SCHEMA = "1.0.0"

    /**
     * @return 읽지 못하면 null. **빈 DB 를 돌려주지 않는다** — 「자료가
     *   없다」와 「읽지 못했다」는 화면에서 다른 말을 해야 한다.
     */
    fun parse(text: String): MicLocationDb? = runCatching {
        val root = JSONObject(text)
        val schema = root.optString("schema_version")
        // **모르는 판을 짐작으로 읽지 않는다.** 뜻이 바뀐 자리를 옛 뜻으로
        // 읽으면 틀린 안내가 확인된 자료의 얼굴로 나간다.
        if (schema != SUPPORTED_SCHEMA) return null

        MicLocationDb(
            schemaVersion = schema,
            contentVersion = root.optString("content_version"),
            checkedOn = root.optString("checked_on"),
            models = root.optJSONArray("models").objects().mapNotNull(::model),
            pendingModelIds = root.optJSONArray("pending_models").objects()
                .mapNotNull { it.optStringOrNull("model_id") },
            sources = root.optJSONArray("sources").objects().mapNotNull(::source),
            orientations = orientations(root.optJSONObject("orientation_conventions")),
        )
    }.getOrNull()

    private fun model(o: JSONObject): MicModel? {
        val id = o.optStringOrNull("model_id") ?: return null
        val name = o.optStringOrNull("marketing_name") ?: return null
        val category = when (o.optString("category")) {
            "phone" -> MicDeviceCategory.Phone
            "tablet" -> MicDeviceCategory.Tablet
            else -> return null
        }
        val locations = o.optJSONArray("locations").objects().mapNotNull(::location)
        if (locations.isEmpty()) return null
        val count = o.optIntOrNull("documented_mic_location_count") ?: return null
        // **셈과 목록이 어긋나면 버린다.** 원본 검증기도 같은 것을 본다 —
        // 어긋난 채로 띄우면 「3곳」이라 적고 둘만 보여 주게 된다.
        if (count != locations.size) return null

        return MicModel(
            modelId = id,
            marketingName = name,
            category = category,
            // **코드 대조를 못 한 기종은 빈 배열이다.** 그대로 둔다 —
            // 비었다는 것이 「자동으로 찾지 말라」는 뜻이다.
            documentedModelCodes = o.optJSONArray("documented_model_codes").strings(),
            documentedLocationCount = count,
            locations = locations,
            orientationId = o.optStringOrNull("orientation_id") ?: return null,
            sourceIds = o.optJSONArray("source_ids").strings(),
            notesKo = o.optStringOrNull("notes_ko"),
        )
    }

    private fun location(o: JSONObject): MicLocation? {
        val key = o.optStringOrNull("location_key") ?: return null
        val surface = MicSurface.of(o.optString("surface")) ?: return null
        val desc = o.optStringOrNull("description_ko") ?: return null
        val ev = o.optJSONObject("evidence")
        return MicLocation(
            locationKey = key,
            surface = surface,
            descriptionKo = desc,
            sourceId = ev?.optStringOrNull("source_id").orEmpty(),
            printedPage = ev?.optIntOrNull("printed_page"),
        )
    }

    private fun source(o: JSONObject): MicSource? {
        val id = o.optStringOrNull("source_id") ?: return null
        return MicSource(
            sourceId = id,
            author = o.optStringOrNull("author").orEmpty(),
            title = o.optStringOrNull("title").orEmpty(),
            url = o.optStringOrNull("url").orEmpty(),
            // **모르면 공식이 아닌 쪽으로 둔다.** 출처를 실제보다 세게
            // 말하는 것이 약하게 말하는 것보다 나쁘다.
            official = o.optString("hosting") == "official",
        )
    }

    private fun orientations(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().mapNotNull { k ->
            o.optJSONObject(k)?.optStringOrNull("description_ko")?.let { k to it }
        }.toMap()
    }

    // ── JSON 잔손질 ────────────────────────────────────────
    //
    // `org.json` 의 `optString` 은 없는 값에 **빈 문자열**을 준다. 그것을
    // 그대로 쓰면 「모른다」가 「빈 이름」이 되어 화면에 빈칸이 뜬다.

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) {
            emptyList()
        } else {
            (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotEmpty() } }
        }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (isNull(name) || !has(name)) null else optInt(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
}
