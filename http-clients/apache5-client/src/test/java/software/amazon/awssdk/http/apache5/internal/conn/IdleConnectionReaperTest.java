/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.apache5.internal.conn;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.io.CloseMode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Tests for {@link IdleConnectionReaper}.
 */
@RunWith(MockitoJUnitRunner.class)
public class IdleConnectionReaperTest {
    private static final long SLEEP_PERIOD = 250;

    private final Map<PoolingHttpClientConnectionManager, Long> connectionManagers = new HashMap<>();

    @Mock
    public ExecutorService executorService;

    @Mock
    public PoolingHttpClientConnectionManager connectionManager;

    private IdleConnectionReaper idleConnectionReaper;

    @Before
    public void methodSetup() {
        this.connectionManagers.clear();
        idleConnectionReaper = new IdleConnectionReaper(connectionManagers, () -> executorService, SLEEP_PERIOD);
    }

    @Test
    public void setsUpExecutorIfManagerNotPreviouslyRegistered() {
        idleConnectionReaper.registerConnectionManager(connectionManager, 1L);
        verify(executorService).execute(any(Runnable.class));
    }

    @Test
    public void shutsDownExecutorIfMapEmptied() {
        // use register method so it sets up the executor
        idleConnectionReaper.registerConnectionManager(connectionManager, 1L);
        idleConnectionReaper.deregisterConnectionManager(connectionManager);
        verify(executorService).shutdownNow();
    }

    @Test
    public void doesNotShutDownExecutorIfNoManagerRemoved() {
        idleConnectionReaper.registerConnectionManager(connectionManager, 1L);
        HttpClientConnectionManager someOtherConnectionManager = mock(HttpClientConnectionManager.class);
        idleConnectionReaper.deregisterConnectionManager(someOtherConnectionManager);
        verify(executorService, times(0)).shutdownNow();
    }

    @Test(timeout = 1000L)
    public void testReapsConnections() throws InterruptedException {
        IdleConnectionReaper reaper = new IdleConnectionReaper(new HashMap<>(),
                                                               Executors::newSingleThreadExecutor,
                                                               SLEEP_PERIOD);
        final long idleTime = 1L;
        reaper.registerConnectionManager(connectionManager, idleTime);
        try {
            Thread.sleep(SLEEP_PERIOD * 2);
            verify(connectionManager, atLeastOnce()).closeIdle(any(TimeValue.class));
        } finally {
            reaper.deregisterConnectionManager(connectionManager);
        }
    }
}
