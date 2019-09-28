import com.typesafe.scalalogging.StrictLogging
import monix.execution.Cancelable
import monix.reactive.Observable
import org.scalatest.FlatSpec

class Example_1_HelloWorld extends FlatSpec with StrictLogging{
    import monix.execution.Scheduler.Implicits.global
    import scala.concurrent.Await
    import scala.concurrent.duration._

    "Task" should "" in {
        /**
         * Task 是 Monix 的一个基本容器。和 Future 类似，但是 Task 是 Lazy 的
         */
        import monix.eval._
        val task = Task{ 1 + 1 }

        /**
         * Task 可以指定多种 Eval 模式，比如同步，runSyncStep, 异步 runAsync，或 Future:
         */
        import monix.execution.CancelableFuture
        val f: CancelableFuture[Int] = task.runToFuture
        f.onComplete( i => i.foreach(println))

        Await.result(f, 5.seconds)
    }

    "Lazy Observable" should "" in {
        /**
         * Observable：可观测对象，是 Monix Reactive 的"上游"组件
         */
        val tick: Observable[Long] = {
            // 每秒产生一次可观测事件
            Observable.interval(1.second)
              .filter(_ % 2 == 0)  // 如果输入的是偶数
              .map(_ * 2)
              // 将结果转成 Seq[Int, Int]
              .flatMap(x => Observable.fromIterable(Seq(x, x)))
        }

        val cancelableSubscriber: Cancelable = tick
          // 万事具备，获取前 5 个输入
          .take(5)
          // dump 方法给每一次事件的 value 加上前缀，然后交给打印(println)函数
          .dump("Out")
          // 得到事件 "订阅者（Subscriber）"，并开始订阅（持续收到 dump 之后的闭包，然后解包执行）
          .subscribe()

        // 主线程睡 6 秒，保证订阅者有足够的时间接受并处理数据
        Thread.sleep(6000)

        // 结束订阅
        cancelableSubscriber.cancel()
    }
}
