package com.tencent.matrix.batterycanary.monitor.feature;

import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;

import com.tencent.matrix.batterycanary.monitor.AppStats;
import com.tencent.matrix.batterycanary.monitor.BatteryMonitorCore;
import com.tencent.matrix.batterycanary.monitor.feature.AbsTaskMonitorFeature.TaskJiffiesSnapshot;
import com.tencent.matrix.batterycanary.monitor.feature.JiffiesMonitorFeature.JiffiesSnapshot;
import com.tencent.matrix.batterycanary.monitor.feature.JiffiesMonitorFeature.JiffiesSnapshot.ThreadJiffiesEntry;
import com.tencent.matrix.batterycanary.monitor.feature.MonitorFeature.Snapshot;
import com.tencent.matrix.batterycanary.monitor.feature.MonitorFeature.Snapshot.Delta;
import com.tencent.matrix.batterycanary.monitor.feature.MonitorFeature.Snapshot.Entry.DigitEntry;
import com.tencent.matrix.batterycanary.stats.HealthStatsFeature;
import com.tencent.matrix.batterycanary.utils.BatteryCanaryUtil;
import com.tencent.matrix.batterycanary.utils.Consumer;
import com.tencent.matrix.batterycanary.utils.Function;
import com.tencent.matrix.batterycanary.utils.PowerProfile;
import com.tencent.matrix.util.MatrixLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

/**
 * @author Kaede
 * @since 2021/9/18
 */
public class CompositeMonitors {
    private static final String TAG = "Matrix.battery.CompositeMonitors";

    public static final String SCOPE_UNKNOWN = "unknown";
    public static final String SCOPE_CANARY = "canary";
    public static final String SCOPE_INTERNAL = "internal";
    public static final String SCOPE_OVERHEAT = "overheat";

    // Differing
    protected final List<Class<? extends Snapshot<?>>> mMetrics = new ArrayList<>();
    protected final Map<Class<? extends Snapshot<?>>, Snapshot<?>> mBgnSnapshots = new HashMap<>();
    protected final Map<Class<? extends Snapshot<?>>, Delta<?>> mDeltas = new HashMap<>();

    // Sampling
    protected final Map<Class<? extends Snapshot<?>>, Long> mSampleRegs = new HashMap<>();
    protected final Map<Class<? extends Snapshot<?>>, Snapshot.Sampler> mSamplers = new HashMap<>();
    protected final Map<Class<? extends Snapshot<?>>, Snapshot.Sampler.Result> mSampleResults = new HashMap<>();

    // Task Tracing
    protected final Map<Class<? extends AbsTaskMonitorFeature>, List<Delta<TaskJiffiesSnapshot>>> mTaskDeltas = new HashMap<>();
    protected final Map<String, List<Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>>>> mTaskDeltasCollect = new HashMap<>();

    // Extra Info
    protected final Bundle mExtras = new Bundle();

    // Call Stacks
    protected final Map<String, String> mStacks = new HashMap<>();

    @Nullable
    protected BatteryMonitorCore mMonitor;
    @Nullable
    protected AppStats mAppStats;
    @Nullable
    protected CpuFreqSampler mCpuFreqSampler;

    protected long mBgnMillis = SystemClock.uptimeMillis();
    protected String mScope;

    public CompositeMonitors(@Nullable BatteryMonitorCore core) {
        mMonitor = core;
        mScope = SCOPE_UNKNOWN;
    }

    public CompositeMonitors(@Nullable BatteryMonitorCore core, String scope) {
        mMonitor = core;
        mScope = scope;
    }

    public String getScope() {
        return mScope;
    }

    @CallSuper
    public void clear() {
        MatrixLog.i(TAG, hashCode() + " #clear: " + mScope);
        mBgnSnapshots.clear();
        mDeltas.clear();
        mSamplers.clear();
        mSampleResults.clear();
        mTaskDeltas.clear();
        mTaskDeltasCollect.clear();
        mExtras.clear();
        mStacks.clear();
        mCpuFreqSampler = null;
    }

    public CompositeMonitors fork() {
        return fork(new CompositeMonitors(mMonitor, mScope));
    }

    @CallSuper
    protected CompositeMonitors fork(CompositeMonitors that) {
        MatrixLog.i(TAG, hashCode() + " #fork: " + mScope);
        that.clear();
        that.mBgnMillis = this.mBgnMillis;
        that.mAppStats = this.mAppStats;

        that.mMetrics.addAll(mMetrics);
        that.mBgnSnapshots.putAll(mBgnSnapshots);
        that.mDeltas.putAll(mDeltas);

        // Sampler can not be cloned.
        // that.mSampleRegs.putAll(mSampleRegs);
        // that.mSamplers.putAll(mSamplers);
        // that.mSampleResults.putAll(mSampleResults);

        that.mTaskDeltas.putAll(this.mTaskDeltas);
        that.mTaskDeltasCollect.putAll(this.mTaskDeltasCollect);
        that.mExtras.putAll(this.mExtras);
        that.mStacks.putAll(this.mStacks);
        that.mCpuFreqSampler = this.mCpuFreqSampler;
        return that;
    }

    @Nullable
    public BatteryMonitorCore getMonitor() {
        return mMonitor;
    }

    @Nullable
    public <T extends MonitorFeature> T getFeature(Class<T> clazz) {
        if (mMonitor == null) {
            return null;
        }
        for (MonitorFeature plugin : mMonitor.getConfig().features) {
            if (clazz.isAssignableFrom(plugin.getClass())) {
                //noinspection unchecked
                return (T) plugin;
            }
        }
        return null;
    }

    public <T extends MonitorFeature> void getFeature(Class<T> clazz, Consumer<T> block) {
        T feature = getFeature(clazz);
        if (feature != null) {
            block.accept(feature);
        }
    }

    @Nullable
    public AppStats getAppStats() {
        return mAppStats;
    }


    public void getAppStats(Consumer<AppStats> block) {
        AppStats appStats = getAppStats();
        if (appStats != null) {
            block.accept(appStats);
        }
    }

    public void setAppStats(@Nullable AppStats appStats) {
        mAppStats = appStats;
    }

    public int getCpuLoad() {
        if (mAppStats == null) {
            MatrixLog.w(TAG, "AppStats should not be null to get CpuLoad");
            return -1;
        }
        long appJiffiesDelta;
        Delta<JiffiesMonitorFeature.UidJiffiesSnapshot> uidJiffies = getDelta(JiffiesMonitorFeature.UidJiffiesSnapshot.class);
        if (uidJiffies != null) {
            appJiffiesDelta = uidJiffies.dlt.totalUidJiffies.get();
        } else {
            Delta<JiffiesSnapshot> pidJiffies = getDelta(JiffiesSnapshot.class);
            if (pidJiffies == null) {
                MatrixLog.w(TAG, JiffiesSnapshot.class + " should be metrics to get CpuLoad");
                return -1;
            }
            appJiffiesDelta = pidJiffies.dlt.totalJiffies.get();
        }

        long cpuUptimeDelta = mAppStats.duringMillis;
        float cpuLoad = cpuUptimeDelta > 0 ? (float) (appJiffiesDelta * 10) / cpuUptimeDelta : 0;
        return (int) (cpuLoad * 100);
    }

    public int getNorCpuLoad() {
        int cpuLoad = getCpuLoad();
        if (cpuLoad == -1) {
            MatrixLog.w(TAG, "cpu is invalid");
            return -1;
        }
        MonitorFeature.Snapshot.Sampler.Result result = getSamplingResult(DeviceStatMonitorFeature.CpuFreqSnapshot.class);
        if (result == null) {
            MatrixLog.w(TAG, "cpufreq is null");
            return -1;
        }
        List<int[]> cpuFreqSteps = BatteryCanaryUtil.getCpuFreqSteps();
        if (cpuFreqSteps.size() != BatteryCanaryUtil.getCpuCoreNum()) {
            MatrixLog.w(TAG, "cpuCore is invalid: " + cpuFreqSteps.size() + " vs " + BatteryCanaryUtil.getCpuCoreNum());
        }
        long sumMax = 0;
        for (int[] steps : cpuFreqSteps) {
            int max = 0;
            for (int item : steps) {
                if (item > max) {
                    max = item;
                }
            }
            sumMax += max;
        }
        if (sumMax <= 0) {
            MatrixLog.w(TAG, "cpufreq sum is invalid: " + sumMax);
            return -1;
        }
        if (result.sampleAvg >= sumMax) {
            // avgFreq should not greater than maxFreq
            MatrixLog.w(TAG, "NorCpuLoad err: sampling = " + result);
            for (int[] item : cpuFreqSteps) {
                MatrixLog.w(TAG, "NorCpuLoad err: freqs = " + Arrays.toString(item));
            }
        }
        return (int) (cpuLoad * result.sampleAvg / sumMax);
    }

    /**
     * Work in progress
     */
    public int getDevCpuLoad() {
        if (mAppStats == null) {
            MatrixLog.w(TAG, "AppStats should not be null to get CpuLoad");
            return -1;
        }
        Delta<CpuStatFeature.CpuStateSnapshot> cpuJiffies = getDelta(CpuStatFeature.CpuStateSnapshot.class);
        if (cpuJiffies == null) {
            MatrixLog.w(TAG, "Configure CpuLoad by uptime");
            return -1;
        }

        long cpuJiffiesDelta = cpuJiffies.dlt.totalCpuJiffies();
        long devJiffiesDelta = mAppStats.duringMillis;
        float cpuLoad = devJiffiesDelta > 0 ? (float) (cpuJiffiesDelta * 10) / devJiffiesDelta : 0;
        return (int) (cpuLoad * 100);
    }

    public long computeAvgJiffies(long jiffies) {
        if (mAppStats == null) {
            MatrixLog.w(TAG, "AppStats should not be null to computeAvgJiffies");
            return -1;
        }
        return computeAvgJiffies(jiffies, mAppStats.duringMillis);
    }

    public static long computeAvgJiffies(long jiffies, long millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException("Illegal millis: " + millis);
        }
        return (long) (jiffies / (millis / 60000f));
    }

    public <T extends Snapshot<T>> boolean isOverHeat(Class<T> snapshotClass) {
        AppStats appStats = getAppStats();
        Delta<?> delta = getDelta(snapshotClass);
        if (appStats == null || delta == null) {
            return false;
        }
        if (snapshotClass == JiffiesSnapshot.class) {
            //noinspection unchecked
            Delta<JiffiesSnapshot> jiffiesDelta = (Delta<JiffiesSnapshot>) delta;
            long minute = appStats.getMinute();
            long avgJiffies = jiffiesDelta.dlt.totalJiffies.get() / minute;
            return minute >= 5 && avgJiffies >= 1000;
        }
        // Override by child
        return false;
    }

    @Nullable
    public <T extends Snapshot<T>> Delta<T> getDelta(Class<T> snapshotClass) {
        //noinspection unchecked
        return (Delta<T>) mDeltas.get(snapshotClass);
    }

    public <T extends Snapshot<T>> void getDelta(Class<T> snapshotClass, Consumer<Delta<T>> block) {
        Delta<T> delta = getDelta(snapshotClass);
        if (delta != null) {
            block.accept(delta);
        }
    }

    @Nullable
    public Delta<?> getDeltaRaw(Class<? extends Snapshot<?>> snapshotClass) {
        return mDeltas.get(snapshotClass);
    }

    public void getDeltaRaw(Class<? extends Snapshot<?>> snapshotClass, Consumer<Delta<?>> block) {
        Delta<?> delta = getDeltaRaw(snapshotClass);
        if (delta != null) {
            block.accept(delta);
        }
    }

    public void putDelta(Class<? extends Snapshot<?>> snapshotClass, Delta<? extends Snapshot<?>> delta) {
        mDeltas.put(snapshotClass, delta);
    }

    public Snapshot.Sampler.Result getSamplingResult(Class<? extends Snapshot<?>> snapshotClass) {
        return mSampleResults.get(snapshotClass);
    }

    public void getSamplingResult(Class<? extends Snapshot<?>> snapshotClass, Consumer<Snapshot.Sampler.Result> block) {
        Snapshot.Sampler.Result result = getSamplingResult(snapshotClass);
        if (result != null) {
            block.accept(result);
        }
    }

    @CallSuper
    public CompositeMonitors metricAll() {
        metric(JiffiesSnapshot.class);
        metric(AlarmMonitorFeature.AlarmSnapshot.class);
        metric(WakeLockMonitorFeature.WakeLockSnapshot.class);
        metric(CpuStatFeature.CpuStateSnapshot.class);

        metric(AppStatMonitorFeature.AppStatSnapshot.class);
        metric(DeviceStatMonitorFeature.CpuFreqSnapshot.class);
        metric(DeviceStatMonitorFeature.BatteryTmpSnapshot.class);

        metric(TrafficMonitorFeature.RadioStatSnapshot.class);
        metric(BlueToothMonitorFeature.BlueToothSnapshot.class);
        metric(WifiMonitorFeature.WifiSnapshot.class);
        metric(LocationMonitorFeature.LocationSnapshot.class);
        return this;
    }

    public CompositeMonitors metricCpuLoad() {
        if (!mMetrics.contains(JiffiesSnapshot.class)) {
            metric(JiffiesSnapshot.class);
        }
        if (!mMetrics.contains(CpuStatFeature.CpuStateSnapshot.class)) {
            metric(CpuStatFeature.CpuStateSnapshot.class);
        }
        return this;
    }

    public CompositeMonitors metric(Class<? extends Snapshot<?>> snapshotClass) {
        if (!mMetrics.contains(snapshotClass)) {
            mMetrics.add(snapshotClass);
        }
        return this;
    }

    public CompositeMonitors sample(Class<? extends Snapshot<?>> snapshotClass) {
        return sample(snapshotClass, BatteryCanaryUtil.ONE_MIN);
    }

    public CompositeMonitors sample(Class<? extends Snapshot<?>> snapshotClass, long interval) {
        mSampleRegs.put(snapshotClass, interval);
        return this;
    }

    public void start() {
        MatrixLog.i(TAG, hashCode() + " #start: " + mScope);
        mAppStats = null;
        mBgnMillis = SystemClock.uptimeMillis();
        configureBgnSnapshots();
        configureSamplers();
    }

    public void finish() {
        MatrixLog.i(TAG, hashCode() + " #finish: " + mScope);
        configureEndDeltas();
        collectStacks();
        configureSampleResults();
        mAppStats = AppStats.current(SystemClock.uptimeMillis() - mBgnMillis);
    }

    protected void configureBgnSnapshots() {
        for (Class<? extends Snapshot<?>> item : mMetrics) {
            Snapshot<?> currSnapshot = statCurrSnapshot(item);
            if (currSnapshot != null) {
                mBgnSnapshots.put(item, currSnapshot);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void configureEndDeltas() {
        for (Map.Entry<Class<? extends Snapshot<?>>, Snapshot<?>> item : mBgnSnapshots.entrySet()) {
            Snapshot lastSnapshot = item.getValue();
            if (lastSnapshot != null) {
                Class<? extends Snapshot<?>> snapshotClass = item.getKey();
                Snapshot currSnapshot = statCurrSnapshot(snapshotClass);
                if (currSnapshot != null && currSnapshot.getClass() == lastSnapshot.getClass()) {
                    putDelta(snapshotClass, currSnapshot.diff(lastSnapshot));
                }
            }
        }
    }

    protected void collectStacks() {
        if (mMonitor == null) {
            return;
        }
        // Figure out thread' stack if need
        if (SCOPE_CANARY.equals(getScope())) {
            // 待机功耗监控
            AppStats appStats = getAppStats();
            if (appStats != null && !appStats.isForeground()) {
                getDelta(JiffiesSnapshot.class, new Consumer<Delta<JiffiesSnapshot>>() {
                    @Override
                    public void accept(Delta<JiffiesSnapshot> delta) {
                        long minute = Math.max(1, delta.during / BatteryCanaryUtil.ONE_MIN);
                        if (minute < 5) {
                            return;
                        }
                        for (ThreadJiffiesEntry threadEntry : delta.dlt.threadEntries.getList()) {
                            long topThreadAvgJiffies = threadEntry.get() / minute;
                            if (topThreadAvgJiffies < 3000L) {
                                break;
                            }
                            String stack = mMonitor.getConfig().callStackCollector.collect(threadEntry.tid);
                            if (!TextUtils.isEmpty(stack)) {
                                mStacks.put(String.valueOf(threadEntry.tid), stack);
                            }
                        }
                    }
                });
            }
        }
    }

    @CallSuper
    protected Snapshot<?> statCurrSnapshot(Class<? extends Snapshot<?>> snapshotClass) {
        Snapshot<?> snapshot = null;
        if (snapshotClass == AlarmMonitorFeature.AlarmSnapshot.class) {
            AlarmMonitorFeature feature = getFeature(AlarmMonitorFeature.class);
            if (feature != null) {
                snapshot = feature.currentAlarms();
            }
            return snapshot;
        }
        if (snapshotClass == BlueToothMonitorFeature.BlueToothSnapshot.class) {
            BlueToothMonitorFeature feature = getFeature(BlueToothMonitorFeature.class);
            if (feature != null) {
                snapshot = feature.currentSnapshot();
            }
            return snapshot;
        }
        if (snapshotClass == DeviceStatMonitorFeature.CpuFreqSnapshot.class) {
            DeviceStatMonitorFeature feature = getFeature(DeviceStatMonitorFeature.class);
            if (feature != null) {
                snapshot = feature.currentCpuFreq();
            }
            return snapshot;
        }
        if (snapshotClass == DeviceStatMonitorFeature.BatteryTmpSnapshot.class) {
            DeviceStatMonitorFeature feature = getFeature(DeviceStatMonitorFeature.class);
            if (feature != null && mMonitor != null) {
                snapshot = feature.currentBatteryTemperature(mMonitor.getContext());
            }
            return snapshot;
        }
        if (snapshotClass == JiffiesSnapshot.class) {
            JiffiesMonitorFeature feature = getFeature(JiffiesMonitorFeature.class);
            if (feature != null) {
                snapshot = feature.currentJiffiesSnapshot();
            }
            return snapshot;
        }
        if (snapshotClass == JiffiesMonitorFeature.UidJiffiesSnapshot.class) {
            JiffiesMonitorFeature feat = getFeature(JiffiesMonitorFeature.class);
            if (feat != null) {
                return feat.currentUidJiffiesSnapshot();
            }
        }
        if (snapshotClass == LocationMonitorFeature.LocationSnapshot.class) {
            LocationMonitorFeature feature = getFeature(LocationMonitorFeature.class);
            if (feature != null) {
                snapshot = feature.currentSnapshot();
            }
            return snapshot;
        }
        if (snapshotClass == TrafficMonitorFeature.RadioStatSnapshot.class) {
            TrafficMonitorFeature feature = getFeature(TrafficMonitorFeature.class);
            if (feature != null && mMonitor != null) {
                snapshot = feature.currentRadioSnapshot(mMonitor.getContext());
            }
            return snapshot;
        }
        if (snapshotClass == WakeLockMonitorFeature.WakeLockSnapshot.class) {
            WakeLockMonitorFeature feature = getFeature(WakeLockMonitorFeature.class);
            if (feature != null) {
                snapshot = feature.currentWakeLocks();
            }
            return snapshot;
        }
        if (snapshotClass == WifiMonitorFeature.WifiSnapshot.class) {
            WifiMonitorFeature feature = getFeature(WifiMonitorFeature.class);
            if (feature != null) {
                snapshot = feature.currentSnapshot();
            }
            return snapshot;
        }
        if (snapshotClass == CpuStatFeature.CpuStateSnapshot.class) {
            CpuStatFeature feature = getFeature(CpuStatFeature.class);
            if (feature != null && feature.isSupported()) {
                snapshot = feature.currentCpuStateSnapshot();
            }
            return snapshot;
        }
        if (snapshotClass == AppStatMonitorFeature.AppStatSnapshot.class) {
            AppStatMonitorFeature feature = getFeature(AppStatMonitorFeature.class);
            if (feature != null) {
                snapshot = feature.currentAppStatSnapshot();
            }
            return snapshot;
        }
        if (snapshotClass == HealthStatsFeature.HealthStatsSnapshot.class) {
            HealthStatsFeature feature = getFeature(HealthStatsFeature.class);
            if (feature != null) {
                snapshot = feature.currHealthStatsSnapshot();
            }
            return snapshot;
        }
        return null;
    }

    protected void configureSamplers() {
        for (Map.Entry<Class<? extends Snapshot<?>>, Long> item : mSampleRegs.entrySet()) {
            Snapshot.Sampler sampler = statSampler(item.getKey());
            if (sampler != null) {
                sampler.setInterval(item.getValue());
                sampler.start();
            }
        }
    }

    protected void configureSampleResults() {
        for (Map.Entry<Class<? extends Snapshot<?>>, Snapshot.Sampler> item : mSamplers.entrySet()) {
            MatrixLog.i(TAG, hashCode() + " " + item.getValue().getTag() + " #pause: " + mScope);
            item.getValue().pause();
            Snapshot.Sampler.Result result = item.getValue().getResult();
            if (result != null) {
                mSampleResults.put(item.getKey(), result);
            }
        }
    }

    @CallSuper
    protected Snapshot.Sampler statSampler(Class<? extends Snapshot<?>> snapshotClass) {
        Snapshot.Sampler sampler = null;
        if (snapshotClass == DeviceStatMonitorFeature.CpuFreqSnapshot.class) {
            final DeviceStatMonitorFeature feature = getFeature(DeviceStatMonitorFeature.class);
            if (feature != null && mMonitor != null) {
                final CpuStatFeature cpuStatsFeat = getFeature(CpuStatFeature.class);
                if (cpuStatsFeat != null) {
                    if (cpuStatsFeat.isSupported()) {
                        mCpuFreqSampler = new CpuFreqSampler(BatteryCanaryUtil.getCpuFreqSteps());
                    }
                }
                sampler = new Snapshot.Sampler("cpufreq", mMonitor.getHandler(), new Function<Snapshot.Sampler, Number>() {
                    @Override
                    public Number apply(Snapshot.Sampler sampler) {
                        int[] cpuFreqs = BatteryCanaryUtil.getCpuCurrentFreq();
                        if (cpuStatsFeat != null && cpuStatsFeat.isSupported()) {
                            if (mCpuFreqSampler != null && mCpuFreqSampler.isCompat(cpuStatsFeat.getPowerProfile())) {
                                mCpuFreqSampler.count(cpuFreqs);
                            }
                        }
                        DeviceStatMonitorFeature.CpuFreqSnapshot snapshot = feature.currentCpuFreq(cpuFreqs);
                        List<DigitEntry<Integer>> list = snapshot.cpuFreqs.getList();
                        MatrixLog.i(TAG, CompositeMonitors.this.hashCode() + " #onSampling: " + mScope);
                        MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + ", val = " + list);
                        if (list.isEmpty()) {
                            return Snapshot.Sampler.INVALID;
                        }
                        // Better to use sum of all cpufreqs, rather than just use the max value?
                        // Collections.sort(list, new Comparator<DigitEntry<Integer>>() {
                        //     @Override
                        //     public int compare(DigitEntry<Integer> o1, DigitEntry<Integer> o2) {
                        //         return o1.get().compareTo(o2.get());
                        //     }
                        // });
                        // return list.isEmpty() ? 0 : list.get(list.size() - 1).get();
                        long sum = 0;
                        for (DigitEntry<Integer> item : list) {
                            sum += item.get();
                        }
                        return sum;
                    }
                });
                mSamplers.put(snapshotClass, sampler);
            }
            return sampler;
        }
        if (snapshotClass == DeviceStatMonitorFeature.BatteryTmpSnapshot.class) {
            final DeviceStatMonitorFeature feature = getFeature(DeviceStatMonitorFeature.class);
            if (feature != null && mMonitor != null) {
                sampler = new Snapshot.Sampler("batt-temp", mMonitor.getHandler(), new Function<Snapshot.Sampler, Number>() {
                    @Override
                    public Number apply(Snapshot.Sampler sampler) {
                        DeviceStatMonitorFeature.BatteryTmpSnapshot snapshot = feature.currentBatteryTemperature(mMonitor.getContext());
                        Integer value = snapshot.temp.get();
                        MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + ", val = " + value);
                        if (value == -1) {
                            return Snapshot.Sampler.INVALID;
                        }
                        return value;
                    }
                });
                mSamplers.put(snapshotClass, sampler);
            }
            return sampler;
        }
        if (snapshotClass == DeviceStatMonitorFeature.ThermalStatSnapshot.class) {
            final DeviceStatMonitorFeature feature = getFeature(DeviceStatMonitorFeature.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && feature != null && mMonitor != null) {
                sampler = new Snapshot.Sampler("thermal-stat", mMonitor.getHandler(), new Function<Snapshot.Sampler, Number>() {
                    @Override
                    public Number apply(Snapshot.Sampler sampler) {
                        int value = BatteryCanaryUtil.getThermalStat(mMonitor.getContext());
                        MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + ", val = " + value);
                        if (value == -1) {
                            return Snapshot.Sampler.INVALID;
                        }
                        return value;
                    }
                });
                mSamplers.put(snapshotClass, sampler);
            }
            return sampler;
        }
        if (snapshotClass == DeviceStatMonitorFeature.ThermalHeadroomSnapshot.class) {
            final DeviceStatMonitorFeature feature = getFeature(DeviceStatMonitorFeature.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && feature != null && mMonitor != null) {
                final Long interval = mSampleRegs.get(snapshotClass);
                if (interval != null && interval >= 1000L) {
                    sampler = new Snapshot.Sampler("thermal-headroom", mMonitor.getHandler(), new Function<Snapshot.Sampler, Number>() {
                        @Override
                        public Number apply(Snapshot.Sampler sampler) {
                            float value = BatteryCanaryUtil.getThermalHeadroom(mMonitor.getContext(), (int) (interval / 1000L));
                            MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + ", val = " + value);
                            if (value == -1f) {
                                return Snapshot.Sampler.INVALID;
                            }
                            return value;
                        }
                    });
                    mSamplers.put(snapshotClass, sampler);
                }
            }
            return sampler;
        }
        if (snapshotClass == DeviceStatMonitorFeature.ChargeWattageSnapshot.class) {
            final DeviceStatMonitorFeature feature = getFeature(DeviceStatMonitorFeature.class);
            if (feature != null && mMonitor != null) {
                sampler = new Snapshot.Sampler("batt-watt", mMonitor.getHandler(), new Function<Snapshot.Sampler, Number>() {
                    @Override
                    public Number apply(Snapshot.Sampler sampler) {
                        int value = BatteryCanaryUtil.getChargingWatt(mMonitor.getContext());
                        MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + ", val = " + value);
                        if (value == -1) {
                            return Snapshot.Sampler.INVALID;
                        }
                        return value;
                    }
                });
                mSamplers.put(snapshotClass, sampler);
            }
            return sampler;
        }
        if (snapshotClass == CpuStatFeature.CpuStateSnapshot.class) {
            final CpuStatFeature feature = getFeature(CpuStatFeature.class);
            if (feature != null && feature.isSupported() && mMonitor != null) {
                sampler = new Snapshot.Sampler("cpu-stat", mMonitor.getHandler(), new Function<Snapshot.Sampler, Number>() {
                    @Override
                    public Number apply(Snapshot.Sampler sampler) {
                        CpuStatFeature.CpuStateSnapshot snapshot = feature.currentCpuStateSnapshot();
                        for (int i = 0; i < snapshot.cpuCoreStates.size(); i++) {
                            Snapshot.Entry.ListEntry<DigitEntry<Long>> item = snapshot.cpuCoreStates.get(i);
                            MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + " cpuCore" + i  + ", val = " + item.getList());
                        }
                        for (int i = 0; i < snapshot.procCpuCoreStates.size(); i++) {
                            Snapshot.Entry.ListEntry<DigitEntry<Long>> item = snapshot.procCpuCoreStates.get(i);
                            MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + " procCpuCluster" + i + ", val = " + item.getList());
                        }
                        return 0;
                    }
                });
                mSamplers.put(snapshotClass, sampler);
            }
            return sampler;
        }
        if (snapshotClass == JiffiesMonitorFeature.UidJiffiesSnapshot.class) {
            final JiffiesMonitorFeature feature = getFeature(JiffiesMonitorFeature.class);
            if (feature != null && mMonitor != null) {
                sampler = new Snapshot.Sampler("uid-jiffies", mMonitor.getHandler(), new Function<Snapshot.Sampler, Number>() {
                    JiffiesMonitorFeature.UidJiffiesSnapshot mLastSnapshot;
                    @Override
                    public Number apply(Snapshot.Sampler sampler) {
                        JiffiesMonitorFeature.UidJiffiesSnapshot curr = feature.currentUidJiffiesSnapshot();
                        if (mLastSnapshot != null) {
                            Delta<JiffiesMonitorFeature.UidJiffiesSnapshot> delta = curr.diff(mLastSnapshot);
                            long minute = Math.max(1, delta.during / BatteryCanaryUtil.ONE_MIN);
                            long avgUidJiffies = computeAvgJiffies(delta.dlt.totalUidJiffies.get(), delta.during);
                            MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + " avgUidJiffies, val = " + avgUidJiffies + ", minute = " + minute);
                            for (Delta<JiffiesSnapshot> item : delta.dlt.pidDeltaJiffiesList) {
                                long avgPidJiffies = computeAvgJiffies(item.dlt.totalJiffies.get(), delta.during);
                                MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + " avgPidJiffies, val = " + avgPidJiffies + ", minute = " + minute + ", name = " + item.dlt.name);
                            }
                            mLastSnapshot = curr;
                            return avgUidJiffies;
                        } else {
                            mLastSnapshot = curr;
                        }
                        return 0;
                    }
                });
                mSamplers.put(snapshotClass, sampler);
            }
            return sampler;
        }
        if (snapshotClass == TrafficMonitorFeature.RadioStatSnapshot.class) {
            final TrafficMonitorFeature feature = getFeature(TrafficMonitorFeature.class);
            if (feature != null && mMonitor != null) {
                sampler = new MonitorFeature.Snapshot.Sampler("traffic", mMonitor.getHandler(), new Function<Snapshot.Sampler, Number>() {
                    @Override
                    public Number apply(Snapshot.Sampler sampler) {
                        TrafficMonitorFeature.RadioStatSnapshot snapshot = feature.currentRadioSnapshot(mMonitor.getContext());
                        if (snapshot != null) {
                            MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + " wifiRx, val = " + snapshot.wifiRxBytes);
                            MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + " wifiTx, val = " + snapshot.wifiTxBytes);
                            MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + " mobileRx, val = " + snapshot.mobileRxBytes);
                            MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + " mobileTx, val = " + snapshot.mobileTxBytes);
                        }
                        return 0;
                    }
                });
                mSamplers.put(snapshotClass, sampler);
            }
            return sampler;
        }
        if (snapshotClass == DeviceStatMonitorFeature.BatteryCurrentSnapshot.class) {
            final DeviceStatMonitorFeature feature = getFeature(DeviceStatMonitorFeature.class);
            if (feature != null && mMonitor != null) {
                sampler = new MonitorFeature.Snapshot.Sampler("batt-curr", mMonitor.getHandler(), new Function<Snapshot.Sampler, Number>() {
                    @Override
                    public Number apply(Snapshot.Sampler sampler) {
                        DeviceStatMonitorFeature.BatteryCurrentSnapshot snapshot = feature.currentBatteryCurrency(mMonitor.getContext());
                        Long value = snapshot.stat.get();
                        MatrixLog.i(TAG, "onSampling " + sampler.mCount + " " + sampler.mTag + ", val = " + value);
                        if (value == -1L) {
                            return Snapshot.Sampler.INVALID;
                        }
                        return value;
                    }
                });
                mSamplers.put(snapshotClass, sampler);
            }
            return sampler;
        }
        return null;
    }

    protected void configureTaskDeltas(final Class<? extends AbsTaskMonitorFeature> featClass) {
        if (mAppStats != null) {
            AbsTaskMonitorFeature taskFeat = getFeature(featClass);
            if (taskFeat != null) {
                List<Delta<TaskJiffiesSnapshot>> deltas = taskFeat.currentJiffies(mAppStats.duringMillis);
                // No longer clear here
                // Clear at BG Scope or OverHeat
                // taskFeat.clearFinishedJiffies();
                putTaskDeltas(featClass, deltas);
            }
        }
    }

    protected void collectTaskDeltas() {
        if (!mTaskDeltas.isEmpty()) {
            for (Map.Entry<Class<? extends AbsTaskMonitorFeature>, List<Delta<TaskJiffiesSnapshot>>> entry : mTaskDeltas.entrySet()) {
                Class<? extends AbsTaskMonitorFeature> key = entry.getKey();
                for (Delta<TaskJiffiesSnapshot> taskDelta : entry.getValue()) {
                    // FIXME: better windowMillis cfg of Task and AppStats
                    if (taskDelta.bgn.time >= mBgnMillis) {
                        List<Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>>> pairList = mTaskDeltasCollect.get(taskDelta.dlt.name);
                        if (pairList == null) {
                            pairList = new ArrayList<>();
                            mTaskDeltasCollect.put(taskDelta.dlt.name, pairList);
                        }
                        pairList.add(new Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>>(key, taskDelta));
                    }
                }
            }
        }
    }

    public void putTaskDeltas(Class<? extends AbsTaskMonitorFeature> key, List<Delta<TaskJiffiesSnapshot>> deltas) {
        mTaskDeltas.put(key, deltas);
    }

    public List<Delta<TaskJiffiesSnapshot>> getTaskDeltas(Class<? extends AbsTaskMonitorFeature> key) {
        List<Delta<TaskJiffiesSnapshot>> deltas = mTaskDeltas.get(key);
        if (deltas == null) {
            return Collections.emptyList();
        }
        return deltas;
    }

    public void getTaskDeltas(Class<? extends AbsTaskMonitorFeature> key, Consumer<List<Delta<TaskJiffiesSnapshot>>> block) {
        List<Delta<TaskJiffiesSnapshot>> deltas = mTaskDeltas.get(key);
        if (deltas != null) {
            block.accept(deltas);
        }
    }

    public Map<String, List<Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>>>> getCollectedTaskDeltas() {
        if (mTaskDeltasCollect.size() <= 1) {
            return mTaskDeltasCollect;
        }
        // Sorting by jiffies sum
        return sortMapByValue(mTaskDeltasCollect, new Comparator<Map.Entry<String, List<Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>>>>>() {
            @SuppressWarnings("ConstantConditions")
            @Override
            public int compare(Map.Entry<String, List<Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>>>> o1, Map.Entry<String, List<Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>>>> o2) {
                long sumLeft = 0, sumRight = 0;
                for (Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>> item : o1.getValue()) {
                    sumLeft += item.second.dlt.jiffies.get();
                }
                for (Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>> item : o2.getValue()) {
                    sumRight += item.second.dlt.jiffies.get();
                }
                long minus = sumLeft - sumRight;
                if (minus == 0) return 0;
                if (minus > 0) return -1;
                return 1;
            }
        });
    }

    public void getCollectedTaskDeltas(Consumer<Map<String, List<Pair<Class<? extends AbsTaskMonitorFeature>, Delta<TaskJiffiesSnapshot>>>>> block) {
        block.accept(getCollectedTaskDeltas());
    }

    public void getAllPidDeltaList(Consumer<List<Delta<JiffiesSnapshot>>> block) {
        List<Delta<JiffiesSnapshot>> deltaList = getAllPidDeltaList();
        if (deltaList != null) {
            block.accept(deltaList);
        }
    }

    public List<Delta<JiffiesSnapshot>> getAllPidDeltaList() {
        Delta<JiffiesMonitorFeature.UidJiffiesSnapshot> delta = getDelta(JiffiesMonitorFeature.UidJiffiesSnapshot.class);
        if (delta == null) {
            Delta<JiffiesSnapshot> pidDelta = getDelta(JiffiesSnapshot.class);
            if (pidDelta != null) {
                return Collections.singletonList(pidDelta);
            }
            return Collections.emptyList();
        }
        return delta.dlt.pidDeltaJiffiesList;
    }

    public Map<String, String> getStacks() {
        return mStacks;
    }

    public Bundle getExtras() {
        return mExtras;
    }

    @Nullable
    public CpuFreqSampler getCpuFreqSampler() {
        return mCpuFreqSampler;
    }

    @Override
    @NonNull
    public String toString() {
        return "CompositeMonitors{" + "\n" +
                "Metrics=" + mMetrics + "\n" +
                ", BgnSnapshots=" + mBgnSnapshots + "\n" +
                ", Deltas=" + mDeltas + "\n" +
                ", SampleRegs=" + mSampleRegs + "\n" +
                ", Samplers=" + mSamplers + "\n" +
                ", SampleResults=" + mSampleResults + "\n" +
                ", TaskDeltas=" + mTaskDeltas + "\n" +
                ", AppStats=" + mAppStats + "\n" +
                ", Stacks=" + mStacks + "\n" +
                ", Extras =" + mExtras + "\n" +
                '}';
    }

    static <K, V> Map<K, V> sortMapByValue(Map<K, V> map, Comparator<? super Map.Entry<K, V>> comparator) {
        List<Map.Entry<K, V>> list = new ArrayList<>(map.entrySet());
        Collections.sort(list, comparator);

        Map<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : list) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public static class CpuFreqSampler {
        public int[] cpuCurrentFreq;
        public final List<int[]> cpuFreqSteps;
        public final List<int[]> cpuFreqCounters;

        public CpuFreqSampler(List<int[]> cpuFreqSteps) {
            this.cpuFreqSteps = cpuFreqSteps;
            this.cpuFreqCounters = new ArrayList<>(cpuFreqSteps.size());
            for (int[] item : cpuFreqSteps) {
                this.cpuFreqCounters.add(new int[item.length]);
            }
        }

        public boolean isCompat(PowerProfile powerProfile) {
            if (cpuFreqSteps.size() == powerProfile.getCpuCoreNum()) {
                for (int i = 0; i < cpuFreqSteps.size(); i++) {
                    int clusterByCpuNum = powerProfile.getClusterByCpuNum(i);
                    int steps = powerProfile.getNumSpeedStepsInCpuCluster(clusterByCpuNum);
                    if (cpuFreqSteps.get(i).length != steps) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        public void count(int[] cpuCurrentFreq) {
            this.cpuCurrentFreq = BatteryCanaryUtil.getCpuCurrentFreq();
            for (int i = 0; i < cpuCurrentFreq.length; i++) {
                int speed = cpuCurrentFreq[i];
                int[] steps = cpuFreqSteps.get(i);
                if (speed < steps[0]) {
                    cpuFreqCounters.get(i)[0]++;
                    continue;
                }
                boolean found = false;
                for (int j = 0; j < steps.length; j++) {
                    if (speed <= steps[j]) {
                        cpuFreqCounters.get(i)[j]++;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    if (speed > steps[steps.length - 1]) {
                        cpuFreqCounters.get(i)[steps.length - 1]++;
                    }
                }
            }
        }
    }
}
