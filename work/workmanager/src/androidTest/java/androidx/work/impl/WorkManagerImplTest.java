/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.work.impl;

import static androidx.work.ExistingWorkPolicy.APPEND;
import static androidx.work.ExistingWorkPolicy.KEEP;
import static androidx.work.ExistingWorkPolicy.REPLACE;
import static androidx.work.NetworkType.METERED;
import static androidx.work.NetworkType.NOT_REQUIRED;
import static androidx.work.State.BLOCKED;
import static androidx.work.State.CANCELLED;
import static androidx.work.State.ENQUEUED;
import static androidx.work.State.FAILED;
import static androidx.work.State.RUNNING;
import static androidx.work.State.SUCCEEDED;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.isOneOf;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.arch.core.executor.ArchTaskExecutor;
import android.arch.core.executor.TaskExecutor;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.arch.persistence.db.SupportSQLiteDatabase;
import android.arch.persistence.db.SupportSQLiteOpenHelper;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SdkSuppress;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import androidx.work.Arguments;
import androidx.work.BackoffPolicy;
import androidx.work.BaseWork;
import androidx.work.Constraints;
import androidx.work.ContentUriTriggers;
import androidx.work.PeriodicWork;
import androidx.work.TestLifecycleOwner;
import androidx.work.Work;
import androidx.work.WorkContinuation;
import androidx.work.WorkManagerTest;
import androidx.work.WorkStatus;
import androidx.work.impl.model.Dependency;
import androidx.work.impl.model.DependencyDao;
import androidx.work.impl.model.WorkName;
import androidx.work.impl.model.WorkSpec;
import androidx.work.impl.model.WorkSpecDao;
import androidx.work.impl.model.WorkTag;
import androidx.work.impl.model.WorkTagDao;
import androidx.work.impl.utils.taskexecutor.InstantTaskExecutorRule;
import androidx.work.impl.workers.ConstraintTrackingWorker;
import androidx.work.worker.InfiniteTestWorker;
import androidx.work.worker.TestWorker;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class WorkManagerImplTest extends WorkManagerTest {
    private WorkDatabase mDatabase;
    private WorkManagerImpl mWorkManagerImpl;

    @Rule
    public InstantTaskExecutorRule mRule = new InstantTaskExecutorRule();

    @Before
    public void setUp() {
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            @Override
            public void executeOnDiskIO(@NonNull Runnable runnable) {
                runnable.run();
            }

            @Override
            public void postToMainThread(@NonNull Runnable runnable) {
                runnable.run();
            }

            @Override
            public boolean isMainThread() {
                return true;
            }
        });

        Context context = InstrumentationRegistry.getTargetContext();
        WorkManagerConfiguration configuration = new WorkManagerConfiguration(
                context,
                true,
                Executors.newSingleThreadExecutor());
        mWorkManagerImpl = new WorkManagerImpl(context, configuration);
        mDatabase = mWorkManagerImpl.getWorkDatabase();
    }

    @After
    public void tearDown() {
        List<String> ids = mDatabase.workSpecDao().getAllWorkSpecIds();
        for (String id : ids) {
            mWorkManagerImpl.cancelWorkById(id);
        }
        mDatabase.close();
        ArchTaskExecutor.getInstance().setDelegate(null);
    }

    @Test
    @SmallTest
    public void testEnqueue_insertWork() throws InterruptedException {
        final int workCount = 3;
        final Work[] workArray = new Work[workCount];
        for (int i = 0; i < workCount; ++i) {
            workArray[i] = new Work.Builder(TestWorker.class).build();
        }
        mWorkManagerImpl.beginWith(workArray[0]).then(workArray[1]).then(workArray[2]).enqueue();

        for (int i = 0; i < workCount; ++i) {
            String id = workArray[i].getId();
            assertThat(mDatabase.workSpecDao().getWorkSpec(id), is(notNullValue()));
            assertThat(
                    "index " + i + " does not have expected number of dependencies!",
                    mDatabase.dependencyDao().getPrerequisites(id).size() > 0,
                    is(i > 0));
        }
    }

    @Test
    @SmallTest
    public void testEnqueue_insertMultipleWork() {
        Work work1 = new Work.Builder(TestWorker.class).build();
        Work work2 = new Work.Builder(TestWorker.class).build();
        Work work3 = new Work.Builder(TestWorker.class).build();

        mWorkManagerImpl.enqueue(work1, work2, work3);

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getWorkSpec(work1.getId()), is(notNullValue()));
        assertThat(workSpecDao.getWorkSpec(work2.getId()), is(notNullValue()));
        assertThat(workSpecDao.getWorkSpec(work3.getId()), is(notNullValue()));
    }

    @Test
    @SmallTest
    public void testEnqueue_insertWithDependencies() {
        Work work1a = new Work.Builder(TestWorker.class).build();
        Work work1b = new Work.Builder(TestWorker.class).build();
        Work work2 = new Work.Builder(TestWorker.class).build();
        Work work3a = new Work.Builder(TestWorker.class).build();
        Work work3b = new Work.Builder(TestWorker.class).build();

        mWorkManagerImpl.beginWith(work1a, work1b).then(work2).then(work3a, work3b).enqueue();

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getWorkSpec(work1a.getId()), is(notNullValue()));
        assertThat(workSpecDao.getWorkSpec(work1b.getId()), is(notNullValue()));
        assertThat(workSpecDao.getWorkSpec(work2.getId()), is(notNullValue()));
        assertThat(workSpecDao.getWorkSpec(work3a.getId()), is(notNullValue()));
        assertThat(workSpecDao.getWorkSpec(work3b.getId()), is(notNullValue()));

        DependencyDao dependencyDao = mDatabase.dependencyDao();
        assertThat(dependencyDao.getPrerequisites(work1a.getId()),
                is(emptyCollectionOf(String.class)));
        assertThat(dependencyDao.getPrerequisites(work1b.getId()),
                is(emptyCollectionOf(String.class)));

        List<String> prerequisites = dependencyDao.getPrerequisites(work2.getId());
        assertThat(prerequisites, containsInAnyOrder(work1a.getId(), work1b.getId()));

        prerequisites = dependencyDao.getPrerequisites(work3a.getId());
        assertThat(prerequisites, containsInAnyOrder(work2.getId()));

        prerequisites = dependencyDao.getPrerequisites(work3b.getId());
        assertThat(prerequisites, containsInAnyOrder(work2.getId()));
    }

    @Test
    @SmallTest
    public void testEnqueue_insertWithCompletedDependencies_isNotStatusBlocked() {
        Work work1 = new Work.Builder(TestWorker.class).withInitialState(SUCCEEDED).build();

        WorkContinuation workContinuation = mWorkManagerImpl.beginWith(work1);
        workContinuation.enqueue();
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getState(work1.getId()), is(SUCCEEDED));

        Work work2 = new Work.Builder(InfiniteTestWorker.class).build();
        workContinuation.then(work2).enqueue();
        assertThat(workSpecDao.getState(work2.getId()), isOneOf(ENQUEUED, RUNNING));
    }

    @Test
    @SmallTest
    public void testEnqueue_insertWithFailedDependencies_isStatusFailed() {
        Work work1 = new Work.Builder(TestWorker.class).withInitialState(FAILED).build();

        WorkContinuation workContinuation = mWorkManagerImpl.beginWith(work1);
        workContinuation.enqueue();
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getState(work1.getId()), is(FAILED));

        Work work2 = new Work.Builder(TestWorker.class).build();
        workContinuation.then(work2).enqueue();
        assertThat(workSpecDao.getState(work2.getId()), is(FAILED));
    }

    @Test
    @SmallTest
    public void testEnqueue_insertWithCancelledDependencies_isStatusCancelled() {
        Work work1 = new Work.Builder(TestWorker.class).withInitialState(CANCELLED).build();

        WorkContinuation workContinuation = mWorkManagerImpl.beginWith(work1);
        workContinuation.enqueue();
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getState(work1.getId()), is(CANCELLED));

        Work work2 = new Work.Builder(TestWorker.class).build();
        workContinuation.then(work2).enqueue();
        assertThat(workSpecDao.getState(work2.getId()), is(CANCELLED));
    }

    @Test
    @SmallTest
    @SdkSuppress(minSdkVersion = 23)
    public void testEnqueue_insertWorkConstraints() {
        Uri testUri1 = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri testUri2 = MediaStore.Images.Media.INTERNAL_CONTENT_URI;

        Work work0 = new Work.Builder(TestWorker.class)
                .withConstraints(
                        new Constraints.Builder()
                                .setRequiresCharging(true)
                                .setRequiresDeviceIdle(true)
                                .setRequiredNetworkType(METERED)
                                .setRequiresBatteryNotLow(true)
                                .setRequiresStorageNotLow(true)
                                .addContentUriTrigger(testUri1, true)
                                .addContentUriTrigger(testUri2, false)
                                .build())
                .build();
        Work work1 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl.beginWith(work0).then(work1).enqueue();

        WorkSpec workSpec0 = mDatabase.workSpecDao().getWorkSpec(work0.getId());
        WorkSpec workSpec1 = mDatabase.workSpecDao().getWorkSpec(work1.getId());

        ContentUriTriggers expectedTriggers = new ContentUriTriggers();
        expectedTriggers.add(testUri1, true);
        expectedTriggers.add(testUri2, false);

        Constraints constraints = workSpec0.getConstraints();
        assertThat(constraints, is(notNullValue()));
        assertThat(constraints.requiresCharging(), is(true));
        assertThat(constraints.requiresDeviceIdle(), is(true));
        assertThat(constraints.requiresBatteryNotLow(), is(true));
        assertThat(constraints.requiresStorageNotLow(), is(true));
        assertThat(constraints.getRequiredNetworkType(), is(METERED));
        if (Build.VERSION.SDK_INT >= 24) {
            assertThat(constraints.getContentUriTriggers(), is(expectedTriggers));
        } else {
            assertThat(constraints.getContentUriTriggers(), is(new ContentUriTriggers()));
        }

        constraints = workSpec1.getConstraints();
        assertThat(constraints, is(notNullValue()));
        assertThat(constraints.requiresCharging(), is(false));
        assertThat(constraints.requiresDeviceIdle(), is(false));
        assertThat(constraints.requiresBatteryNotLow(), is(false));
        assertThat(constraints.requiresStorageNotLow(), is(false));
        assertThat(constraints.getRequiredNetworkType(), is(NOT_REQUIRED));
        assertThat(constraints.getContentUriTriggers().size(), is(0));
    }

    @Test
    @SmallTest
    public void testEnqueue_insertWorkInitialDelay() {
        final long expectedInitialDelay = 5000L;
        Work work0 = new Work.Builder(TestWorker.class)
                .withInitialDelay(expectedInitialDelay, TimeUnit.MILLISECONDS)
                .build();
        Work work1 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl.beginWith(work0).then(work1).enqueue();

        WorkSpec workSpec0 = mDatabase.workSpecDao().getWorkSpec(work0.getId());
        WorkSpec workSpec1 = mDatabase.workSpecDao().getWorkSpec(work1.getId());

        assertThat(workSpec0.getInitialDelay(), is(expectedInitialDelay));
        assertThat(workSpec1.getInitialDelay(), is(0L));
    }

    @Test
    @SmallTest
    public void testEnqueue_insertWorkBackoffPolicy() {
        Work work0 = new Work.Builder(TestWorker.class)
                .withBackoffCriteria(BackoffPolicy.LINEAR, 50000, TimeUnit.MILLISECONDS)
                .build();
        Work work1 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl.beginWith(work0).then(work1).enqueue();

        WorkSpec workSpec0 = mDatabase.workSpecDao().getWorkSpec(work0.getId());
        WorkSpec workSpec1 = mDatabase.workSpecDao().getWorkSpec(work1.getId());

        assertThat(workSpec0.getBackoffPolicy(), is(BackoffPolicy.LINEAR));
        assertThat(workSpec0.getBackoffDelayDuration(), is(50000L));

        assertThat(workSpec1.getBackoffPolicy(), is(BackoffPolicy.EXPONENTIAL));
        assertThat(workSpec1.getBackoffDelayDuration(), is(BaseWork.DEFAULT_BACKOFF_DELAY_MILLIS));
    }

    @Test
    @SmallTest
    public void testEnqueue_insertWorkTags() {
        final String firstTag = "first_tag";
        final String secondTag = "second_tag";
        final String thirdTag = "third_tag";

        Work work0 = new Work.Builder(TestWorker.class).addTag(firstTag).addTag(secondTag).build();
        Work work1 = new Work.Builder(TestWorker.class).addTag(firstTag).build();
        Work work2 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl.beginWith(work0).then(work1).then(work2).enqueue();

        WorkTagDao workTagDao = mDatabase.workTagDao();
        assertThat(workTagDao.getWorkSpecIdsWithTag(firstTag),
                containsInAnyOrder(work0.getId(), work1.getId()));
        assertThat(workTagDao.getWorkSpecIdsWithTag(secondTag), containsInAnyOrder(work0.getId()));
        assertThat(workTagDao.getWorkSpecIdsWithTag(thirdTag), emptyCollectionOf(String.class));
    }

    @Test
    @SmallTest
    public void testEnqueue_insertPeriodicWork() {
        PeriodicWork periodicWork = new PeriodicWork.Builder(
                TestWorker.class,
                PeriodicWork.MIN_PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS)
                .build();
        mWorkManagerImpl.enqueue(periodicWork);

        WorkSpec workSpec = mDatabase.workSpecDao().getWorkSpec(periodicWork.getId());
        assertThat(workSpec.isPeriodic(), is(true));
        assertThat(workSpec.getIntervalDuration(), is(PeriodicWork.MIN_PERIODIC_INTERVAL_MILLIS));
        assertThat(workSpec.getFlexDuration(), is(PeriodicWork.MIN_PERIODIC_INTERVAL_MILLIS));
    }

    @Test
    @SmallTest
    public void testEnqueued_work_setsPeriodStartTime() {
        Work work = new Work.Builder(TestWorker.class).build();
        assertThat(getWorkSpec(work).getPeriodStartTime(), is(0L));

        long beforeEnqueueTime = System.currentTimeMillis();

        mWorkManagerImpl.enqueue(work);

        WorkSpec workSpec = mDatabase.workSpecDao().getWorkSpec(work.getId());
        assertThat(workSpec.getPeriodStartTime(), is(greaterThanOrEqualTo(beforeEnqueueTime)));
    }

    @Test
    @SmallTest
    public void testEnqueued_periodicWork_setsPeriodStartTime() {
        PeriodicWork periodicWork = new PeriodicWork.Builder(
                TestWorker.class,
                PeriodicWork.MIN_PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS)
                .build();
        assertThat(getWorkSpec(periodicWork).getPeriodStartTime(), is(0L));

        long beforeEnqueueTime = System.currentTimeMillis();

        mWorkManagerImpl.enqueue(periodicWork);

        WorkSpec workSpec = mDatabase.workSpecDao().getWorkSpec(periodicWork.getId());
        assertThat(workSpec.getPeriodStartTime(), is(greaterThanOrEqualTo(beforeEnqueueTime)));
    }

    @Test
    @SmallTest
    public void testBeginWithName_setsUniqueName() {
        final String testName = "myname";

        Work work = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl.beginWithName(testName, REPLACE)
                .then(work)
                .enqueue();

        List<String> workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(work.getId(), isIn(workSpecIds));
    }

    @Test
    @SmallTest
    public void testBeginWithName_deletesOldWorkOnReplace() {
        final String testName = "myname";

        Work originalWork = new Work.Builder(InfiniteTestWorker.class).build();
        insertNamedWorks(testName, originalWork);

        List<String> workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds, containsInAnyOrder(originalWork.getId()));

        Work replacementWork1 = new Work.Builder(TestWorker.class).build();
        Work replacementWork2 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl
                .beginWithName(testName, REPLACE, replacementWork1)
                .then(replacementWork2)
                .enqueue();

        workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(
                workSpecIds,
                containsInAnyOrder(replacementWork1.getId(), replacementWork2.getId()));

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getWorkSpec(originalWork.getId()), is(nullValue()));
        assertThat(workSpecDao.getWorkSpec(replacementWork1.getId()), is(not(nullValue())));
        assertThat(workSpecDao.getWorkSpec(replacementWork2.getId()), is(not(nullValue())));
    }

    @Test
    @SmallTest
    public void testBeginWithName_keepsExistingWorkOnKeep() {
        final String testName = "myname";

        Work originalWork = new Work.Builder(InfiniteTestWorker.class).build();
        insertNamedWorks(testName, originalWork);

        List<String> workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds, containsInAnyOrder(originalWork.getId()));

        Work replacementWork1 = new Work.Builder(TestWorker.class).build();
        Work replacementWork2 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl
                .beginWithName(testName, KEEP, replacementWork1)
                .then(replacementWork2)
                .enqueue();

        workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds, containsInAnyOrder(originalWork.getId()));

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getWorkSpec(originalWork.getId()), is(not(nullValue())));
        assertThat(workSpecDao.getWorkSpec(replacementWork1.getId()), is(nullValue()));
        assertThat(workSpecDao.getWorkSpec(replacementWork2.getId()), is(nullValue()));
    }

    @Test
    @SmallTest
    public void testBeginWithName_replacesExistingWorkOnKeepWhenExistingWorkIsFinished() {
        final String testName = "myname";

        Work originalWork = new Work.Builder(TestWorker.class).withInitialState(SUCCEEDED).build();
        insertNamedWorks(testName, originalWork);

        List<String> workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds, containsInAnyOrder(originalWork.getId()));

        Work replacementWork1 = new Work.Builder(TestWorker.class).build();
        Work replacementWork2 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl
                .beginWithName(testName, KEEP, replacementWork1)
                .then(replacementWork2)
                .enqueue();

        workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds,
                containsInAnyOrder(replacementWork1.getId(), replacementWork2.getId()));

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getWorkSpec(originalWork.getId()), is(nullValue()));
        assertThat(workSpecDao.getWorkSpec(replacementWork1.getId()), is(not(nullValue())));
        assertThat(workSpecDao.getWorkSpec(replacementWork2.getId()), is(not(nullValue())));
    }

    @Test
    @SmallTest
    public void testBeginWithName_appendsExistingWorkOnAppend() {
        final String testName = "myname";

        Work originalWork = new Work.Builder(InfiniteTestWorker.class).build();
        insertNamedWorks(testName, originalWork);

        List<String> workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds, containsInAnyOrder(originalWork.getId()));

        Work appendWork1 = new Work.Builder(TestWorker.class).build();
        Work appendWork2 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl
                .beginWithName(testName, APPEND, appendWork1)
                .then(appendWork2)
                .enqueue();

        workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds,
                containsInAnyOrder(originalWork.getId(), appendWork1.getId(), appendWork2.getId()));

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getWorkSpec(originalWork.getId()), is(not(nullValue())));
        assertThat(workSpecDao.getState(appendWork1.getId()), is(BLOCKED));
        assertThat(workSpecDao.getState(appendWork2.getId()), is(BLOCKED));

        assertThat(mDatabase.dependencyDao().getDependentWorkIds(originalWork.getId()),
                containsInAnyOrder(appendWork1.getId()));
    }

    @Test
    @SmallTest
    public void testBeginWithName_appendsExistingWorkToOnlyLeavesOnAppend() {
        final String testName = "myname";

        Work originalWork1 = new Work.Builder(InfiniteTestWorker.class).build();
        Work originalWork2 = new Work.Builder(InfiniteTestWorker.class).build();
        Work originalWork3 = new Work.Builder(InfiniteTestWorker.class).build();
        Work originalWork4 = new Work.Builder(InfiniteTestWorker.class).build();
        insertNamedWorks(testName, originalWork1, originalWork2, originalWork3, originalWork4);
        insertDependency(originalWork4, originalWork2);
        insertDependency(originalWork3, originalWork2);
        insertDependency(originalWork2, originalWork1);

        List<String> workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds,
                containsInAnyOrder(
                        originalWork1.getId(),
                        originalWork2.getId(),
                        originalWork3.getId(),
                        originalWork4.getId()));

        Work appendWork1 = new Work.Builder(TestWorker.class).build();
        Work appendWork2 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl
                .beginWithName(testName, APPEND, appendWork1)
                .then(appendWork2)
                .enqueue();

        workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds,
                containsInAnyOrder(
                        originalWork1.getId(),
                        originalWork2.getId(),
                        originalWork3.getId(),
                        originalWork4.getId(),
                        appendWork1.getId(),
                        appendWork2.getId()));

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getWorkSpec(originalWork1.getId()), is(not(nullValue())));
        assertThat(workSpecDao.getWorkSpec(originalWork2.getId()), is(not(nullValue())));
        assertThat(workSpecDao.getWorkSpec(originalWork3.getId()), is(not(nullValue())));
        assertThat(workSpecDao.getWorkSpec(originalWork4.getId()), is(not(nullValue())));
        assertThat(workSpecDao.getState(appendWork1.getId()), is(BLOCKED));
        assertThat(workSpecDao.getState(appendWork2.getId()), is(BLOCKED));

        DependencyDao dependencyDao = mDatabase.dependencyDao();
        assertThat(dependencyDao.getPrerequisites(appendWork1.getId()),
                containsInAnyOrder(originalWork3.getId(), originalWork4.getId()));
        assertThat(dependencyDao.getPrerequisites(appendWork2.getId()),
                containsInAnyOrder(appendWork1.getId()));
    }

    @Test
    @SmallTest
    public void testBeginWithName_insertsExistingWorkWhenNothingToAppendTo() {
        final String testName = "myname";

        Work appendWork1 = new Work.Builder(TestWorker.class).build();
        Work appendWork2 = new Work.Builder(TestWorker.class).build();
        mWorkManagerImpl
                .beginWithName(testName, APPEND, appendWork1)
                .then(appendWork2)
                .enqueue();

        List<String> workSpecIds = mDatabase.workNameDao().getWorkSpecIdsWithName(testName);
        assertThat(workSpecIds,
                containsInAnyOrder(appendWork1.getId(), appendWork2.getId()));
    }

    @Test
    @SmallTest
    public void testGetStatusByIdSync() {
        Work work = new Work.Builder(TestWorker.class).withInitialState(SUCCEEDED).build();
        insertWorkSpecAndTags(work);

        WorkStatus workStatus = mWorkManagerImpl.getStatusByIdBlocking(work.getId());
        assertThat(workStatus.getId(), is(work.getId()));
        assertThat(workStatus.getState(), is(SUCCEEDED));
    }

    @Test
    @SmallTest
    public void testGetStatusByIdSync_returnsNullIfNotInDatabase() {
        WorkStatus workStatus = mWorkManagerImpl.getStatusByIdBlocking("dummy");
        assertThat(workStatus, is(nullValue()));
    }

    @Test
    @SmallTest
    @SuppressWarnings("unchecked")
    public void testGetStatusesById() {
        Work work0 = new Work.Builder(TestWorker.class).build();
        Work work1 = new Work.Builder(TestWorker.class).build();
        insertWorkSpecAndTags(work0);
        insertWorkSpecAndTags(work1);

        Observer<List<WorkStatus>> mockObserver = mock(Observer.class);

        TestLifecycleOwner testLifecycleOwner = new TestLifecycleOwner();
        LiveData<List<WorkStatus>> liveData =
                mWorkManagerImpl.getStatusesById(Arrays.asList(work0.getId(), work1.getId()));
        liveData.observe(testLifecycleOwner, mockObserver);

        ArgumentCaptor<List<WorkStatus>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockObserver).onChanged(captor.capture());
        assertThat(captor.getValue(), is(not(nullValue())));
        assertThat(captor.getValue().size(), is(2));

        WorkStatus workStatus0 = new WorkStatus(
                work0.getId(),
                ENQUEUED,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        WorkStatus workStatus1 = new WorkStatus(
                work1.getId(),
                ENQUEUED,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        assertThat(captor.getValue(), containsInAnyOrder(workStatus0, workStatus1));

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        workSpecDao.setState(RUNNING, work0.getId());

        verify(mockObserver, times(2)).onChanged(captor.capture());
        assertThat(captor.getValue(), is(not(nullValue())));
        assertThat(captor.getValue().size(), is(2));

        workStatus0 = new WorkStatus(
                work0.getId(),
                RUNNING,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        assertThat(captor.getValue(), containsInAnyOrder(workStatus0, workStatus1));

        clearInvocations(mockObserver);
        workSpecDao.setState(RUNNING, work1.getId());

        verify(mockObserver).onChanged(captor.capture());
        assertThat(captor.getValue(), is(not(nullValue())));
        assertThat(captor.getValue().size(), is(2));

        workStatus1 = new WorkStatus(
                work1.getId(),
                RUNNING,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        assertThat(captor.getValue(), containsInAnyOrder(workStatus0, workStatus1));

        liveData.removeObservers(testLifecycleOwner);
    }

    @Test
    @SmallTest
    public void testGetStatusesByTagSync() {
        final String firstTag = "first_tag";
        final String secondTag = "second_tag";

        Work work0 = new Work.Builder(TestWorker.class)
                .addTag(firstTag)
                .addTag(secondTag)
                .withInitialState(RUNNING)
                .build();
        Work work1 = new Work.Builder(TestWorker.class)
                .addTag(firstTag)
                .withInitialState(BLOCKED)
                .build();
        Work work2 = new Work.Builder(TestWorker.class)
                .addTag(secondTag)
                .withInitialState(SUCCEEDED)
                .build();
        insertWorkSpecAndTags(work0);
        insertWorkSpecAndTags(work1);
        insertWorkSpecAndTags(work2);

        WorkStatus workStatus0 = new WorkStatus(
                work0.getId(),
                RUNNING,
                Arguments.EMPTY,
                Arrays.asList(firstTag, secondTag));
        WorkStatus workStatus1 = new WorkStatus(
                work1.getId(),
                BLOCKED,
                Arguments.EMPTY,
                Collections.singletonList(firstTag));
        WorkStatus workStatus2 = new WorkStatus(
                work2.getId(),
                SUCCEEDED,
                Arguments.EMPTY,
                Collections.singletonList(secondTag));

        List<WorkStatus> workStatuses = mWorkManagerImpl.getStatusesByTagBlocking(firstTag);
        assertThat(workStatuses, containsInAnyOrder(workStatus0, workStatus1));

        workStatuses = mWorkManagerImpl.getStatusesByTagBlocking(secondTag);
        assertThat(workStatuses, containsInAnyOrder(workStatus0, workStatus2));

        workStatuses = mWorkManagerImpl.getStatusesByTagBlocking("dummy");
        assertThat(workStatuses.size(), is(0));
    }

    @Test
    @SmallTest
    @SuppressWarnings("unchecked")
    public void testGetStatusesByTag() {
        final String firstTag = "first_tag";
        final String secondTag = "second_tag";
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();

        Work work0 = new Work.Builder(TestWorker.class)
                .addTag(firstTag)
                .addTag(secondTag)
                .withInitialState(RUNNING)
                .build();
        Work work1 = new Work.Builder(TestWorker.class)
                .addTag(firstTag)
                .withInitialState(BLOCKED)
                .build();
        Work work2 = new Work.Builder(TestWorker.class)
                .addTag(secondTag)
                .withInitialState(SUCCEEDED)
                .build();
        insertWorkSpecAndTags(work0);
        insertWorkSpecAndTags(work1);
        insertWorkSpecAndTags(work2);

        Observer<List<WorkStatus>> mockObserver = mock(Observer.class);

        TestLifecycleOwner testLifecycleOwner = new TestLifecycleOwner();
        LiveData<List<WorkStatus>> liveData = mWorkManagerImpl.getStatusesByTag(firstTag);
        liveData.observe(testLifecycleOwner, mockObserver);

        ArgumentCaptor<List<WorkStatus>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockObserver).onChanged(captor.capture());
        assertThat(captor.getValue(), is(not(nullValue())));
        assertThat(captor.getValue().size(), is(2));

        WorkStatus workStatus0 = new WorkStatus(
                work0.getId(),
                RUNNING,
                Arguments.EMPTY,
                Arrays.asList(firstTag, secondTag));
        WorkStatus workStatus1 = new WorkStatus(
                work1.getId(),
                BLOCKED,
                Arguments.EMPTY,
                Collections.singletonList(firstTag));
        assertThat(captor.getValue(), containsInAnyOrder(workStatus0, workStatus1));

        workSpecDao.setState(ENQUEUED, work0.getId());

        verify(mockObserver, times(2)).onChanged(captor.capture());
        assertThat(captor.getValue(), is(not(nullValue())));
        assertThat(captor.getValue().size(), is(2));

        workStatus0 = new WorkStatus(
                work0.getId(),
                ENQUEUED,
                Arguments.EMPTY,
                Arrays.asList(firstTag, secondTag));
        assertThat(captor.getValue(), containsInAnyOrder(workStatus0, workStatus1));

        liveData.removeObservers(testLifecycleOwner);
    }

    @Test
    @SmallTest
    public void getStatusByNameSync() {
        final String testName = "myname";

        Work work0 = new Work.Builder(InfiniteTestWorker.class).withInitialState(RUNNING).build();
        Work work1 = new Work.Builder(InfiniteTestWorker.class).withInitialState(BLOCKED).build();
        Work work2 = new Work.Builder(InfiniteTestWorker.class).withInitialState(BLOCKED).build();
        insertNamedWorks(testName, work0, work1, work2);
        insertDependency(work1, work0);
        insertDependency(work2, work1);

        WorkStatus workStatus0 = new WorkStatus(
                work0.getId(),
                RUNNING,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        WorkStatus workStatus1 = new WorkStatus(
                work1.getId(),
                BLOCKED,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        WorkStatus workStatus2 = new WorkStatus(
                work2.getId(),
                BLOCKED,
                Arguments.EMPTY,
                Collections.<String>emptyList());

        List<WorkStatus> workStatuses = mWorkManagerImpl.getStatusesByNameBlocking(testName);
        assertThat(workStatuses, containsInAnyOrder(workStatus0, workStatus1, workStatus2));

        workStatuses = mWorkManagerImpl.getStatusesByNameBlocking("dummy");
        assertThat(workStatuses.size(), is(0));
    }

    @Test
    @SmallTest
    @SuppressWarnings("unchecked")
    public void testGetStatusesByName() {
        final String testName = "myname";
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();

        Work work0 = new Work.Builder(InfiniteTestWorker.class).withInitialState(RUNNING).build();
        Work work1 = new Work.Builder(InfiniteTestWorker.class).withInitialState(BLOCKED).build();
        Work work2 = new Work.Builder(InfiniteTestWorker.class).withInitialState(BLOCKED).build();
        insertNamedWorks(testName, work0, work1, work2);
        insertDependency(work1, work0);
        insertDependency(work2, work1);

        Observer<List<WorkStatus>> mockObserver = mock(Observer.class);

        TestLifecycleOwner testLifecycleOwner = new TestLifecycleOwner();
        LiveData<List<WorkStatus>> liveData = mWorkManagerImpl.getStatusesByName(testName);
        liveData.observe(testLifecycleOwner, mockObserver);

        ArgumentCaptor<List<WorkStatus>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockObserver).onChanged(captor.capture());
        assertThat(captor.getValue(), is(not(nullValue())));
        assertThat(captor.getValue().size(), is(3));

        WorkStatus workStatus0 = new WorkStatus(
                work0.getId(),
                RUNNING,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        WorkStatus workStatus1 = new WorkStatus(
                work1.getId(),
                BLOCKED,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        WorkStatus workStatus2 = new WorkStatus(
                work2.getId(),
                BLOCKED,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        assertThat(captor.getValue(), containsInAnyOrder(workStatus0, workStatus1, workStatus2));

        workSpecDao.setState(ENQUEUED, work0.getId());

        verify(mockObserver, times(2)).onChanged(captor.capture());
        assertThat(captor.getValue(), is(not(nullValue())));
        assertThat(captor.getValue().size(), is(3));

        workStatus0 = new WorkStatus(
                work0.getId(),
                ENQUEUED,
                Arguments.EMPTY,
                Collections.<String>emptyList());
        assertThat(captor.getValue(), containsInAnyOrder(workStatus0, workStatus1, workStatus2));

        liveData.removeObservers(testLifecycleOwner);
    }

    @Test
    @SmallTest
    public void testCancelWorkById() {
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();

        Work work0 = new Work.Builder(TestWorker.class).build();
        Work work1 = new Work.Builder(TestWorker.class).build();
        insertWorkSpecAndTags(work0);
        insertWorkSpecAndTags(work1);

        mWorkManagerImpl.cancelWorkById(work0.getId());

        assertThat(workSpecDao.getState(work0.getId()), is(CANCELLED));
        assertThat(workSpecDao.getState(work1.getId()), is(not(CANCELLED)));
    }

    @Test
    @SmallTest
    public void testCancelWorkById_cancelsDependentWork() {
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();

        Work work0 = new Work.Builder(TestWorker.class).build();
        Work work1 = new Work.Builder(TestWorker.class).withInitialState(BLOCKED).build();
        insertWorkSpecAndTags(work0);
        insertWorkSpecAndTags(work1);
        insertDependency(work1, work0);

        mWorkManagerImpl.cancelWorkById(work0.getId());

        assertThat(workSpecDao.getState(work0.getId()), is(CANCELLED));
        assertThat(workSpecDao.getState(work1.getId()), is(CANCELLED));
    }

    @Test
    @SmallTest
    public void testCancelWorkById_cancelsUnfinishedWorkOnly() {
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();

        Work work0 = new Work.Builder(TestWorker.class).withInitialState(SUCCEEDED).build();
        Work work1 = new Work.Builder(TestWorker.class).withInitialState(ENQUEUED).build();
        insertWorkSpecAndTags(work0);
        insertWorkSpecAndTags(work1);
        insertDependency(work1, work0);

        mWorkManagerImpl.cancelWorkById(work0.getId());

        assertThat(workSpecDao.getState(work0.getId()), is(SUCCEEDED));
        assertThat(workSpecDao.getState(work1.getId()), is(CANCELLED));
    }

    @Test
    @SmallTest
    public void testCancelAllWorkByTag() {
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();

        final String tagToClear = "tag_to_clear";
        final String tagNotToClear = "tag_not_to_clear";

        Work work0 = new Work.Builder(TestWorker.class).addTag(tagToClear).build();
        Work work1 = new Work.Builder(TestWorker.class).addTag(tagToClear).build();
        Work work2 = new Work.Builder(TestWorker.class).addTag(tagNotToClear).build();
        Work work3 = new Work.Builder(TestWorker.class).addTag(tagNotToClear).build();
        insertWorkSpecAndTags(work0);
        insertWorkSpecAndTags(work1);
        insertWorkSpecAndTags(work2);
        insertWorkSpecAndTags(work3);

        mWorkManagerImpl.cancelAllWorkByTag(tagToClear);

        assertThat(workSpecDao.getState(work0.getId()), is(CANCELLED));
        assertThat(workSpecDao.getState(work1.getId()), is(CANCELLED));
        assertThat(workSpecDao.getState(work2.getId()), is(not(CANCELLED)));
        assertThat(workSpecDao.getState(work3.getId()), is(not(CANCELLED)));
    }

    @Test
    @SmallTest
    public void testCancelAllWorkByTag_cancelsDependentWork() {
        String tag = "tag";

        Work work0 = new Work.Builder(TestWorker.class).addTag(tag).build();
        Work work1 = new Work.Builder(TestWorker.class).build();
        Work work2 = new Work.Builder(TestWorker.class).build();
        Work work3 = new Work.Builder(TestWorker.class).build();
        Work work4 = new Work.Builder(TestWorker.class).build();

        insertWorkSpecAndTags(work0);
        insertWorkSpecAndTags(work1);
        insertWorkSpecAndTags(work2);
        insertWorkSpecAndTags(work3);
        insertWorkSpecAndTags(work4);

        // Dependency graph:
        //                             0
        //                             |
        //                       |------------|
        //            3          1            4
        //            |          |
        //            ------------
        //                 |
        //                 2

        insertDependency(work2, work1);
        insertDependency(work2, work3);
        insertDependency(work1, work0);
        insertDependency(work4, work0);

        mWorkManagerImpl.cancelAllWorkByTag(tag);

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getState(work0.getId()), is(CANCELLED));
        assertThat(workSpecDao.getState(work1.getId()), is(CANCELLED));
        assertThat(workSpecDao.getState(work2.getId()), is(CANCELLED));
        assertThat(workSpecDao.getState(work3.getId()), is(not(CANCELLED)));
        assertThat(workSpecDao.getState(work4.getId()), is(CANCELLED));
    }

    @Test
    @SmallTest
    public void testCancelWorkByName() {
        final String testName = "myname";

        Work work0 = new Work.Builder(InfiniteTestWorker.class).build();
        Work work1 = new Work.Builder(InfiniteTestWorker.class).build();
        insertNamedWorks(testName, work0, work1);

        mWorkManagerImpl.cancelAllWorkByName(testName);

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getState(work0.getId()), is(CANCELLED));
        assertThat(workSpecDao.getState(work1.getId()), is(CANCELLED));
    }

    @Test
    @SmallTest
    public void testCancelWorkByName_ignoresFinishedWork() {
        final String testName = "myname";

        Work work0 = new Work.Builder(InfiniteTestWorker.class).withInitialState(SUCCEEDED).build();
        Work work1 = new Work.Builder(InfiniteTestWorker.class).build();
        insertNamedWorks(testName, work0, work1);

        mWorkManagerImpl.cancelAllWorkByName(testName);

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getState(work0.getId()), is(SUCCEEDED));
        assertThat(workSpecDao.getState(work1.getId()), is(CANCELLED));
    }

    @Test
    @SmallTest
    public void testSynchronousCancelAndGetStatus() {
        Work work = new Work.Builder(TestWorker.class).build();
        insertWorkSpecAndTags(work);

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getState(work.getId()), is(ENQUEUED));

        mWorkManagerImpl.cancelWorkByIdBlocking(work.getId());
        assertThat(mWorkManagerImpl.getStatusByIdBlocking(work.getId()).getState(), is(CANCELLED));
    }

    @Test
    @SmallTest
    public void testGenerateCleanupCallback_resetsRunningWorkStatuses() {
        WorkSpecDao workSpecDao = mDatabase.workSpecDao();

        Work work = new Work.Builder(TestWorker.class).withInitialState(RUNNING).build();
        workSpecDao.insertWorkSpec(getWorkSpec(work));

        assertThat(workSpecDao.getState(work.getId()), is(RUNNING));

        SupportSQLiteOpenHelper openHelper = mDatabase.getOpenHelper();
        SupportSQLiteDatabase db = openHelper.getWritableDatabase();
        WorkDatabase.generateCleanupCallback().onOpen(db);

        assertThat(workSpecDao.getState(work.getId()), is(ENQUEUED));
    }

    @Test
    @SmallTest
    public void testGenerateCleanupCallback_deletesOldFinishedWork() {
        Work work1 = new Work.Builder(TestWorker.class)
                .withInitialState(SUCCEEDED)
                .withPeriodStartTime(WorkDatabase.getPruneDate() - 1L, TimeUnit.MILLISECONDS)
                .build();
        Work work2 = new Work.Builder(TestWorker.class)
                .withPeriodStartTime(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                .build();

        insertWorkSpecAndTags(work1);
        insertWorkSpecAndTags(work2);

        SupportSQLiteOpenHelper openHelper = mDatabase.getOpenHelper();
        SupportSQLiteDatabase db = openHelper.getWritableDatabase();
        WorkDatabase.generateCleanupCallback().onOpen(db);

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getWorkSpec(work1.getId()), is(nullValue()));
        assertThat(workSpecDao.getWorkSpec(work2.getId()), is(not(nullValue())));
    }

    @Test
    @SmallTest
    public void testGenerateCleanupCallback_doesNotDeleteOldFinishedWorkWithActiveDependents() {
        Work work0 = new Work.Builder(TestWorker.class)
                .withInitialState(SUCCEEDED)
                .withPeriodStartTime(WorkDatabase.getPruneDate() - 1L, TimeUnit.MILLISECONDS)
                .build();
        Work work1 = new Work.Builder(TestWorker.class)
                .withInitialState(SUCCEEDED)
                .withPeriodStartTime(WorkDatabase.getPruneDate() - 1L, TimeUnit.MILLISECONDS)
                .build();
        Work work2 = new Work.Builder(TestWorker.class)
                .withInitialState(ENQUEUED)
                .withPeriodStartTime(WorkDatabase.getPruneDate() - 1L, TimeUnit.MILLISECONDS)
                .build();

        insertWorkSpecAndTags(work0);
        insertWorkSpecAndTags(work1);
        insertWorkSpecAndTags(work2);

        // Dependency graph: 0 -> 1 -> 2
        insertDependency(work1, work0);
        insertDependency(work2, work1);

        SupportSQLiteOpenHelper openHelper = mDatabase.getOpenHelper();
        SupportSQLiteDatabase db = openHelper.getWritableDatabase();
        WorkDatabase.generateCleanupCallback().onOpen(db);

        WorkSpecDao workSpecDao = mDatabase.workSpecDao();
        assertThat(workSpecDao.getWorkSpec(work0.getId()), is(nullValue()));
        assertThat(workSpecDao.getWorkSpec(work1.getId()), is(not(nullValue())));
        assertThat(workSpecDao.getWorkSpec(work2.getId()), is(not(nullValue())));
    }

    @Test
    @SmallTest
    @SdkSuppress(maxSdkVersion = 22)
    public void testEnqueueApi22OrLower_withBatteryNotLowConstraint_expectsOriginalWorker() {
        Work work = new Work.Builder(TestWorker.class)
                .withConstraints(new Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build())
                .build();
        mWorkManagerImpl.beginWith(work).blocking().enqueueBlocking();

        WorkSpec workSpec = mDatabase.workSpecDao().getWorkSpec(work.getId());
        assertThat(workSpec.getWorkerClassName(), is(TestWorker.class.getName()));
    }

    @Test
    @SmallTest
    @SdkSuppress(maxSdkVersion = 22)
    public void testEnqueueApi22OrLower_withStorageNotLowConstraint_expectsOriginalWorker() {
        Work work = new Work.Builder(TestWorker.class)
                .withConstraints(new Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build())
                .build();
        mWorkManagerImpl.beginWith(work).blocking().enqueueBlocking();

        WorkSpec workSpec = mDatabase.workSpecDao().getWorkSpec(work.getId());
        assertThat(workSpec.getWorkerClassName(), is(TestWorker.class.getName()));
    }

    @Test
    @SmallTest
    @SdkSuppress(minSdkVersion = 23, maxSdkVersion = 25)
    public void testEnqueueApi23To25_withBatteryNotLowConstraint_expectsConstraintTrackingWorker() {
        Work work = new Work.Builder(TestWorker.class)
                .withConstraints(new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build())
                .build();
        mWorkManagerImpl.beginWith(work).blocking().enqueueBlocking();

        WorkSpec workSpec = mDatabase.workSpecDao().getWorkSpec(work.getId());
        assertThat(workSpec.getWorkerClassName(), is(ConstraintTrackingWorker.class.getName()));
        assertThat(workSpec.getArguments().getString(
                ConstraintTrackingWorker.ARGUMENT_CLASS_NAME, null),
                is(TestWorker.class.getName()));
    }

    @Test
    @SmallTest
    @SdkSuppress(minSdkVersion = 23, maxSdkVersion = 25)
    public void testEnqueueApi23To25_withStorageNotLowConstraint_expectsConstraintTrackingWorker() {
        Work work = new Work.Builder(TestWorker.class)
                .withConstraints(new Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build())
                .build();
        mWorkManagerImpl.beginWith(work).blocking().enqueueBlocking();

        WorkSpec workSpec = mDatabase.workSpecDao().getWorkSpec(work.getId());
        assertThat(workSpec.getWorkerClassName(), is(ConstraintTrackingWorker.class.getName()));
        assertThat(workSpec.getArguments().getString(
                ConstraintTrackingWorker.ARGUMENT_CLASS_NAME, null),
                is(TestWorker.class.getName()));
    }

    @Test
    @SmallTest
    @SdkSuppress(minSdkVersion = 26)
    public void testEnqueueApi26OrHigher_withBatteryNotLowConstraint_expectsOriginalWorker() {
        Work work = new Work.Builder(TestWorker.class)
                .withConstraints(new Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build())
                .build();
        mWorkManagerImpl.beginWith(work).blocking().enqueueBlocking();

        WorkSpec workSpec = mDatabase.workSpecDao().getWorkSpec(work.getId());
        assertThat(workSpec.getWorkerClassName(), is(TestWorker.class.getName()));
    }

    @Test
    @SmallTest
    @SdkSuppress(minSdkVersion = 26)
    public void testEnqueueApi26OrHigher_withStorageNotLowConstraint_expectsOriginalWorker() {
        Work work = new Work.Builder(TestWorker.class)
                .withConstraints(new Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build())
                .build();
        mWorkManagerImpl.beginWith(work).blocking().enqueueBlocking();

        WorkSpec workSpec = mDatabase.workSpecDao().getWorkSpec(work.getId());
        assertThat(workSpec.getWorkerClassName(), is(TestWorker.class.getName()));
    }

    private void insertWorkSpecAndTags(Work work) {
        mDatabase.workSpecDao().insertWorkSpec(getWorkSpec(work));
        for (String tag : getTags(work)) {
            mDatabase.workTagDao().insert(new WorkTag(tag, work.getId()));
        }
    }

    private void insertNamedWorks(String name, Work... works) {
        for (Work work : works) {
            insertWorkSpecAndTags(work);
            mDatabase.workNameDao().insert(new WorkName(name, work.getId()));
        }
    }

    private void insertDependency(Work work, Work prerequisiteWork) {
        mDatabase.dependencyDao().insertDependency(
                new Dependency(work.getId(), prerequisiteWork.getId()));
    }
}
