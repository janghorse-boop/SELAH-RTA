package kr.joa.selahrta.dsp

import kotlin.math.*
import java.lang.management.ManagementFactory

/** Accelerated desktop arithmetic experiment, not an Android soak test. */
fun main() {
    val fs=48000
    val capacity=fs*3
    val exact=RollingLeq(3000,fs).apply{enableExactWindow()}
    val powers=DoubleArray(capacity)
    val wave=DoubleArray(65536){i -> sin(2*PI*997*i/fs)*.9}
    var naive=0.0; var worstNaive=0.0; var worstExact=0.0
    var checks=0; var head=0
    val start=System.nanoTime()
    // 2 h of input frames. Step 80 dB every 30 s and compare every whole second.
    repeat(fs*7200){i ->
        val sample=wave[i and 65535]*(if((i/(fs*30))%2==0)1.0 else .0001)
        exact.add(sample)
        val p=sample*sample
        naive-=powers[head];powers[head]=p;naive+=p
        head=(head+1)%capacity
        if(i>=capacity && (i+1)%fs==0){
            val expected=10*log10(powers.sum()/capacity)
            val a=exact.leqDbfs()!!.value
            val b=10*log10(naive/capacity)
            worstExact=max(worstExact,abs(a-expected))
            worstNaive=max(worstNaive,abs(b-expected)); checks++
            check(abs(a-expected)<1e-5){"Exact error ${a-expected} at frame $i"}
        }
    }
    println("SOAK frames=${fs*7200L} checks=$checks exactMaxErrorDb=$worstExact naiveMaxErrorDb=$worstNaive elapsedSec=${(System.nanoTime()-start)/1e9}")
    exact.reset();check(exact.leqDbfs()==null);check(!exact.isFull)
    repeat(capacity){exact.add(.01)}
    check(abs(exact.leqDbfs()!!.value+40)<1e-9)
    println("RESET noOldEnergy=true")

    // Allocation/timing is for this desktop JVM and this component only.
    val bean=ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val block=FloatArray(960){(.01*sin(2*PI*1000*it/fs)).toFloat()}
    val window=CleanWindow(fs)
    var ns=1_000_000_000L
    repeat(1000){ns+=20_000_000L;window.observe(block,block.size,ns,false)}
    val id=Thread.currentThread().id
    val before=bean.getThreadAllocatedBytes(id); val t=System.nanoTime()
    repeat(3000){ns+=20_000_000L;window.observe(block,block.size,ns,false)}
    println("CLEANWINDOW_DESKTOP inputSeconds=60 elapsedSec=${(System.nanoTime()-t)/1e9} allocatedBytes=${bean.getThreadAllocatedBytes(id)-before}")
}
