"""1초 간격 5분 시운전 — `2026-10-03-three-obs-pattern.md` 5.1 규칙 그대로의 분석.

**결과를 보기 전에 썼다**(5.1 「스크립트를 먼저 쓴다」, 첫 판 `c62abdc`). 규칙을 바꾸지 않는다.
28회차 R28-01·02 로 **판정을 세션마다** 걸고 **`Measured` 가 아닌 줄을 순서 비교에서 뺐다** —
분석 규칙(5.1)은 그대로이고, 기존 원자료 넷의 출력 숫자는 바뀌지 않았다.
시험: `python docs/verify/2026-10-03-dense-trial-analysis-test.py`
돌리기: 저장소 뿌리에서 `PYTHONIOENCODING=utf-8 python docs/verify/2026-10-03-dense-trial-analysis.py <ADRIFT 로그>`

숫자는 사후 기술 통계다. 원인·참 주기·대역 밖 접힘의 배제를 적지 않는다.
"""
import math
import re
import statistics
import sys

FS = 48000.0
MIN_DISTINCT = 200
MIN_STEP = 32768
MAX_GAP_S = 5.0
F_LO = 0.005
F_STEP = 0.0001

# 3.3 의 근사 후보(실제 시각으로 다시 맞춘 주기, 초) — 「가까운 것」을 적을 때만 쓴다.
CANDIDATES = {
    "오늘 184a7daf": [28.868, 15.417, 7.454, 6.083, 4.280, 3.789, 3.001, 2.752, 2.311, 2.160, 1.879],
    "어제 2776783f": [31.221, 14.820, 7.601, 5.988, 4.328, 3.752, 3.025, 2.732, 2.325, 2.148, 1.888],
}

OBS = re.compile(
    r"OBS session=(\S+) kind=(\S+) epoch=(-?\d+) windowEnd=(\d+) lag=(-?\d+) found=(\w+) "
    r".*underruns=(-?\d+) outErr=(\d+) inErr=(\d+) routeChanged=(\w+)"
)


HEAD = re.compile(r"HEAD session=(\S+)")


def load(path):
    """판정 줄은 **그 세션의** 것으로 묶는다(28회차 R28-01).

    ADRIFT 로그에서 `SESSION VALID/INVALID`·`RESULT PASS/FAIL` 줄에는 세션 이름이 없다.
    그래서 바로 앞의 `HEAD session=` 줄의 세션에 붙인다. 처음엔 파일 어딘가에 PASS 한 줄만
    있으면 파일 전체를 받아, 여러 세션이 섞인 로그에서 실패 세션까지 분석했다.
    """
    verdicts, obs, cur = {}, [], None
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    for l in lines:
        h = HEAD.search(l)
        if h:
            cur = h.group(1)
            verdicts.setdefault(cur, set())
            continue
        m = OBS.search(l)
        if m:
            g = m.groups()
            obs.append(dict(session=g[0], kind=g[1], epoch=int(g[2]), we=int(g[3]), lag=int(g[4]),
                            found=g[5] == "true", under=int(g[6]), outErr=int(g[7]),
                            inErr=int(g[8]), route=g[9] == "true"))
            continue
        for word in ("SESSION VALID", "SESSION INVALID", "RESULT PASS", "RESULT FAIL"):
            if word in l and cur is not None:
                verdicts[cur].add(word)
    return verdicts, obs


def accepted(v):
    """규칙 1 — 그 세션이 VALID·PASS 이고 INVALID·FAIL 이 없을 때만."""
    return {"SESSION VALID", "RESULT PASS"} <= v and not ({"SESSION INVALID", "RESULT FAIL"} & v)


def new_seg():
    return dict(rows=0, skipped={}, notfound=0, dup=0, kept=[], reason=None)


def segments(obs):
    """규칙 2·3 — `DriftLogAnalyzer.analyze` 와 같은 차례로 가른다(한 세션 안).

    - 사건(경로 바뀜·언더런·출력/입력 오류의 누계 증가)은 **종류와 상관없이** 모든 줄에서
      보고, 다음에 구간에 넣는 관측에서 끊는다.
    - `Measured` 가 아닌 줄은 종류별로 세고 **버린다** — 그 줄의 `windowEnd` 는 임시값(0)이라
      순서 비교에 넣지 않는다(28회차 R28-02. 처음엔 그 0 을 역행으로 읽어 정상 구간을 갈랐다).
    - 못 찾은 관측·같은 (epoch, windowEnd) 의 거듭은 세고 버린다.
    - 순서는 **마지막으로 넣은 관측**과 견준다(`windowEnd` 가 같거나 작으면 끊는다).
    버린 줄은 그 순간 열려 있는 구간의 개수에 센다.
    """
    segs, cur = [], new_seg()
    last, last_kept, pending, seen = None, None, None, set()
    for o in obs:
        if o["route"]:
            pending = pending or "경로 바뀜"
        if last is not None:
            if o["under"] > last["under"]:
                pending = pending or "출력 언더런"
            if o["outErr"] > last["outErr"]:
                pending = pending or "출력 오류"
            if o["inErr"] > last["inErr"]:
                pending = pending or "입력 오류"
        last = o
        if o["kind"] != "Measured":
            cur["rows"] += 1
            cur["skipped"][o["kind"]] = cur["skipped"].get(o["kind"], 0) + 1
            continue
        if not o["found"]:
            cur["rows"] += 1
            cur["notfound"] += 1
            continue
        key = (o["epoch"], o["we"])
        if key in seen:
            cur["rows"] += 1
            cur["dup"] += 1
            continue
        seen.add(key)
        reason = None
        if last_kept is not None:
            if o["epoch"] != last_kept["epoch"]:
                reason = "epoch 바뀜"
            elif pending:
                reason = pending
            elif o["we"] <= last_kept["we"]:
                reason = "순서 어긋남"
        if reason:
            segs.append(cur)
            cur = new_seg()
            cur["reason"] = reason
        pending = None
        cur["rows"] += 1
        cur["kept"].append(o)
        last_kept = o
    if cur["rows"]:
        segs.append(cur)
    return segs


def detrend(t, y):
    n = len(t)
    tm, ym = sum(t) / n, sum(y) / n
    sxx = sum((a - tm) ** 2 for a in t)
    b = sum((a - tm) * (c - ym) for a, c in zip(t, y)) / sxx
    return [c - ym - b * (a - tm) for a, c in zip(t, y)]


def sine_fit(t, r, f):
    w = 2 * math.pi * f
    C = [math.cos(w * a) for a in t]
    S = [math.sin(w * a) for a in t]
    cc = sum(c * c for c in C); ss = sum(s * s for s in S); cs = sum(c * s for c, s in zip(C, S))
    yc = sum(v * c for v, c in zip(r, C)); ys = sum(v * s for v, s in zip(r, S))
    det = cc * ss - cs * cs
    if det <= 0:
        return 0.0, 0.0
    A = (yc * ss - ys * cs) / det; B = (ys * cc - yc * cs) / det
    fit = sum((A * c + B * s) ** 2 for c, s in zip(C, S))
    return fit / sum(v * v for v in r), math.hypot(A, B)


def analyze(seg, k):
    """한 구간을 5.1 대로. 출력하고, 시험이 볼 수 있게 결과를 dict 로 돌려준다."""
    uniq = seg["kept"]
    found = len(uniq) + seg["dup"]
    skipped = " · ".join("%s %d" % kv for kv in sorted(seg["skipped"].items())) or "없음"
    print("== 구간 #%d%s" % (k, "" if seg["reason"] is None else " (시작 까닭: %s)" % seg["reason"]))
    print("  관측 %d · 측정 아님 %s · 찾음 %d · 서로 다른 찾은 창 %d · 중복 %d · 못 찾음 %d"
          % (seg["rows"], skipped, found, len(uniq), seg["dup"], seg["notfound"]))
    out = dict(distinct=len(uniq), hold=[], best=None)
    if len(uniq) < 2:
        out["hold"].append("찾은 창이 둘 미만")
        print("  분석 보류 — 찾은 창이 둘 미만")
        return out
    we = [o["we"] for o in uniq]
    steps = [b - a for a, b in zip(we, we[1:])]
    span = (we[-1] - we[0]) / FS
    gap = max(steps) / FS
    print("  표본 좌표 시간 폭 %.3f초 · 최대 공백 %.3f초 · 걸음 최소 %d · 중앙값 %d · 최대 %d"
          % (span, gap, min(steps), statistics.median(steps), max(steps)))
    hold = []
    if len(uniq) < MIN_DISTINCT:
        hold.append("찾은 서로 다른 창 %d < %d" % (len(uniq), MIN_DISTINCT))
    if min(steps) < MIN_STEP:
        hold.append("걸음 %d < %d(지연 창 겹침)" % (min(steps), MIN_STEP))
    if gap > MAX_GAP_S:
        hold.append("최대 공백 %.3f초 > %.1f초" % (gap, MAX_GAP_S))
    t = [w / FS for w in we]
    lag = [o["lag"] for o in uniq]
    res = detrend(t, lag)
    if sum(v * v for v in res) == 0:
        hold.append("직선 잔차 제곱합 0 — 주기 있음·없음을 적지 않는다")
    out.update(span=span, gap=gap, min_step=min(steps))
    if hold:
        out["hold"] = hold
        print("  분석 보류 —", " / ".join(hold))
        return out
    f_hi = 1.0 / (2.0 * statistics.median(steps) / FS)
    print("  지연 %d~%d · 탐색 대역 %.4f~%.4f Hz(상한은 등간격 근사에서 빌린 탐색 상한), 걸음 %.4f Hz"
          % (min(lag), max(lag), F_LO, f_hi, F_STEP))
    curve = []
    i = 0
    while F_LO + i * F_STEP <= f_hi + 1e-12:
        f = F_LO + i * F_STEP
        r2, amp = sine_fit(t, res, f)
        curve.append((f, r2, amp))
        i += 1
    # 국소 최대(양 끝 포함). R² 같으면 낮은 주파수 먼저.
    peaks = []
    for j, (f, r2, amp) in enumerate(curve):
        left = curve[j - 1][1] if j > 0 else -1
        right = curve[j + 1][1] if j + 1 < len(curve) else -1
        if r2 >= left and r2 >= right:
            peaks.append((f, r2, amp))
    peaks.sort(key=lambda p: (-p[1], p[0]))
    out["best"] = peaks[0] if peaks else None
    names = ["대역 안 최적", "다음 국소 최대", "그다음 국소 최대"]
    for name, (f, r2, amp) in zip(names, peaks[:3]):
        near = []
        for day, cs in CANDIDATES.items():
            c = min(cs, key=lambda p: abs(p - 1 / f))
            near.append("%s %.3f초" % (day, c))
        print("  %s: f=%.4f Hz · 주기 %.3f초 · 진폭 %.2f표본 · R²=%.3f(고르기 효과로 부풀었다) · 가까운 3.3 후보: %s"
              % (name, f, 1 / f, amp, r2, ", ".join(near)))
    print("  원인·참 주기·대역 밖 접힘의 배제는 적지 않는다. 「표본 좌표 폭 %.1f초 안에서 그 대역의 최적」까지만." % span)
    return out


def run(path):
    """세션마다 규칙 1 을 따로 걸고, 받은 세션만 구간으로 가른다. {세션: [구간 결과]} 를 돌려준다."""
    verdicts, obs = load(path)
    print("파일", path, "· OBS %d · 세션 %d" % (len(obs), len({o["session"] for o in obs})))
    results = {}
    for session in dict.fromkeys(o["session"] for o in obs):
        v = verdicts.get(session, set())
        print("# 세션 %s · 판정 %s" % (session, " · ".join(sorted(v)) or "없음"))
        if not accepted(v):
            print("  규칙 1 — 해석하지 않는다")
            results[session] = None
            continue
        segs = segments([o for o in obs if o["session"] == session])
        print("  구간 %d개(epoch·경로·언더런·오류·순서에서 가름)" % len(segs))
        results[session] = [analyze(sg, k) for k, sg in enumerate(segs)]
    return results


def main():
    run(sys.argv[1])


if __name__ == "__main__":
    main()
