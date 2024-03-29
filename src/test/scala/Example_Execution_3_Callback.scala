import com.typesafe.scalalogging.StrictLogging
import monix.execution.Callback
import org.scalatest.FlatSpec

class Example_Execution_3_Callback extends FlatSpec with StrictLogging {
    "Callback" should "" in {
        /**
         * https://monix.io/docs/3x/execution/callback.html
         *
         * Callback 和 IO 的 async 类似，它可以给 Task 在结束的时候附加一个回调函数。这个回调函数通过协程框架来处理。
         * 不同的是 Monix 的 callback 可以定义左右值的数据类型, 不想 IO Async 的左值必须是 Throwable
         */

        // 定义一个 Callback，类型参数分别是左右值
        val callback = new Callback[Throwable, Int] {
            def onSuccess(value: Int): Unit =
                logger.info(s"Receive: $value")
            def onError(ex: Throwable): Unit =
                logger.error(ex.getMessage, ex)
        }

        val task = monix.eval.Task{
            logger.info("Return: 1")
            1
        }

        import monix.execution.Scheduler.Implicits.global
        task.runAsync(callback)

        // 需要注意的是callback 不是 stack 安全的：https://monix.io/docs/3x/execution/callback.html
    }

    "Promise to Callback" should "" in {
        /**
         * 可以将一个 Promise 转换成 Callback
         */
        val p = scala.concurrent.Promise[String]()
        // p: scala.concurrent.Promise[String] = Future(<not completed>)

        val callback = Callback.fromPromise(p)
        // callback: monix.execution.Callback[Throwable,String] = <function1>
    }
}
