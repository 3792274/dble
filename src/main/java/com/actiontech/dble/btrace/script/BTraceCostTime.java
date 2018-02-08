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
    }
    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "startProcess"
    )
    public static void startProcess(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->1.startProcess", duration);
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
        Profiling.recordExit(profiler, "request->2.endParse", duration);
    }

    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "endRoute"
    )
    public static void endRoute(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->3.endRoute", duration);
    }

    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "pushDown"
    )
    public static void pushDown(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->3.1.pushdown", duration);
    }

    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "resFromBack"
    )
    public static void resFromBack(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->4.resFromBack", duration);
    }


    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "setPushQueue"
    )
    public static void setPushQueue(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->4.1.setPushQueue", duration);
    }


    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "setProcessFinished"
    )
    public static void setProcessFinished(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->4.2setProcessFinished", duration);
    }

    @OnMethod(
            clazz = "com.actiontech.dble.btrace.provider.CostTimeProvider",
            method = "startExecuteBackend"
    )
    public static void startExecuteBackend(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
        Long ts = BTraceUtils.Collections.get(records, arg);
        if (ts == null) {
            return;
        }
        long duration = timeNanos() - ts;
        Profiling.recordExit(profiler, "request->5.startExecuteBackend", duration);
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
        Profiling.recordExit(profiler, "request->6.response", duration);
    }


//    @OnMethod(clazz="com.actiontech.dble.net.NIOSocketWR", method="asyncReadBack", location=@Location(value=Kind.RETURN))
//    public static void asyncReadBack(@ProbeMethodName(fqn=true) String probeMethod, @Duration long duration) {
//        BTraceUtils.Profiling.recordExit(profiler, probeMethod, duration);
//    }
//
//    @OnMethod(clazz="com.actiontech.dble.net.AbstractConnection", method="onReadDataBackend", location=@Location(value=Kind.RETURN))
//    public static void onReadDataBackend(@ProbeMethodName(fqn=true) String probeMethod, @Duration long duration) {
//        BTraceUtils.Profiling.recordExit(profiler, probeMethod, duration);
//    }
//
//    @OnMethod(clazz="com.actiontech.dble.net.handler.BackendAsyncHandler", method="offerData", location=@Location(value=Kind.RETURN))
//    public static void offerData(@ProbeMethodName(fqn=true) String probeMethod, @Duration long duration) {
//        BTraceUtils.Profiling.recordExit(profiler, probeMethod, duration);
//    }
//
//    @OnMethod(
//            clazz = "com.actiontech.dble.net.NIOReactor",
//            method = "backendSelectCostTime"
//    )
//    public static void backendSelectCostTime(@ProbeClassName String probeClass, @ProbeMethodName String probeMethod, long arg) {
//
//        Profiling.recordExit(profiler, "4-3 selector_cost_time", arg);
//    }

    @OnTimer(4000)
    public static void print() {
        BTraceUtils.Profiling.printSnapshot("profiling:", profiler);
    }
}
