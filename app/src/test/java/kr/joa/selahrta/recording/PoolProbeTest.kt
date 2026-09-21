package kr.joa.selahrta.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier

/**
 * **검증자 probe 그대로**(`docs/review/EQB-service-PoolProbe.kt`).
 *
 * 검증자가 보낸 `fun main()` 의 본문을 손대지 않고 옮겼다. 바꾼 것은
 * JUnit 으로 돌리기 위한 껍데기와, **아래에 적은 단언 두 줄**뿐이다.
 * 고치기 **전에** 결함이 이 코드로 재현되는 것을 먼저 확인하려는 것이다.
 *
 * 검증자가 받은 출력:
 *
 * ```
 * CONCURRENT_RETURN rounds=30000 lostReturns=1
 * DOUBLE_RETURN_WITH_OTHER_HELD rejected=false next=0,0
 * ```
 *
 * 두 가지를 말한다:
 *
 * 1. **두 스레드가 동시에 돌려주면 반환이 사라진다.** `release` 가
 *    `given` 을 읽고-쓰는 복합 갱신이라, 단일 반환자를 전제한다.
 *    그런데 내가 적은 `offer()` 예시는 복사 실패·닫힘에서 **생산자가**
 *    돌려준다 — 그 전제를 내가 깼다.
 * 2. **같은 번호를 두 번 돌려줘도 막지 못한다.** 개수만 보고 번호를
 *    보지 않았다.
 *
 * ## 고친 뒤 이 probe 가 무엇을 말하고 무엇을 말하지 않는가
 *
 * | 출력 | 고친 뒤 | 단언하는가 |
 * |---|---|---|
 * | `DOUBLE_RETURN_WITH_OTHER_HELD rejected` | `true`, `next=0,-1` | **한다** |
 * | `CONCURRENT_RETURN lostReturns` | 0~3 사이를 오간다 | **안 한다** |
 *
 * **두 번째는 여전히 0 이 아니다.** 처음 돌렸을 때 0 이 나와 「고쳐졌다」고
 * 적을 뻔했는데, 다섯 번 돌려 보니 `0, 0, 2, 3, 2` 였다. [BufferPool.release]
 * 는 **지금도 두 스레드가 부르면 반환을 잃는다.**
 *
 * 고쳐진 것은 `release` 가 아니라 **제품이 두 스레드에서 부르지 않게 된
 * 것**이다(생산자는 [BufferPool.cancel] 을 쓴다). 그러니 이 probe 의
 * 첫 줄은 **규약 밖의 사용**을 재현하는 것이고, 회귀 시험이 될 수 없다.
 * 규약을 지키는 회귀 시험은
 * `PacketPipeTest.writer 가 돌려주는 순간 생산자가 물러나도 잃지 않는다`
 * 에 있고, 거기서는 「잃었는가」가 아니라 **「생산자가 free-ring 을
 * 건드렸는가」**를 본다 — 그래야 타이밍에 기대지 않는다.
 */
class PoolProbeTest {

    @Test
    fun `검증자 probe`() {
        var pool = BufferPool(2, 1)
        val b = CyclicBarrier(3)
        val rounds = 30000
        val threads = (0..1).map { i ->
            Thread { repeat(rounds) { b.await(); runCatching { pool.release(i) }; b.await() } }
                .apply { start() }
        }
        var lost = 0
        repeat(rounds) {
            pool = BufferPool(2, 1)
            check(pool.acquire() == 0); check(pool.acquire() == 1)
            b.await(); b.await()
            if (pool.inUse != 0) lost++
        }
        threads.forEach { it.join() }
        println("CONCURRENT_RETURN rounds=$rounds lostReturns=$lost")

        val p = BufferPool(2, 1)
        val x = p.acquire()
        p.acquire()
        p.release(x)
        val rejected = runCatching { p.release(x) }.isFailure
        println("DOUBLE_RETURN_WITH_OTHER_HELD rejected=$rejected next=${p.acquire()},${p.acquire()}")

        // --- 여기부터가 더한 부분 ---
        // 검증자 출력은 사람이 읽는 기록일 뿐이다. 시험이 통과했다는 말이
        // 뜻을 가지려면 단언이 있어야 한다.
        assertTrue("같은 번호를 두 번 돌려주는 것을 막지 못한다", rejected)

        val q = BufferPool(2, 1)
        val y = q.acquire()
        q.acquire()
        q.release(y)
        runCatching { q.release(y) }
        assertEquals("두 번 돌려준 뒤 같은 번호를 다시 내준다", y, q.acquire())
        assertEquals("남은 것이 없는데 더 내준다", -1, q.acquire())

        // `lost` 는 단언하지 않는다. 규약 밖(두 스레드가 release)이라
        // 지금도 0 이 아니고, 0 이 나오는 날은 운이 좋았을 뿐이다.
    }
}
