import com.typesafe.scalalogging.StrictLogging
import org.scalatest.FlatSpec

/**
 * https://monix.io/docs/3x/execution/scheduler.html
 *
 * Monix 的 Scheduler 相比 Scala ExecutionContext, 它允许：延时、重复计算，并且允许返回一个可终止运算的句柄。
 */
class Example_2_Scheduler extends FlatSpec with StrictLogging {
    import java.util.concurrent.TimeUnit

    "Monix Scheduler works with Scala Future" should "" in {
        /**
         * Monix Scheduler 扩展自 ExecutionContext，因此它可以在一定程度上替换缺省的 EC。同时，Monix 提供了一个
         * monix.execution.Scheduler.Implicits.global 实例用于替换 Scala 缺省的 global 实例.
         *
         * 并且因为 Scheduler 兼容 Scala EC, 因此它也背靠 ForkJoinPool，所有关于 ForkJoinPool 的参数也同样生效：
         *
         * “scala.concurrent.context.minThreads” an integer specifying the minimum number of active threads in the pool
         * “scala.concurrent.context.maxThreads” an integer specifying the maximum number of active threads in the pool
         * “scala.concurrent.context.numThreads” can be either an integer, specifying the parallelism directly or a
         *   string with the format “xNUM” (e.g. “x1.5”) specifying the multiplication factor of the number of available
         *   processors (taken with Runtime.availableProcessors)
         *
         * Example of setting a system property:
         *   java -Dscala.concurrent.context.minThreads=10 ...
         */
        // 用 Monix global 实例代替 Scala 缺省的 EC global 保持了对 Future 的兼容性。
        import monix.execution.Scheduler.Implicits.global

        import concurrent.Future
        Future(1 + 1).foreach(println)
    }

    "Execute Runnables" should "" in {
        /**
         * 可以用 Scheduler 驱动一个 Runnable
         */
        import monix.execution.Scheduler.{global => scheduler}

        scheduler.execute(new Runnable {
            def run(): Unit = {
                println("Hello, world!")
            }
        })
    }

    "Schedule with a Delay" should "" in {
        import monix.execution.Scheduler.{global => scheduler}

        // scheduler 会返回一个 cancelable 对象
        val cancelable = scheduler.scheduleOnce( 5, TimeUnit.SECONDS, new Runnable {
                def run(): Unit =  println("Hello, world!")
            })

        // In case we change our mind, before time's up
        cancelable.cancel()
    }

    "Schedule Repeatedly" should  "" in {
        import monix.execution.Scheduler.{global => scheduler}
        /**
         * 延迟施加于前一个任务结束，后一个任务开始之间，因此以下两个任务的开始时间间隔将达到 7秒(5s + 2000ms)
         */
        val c = scheduler.scheduleWithFixedDelay(3, 5, TimeUnit.SECONDS, new Runnable {
                def run(): Unit = {
                    Thread.sleep(2000) // 2 seconds
                    println("Fixed delay task")
                }
            })

        // If we change our mind and want to cancel
        c.cancel()
    }

    "TestScheduler" should "" in {
        /**
         * TestScheduler 不是用于测试的类，而是一个"懒惰"的 Scheduler。它允许我们先定义任务，然后在适当的时候“模拟”
         * 时间条件触发执行.
         */
        import monix.execution.schedulers.TestScheduler
        import scala.concurrent.duration._

        val testScheduler = TestScheduler()

        // 立即执行
        testScheduler.execute(new Runnable {
            def run() = println("Immediate!")
        })

        // 延迟一秒
        testScheduler.scheduleOnce(1.second) {
            println("Delayed execution!")
        }

        // 触发立即执行
        testScheduler.tick()
        // => Immediate!

        /**
         * 注意：由于两个任务在同一个 scheduler 里面，因此如果直接触发延迟任务，实际上 immediate 任务的条件同样会得到满足，因此
         * 也会被触发，并且会先打印出结果。
         */
        // 再次触发，给予一个模拟的一秒信号量
        testScheduler.tick(1.second)
        // => Delayed execution!
    }
}
