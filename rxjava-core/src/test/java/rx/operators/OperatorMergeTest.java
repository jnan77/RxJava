/**
 * Copyright 2014 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.operators;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import rx.Observable;
import rx.Observer;
import rx.Subscriber;
import rx.Subscription;
import rx.schedulers.Schedulers;
import rx.subscriptions.Subscriptions;
import rx.util.functions.Action0;
import rx.util.functions.Action1;

public class OperatorMergeTest {

    @Mock
    Observer<String> stringObserver;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testMergeObservableOfObservables() {
        final Observable<String> o1 = Observable.create(new TestSynchronousObservable());
        final Observable<String> o2 = Observable.create(new TestSynchronousObservable());

        Observable<Observable<String>> observableOfObservables = Observable.create(new Observable.OnSubscribeFunc<Observable<String>>() {

            @Override
            public Subscription onSubscribe(Observer<? super Observable<String>> observer) {
                // simulate what would happen in an observable
                observer.onNext(o1);
                observer.onNext(o2);
                observer.onCompleted();

                return Subscriptions.empty();
            }

        });
        Observable<String> m = Observable.merge(observableOfObservables);
        m.subscribe(stringObserver);

        verify(stringObserver, never()).onError(any(Throwable.class));
        verify(stringObserver, times(1)).onCompleted();
        verify(stringObserver, times(2)).onNext("hello");
    }

    @Test
    public void testMergeArray() {
        final Observable<String> o1 = Observable.create(new TestSynchronousObservable());
        final Observable<String> o2 = Observable.create(new TestSynchronousObservable());

        Observable<String> m = Observable.merge(o1, o2);
        m.subscribe(stringObserver);

        verify(stringObserver, never()).onError(any(Throwable.class));
        verify(stringObserver, times(2)).onNext("hello");
        verify(stringObserver, times(1)).onCompleted();
    }

    @Test
    public void testMergeList() {
        final Observable<String> o1 = Observable.create(new TestSynchronousObservable());
        final Observable<String> o2 = Observable.create(new TestSynchronousObservable());
        List<Observable<String>> listOfObservables = new ArrayList<Observable<String>>();
        listOfObservables.add(o1);
        listOfObservables.add(o2);

        Observable<String> m = Observable.merge(listOfObservables);
        m.subscribe(stringObserver);

        verify(stringObserver, never()).onError(any(Throwable.class));
        verify(stringObserver, times(1)).onCompleted();
        verify(stringObserver, times(2)).onNext("hello");
    }

    @Test
    public void testUnSubscribeObservableOfObservables() throws InterruptedException {

        final AtomicBoolean unsubscribed = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);

        Observable<Observable<Long>> source = Observable.create(new Observable.OnSubscribeFunc<Observable<Long>>() {

            @Override
            public Subscription onSubscribe(final Observer<? super Observable<Long>> observer) {
                // verbose on purpose so I can track the inside of it
                final Subscription s = Subscriptions.create(new Action0() {

                    @Override
                    public void call() {
                        System.out.println("*** unsubscribed");
                        unsubscribed.set(true);
                    }

                });

                new Thread(new Runnable() {

                    @Override
                    public void run() {

                        while (!unsubscribed.get()) {
                            observer.onNext(Observable.from(1L, 2L));
                        }
                        System.out.println("Done looping after unsubscribe: " + unsubscribed.get());
                        observer.onCompleted();

                        // mark that the thread is finished
                        latch.countDown();
                    }
                }).start();

                return s;
            }

            ;

        });

        final AtomicInteger count = new AtomicInteger();
        Observable.merge(source).take(6).toBlockingObservable().forEach(new Action1<Long>() {

            @Override
            public void call(Long v) {
                System.out.println("Value: " + v);
                int c = count.incrementAndGet();
                if (c > 6) {
                    fail("Should be only 6");
                }

            }
        });

        latch.await(1000, TimeUnit.MILLISECONDS);

        System.out.println("unsubscribed: " + unsubscribed.get());

        assertTrue(unsubscribed.get());

    }

    @Test
    public void testMergeArrayWithThreading() {
        final TestASynchronousObservable o1 = new TestASynchronousObservable();
        final TestASynchronousObservable o2 = new TestASynchronousObservable();

        Observable<String> m = Observable.merge(Observable.create(o1), Observable.create(o2));
        m.subscribe(stringObserver);

        try {
            o1.t.join();
            o2.t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        verify(stringObserver, never()).onError(any(Throwable.class));
        verify(stringObserver, times(2)).onNext("hello");
        verify(stringObserver, times(1)).onCompleted();
    }

    @Test
    public void testSynchronizationOfMultipleSequences() throws Throwable {
        final TestASynchronousObservable o1 = new TestASynchronousObservable();
        final TestASynchronousObservable o2 = new TestASynchronousObservable();

        // use this latch to cause onNext to wait until we're ready to let it go
        final CountDownLatch endLatch = new CountDownLatch(1);

        final AtomicInteger concurrentCounter = new AtomicInteger();
        final AtomicInteger totalCounter = new AtomicInteger();

        Observable<String> m = Observable.merge(Observable.create(o1), Observable.create(o2));
        m.subscribe(new Subscriber<String>() {

            @Override
            public void onCompleted() {

            }

            @Override
            public void onError(Throwable e) {
                throw new RuntimeException("failed", e);
            }

            @Override
            public void onNext(String v) {
                totalCounter.incrementAndGet();
                concurrentCounter.incrementAndGet();
                try {
                    // wait here until we're done asserting
                    endLatch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    throw new RuntimeException("failed", e);
                } finally {
                    concurrentCounter.decrementAndGet();
                }
            }

        });

        // wait for both observables to send (one should be blocked)
        o1.onNextBeingSent.await();
        o2.onNextBeingSent.await();

        // I can't think of a way to know for sure that both threads have or are trying to send onNext
        // since I can't use a CountDownLatch for "after" onNext since I want to catch during it
        // but I can't know for sure onNext is invoked
        // so I'm unfortunately reverting to using a Thread.sleep to allow the process scheduler time
        // to make sure after o1.onNextBeingSent and o2.onNextBeingSent are hit that the following
        // onNext is invoked.

        Thread.sleep(300);

        try { // in try/finally so threads are released via latch countDown even if assertion fails
            assertEquals(1, concurrentCounter.get());
        } finally {
            // release so it can finish
            endLatch.countDown();
        }

        try {
            o1.t.join();
            o2.t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(2, totalCounter.get());
        assertEquals(0, concurrentCounter.get());
    }

    /**
     * unit test from OperationMergeDelayError backported here to show how these use cases work with normal merge
     */
    @Test
    public void testError1() {
        // we are using synchronous execution to test this exactly rather than non-deterministic concurrent behavior
        final Observable<String> o1 = Observable.create(new TestErrorObservable("four", null, "six")); // we expect to lose "six"
        final Observable<String> o2 = Observable.create(new TestErrorObservable("one", "two", "three")); // we expect to lose all of these since o1 is done first and fails

        Observable<String> m = Observable.merge(o1, o2);
        m.subscribe(stringObserver);

        verify(stringObserver, times(1)).onError(any(NullPointerException.class));
        verify(stringObserver, never()).onCompleted();
        verify(stringObserver, times(0)).onNext("one");
        verify(stringObserver, times(0)).onNext("two");
        verify(stringObserver, times(0)).onNext("three");
        verify(stringObserver, times(1)).onNext("four");
        verify(stringObserver, times(0)).onNext("five");
        verify(stringObserver, times(0)).onNext("six");
    }

    /**
     * unit test from OperationMergeDelayError backported here to show how these use cases work with normal merge
     */
    @Test
    public void testError2() {
        // we are using synchronous execution to test this exactly rather than non-deterministic concurrent behavior
        final Observable<String> o1 = Observable.create(new TestErrorObservable("one", "two", "three"));
        final Observable<String> o2 = Observable.create(new TestErrorObservable("four", null, "six")); // we expect to lose "six"
        final Observable<String> o3 = Observable.create(new TestErrorObservable("seven", "eight", null));// we expect to lose all of these since o2 is done first and fails
        final Observable<String> o4 = Observable.create(new TestErrorObservable("nine"));// we expect to lose all of these since o2 is done first and fails

        Observable<String> m = Observable.merge(o1, o2, o3, o4);
        m.subscribe(stringObserver);

        verify(stringObserver, times(1)).onError(any(NullPointerException.class));
        verify(stringObserver, never()).onCompleted();
        verify(stringObserver, times(1)).onNext("one");
        verify(stringObserver, times(1)).onNext("two");
        verify(stringObserver, times(1)).onNext("three");
        verify(stringObserver, times(1)).onNext("four");
        verify(stringObserver, times(0)).onNext("five");
        verify(stringObserver, times(0)).onNext("six");
        verify(stringObserver, times(0)).onNext("seven");
        verify(stringObserver, times(0)).onNext("eight");
        verify(stringObserver, times(0)).onNext("nine");
    }

    private static class TestSynchronousObservable implements Observable.OnSubscribeFunc<String> {

        @Override
        public Subscription onSubscribe(Observer<? super String> observer) {

            observer.onNext("hello");
            observer.onCompleted();

            return Subscriptions.empty();
        }
    }

    private static class TestASynchronousObservable implements Observable.OnSubscribeFunc<String> {
        Thread t;
        final CountDownLatch onNextBeingSent = new CountDownLatch(1);

        @Override
        public Subscription onSubscribe(final Observer<? super String> observer) {
            t = new Thread(new Runnable() {

                @Override
                public void run() {
                    onNextBeingSent.countDown();
                    observer.onNext("hello");
                    // I can't use a countDownLatch to prove we are actually sending 'onNext'
                    // since it will block if synchronized and I'll deadlock
                    observer.onCompleted();
                }

            });
            t.start();

            return Subscriptions.empty();
        }
    }

    /**
     * A Observable that doesn't do the right thing on UnSubscribe/Error/etc in that it will keep sending events down the pipe regardless of what happens.
     */
    private static class TestObservable implements Observable.OnSubscribeFunc<String> {

        Observer<? super String> observer = null;
        volatile boolean unsubscribed = false;
        Subscription s = new Subscription() {

            @Override
            public void unsubscribe() {
                unsubscribed = true;

            }

            @Override
            public boolean isUnsubscribed() {
                return unsubscribed;
            }

        };

        /* used to simulate subscription */
        public void sendOnCompleted() {
            observer.onCompleted();
        }

        /* used to simulate subscription */
        public void sendOnNext(String value) {
            observer.onNext(value);
        }

        /* used to simulate subscription */
        @SuppressWarnings("unused")
        public void sendOnError(Throwable e) {
            observer.onError(e);
        }

        @Override
        public Subscription onSubscribe(final Observer<? super String> observer) {
            this.observer = observer;
            return s;
        }
    }

    private static class TestErrorObservable implements Observable.OnSubscribeFunc<String> {

        String[] valuesToReturn;

        TestErrorObservable(String... values) {
            valuesToReturn = values;
        }

        @Override
        public Subscription onSubscribe(Observer<? super String> observer) {

            for (String s : valuesToReturn) {
                if (s == null) {
                    System.out.println("throwing exception");
                    observer.onError(new NullPointerException());
                } else {
                    observer.onNext(s);
                }
            }
            observer.onCompleted();

            return Subscriptions.empty();
        }
    }

    @Test
    public void testWhenMaxConcurrentIsOne() {
        for (int i = 0; i < 100; i++) {
            List<Observable<String>> os = new ArrayList<Observable<String>>();
            os.add(Observable.from("one", "two", "three", "four", "five").subscribeOn(Schedulers.newThread()));
            os.add(Observable.from("one", "two", "three", "four", "five").subscribeOn(Schedulers.newThread()));
            os.add(Observable.from("one", "two", "three", "four", "five").subscribeOn(Schedulers.newThread()));

            List<String> expected = Arrays.asList("one", "two", "three", "four", "five", "one", "two", "three", "four", "five", "one", "two", "three", "four", "five");
            Iterator<String> iter = Observable.merge(os, 1).toBlockingObservable().toIterable().iterator();
            List<String> actual = new ArrayList<String>();
            while (iter.hasNext()) {
                actual.add(iter.next());
            }
            assertEquals(expected, actual);
        }
    }

    @Test
    public void testMaxConcurrent() {
        for (int times = 0; times < 100; times++) {
            int observableCount = 100;
            // Test maxConcurrent from 2 to 12
            int maxConcurrent = 2 + (times % 10);
            AtomicInteger subscriptionCount = new AtomicInteger(0);

            List<Observable<String>> os = new ArrayList<Observable<String>>();
            List<SubscriptionCheckObservable> scos = new ArrayList<SubscriptionCheckObservable>();
            for (int i = 0; i < observableCount; i++) {
                SubscriptionCheckObservable sco = new SubscriptionCheckObservable(
                        subscriptionCount, maxConcurrent);
                scos.add(sco);
                os.add(Observable.create(sco).subscribeOn(
                        Schedulers.computation()));
            }

            Iterator<String> iter = Observable.merge(os, maxConcurrent)
                    .toBlockingObservable().toIterable().iterator();
            List<String> actual = new ArrayList<String>();
            while (iter.hasNext()) {
                actual.add(iter.next());
            }
            assertEquals(5 * observableCount, actual.size());
            for (SubscriptionCheckObservable sco : scos) {
                assertFalse(sco.failed);
            }
        }
    }

    private static class SubscriptionCheckObservable implements
            Observable.OnSubscribeFunc<String> {

        private final AtomicInteger subscriptionCount;
        private final int maxConcurrent;
        volatile boolean failed = false;

        SubscriptionCheckObservable(AtomicInteger subscriptionCount,
                int maxConcurrent) {
            this.subscriptionCount = subscriptionCount;
            this.maxConcurrent = maxConcurrent;
        }

        @Override
        public Subscription onSubscribe(Observer<? super String> t1) {
            if (subscriptionCount.incrementAndGet() > maxConcurrent) {
                failed = true;
            }
            t1.onNext("one");
            t1.onNext("two");
            t1.onNext("three");
            t1.onNext("four");
            t1.onNext("five");
            // We could not decrement subscriptionCount in the unsubscribe method
            // as "unsubscribe" is not guaranteed to be called before the next "subscribe".
            subscriptionCount.decrementAndGet();
            t1.onCompleted();
            return Subscriptions.empty();
        }

    }
}
