import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 虚拟线程 vs 有界平台线程池：阻塞 IO 并发能力的对照实验。
 *
 * <p>这不是端到端基准，而是**把变量隔离出来**：两者执行完全相同的阻塞任务，
 * 唯一差别是调度它们的执行器。想回答的问题只有一个——
 * "当任务在等 IO（不占 CPU）时，虚拟线程能比固定线程池并发多少"。
 *
 * <p>任务是 {@code Thread.sleep}，它和等模型返回在网络层面是同一件事：
 * 线程进入阻塞、让出 CPU。用 sleep 而不是真调模型，是为了让结果可复现、且不花钱。
 * 真实模型调用的等待时间更长（数秒级），虚拟线程的优势只会更明显。
 *
 * <p>运行方式（单文件源码启动，不需要编译）：
 * <pre>
 *   $JAVA_HOME/bin/java backend/tools/VirtualThreadBench.java
 *   $JAVA_HOME/bin/java backend/tools/VirtualThreadBench.java 400 80   # 自定义任务数与阻塞时长(ms)
 * </pre>
 * 在 JDK 17 上运行会跳过虚拟线程那一组并说明原因——同一份代码两个 JDK 都能跑，
 * 正好用来对比。
 */
public final class VirtualThreadBench {

    private static final int POOL_SIZE = 16;   // 与 CodeCanvasConfig 里的有界线程池保持一致

    public static void main(String[] args) throws Exception {
        int tasks = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int blockMs = args.length > 1 ? Integer.parseInt(args[1]) : 50;

        System.out.printf("Java %s（%s）%n", System.getProperty("java.version"),
                System.getProperty("java.vendor"));
        System.out.printf("任务数 %d，每个阻塞 %d ms，有界池大小 %d%n%n", tasks, blockMs, POOL_SIZE);

        ExecutorService virtual = tryVirtual(tasks);
        if (virtual != null) {
            Result r = run("虚拟线程", virtual, tasks, blockMs);
            print(r, tasks, blockMs);
        } else {
            System.out.println("虚拟线程：当前 JDK 不支持（需要 21+），本组跳过。");
            System.out.println("在 JDK 21 上重跑本文件即可看到对照。\n");
        }

        Result pooled = run("有界平台线程池", Executors.newFixedThreadPool(POOL_SIZE), tasks, blockMs);
        print(pooled, tasks, blockMs);

        if (virtual != null) {
            System.out.printf("说明：两者执行的任务完全相同，差别只在调度方式。%n"
                    + "有界池并发上限就是 %d，所以 %d 个任务必然排队；%n"
                    + "虚拟线程在阻塞时会让出载体线程，因此能全部并发推进。%n",
                    POOL_SIZE, tasks);
        }
    }

    private static ExecutorService tryVirtual(int tasks) {
        try {
            // 每个任务一条虚拟线程：这正是"为阻塞等待设计"的用法
            return (ExecutorService) Executors.class
                    .getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Result run(String name, ExecutorService executor, int tasks, int blockMs)
            throws Exception {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        threads.resetPeakThreadCount();
        int before = threads.getThreadCount();

        CountDownLatch done = new CountDownLatch(tasks);

        long t0 = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            executor.submit(() -> {
                try {
                    Thread.sleep(blockMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.countDown();
            });
        }
        done.await(10, TimeUnit.MINUTES);
        long wall = System.nanoTime() - t0;

        int peakThreads = threads.getPeakThreadCount() - before;
        executor.shutdownNow();

        // 实际并发度：所有任务阻塞时长的总和 / 墙钟时间。理想值 = 任务数。
        double parallelism = (double) tasks * blockMs / (wall / 1_000_000.0);

        return new Result(name, wall / 1_000_000.0, peakThreads, parallelism);
    }

    private static void print(Result r, int tasks, int blockMs) {
        System.out.printf("%-14s 墙钟 %7.0f ms   平台线程峰值 %4d   实际并发度 %6.1f / %d%n",
                r.name, r.wallMs, r.peakThreads, r.parallelism, tasks);
        System.out.printf("%-14s （完全串行需 %d ms，不受调度限制的下限是 %d ms）%n%n",
                "", tasks * blockMs, blockMs);
    }

    private record Result(String name, double wallMs, int peakThreads, double parallelism) {
    }
}
