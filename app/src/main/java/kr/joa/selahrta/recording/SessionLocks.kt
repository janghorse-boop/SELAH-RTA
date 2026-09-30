package kr.joa.selahrta.recording

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * **한 기록을 한 번에 하나만 만진다**(독립 검토 R5-01).
 *
 * ## 무엇이 어긋났나
 *
 * 다시 분석은 **분석 전에 옛 겉장을 읽고, 분석이 끝난 뒤 그 옛 겉장을
 * 바탕으로 새 겉장을 게시한다.** 그 사이가 몇 분이다. 그 사이에 메모를
 * 적으면 **저장은 성공했다고 답하고** 곧이어 게시가 옛 겉장으로 덮는다.
 *
 * 반례를 그대로 돌려 봤다:
 *
 * ```
 * R5_MEMO saveAcknowledged=true finalMemo=
 * ```
 *
 * 사람은 「저장했습니다」를 봤고, 메모는 없다. **화면에는 아무 표시도
 * 안 난다.**
 *
 * 앞 회차(R4-02)에 저널을 넣어 **재시작 뒤의 어긋남**은 닫았지만, 이것은
 * 그것과 다른 결이다 — **돌아가는 중의 경합**이라 저널로는 안 닫힌다.
 *
 * ## 왜 세션 단위인가
 *
 * 저장소 전체를 잠그면 **다른 기록의 메모 한 줄이 두 시간짜리 재분석을
 * 기다린다.** 반대로 메서드마다 잠그면 **읽고-고쳐-쓰기가 쪼개져** 지금
 * 결함이 그대로 남는다. 경계는 「기록 하나」다.
 *
 * ## 열쇠는 **폴더의 실제 경로 + 기록 번호**
 *
 * 같은 폴더를 가리키는 [SessionStore] 가 둘 생길 수 있다(화면과 내보내기가
 * 따로 만든다). 인스턴스에 잠금을 달면 **둘이 서로 다른 잠금을 쥐어**
 * 아무것도 막지 못한다. 그래서 **프로세스에 하나**만 둔다.
 *
 * 경로는 `canonicalPath` 로 정규화한다 — `sessions/` 와 `sessions/./` 가
 * 다른 열쇠가 되면 역시 막지 못한다.
 *
 * ## 잠금을 **버리지 않는다**
 *
 * 기다리는 사람이 있는데 잠금을 치우면 **다음 사람이 새 잠금을 쥐고
 * 들어온다** — 둘이 동시에 돈다. 기록 수만큼만 쌓이고 하나가 수십 바이트라
 * 그냥 둔다.
 *
 * ## 재진입이 필요하다
 *
 * `readSnapshot` 안에서 `recover` 를 부르고, 다시 분석은 그 둘을 다시
 * 감싼다. 재진입이 안 되면 **제 잠금에 제가 걸려** 그대로 멎는다.
 */
internal object SessionLocks {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun of(root: File, id: String): ReentrantLock =
        locks.computeIfAbsent(keyOf(root, id)) { ReentrantLock() }

    /**
     * 같은 폴더·같은 기록이면 **같은 문자열**이어야 한다.
     *
     * `canonicalPath` 가 실패하는 자리가 있어(지워진 폴더 등) 그때는
     * `absolutePath` 로 내려간다. 정규화가 덜 된 열쇠라도 **없는 것보다는
     * 낫다** — 같은 문자열을 쓰는 쪽끼리는 여전히 막힌다.
     */
    private fun keyOf(root: File, id: String): String {
        val base = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        // 기록 번호에 경로 구분자가 섞여도 열쇠가 겹치지 않도록 NUL 로 나눈다.
        return "$base\u0000$id"
    }
}
