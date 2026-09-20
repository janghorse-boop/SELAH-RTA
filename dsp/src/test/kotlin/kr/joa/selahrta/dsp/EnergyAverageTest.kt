package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EnergyAverageTest {

    @Test
    fun `아무것도 안 넣으면 값이 없다`() {
        val a = EnergyAverage()
        assertNull(a.rms())
        assertNull(a.dbfs())
        assertEquals(0L, a.frameCount())
    }

    @Test
    fun `같은 레벨만 모으면 그 레벨 그대로다`() {
        val a = EnergyAverage()
        repeat(10) { a.add(0.1, 1024) }
        assertEquals(0.1, a.rms()!!, 1e-12)
    }

    @Test
    fun `dB 로 평균한 값과 다르다 — 이것이 이 클래스가 있는 이유다`() {
        // 90dBFS 상당과 70dBFS 상당을 반씩 섞는다(여기서는 -10dB, -30dB).
        val loud = 10.0.pow(-10.0 / 20.0)   // -10 dBFS
        val quiet = 10.0.pow(-30.0 / 20.0)  // -30 dBFS

        val a = EnergyAverage()
        a.add(loud, 1000)
        a.add(quiet, 1000)
        val energyDb = a.dbfs()!!.value

        // dB 를 그냥 평균하면 -20dB 이 나온다. 에너지 평균은 그보다 훨씬 높다.
        val naiveDbAverage = (-10.0 + -30.0) / 2.0
        assertEquals(-20.0, naiveDbAverage, 1e-9)
        // 전력으로 (0.1 + 0.001) / 2 = 0.0505 → 10·log10(0.0505) = -12.9671
        assertEquals(-12.9671, energyDb, 1e-4)
        assertTrue(
            "에너지 평균이 dB 평균보다 7dB 남짓 높아야 한다 (차이 ${energyDb - naiveDbAverage}dB)",
            energyDb - naiveDbAverage > 6.0,
        )
    }

    @Test
    fun `프레임 수로 가중한다`() {
        // 큰 소리가 아주 짧게 스쳤다면 평균은 조용한 쪽에 가까워야 한다.
        val a = EnergyAverage()
        a.add(1.0, 10)      // 아주 짧은 큰 소리
        a.add(0.01, 9990)   // 대부분은 조용함
        val rms = a.rms()!!
        // 에너지: (1.0^2*10 + 0.01^2*9990) / 10000 = (10 + 0.999)/10000
        assertEquals(sqrt(10.999 / 10000.0), rms, 1e-9)
        assertTrue("짧은 큰 소리가 전체를 지배하면 안 된다", rms < 0.04)
    }

    @Test
    fun `길이 0 덩어리는 무시한다`() {
        val a = EnergyAverage()
        a.add(0.5, 1000)
        a.add(0.9, 0)
        assertEquals(0.5, a.rms()!!, 1e-12)
        assertEquals(1000L, a.frameCount())
    }

    @Test
    fun `순서를 바꿔도 결과가 같다`() {
        val levels = listOf(0.01 to 500, 0.3 to 2000, 0.05 to 1200, 0.7 to 300)
        val a = EnergyAverage().apply { levels.forEach { add(it.first, it.second) } }
        val b = EnergyAverage().apply { levels.reversed().forEach { add(it.first, it.second) } }
        assertTrue(abs(a.rms()!! - b.rms()!!) < 1e-12)
    }

    @Test
    fun `reset 하면 처음으로 돌아간다`() {
        val a = EnergyAverage()
        a.add(0.9, 1000)
        a.reset()
        assertNull(a.rms())
        assertEquals(0L, a.frameCount())
    }

    @Test
    fun `잘못된 입력은 거부한다`() {
        val a = EnergyAverage()
        assertThrows(IllegalArgumentException::class.java) { a.add(-0.1, 100) }
        assertThrows(IllegalArgumentException::class.java) { a.add(0.1, -1) }
    }

    @Test
    fun `무음만 모으면 바닥값이 된다`() {
        val a = EnergyAverage()
        repeat(5) { a.add(0.0, 1024) }
        assertEquals(0.0, a.rms()!!, 0.0)
        assertEquals(SILENCE_DBFS, a.dbfs()!!.value, 0.0)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
    private fun sqrt(x: Double) = kotlin.math.sqrt(x)
}
