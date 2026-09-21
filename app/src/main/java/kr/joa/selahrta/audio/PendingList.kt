package kr.joa.selahrta.audio

/**
 * 끝나기를 기다리는 것들의 목록. **모든 갱신을 하나의 짧은 자물쇠로
 * 직렬화한다.**
 *
 * **왜 따로 뒀는가** — `CopyOnWriteArrayList` 를 쓰다가 앱이 죽을 수 있는
 * 자리를 만들었다(독립 검증 RC01). 낱개 연산이 안전한 것과 **복합 연산이
 * 안전한 것은 다른 이야기**인데 그것을 섞어 생각했다:
 *
 * ```
 * java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0
 *   java.util.concurrent.CopyOnWriteArrayList.remove
 *   kotlin.collections.CollectionsKt__MutableCollectionsKt.filterInPlace
 *   kotlin.collections.CollectionsKt__MutableCollectionsKt.removeAll
 *   kr.joa.selahrta.audio.SignalPlayer.start
 * ```
 *
 * Kotlin 의 `removeAll { }` 은 RandomAccess 목록에서 **번호로 읽고 지우는**
 * 복합 연산이다. 그 사이에 다른 스레드가 하나를 지우면 번호가 어긋난다.
 * 주 스레드의 `start()` 에서 터지므로 **앱이 죽는다.**
 *
 * 그래서 목록을 따로 떼어, 자물쇠 하나로 묶고 **그 안에서 기다리지
 * 않는다** — `release`·`stop`·`join` 은 전부 밖에서 한다.
 */
internal class PendingList<T : Any> {

    private val lock = Any()
    private val items = ArrayList<T>()

    val size: Int get() = synchronized(lock) { items.size }

    /**
     * [isDone] 인 것을 치우고 **남은 수**를 돌려준다.
     *
     * [isDone] 은 이 자물쇠 안에서 불린다. **값만 읽는 가벼운 것**이어야
     * 한다 — 여기서 기다리면 새 재생과 화면이 함께 막힌다.
     */
    fun sweep(isDone: (T) -> Boolean): Int = synchronized(lock) {
        val i = items.iterator()
        while (i.hasNext()) if (isDone(i.next())) i.remove()
        items.size
    }

    /**
     * 아직 안 끝났으면 넣는다.
     *
     * 넣을지 말지를 **같은 자물쇠 안에서** 판단한다. 밖에서 보고 넣으면,
     * 보는 사이에 끝난 것이 목록에 남아 세는 수가 실제보다 커진다.
     */
    fun addIfPending(item: T, isDone: (T) -> Boolean): Boolean = synchronized(lock) {
        if (isDone(item)) return@synchronized false
        if (!items.contains(item)) items.add(item)
        true
    }

    fun remove(item: T) {
        synchronized(lock) { items.remove(item) }
    }

    fun count(predicate: (T) -> Boolean): Int = synchronized(lock) { items.count(predicate) }
}
