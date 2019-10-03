import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import monix.execution.Cancelable
import org.scalatest.FlatSpec

import scala.io.Source

class Example_Reactive_1_Observable extends FlatSpec with StrictLogging{
    import monix.execution.Scheduler.Implicits.global
    import monix.reactive._
    import concurrent.duration._

    "Observable ticker" should "" in {
        /**
         * 新建一个每秒发生一次事件的 Observable。
         *
         * interval 是一个 Observable 的 builder，函数的说明如下：
         *   Creates an Observable that emits auto-incremented natural numbers(longs)
         */
        val source: Observable[Long] = Observable.interval(1.second)
                // 只发射偶数
                .filter(_ % 2 == 0)
                // 一次发射两个
                .flatMap(x => Observable(x, x))
                .take(10)

        /**
         *Observables 是 lazy 的, 在被 subscribe 之前不会执行任何动作...
         */
        val cancelable: Cancelable = source
                // On consuming it, we want to dump the contents to stdout for debugging purposes
                .dump(s"[Thread-${Thread.currentThread.getName}]")
                /**
                 * 触发！
                 */
                .subscribe()

        // 等待足够长的时间
        Thread.sleep(10000)

        /**
         * 停止消费
         */
        cancelable.cancel()
    }

    "sum iterable" should "" in {
        /**
         * fromIterable 是一个 Observable 的 builder。iterable 结束后会触发 onComplete 方法
         */
        val task: Task[Int] = Observable.fromIterable(1 to 3)
                .map(i => i + 2)
                .map(i => i * 3)
                .sum   // 返回 Lazy Observable. 但是一旦触发会收集所有上游值然后生成新的 Observable，所以上游不可是无尽流。
                /**
                 * firstL 函数将值封装在 Task 中返回. 一个 Task 可以被看作是 Lazy 的 subscriber.
                 *
                 * 在 Monix 中，任何带有 L 后缀的 Observable 函数都表示它将转换成 Task
                 * */
                .firstL

        /**
         * runToFuture 触发 subscriber 的执行动作
         */
        task.runToFuture.foreach(v => logger.info(s"$v"))
    }

    "Assign a Consumer to an Observable" should "" in {
        /**
         * 定义一个关于 Long 的 Observable.
         */
        val source: Observable[Long] = Observable.range(0, 1000)
                .take(100)
                .map(_ * 2)

        /************
         * 定义一个消费 Long，产出 Long 的 Consumer.
         *
         * Consumer 视 Observable 为一个 Stream，因此它具有所有相关的函数，比如 foldLeft:
         */
        val consumer: Consumer[Long, Long] = Consumer.foldLeft(0L)(_ + _)

        /**
         * 将 Consumer 赋予 Observable, 产生一个 Task.
         */
        val task: Task[Long] = source.consumeWith(consumer)
        task.runToFuture.foreach(println)

        /************
         * 就像前例所述，任何带 "L" 后缀的函数都可直接获得 Task，所以这个 Consumer 可以被 “糖”：
         */
        val task1 = source.foldLeftL(0L)(_ + _)
        task1.runToFuture.foreach(println)
    }

    "Lazy and Eager" should "" in {
        /**
         * now 等价于 pure，会立刻求解参数值.
         */
        val eagerObs = Observable.now {
            logger.info("Side effect")  // 立刻得到执行
            "Hello!"
        }
        eagerObs.foreachL(v => logger.info(s"$v")).runToFuture  // Hello

        /**
         * delay 等价于 eval，不要被字面迷惑，它只是表示 lazy
         */
        val lazyObs = Observable.delay {
            logger.info("Side effect")  // 不会执行直到 task被触发
            "Hello!"
        }
        lazyObs.foreachL(v => logger.info(s"$v")).runToFuture  // "Side effect" and "Hello"
    }

    "defer (suspend)" should "" in {
        /**
         * 和 fromFuture 一样，defer 用于 suspend 不安全的操作， 比如有一个不安全的前置运算（本例中打开一个文件）
         * 我们可以将之置于 suspend 中，然后在交给 Observable。(suspend 同时有 flatten 的功能)
         */
        val defer1: Observable[String] = Observable.suspend {
            // The side effect won't happen until subscription
            val lines: Iterator[String] = Source.fromFile("/path/to/file").getLines
            Observable.fromIterator(Task(lines))
        }

        // 等价于：
        val defer2: Observable[String] =
            Observable.eval{Source.fromFile("/path/to/file").getLines}
                    .flatMap(lines => Observable.fromIterator(Task(lines)))
    }

    "Observable.create" should "" in {
        /**
         * create 函数用于快速构建一个 Observable。
         */
        import monix.eval.Task
        import monix.execution.Ack
        import monix.reactive.Observable
        import monix.reactive.OverflowStrategy
        import monix.reactive.observers.Subscriber
        import scala.concurrent.duration._

        /**
         * 定义一个循环任务 Task，它将工作在即将定义的 Observable 的内部，这个函数将 Observable 的订阅者作（subscriber）为参数，
         * 每间隔一段时间产出一个(递增的)数字交给 subscriber（通过 onNext 回调）.
         *
         * Subscriber 是一个 Observer 的实现，它观察 Observable 的产出值。
         */
        def producerLoop(sub: Subscriber[Int], n: Int = 0): Task[Unit] = {
            /**
             *  onNext 是 Observer(subscriber) 定义给 Observable 的用于接收数据的回调窗口。参数就 Observable 传递给订阅者的值，
             *  onNext 返回 Future[Ack] 给 Observable 来告诉它 subscriber 的下一步状态，以便 Observable 决定是否继续发送数据。
             */
            Task.deferFuture(sub.onNext(n))
                    .delayExecution(100.millis)  // 只是延迟一下计算
                    .flatMap {
                        /**
                         * subscriber 的 onNext 返回一个 Task[Ack] 类型。 Ack 是一个专门作为信号的类型，
                         * 它包含两个子类型：Continue 和 Stop，分别表"继续"还是"结束"。可以被用于循环控制
                         */
                        case Ack.Continue => producerLoop(sub, n + 1)
                        case Ack.Stop => {
                            logger.info("Subscriber is requesting for stop")
                            Task.unit
                        }
                    }
        }

        val source: Observable[Int] =
            /**
             * create 是一个 Reader Monad 函数，接受一个 Subscriber 参数作为该 Observable 的消费端, 我们将这个 subscriber
             * 交给前面定义的 producerLoop Task。create 函数混合了 Task 和 subscriber 后生成 Observable（通过调用
             * builders.CreateObservable 来生成）
             *
             * 第一个参数 OverflowStrategy.Unbounded 表示缓存队列容量没有限制。这有可能导致缓存无限增长，直到内存耗尽。
             * */
            Observable.create(OverflowStrategy.Unbounded) { sub =>
                producerLoop(sub)
                        // guarantee 在任务结束后做清扫工作
                        .guarantee(Task(println("Producer has been completed")))
                        /**
                         * create 的参数签名是 Subscriber => Cancelable，因此在 producerLoop 执行结束后调用
                         * Task#runToFuture 来生成 Cancelable
                         * */
                        .runToFuture(sub.scheduler)  // subscriber 中包含一个缺省的 scheduler，可以作为执行上下文。
            }

        /**
         * subscribe 函数生成并注册 subscriber
         */
        val subscriber: Cancelable = source
          /**
           * take until 获得一个计数器，事件到后关闭注册。
           */
          .takeUntil(Observable.unit.delayExecution(1090.millis))
          .dump("Received").subscribe()

        // 等待足够的事件后退出（take until 规定的时间到达后就不会再收到数据了）
        Thread.sleep(2000)
        subscriber.cancel()
    }

    "Communicate via Queue" should "" in {
        /**
         * 队列模型：
         *
         * 除了常规的观察者和被观察者模型外，我们也可以从通讯的渠道上考虑建立反应机制。比如队列模型。Monix catnat 的 Queue 支持回压，
         * 因此我们可以用它来实现相同的目的：
         */
        import monix.catnap.ConcurrentQueue
        import monix.eval.Task
        import monix.reactive.Observable

        /**
         * 定义一个任务向 Queue 中填充内容. Queue 的参数表示 Queue
         */
        def feedItem[A](queue: ConcurrentQueue[Task, A], item: A): Task[Unit] =
            queue.offer(item)

        /**
         * 定义一个任务从 Queue 中取出内容并处理。
         */
        def processStream[A](observable: Observable[A]): Task[Unit] = observable
              // 并发 3 个处理线程
              .mapParallelUnordered(3)(i => Task(println(i)))
              .completedL

        /**
         * 生成一个容量为 128, MPSC (Multiple publisher Single Consumer) 的 Queue.
         *
         * Queue 的容量可以有 Unbounded(不限量), 和 Bounded（一般是 2 的 N 次方）
         *
         * 类型可以有：
         *   MPMC: multi-producer, multi-consumer
         *   MPSC: multi-producer, single-consumer
         *   SPMC: single-producer, multi-consumer
         *   SPSC: single-producer, single-consumer
         */
        import monix.execution.ChannelType.MPSC
        import monix.execution.BufferCapacity.Bounded
        ConcurrentQueue[Task].withConfig[Int](
            capacity = Bounded(128),
            channelType = MPSC
        ).flatMap { queue =>
            Task
              /** parZip2：表示同时执行两个任务。（类似的还有 parZip3, ...）*/
              .parZip2(
                  // 向 Queue 中填入一个数字 2.
                  feedItem(queue, 2) /*Observable.fromIterable(1 to 10).foreachL(i => feedItem(queue, i))*/,
                  // 通过 repleatEvalF 生成一个反复执行的 Observable 读取 Queue 中的数据.
                  processStream(Observable.repeatEvalF(queue.poll))
              )
        }.runToFuture

        Thread.sleep(1000)
    }

    "Subject mode" should "" in {
        /**
         * Subject "主题"模型：
         *
         * 类似 Queue 模型，主题也是一种发布者和订阅者共享的模型。也可以实现反应式。
         */
        import monix.eval.Task
        import monix.execution.Ack
        import monix.reactive.subjects.ConcurrentSubject
        import monix.reactive.{MulticastStrategy, Observable, Observer}

        /**
         * 生成一个"并发"主题
         */
        val subject: ConcurrentSubject[Int, Int] = ConcurrentSubject[Int](MulticastStrategy.replay)

        /**
         * 主题的特殊性在于，它同时代表 observer 和 observable：
         */
        def feedItem[A](observer: Observer[A], item: A): Task[Ack] = Task.deferFuture(observer.onNext(item))

        def processStream[A](observable: Observable[A]): Task[Unit] = observable
              .mapParallelUnordered(3)(i => Task(println(i)))
              .completedL

        /**
         * 因此主题可以被同时做为数据发布和数据处理函数的参数
         */
        Task.parZip2(
            feedItem(subject, 2),
            processStream(subject)
        ).runToFuture

        Thread.sleep(1000)
    }
}
