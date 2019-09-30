import com.typesafe.scalalogging.StrictLogging
import monix.eval.Coeval
import monix.execution.Cancelable
import org.scalatest.FlatSpec

import scala.annotation.tailrec
import scala.concurrent.{Await, TimeoutException}
import scala.util.Random

class Example_Eval_1_Task extends FlatSpec with StrictLogging {
    import monix.execution.Scheduler.Implicits.global
    // A Future type that is also Cancelable
    import monix.execution.CancelableFuture
    // Task is in monix.eval
    import monix.eval.Task
    import concurrent.duration._

    "Eval Task" should "" in {
        /**
         * Task 是 lazy， 异步的。构建的时候不会被执行，直到 runToFuture 或 rubAsync才会被 Eval.
         *
         * eval 表示延迟任务并将任务的执行置于与 runToFuture 相同的上下文空间（调用 runToFuture 函数的空间，不是 runToFuture
         * 将切换去的空间）。与 eval 对应的是 now，now 会立刻在但前上下文求取任务值，然后装入 Task 容器。相当于 pure
         *
         * 注意 executeAsync 相当于 IO.shift。它会在（隐式）Scheduler 的执行上下文（本例使用缺省的 global）中生成一
         * 个异步数据结构，因此如果执行了此函数，可以看到任务的执行线程被切换到了 `global`。如果不执行这条指令则会看到任务
         * 会使用当前执行上下文(主线程)来执行。
         */
        val task = Task.eval{
            logger.info("Task is running.")
            1 + 1
        }//.executeAsync

        /**
         * 1) runToFuture 会切换到（隐式）Scheduler 执行上下文（global）中接受结果。
         */
        val f: CancelableFuture[Int] = task.runToFuture

        /**
         * 1-1) 处理结果. foreach 或 map 都执行在 runToFuture 的 Scheduler 的执行上下文中。因此会看到任务线程是 `global`
         */
        f.foreach(v => logger.info(s" foreach received: $v"))
        /**
         * 1-2) 在 map 中处理结果
         */
        for (result <- f) {
            logger.info(s"map received: $result")
        }

        /**
         * 销毁
         */
        f.cancel()

        /**
         * 2) runToFuture 不具备回调能力，rubAsync 则具备异步回调处理结果的能力，
         *
         * 但是 rubAsync 和 runToFuture 不同的是它只处理异步回调，无关执行上下文的切换。因此如果要切换上下文，达到和 runToFuture
         * 一样的效果，则要先显示执行 executeAsync.
         */
        val cancelable: Cancelable = task/*.executeAsync*/.runAsync {
            case Right(value) => logger.info(s"Received: $value")
            case Left(ex) => logger.error(s"ERROR: ${ex.getMessage}", ex)
        }
        cancelable.cancel()  //  销毁
    }

    "Turn Seq[Task] to Task[Seq]" should "" in {
        val ta = Task {
            logger.info("Task 1 is running.")
            1 }
        val tb = Task {
            logger.info("Task 2 is running.")
            2 }

        /*********
         * Task.sequence 将 Seq[Task] 转换成 Task[Seq], 并保证执行和返回值的顺序
         */
        val orderedList: Task[Seq[Int]] = Task.sequence(Seq(ta, tb))
        // We always get this ordering:
        orderedList.runToFuture.foreach(v => logger.info(s"$v"))

        /*********
         * Task.gather 将 Seq[Task] 转换成 Task[Seq]但是不保证执行的顺序，但是保证返回值的顺序
         */
        val randomExecList: Task[Seq[Int]] = Task.gather(Seq(ta, tb))
        randomExecList.runToFuture.foreach(v => logger.info(s"$v"))

        /*********
         * Task.gatherUnordered 不仅不保证执行的顺序，也不保证返回值的顺序
         */
        val unorderedlist: Task[Seq[Int]] = Task.gatherUnordered(Seq(ta, tb))
        unorderedlist.runToFuture.foreach(v => logger.info(s"$v"))
    }

    "executeAsync, executeOn" should "" in {
        /*******
         * 就像上面例子的说明，executeAsync 可以切换执行上下文的边界。
         */
        Task{
            logger.info("Task is running in executeAsync mode will be shift to `global` pool.")
            1 + 1
        }.executeAsync.runToFuture

        Task{
            logger.info("Task is running in `main` thread context.")
            1 + 1
        }.runToFuture

        /********
         * executeOn 可以用于显示切换 ec。
         */
        // 新建一个特定的 ec
        import monix.execution.Scheduler
        lazy /*implicit*/ val io = Scheduler.io(name="my-io")

        // 执行任务的时候切换到临时 ec
        Task{
            logger.info("executeOn shift task to `io` execution context.")
            1 + 1
        }.executeOn(io)
          /**
           * executeOn 结束后会停留在 io ec 中, 但是如果 io 没有设置成 implicit 取代缺省的 global 的话，后续
           * 函数有可能不会继续使用它，而会切换回缺省的 ec
           * */
          .doOnFinish(_ => Task{logger.info("doOnFinish task")})  // doOnFinish 的一个非常有用的用途是用来关闭资源
          .runToFuture
          .foreach(v => logger.info(s"executeOn post process received: $v in `global` EC"))
    }

    "Delay eval Task" should "" in {
        /**
         * 定义一个延迟一秒执行的任务
         */
        val task = Task(1 + 1).delayExecution(1.second)
        val result: CancelableFuture[Int] = task.runToFuture

        /**
         * 将不会看到输出，因为执行被延迟了一秒，以至于在被销毁之前没有被执行，
         */
        result.foreach(println)

        /**
         * cancel 会销毁所有未被执行的任务（但是不会销毁正在执行的任务）
         */
        result.cancel()
    }

    "Task can race for each other" should "" in {
        /**
         * Task.racePair 返回两个 Task 中先执行完的那个结果
         */
        val ta = Task{1 + 1}
        val tb = Task{10}

        Task.racePair(ta, tb).runToFuture.foreach {
            case Left((a, loser)) =>
                logger.info(s"A succeeded: $a")
                loser.cancel
            case Right((loser, b)) =>
                logger.info(s"B succeeded: $b")
                loser.cancel
        }
    }

    "Blocking eval task" should "" in {
        /********
         * // eval 出来的 task是没有状态的，每次 runToFuture 都会导致从新 eval
         */
        val task: Task[Int] = Task.eval{
            logger.info("Task is running")
            1 + 1
        }//.executeAsync
        task.runToFuture.foreach(v => logger.info(s"foreach received: $v"))
        task.runToFuture.foreach(v => logger.info(s"foreach received: $v"))
        task.runToFuture.foreach(v => logger.info(s"foreach received: $v"))

        // 注意：eval 的结果(Task) 是无状态的，但是 runToFuture 的结果(CancelableFuture)是有状态的, 它不会重复多次计算
        val f: CancelableFuture[Int] = task.runToFuture
        f.foreach(v => logger.info(s"cancelableFuture foreach received: $v"))
        f.foreach(v => logger.info(s"cancelableFuture foreach received: $v"))

        // 并且 runToFuture 返回的 CancelableFuture 是个 Future，也就是说它是 eager 且有状态的，可以通过 memorize 来代替，
        // memorize 是 lazy 且有状态的
        val m: Task[Int] = task.memoize
        m.foreach(v => logger.info(s"memorize foreach received: $v"))
        m.foreach(v => logger.info(s"memorize foreach received: $v"))

        /*********
         *
         * 除了runToFuture，还可以显示地阻塞等待 task，但不被推荐，只在特定需求下使用.
         *
         * Await 除了阻塞外，还有一个副作用是会将结果（从 runToFuture 切换去的 Scheduler global）取回当前执行上下文中。
         */
        logger.info{s"Await received: ${Await.result(task.runToFuture, 3.seconds)}"}

        /**********
         * evalOnce 是有状态的，它只会求值一次，然后记住结果以后无论调用多少次都不会再次求值了。
         */
        val task2 = Task.evalOnce{
            logger.info("Eval will run only one time")
            1 + 1
        }
        // task2 不会被重复计算。
        task2.runToFuture.foreach(v => logger.info(s"evalOnce foreach received: $v"))
        task2.runToFuture.foreach(v => logger.info(s"evalOnce foreach received: $v"))
        task2.runToFuture.foreach(v => logger.info(s"evalOnce foreach received: $v"))

        // evalOnce 结合 memoize, 每一步都是有状态的
        task2.memoize.foreach(v => logger.info(s"evalOnce memoize foreach received: $v"))
        task2.memoize.foreach(v => logger.info(s"evalOnce memoize foreach received: $v"))
    }

    "Defer eval" should "" in {
        /**********
         * defer 和 eval 的目标是一致的，但是它作用于一个立刻求值的运算将它转成 lazy。（通过返回 suspend）
         */
        val task: Task[Int] = Task.defer{
            // defer 会 suspend now 的执行，将它转回 eval
            Task.now {
                logger.info("Task is running")
                1 + 1
            }
        }
        task.runToFuture.foreach(v => logger.info(s"foreach received: $v"))
        task.runToFuture.foreach(v => logger.info(s"foreach received: $v"))

        /********
         * 并且 defer 和 IO.suspend 一样，具有 flatten 的作用，这也会使得堆栈变得安全
         */
        def fib(cycles: Int, a: BigInt=1, b: BigInt=1): Task[BigInt] = {
            if (cycles > 0)
                Task.defer(fib(cycles-1, b, a+b))
            else
                Task.now(b)
        }
        fib(100000).runToFuture.foreach(v => logger.info(s"fib returns: $v"))
    }

    "fromFuture" should "" in {
        /*********
         *  和 IO 一样，Task 具有 fromFuture 功能，但是语法更直接。
         */
        import scala.concurrent.Future

        val future = Future {
            logger.info("Task is running")
            1 + 1
        }
        val task: Task[Int] = Task.fromFuture(future)

        task.runToFuture.foreach(v => logger.info(s"fromFuture received: $v"))
        task.runToFuture.foreach(v => logger.info(s"fromFuture received: $v"))

        /*********
         * 但是以上代码存在一个问题：Future 执行在 Task 之外，实际上我们并没有阻止它 eager 求值。为此我们可以使用 defer
         * 来管理 Future 的生成：
         */
        val task2: Task[Int] = Task.defer{
            // 因为 Future 实行在 defer 内，因此被转变成了 lazy
            val future = Future {
                logger.info("Task is running")
                1 + 1
            }

            /**
             * 和 IO suspend 一样，defer 生成的 suspend 同样具有 flatten 的作用，fromFuture 生成的内嵌 Task 会被
             * defer flatten 掉。
             */
            Task.fromFuture(future)
        }
        task2.runToFuture.foreach(v => logger.info(s"deferred fromFuture received: $v"))
        task2.runToFuture.foreach(v => logger.info(s"deferred fromFuture received: $v"))

        /********
         * 以上嵌套语法可以被缩减为一句：
         */
        val task3: Task[Int] = Task.deferFuture{
            // deferFuture 将 defer 和 fromFuture 合并为一条指令
            Future {
                logger.info("Task is running")
                1 + 1
            }
        }
        task3.runToFuture.foreach(v => logger.info(s"deferFuture received: $v"))
        task3.runToFuture.foreach(v => logger.info(s"deferFuture received: $v"))
    }

    "Convert Task to Coeval" should "" in {
        /**
         * Coeval stands for Co-Eval，也就是立刻求值（Eval 是延迟求值）。
         */
        val task: Task[Int] = Task.eval{
            logger.info("Task is running")
            1 + 1
        }//.executeAsync  // executeAsync 决定是否切换任务上下文。

        /**
         * 转换成 Coeval
         */
        val coeval = Coeval(task.runSyncStep)

        /**
         * value 会立刻导致 unboxing。
         */
        coeval.value match {
            case Right(v) =>
                // 如果没有执行 executeAsync (切换上下文)，我们可以在当前上下文中等到返回值。
                logger.info(s"Right value: $v")
            case Left(future) =>
                // 如果执行了 executeAsync, 任务切换到另一个线程中，那么我们将会得到 Left 值，并且得到一个 future.
                future.foreach(v => logger.info(s"Left value: $v"))
        }
    }

    "Task exception handler" should "" in {
        /**
         * 对于失败，可以通过 Task.runAsync 的回调来处理。
         *
         * 对于日志，可以通过定制 Scheduler 的 UncaughtExceptionReporter 参数来扑获
         *
         * 我们还可以用 onErrorHandleWith 让任务从失败中恢复：
         */

        val source = Task(throw new RuntimeException("Boom!"))

        val recovered = source.onErrorHandleWith {
            case _: RuntimeException =>
                // Oh, we know about timeouts, recover it
                Task.now("Recovered!")
            case other =>
                // 相比直接 throw exception 而言，raiseError 是一个更被推荐的方式。
                Task.raiseError(other)
        }

        recovered.runToFuture.foreach(println)
    }

    "Task can be converted to Reactive Publisher" should "" in {
        /**
         * 定义一个任务，并将任务转换成 Publisher
         */
        val task = Task.eval(Random.nextInt())
        val publisher: org.reactivestreams.Publisher[Int] = task.toReactivePublisher

        /**
         * 设置订阅者
         */
        import org.reactivestreams._
        publisher.subscribe(new Subscriber[Int] {
            def onSubscribe(s: Subscription): Unit = s.request(Long.MaxValue)
            def onNext(e: Int): Unit = logger.info(s"OnNext: $e")
            def onComplete(): Unit = logger.info("OnComplete")
            def onError(ex: Throwable): Unit = logger.error(s"ERROR: ${ex.getMessage}", ex)
        })

        /**
         * 执行（发布）任务
         * */
        task.runToFuture
    }
}
