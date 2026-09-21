package kr.joa.selahrta.data.instrument

import kr.joa.selahrta.domain.instrument.FilterGuidance
import kr.joa.selahrta.domain.instrument.FilterType
import kr.joa.selahrta.domain.instrument.InstrumentProfile
import kr.joa.selahrta.domain.instrument.InstrumentType

/**
 * 전자드럼·리얼드럼 파트(명세 §4.5·§4.6).
 *
 * **혼합 신호로 개별 드럼의 원인을 확정하지 않는다.** 전체 키트를 잰
 * 결과에서 「킥이 문제다」라고 말할 수 없다 — 그 제한이 파트마다
 * 주의사항으로 따라다닌다.
 */
internal object DrumsCatalog {

    // ------------------------------------------------------------------
    // 전자드럼 (§4.5)
    // ------------------------------------------------------------------

    private val electronicCautions = listOf(
        "전자드럼은 이미 EQ·압축·리버브가 적용된 샘플일 수 있습니다. 샘플 선택·튜닝·decay·velocity curve·모듈 파트 볼륨을 EQ 보다 먼저 확인합니다.",
        "모듈에서 PA 로 재생되는 소리를 재는 것입니다. 패드를 직접 때리는 생타격 소리만 재면 이 가이드를 적용하지 않습니다.",
        "전자드럼의 USB-MIDI 연결은 USB 마이크 입력이 아닙니다.",
    )

    val electronic: List<InstrumentProfile> = listOf(
        profile(
            stableId = "edrums.full_kit",
            type = InstrumentType.ELECTRONIC_DRUMS,
            subtypeId = "full_kit",
            nameKo = "전체 키트",
            roles = listOf("리듬"),
            regions = listOf(
                region("weight", 50.0, 120.0, listOf("무게"), emptyList(), "키트 전체의 무게입니다.", "같은 패턴을 10초 이상 반복하며 듣습니다.", "킥과 베이스가 함께 들어 있어 따로 떼어 판단할 수 없습니다."),
                region("body", 150.0, 300.0, listOf("바디"), emptyList(), "몸통의 울림입니다.", "필인 없이 기본 패턴에서 듣습니다.", "여러 파트가 겹쳐 있습니다."),
                region("attack", 2000.0, 5000.0, listOf("어택"), emptyList(), "타격의 시작입니다.", "스틱이 닿는 소리를 듣습니다.", "스네어·하이햇·심벌이 함께 들어 있습니다."),
                region("air", 8000.0, 14000.0, listOf("공기감"), emptyList(), "키트의 공기감입니다.", "심벌이 울리는 구간에서 듣습니다.", "심벌만의 것이 아닙니다."),
                region("clutter", 200.0, 500.0, emptyList(), listOf("뭉침"), "키트가 뭉쳐 들릴 때 조사할 곳입니다.", "모듈의 파트 볼륨을 먼저 확인하고도 남는지 봅니다.", "전체 출력에 거는 EQ 는 모든 파트에 함께 걸립니다."),
                region("harsh", 3000.0, 8000.0, emptyList(), listOf("자극"), "찌르듯 들릴 때 조사할 곳입니다.", "심벌이 많은 구간에서 듣습니다.", "스네어의 어택도 함께 줄어듭니다."),
            ),
            hpf = hpf(20.0, 30.0, alt = null, bypassDefault = true, condition = "기본은 bypass 또는 20~30 입니다. 파트 설정을 먼저 확인합니다.", caution = "킥의 기반이 이 부근입니다."),
            lpf = FilterGuidance(FilterType.LPF, bypassDefault = true, rangeHz = null, conditionKo = "기본은 bypass 입니다.", cautionKo = "전체 출력에 걸면 심벌의 공기감이 함께 사라집니다."),
            cautions = electronicCautions + "전체 키트를 재면 개별 파트의 원인을 자동으로 지목하지 않습니다. 모듈의 파트 solo 나 분리 출력이 필요합니다.",
            sourceIds = listOf(Sources.IZOTOPE_DRUMS, Sources.IZOTOPE_DRUM_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                gate(
                    bypassDefault = true,
                    usage = "모듈은 이미 편집된 음색일 수 있어 bypass 입니다. 숫자 프리셋을 주지 않습니다.",
                    cautions = listOf("전체 출력 게이트는 심벌 꼬리와 고스트노트를 함께 자릅니다."),
                    attack = null, hold = null, release = null, attenuation = null,
                ),
                comp(
                    bypassDefault = true,
                    usage = "bypass 또는 아주 약하게. 이미 처리된 키트에 중복 압축을 피합니다.",
                    cautions = listOf("심벌 펌핑을 확인합니다."),
                    ratio = 1.5 to 2.0, attack = 20.0 to 40.0, release = 100.0 to 250.0, gr = 0.0 to 2.0,
                ),
            ),
        ),
        profile(
            stableId = "edrums.kick",
            type = InstrumentType.ELECTRONIC_DRUMS,
            subtypeId = "kick",
            nameKo = "킥",
            roles = listOf("저역", "리듬"),
            regions = listOf(
                region("foundation", 35.0, 90.0, listOf("기반", "펀치"), emptyList(), "킥의 기반과 타격입니다.", "베이스와 함께 들으며 서로 가리지 않는지 봅니다.", "작은 스피커에서는 들리지 않습니다."),
                region("body", 90.0, 180.0, listOf("바디"), emptyList(), "킥의 몸집입니다.", "단독으로 반복해 듣습니다.", "베이스와 겹칩니다."),
                region("click", 2000.0, 5000.0, listOf("어택"), listOf("지나친 클릭"), "비터와 클릭 소리입니다.", "작은 스피커에서 킥이 들리는지로 판단합니다.", "과하면 딱딱거립니다."),
                region("boxy", 180.0, 450.0, emptyList(), listOf("답답함"), "답답하게 들릴 때 조사할 곳입니다.", "모듈의 샘플을 바꿔 보고도 남는지 확인합니다.", "많이 깎으면 킥이 얇아집니다."),
            ),
            hpf = hpf(20.0, 35.0, alt = null, condition = "서브를 포함할지 정한 뒤 20~35 를 견줍니다.", caution = "킥의 기반을 깎게 되기 쉽습니다."),
            lpf = lpf(6000.0, 10000.0, bypassDefault = true, condition = "기본은 bypass, 클릭이 거칠면 6~10 kHz 를 시험합니다.", caution = "클릭이 함께 줄어 작은 스피커에서 킥이 사라질 수 있습니다."),
            cautions = electronicCautions,
            sourceIds = listOf(Sources.IZOTOPE_DRUMS, Sources.IZOTOPE_DRUM_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(edrumPartGate(), edrumPartComp()),
        ),
        profile(
            stableId = "edrums.snare",
            type = InstrumentType.ELECTRONIC_DRUMS,
            subtypeId = "snare",
            nameKo = "스네어",
            roles = listOf("리듬"),
            regions = listOf(
                region("body", 120.0, 250.0, listOf("바디"), emptyList(), "스네어의 몸집입니다.", "단독으로 반복해 듣습니다.", "과하면 둔해집니다."),
                region("clarity", 1000.0, 3000.0, listOf("명료도"), emptyList(), "스네어가 구분되는 곳입니다.", "합주에서 묻히는지 듣습니다.", "보컬과 겹칩니다."),
                region("attack", 2000.0, 5000.0, listOf("어택"), emptyList(), "스틱이 닿는 소리입니다.", "센 타격에서 스틱이 들리는지 듣습니다.", "올리면 어택이 붙지만 날카로워지기 쉽습니다."),
                // 명세 §4.5 는 성격을 2~5k, 증상을 **3~6k** 로 따로 적는다(독립 검증 EQB01).
                region("harsh", 3000.0, 6000.0, emptyList(), listOf("날카로움"), "날카롭게 들릴 때 조사할 곳입니다.", "센 타격에서 귀를 찌르는지 듣습니다.", "어택이 함께 줄어듭니다."),
                region("snap", 6000.0, 12000.0, listOf("스냅"), emptyList(), "스냅의 밝기입니다.", "고스트노트에서 듣습니다.", "잡음도 함께 커집니다."),
                region("boxy", 300.0, 800.0, emptyList(), listOf("상자 느낌", "울림"), "상자처럼 울릴 때 조사할 곳입니다.", "샘플을 바꿔 보고도 남는지 확인합니다.", "바디와 겹칩니다."),
            ),
            hpf = hpf(70.0, 100.0, alt = null, condition = "킥의 누음을 줄일 때 70~100 을 견줍니다.", caution = "스네어의 바디가 얇아질 수 있습니다."),
            lpf = lpf(10000.0, 14000.0, bypassDefault = true, condition = "기본은 bypass, 최상단이 거칠면 10~14 kHz 를 시험합니다.", caution = "스냅이 함께 줄어듭니다."),
            cautions = electronicCautions,
            sourceIds = listOf(Sources.IZOTOPE_DRUMS, Sources.IZOTOPE_DRUM_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(edrumPartGate(), edrumPartComp()),
        ),
        profile(
            stableId = "edrums.tom",
            type = InstrumentType.ELECTRONIC_DRUMS,
            subtypeId = "tom",
            nameKo = "탐",
            roles = listOf("리듬", "필인"),
            regions = listOf(
                region("pitch_body", 50.0, 200.0, listOf("음고", "바디"), emptyList(), "탐의 음고와 몸통입니다.", "필인을 반복하며 듣습니다.", "플로어탐과 랙탐의 자리가 다릅니다."),
                region("attack", 2000.0, 5000.0, listOf("어택"), emptyList(), "스틱이 닿는 소리입니다.", "빠른 필인에서 음이 구분되는지 듣습니다.", "과하면 딱딱해집니다."),
                region("clutter", 200.0, 600.0, emptyList(), listOf("뭉침"), "뭉쳐 들릴 때 조사할 곳입니다.", "모듈의 decay 를 먼저 줄여 보고도 남는지 확인합니다.", "몸통이 함께 사라질 수 있습니다."),
            ),
            hpf = hpf(30.0, 50.0, alt = 40.0 to 70.0, condition = "플로어탐은 30~50, 랙탐은 40~70 을 견줍니다.", caution = "탐의 몸통이 얇아질 수 있습니다."),
            lpf = lpf(8000.0, 12000.0, bypassDefault = true, condition = "기본은 bypass, 필요하면 8~12 kHz 를 시험합니다.", caution = "어택이 함께 줄어듭니다."),
            cautions = electronicCautions,
            sourceIds = listOf(Sources.IZOTOPE_DRUMS, Sources.IZOTOPE_DRUM_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(edrumPartGate(), edrumPartComp()),
        ),
        profile(
            stableId = "edrums.hihat",
            type = InstrumentType.ELECTRONIC_DRUMS,
            subtypeId = "hihat",
            nameKo = "하이햇",
            roles = listOf("리듬"),
            regions = listOf(
                region("rhythm", 3000.0, 8000.0, listOf("리듬", "어택"), emptyList(), "하이햇의 리듬과 어택입니다.", "8비트를 반복하며 듣습니다.", "올리면 리듬이 또렷해지지만 쉽게 거칠어집니다."),
                // 명세 §4.5 는 성격을 3~8k, 증상을 **3~7k** 로 따로 적는다(독립 검증 EQB01).
                region("harsh", 3000.0, 7000.0, emptyList(), listOf("거침"), "거칠게 들릴 때 조사할 곳입니다.", "오래 들었을 때 피로한지로 판단합니다.", "리듬의 또렷함이 함께 줄어듭니다."),
                region("bright", 8000.0, 14000.0, listOf("밝기"), emptyList(), "하이햇의 밝기입니다.", "오픈 하이햇에서 듣습니다.", "지속음이 끊기기 쉽습니다."),
            ),
            hpf = hpf(150.0, 300.0, alt = null, condition = "다른 파트의 누음을 줄일 때 150~300 을 견줍니다.", caution = "하이햇의 몸통이 사라질 수 있습니다."),
            lpf = lpf(12000.0, 16000.0, bypassDefault = true, condition = "기본은 bypass, 최상단이 거칠면 12~16 kHz 를 시험합니다.", caution = "밝기가 함께 줄어듭니다."),
            cautions = electronicCautions,
            sourceIds = listOf(Sources.IZOTOPE_DRUMS, Sources.IZOTOPE_DRUM_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(cymbalGate(), cymbalComp()),
        ),
        profile(
            stableId = "edrums.cymbal",
            type = InstrumentType.ELECTRONIC_DRUMS,
            subtypeId = "cymbal",
            nameKo = "심벌",
            roles = listOf("리듬", "악센트"),
            regions = listOf(
                region("metal_body", 300.0, 1000.0, listOf("금속 몸통"), emptyList(), "심벌의 몸통입니다.", "크래시를 반복하며 듣습니다.", "다른 파트와 겹칩니다."),
                region("attack", 3000.0, 8000.0, listOf("어택"), emptyList(), "스틱이 닿는 소리입니다.", "라이드의 벨에서 듣습니다.", "올리면 어택이 붙지만 쉽게 찌르는 소리가 됩니다."),
                // 명세 §4.5 는 성격을 3~8k, 증상을 **3~7k** 로 따로 적는다(독립 검증 EQB01).
                region("harsh", 3000.0, 7000.0, emptyList(), listOf("자극", "긴 잔향 누적"), "찌르듯 들리거나 잔향이 쌓일 때 조사할 곳입니다.", "크래시가 이어지는 구간에서 듣습니다.", "스틱의 어택이 함께 줄어듭니다."),
                region("air", 8000.0, 16000.0, listOf("공기감"), emptyList(), "심벌의 공기감입니다.", "잔향이 남는 구간에서 듣습니다.", "공기감이 줄면 심벌이 답답해집니다."),
            ),
            hpf = hpf(100.0, 250.0, alt = null, condition = "다른 파트의 누음을 줄일 때 100~250 을 견줍니다.", caution = "금속 몸통이 사라질 수 있습니다."),
            lpf = lpf(12000.0, 16000.0, bypassDefault = true, condition = "기본은 bypass, 최상단이 거칠면 12~16 kHz 를 시험합니다.", caution = "공기감이 함께 줄어듭니다."),
            cautions = electronicCautions,
            sourceIds = listOf(Sources.IZOTOPE_DRUMS, Sources.IZOTOPE_DRUM_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(cymbalGate(), cymbalComp()),
        ),
    )

    private fun edrumPartGate() = gate(
        bypassDefault = true,
        usage = "필요하면 모듈의 decay·noise 설정을 먼저 봅니다. 그래도 남으면 리얼드럼의 해당 파트를 참고합니다.",
        cautions = listOf(
            "리얼드럼 값을 자동으로 복사하지 않습니다. 파트 solo 로 확인한 뒤 씁니다.",
            "감쇠량은 6~12 dB 부터 시작합니다.",
        ),
        attack = null, hold = null, release = null, attenuation = 6.0 to 12.0,
    )

    private fun edrumPartComp() = comp(
        bypassDefault = true,
        usage = "리얼드럼 해당 파트 범위에서 약한 쪽을 씁니다. attack·release 는 파트마다 다릅니다.",
        cautions = listOf(
            "샘플·모듈 처리를 먼저 확인합니다. 단순히 같은 값을 자동 적용하지 않습니다.",
        ),
        ratio = null, attack = null, release = null, gr = 0.0 to 3.0,
    )

    private fun cymbalGate() = gate(
        bypassDefault = true,
        usage = "전자·리얼 모두 일반적으로 bypass 입니다. 숫자 프리셋을 주지 않습니다.",
        cautions = listOf("지속음과 공간감이 끊기기 쉽습니다."),
        attack = null, hold = null, release = null, attenuation = null,
    )

    private fun cymbalComp() = comp(
        bypassDefault = true,
        usage = "bypass 또는 아주 약하게 씁니다.",
        cautions = listOf("잔향 펌핑과 킥·스네어에 끌리는 압축을 확인합니다."),
        ratio = 1.5 to 2.0, attack = 20.0 to 40.0, release = 150.0 to 350.0, gr = 0.0 to 2.0,
    )

    // ------------------------------------------------------------------
    // 리얼드럼 (§4.6)
    // ------------------------------------------------------------------

    private val acousticCautions = listOf(
        "드럼 크기·튜닝·헤드·뮤트·마이크 위치에 따라 범위가 크게 바뀝니다. 공진이 곧 결함은 아닙니다.",
        "멀티 마이크 드럼의 필터 변경은 다른 마이크와의 합산에도 영향을 줍니다. 전체 키트를 들으며 확인합니다.",
        "객석 마이크 RTA 로 각 채널의 위상·극성을 확정하지 않습니다.",
        "튜닝·댐핑·마이크 위치를 EQ 보다 먼저 점검합니다.",
    )

    val acoustic: List<InstrumentProfile> = listOf(
        profile(
            stableId = "drums.full_kit",
            type = InstrumentType.ACOUSTIC_DRUMS,
            subtypeId = "full_kit",
            nameKo = "전체 키트",
            roles = listOf("리듬"),
            regions = listOf(
                region("full", 30.0, 16000.0, listOf("전대역"), emptyList(), "킥의 저역부터 심벌의 고역까지 걸칩니다. 파트별 역할로 설명합니다.", "같은 패턴을 10초 이상 반복하며 듣습니다.", "혼합 신호라 개별 드럼의 원인을 확정할 수 없습니다."),
            ),
            hpf = FilterGuidance(FilterType.HPF, bypassDefault = true, rangeHz = null, conditionKo = "기본은 bypass 입니다.", cautionKo = "전체에 걸면 킥의 기반이 함께 줄어듭니다."),
            lpf = FilterGuidance(FilterType.LPF, bypassDefault = true, rangeHz = null, conditionKo = "기본은 bypass 입니다.", cautionKo = "전체에 걸면 심벌의 공기감이 함께 줄어듭니다."),
            cautions = acousticCautions + "혼합 신호로 개별 드럼의 원인을 확정하지 않습니다.",
            sourceIds = listOf(Sources.IZOTOPE_DRUMS, Sources.SHURE_CHURCH, Sources.IZOTOPE_DRUM_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
            dynamics = listOf(
                gate(
                    bypassDefault = true,
                    usage = "전체 키트에는 쓰지 않습니다.",
                    cautions = listOf("심벌 꼬리와 고스트노트를 함께 자릅니다."),
                    attack = null, hold = null, release = null, attenuation = null,
                ),
                comp(
                    bypassDefault = true,
                    usage = "bypass 또는 아주 약하게. 의도적인 효과가 아니라면 공간 과장을 최소화합니다.",
                    cautions = listOf("전체 버스 압축은 모든 파트에 함께 걸립니다."),
                    ratio = 1.5 to 2.0, attack = 20.0 to 40.0, release = 100.0 to 300.0, gr = 1.0 to 3.0,
                ),
            ),
        ),
        drumPart(
            "drums.kick", "킥", listOf("저역", "리듬"),
            listOf(
                region("weight", 45.0, 100.0, listOf("무게", "펀치"), emptyList(), "킥의 무게와 타격입니다.", "베이스와 함께 들으며 서로 가리지 않는지 봅니다.", "작은 스피커에서는 들리지 않습니다."),
                region("body", 100.0, 200.0, listOf("바디"), emptyList(), "킥의 몸집입니다.", "단독으로 반복해 듣습니다.", "베이스와 겹칩니다."),
                region("attack", 2000.0, 5000.0, listOf("어택"), emptyList(), "비터가 닿는 소리입니다.", "작은 스피커에서 킥이 들리는지로 판단합니다.", "과하면 딱딱거립니다."),
                region("boxy", 200.0, 500.0, emptyList(), listOf("박스톤", "먹먹함"), "박스톤과 먹먹함의 후보입니다.", "튜닝과 댐핑을 먼저 보고도 남는지 확인합니다.", "많이 깎으면 킥이 얇아집니다."),
            ),
            hpf(25.0, 40.0, null, "25~40 을 견줍니다.", "킥의 기반을 깎게 되기 쉽습니다."),
            lpf(8000.0, 12000.0, true, "기본은 bypass, 필요하면 8~12 kHz 를 시험합니다.", "어택이 함께 줄어듭니다."),
            gate(false, "누음이나 긴 꼬리가 문제일 때 씁니다.", listOf("빠른 더블킥이 모두 열리는지, 저역의 끝이 자연스러운지 확인합니다."), 0.5 to 2.0, 40.0 to 80.0, 80.0 to 180.0, 10.0 to 20.0),
            comp(false, "펀치와 일관성을 얻을 때 씁니다.", listOf("비터가 사라지면 attack 을 다시 봅니다."), 3.0 to 4.0, 15.0 to 35.0, 60.0 to 150.0, 2.0 to 4.0),
            acousticCautions,
        ),
        drumPart(
            "drums.snare", "스네어", listOf("리듬"),
            listOf(
                region("body", 120.0, 250.0, listOf("바디"), emptyList(), "스네어의 몸집입니다.", "단독으로 반복해 듣습니다.", "과하면 둔해집니다."),
                region("clarity", 1000.0, 3000.0, listOf("명료도"), emptyList(), "스네어가 구분되는 곳입니다.", "합주에서 묻히는지 듣습니다.", "보컬과 겹칩니다."),
                region("attack", 2000.0, 5000.0, listOf("어택"), emptyList(), "스틱이 닿는 소리입니다.", "센 타격에서 스틱이 들리는지 듣습니다.", "올리면 어택이 붙지만 자극적으로 들리기 쉽습니다."),
                // 명세 §4.6 은 성격을 2~5k, 증상을 **3~6k** 로 따로 적는다(독립 검증 EQB01).
                region("harsh", 3000.0, 6000.0, emptyList(), listOf("자극"), "찌르듯 들릴 때 조사할 곳입니다.", "센 타격에서 귀를 찌르는지 듣습니다.", "어택이 함께 줄어듭니다."),
                region("wires", 6000.0, 12000.0, listOf("스냅"), emptyList(), "스네어 와이어의 소리입니다.", "고스트노트에서 듣습니다.", "하이햇 누음도 함께 커집니다."),
                region("ring", 300.0, 900.0, emptyList(), listOf("울림", "박스톤"), "울림과 박스톤의 후보입니다.", "튜닝과 댐핑을 먼저 보고도 남는지 확인합니다.", "바디와 겹칩니다."),
            ),
            hpf(60.0, 100.0, null, "킥의 누음을 줄일 때 60~100 을 견줍니다.", "스네어의 바디가 얇아질 수 있습니다."),
            lpf(12000.0, 16000.0, true, "기본은 bypass, 최상단이 거칠면 12~16 kHz 를 시험합니다.", "와이어의 소리가 함께 줄어듭니다."),
            gate(false, "고스트노트 보존이 우선입니다. 누음이 실제로 문제일 때만 씁니다.", listOf("약한 stroke 때문에 bypass 가 나을 수 있습니다."), 0.5 to 2.0, 50.0 to 100.0, 100.0 to 200.0, 6.0 to 12.0),
            comp(false, "타격과 바디의 균형을 잡을 때 씁니다.", listOf("하이햇 누음이 함께 커지는지 확인합니다."), 3.0 to 4.0, 10.0 to 30.0, 80.0 to 180.0, 2.0 to 4.0),
            acousticCautions,
        ),
        drumPart(
            "drums.rack_tom", "랙탐", listOf("리듬", "필인"),
            listOf(
                region("body", 80.0, 180.0, listOf("바디"), emptyList(), "랙탐의 몸통입니다.", "필인을 반복하며 듣습니다.", "스네어와 겹칩니다."),
                region("stick", 2000.0, 5000.0, listOf("어택", "존재감"), emptyList(), "스틱의 존재감입니다.", "빠른 필인에서 음이 구분되는지 듣습니다.", "과하면 딱딱해집니다."),
                region("clutter", 250.0, 600.0, emptyList(), listOf("먹먹함", "긴 공진"), "먹먹함과 긴 공진의 후보입니다.", "댐핑을 먼저 대 보고도 남는지 확인합니다.", "몸통이 함께 사라질 수 있습니다."),
            ),
            hpf(40.0, 60.0, null, "40~60 을 견줍니다.", "탐의 몸통이 얇아질 수 있습니다."),
            lpf(8000.0, 12000.0, true, "기본은 bypass, 필요하면 8~12 kHz 를 시험합니다.", "스틱의 존재감이 함께 줄어듭니다."),
            gate(false, "타격 사이의 누음을 정리할 때 씁니다.", listOf("작은 탐까지 열리는지 확인합니다."), 0.5 to 3.0, 80.0 to 150.0, 150.0 to 300.0, 10.0 to 20.0),
            comp(false, "몸통을 유지하며 레벨을 고를 때 씁니다.", listOf("다음 타격 전에 회복되는지 확인합니다."), 2.0 to 4.0, 15.0 to 35.0, 120.0 to 300.0, 2.0 to 4.0),
            acousticCautions,
        ),
        drumPart(
            "drums.floor_tom", "플로어탐", listOf("리듬", "필인"),
            listOf(
                region("body", 50.0, 120.0, listOf("바디", "펀치"), emptyList(), "플로어탐의 몸통과 타격입니다.", "필인을 반복하며 듣습니다.", "킥과 겹칩니다."),
                region("attack", 2000.0, 4000.0, listOf("어택"), emptyList(), "스틱이 닿는 소리입니다.", "빠른 필인에서 듣습니다.", "과하면 딱딱해집니다."),
                region("boom", 150.0, 400.0, emptyList(), listOf("붕붕거림"), "붕붕거릴 때 조사할 곳입니다.", "댐핑을 먼저 대 보고도 남는지 확인합니다.", "몸통이 함께 사라질 수 있습니다."),
            ),
            hpf(25.0, 40.0, null, "25~40 을 견줍니다.", "저역의 몸통이 얇아질 수 있습니다."),
            lpf(8000.0, 12000.0, true, "기본은 bypass, 필요하면 8~12 kHz 를 시험합니다.", "어택이 함께 줄어듭니다."),
            gate(false, "긴 몸통 울림을 정리할 때 씁니다.", listOf("저역의 decay 를 과도하게 끊지 않습니다."), 0.5 to 3.0, 100.0 to 200.0, 200.0 to 450.0, 10.0 to 20.0),
            comp(false, "몸통을 유지하며 레벨을 고를 때 씁니다.", listOf("다음 타격 전에 회복되는지 확인합니다."), 2.0 to 4.0, 15.0 to 35.0, 120.0 to 300.0, 2.0 to 4.0),
            acousticCautions,
        ),
        drumPart(
            "drums.hihat", "하이햇", listOf("리듬"),
            listOf(
                region("rhythm", 3000.0, 8000.0, listOf("리듬"), emptyList(), "하이햇의 리듬입니다.", "8비트를 반복하며 듣습니다.", "올리면 리듬이 또렷해지지만 쉽게 날카로워집니다."),
                // 명세 §4.6 은 성격을 3~8k, 증상을 **3~7k** 로 따로 적는다(독립 검증 EQB01).
                region("harsh", 3000.0, 7000.0, emptyList(), listOf("날카로움"), "날카롭게 들릴 때 조사할 곳입니다.", "오래 들었을 때 피로한지로 판단합니다.", "리듬의 또렷함이 함께 줄어듭니다."),
                region("bright", 8000.0, 14000.0, listOf("밝기"), emptyList(), "하이햇의 밝기입니다.", "오픈 하이햇에서 듣습니다.", "지속음이 끊기기 쉽습니다."),
            ),
            hpf(150.0, 300.0, null, "다른 파트의 누음을 줄일 때 150~300 을 견줍니다.", "하이햇의 몸통이 사라질 수 있습니다."),
            lpf(12000.0, 16000.0, true, "기본은 bypass, 최상단이 거칠면 12~16 kHz 를 시험합니다.", "밝기가 함께 줄어듭니다."),
            cymbalGate(),
            cymbalComp(),
            acousticCautions,
        ),
        drumPart(
            "drums.overhead", "오버헤드 / 심벌", listOf("리듬", "악센트"),
            listOf(
                region("kit_body", 200.0, 800.0, listOf("키트 몸통"), emptyList(), "오버헤드가 담는 키트 전체의 몸통입니다.", "기본 패턴에서 듣습니다.", "다른 파트의 누음이 함께 들어 있습니다."),
                region("stick", 3000.0, 8000.0, listOf("어택"), emptyList(), "스틱이 닿는 소리입니다.", "라이드에서 듣습니다.", "올리면 스틱이 또렷해지지만 쉽게 찌르는 소리가 됩니다."),
                // 명세 §4.6 은 성격을 3~8k, 증상을 **3~7k** 로 따로 적는다(독립 검증 EQB01).
                region("harsh", 3000.0, 7000.0, emptyList(), listOf("자극", "다른 파트 누음"), "찌르듯 들리거나 다른 파트가 함께 커질 때 조사할 곳입니다.", "스네어·하이햇이 오버헤드로 얼마나 들어오는지 함께 듣습니다.", "스틱의 또렷함이 함께 줄어듭니다."),
                region("air", 8000.0, 16000.0, listOf("공기감"), emptyList(), "심벌의 공기감입니다.", "잔향이 남는 구간에서 듣습니다.", "다른 파트의 누음도 함께 커집니다."),
            ),
            hpf(60.0, 100.0, 150.0 to 250.0, "키트 전체를 담는 역할이면 60~100, 심벌 중심이면 150~250 을 견줍니다.", "키트의 몸통이 함께 사라질 수 있습니다."),
            FilterGuidance(FilterType.LPF, bypassDefault = true, rangeHz = null, conditionKo = "기본은 bypass 입니다.", cautionKo = "공기감이 함께 사라집니다."),
            cymbalGate(),
            cymbalComp(),
            acousticCautions,
        ),
        drumPart(
            "drums.room", "룸", listOf("공간"),
            listOf(
                region("space", 30.0, 16000.0, listOf("공간", "지속음"), listOf("잔향 누적"), "공간의 울림과 지속음입니다. 펀치는 직접음과의 관계로 나타납니다.", "연주를 멈춘 직후의 꼬리를 듣습니다.", "저중역의 잔향과 반사가 누적될 수 있습니다."),
            ),
            hpf(30.0, 60.0, null, "기본은 bypass, 저역 잔향이 쌓이면 30~60 을 견줍니다.", "공간의 두께가 함께 줄어듭니다."),
            FilterGuidance(FilterType.LPF, bypassDefault = true, rangeHz = null, conditionKo = "기본은 bypass 입니다.", cautionKo = "공간의 밝기가 함께 줄어듭니다."),
            cymbalGate(),
            comp(true, "bypass 또는 아주 약하게. 의도적인 효과가 아니라면 공간 과장을 최소화합니다.", listOf("룸 압축은 공간을 과장하기 쉽습니다."), 1.5 to 2.0, 20.0 to 40.0, 100.0 to 300.0, 1.0 to 3.0),
            acousticCautions,
        ),
    )

    private fun drumPart(
        stableId: String, nameKo: String, roles: List<String>,
        regions: List<kr.joa.selahrta.domain.instrument.FrequencyRegion>,
        hpf: FilterGuidance, lpf: FilterGuidance,
        gate: kr.joa.selahrta.domain.instrument.DynamicsGuidance,
        comp: kr.joa.selahrta.domain.instrument.DynamicsGuidance,
        cautions: List<String>,
    ) = profile(
        stableId = stableId,
        type = InstrumentType.ACOUSTIC_DRUMS,
        subtypeId = stableId.substringAfter('.'),
        nameKo = nameKo,
        roles = roles,
        regions = regions,
        hpf = hpf,
        lpf = lpf,
        cautions = cautions,
        sourceIds = listOf(Sources.IZOTOPE_DRUMS, Sources.IZOTOPE_DRUM_COMPRESSION, Sources.IZOTOPE_NOISE_GATES),
        dynamics = listOf(gate, comp),
    )
}
