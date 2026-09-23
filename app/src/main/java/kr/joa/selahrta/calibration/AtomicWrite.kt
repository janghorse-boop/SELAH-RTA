package kr.joa.selahrta.calibration

import java.io.File
import java.io.IOException

/**
 * 임시 파일에 쓴 뒤 옮긴다.
 *
 * 곧바로 덮어쓰다가 중간에 죽으면 반쯤 쓰인 파일이 남는데, 그 파일은
 * 해석은 되면서 점이 모자란 곡선이 되기 쉽다 — 「보정이 걸렸다」고
 * 적히면서 값은 틀린 상태다.
 *
 * **두 벌 두지 않는다.** [CurveStore] 와 [ProfileStore] 가 같은 일을
 * 하는데, 아래 두 가지는 독립 재검증에서 하나씩 짚여 고친 자리다.
 * 복사해 두면 한쪽만 고쳐지고 다른 쪽은 조용히 옛 실수를 지킨다.
 */
internal fun writeAtomically(target: File, text: String) {
    // 임시 이름을 매번 다르게 짓는다. 같은 대상에 두 번 저장이 겹치면
    // 둘이 같은 `.tmp` 에 써서 서로의 내용을 섞는다.
    val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(target)) {
        // **여기서 직접 덮어쓰지 않는다.** 그러면 「원자적으로 쓴다」는
        // 말이 실패 경로에서만 거짓이 되어, 하필 그때 반쯤 쓰인 파일이
        // 남는다. 실패는 실패로 알린다.
        tmp.delete()
        throw IOException("파일을 제자리에 옮기지 못했습니다: ${target.name}")
    }
}
