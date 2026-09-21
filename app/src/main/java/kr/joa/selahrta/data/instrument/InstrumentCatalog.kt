package kr.joa.selahrta.data.instrument

import kr.joa.selahrta.domain.instrument.CompressorGuidance
import kr.joa.selahrta.domain.instrument.DbRange
import kr.joa.selahrta.domain.instrument.DynamicsGuidance
import kr.joa.selahrta.domain.instrument.FilterGuidance
import kr.joa.selahrta.domain.instrument.FilterType
import kr.joa.selahrta.domain.instrument.FrequencyRegion
import kr.joa.selahrta.domain.instrument.GateGuidance
import kr.joa.selahrta.domain.instrument.HzRange
import kr.joa.selahrta.domain.instrument.InstrumentProfile
import kr.joa.selahrta.domain.instrument.InstrumentType
import kr.joa.selahrta.domain.instrument.MsRange
import kr.joa.selahrta.domain.instrument.ProcessorType
import kr.joa.selahrta.domain.instrument.RatioRange

/**
 * 내장 카탈로그(명세 §4·§18.3·§18.4).
 *
 * **JSON 이 아니라 Kotlin 이다.** 명세 §11 은 「내장 JSON 카탈로그」라고
 * 적었지만 여기서는 소스로 둔다 — 값의 앞뒤·단위·부호를 **컴파일과
 * 생성자에서** 막을 수 있고, 파싱 실패라는 상태가 아예 생기지 않는다.
 * 명세 §13 이 「실제 코드 구조와 충돌하면 최소 호환 설계를 ADR 에
 * 남기라」고 한 자리다. 나중에 사용자 카탈로그를 파일로 받게 되면 그때
 * 같은 자료형으로 읽어 들인다.
 *
 * **이 수치는 표준이 아니다.** 제품의 **청취 탐색 범위**이며, 출처
 * 자료가 이 조합을 보증하지 않는다(명세 §17). 음향 담당자 검토를 거쳐
 * 버전과 함께 바꾼다.
 */
object InstrumentCatalog {

    /** 카탈로그 판. 스냅샷·프리셋에 함께 적어 나중에 견준다. */
    const val VERSION = "1.0a"

    /** 6종 전부. 화면에 나오는 차례다. */
    val profiles: List<InstrumentProfile> by lazy {
        synth + acousticGuitar + electricGuitar + bassGuitar + DrumsCatalog.electronic + DrumsCatalog.acoustic
    }

    fun byType(type: InstrumentType): List<InstrumentProfile> = profiles.filter { it.type == type }

    fun find(stableId: String): InstrumentProfile? = profiles.firstOrNull { it.stableId == stableId }

    // ------------------------------------------------------------------
    // 신디사이저 (§4.1)
    // ------------------------------------------------------------------

    private val synthCautions = listOf(
        "패치·옥타브·레이어·필터 resonance·내장 EQ/리버브를 EQ 보다 먼저 확인합니다.",
        "패드의 고역이 적거나 필터 스윕의 피크가 움직이는 것은 정상 표현일 수 있습니다.",
        "패치를 바꾸면 누적된 후보를 버리고 A/B 를 다시 측정해야 합니다.",
        "스테레오 패치를 모노 마이크로 잰 결과는 스테레오 이미지도, 전기적 L/R 합산 응답도 아닙니다.",
    )

    private val synth: List<InstrumentProfile> = listOf(
        profile(
            stableId = "synth.piano_ep",
            type = InstrumentType.SYNTH,
            subtypeId = "piano_ep",
            nameKo = "피아노 / EP",
            roles = listOf("화성", "리드"),
            regions = listOf(
                region("body", 60.0, 200.0, listOf("바디"), emptyList(), "건반의 두께와 무게감이 있는 곳입니다.", "낮은 건반을 치며 두께가 충분한지 듣습니다.", "과하면 베이스와 겹쳐 저역이 뭉칩니다."),
                region("warmth", 200.0, 500.0, listOf("온기"), emptyList(), "따뜻함이 만들어지는 곳입니다.", "코드를 길게 눌러 두고 듣습니다.", "전체 합주에서는 다른 악기와 가장 잘 겹치는 구간입니다."),
                region("muddy", 150.0, 500.0, emptyList(), listOf("먹먹함"), "두께는 충분한데 답답하게 들릴 때 조사할 곳입니다.", "두께가 충분한데 답답할 때만 작은 컷을 견줍니다.", "많이 깎으면 피아노가 얇아집니다."),
                region("boxy", 500.0, 1000.0, emptyList(), listOf("상자 느낌"), "상자 안에서 나는 듯한 느낌의 후보입니다.", "단음 프레이즈를 반복하며 듣습니다.", "이 구간은 명료도에도 관여하므로 과한 컷은 존재감을 뺍니다."),
                region("clarity", 1000.0, 3000.0, listOf("명료도"), emptyList(), "음정과 음형이 구분되는 곳입니다.", "합주 중 피아노가 묻히는지 듣습니다.", "보컬과 겹치므로 올리기 전에 편곡·페이더를 먼저 견줍니다."),
                region("attack", 2000.0, 5000.0, listOf("어택"), listOf("딱딱함"), "건반을 누르는 순간의 소리입니다.", "강하게 친 음에서 귀를 찌르는지 듣습니다.", "올리면 존재감이 붙지만 딱딱해지기 쉽습니다."),
                region("air", 8000.0, 12000.0, listOf("공기감"), emptyList(), "개방감과 상부 배음입니다.", "잔향이 남는 구간에서 듣습니다.", "모든 악기에 필요한 목표가 아닙니다. 잡음도 함께 커집니다."),
            ),
            hpf = hpf(30.0, 60.0, alt = 70.0 to 120.0, condition = "독주나 저음을 살릴 때는 30~60, 합주에서 역할에 따라 70~120 을 견줍니다.", caution = "낮은 건반의 기음이 이 부근이라 필터가 음색을 바꿉니다."),
            lpf = lpfBypass("기본은 bypass 입니다. 최상단이 거칠거나 잡음이 있을 때만 12~16 kHz 를 시험합니다.", 12000.0, 16000.0),
            cautions = synthCautions,
            sourceIds = listOf(Sources.IZOTOPE_EQ_CHEAT_SHEET, Sources.SHURE_CHURCH, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                expander(
                    bypassDefault = true,
                    usage = "보통 bypass 입니다. 출력에 잡음이 있을 때만 약한 익스팬더를 시험합니다.",
                    cautions = listOf("페달로 남긴 잔향과 약한 건반이 잘리지 않는지 확인합니다."),
                    attack = 1.0 to 5.0, hold = 80.0 to 150.0, release = 200.0 to 500.0,
                    attenuation = 6.0 to 12.0,
                ),
                comp(
                    bypassDefault = false,
                    usage = "건반마다의 세기 편차를 고르게 만들 때 씁니다.",
                    cautions = listOf("attack 이 빠르면 건반 터치가 둔해집니다."),
                    ratio = 2.0 to 3.0, attack = 15.0 to 35.0, release = 100.0 to 250.0, gr = 1.0 to 3.0,
                ),
            ),
        ),
        profile(
            stableId = "synth.pad_strings",
            type = InstrumentType.SYNTH,
            subtypeId = "pad_strings",
            nameKo = "패드 / 스트링",
            roles = listOf("화성 채움"),
            regions = listOf(
                region("thickness", 150.0, 500.0, listOf("두께"), emptyList(), "패드의 몸집이 만들어지는 곳입니다.", "다른 악기와 함께 틀어 두고 저중역이 쌓이는지 듣습니다.", "여러 악기가 같은 구간을 쓰면 합이 먹먹해집니다."),
                region("lowmid_pileup", 150.0, 400.0, emptyList(), listOf("저중역 누적"), "여러 악기의 저중역이 겹쳐 쌓이는 후보입니다.", "패드만 잠시 빼 보고 전체가 맑아지는지 듣습니다.", "패드만 깎는 것이 답이 아닐 수 있습니다. 편곡을 먼저 봅니다."),
                region("presence", 1000.0, 3000.0, listOf("존재감"), emptyList(), "패드가 믹스에서 들리는 정도를 정합니다.", "보컬과 함께 틀고 둘이 다투는지 듣습니다.", "1~4 kHz 는 보컬의 자리이기도 합니다."),
                region("vocal_clash", 1000.0, 4000.0, emptyList(), listOf("보컬과 충돌"), "보컬이 묻힐 때 조사할 곳입니다.", "보컬이 묻히면 패드의 이 구간을 작게 깎아 견줍니다.", "존재감 부스트보다 편곡·페이더를 먼저 견줍니다."),
                region("brightness", 6000.0, 12000.0, listOf("밝기", "공기감"), emptyList(), "패드의 밝기와 개방감입니다.", "길게 끄는 구간에서 듣습니다.", "패드에 따라 고역이 적은 것이 정상입니다."),
                region("harsh", 6000.0, 10000.0, emptyList(), listOf("거친 질감"), "거칠게 들릴 때 조사할 곳입니다.", "오래 들었을 때 피로한지로 판단합니다.", "많이 깎으면 패드가 답답해집니다."),
            ),
            hpf = hpf(100.0, 200.0, alt = null, condition = "패드가 저역을 맡지 않는다면 100~200 부터 견줍니다.", caution = "느린 fade-in 의 앞부분이 먼저 사라질 수 있습니다."),
            lpf = lpf(8000.0, 14000.0, bypassDefault = true, condition = "기본은 bypass, 최상단이 거칠면 8~14 kHz 를 시험합니다.", caution = "공기감이 함께 사라집니다."),
            cautions = synthCautions,
            sourceIds = listOf(Sources.IZOTOPE_EQ_CHEAT_SHEET, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                gate(
                    bypassDefault = true,
                    usage = "원칙적으로 bypass 입니다. 숫자 프리셋을 주지 않습니다.",
                    cautions = listOf("느린 fade-in 과 긴 release 를 게이트가 잘라버립니다."),
                    attack = null, hold = null, release = null, attenuation = null,
                ),
                comp(
                    bypassDefault = true,
                    usage = "bypass 또는 아주 약하게. 이미 평탄하면 필요 없습니다.",
                    cautions = listOf("느린 swell 을 누르지 않도록 합니다."),
                    ratio = 1.5 to 2.0, attack = 30.0 to 80.0, release = 200.0 to 600.0, gr = 0.0 to 2.0,
                ),
            ),
        ),
        profile(
            stableId = "synth.lead_brass",
            type = InstrumentType.SYNTH,
            subtypeId = "lead_brass",
            nameKo = "리드 / 브라스",
            roles = listOf("리드", "훅"),
            regions = listOf(
                region("body", 150.0, 600.0, listOf("바디"), emptyList(), "리드의 몸집입니다.", "프레이즈를 반복하며 얇지 않은지 듣습니다.", "과하면 중역이 뭉칩니다."),
                region("shape", 700.0, 2000.0, listOf("명료도"), emptyList(), "음형이 드러나는 곳입니다.", "빠른 프레이즈에서 음이 구분되는지 듣습니다.", "보컬과 겹칩니다."),
                region("attack", 2000.0, 5000.0, listOf("어택", "존재감"), emptyList(), "시작 부분과 존재감입니다.", "센 음에서 귀를 찌르는지 듣습니다.", "올리면 존재감이 붙지만 날카로워지기 쉽습니다."),
                // 명세 §4.1 은 성격을 2~5k, 증상을 **2~6k** 로 따로 적는다.
                // 하나로 합치면 증상 카드의 숫자가 5k 로 잘린다(독립 검증 EQB01).
                region("harsh", 2000.0, 6000.0, emptyList(), listOf("날카로움"), "날카롭게 들릴 때 조사할 곳입니다.", "센 음을 반복하며 귀를 찌르는지 듣습니다.", "어택과 존재감이 함께 줄어듭니다."),
                region("nasal", 300.0, 800.0, emptyList(), listOf("답답함", "콧소리"), "답답하거나 콧소리처럼 들릴 때 조사할 곳입니다.", "패치를 바꿔 보고도 남는지 확인합니다.", "이 구간은 바디이기도 해서 과한 컷은 빈약해집니다."),
                region("bright_harmonics", 8000.0, 12000.0, listOf("밝기"), emptyList(), "밝은 배음입니다.", "길게 끄는 음에서 듣습니다.", "패치 자체의 특성일 수 있습니다."),
            ),
            hpf = hpf(70.0, 150.0, alt = null, condition = "리드가 저역을 맡지 않으면 70~150 을 견줍니다.", caution = "낮은 옥타브를 쓰는 패치라면 음색이 바뀝니다."),
            lpf = lpf(8000.0, 12000.0, bypassDefault = true, condition = "기본은 bypass, 최상단이 거칠면 8~12 kHz 를 시험합니다.", caution = "밝기가 함께 줄어듭니다."),
            cautions = synthCautions,
            sourceIds = listOf(Sources.IZOTOPE_EQ_CHEAT_SHEET, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                gate(
                    bypassDefault = true,
                    usage = "의도한 sustain 과 필터 꼬리를 먼저 확인합니다. 필요할 때만 씁니다.",
                    cautions = listOf("부드러운 시작과 낮은 음의 끝이 보존되는지 확인합니다."),
                    attack = 1.0 to 5.0, hold = 50.0 to 100.0, release = 150.0 to 300.0,
                    attenuation = 6.0 to 12.0,
                ),
                comp(
                    bypassDefault = false,
                    usage = "프레이즈의 크기를 고르게 유지할 때 씁니다.",
                    cautions = listOf("의도한 어택과 필터 움직임이 보존되는지 확인합니다."),
                    ratio = 2.0 to 3.0, attack = 10.0 to 30.0, release = 80.0 to 200.0, gr = 1.0 to 3.0,
                ),
            ),
        ),
        profile(
            stableId = "synth.bass",
            type = InstrumentType.SYNTH,
            subtypeId = "synth_bass",
            nameKo = "신스 베이스",
            roles = listOf("저역"),
            regions = listOf(
                region("sub", 25.0, 80.0, listOf("서브"), emptyList(), "몸으로 느끼는 가장 낮은 곳입니다.", "작은 스피커에서는 들리지 않을 수 있으니 두 곳에서 듣습니다.", "여기를 올리면 헤드룸이 빠르게 줄어듭니다."),
                region("weight", 60.0, 150.0, listOf("무게", "펀치"), emptyList(), "무게와 타격이 있는 곳입니다.", "킥과 함께 들으며 서로 가리지 않는지 봅니다.", "킥과 겹칩니다."),
                region("body", 150.0, 350.0, listOf("바디"), emptyList(), "베이스의 몸집입니다.", "코드 악기와 함께 듣습니다.", "과하면 저중역이 뭉칩니다."),
                region("boom", 40.0, 120.0, emptyList(), listOf("붕붕거림"), "붕붕거릴 때 조사할 곳입니다.", "같은 음을 반복하며 특정 음만 튀는지 듣습니다.", "공간의 공진일 수도 있습니다."),
                region("muddy", 150.0, 400.0, emptyList(), listOf("먹먹함"), "먹먹할 때 조사할 곳입니다.", "전체 합주에서 듣습니다.", "의도적인 패치 공진을 문제로 오인하지 않습니다."),
                region("pitch", 700.0, 2000.0, listOf("명료도"), emptyList(), "음정이 식별되는 곳입니다.", "저역이 적은 스피커에서 음정이 들리는지 듣습니다.", "여기를 깎으면 작은 스피커에서 베이스가 사라집니다."),
                region("texture", 2000.0, 5000.0, listOf("질감"), emptyList(), "패치의 질감입니다.", "필터가 열릴 때의 소리를 듣습니다.", "잡음도 함께 커집니다."),
            ),
            hpf = hpf(20.0, 30.0, alt = null, bypassDefault = true, condition = "기본은 bypass. 서브가 과하면 20~30 부터 견줍니다.", caution = "베이스의 기음을 깎게 되기 쉽습니다."),
            lpf = lpf(3000.0, 8000.0, bypassDefault = true, condition = "질감을 고르는 용도입니다. 필요할 때만 3~8 kHz 를 시험합니다.", caution = "어택과 질감이 함께 사라집니다."),
            cautions = synthCautions,
            sourceIds = listOf(Sources.IZOTOPE_EQ_CHEAT_SHEET, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                gate(
                    bypassDefault = true,
                    usage = "의도한 sustain 과 필터 꼬리를 먼저 확인합니다. 필요할 때만 씁니다.",
                    cautions = listOf("낮은 음의 끝이 잘리지 않는지 확인합니다."),
                    attack = 1.0 to 5.0, hold = 50.0 to 100.0, release = 150.0 to 300.0,
                    attenuation = 6.0 to 12.0,
                ),
                comp(
                    bypassDefault = false,
                    usage = "음마다의 레벨 차를 정리할 때 씁니다.",
                    cautions = listOf("서브의 펌핑과 왜곡을 확인합니다.", "패치 안에 이미 압축이 들어 있을 수 있습니다."),
                    ratio = 2.0 to 4.0, attack = 15.0 to 40.0, release = 100.0 to 250.0, gr = 1.0 to 4.0,
                ),
            ),
        ),
        profile(
            stableId = "synth.other",
            type = InstrumentType.SYNTH,
            subtypeId = "other",
            nameKo = "기타 / 복합",
            roles = listOf("패치에 따라 다름"),
            regions = listOf(
                region("full", 20.0, 20000.0, listOf("전대역"), emptyList(), "패치에 따라 전대역에 걸칩니다. 범용 정상 곡선이 없습니다.", "그 패치가 합주에서 맡은 역할을 먼저 적고, 그 역할의 구간만 듣습니다.", "두 겹 이상의 역할을 한 패치가 맡고 있으면 하나로 판단할 수 없습니다."),
            ),
            hpf = FilterGuidance(FilterType.HPF, bypassDefault = true, rangeHz = null, conditionKo = "역할을 확인한 뒤에 고릅니다. 기본은 bypass 입니다.", cautionKo = "역할을 모르는 채 거는 필터는 되돌리기 어렵습니다."),
            lpf = FilterGuidance(FilterType.LPF, bypassDefault = true, rangeHz = null, conditionKo = "역할을 확인한 뒤에 고릅니다. 기본은 bypass 입니다.", cautionKo = "역할을 모르는 채 거는 필터는 되돌리기 어렵습니다."),
            cautions = synthCautions + "이 항목에는 범용 정상 곡선이 없습니다. 패치의 역할을 사용자가 기록해야 합니다.",
            sourceIds = listOf(Sources.IZOTOPE_EQ_CHEAT_SHEET, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                gate(
                    bypassDefault = true,
                    usage = "역할을 모르는 채로는 쓰지 않습니다.",
                    cautions = listOf("패치의 의도된 꼬리가 잘릴 수 있습니다."),
                    attack = null, hold = null, release = null, attenuation = null,
                ),
                comp(
                    bypassDefault = true,
                    usage = "역할을 확인한 뒤 해당 세부유형의 값을 참고합니다.",
                    cautions = listOf("다른 세부유형의 값을 그대로 복사하지 않습니다."),
                    ratio = null, attack = null, release = null, gr = null,
                ),
            ),
        ),
    )

    // ------------------------------------------------------------------
    // 어쿠스틱 기타 (§4.2)
    // ------------------------------------------------------------------

    private val acousticGuitarRegions = listOf(
        region("low_string", 80.0, 150.0, listOf("바디", "무게"), listOf("붕붕거림"), "낮은 현의 무게와 몸통 울림입니다.", "낮은 현을 반복해 치며 특정 음만 부푸는지 듣습니다.", "붕붕거림과 꼭 필요한 기음을 구분해야 합니다."),
        region("fullness", 150.0, 350.0, listOf("바디"), listOf("먹먹함"), "풍성함이 만들어지는 곳이고, 과하면 먹먹해집니다.", "코드를 길게 울리며 듣습니다.", "지나치게 깎으면 얇은 소리가 됩니다."),
        region("box", 350.0, 800.0, listOf("나무 울림"), listOf("박스톤"), "나무 몸통의 울림이고 박스톤의 후보입니다.", "마이크 위치를 조금 옮겨 보고도 남는지 확인합니다.", "마이크 위치와 몸통 공진을 EQ 보다 먼저 봅니다."),
        region("clarity", 1000.0, 2500.0, listOf("명료도", "존재감"), emptyList(), "음형이 구분되고 존재감이 생기는 곳입니다.", "합주에서 기타가 들리는지 듣습니다.", "픽업 질감과 보컬 간섭을 함께 듣습니다."),
        region("pick_attack", 2000.0, 5000.0, listOf("어택"), listOf("까칠함"), "피크와 손톱의 어택, 단단한 질감입니다.", "스트로크를 세게 칠 때 듣습니다.", "과하면 까칠해지고 피에조 특유의 거친 느낌이 납니다."),
        region("string_noise", 6000.0, 10000.0, listOf("밝기"), emptyList(), "현 마찰과 밝기입니다.", "코드를 바꿀 때의 손가락 소리를 함께 듣습니다.", "손가락 잡음도 함께 커집니다."),
        region("air", 10000.0, 14000.0, listOf("공기감"), emptyList(), "공기감입니다.", "잔향이 남는 구간에서 듣습니다.", "픽업 신호에 없는 공기감을 부스트로 복원하려 하지 않습니다."),
    )

    private val acousticGuitarCautions = listOf(
        "표준 튜닝의 최저 E 가 약 82 Hz 입니다. 그 부근의 필터는 음색을 바꿉니다. 드롭 튜닝은 더 낮습니다.",
        "부밍은 사운드홀 정면 근접 배치·모니터·공간의 영향일 수 있습니다. EQ 보다 마이크를 조금 옮겨 견주는 편이 효과적일 수 있습니다.",
        "컷 전후로 전체 합주에서 두께가 유지되는지 듣습니다.",
    )

    private val acousticGuitarHpf = hpf(60.0, 80.0, alt = 80.0 to 120.0, condition = "낮은 현과 독주를 살리려면 60~80, 합주에서는 80~120 을 견줍니다. 저역 역할이 불필요할 때만 120~150 을 시험합니다.", caution = "최저 E 가 약 82 Hz 라 이 부근에서 음색이 바뀝니다.")
    private val acousticGuitarLpf = lpf(10000.0, 14000.0, bypassDefault = true, condition = "기본은 bypass 입니다. 잡음이나 거친 최상단을 줄일 때 10~14 kHz 를 시험합니다.", caution = "공기감이 함께 사라집니다.")

    private val acousticGuitar: List<InstrumentProfile> = listOf(
        profile(
            stableId = "acoustic_guitar.strum",
            type = InstrumentType.ACOUSTIC_GUITAR,
            subtypeId = "strum",
            nameKo = "스트로크",
            roles = listOf("리듬", "화성"),
            regions = acousticGuitarRegions,
            hpf = acousticGuitarHpf,
            lpf = acousticGuitarLpf,
            cautions = acousticGuitarCautions + "펀치는 고정된 대역보다 스트로크 어택과 바디의 균형으로 설명합니다.",
            sourceIds = listOf(Sources.IZOTOPE_GUITARS, Sources.SHURE_CHURCH, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                expander(
                    bypassDefault = true,
                    usage = "게이트보다 bypass 또는 약한 익스팬더를 먼저 씁니다.",
                    cautions = listOf("핑거스타일·현 울림·룸톤이 잘릴 수 있습니다."),
                    attack = 1.0 to 5.0, hold = 80.0 to 150.0, release = 200.0 to 400.0,
                    attenuation = 6.0 to 12.0,
                ),
                comp(
                    bypassDefault = false,
                    usage = "스트로크의 강약을 고르게 만들 때 씁니다.",
                    cautions = listOf("피크 어택과 리듬이 유지되는지 확인합니다."),
                    ratio = 2.0 to 3.0, attack = 15.0 to 35.0, release = 80.0 to 180.0, gr = 2.0 to 4.0,
                ),
            ),
        ),
        profile(
            stableId = "acoustic_guitar.fingerstyle",
            type = InstrumentType.ACOUSTIC_GUITAR,
            subtypeId = "fingerstyle",
            nameKo = "핑거스타일",
            roles = listOf("리드", "화성"),
            regions = acousticGuitarRegions,
            hpf = acousticGuitarHpf,
            lpf = acousticGuitarLpf,
            cautions = acousticGuitarCautions,
            sourceIds = listOf(Sources.IZOTOPE_GUITARS, Sources.SHURE_CHURCH, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                expander(
                    bypassDefault = true,
                    usage = "게이트보다 bypass 또는 약한 익스팬더를 먼저 씁니다.",
                    cautions = listOf("현 울림과 룸톤의 꼬리가 잘리기 쉽습니다."),
                    attack = 1.0 to 5.0, hold = 80.0 to 150.0, release = 200.0 to 400.0,
                    attenuation = 6.0 to 12.0,
                ),
                comp(
                    bypassDefault = false,
                    usage = "돌출하는 피킹을 완화할 때 씁니다.",
                    cautions = listOf("손가락 잡음과 잔향이 함께 커질 수 있습니다."),
                    ratio = 2.0 to 3.0, attack = 5.0 to 20.0, release = 100.0 to 250.0, gr = 1.0 to 3.0,
                ),
            ),
        ),
    )

    // ------------------------------------------------------------------
    // 일렉기타 (§4.3)
    // ------------------------------------------------------------------

    private val electricGuitarRegions = listOf(
        region("low_string", 80.0, 180.0, listOf("무게", "펀치"), emptyList(), "저음현과 팜뮤트의 무게입니다.", "팜뮤트 리프를 반복하며 킥·베이스와 다투는지 듣습니다.", "킥·베이스와 저역이 겹칩니다."),
        region("body", 150.0, 400.0, listOf("바디"), listOf("뭉침"), "기타의 몸집입니다.", "코드를 길게 울리며 듣습니다.", "과하면 뭉치고, 과도한 컷은 빈약해집니다."),
        region("midrange", 400.0, 900.0, listOf("중역 질감"), listOf("박스톤", "콧소리"), "중역의 질감입니다. 박스톤의 후보이면서 그 기타의 개성이기도 합니다.", "앰프의 톤 노브를 먼저 돌려 보고도 남는지 확인합니다.", "깎으면 개성이 함께 사라집니다."),
        region("clarity", 800.0, 2000.0, listOf("명료도"), emptyList(), "음정과 코드가 구분되는 곳입니다.", "전체 믹스에서 코드가 식별되는지 듣습니다.", "보컬과 겹칩니다."),
        region("pick_attack", 2000.0, 4000.0, listOf("어택", "존재감"), listOf("날카로움"), "피킹의 어택과 존재감입니다.", "센 피킹에서 귀를 찌르는지 듣습니다.", "보컬과 충돌하거나 귀를 찌를 수 있습니다."),
        region("fizz", 4000.0, 8000.0, listOf("질감"), listOf("거친 고역"), "디스토션의 질감이고 fizz 의 후보입니다.", "하이게인에서 코드를 길게 울리며 듣습니다.", "마이크 각도와 캐비닛 IR 을 EQ 보다 먼저 봅니다."),
        region("openness", 8000.0, 12000.0, listOf("공기감"), emptyList(), "클린과 효과음의 개방감입니다.", "클린 아르페지오에서 듣습니다.", "일반 기타 캐비닛에서 약한 것이 정상입니다. 고역 부족으로 자동 판정하지 않습니다."),
    )

    private val electricGuitarCautions = listOf(
        "캐비닛 처리 없는 DI 를 정상 앰프 음색 기준과 견주지 않습니다.",
        "LPF 로 앰프·캐비닛의 자연 감쇠를 보상하려 하지 않습니다.",
        "팜뮤트의 순간 과다는 정적 EQ 만으로 해결되지 않을 수 있습니다. 프리앰프 게인·연주 세기·채널 레벨도 함께 견줍니다.",
    )

    private val electricGuitarHpf = hpf(60.0, 90.0, alt = 80.0 to 120.0, condition = "저음과 다운튜닝을 살리려면 60~90, 합주 출발점은 80~120 입니다.", caution = "다운튜닝에서는 기음을 깎게 되기 쉽습니다.")

    private val electricGuitar: List<InstrumentProfile> = listOf(
        profile(
            stableId = "electric_guitar.clean",
            type = InstrumentType.ELECTRIC_GUITAR,
            subtypeId = "clean",
            nameKo = "클린",
            roles = listOf("화성", "아르페지오"),
            regions = electricGuitarRegions,
            hpf = electricGuitarHpf,
            lpf = lpf(10000.0, 14000.0, bypassDefault = true, condition = "클린은 10~14 kHz 또는 bypass 입니다.", caution = "개방감이 함께 줄어듭니다."),
            cautions = electricGuitarCautions,
            sourceIds = listOf(Sources.IZOTOPE_GUITARS, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                expander(
                    bypassDefault = true,
                    usage = "bypass 또는 약한 익스팬더를 씁니다.",
                    cautions = listOf("sustain 이 손상되는지 확인합니다."),
                    attack = 1.0 to 3.0, hold = 50.0 to 100.0, release = 150.0 to 300.0,
                    attenuation = 6.0 to 12.0,
                ),
                comp(
                    bypassDefault = false,
                    usage = "터치 편차를 줄이고 sustain 을 얻을 때 씁니다.",
                    cautions = listOf("어택 손실과 노이즈 증가를 확인합니다."),
                    ratio = 2.0 to 3.0, attack = 10.0 to 30.0, release = 80.0 to 200.0, gr = 2.0 to 4.0,
                ),
            ),
        ),
        profile(
            stableId = "electric_guitar.crunch",
            type = InstrumentType.ELECTRIC_GUITAR,
            subtypeId = "crunch",
            nameKo = "크런치",
            roles = listOf("리듬"),
            regions = electricGuitarRegions,
            hpf = electricGuitarHpf,
            lpf = FilterGuidance(
                FilterType.LPF, bypassDefault = true, rangeHz = null,
                conditionKo = "공통 시작값을 제시하지 않습니다. bypass 부터 청취하며 조정하세요.",
                cautionKo = "게인 정도와 캐비닛에 따라 달라집니다. 정답으로 쓸 공통값은 없습니다.",
            ),
            cautions = electricGuitarCautions + "크런치는 공통 시작값을 제시하지 않습니다. bypass 부터 청취하며 조정하세요.",
            sourceIds = listOf(Sources.IZOTOPE_GUITARS, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                expander(
                    bypassDefault = true,
                    usage = "공통 시작값을 제시하지 않습니다. bypass 부터 청취하며 조정하세요.",
                    cautions = listOf("중간값을 정답처럼 제시하지 않습니다.", "sustain 과 쉼표의 hiss 중 무엇이 문제인지 먼저 정합니다."),
                    attack = null, hold = null, release = null, attenuation = null,
                ),
                comp(
                    bypassDefault = true,
                    usage = "공통 시작값을 제시하지 않습니다. bypass 부터 청취하며 조정하세요.",
                    cautions = listOf("중간값을 정답처럼 제시하지 않습니다.", "왜곡 자체가 이미 압축으로 작용합니다."),
                    ratio = null, attack = null, release = null, gr = null,
                ),
            ),
        ),
        profile(
            stableId = "electric_guitar.high_gain",
            type = InstrumentType.ELECTRIC_GUITAR,
            subtypeId = "high_gain",
            nameKo = "하이게인",
            roles = listOf("리듬", "리드"),
            regions = electricGuitarRegions,
            hpf = electricGuitarHpf,
            lpf = lpf(6000.0, 10000.0, bypassDefault = false, condition = "하이게인은 6~10 kHz 를 견줍니다.", caution = "fizz 와 함께 어택의 질감도 줄어듭니다."),
            cautions = electricGuitarCautions,
            sourceIds = listOf(Sources.IZOTOPE_GUITARS, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                gate(
                    bypassDefault = false,
                    usage = "쉼표의 hiss 가 실제로 문제일 때 씁니다.",
                    cautions = listOf("롱톤에서는 release 를 늘리거나 bypass 합니다.", "리프용 값을 모든 곡에 고정하지 않습니다."),
                    attack = 0.5 to 2.0, hold = 30.0 to 80.0, release = 80.0 to 180.0,
                    attenuation = 12.0 to 24.0,
                ),
                comp(
                    bypassDefault = true,
                    usage = "왜곡 자체로 이미 압축된 경우가 많아 추가 압축을 최소화합니다.",
                    cautions = listOf("추가 압축이 어택을 더 뭉갤 수 있습니다."),
                    ratio = 1.5 to 2.0, attack = 15.0 to 40.0, release = 100.0 to 200.0, gr = 0.0 to 2.0,
                ),
            ),
        ),
    )

    // ------------------------------------------------------------------
    // 베이스기타 (§4.4)
    // ------------------------------------------------------------------

    private val bassRegions = listOf(
        region("lowest", 30.0, 60.0, listOf("최저음"), emptyList(), "가장 낮은 기반입니다. 저 B 가 약 31 Hz, 저 E 가 약 41 Hz 입니다.", "5현이나 저음 튜닝을 쓸 때만 확인합니다.", "큰 부스트가 필요한 곳이 아닙니다."),
        region("weight", 60.0, 120.0, listOf("무게", "펀치"), listOf("붕붕거림"), "무게와 타격이 있는 곳입니다.", "킥과 함께 들으며 서로 가리지 않는지 봅니다.", "붕붕거림이 생기고 킥과 충돌할 수 있습니다."),
        region("body", 120.0, 250.0, listOf("바디", "온기"), listOf("뭉침"), "베이스의 몸집과 온기입니다.", "코드 악기와 함께 듣습니다.", "과하면 뭉칩니다."),
        region("lowmid", 250.0, 500.0, listOf("저중역 연결"), listOf("먹먹함"), "저역과 중역을 잇는 곳입니다.", "전체 합주에서 듣습니다.", "과도한 컷은 작은 스피커에서 베이스를 사라지게 합니다."),
        region("pitch", 700.0, 1500.0, listOf("명료도", "존재감"), listOf("콧소리"), "음정이 식별되는 곳입니다.", "저역이 적은 스피커에서 음정이 들리는지 듣습니다.", "콧소리처럼 느껴질 수도 있습니다."),
        region("attack", 2000.0, 5000.0, listOf("어택"), emptyList(), "피크와 슬랩의 어택입니다.", "피킹 세기를 바꿔 가며 듣습니다.", "프렛·현 잡음도 함께 커집니다."),
        region("slap_bright", 5000.0, 8000.0, listOf("밝기"), emptyList(), "슬랩의 밝기와 금속 질감입니다.", "슬랩 구간에서만 듣습니다.", "공기감은 선택 사항이고 핑거 톤의 필수 목표가 아닙니다."),
    )

    private val bassCautions = listOf(
        "저역이 적은 재생 장치에서 기음만 부스트하지 말고 배음과 중역의 식별을 듣습니다.",
        "킥과 「항상 특정 주파수를 나눠야 한다」는 규칙을 만들지 않습니다. 연주 패턴과 서로의 중심 역할을 함께 조정합니다.",
    )

    private val bassHpf = hpf(25.0, 35.0, alt = 20.0 to 30.0, condition = "4현은 25~35 를 견줍니다. 5현이나 저음 튜닝은 bypass 또는 20~30 부터 듣습니다.", caution = "저 B 가 약 31 Hz 라 5현에서는 기음을 깎게 되기 쉽습니다.")

    private val bassGuitar: List<InstrumentProfile> = listOf(
        profile(
            stableId = "bass_guitar.finger",
            type = InstrumentType.BASS_GUITAR,
            subtypeId = "finger",
            nameKo = "핑거",
            roles = listOf("저역"),
            regions = bassRegions,
            hpf = bassHpf,
            lpf = lpf(4000.0, 8000.0, bypassDefault = true, condition = "핑거 톤은 4~8 kHz 를 견줍니다.", caution = "프렛 잡음과 함께 어택도 줄어듭니다."),
            cautions = bassCautions,
            sourceIds = listOf(Sources.IZOTOPE_EQ_CHEAT_SHEET, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(bassGate(), bassComp(3.0, 4.0, 15.0, 40.0, 80.0, 200.0, 3.0, 5.0, "음정별 레벨을 정리하고 펀치를 유지할 때 씁니다.", listOf("저음에서 release 가 너무 빠르면 거칠어집니다."))),
        ),
        profile(
            stableId = "bass_guitar.pick",
            type = InstrumentType.BASS_GUITAR,
            subtypeId = "pick",
            nameKo = "피크",
            roles = listOf("저역"),
            regions = bassRegions,
            hpf = bassHpf,
            lpf = lpf(8000.0, 12000.0, bypassDefault = true, condition = "피크는 8~12 kHz 또는 bypass 입니다.", caution = "피크 소리의 질감이 함께 줄어듭니다."),
            cautions = bassCautions,
            sourceIds = listOf(Sources.IZOTOPE_EQ_CHEAT_SHEET, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(bassGate(), bassComp(3.0, 4.0, 15.0, 40.0, 80.0, 200.0, 3.0, 5.0, "음정별 레벨을 정리하고 펀치를 유지할 때 씁니다.", listOf("저음에서 release 가 너무 빠르면 거칠어집니다."))),
        ),
        profile(
            stableId = "bass_guitar.slap",
            type = InstrumentType.BASS_GUITAR,
            subtypeId = "slap",
            nameKo = "슬랩",
            roles = listOf("저역", "리듬"),
            regions = bassRegions,
            hpf = bassHpf,
            lpf = lpf(8000.0, 12000.0, bypassDefault = true, condition = "슬랩은 8~12 kHz 또는 bypass 입니다.", caution = "금속 질감이 함께 줄어듭니다."),
            cautions = bassCautions,
            sourceIds = listOf(Sources.IZOTOPE_EQ_CHEAT_SHEET, Sources.IZOTOPE_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(bassGate(), bassComp(3.0, 5.0, 5.0, 15.0, 60.0, 150.0, 3.0, 6.0, "돌출하는 음을 줄일 때 씁니다.", listOf("타격을 모두 없애지 않도록 합니다."))),
        ),
    )

    private fun bassGate() = gate(
        bypassDefault = true,
        usage = "대개 bypass 입니다. 누음이 실제로 문제일 때만 씁니다.",
        cautions = listOf("약한 음과 긴 저음 sustain 을 잘라먹지 않는지 확인합니다."),
        attack = 1.0 to 5.0, hold = 80.0 to 150.0, release = 150.0 to 350.0,
        attenuation = 6.0 to 12.0,
    )

    private fun bassComp(
        rMin: Double, rMax: Double, aMin: Double, aMax: Double,
        relMin: Double, relMax: Double, grMin: Double, grMax: Double,
        usage: String, cautions: List<String>,
    ) = comp(
        bypassDefault = false, usage = usage, cautions = cautions,
        ratio = rMin to rMax, attack = aMin to aMax, release = relMin to relMax, gr = grMin to grMax,
    )
}

/** 출처 열쇠(명세 §17·§18.8). 링크는 화면에서 이 열쇠로 찾는다. */
object Sources {
    const val IZOTOPE_EQ_CHEAT_SHEET = "izotope.eq_cheat_sheet"
    const val IZOTOPE_GUITARS = "izotope.eq_guitars"
    const val IZOTOPE_DRUMS = "izotope.eq_drums"
    const val SHURE_CHURCH = "shure.church_sound"
    const val IZOTOPE_COMPRESSION = "izotope.what_is_compression"
    const val IZOTOPE_NOISE_GATES = "izotope.noise_gates"
    const val IZOTOPE_DRUM_COMPRESSION = "izotope.drum_compression"

    /** 화면에 적는 이름과 주소. */
    val titles: Map<String, Pair<String, String>> = mapOf(
        IZOTOPE_EQ_CHEAT_SHEET to ("iZotope — EQ Cheat Sheet" to "https://www.izotope.com/community/blog/eq-cheat-sheet"),
        IZOTOPE_GUITARS to ("iZotope — How to EQ Guitars in Your Mix" to "https://www.izotope.com/community/blog/how-to-eq-guitars-in-your-mix"),
        IZOTOPE_DRUMS to ("iZotope — How to EQ Drums" to "https://www.izotope.com/community/blog/how-to-eq-drums"),
        SHURE_CHURCH to ("Shure — Professional Mixing Tips for Church Sound" to "https://www.shure.com/en-EU/insights/professional-mixing-tips-for-church-sound"),
        IZOTOPE_COMPRESSION to ("iZotope — What Is Audio Compression?" to "https://www.izotope.com/community/blog/what-is-audio-compression"),
        IZOTOPE_NOISE_GATES to ("iZotope — What Is a Noise Gate and How to Use It" to "https://shopify.izotope.com/community/blog/noise-gates"),
        IZOTOPE_DRUM_COMPRESSION to ("iZotope — Ultimate Drum Compression Guide" to "https://www.izotope.com/en/learn/drum-compression"),
    )
}

// ----------------------------------------------------------------------
// 만들기 도우미. 카탈로그가 길어 같은 문구를 되풀이하지 않으려는 것이다.
// ----------------------------------------------------------------------

internal fun region(
    id: String, low: Double, high: Double,
    character: List<String>, symptom: List<String>,
    explain: String, listen: String, caution: String,
) = FrequencyRegion(
    id = id, range = HzRange(low, high),
    characterTags = character, symptomTags = symptom,
    explanationKo = explain, listenConditionKo = listen, cautionKo = caution,
)

internal fun hpf(
    low: Double, high: Double, alt: Pair<Double, Double>?,
    condition: String, caution: String, bypassDefault: Boolean = false,
) = FilterGuidance(
    filterType = FilterType.HPF, bypassDefault = bypassDefault,
    rangeHz = HzRange(low, high),
    altRangeHz = alt?.let { HzRange(it.first, it.second) },
    conditionKo = condition, cautionKo = caution,
)

internal fun lpf(
    low: Double, high: Double, bypassDefault: Boolean,
    condition: String, caution: String,
) = FilterGuidance(
    filterType = FilterType.LPF, bypassDefault = bypassDefault,
    rangeHz = HzRange(low, high), conditionKo = condition, cautionKo = caution,
)

internal fun lpfBypass(condition: String, low: Double, high: Double) = FilterGuidance(
    filterType = FilterType.LPF, bypassDefault = true, rangeHz = HzRange(low, high),
    conditionKo = condition,
    cautionKo = "차단주파수는 완전 제거 경계가 아닙니다. 고역이 점진적으로 줄어듭니다.",
)

/** §18.3 의 Threshold 맞추는 방법. 모든 게이트 카드가 같은 절차를 쓴다. */
internal const val GATE_THRESHOLD_PROCEDURE =
    "외부 믹서의 해당 채널에서 잡음·누음 구간과 **가장 약하게 살려야 할 연주**를 번갈아 듣습니다. " +
        "열림 threshold 를 두 수준 사이에서 조정하고, 약한 연주가 열리지 않으면 낮추거나 bypass 합니다. " +
        "두 수준이 겹치면 게이트로 깨끗하게 분리하기 어렵습니다. 최대 감쇠 대신 작은 Range 에서 시작합니다."

internal const val COMP_THRESHOLD_PROCEDURE =
    "외부 믹서의 GR 미터를 보며, 큰 연주에서 아래 참고 범위만큼 GR 이 생기도록 threshold 를 내립니다. " +
        "앱의 dBFS 를 threshold 로 옮겨 적지 않습니다. 이미 일정한 소스에는 0 dB 가 적절할 수 있습니다."

internal const val COMP_MAKEUP_PROCEDURE =
    "Makeup 은 0 dB 에서 시작해 bypass 와 청감 레벨을 비슷하게 맞춥니다. " +
        "Auto makeup 이 켜져 있는지 확인합니다. 더 커졌다는 이유로 더 좋다고 판단하지 않습니다."

internal val GATE_HYSTERESIS = DbRange(2.0, 6.0)

internal fun gate(
    bypassDefault: Boolean, usage: String, cautions: List<String>,
    attack: Pair<Double, Double>?, hold: Pair<Double, Double>?,
    release: Pair<Double, Double>?, attenuation: Pair<Double, Double>?,
    processorType: ProcessorType = ProcessorType.GATE,
) = DynamicsGuidance(
    processorType = processorType,
    bypassDefault = bypassDefault,
    usageConditionKo = usage,
    cautionsKo = cautions + "게이트는 열린 동안 섞인 누음·hiss 를 제거하지 못합니다. 발진 방지 장치나 불량 케이블의 해결책이 아닙니다.",
    gate = GateGuidance(
        attackMs = attack?.let { MsRange(it.first, it.second) },
        holdMs = hold?.let { MsRange(it.first, it.second) },
        releaseMs = release?.let { MsRange(it.first, it.second) },
        attenuationDb = attenuation?.let { DbRange(it.first, it.second) },
        hysteresisDb = if (attenuation != null) GATE_HYSTERESIS else null,
        thresholdProcedureKo = GATE_THRESHOLD_PROCEDURE,
    ),
)

internal fun expander(
    bypassDefault: Boolean, usage: String, cautions: List<String>,
    attack: Pair<Double, Double>?, hold: Pair<Double, Double>?,
    release: Pair<Double, Double>?, attenuation: Pair<Double, Double>?,
) = gate(
    bypassDefault, usage,
    cautions + "익스팬더는 작은 소리를 비례적으로 줄여, hard gate 보다 연주 끝을 자연스럽게 남길 수 있습니다.",
    attack, hold, release, attenuation, ProcessorType.EXPANDER,
)

internal fun comp(
    bypassDefault: Boolean, usage: String, cautions: List<String>,
    ratio: Pair<Double, Double>?, attack: Pair<Double, Double>?,
    release: Pair<Double, Double>?, gr: Pair<Double, Double>?,
) = DynamicsGuidance(
    processorType = ProcessorType.COMPRESSOR,
    bypassDefault = bypassDefault,
    usageConditionKo = usage,
    cautionsKo = cautions + "아래 GR 은 외부 믹서의 미터에서 사용자가 확인할 참고 범위이며, 앱의 실측값이 아닙니다.",
    compressor = CompressorGuidance(
        ratio = ratio?.let { RatioRange(it.first, it.second) },
        attackMs = attack?.let { MsRange(it.first, it.second) },
        releaseMs = release?.let { MsRange(it.first, it.second) },
        kneeHintKo = "soft 또는 medium 에서 시작합니다.",
        grReferenceDb = gr?.let { DbRange(it.first, it.second) },
        thresholdProcedureKo = COMP_THRESHOLD_PROCEDURE,
        makeupProcedureKo = COMP_MAKEUP_PROCEDURE,
    ),
)

internal fun profile(
    stableId: String, type: InstrumentType, subtypeId: String, nameKo: String,
    roles: List<String>, regions: List<FrequencyRegion>,
    hpf: FilterGuidance, lpf: FilterGuidance,
    cautions: List<String>, sourceIds: List<String>, dynamics: List<DynamicsGuidance>,
) = InstrumentProfile(
    stableId = stableId, type = type, subtypeId = subtypeId, nameKo = nameKo,
    roles = roles, regions = regions, hpf = hpf, lpf = lpf,
    cautions = cautions, sourceIds = sourceIds, dynamics = dynamics,
)
