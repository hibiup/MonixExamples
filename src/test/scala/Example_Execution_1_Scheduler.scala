import java.util.concurrent.RejectedExecutionException

import com.typesafe.scalalogging.StrictLogging
import monix.execution.schedulers.SchedulerService
import org.scalatest.FlatSpec

import scala.concurrent.Await


/**
 * Execution 作为 Monix 的一个子项目，负责管理多任务执行环境，包括任务执行器Scheduler，
 *
 * https://monix.io/docs/3x/execution/scheduler.html
 *
 * Monix 的 Scheduler 相比 Scala ExecutionContext, 它允许：延时、重复计算，并且允许返回一个可终止运算的句柄。
 */
class Example_Execution_1_Scheduler extends FlatSpec with StrictLogging {
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

    "Execution Model" should "" in {
        /**
         * 定制 Monix Scheduler：
         *
         * onix Scheduler 支持三种执行模式：
         *
         *   BatchedExecution：批量模式，模式内任务以同步模式执行，强制异步边界。该模式的缺省任务数是 1024 个，以下 jvm 参数可以修改该值：
         *     java -Dmonix.environment.batchSize=256
         *
         *   AlwaysAsyncExecution：总是以异步执行所有任务，相当于 Scala 的 Future
         *
         *   SynchronousExecution：总是同步执行。
         */

         /**
          * 1） 基于 Java 的 Executor 来构建，构建的时候可以结合以上执行模式:
          */
        import java.util.concurrent.Executors
        import monix.execution.ExecutionModel.AlwaysAsyncExecution
        import monix.execution.{Scheduler, UncaughtExceptionReporter}

        /**
         * 任务分配执行器（java.util.concurrent.ScheduledExecutorService）。缺省是单线程 newSingleThreadScheduledExecutor，
         * 因为它只负责分配任务，并不实际执行任务
         * */
        lazy val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

        /**
         * 实际任务执行器, 缺省是 ForkJoinPool.
         * */
        lazy val executorService = scala.concurrent.ExecutionContext.Implicits.global

        // UncaughtExceptionReporter 收集异步任务中的异常，可以定制它的输出，比如输入到系统日志中。
        lazy val uncaughtExceptionReporter = UncaughtExceptionReporter{ ex => logger.error(ex.getMessage, ex) }

        lazy val scheduler: Scheduler = Scheduler(
            scheduledExecutor, // （可选）
            executorService,
            uncaughtExceptionReporter, // （可选）
            AlwaysAsyncExecution  // （可选）
        )
        scheduler.execute(() => println(s"Hello, ${scheduler.source}!"))

        /**
         * 2）定制 ForkJoinPool 制定大小.
         *
         * SchedulerService 是 Scheduler 的子类, 提供了关闭（termination）Scheduler 的功能。
         */
        lazy val forkJoinPoolScheduler: SchedulerService =
            Scheduler.computation(
                parallelism = 10,
                executionModel = AlwaysAsyncExecution
            )
        forkJoinPoolScheduler.execute(() => {
            Thread.sleep(3000)
            println(s"Hello, ${forkJoinPoolScheduler.source}!")
        })

        import scala.concurrent.duration._
        import monix.execution.Scheduler.global
        /**
         * shutdown 不会终止正在执行中的任务，因此终止过程也许不会立刻完成。如果我们希望 scheduler 彻底终止后在继续，则需要
         * 调用 awaitTermination. awaitTermination 并不会调用 shutdown, 它唯一的作用就是等待 shutdown 完成或超时。
         */
        logger.info("Trying to shutdown scheduler")
        forkJoinPoolScheduler.shutdown()
        logger.info("Shutdown request has been submitted")
        // awaitTermination 本身也是异步的
        val termination = forkJoinPoolScheduler.awaitTermination(30.seconds, global)
        Await.result(termination, Duration.Inf) match {
            case true => logger.info("Scheduler has been shutdown.")
        }

        // 关闭后的 Scheduler 不接受新任务
        logger.info(s"Scheduler is shutdown: ${forkJoinPoolScheduler.isShutdown}")
        try
            forkJoinPoolScheduler.execute(() => println(s"Hello, ${forkJoinPoolScheduler.source}!"))
        catch {
            case e: RejectedExecutionException => logger.info(e.getMessage)
        }

        /**
         * 3) 创建一个基于IO绑定无限制线程池的 Scheduler
         * */
        lazy val unboundedScheduler = Scheduler.io(name="my-io")

        /**
         * 4) 单线程池 Scheduler
         */
        lazy val singleThreadScheduler = Scheduler.singleThread(name="my-thread")

        /**
         * 5) Trampoline scheduler
         */
        lazy val trampolineScheduler = Scheduler.trampoline(executionModel=AlwaysAsyncExecution)
    }
}
