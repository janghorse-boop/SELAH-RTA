package kr.joa.selahrta.audio

/**
 * 입력 경로 통지 **하나마다** 그 자리에서 다시 조회한 경로(TF 설계 3.3, 32회차 R32-03).
 *
 * `MicSource` 의 기존 콜백(`onRoutingChanged`·`onRouteConfirmed`)은 기기·주소·조합을 견줘 **같으면
 * 생략**한다. 그래서 늦게 처리된 A→B→A·조합 22→24→22 뒤에는 새 확인이 오지 않는다. 이것은 같아도
 * 늘 나온다 — 받는 쪽(Transfer Function)이 통지 뒤 경로를 스스로 다시 판단하게 한다.
 *
 * @param captureId `MicSource` 의 열기마다 바뀌는 번호. 옛 캡처의 늦은 스냅샷을 가린다.
 * @param noticeSeq 그 캡처 안에서 통지마다 +1.
 * @param format 그 자리에서 다시 조회한 경로. 경로를 모르면 null. **조회만 했고 `MicSource` 의 상태는
 *   바꾸지 않았다**(기존 거르기 비교를 흔들지 않으려는 것).
 * @param readSeqAtSnapshot 스냅샷을 뜬 순간까지 캡처 스레드가 **예약한** 읽기 번호
 *   ([AudioBlock.readSeq]). 이 번호 이하의 블록은 통지 전에 읽기 시작한 것이다.
 */
data class RouteSnapshot(
    val captureId: Long,
    val noticeSeq: Long,
    val format: OpenedFormat?,
    val readSeqAtSnapshot: Long,
)
