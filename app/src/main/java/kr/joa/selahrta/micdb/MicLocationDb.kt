package kr.joa.selahrta.micdb

/**
 * **갤럭시 내장 마이크 위치 안내 DB**(`docs/data/galaxy-mic-location-db/`).
 *
 * 삼성 도면에서 **마이크라고 적힌 자리**를 옮겨 적은 문서 기반 자료다.
 * 폰을 어디에 놓아야 하는지 사람이 알 수 있게 하는 것이 전부다.
 *
 * ## 이것이 아닌 것 — 세 가지를 섞지 않는다
 *
 * 자료 제공자가 지시서 3·5장에서 못박은 자리이고, 이 파일 전체가 그
 * 경계를 지키려고 있다.
 *
 * | | 무엇인가 | 어디서 오나 |
 * |---|---|---|
 * | **위치 안내** | 도면에 적힌 **외관상 구멍 자리** | 이 DB |
 * | 실행 중 진단 | 지금 녹음에 쓰이는 마이크 | `getActiveMicrophones()` |
 * | 음압 교정 | 이 경로의 감도 | 사람이 재서 얻은 값 |
 *
 * 그래서:
 *
 * - **[MicLocation.locationKey] 는 화면용 이름이지 `MicrophoneInfo.getId()`
 *   가 아니다.** 둘을 잇는 대응표는 검증된 적이 없다.
 * - **[MicModel.documentedLocationCount] 는 「이 기기의 마이크 개수」가
 *   아니다.** 도면에 적힌 자리의 수다. Tab S8 도면에 두 자리가 보인다고
 *   내부 캡슐이 둘이라는 뜻이 아니다.
 * - **이 DB 로 음압 보정값을 만들지 않는다.** 마이크 수나 자리가 달라도
 *   그것만으로 감도를 말할 수 없다. `FactoryCalibration` 과 이 파일은
 *   서로를 부르지 않는다 — 일부러 그렇게 두었다.
 *
 * ## 모르는 것은 null 이다
 *
 * 원본의 규약이 `null_means_not_verified_not_zero_or_false` 다. 확인하지
 * 못한 자리를 0 이나 거짓으로 바꿔 담지 않는다.
 */
data class MicLocationDb(
    val schemaVersion: String,
    val contentVersion: String,
    /** 자료를 확인한 날(`2026-09-26`). 화면에 적어 언제 것인지 밝힌다. */
    val checkedOn: String,
    val models: List<MicModel>,
    /**
     * **아직 확인하지 못한 기종.** 자동 조회에 넣지 않는다.
     *
     * 이름만 들고 있는 까닭은, 「그 기종은 아직 못 봤다」와 「그런 기종은
     * 없다」가 다르기 때문이다.
     */
    val pendingModelIds: List<String>,
    val sources: List<MicSource>,
    val orientations: Map<String, String>,
) {
    fun modelById(id: String): MicModel? = models.firstOrNull { it.modelId == id }
    fun sourceById(id: String): MicSource? = sources.firstOrNull { it.sourceId == id }
}

data class MicModel(
    val modelId: String,
    /** 「Galaxy S23 Ultra」 같은 제품명. 화면에 그대로 쓴다. */
    val marketingName: String,
    val category: MicDeviceCategory,
    /**
     * 문서에서 **코드까지 대조한** 모델코드. 비었으면 자동 조회를 하지 않는다.
     *
     * S23 3종이 그렇다 — 국내 제품명별 자리는 확인됐지만 그 페이지에서
     * 모델코드를 맞춰 본 것은 아니다.
     */
    val documentedModelCodes: List<String>,
    /** **도면에 적힌 자리의 수.** 기기의 마이크 개수가 아니다. */
    val documentedLocationCount: Int,
    val locations: List<MicLocation>,
    /** 어느 방향으로 놓고 본 기준인가. [MicLocationDb.orientations] 의 열쇠. */
    val orientationId: String,
    val sourceIds: List<String>,
    val notesKo: String?,
) {
    /** 이 기종을 모델코드로 자동으로 찾아도 되는가. */
    val autoLookupAllowed: Boolean get() = documentedModelCodes.isNotEmpty()
}

enum class MicDeviceCategory(val labelKo: String) {
    Phone("휴대폰"),
    Tablet("태블릿"),
}

data class MicLocation(
    /** **화면용 이름이다.** 안드로이드 마이크 id 와 아무 관계가 없다. */
    val locationKey: String,
    val surface: MicSurface,
    val descriptionKo: String,
    /** 어느 문서의 어느 쪽에서 봤는가. */
    val sourceId: String,
    val printedPage: Int?,
)

/** 도면이 가리킨 면. 이름은 원본 그대로다. */
enum class MicSurface(val key: String, val labelKo: String) {
    TopEdge("top_edge", "상단"),
    BottomEdge("bottom_edge", "하단"),
    LeftEdge("left_edge", "왼쪽"),
    RightEdge("right_edge", "오른쪽"),
    Rear("rear", "후면"),
    RearCameraRim("rear_camera_rim", "후면 카메라 테두리"),
    RearCameraArea("rear_camera_area", "후면 카메라 주변"),
    ;

    companion object {
        fun of(key: String): MicSurface? = entries.firstOrNull { it.key == key }
    }
}

data class MicSource(
    val sourceId: String,
    val author: String,
    val title: String,
    val url: String,
    /**
     * **삼성이 쓴 문서인 것과 삼성 서버에서 받은 것은 다른 일이다.**
     * 원본이 그 둘을 갈라 두었으므로 여기서도 가른다.
     */
    val official: Boolean,
) {
    val hostingKo: String get() = if (official) "공식" else "외부 미러"
}

/**
 * 지금 기기에 대한 조회 결과.
 *
 * **「찾았다」와 「이 기기가 맞다」는 다르다.** 그래서 세 갈래다.
 */
sealed interface MicLocationMatch {
    /**
     * 모델코드가 문서의 코드와 **정확히** 같다. 문서 기반 안내를 건다.
     *
     * 그래도 실기기로 확인한 것은 아니다 — 원본의
     * `documentary_reference_not_device_tested` 그대로다.
     */
    data class Documented(val model: MicModel, val matchedCode: String) : MicLocationMatch

    /**
     * 사람이 제품명을 골랐다. **참고 도면**이다.
     *
     * 지금 기기와 맞는지 앱은 모른다. 골랐다는 사실이 확인이 되지 않는다.
     */
    data class ManualReference(val model: MicModel) : MicLocationMatch

    /**
     * 이 기기의 자리를 모른다.
     *
     * **비슷한 기종의 것을 대신 쓰지 않는다.** 접미사를 떼거나 `SM-S9` 로
     * 묶으면 다른 세대가 걸린다.
     */
    data class Unknown(val reasonKo: String) : MicLocationMatch
}

/**
 * **정확히 일치할 때만** 문서 안내를 건다(원본 지시서 5B).
 *
 * 다듬는 것은 앞뒤 공백을 떼고 대문자로 바꾸는 것까지다. 접미사
 * (`N`·`B`·`U1`·`E`·`/DS`·`UD`)를 떼거나, `SM-S9` 같은 앞머리로 여러
 * 세대를 묶거나, Plus·Ultra·FE·연식이 비슷하다고 다른 자료를 쓰지 않는다.
 *
 * **왜 이렇게까지 좁히나** — 틀린 자리를 알려 주면 사람은 그 자리에 대고
 * 잰다. 모른다고 말하면 사람이 직접 찾아본다. 뒤엣것이 낫다.
 */
fun lookupMicLocations(
    db: MicLocationDb,
    manufacturer: String,
    modelCode: String,
): MicLocationMatch {
    if (!manufacturer.trim().equals("samsung", ignoreCase = true)) {
        return MicLocationMatch.Unknown("갤럭시 기기에서만 자리 안내를 드릴 수 있습니다.")
    }
    val code = modelCode.trim().uppercase()
    if (code.isEmpty()) {
        return MicLocationMatch.Unknown("기기의 모델코드를 읽지 못했습니다.")
    }
    val hit = db.models.firstOrNull { m -> m.documentedModelCodes.any { it.trim().uppercase() == code } }
    return if (hit != null) {
        MicLocationMatch.Documented(hit, code)
    } else {
        MicLocationMatch.Unknown(
            "$code 의 마이크 자리는 아직 확인된 자료에 없습니다. " +
                "아래에서 제품명을 고르면 참고 도면을 보여 드립니다 — " +
                "지금 기기와 같은지는 확인된 것이 아닙니다.",
        )
    }
}

/** 사람이 제품명을 골랐다. **확인 상태는 바뀌지 않는다.** */
fun manualMicLocations(db: MicLocationDb, modelId: String): MicLocationMatch =
    db.modelById(modelId)
        ?.let { MicLocationMatch.ManualReference(it) }
        ?: MicLocationMatch.Unknown("고르신 제품의 자료를 찾지 못했습니다.")
