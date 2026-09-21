import kr.joa.selahrta.dsp.*
import kotlin.math.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
fun main() {
    val q=ArrayBlockingQueue<Int>(1); val accepted=AtomicLong(0)
    var rejected=false
    val t=Thread {
        accepted.getAndIncrement()
        Thread.currentThread().interrupt()
        try { q.put(0) } catch(e:InterruptedException) { rejected=true }
    }
    t.start(); t.join()
    println("QUEUE emptyCapacity=1 accepted=${accepted.get()} consumed=${q.size} interrupted=$rejected")
    check(rejected && accepted.get()==1L && q.isEmpty())
    val fs=48000
    val x=FloatArray(fs*3) { i -> (0.2*sin(2*PI*1000*i/fs)+(if(i%7919==0)0.9 else 0.0)).toFloat() }
    fun spl(cuts:List<Int>):MultiWeightFrame {
        val e=MultiWeightEngine(fs,TimeWeight.Fast); var i=0; var c=0; var last:MultiWeightFrame?=null
        while(i<x.size) { val n=minOf(cuts[c++%cuts.size],x.size-i); last=e.process(x.copyOfRange(i,i+n),n); i+=n }
        return last!!
    }
    val cuts=listOf(1,13,448,1023,1024,2000,3001)
    val a=spl(listOf(1024)); val b=spl(cuts)
    for(w in Weighting.entries) {
        val p=a.of(w); val r=b.of(w)
        println("SPL $w short=${p.leqShortDbfs==r.leqShortDbfs} long=${p.leqLongDbfs==r.leqLongDbfs} sessionA=${p.leqSessionDbfs} sessionB=${r.leqSessionDbfs} fullEqual=${p==r}")
    }
    fun feedback(cuts:List<Int>):List<FeedbackEvent> {
        val r=RtaEngine(fs); val d=FeedbackDetector(r.fftSize,fs); var ms=0L
        r.spectrumSink=SpectrumSink { p -> d.process(p,ms) }
        var i=0; var c=0
        while(i<x.size) {val n=minOf(cuts[c++%cuts.size],x.size-i); ms=(i+n)*1000L/fs;r.process(x.copyOfRange(i,i+n),n);i+=n}
        return d.events.toList()
    }
    val fa=feedback(listOf(1024)); val fb=feedback(cuts)
    println("FEEDBACK whole=$fa")
    println("FEEDBACK split=$fb")
    println("FEEDBACK equal=${fa==fb}")
    check(fa!=fb)
}
