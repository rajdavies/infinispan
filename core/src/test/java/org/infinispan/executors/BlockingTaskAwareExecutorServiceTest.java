package org.infinispan.executors;

import org.infinispan.test.AbstractInfinispanTest;
import org.infinispan.util.DefaultTimeService;
import org.infinispan.util.concurrent.BlockingRunnable;
import org.infinispan.util.concurrent.BlockingTaskAwareExecutorService;
import org.infinispan.util.concurrent.BlockingTaskAwareExecutorServiceImpl;
import org.testng.annotations.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Simple executor test
 *
 * @author Pedro Ruivo
 * @since 5.3
 */
@Test(groups = "functional", testName = "executors.BlockingTaskAwareExecutorServiceTest")
public class BlockingTaskAwareExecutorServiceTest extends AbstractInfinispanTest {

   public void testSimpleExecution() throws Exception {
      BlockingTaskAwareExecutorService executorService = createExecutorService();
      try {
         final DoSomething doSomething = new DoSomething();
         executorService.execute(doSomething);

         Thread.sleep(100);

         assert !doSomething.isReady();
         assert !doSomething.isExecuted();

         doSomething.markReady();
         executorService.checkForReadyTasks();

         assert doSomething.isReady();

         eventually(new Condition() {
            @Override
            public boolean isSatisfied() throws Exception {
               return doSomething.isExecuted();
            }
         });
      } finally {
         executorService.shutdownNow();
      }
   }

   public void testMultipleExecutions() throws Exception {
      BlockingTaskAwareExecutorServiceImpl executorService = createExecutorService();
      try {
         List<DoSomething> tasks = new LinkedList<DoSomething>();

         for (int i = 0; i < 30; ++i) {
            tasks.add(new DoSomething());
         }

         for (DoSomething doSomething : tasks) {
            executorService.execute(doSomething);
         }

         for (DoSomething doSomething : tasks) {
            assert !doSomething.isReady();
            assert !doSomething.isExecuted();
         }

         for (DoSomething doSomething : tasks) {
            doSomething.markReady();
         }
         executorService.checkForReadyTasks();

         for (final DoSomething doSomething : tasks) {
            eventually(new Condition() {
               @Override
               public boolean isSatisfied() throws Exception {
                  return doSomething.isExecuted();
               }
            });
         }

      } finally {
         executorService.shutdownNow();
      }
   }

   private BlockingTaskAwareExecutorServiceImpl createExecutorService() {
      return new BlockingTaskAwareExecutorServiceImpl(new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
                                                                             new LinkedBlockingQueue<Runnable>(1000),
                                                                             new DummyThreadFactory()),
                                                      TIME_SERVICE);
   }

   public static class DummyThreadFactory implements ThreadFactory {

      @Override
      public Thread newThread(Runnable runnable) {
         return new Thread(runnable);
      }
   }

   public static class DoSomething implements BlockingRunnable {

      private volatile boolean ready = false;
      private volatile boolean executed = false;

      @Override
      public synchronized final boolean isReady() {
         return ready;
      }

      @Override
      public synchronized final void run() {
         executed = true;
      }

      public synchronized final void markReady() {
         ready = true;
      }

      public synchronized final boolean isExecuted() {
         return executed;
      }
   }
}
