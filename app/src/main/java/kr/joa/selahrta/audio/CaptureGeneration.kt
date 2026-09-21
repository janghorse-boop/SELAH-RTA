package kr.joa.selahrta.audio

/**
 * 측정 세대를 매기고, **어느 세대의 소식을 받아들일지** 가린다.
 *
 * 오디오 쪽에서 오는 소식(덩어리·경로 확인·종료)은 늦게 도착할 수 있다.
 * 500ms 안에 안 끝난 작업 스레드는 그대로 두고 자원을 놓기 때문이다.
 * 그 늦은 소식을 그냥 받으면:
 *
 * - 이미 끝난 측정의 덩어리가 **다음 측정의 엔진**으로 들어가고(F02),
 * - 늦게 온 경로 확인이 **방금 지운 보정을 다시 구독**하며(F04),
 * - 늦게 온 읽기 오류가 **사람이 정상적으로 멈춘 것을 실패로 뒤집는다**(F04).
 *
 * 규칙은 둘뿐이다. **멈추면 아무도 받아들이지 않는다**, 그리고 **새로
 * 시작하면 이전 세대는 받아들이지 않는다.** 이 클래스는 그 둘만 지킨다.
 */
class CaptureGeneration {

    /** 지금 받아들이는 세대. 멈춰 있으면 [NONE]. */
    var current: Long = NONE
        private set

    private var counter: Long = NONE

    /** 새 세대를 연다. 그 번호를 돌려준다. */
    fun begin(): Long {
        counter++
        current = counter
        return counter
    }

    /** 지금 세대를 닫는다. 이후로는 어떤 소식도 받아들이지 않는다. */
    fun end() {
        current = NONE
    }

    /** 이 세대의 소식을 지금 받아들여도 되는가. */
    fun accepts(generation: Long): Boolean = generation != NONE && generation == current

    /** 돌고 있는가. */
    val running: Boolean get() = current != NONE

    companion object {
        /** 「아무 세대도 아님」. 멈춘 상태이며, 어떤 소식도 이 번호와 같지 않다. */
        const val NONE = 0L
    }
}
