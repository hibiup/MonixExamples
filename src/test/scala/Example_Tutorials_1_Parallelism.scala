import com.typesafe.scalalogging.StrictLogging
import org.scalatest.FlatSpec

/**
 * https://monix.io/docs/3x/tutorials/parallelism.html
 */

class Example_Tutorials_1_Parallelism extends FlatSpec with StrictLogging{
    // On evaluation a Scheduler is needed
    import monix.execution.Scheduler.Implicits.global
    import scala.concurrent.duration._

    // For Task
    import monix.eval.Task

    // For Observable
    import monix.reactive._

    /**
     * 利用 Task 实现最简单的并发.
     * */
    "The Naive Way" should "" in {
        val items = 0 until 1000

        // The list of all tasks needed for execution
        val tasks = items.map(i => Task(i * 2))

        /**
         * gather 会严格按顺序触发并返回结果，如果对执行顺序没有要求，可以使用 `Task.gatherUnordered`.
         * gatherUnordered 以非阻塞方式触发任务，执行的速度可以更快。
         */
        // Processing in parallel
        val aggregate = Task.gather(tasks).map(_.toList)

        // Evaluation:
        aggregate.foreach(println)
        //=> List(0, 2, 4, 6, 8, 10, 12, 14, 16,...
    }

    /**
     * 直接使用 gather 会导致所有任务被同时触发，这可能因为资源不足而导致阻塞， 可以使用 Task.sliding 对
     * 任务先进行切片，然后再交由 gather(gatherUnordered)
     */
    "Imposing a Parallelism Limit" should "" in {
        val items = 0 until 10

        // The list of all tasks needed for execution
        val tasks: Seq[Task[Int]] = items.map(i => Task(i * 2))

        /**
         * sliding 的两个参数，第一个 size 表示每次取值的个数。第二个 step 表示间隔多少取一次值。以本例为例：
         * 间隔 3 个取一次值，每次取 2 个，“0,1,2” 中取出 “0,1” 意味着数字 "2" 会被丢弃，同样下一个 step
         * 从 “3,4,5” 中取出 “3,4”。同理如果 step 小于 size，那么会有数字被重复取得。
         */
        // Building batches of 10 tasks to execute in parallel:
        val batches: Iterable[Task[Seq[Int]]] = tasks.sliding(2, 3)  // size, step
                .map(b => Task.gather(b)).to(Iterable)

        /**
         * 注意：以上 gather 的是 sliding 的值(Tasks 的集合，Tasks 的 Task)，而不是每一个 task 本身的值.
         * 对于 Iterable, 要用 sequence 来取得里面的每一个运算结果。
         */
        // Sequencing batches, then flattening the final result
        val aggregate = Task.sequence(batches).map(_.flatten.toList)

        // Evaluation:
        aggregate.foreach(println)
        //=> List(0, 2, 4, 6, 8, 10, 12, 14, 16,...
    }

    /**
     *  另一种实现并行运算的是通过 Observable.mapParallelUnordered 方法. 这个方法同样不管执行顺序。
     *  如果需要保持顺序，要使用 mapParallelOrdered 函数。
     *
     *  非阻塞（Unordered）的执行效率更高并且还能避免一种情况就是前面的任务阻塞，后面的就全都阻塞，在非阻塞
     *  的系统中，只要系统通道畅通，后续的任务就还能继续保持前进。
     */
    "Observable.mapParallelUnordered" should "" in {
        /**
         * 定义一个可观测“流”
         */
        val source = Observable.range(0,1000)

        /**
         * 从源段，以 10 个一组进行并行计算。
         */
        // The parallelism factor needs to be specified
        val processed = source.mapParallelUnordered(parallelism = 10) { i =>
            Task(i * 2)
        }

        // Evaluation:
        processed.toListL.foreach(println)
        //=> List(2, 10, 0, 4, 8, 6, 12...
    }

    /**
     * 我们也可以将并发限制设置在流的消费端，也就是说由消费端来决定一次接受几个数字，而源端只管尽力发送。
     * */
    "Consumer.loadBalancer" should "" in {
        import monix.eval._
        import monix.reactive._

        /**
         * 定义一个消费者和它的消费计算方程
         * */
        val sumConsumer = Consumer.foldLeft[Long,Long](0L)(_ + _)

        /**
         * Consumer.loadBalance 生成一个负载均衡，给定并发数(parallelism)，消费者，和每个消费的结果的
         * 合并算法。
         *
         */
        val loadBalancer = {
            Consumer.loadBalance(parallelism=10, sumConsumer)
                    .map(a =>
                        a.sum)
        }

        val observable: Observable[Long] = Observable.range(0, 100000)
        /**
         * observable.consumeWith 将消费端负载施用于源端。
         */
        val task: Task[Long] = observable.consumeWith(loadBalancer)

        // Consume the whole stream and get the result
        task.runToFuture.foreach(println)  // 异步执行
        //=> 4999950000

        Thread.sleep(1000)
    }

    "flatMap and MergeMap" should "" in {
        val source: Observable[Int] = Observable(2) ++ Observable(3, 4).delayExecution(50.millis)

        /**
         * mergeMap 接受上游输入，返回新的 Observable. 在本例中，上游先发送一个数字 2，然后间隔50毫秒。
         * mergeMap 将数字 2 映射到一个新的间隔 50 毫秒送出 2A, 2B 队列的 Observable。之后 mergeMap 再次
         * 接受到数字 3, 同样映射成 3A, 3B, 紧接着接受到 4 -> 4A, 4B, 由于 3A 到 4A 之间没有间隔限制，因此
         * 被连续发送出去，然后 3B(相对于 3A 间隔 50 毫秒后)和 4B(相对于4A) 被发送出去。
         *
         * 这个例子演示了 mergeMap 具有合并 observable 的能力。当前后两个 Observable 的时间线存在重叠的时候
         * 会被合并在一起。
         */
        val mergedStream: Observable[String] = {
            source.mergeMap(i =>
                Observable(
                    s"mergeMap: [${Thread.currentThread.getName}] -> ${i}A",
                    s"mergeMap: [${Thread.currentThread.getName}] -> ${i}B"
                ).delayOnNext(50.millis)
            )
        }

        mergedStream.foreachL(println).runToFuture
        Thread.sleep(110)

        /**
         * 相比而言 flatMap 不具备合并 observable 的能力。因此当多个 observable 的时间线存在重叠的时候，
         * 后一个 observable 不能被合并到前一个去，subscriber 必须按 observable 生成的顺序逐个消费。
         *
         * 例如本例虽然 4A 的产生时间早于 3B，但是订阅者也无法提前收到它，必须等到 （3A, 3B） 这个系列被消费掉之后
         * 才接受到 (4A, 4B)
         */
        val flatStream: Observable[String] = {
            source.flatMap(i =>
                Observable(
                    s"flatMap: [${Thread.currentThread.getName}] -> ${i}A",
                    s"flatMap: [${Thread.currentThread.getName}] -> ${i}B"
                ).delayOnNext(50.millis)
            )
        }

        flatStream.foreachL(println).runToFuture
        Thread.sleep(500)
    }

    "switchMap" should "" in {
        /**
         * 和 mergeMap 不同，switchMap 不合并两个 Observable，而是用后一个覆盖前一个：
         */
        import cats.effect.ExitCase

        def child(i: Int): Observable[String] = {
            Observable(s"${i}A", s"${i}B", s"${i}C")
              .delayOnNext(50.millis)
              .guaranteeCase {
                  case ExitCase.Completed => Task(println(s"$i: Request has been completed."))
                  case ExitCase.Error(e) => Task(println(s"$i: Request has encountered an error."))
                  case ExitCase.Canceled => Task(println(s"$i: Request has been canceled."))
              }
        }

        Observable(2, 3, 4).delayOnNext(100.millis)
          .switchMap(child)
          .foreachL(println).runToFuture

        Thread.sleep(1000)
    }

    "Error handler" should "" in {
        import monix.reactive.Observable

        val observable = Observable(1, 2, 3) ++ Observable.raiseError(new Exception("Boom!")) ++ Observable(0)

        /**
         * 当异常发生的时候 onErrorHandle 被调用
         */
        observable.onErrorHandle{ e =>
            logger.info(e.getMessage)
            4  // 将错误转换成 4
        }.foreachL(println).runToFuture

        /**
         * 可以插入一个新的 Observable
         */
        observable
                .onErrorHandleWith(_ => Observable(4, 5))
                .foreachL(println).runToFuture

        /**
         * 也可以删除出错的数据
         */
        observable
                .onErrorHandleWith(_ => Observable.unit)
                .foreachL{
                    case () => println(s"something has been dropped")
                    case v => println(s"$v")
                }.runToFuture

        Thread.sleep(100)
    }

    "Subjects has dual-way message sending ability" should "" in {
        /**
         * Subjects
         */
    }
}
