import kr.joa.selahrta.recording.BufferPool
import java.util.concurrent.CyclicBarrier
fun main() {
 var pool=BufferPool(2,1)
 val b=CyclicBarrier(3)
 val rounds=30000
 val threads=(0..1).map { i -> Thread { repeat(rounds) { b.await(); pool.release(i); b.await() } }.apply { start() } }
 var lost=0
 repeat(rounds) {
  pool=BufferPool(2,1)
  check(pool.acquire()==0);check(pool.acquire()==1)
  b.await();b.await()
  if(pool.inUse != 0) lost++
 }
 threads.forEach { it.join() }
 println("CONCURRENT_RETURN rounds=$rounds lostReturns=$lost")
 val p=BufferPool(2,1);val x=p.acquire();p.acquire()
 p.release(x)
 val rejected=runCatching {p.release(x)}.isFailure
 println("DOUBLE_RETURN_WITH_OTHER_HELD rejected=$rejected next=${p.acquire()},${p.acquire()}")
}
