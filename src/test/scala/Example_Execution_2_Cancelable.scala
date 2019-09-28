import com.typesafe.scalalogging.StrictLogging
import monix.execution.{Cancelable, Scheduler}
import monix.execution.cancelables.{BooleanCancelable, CompositeCancelable, OrderedCancelable, RefCountCancelable}
import org.scalatest.FlatSpec

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class Example_Execution_2_Cancelable extends FlatSpec with StrictLogging{
    "An empty cancelable" should "" in {
        val c = Cancelable.empty // 等价于 Cancelable()

        // 可以给 cancel 事件加一个动作
        val c1 = Cancelable(() => println("Canceled!"))
        c1.cancel()  // Canceled!

        // BooleanCancelable 比 Cancelable 多了一个状态查询方法：isCanceled
        val c2 = BooleanCancelable(() => println("Effect!"))
        logger.info(s"BooleanCancelable state: ${c2.isCanceled }")
        c2.cancel()  // Effect!
        logger.info(s"BooleanCancelable state: ${c2.isCanceled }")
    }

    "CompositeCancelable" should "" in {
        /**
         * Composite cancelable 可以让 Cancelable 获得组合能力。这给程序赋予了同时关闭多个资源的能力
         */
        import monix.execution.Cancelable

        val c: CompositeCancelable = CompositeCancelable()

        // 组合多个 CompositeCancelable
        c += Cancelable(() => println("Canceled #1"))
        val c2 = Cancelable(() => println("Canceled #2"))
        c += c2
        c += Cancelable(() => println("Canceled #3"))

        // 可以移除
        c -= c2

        // 一次触发多个 Cancelable
        c.cancel()

        /**
         * 对一个已经 cancel 的对象再次附加新的 CompositeCancelable 会立刻触发新的 cancel 行为，但是这个行为
         * 也只会加载在新的对象上
         */
        c += Cancelable(() => println("Canceled #4"))
        // => Canceled #4
    }

    "OrderedCancelable" should "" in {
        val scheduler = Scheduler.computation()

        /**
         * OrderedCancelable 通过给 Cancelable 设定一个优先级（order）来保证总有一个（order值最大的那个）
         * 对象会被关闭。
         *
         * 注意 OrderedCancelable  不是 Compsitable 的，因此当它关闭的时候不会将所有的 Cancelable 都关闭
         * 它只关闭最大的那个。
         */
        val c = OrderedCancelable()

        /**
         * 设置一个 cancelable，order 设置为 2
         * */
        scheduler.execute(() => {
          logger.info("Update cancelable for second position")
          c.orderedUpdate(Cancelable(() => println("Number #2")), order = 2)
        })

        /**
         * 设置一个 order 值较小的 cancelable,].
         *
         * 注意，使用的是 orderedUpdate, 而不是 append, OrderedCancelable 比较新的 order 值来决定是否覆盖之前的那个
         */
        scheduler.scheduleOnce(1.seconds) {
            logger.info("Update cancelable for first position")
            c.orderedUpdate(Cancelable(() => println("Number #1")), order = 1)
        }

        /*val termination: Future[Boolean] = */scheduler.awaitTermination(2.seconds /* ,global*/)  // awaitTermination 本身也是异步的
        // Await.result(termination, Duration.Inf)

        /**
         * 关闭 OrderedCancelable 会看到 order 值较大的那个 Cancelable 被关闭
         */
        c.cancel()
    }

    "RefCountCancelable" should "" in {
        /**
         * RefCountCancelable 提供了一个安全的结束容器，它允许用户拥有多份引用，但是只有最后一份被 cancel 后才会真正结束，
         */

        // 得到一个 cancelable（赋予一个 callback ）
        val refs = RefCountCancelable { () => println("Everything was canceled")}

        // 获得两份引用
        val ref1 = refs.acquire()
        val ref2 = refs.acquire()

        // cancel 原始引用不会触发 callback
        logger.info("End original one")
        refs.cancel()

        // 虽然状态上处于结束状态
        refs.isCanceled
        // res: Boolean = true

        // 也无法从结束的引用中获得新的引用
        val ref3 = refs.acquire()

        assert(ref3 == Cancelable.empty)

        // 终止全部引用
        logger.info("End second one")
        ref1.cancel()

        logger.info("End the last one")
        ref2.cancel()  // 最后一个终止的引用将触发 callback
    }
}
