package com.path.android.jobqueue.test.jobmanager;

import com.path.android.jobqueue.Job;
import com.path.android.jobqueue.JobManager;
import com.path.android.jobqueue.Params;
import com.path.android.jobqueue.RetryConstraint;
import com.path.android.jobqueue.log.JqLog;
import com.path.android.jobqueue.test.jobs.DummyJob;

import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;

import android.util.Pair;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.CoreMatchers.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = com.path.android.jobqueue.BuildConfig.class)
public class RetryLogicTest extends JobManagerTestBase {

    static RetryProvider retryProvider;

    static boolean canRun;

    static int runCount;

    static CountDownLatch onRunLatch;

    static Callback onRunCallback;

    static Callback onCancelCallback;

    static CountDownLatch cancelLatch;

    static CountDownLatch dummyJobRunLatch;

    @Before
    public void clear() {
        retryProvider = null;
        canRun = false;
        runCount = 0;
        onRunLatch = null;
        onRunCallback = null;
        onCancelCallback = null;
        cancelLatch = new CountDownLatch(1);
        dummyJobRunLatch = new CountDownLatch(1);
    }

    @Test
    public void testExponential() {
        assertThat("exp 1",RetryConstraint.createExponentialBackoff(1, 10).getNewDelayInMs(),
                is(10L));
        assertThat("exp 2",RetryConstraint.createExponentialBackoff(2, 10).getNewDelayInMs(),
                is(20L));
        assertThat("exp 3",RetryConstraint.createExponentialBackoff(3, 10).getNewDelayInMs(),
                is(40L));

        assertThat("exp 1",RetryConstraint.createExponentialBackoff(1, 5).getNewDelayInMs(),
                is(5L));
        assertThat("exp 2",RetryConstraint.createExponentialBackoff(2, 5).getNewDelayInMs(),
                is(10L));
        assertThat("exp 3", RetryConstraint.createExponentialBackoff(3, 5).getNewDelayInMs(),
                is(20L));
    }

    @Test
    public void testRunCountPersistent() throws InterruptedException {
        testFirstRunCount(true);
    }

    @Test
    public void testRunCountNonPersistent() throws InterruptedException {
        testFirstRunCount(false);
    }

    public void testFirstRunCount(boolean persistent) throws InterruptedException {
        final AtomicInteger runCnt = new AtomicInteger(0);
        onRunCallback = new Callback() {
            @Override
            public void on(Job job) {
                assertThat("run count should match", ((RetryJob) job).getCurrentRunCount(),
                        is(runCnt.incrementAndGet()));
            }
        };
        retryProvider = new RetryProvider() {
            @Override
            public RetryConstraint build(Job job, Throwable throwable, int runCount,
                    int maxRunCount) {
                return RetryConstraint.RETRY;
            }
        };
        canRun = true;
        RetryJob job = new RetryJob(new Params(0).setPersistent(persistent));
        job.retryLimit = 10;
        createJobManager().addJob(job);
        assertThat("", cancelLatch.await(4, TimeUnit.SECONDS), is(true));
        assertThat("", runCount, is(10));
    }

    @Test
    public void testChangeDelayOfTheGroup() throws InterruptedException {
        testChangeDelayOfTheGroup(null);
    }

    @Test
    public void testChangeDelayOfTheGroupPersistent() throws InterruptedException {
        testChangeDelayOfTheGroup(true);
    }

    @Test
    public void testChangeDelayOfTheGroupNonPersistent() throws InterruptedException {
        testChangeDelayOfTheGroup(false);
    }

    public void testChangeDelayOfTheGroup(Boolean persistent) throws InterruptedException {
        canRun = true;
        enableDebug();
        RetryJob job1 = new RetryJob(new Params(2).setPersistent(Boolean.TRUE.equals(persistent)).groupBy("g1"));
        job1.identifier = "job 1 id";
        RetryJob job2 = new RetryJob(new Params(2).setPersistent(!Boolean.FALSE.equals(persistent)).groupBy("g1"));
        job2.identifier = "job 2 id";
        job1.retryLimit = 2;
        job2.retryLimit = 2;
        final String job1Id = job1.identifier;
        final String job2Id = job2.identifier;
        final PersistableDummyJob postTestJob = new PersistableDummyJob(new Params(1)
        .groupBy("g1").setPersistent(Boolean.TRUE.equals(persistent)));
        retryProvider = new RetryProvider() {
            @Override
            public RetryConstraint build(Job job, Throwable throwable, int runCount,
                    int maxRunCount) {
                RetryConstraint constraint = new RetryConstraint(true);
                constraint.setNewDelayInMs(2000L);
                constraint.setApplyNewDelayToGroup(true);
                return constraint;
            }
        };
        final List<Pair<String, Long>> runTimes = new ArrayList<>();
        final Map<String, Long> cancelTimes = new HashMap<>();
        onRunCallback = new Callback() {
            @Override
            public void on(Job job) {
                RetryJob retryJob = (RetryJob) job;
                runTimes.add(new Pair<>(retryJob.identifier, System.nanoTime()));
            }
        };
        onCancelCallback = new Callback() {
            @Override
            public void on(Job job) {
                JqLog.d("on cancel of job %s", job);
                RetryJob retryJob = (RetryJob) job;
                assertThat("Job should cancel only once",
                        cancelTimes.containsKey(retryJob.identifier), is(false));
                cancelTimes.put(retryJob.identifier, System.nanoTime());
                if (!job.isPersistent() || postTestJob.isPersistent()) {
                    assertThat("the 3rd job should not run until others cancel fully",
                            dummyJobRunLatch.getCount(), is(1L));
                }
            }
        };
        cancelLatch = new CountDownLatch(2);

        JobManager jobManager = createJobManager();
        jobManager.addJob(job1);
        jobManager.addJob(job2);
        jobManager.addJob(postTestJob);

        assertThat("jobs should be canceled", cancelLatch.await(7, TimeUnit.SECONDS), is(true));
        assertThat("should run 4 times", runTimes.size(), is(4));
        for (int i = 0; i < 4; i ++) {
            assertThat("first two runs should be job1, last two jobs should be job 2. checking " + i,
                    runTimes.get(i).first, is(i < 2 ? job1Id : job2Id));
        }
        long timeInBetween = TimeUnit.NANOSECONDS.toSeconds(
                runTimes.get(1).second - runTimes.get(0).second);
        assertThat("time between two runs should be at least 2 seconds. job 1 and 2" + ":"
                + timeInBetween, 2 <= timeInBetween, is(true));
        timeInBetween = TimeUnit.NANOSECONDS.toSeconds(
                runTimes.get(3).second - runTimes.get(2).second);
        assertThat("time between two runs should be at least 2 seconds. job 3 and 4" + ":"
                + timeInBetween, 2 <= timeInBetween, is(true));
        assertThat("the other job should run after others are cancelled",
                dummyJobRunLatch.await(1, TimeUnit.SECONDS), is(true));
        // another job should just run
        dummyJobRunLatch = new CountDownLatch(1);
        jobManager.addJob(new PersistableDummyJob(new Params(1).groupBy("g1")));
        assertThat("a newly added job should just run quickly", dummyJobRunLatch.await(500,
                TimeUnit.MILLISECONDS), is(true));
    }

    @Test
    public void testChangeDelayPersistent() throws InterruptedException {
        testChangeDelay(true);
    }

    @Test
    public void testChangeDelayNonPersistent() throws InterruptedException {
        testChangeDelay(false);
    }

    public void testChangeDelay(boolean persistent) throws InterruptedException {
        canRun = true;
        RetryJob job = new RetryJob(new Params(1).setPersistent(persistent));
        job.retryLimit = 2;
        retryProvider = new RetryProvider() {
            @Override
            public RetryConstraint build(Job job, Throwable throwable, int runCount,
                    int maxRunCount) {
                RetryConstraint constraint = new RetryConstraint(true);
                constraint.setNewDelayInMs(2000L);
                return constraint;
            }
        };
        final List<Long> runTimes = new ArrayList<>();
        onRunCallback = new Callback() {
            @Override
            public void on(Job job) {
                runTimes.add(System.nanoTime());
            }
        };
        createJobManager().addJob(job);
        assertThat("job should be canceled", cancelLatch.await(4, TimeUnit.SECONDS), is(true));
        assertThat("should run 2 times", runCount, is(2));
        long timeInBetween = TimeUnit.NANOSECONDS.toSeconds(runTimes.get(1) - runTimes.get(0));
        assertThat("time between two runs should be at least 2 seconds. " + timeInBetween,
                 2 <= timeInBetween, is(true));
    }

    @Test
    public void testChangePriorityAndObserveExecutionOrderPersistent() throws InterruptedException {
        testChangePriorityAndObserveExecutionOrder(true);
    }

    @Test
    public void testChangePriorityAndObserveExecutionOrderNonPersistent()
            throws InterruptedException {
        testChangePriorityAndObserveExecutionOrder(false);
    }

    public void testChangePriorityAndObserveExecutionOrder(boolean persistent)
            throws InterruptedException {
        cancelLatch = new CountDownLatch(2);
        RetryJob job1 = new RetryJob(new Params(10).setPersistent(persistent).groupBy("group"));
        job1.identifier = "1";
        RetryJob job2 = new RetryJob(new Params(5).setPersistent(persistent).groupBy("group"));
        job2.identifier = "2";
        JobManager jobManager = createJobManager();
        jobManager.stop();
        jobManager.addJob(job1);
        jobManager.addJob(job2);
        retryProvider = new RetryProvider() {
            @Override
            public RetryConstraint build(Job job, Throwable throwable, int runCount,
                    int maxRunCount) {
                RetryJob retryJob = (RetryJob) job;
                if ("1".equals(retryJob.identifier)) {
                    if (retryJob.getPriority() == 1) {
                        return RetryConstraint.CANCEL;
                    }
                    RetryConstraint retryConstraint = new RetryConstraint(true);
                    retryConstraint.setNewPriority(1);
                    return retryConstraint;
                } else {
                    return RetryConstraint.CANCEL;
                }
            }
        };
        final List<String> runOrder = new ArrayList<>();
        onRunCallback = new Callback() {
            @Override
            public void on(Job job) {
                runOrder.add(((RetryJob) job).identifier);
            }
        };
        canRun = true;
        jobManager.start();
        assertThat("both jobs should be canceled eventually", cancelLatch.await(3, TimeUnit.MINUTES)
                , is(true));
        assertThat("jobs should run a total of 3 times", runCount, is(3));
        final List<String> expectedRunOrder = Arrays.asList("1", "2", "1");
        assertThat("expected run order count should match", runOrder.size(), is(expectedRunOrder.size()));
        for (int i = 0; i < expectedRunOrder.size(); i++) {
            assertThat("at iteration " + i + ", this job should run",
                    runOrder.get(i), is(expectedRunOrder.get(i)));
        }
    }

    @Test
    public void testChangePriorityPersistent() throws InterruptedException {
        testChangePriority(true);
    }

    @Test
    public void testChangePriorityNonPersistent() throws InterruptedException {
        testChangePriority(false);
    }

    @Ignore
    public void testChangePriority(boolean persistent) throws InterruptedException {
        final AtomicInteger priority = new AtomicInteger(1);
        retryProvider = new RetryProvider() {
            @Override
            public RetryConstraint build(Job job, Throwable throwable, int runCount, int maxRunCount) {
                RetryConstraint constraint = new RetryConstraint(true);
                priority.set(job.getPriority() * 2);
                constraint.setNewPriority(priority.get());
                return constraint;
            }
        };

        onRunCallback = new Callback() {
            @Override
            public void on(Job job) {
                assertThat("priority should be the expected value", job.getPriority(), is(priority.get()));
            }
        };
        RetryJob retryJob = new RetryJob(new Params(priority.get()).setPersistent(persistent));
        retryJob.retryLimit = 3;
        canRun = true;
        onRunLatch = new CountDownLatch(3);
        createJobManager().addJob(retryJob);
        assertThat(onRunLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("it should run 3 times", runCount, is(3));
        assertThat(cancelLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testCancelPersistent() throws InterruptedException {
        testCancel(true);
    }

    @Test
    public void testCancelNonPersistent() throws InterruptedException {
        testCancel(false);
    }

    public void testCancel(boolean persistent) throws InterruptedException {
        canRun = true;
        retryProvider = new RetryProvider() {
            @Override
            public RetryConstraint build(Job job, Throwable throwable, int runCount, int maxRunCount) {
                return RetryConstraint.CANCEL;
            }
        };
        RetryJob job = new RetryJob(new Params(1).setPersistent(persistent));
        job.retryLimit = 3;
        onRunLatch = new CountDownLatch(3);
        createJobManager().addJob(job);
        assertThat(onRunLatch.await(2, TimeUnit.SECONDS), is(false));
        assertThat("it should run 1 time", runCount, is(1));
        assertThat(cancelLatch.await(2, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void retryPersistent() throws InterruptedException {
        testRetry(true, true);
    }

    @Test
    public void retryNonPersistent() throws InterruptedException {
        testRetry(false, true);
    }

    @Test
    public void retryPersistentWithNull() throws InterruptedException {
        testRetry(true, false);
    }

    @Test
    public void retryNonPersistentWithNull() throws InterruptedException {
        testRetry(false, false);
    }

    public void testRetry(boolean persistent, final boolean returnTrue) throws InterruptedException {
        canRun = true;
        retryProvider = new RetryProvider() {
            @Override
            public RetryConstraint build(Job job, Throwable throwable, int runCount, int maxRunCount) {
                return returnTrue ? RetryConstraint.RETRY : null;
            }
        };
        RetryJob job = new RetryJob(new Params(1).setPersistent(persistent));
        job.retryLimit = 3;
        onRunLatch = new CountDownLatch(3);
        createJobManager().addJob(job);
        assertThat(onRunLatch.await(2, TimeUnit.SECONDS), is(true));
        assertThat("it should run 3 times", runCount, is(3));
        assertThat(cancelLatch.await(2, TimeUnit.SECONDS), is(true));
    }

    public static class RetryJob extends Job {
        int retryLimit = 5;
        String identifier;
        protected RetryJob(Params params) {
            super(params);
        }

        @Override
        public void onAdded() {

        }

        @Override
        public void onRun() throws Throwable {
            assertThat("should be allowed to run", canRun, is(true));
            if (onRunCallback != null) {
                onRunCallback.on(this);
            }
            runCount++;
            if (onRunLatch != null) {
                onRunLatch.countDown();
            }
            throw new RuntimeException("i like to fail please");
        }

        @Override
        protected int getRetryLimit() {
            return retryLimit;
        }

        @Override
        protected void onCancel() {
            if (onCancelCallback != null) {
                onCancelCallback.on(this);
            }
            cancelLatch.countDown();
        }

        @Override
        protected RetryConstraint shouldReRunOnThrowable(Throwable throwable, int runCount,
                int maxRunCount) {
            if (retryProvider != null) {
                return retryProvider.build(this, throwable, runCount, maxRunCount);
            }
            return RetryConstraint.createExponentialBackoff(runCount, 1000);
        }

        @Override
        public int getCurrentRunCount() {
            return super.getCurrentRunCount();
        }
    }

    private static class PersistableDummyJob extends DummyJob {
        public PersistableDummyJob(Params params) {
            super(params);
        }

        @Override
        public void onRun() throws Throwable {
            super.onRun();
            dummyJobRunLatch.countDown();
        }
    }


    interface RetryProvider {
        RetryConstraint build(Job job, Throwable throwable, int runCount,
                int maxRunCount);
    }

    interface Callback {
        public void on(Job job);
    }
}
