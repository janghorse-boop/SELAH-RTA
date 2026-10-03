"""1초 간격 5분 시운전 — `2026-10-03-three-obs-pattern.md` 5.1 규칙 그대로의 분석.

**결과를 보기 전에 썼다**(5.1 「스크립트를 먼저 쓴다」). 규칙을 바꾸지 않는다.
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


def load(path):
    lines = open(path, encoding="utf-8").read().splitlines()
    valid = any("SESSION VALID" in l for l in lines)
    invalid = any("SESSION INVALID" in l for l in lines)
    passed = any("RESULT PASS" in l for l in lines)
    obs = []
    for l in lines:
        m = OBS.search(l)
        if m:
            g = m.groups()
            obs.append(dict(session=g[0], epoch=int(g[2]), we=int(g[3]), lag=int(g[4]),
                            found=g[5] == "true", under=int(g[6]), outErr=int(g[7]),
                            inErr=int(g[8]), route=g[9] == "true"))
    return valid and not invalid, passed, obs


def segments(obs):
    """규칙 2 — 세션·epoch·경로·언더런·오류·순서에서 가른다. 가로질러 맞추지 않는다."""
    segs, cur, prev = [], [], None
    for o in obs:
        brk = prev is not None and (
            o["session"] != prev["session"] or o["epoch"] != prev["epoch"] or o["route"]
            or o["under"] != prev["under"] or o["outErr"] != prev["outErr"]
            or o["inErr"] != prev["inErr"] or o["we"] < prev["we"]
        )
        if brk and cur:
            segs.append(cur)
            cur = []
        cur.append(o)
        prev = o
    if cur:
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
    n_obs = len(seg)
    found = [o for o in seg if o["found"]]
    seen, uniq, dup = set(), [], 0
    for o in found:  # 규칙 3 — 같은 windowEnd 는 처음 것만
        if o["we"] in seen:
            dup += 1
            continue
        seen.add(o["we"])
        uniq.append(o)
    print("== 구간 #%d" % k)
    print("  관측 %d · 찾음 %d · 서로 다른 찾은 창 %d · 중복 %d · 못 찾음 %d"
          % (n_obs, len(found), len(uniq), dup, n_obs - len(found)))
    if len(uniq) < 2:
        print("  분석 보류 — 찾은 창이 둘 미만")
        return
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
    if hold:
        print("  분석 보류 —", " / ".join(hold))
        return
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
    names = ["대역 안 최적", "다음 국소 최대", "그다음 국소 최대"]
    for name, (f, r2, amp) in zip(names, peaks[:3]):
        near = []
        for day, cs in CANDIDATES.items():
            c = min(cs, key=lambda p: abs(p - 1 / f))
            near.append("%s %.3f초" % (day, c))
        print("  %s: f=%.4f Hz · 주기 %.3f초 · 진폭 %.2f표본 · R²=%.3f(고르기 효과로 부풀었다) · 가까운 3.3 후보: %s"
              % (name, f, 1 / f, amp, r2, ", ".join(near)))
    print("  원인·참 주기·대역 밖 접힘의 배제는 적지 않는다. 「표본 좌표 폭 %.1f초 안에서 그 대역의 최적」까지만." % span)


def main():
    path = sys.argv[1]
    valid, passed, obs = load(path)
    print("파일", path, "· SESSION VALID=%s · RESULT PASS=%s · OBS %d" % (valid, passed, len(obs)))
    if not (valid and passed):
        print("규칙 1 — 해석하지 않는다")
        return
    segs = segments(obs)
    print("구간 %d개(세션·epoch·경로·언더런·오류·순서에서 가름)" % len(segs))
    for k, s in enumerate(segs):
        analyze(s, k)


if __name__ == "__main__":
    main()
