/*
 * Copyright 2020, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import io.opentelemetry.auto.bootstrap.instrumentation.java.concurrent.CallableWrapper
import io.opentelemetry.auto.bootstrap.instrumentation.java.concurrent.RunnableWrapper
import io.opentelemetry.auto.test.AgentTestRunner
import io.opentelemetry.auto.test.utils.ConfigUtils
import io.opentelemetry.sdk.trace.SpanData
import spock.lang.Shared

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import static io.opentelemetry.auto.test.utils.TraceUtils.runUnderTrace
import static org.junit.Assume.assumeTrue

class ExecutorInstrumentationTest extends AgentTestRunner {

  static {
    ConfigUtils.updateConfig {
      System.setProperty("ota.trace.executors", "ExecutorInstrumentationTest\$CustomThreadPoolExecutor")
    }
  }

  @Shared
  def executeRunnable = { e, c -> e.execute((Runnable) c) }
  @Shared
  def executeForkJoinTask = { e, c -> e.execute((ForkJoinTask) c) }
  @Shared
  def submitRunnable = { e, c -> e.submit((Runnable) c) }
  @Shared
  def submitCallable = { e, c -> e.submit((Callable) c) }
  @Shared
  def submitForkJoinTask = { e, c -> e.submit((ForkJoinTask) c) }
  @Shared
  def invokeAll = { e, c -> e.invokeAll([(Callable) c]) }
  @Shared
  def invokeAllTimeout = { e, c -> e.invokeAll([(Callable) c], 10, TimeUnit.SECONDS) }
  @Shared
  def invokeAny = { e, c -> e.invokeAny([(Callable) c]) }
  @Shared
  def invokeAnyTimeout = { e, c -> e.invokeAny([(Callable) c], 10, TimeUnit.SECONDS) }
  @Shared
  def invokeForkJoinTask = { e, c -> e.invoke((ForkJoinTask) c) }
  @Shared
  def scheduleRunnable = { e, c -> e.schedule((Runnable) c, 10, TimeUnit.MILLISECONDS) }
  @Shared
  def scheduleCallable = { e, c -> e.schedule((Callable) c, 10, TimeUnit.MILLISECONDS) }

  def "#poolImpl '#name' propagates"() {
    setup:
    assumeTrue(poolImpl != null) // skip for Java 7 CompletableFuture
    def pool = poolImpl
    def m = method

    new Runnable() {
      @Override
      void run() {
        runUnderTrace("parent") {
          // this child will have a span
          def child1 = new JavaAsyncChild()
          // this child won't
          def child2 = new JavaAsyncChild(false, false)
          m(pool, child1)
          m(pool, child2)
          child1.waitForCompletion()
          child2.waitForCompletion()
        }
      }
    }.run()

    TEST_WRITER.waitForTraces(1)
    List<SpanData> trace = TEST_WRITER.traces[0]

    expect:
    TEST_WRITER.traces.size() == 1
    trace.size() == 2
    trace.get(0).name == "parent"
    trace.get(1).name == "asyncChild"
    trace.get(1).parentSpanId == trace.get(0).spanId

    cleanup:
    if (pool?.hasProperty("shutdown")) {
      pool?.shutdown()
    }

    // Unfortunately, there's no simple way to test the cross product of methods/pools.
    where:
    name                     | method              | poolImpl
    "execute Runnable"       | executeRunnable     | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Runnable"        | submitRunnable      | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Callable"        | submitCallable      | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAll"              | invokeAll           | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAll with timeout" | invokeAllTimeout    | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAny"              | invokeAny           | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAny with timeout" | invokeAnyTimeout    | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))

    // Scheduled executor has additional methods and also may get disabled because it wraps tasks
    "execute Runnable"       | executeRunnable     | new ScheduledThreadPoolExecutor(1)
    "submit Runnable"        | submitRunnable      | new ScheduledThreadPoolExecutor(1)
    "submit Callable"        | submitCallable      | new ScheduledThreadPoolExecutor(1)
    "invokeAll"              | invokeAll           | new ScheduledThreadPoolExecutor(1)
    "invokeAll with timeout" | invokeAllTimeout    | new ScheduledThreadPoolExecutor(1)
    "invokeAny"              | invokeAny           | new ScheduledThreadPoolExecutor(1)
    "invokeAny with timeout" | invokeAnyTimeout    | new ScheduledThreadPoolExecutor(1)
    "schedule Runnable"      | scheduleRunnable    | new ScheduledThreadPoolExecutor(1)
    "schedule Callable"      | scheduleCallable    | new ScheduledThreadPoolExecutor(1)

    // ForkJoinPool has additional set of method overloads for ForkJoinTask to deal with
    "execute Runnable"       | executeRunnable     | new ForkJoinPool()
    "execute ForkJoinTask"   | executeForkJoinTask | new ForkJoinPool()
    "submit Runnable"        | submitRunnable      | new ForkJoinPool()
    "submit Callable"        | submitCallable      | new ForkJoinPool()
    "submit ForkJoinTask"    | submitForkJoinTask  | new ForkJoinPool()
    "invoke ForkJoinTask"    | invokeForkJoinTask  | new ForkJoinPool()
    "invokeAll"              | invokeAll           | new ForkJoinPool()
    "invokeAll with timeout" | invokeAllTimeout    | new ForkJoinPool()
    "invokeAny"              | invokeAny           | new ForkJoinPool()
    "invokeAny with timeout" | invokeAnyTimeout    | new ForkJoinPool()

    // CustomThreadPoolExecutor would normally be disabled except enabled above.
    "execute Runnable"       | executeRunnable     | new CustomThreadPoolExecutor()
    "submit Runnable"        | submitRunnable      | new CustomThreadPoolExecutor()
    "submit Callable"        | submitCallable      | new CustomThreadPoolExecutor()
    "invokeAll"              | invokeAll           | new CustomThreadPoolExecutor()
    "invokeAll with timeout" | invokeAllTimeout    | new CustomThreadPoolExecutor()
    "invokeAny"              | invokeAny           | new CustomThreadPoolExecutor()
    "invokeAny with timeout" | invokeAnyTimeout    | new CustomThreadPoolExecutor()

    // Internal executor used by CompletableFuture
    "execute Runnable"       | executeRunnable     | java7SafeCompletableFutureThreadPerTaskExecutor()
  }

  def "#poolImpl '#name' disabled wrapping"() {
    setup:
    def pool = poolImpl
    def m = method
    def w = wrap

    JavaAsyncChild child = new JavaAsyncChild(true, true)
    new Runnable() {
      @Override
      void run() {
        runUnderTrace("parent") {
          m(pool, w(child))
        }
      }
    }.run()
    // We block in child to make sure spans close in predictable order
    child.unblock()

    TEST_WRITER.waitForTraces(2)

    expect:
    TEST_WRITER.traces.size() == 2
    TEST_WRITER.traces[0].size() == 1
    TEST_WRITER.traces[0][0].name == "parent"
    TEST_WRITER.traces[1].size() == 1
    TEST_WRITER.traces[1][0].name == "asyncChild"

    cleanup:
    pool?.shutdown()

    where:
    // Scheduled executor cannot accept wrapped tasks
    // TODO: we should have a test that passes lambda, but this is hard
    // because this requires tests to be run in java8+ only.
    // Instead we 'hand-wrap' tasks in this test.
    name                | method           | wrap                        | poolImpl
    "execute Runnable"  | executeRunnable  | { new RunnableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "submit Runnable"   | submitRunnable   | { new RunnableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "submit Callable"   | submitCallable   | { new CallableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "schedule Runnable" | scheduleRunnable | { new RunnableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "schedule Callable" | scheduleCallable | { new CallableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
  }

  def "#poolImpl '#name' reports after canceled jobs"() {
    setup:
    def pool = poolImpl
    def m = method
    List<JavaAsyncChild> children = new ArrayList<>()
    List<Future> jobFutures = new ArrayList<>()

    new Runnable() {
      @Override
      void run() {
        runUnderTrace("parent") {
          try {
            for (int i = 0; i < 20; ++i) {
              // Our current instrumentation instrumentation does not behave very well
              // if we try to reuse Callable/Runnable. Namely we would be getting 'orphaned'
              // child traces sometimes since state can contain only one parent span - and
              // we do not really have a good way for attributing work to correct parent span
              // if we reuse Callable/Runnable.
              // Solution for now is to never reuse a Callable/Runnable.
              final JavaAsyncChild child = new JavaAsyncChild(false, true)
              children.add(child)
              try {
                Future f = m(pool, child)
                jobFutures.add(f)
              } catch (InvocationTargetException e) {
                throw e.getCause()
              }
            }
          } catch (RejectedExecutionException e) {
          }

          for (Future f : jobFutures) {
            f.cancel(false)
          }
          for (JavaAsyncChild child : children) {
            child.unblock()
          }
        }
      }
    }.run()

    TEST_WRITER.waitForTraces(1)

    expect:
    TEST_WRITER.traces.size() == 1

    where:
    name                | method           | poolImpl
    "submit Runnable"   | submitRunnable   | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Callable"   | submitCallable   | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))

    // Scheduled executor has additional methods and also may get disabled because it wraps tasks
    "submit Runnable"   | submitRunnable   | new ScheduledThreadPoolExecutor(1)
    "submit Callable"   | submitCallable   | new ScheduledThreadPoolExecutor(1)
    "schedule Runnable" | scheduleRunnable | new ScheduledThreadPoolExecutor(1)
    "schedule Callable" | scheduleCallable | new ScheduledThreadPoolExecutor(1)

    // ForkJoinPool has additional set of method overloads for ForkJoinTask to deal with
    "submit Runnable"   | submitRunnable   | new ForkJoinPool()
    "submit Callable"   | submitCallable   | new ForkJoinPool()
  }

  private static Executor java7SafeCompletableFutureThreadPerTaskExecutor() {
    try {
      return new CompletableFuture.ThreadPerTaskExecutor()
    } catch (NoClassDefFoundError e) {
      return null
    }
  }

  static class CustomThreadPoolExecutor extends AbstractExecutorService {
    volatile running = true
    def workQueue = new LinkedBlockingQueue<Runnable>(10)

    def worker = new Runnable() {
      void run() {
        try {
          while (running) {
            def runnable = workQueue.take()
            runnable.run()
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt()
        } catch (Exception e) {
          e.printStackTrace()
        }
      }
    }

    def workerThread = new Thread(worker, "ExecutorTestThread")

    private CustomThreadPoolExecutor() {
      workerThread.start()
    }

    @Override
    void shutdown() {
      running = false
      workerThread.interrupt()
    }

    @Override
    List<Runnable> shutdownNow() {
      running = false
      workerThread.interrupt()
      return []
    }

    @Override
    boolean isShutdown() {
      return !running
    }

    @Override
    boolean isTerminated() {
      return workerThread.isAlive()
    }

    @Override
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      workerThread.join(unit.toMillis(timeout))
      return true
    }

    @Override
    def <T> Future<T> submit(Callable<T> task) {
      def future = newTaskFor(task)
      execute(future)
      return future
    }

    @Override
    def <T> Future<T> submit(Runnable task, T result) {
      def future = newTaskFor(task, result)
      execute(future)
      return future
    }

    @Override
    Future<?> submit(Runnable task) {
      def future = newTaskFor(task, null)
      execute(future)
      return future
    }

    @Override
    def <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
      return super.invokeAll(tasks)
    }

    @Override
    def <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
      return super.invokeAll(tasks)
    }

    @Override
    def <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
      return super.invokeAny(tasks)
    }

    @Override
    def <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
      return super.invokeAny(tasks)
    }

    @Override
    void execute(Runnable command) {
      workQueue.put(command)
    }
  }
}
