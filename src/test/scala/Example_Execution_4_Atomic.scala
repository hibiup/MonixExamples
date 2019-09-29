import com.typesafe.scalalogging.StrictLogging
import org.scalatest.FlatSpec

class Example_Execution_4_Atomic extends FlatSpec with StrictLogging {
    "Atomic Numeric" should "" in {
        import java.util.concurrent.atomic._

        /**
         * Java Atomic 支持的数字类型有限，包括自然数类型 AtomicInteger，AtomicLong... 例如：
         */
        val ai = new AtomicInteger(1)
        logger.info(ai.get().toString) // 原子获得当前值
        logger.info(ai.incrementAndGet().toString) // 先递增，然后获得当前值
        logger.info(ai.get().toString) // 多次获取不改变值
        logger.info(ai.get().toString)
        logger.info(ai.getAndIncrement().toString) // 先获得当前值，然后递增
        logger.info(ai.get().toString) // 获得上一条递增后的值
        // 还有很多方法，比如递减，累积……

        /**
         * Monix Atomic 支持更多，包括 BigDecimal, BigInt, 甚至 String 等
         */
        import monix.execution.atomic.{Atomic => MonixAtomic}

        // 首先 Monix 提供了一个统一的 Atomic 类型接口来获取所有的原子类型
        val atomicString = MonixAtomic("Hello, Monix Atomic")

        val atomicBigDecimal = MonixAtomic(BigDecimal("12345678901234567890"))
        println(atomicBigDecimal.incrementAndGet(1)) //12345678901234567891
        assert( atomicBigDecimal.get === BigDecimal("12345678901234567891"))
    }

    "Atomic Reference" should "" in {
        import java.util.concurrent.atomic.{AtomicReference => JAtomicReference}
        /**
         * Java 原子引用类型：java.util.concurrent.atomic.AtomicReference 的比较必须基于引用，而非结构值
         */
        final case class User(name:String, age:Int)

        val user = User("Joun", 16)
        val atomicUserRef:JAtomicReference[User] = new JAtomicReference[User](user)
        println(atomicUserRef.get())

        val updateUser = User("Shinichi", 17)
        var isSuccess:Boolean = atomicUserRef.compareAndSet(user, updateUser) // 原子替换
        println(s"Update is $isSuccess")  // true
        println(atomicUserRef.get())

        val javaAtomicRef = new JAtomicReference(0.0)
        // 比较必须基于引用，而非结构值
        isSuccess = javaAtomicRef.compareAndSet(0.0, 0.1)
        println(s"Update is $isSuccess")  // false
        assert(javaAtomicRef.get === 0.0)

        /**
         * Monix 的比较允许基于值
         */
        import monix.execution.atomic.{Atomic => MonixAtomic}

        val momixAtomicRef = MonixAtomic(0.0)
        // 其次，Monix Atomic Reference 可以基于值比较
        isSuccess = momixAtomicRef.compareAndSet(0.0, 0.1)
        println(s"Update is $isSuccess")  // true
        assert(momixAtomicRef.get === 0.1)
    }

    "Atomic Reference for Queue" should "" in {
        import collection.immutable.Queue
        import java.util.concurrent.atomic.AtomicReference

        /**
         * 这个例子利用 AtomicReference 实现对 Queue 的安全修改
         */
        def pushElementAndGet[T <: AnyRef, U <: T](ref: AtomicReference[Queue[T]], elem: U): Queue[T] = {
            var continue = true
            var update = null

            while (continue) {
                var current: Queue[T] = ref.get()
                var update = current.enqueue(elem)
                continue = !ref.compareAndSet(current, update)
            }

            update
        }
    }
}
