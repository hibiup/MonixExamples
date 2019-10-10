import com.typesafe.scalalogging.StrictLogging
import org.scalatest.FlatSpec


class Example_Catnap_1_CircuitBreaker extends FlatSpec with StrictLogging{
    "CircuitBreaker" should "" in {
        /**
         * Circuit breaker: 断路器，允许服务失败后将服务从系统环路中断下来，以停止继续接受用户的服务。
         *
         * 断路器基于状态机来管理三种状态：
         *
         *   Close:（闭路）正常接受服务。
         *       状态机处于 Close 状态下，它接受正常请求，并维护一个失败次数计数器，当失败的次数达到规定的上线，状态机转入 Open（断开）状态。
         *
         *   Open:（断开）状态下断路器拒绝所有服务。
         *       对所有的请求快速返回 ExecutionRejectedException 异常。当 Open 的状态达到 resetTimeout 时长后断路器转入 HalfOpen
         *       状态以允许常识性地接入一个请求来探测服务是否恢复正常。
         *
         *   HalfOpen: 半断开
         *       处于这个状态下的断路器将尝试接受且只接受一个服务（其它并发服务保持返回 ExecutionRejectedException）用于探测服务是否恢复正常。
         *       如果服务恢复了正常，状态将被重置回 Close 状态，失败计数器和 resetTimeout 也都会被重置。如果服务仍然失败，状态被设置回
         *       Open 状态，resetTimeout 将被乘以一个补偿因子(直至 maxResetTimeout)等待下一次被允许进入 HalfOpen 状态.
         */

        import monix.catnap.CircuitBreaker
        import monix.eval._
        import scala.concurrent.duration._
        import monix.execution.Scheduler.Implicits.global

        /**
         * 1）定义一个断路器，以允许对 Task 进行服务管理。（注意，断路器本身也是由 Task 实现的。）
         */
        val circuitBreaker: CircuitBreaker[Task] = {
            val resetTimeout = 2
            CircuitBreaker[Task].of(
                maxFailures = 1, // 允许的最大失败次数
                resetTimeout = resetTimeout.seconds, // Open 后转入 HalfOpen 的时长。
                exponentialBackoffFactor = 2, // 延时补偿因子（可选）
                maxResetTimeout = 10.minutes, // 最大延时（可选）

                /**
                 * 可以添加对各种事件的回调函数（可选）
                 */
                onRejected = Task {
                    logger.info("Task rejected in Open or HalfOpen")
                },
                onClosed = Task {
                    logger.info("Switched to Close, accepting tasks again")
                },
                onHalfOpen = Task {
                    logger.info("Switched to HalfOpen, accepted one task for testing")
                },
                onOpen = Task {
                    logger.info(s"Switched to Open, all incoming tasks rejected for the next ${resetTimeout} seconds")
                }
            )
        }.runSyncUnsafe()  // 生成断路器


        /**
         * 2）定义一个可能出问题的 Task
         */
        def problematicTask(i:Int): Task[Int] = Task {
            logger.info(s"Task is reached, parameter is $i")
            if (i % 3 == 0) i
            else throw new RuntimeException("Boom..")
        }

        /**
         * 3) 将问题任务置于断路器保护之下。返回一个代理
         */
        def protectedTask(i:Int): Task[Int] = circuitBreaker.protect(problematicTask(i))

        /**
         * 4) 多次调用被保护的代理
        * */
        for (i <- Range(0, 15)){
            protectedTask(i).runAsync{
                case Right(n) => logger.info(s"Task return: $n")
                case Left(t) => logger.info(s"Task failed by ${t.getMessage}")
            }
            Thread.sleep(1000)
        }
    }
}
