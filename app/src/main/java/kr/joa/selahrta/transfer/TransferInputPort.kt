package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.AudioBlock
import kr.joa.selahrta.audio.RouteSnapshot
import kr.joa.selahrta.dsp.BlockStats

/**
 * 측정 입력이 Transfer Function 으로 가는 통로(TF 설계 3.3). `CaptureController` 가 넘긴다.
 *
 * 블록은 **캡처 스레드**에서, 나머지는 **주 스레드**에서 온다. 받는 쪽은 블록을 엔진에 넣기·reset·
 * 무장을 한 자물쇠 안에서 다룬다([TransferIngest]).
 */
interface TransferInputPort {
    /** 거르기 전의 원시 경로 통지. 주 스레드. */
    fun onRawNotice(captureId: Long) = Unit

    /** 통지마다 새로 조회한 경로. 주 스레드. */
    fun onSnapshot(snapshot: RouteSnapshot) = Unit

    /** 측정 블록. **캡처 스레드.** [block] 의 배열은 다음 블록에서 다시 쓰이므로 이 호출 안에서만 읽는다. */
    fun onBlock(captureId: Long, block: AudioBlock, stats: BlockStats) = Unit

    /** 캡처가 끝났다(오류·멈춤·다른 입력으로 다시 엶). 주 스레드. */
    fun onCaptureEnded(captureId: Long) = Unit
}
