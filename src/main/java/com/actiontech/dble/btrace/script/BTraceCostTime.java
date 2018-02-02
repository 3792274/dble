/*
 * Copyright (C) 2016-2018 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.btrace.script;


import com.sun.btrace.BTraceUtils;
import com.sun.btrace.Profiler;
import com.sun.btrace.annotations.*;

import java.util.Map;

import static com.sun.btrace.BTraceUtils.Profiling;
import static com.sun.btrace.BTraceUtils.timeNanos;

@BTrace
public class BTraceCostTime {
    private static Map<Long, Long> records = BTraceUtils.Collections.newHashMap();

    @Property
    static Profiler profiler = BTraceUtils.Profiling.newProfiler();

    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "beginRequest"
    )
    public static void beginRequest(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        BTraceUtils.Collections.put(records, arg, timeNanos());
        Profiling.recordEntry(profiler, "request->response");
        Profiling.recordEntry(profiler, "request->endParseProtocol");
        Profiling.recordEntry(profiler, "request->endParse");
        Profiling.recordEntry(profiler, "request->endRouter");
    }

    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "endParseProtocol"
    )
    public static void endParseProtocol(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->endParseProtocol", duration);
    }

    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "endParse"
    )
    public static void endParse(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->endParse", duration);
    }

    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "endRouter"
    )
    public static void endRouter(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->endRouter", duration);
    }

    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "beginResponse"
    )
    public static void beginResponse(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        BTraceUtils.Collections.remove(records, arg);
        Profiling.recordExit(profiler, "request->response", duration);
    }


    @OnTimer(4000)
    public static void print() {
        BTraceUtils.Profiling.printSnapshot("profiling:", profiler);
    }
}
