package kr.joa.selahrta.review

import kr.joa.selahrta.dsp.*
import kotlin.math.PI
import kotlin.math.sin

/** Numerical prototype ONLY: fixed 1 kHz calibrator, not a production patch.
 * One instance per capture session; the live meter is not reset or replaced.
 * Wiring of tone/routing evidence, UI, CPU cost, and reference-meter timing
 * remains implementation work. No claim of calibrated acoustic accuracy. */
private class CleanCalibratorWindow(private val rate:Int) {
    private var engine:MultiWeightEngine?=null
    private var frames=0L
    private var lastNs:Long?=null
    private var frame:MultiWeightFrame?=null
    fun observe(pcm:FloatArray,atNs:Long) {
        val gap=lastNs?.let{atNs-it>500_000_000L}?:false
        if(gap)clear()
        lastNs=atNs
        if(pcm.isEmpty() || blockStats(pcm,pcm.size).clipped){clear();return}
        val target=engine?:MultiWeightEngine(rate,TimeWeight.Slow,leqShortMs=3000,leqLongMs=3000).also{engine=it}
        frame=target.process(pcm,pcm.size)
        frames+=pcm.size
    }
    private fun clear(){engine=null;frame=null;frames=0;lastNs=null}
    fun value(nowNs:Long,w:Weighting):Double? {
        val at=lastNs?:return null
        if(nowNs-at !in 0L..500_000_000L || frames<rate*3L)return null
        // Bounded 3-second energy average of clean input, not the live exponential value.
        return frame?.of(w)?.leqShortDbfs?.value
    }
}
fun main() {
    fun tone(amp:Double)=FloatArray(960){(amp*sin(2*PI*1000*it/48000)).toFloat()}
    var at=1_000_000_000L
    val window=CleanCalibratorWindow(48000)
    repeat(250){at+=20_000_000;window.observe(tone(1.0),at)}
    check(window.value(at,Weighting.A)==null)
    repeat(149){at+=20_000_000;window.observe(tone(.01),at)}
    check(window.value(at,Weighting.A)==null)
    repeat(6){at+=20_000_000;window.observe(tone(.01),at)}
    val stable=MultiWeightEngine(48000,TimeWeight.Slow)
    repeat(1000){stable.process(tone(.01),960)}
    val ref=stable.process(tone(.01),960).a.currentDbfs.value
    val actual=window.value(at,Weighting.A)!!
    check(kotlin.math.abs(actual-ref)<.05)
    println("PROTOTYPE afterClipping cleanMs=3100 meanDbfs=$actual referenceDbfs=$ref errorDb=${actual-ref}")
    repeat(155){at+=20_000_000;window.observe(tone(.1),at)}
    check(kotlin.math.abs(window.value(at,Weighting.A)!!-(ref+20))<.05)
    println("PROTOTYPE laterLevelChange meanDbfs=${window.value(at,Weighting.A)}")
    at+=3_100_000_000
    check(window.value(at,Weighting.A)==null)
    at+=20_000_000;window.observe(tone(.01),at)
    check(window.value(at,Weighting.A)==null)
    println("PROTOTYPE gapThen20ms value=null")
    repeat(149){at+=20_000_000;window.observe(tone(.01),at)}
    check(window.value(at,Weighting.A)!=null)
    window.observe(floatArrayOf(),at)
    check(window.value(at,Weighting.A)==null)
    println("PROTOTYPE errorBlock value=null")
}

