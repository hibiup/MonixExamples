import com.typesafe.scalalogging.StrictLogging
import monix.execution.Cancelable
import monix.execution.cancelables.BooleanCancelable
import org.scalatest.FlatSpec

class Example_Execution_Cancelable extends FlatSpec with StrictLogging{
    "An empty cancelable" should "" in {
        val c = Cancelable.empty // 等价于 Cancelable()

        val c1 = Cancelable(() => println("Canceled!"))
        c1.cancel()  // Canceled!

        // BooleanCancelable 比 Cancelable 多了一个状态查询方法：isCanceled
        val c2 = BooleanCancelable(() => println("Effect!"))
        logger.info(s"State: ${c2.isCanceled }")
        c2.cancel()  // Effect!
        logger.info(s"State: ${c2.isCanceled }")
    }
}
