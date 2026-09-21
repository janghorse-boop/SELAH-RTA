import kr.joa.selahrta.dsp.*
import kotlin.math.*

fun main() {
    val fs=48000; val n=4096
    val ba=BandAnalyzer(n,fs)
    val ps=PowerSpectrum(n)
    val p=DoubleArray(n/2+1); val bp=DoubleArray(31)
    for(b in listOf(0,5,7,8,10,17)) {
        var worst=-1e9; var at=0.0
        for(t in listOf(0.01,0.1,0.25,0.5,0.75,0.9,0.99)) {
            val f=ThirdOctave.lowerEdge(b)*(ThirdOctave.upperEdge(b)/ThirdOctave.lowerEdge(b)).pow(t)
            ps.compute(DoubleArray(n){sin(2*PI*f*it/fs)},0,p)
            ba.toBandPower(p,bp)
            val loss=-10*log10(bp[b]/0.5)
            if(loss>worst){worst=loss;at=t}
            if(t==0.1 || t==0.9) println("BAND f=${ThirdOctave.CENTERS_HZ[b]} t=$t measuredLoss=$loss")
        }
        println("BAND_SUMMARY f=${ThirdOctave.CENTERS_HZ[b]} declaredLoss=${ba.bandLossDb[b]} resolved=${ba.bandResolved[b]} sampledWorst=$worst at=$at")
    }
    val c=CalibrationCurve.of(listOf(CurvePoint(ThirdOctave.lowerEdge(17),-6.0),CurvePoint(1000.0,0.0),CurvePoint(ThirdOctave.upperEdge(17),6.0))).getOrThrow()
    val engine=RtaEngine(fs,smoothingFactor=0.0)
    engine.setCurve(c)
    engine.process(FloatArray(n){(0.5*sin(2*PI*1000*it/fs)).toFloat()},n)
    val actual=engine.frame()!!.bandsDbfs[17]
    val expected=10*log10(0.125)
    println("ACTUAL_RTA_CORRECTION actual=$actual expected=$expected error=${actual-expected}")
    check(abs(actual-expected)<0.05)
    val old=engine.frame()
    engine.setCurve(CalibrationCurve.of(listOf(CurvePoint(20.0,10.0),CurvePoint(20000.0,10.0))).getOrThrow())
    println("AFTER_SET_CURVE priorFrameStillReturned=${engine.frame()===old} level=${engine.frame()!!.bandsDbfs[17]}")
    engine.process(FloatArray(n/2){(0.5*sin(2*PI*1000*(it+n)/fs)).toFloat()},n/2)
    println("AFTER_NEXT_FFT level=${engine.frame()!!.bandsDbfs[17]}")
}
