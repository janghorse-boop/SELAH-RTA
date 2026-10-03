"""`2026-10-03-dense-trial-analysis.py` 의 경계 시험(28회차 R28-01·02 와 그 둘레).

돌리기: 저장소 뿌리에서 `PYTHONIOENCODING=utf-8 python docs/verify/2026-10-03-dense-trial-analysis-test.py`
합성 로그는 임시 폴더에만 만든다. 원자료를 고치지 않는다.
"""
import contextlib
import importlib.util
import io
import math
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location(
    "dense", os.path.join(HERE, "2026-10-03-dense-trial-analysis.py"))
dense = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dense)

STEP = 50_000  # 약 1.04초 — 지연 창(32,768)보다 크다


def obs(session, we, lag, kind="Measured", found=True, under=0, out_err=0, in_err=0, route=False):
    return ("OBS session=%s kind=%s epoch=0 windowEnd=%d lag=%d found=%s sharpness=3.0 mono=0 "
            "underruns=%d outErr=%d inErr=%d routeChanged=%s outRoute=1 peak=0.5"
            % (session, kind, we, lag, "true" if found else "false", under, out_err, in_err,
               "true" if route else "false"))


def good_rows(session, n, start=1, period=3.3):
    """주기 `period` 초의 정수 지연 — 훑기가 돌 만큼 촘촘하고 고른 창."""
    return [obs(session, (start + i) * STEP, 7200 + round(2 * math.sin(2 * math.pi * (start + i) * STEP / 48000 / period)))
            for i in range(n)]


def session(name, rows, verdict=("SESSION VALID", "RESULT PASS")):
    return ["HEAD session=%s mode=Trial" % name] + rows + list(verdict)


def run(lines):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
        path = f.name
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            return dense.run(path)
    finally:
        os.remove(path)


class Rule1PerSession(unittest.TestCase):
    """R28-01 — 다른 세션의 PASS 가 실패 세션을 받아 주면 안 된다."""

    def test_실패_세션은_다른_세션의_PASS_로_받지_않는다(self):
        lines = (session("aaaa", good_rows("aaaa", 240), ("SESSION VALID", "RESULT FAIL"))
                 + session("bbbb", good_rows("bbbb", 240)))
        r = run(lines)
        self.assertIsNone(r["aaaa"])
        self.assertEqual(len(r["bbbb"]), 1)
        self.assertEqual(r["bbbb"][0]["distinct"], 240)

    def test_INVALID_는_PASS_가_있어도_받지_않는다(self):
        r = run(session("cccc", good_rows("cccc", 240), ("SESSION INVALID", "RESULT PASS")))
        self.assertIsNone(r["cccc"])

    def test_판정_줄이_없는_세션은_받지_않는다(self):
        r = run(session("dddd", good_rows("dddd", 240), ()))
        self.assertIsNone(r["dddd"])

    def test_HEAD_없이_OBS_만_있는_세션은_받지_않는다(self):
        r = run(good_rows("eeee", 240) + ["SESSION VALID", "RESULT PASS"])
        self.assertIsNone(r["eeee"])


class NonMeasuredRows(unittest.TestCase):
    """R28-02 — `Measured` 가 아닌 줄의 임시 windowEnd=0 을 역행으로 읽지 않는다."""

    def test_RetentionExceeded_한_줄이_구간을_가르지_않는다(self):
        rows = good_rows("ffff", 120) + [obs("ffff", 0, 0, kind="RetentionExceeded", found=False)] \
            + good_rows("ffff", 120, start=121)
        r = run(session("ffff", rows))["ffff"]
        self.assertEqual(len(r), 1)
        self.assertEqual(r[0]["distinct"], 240)
        self.assertEqual(r[0]["hold"], [])
        self.assertIsNotNone(r[0]["best"])

    def test_측정_아닌_줄은_found_가_참이어도_쓰지_않는다(self):
        # 종류 거르기만 지키는 자리 — found 로 걸러지는 경로와 따로 본다.
        rows = good_rows("kkkk", 120) + [obs("kkkk", 0, 0, kind="RetentionExceeded", found=True)] \
            + good_rows("kkkk", 120, start=121)
        r = run(session("kkkk", rows))["kkkk"]
        self.assertEqual(len(r), 1)
        self.assertEqual(r[0]["distinct"], 240)

    def test_측정_아닌_줄의_경로_바뀜은_다음_관측에서_끊는다(self):
        rows = good_rows("gggg", 3) + [obs("gggg", 0, 0, kind="Busy", found=False, route=True)] \
            + good_rows("gggg", 3, start=4)
        r = run(session("gggg", rows))["gggg"]
        self.assertEqual([s["distinct"] for s in r], [3, 3])

    def test_못_찾은_줄의_언더런_증가도_끊는다(self):
        rows = good_rows("hhhh", 3) + [obs("hhhh", 4 * STEP, 0, found=False, under=1)] \
            + [obs("hhhh", (5 + i) * STEP, 7200, under=1) for i in range(3)]
        r = run(session("hhhh", rows))["hhhh"]
        self.assertEqual([s["distinct"] for s in r], [3, 3])


class Rule3(unittest.TestCase):

    def test_같은_windowEnd_는_한_번만_쓰고_중복으로_센다(self):
        rows = good_rows("iiii", 5)
        rows.insert(3, rows[2])
        r = run(session("iiii", rows))["iiii"]
        self.assertEqual(len(r), 1)
        self.assertEqual(r[0]["distinct"], 5)

    def test_200개_미만은_보류한다(self):
        r = run(session("jjjj", good_rows("jjjj", 199)))["jjjj"]
        self.assertTrue(any("200" in h for h in r[0]["hold"]))


class RealData(unittest.TestCase):
    """커밋된 원자료에서 결과 기록의 숫자가 그대로 나오는가."""

    DATA = os.path.join(HERE, "2026-10-03-dense-trial-data")

    def test_분석_대상_세션(self):
        with contextlib.redirect_stdout(io.StringIO()):
            r = dense.run(os.path.join(self.DATA, "dense5-2d3c2c56.txt"))["2d3c2c56"]
        self.assertEqual(len(r), 1)
        self.assertEqual(r[0]["distinct"], 278)
        self.assertAlmostEqual(r[0]["span"], 299.093, places=3)
        f, r2, amp = r[0]["best"]
        self.assertAlmostEqual(1 / f, 3.343, places=3)
        self.assertAlmostEqual(r2, 0.511, places=3)
        self.assertAlmostEqual(amp, 1.57, places=2)

    def test_FAIL_세션은_해석하지_않는다(self):
        with contextlib.redirect_stdout(io.StringIO()):
            r = dense.run(os.path.join(self.DATA, "dense5-32886213-FAIL.txt"))
        self.assertEqual(r, {"32886213": None})


if __name__ == "__main__":
    unittest.main(verbosity=2)
