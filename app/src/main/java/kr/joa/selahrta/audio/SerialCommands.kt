package kr.joa.selahrta.audio

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * **소리 명령을 한 줄로 세우고, 끌리는 동안 쌓인 낡은 것은 버린다**
 * (독립 검토 8회차 3장, 2026-09-29).
 *
 * ## 왜 필요한가 — 실기기에서 잰 값
 *
 * 세기·좌우·대역 주파수 슬라이더는 모두 소리를 다시 튼다. 그 일이
 * 주 스레드에서 돌면 **손가락을 끄는 동안 화면이 통째로 멎는다.**
 * SM-S918N 에서 잰 값은 대역 슬라이더 한 번에 **144ms**(최대 154ms),
 * 스무 번 끌면 **합 2.36초**였다. 한 장이 16.7ms 이니 한 번에 아홉 장이다.
 *
 * ## 왜 하나짜리 실행자인가
 *
 * 명령마다 따로 스레드를 띄우면 `start` 와 `stop` 이 서로를 앞질러
 * 단일 제어 계약이 깨진다. 한 줄로 세우면 부른 차례가 그대로 지켜진다.
 *
 * ## 왜 낡은 것을 버리는가
 *
 * 줄에 세우기만 하면 화면은 안 멎지만 **소리가 손가락을 뒤쫓는다** —
 * 스무 개가 줄을 서서 하나에 144ms 씩, 손을 뗀 뒤로도 2.4초를 더 돈다.
 * 사람이 듣고 싶은 것은 **손을 뗀 그 자리**다.
 *
 * 버려진 `stop` 도 안전하다 — 뒤따르는 `start` 가 제 안에서 먼저 멈춘다.
 *
 * ## 이것이 하지 않는 일
 *
 * 무엇이 안전한 명령인지 모른다. 그저 **마지막 것만 남긴다.** 하나하나가
 * 반드시 실행돼야 하는 일에는 쓰면 안 된다.
 */
class SerialCommands(
    threadName: String,
    /** 시험이 갈아 끼울 수 있게 밖에서도 받는다. */
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, threadName).apply { isDaemon = true }
    },
) {
    private val seq = AtomicLong()

    /** 줄에 세운다. 이 뒤에 더 새 명령이 오면 **이것은 하지 않는다.** */
    fun post(block: () -> Unit) {
        val mine = seq.incrementAndGet()
        // **이미 닫힌 뒤에 들어오는 것은 조용히 버린다.** 앱이 꺼지는
        // 길에서 마지막 명령이 터지면 그것이 사람이 보는 마지막 화면이 된다.
        runCatching {
            executor.execute { if (mine == seq.get()) block() }
        }
    }

    /** 더 받지 않는다. 돌고 있는 것은 끊는다. */
    fun shutdownNow() {
        (executor as? ExecutorService)?.shutdownNow()
    }
}
