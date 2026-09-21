package kr.joa.selahrta.audio
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private fun await(l:CountDownLatch)=check(l.await(5,TimeUnit.SECONDS))
private class HandoffSink(val first:Boolean):SignalSink {
 val entered=CountDownLatch(1); val gate=CountDownLatch(1)
 override fun open(sampleRate:Int,frames:Int)=true
 override fun write(buf:FloatArray,offset:Int,frames:Int):Int { entered.countDown(); if(!first) await(gate); return SignalSink.ERROR_DEAD_OBJECT }
 override fun stop(){gate.countDown()}
 override fun release()=false
}
private fun field(obj:Any,name:String):Any = obj.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(obj)
fun main(){
 val firstDone=CountDownLatch(1); val secondDone=CountDownLatch(1)
 val sinks=ArrayList<HandoffSink>()
 val p=SignalPlayer(onEnded={g,_->if(g==1L) firstDone.countDown() else secondDone.countDown()},openSink={HandoffSink(sinks.isEmpty()).also{sinks.add(it)}},warn={})
 p.start(TestSignal.Sine1k,SignalLevel.Low); await(firstDone)
 p.start(TestSignal.Sine1k,SignalLevel.Low); await(sinks[1].entered)
 val lifeLock=field(p,"lock")
 val pendingLock=field(field(p,"stuck"),"lock")
 val result=AtomicLong(-1);val failure=AtomicReference<Throwable?>()
 val starter=Thread{try{result.set(p.start(TestSignal.Sine1k,SignalLevel.Low))}catch(t:Throwable){failure.set(t)}}
 var observedLock=-1
 synchronized(pendingLock){
   sinks[1].gate.countDown()
   var until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
   while(p.playing!=null && System.nanoTime()<until) Thread.yield()
   check(p.playing==null)
   starter.start()
   until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
   while(System.nanoTime()<until){
     val info=ManagementFactory.getThreadMXBean().getThreadInfo(starter.id)
     if(info?.threadState==Thread.State.BLOCKED){observedLock=info.lockInfo.identityHashCode;break}
     Thread.yield()
   }
 }
 starter.join(5000);await(secondDone)
 check(!starter.isAlive && failure.get()==null)
 check(observedLock==System.identityHashCode(lifeLock)) {"Starter was not blocked on lifecycle lock"}
 check(result.get()==SignalPlayer.NONE && sinks.size==2 && p.pendingCount==2)
 println("HANDOFF_BARRIER blockedOnLifecycleLock=true newOpenRefused=true opens=${sinks.size} pending=${p.pendingCount}")
}
