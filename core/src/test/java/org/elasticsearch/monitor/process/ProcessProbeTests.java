/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.monitor.process;

import org.apache.lucene.util.Constants;
import org.elasticsearch.bootstrap.BootstrapInfo;
import org.elasticsearch.test.ESTestCase;
import org.junit.Test;

import static org.elasticsearch.monitor.jvm.JvmInfo.jvmInfo;
import static org.hamcrest.Matchers.*;

public class ProcessProbeTests extends ESTestCase {

    ProcessProbe probe = ProcessProbe.getInstance();

    @Test
    public void testProcessInfo() {
        ProcessInfo info = probe.processInfo();
        assertNotNull(info);
        assertThat(info.getRefreshInterval(), greaterThanOrEqualTo(0L));
        assertThat(info.getId(), equalTo(jvmInfo().pid()));
        assertThat(info.isMlockall(), equalTo(BootstrapInfo.isMemoryLocked()));
    }

    @Test
    public void testProcessStats() {
        ProcessStats stats = probe.processStats();
        assertNotNull(stats);
        assertThat(stats.getTimestamp(), greaterThan(0L));

        if (Constants.WINDOWS) {
            // Open/Max files descriptors are not supported on Windows platforms
            assertThat(stats.getOpenFileDescriptors(), equalTo(-1L));
            assertThat(stats.getMaxFileDescriptors(), equalTo(-1L));
        } else {
            assertThat(stats.getOpenFileDescriptors(), greaterThan(0L));
            assertThat(stats.getMaxFileDescriptors(), greaterThan(0L));
        }

        ProcessStats.Cpu cpu = stats.getCpu();
        assertNotNull(cpu);

        // CPU percent can be negative if the system recent cpu usage is not available
        assertThat(cpu.getPercent(), anyOf(lessThan((short) 0), allOf(greaterThanOrEqualTo((short) 0), lessThanOrEqualTo((short) 100))));

        // CPU time can return -1 if the the platform does not support this operation, let's see which platforms fail
        assertThat(cpu.total, greaterThan(0L));

        ProcessStats.Mem mem = stats.getMem();
        assertNotNull(mem);
        // Commited total virtual memory can return -1 if not supported, let's see which platforms fail
        assertThat(mem.totalVirtual, greaterThan(0L));
    }
}
