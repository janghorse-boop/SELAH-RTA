package kr.joa.selahrta.dsp
import java.lang.management.ManagementFactory
import kotlin.math.*
fun main() {
    val bean=ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val id=Thread.currentThread().id
    val block=FloatArray(960){(.01*sin(2*PI*1000*it/48000)).toFloat()}
    val window=CleanWindow(48000)
    var ns=1_000_000_000L
    repeat(3000){ns+=20_000_000L;window.observe(block,block.size,ns,false)}
    val before=bean.getThreadAllocatedBytes(id); val t=System.nanoTime()
    repeat(3000){ns+=20_000_000L;window.observe(block,block.size,ns,false)}
    val elapsed=(System.nanoTime()-t)/1e9;val bytes=bean.getThreadAllocatedBytes(id)-before
    println("CLEANWINDOW inputSeconds=60 elapsedSec=$elapsed allocatedBytes=$bytes dbfs=${window.value(ns,Weighting.Z)}")
}
