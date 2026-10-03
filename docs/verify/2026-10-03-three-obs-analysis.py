"""3관측 간격 되풀이 — 원자료 분석 (2026-10-03).

기록: docs/verify/2026-10-03-three-obs-pattern.md
돌리기: 저장소 뿌리에서 `PYTHONIOENCODING=utf-8 python docs/verify/2026-10-03-three-obs-analysis.py`
(윈도 콘솔은 cp949 라 한글이 깨진다 — 숫자는 같다)

원자료를 고치지 않는다. 숫자는 사후 기술 통계이고 판정이 아니다.
"""
import math
import re
from collections import Counter, defaultdict

RECORDS = [
    "docs/verify/2026-10-03-device-remeasure-data/record30-184a7daf.txt",
    "docs/verify/2026-10-02-acoustic-drift-data/record30-2776783f.txt",
]
TIMESTAMP = "docs/verify/2026-10-03-device-remeasure-data/timestamp30.txt"
FS = 48000.0
OBS = re.compile(r" OBS .*windowEnd=(\d+) lag=(-?\d+) found=(\w+).*mono=(\d+)")


def read_obs(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = OBS.search(line)
            if m:
                rows.append((int(m.group(1)), int(m.group(2)), m.group(3) == "true", int(m.group(4))))
    return rows  # (windowEnd, lag, found, mono) — 원래 슬롯 차례


def detrend(t, y):
    n = len(t)
    tm, ym = sum(t) / n, sum(y) / n
    b = sum((a - tm) * (c - ym) for a, c in zip(t, y)) / sum((a - tm) ** 2 for a in t)
    return [c - ym - b * (a - tm) for a, c in zip(t, y)], b


def sine_fit(t, r, f):
    w = 2 * math.pi * f
    C = [math.cos(w * a) for a in t]
    S = [math.sin(w * a) for a in t]
    cc = sum(c * c for c in C); ss = sum(s * s for s in S); cs = sum(c * s for c, s in zip(C, S))
    yc = sum(v * c for v, c in zip(r, C)); ys = sum(v * s for v, s in zip(r, S))
    det = cc * ss - cs * cs
    A = (yc * ss - ys * cs) / det; B = (ys * cc - yc * cs) / det
    fit = sum((A * c + B * s) ** 2 for c, s in zip(C, S))
    return fit / sum(v * v for v in r), math.hypot(A, B)


for path in RECORDS:
    rows = read_obs(path)
    print("==", path.split("/")[-1], "관측", len(rows), "찾음", sum(r[2] for r in rows))

    # 1) 슬롯 차례 mod 3 — 30관측씩 여섯 토막
    for part in range(6):
        d = defaultdict(list)
        for i, r in enumerate(rows):
            if r[2] and part * 30 <= i < (part + 1) * 30:
                d[i % 3].append(r[1])
        print("  토막", part, {k: round(sum(v) / len(v), 2) for k, v in sorted(d.items())})

    # 2) windowEnd 걸음(1024 단위)별 평균 지연
    d = defaultdict(list)
    for a, b in zip(rows, rows[1:]):
        if b[2]:
            d[(b[0] - a[0]) // 1024].append(b[1])
    print("  걸음별:", {k: (len(v), round(sum(v) / len(v), 2)) for k, v in sorted(d.items())})
    print("  windowEnd % 1024 값:", Counter(r[0] % 1024 for r in rows))
    mi = [(b[3] - a[3]) / 1e9 for a, b in zip(rows, rows[1:])]
    print("  관측 간격(mono) 평균 %.4f초 최소 %.3f 최대 %.3f" % (sum(mi) / len(mi), min(mi), max(mi)))

    # 3) 찾은 관측의 직선 잔차에 사인 하나 — 0.0005~0.0498 Hz 훑기
    found = [r for r in rows if r[2]]
    t = [r[0] / FS for r in found]
    res, _ = detrend(t, [r[1] for r in found])
    best = max((sine_fit(t, res, 0.0005 + k * 1e-5) + (0.0005 + k * 1e-5,) for k in range(4930)))
    r2, amp, f = best
    ts = sum(mi) / len(mi)
    print("  사인 최적: R2=%.3f f=%.5f Hz (겉보기 주기 %.2f초) 진폭 %.2f표본" % (r2, f, 1 / f, amp))
    # 4) 후보 — **평균 간격으로 균일 관측을 가정한 근사**(k/평균간격 ± f). 불규칙 관측에서
    #    정확한 접힘 대칭이 아니다(25회차 R25-02). 그래서 후보마다 **실제 시각으로 다시** 맞춘다
    #    (후보 ±0.003 Hz 안의 최적). k 를 어디서 끊느냐는 탐색 선택이다 — 여기서는 0~5.
    cands = sorted({k / ts + s * f for k in range(0, 6) for s in (1, -1) if k / ts + s * f > 0})
    print("  균일 간격(%.4f초) 근사 후보를 실제 시각으로 다시 맞춤 — 주기:R2" % ts)
    refit = []
    for c in cands:
        r2c, fc = max((sine_fit(t, res, c + j * 2e-5)[0], c + j * 2e-5) for j in range(-150, 151))
        refit.append("%.3f:%.3f" % (1 / fc, r2c))
    print("   ", " ".join(refit))

# 5) 타임스탬프 — 표시된 누적 ppm × 표시된 구간 초 × 명목 48 kHz.
#    원래 입력·출력 프레임 위치를 되살린 것이 **아니다**(로그에 frame/nanos 쌍이 없다, R25-04).
xs = []
with open(TIMESTAMP, encoding="utf-8") as f:
    for line in f:
        m = re.search(r"\[(\d+)초\] 드리프트=(-?[\d.]+) ppm\(구간 (\d+)초\)", line)
        if m:
            xs.append((int(m.group(3)), float(m.group(2))))
t = [T for T, _ in xs]
off = [p * 1e-6 * T * FS for T, p in xs]
e, _ = detrend(t, off)
den = sum(v * v for v in e)
print("== timestamp30 명목 표본 환산열의 직선 잔차 RMS %.6f표본" % math.sqrt(den / len(e)))
print("  자기상관 1~6:", [round(sum(e[i] * e[i - k] for i in range(k, len(e))) / den, 3) for k in range(1, 7)])
