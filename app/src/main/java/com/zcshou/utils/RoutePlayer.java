package com.zcshou.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 路线巡航播放器（“循环路径”核心）v2。
 * <p>
 * v2 相比 v1 的改进：
 * 1. 修复循环接缝：到点结算后不再重复结算（justWrapped 守卫），停留计时在换圈时清零；
 * 2. 每圈轨迹微扰：lapOffsetMeter 生效时，每圈整体沿正弦包络偏移，接缝处偏移为 0，
 *    既让每圈轨迹不完全相同（避免被判“重复轨迹”），又不会产生位置跳变；
 * 3. 低频漂移：driftMeter 用慢速正弦而非白噪声，位置连续、方差小，
 *    不会触发跑步类 App 的“漂移点 / 方差”过滤器；
 * 4. 转弯降速：距离拐点 10 m 内按转角比例线性降速，最多降 45%；
 * 5. 步频驱动：advanceMeters(m) 让服务端用真实步数×步幅推进，步数与距离自洽；
 * 6. 位置严格单调前进，不会来回抖动。
 */
public class RoutePlayer {

    /** 闭环循环：走到终点后沿闭合段回到起点，反复进行 */
    public static final int MODE_LOOP = 0;
    /** 往返：走到终点后原路返回，反复进行 */
    public static final int MODE_PINGPONG = 1;
    /** 单次：走到终点即结束 */
    public static final int MODE_ONCE = 2;

    /** 一次推进返回的姿态 */
    public static class Pose {
        public double lng;
        public double lat;
        /** 相对海拔偏移（米），由地形项产生，调用方自行叠加基准海拔 */
        public double alt;
        public double speed;
        public double bearing;
        /** 本圈进度 0~1 */
        public double progress;
        public int lap;
        public boolean moving;
        public boolean finished;
    }

    private final List<RoutePath.Point> path = new ArrayList<>();
    private double[] segLen = new double[0];
    private double lapLen = 0.0;

    private final int mode;
    private final double accel;
    private final double terrainAmp;
    private final double terrainPeriod;
    private final double lapOffsetMeter;
    private final double driftMeter;

    private final Random rnd = new Random();

    private double targetSpeed;
    private double curSpeed = 0.0;

    private int seg = 0;
    private double distInSeg = 0.0;
    private double travelled = 0.0;
    private double lapTravelled = 0.0;
    private int lap = 0;
    private double dwellLeft = 0.0;
    private boolean paused = false;
    private boolean finished = false;
    private boolean justWrapped = false;
    private double lastBearing = 0.0;
    private double elapsed = 0.0;

    /* 本圈微扰向量与漂移相位 */
    private double lapOffLng = 0.0;
    private double lapOffLat = 0.0;
    private final double driftPhaseX = 0.0;
    private final double driftPhaseY = 1.7;

    public RoutePlayer(List<RoutePath.Point> points, int mode, double speed,
                       double accel, double terrainAmp, double terrainPeriod) {
        this(points, mode, speed, accel, terrainAmp, terrainPeriod, 0.0, 0.0);
    }

    /**
     * @param lapOffsetMeter 每圈轨迹微扰幅度（米），0 表示每圈完全一致
     * @param driftMeter     低频漂移幅度（米），0 表示不加漂移
     */
    public RoutePlayer(List<RoutePath.Point> points, int mode, double speed,
                       double accel, double terrainAmp, double terrainPeriod,
                       double lapOffsetMeter, double driftMeter) {
        this.mode = mode;
        this.targetSpeed = speed > 0 ? speed : 1.2;
        this.accel = accel > 0 ? accel : 1.2;
        this.terrainAmp = Math.max(0.0, terrainAmp);
        this.terrainPeriod = terrainPeriod > 1.0 ? terrainPeriod : 500.0;
        this.lapOffsetMeter = Math.max(0.0, lapOffsetMeter);
        this.driftMeter = Math.max(0.0, driftMeter);

        List<RoutePath.Point> valid = new ArrayList<>();
        if (points != null) {
            for (RoutePath.Point p : points) {
                if (p == null) {
                    continue;
                }
                if (!valid.isEmpty()
                        && RoutePath.distanceMeter(valid.get(valid.size() - 1), p) < 0.05) {
                    continue;
                }
                valid.add(p.copy());
            }
        }
        path.addAll(valid);
        if (mode == MODE_LOOP && path.size() >= 2) {
            RoutePath.Point first = path.get(0);
            RoutePath.Point last = path.get(path.size() - 1);
            if (RoutePath.distanceMeter(first, last) > 0.5) {
                path.add(first.copy());
            }
        }
        rebuild();
        newLapOffset();
    }

    private void rebuild() {
        segLen = new double[Math.max(0, path.size() - 1)];
        lapLen = 0.0;
        for (int i = 1; i < path.size(); i++) {
            double d = RoutePath.distanceMeter(path.get(i - 1), path.get(i));
            segLen[i - 1] = d;
            lapLen += d;
        }
        if (seg >= segLen.length) {
            seg = Math.max(0, segLen.length - 1);
            distInSeg = 0;
        }
    }

    /** 每圈重新抽一个偏移向量（正弦包络，接缝处为 0） */
    private void newLapOffset() {
        if (lapOffsetMeter <= 0) {
            lapOffLng = 0;
            lapOffLat = 0;
            return;
        }
        double ang = rnd.nextDouble() * 2 * Math.PI;
        double mag = lapOffsetMeter * (0.4 + 0.6 * rnd.nextDouble());
        double midLat = path.isEmpty() ? 30.0 : path.get(0).lat;
        lapOffLat = (mag * Math.cos(ang)) / RoutePath.METER_PER_DEG_LAT;
        lapOffLng = (mag * Math.sin(ang)) / RoutePath.meterPerDegLng(midLat);
    }

    public boolean isValid() {
        return path.size() >= 2 && lapLen > 0.01;
    }

    public double getLength() {
        return lapLen;
    }

    public double getTravelled() {
        return travelled;
    }

    public int getLap() {
        return lap;
    }

    public int getMode() {
        return mode;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isPaused() {
        return paused;
    }

    public double getTargetSpeed() {
        return targetSpeed;
    }

    public void setTargetSpeed(double speed) {
        if (speed >= 0) {
            targetSpeed = speed;
        }
    }

    public void setPaused(boolean p) {
        paused = p;
    }

    public void reset() {
        seg = 0;
        distInSeg = 0;
        travelled = 0;
        lapTravelled = 0;
        lap = 0;
        dwellLeft = 0;
        curSpeed = 0;
        finished = false;
        justWrapped = false;
        lastBearing = 0;
        elapsed = 0;
        if (mode == MODE_PINGPONG && path.size() >= 2) {
            RoutePath.Point first = path.get(0);
            RoutePath.Point last = path.get(path.size() - 1);
            if (RoutePath.distanceMeter(first, last) < 0.5 && path.size() > 2) {
                Collections.reverse(path);
            }
        }
        newLapOffset();
        rebuild();
    }

    /** 当前姿态（不推进时间） */
    public Pose currentPose() {
        Pose pose = new Pose();
        pose.lap = lap;
        pose.speed = curSpeed;
        pose.bearing = lastBearing;
        pose.progress = lapLen > 0 ? Math.min(1.0, lapTravelled / lapLen) : 0.0;
        pose.finished = finished;

        if (path.isEmpty()) {
            return pose;
        }
        if (path.size() == 1 || seg >= path.size() - 1) {
            RoutePath.Point p = path.get(path.size() - 1);
            pose.lng = p.lng;
            pose.lat = p.lat;
            pose.alt = altitudeOffset();
            applyOffset(pose);
            return pose;
        }
        RoutePath.Point a = path.get(seg);
        RoutePath.Point b = path.get(seg + 1);
        double f = segLen[seg] <= 1e-9 ? 0.0 : distInSeg / segLen[seg];
        RoutePath.Point p = RoutePath.interpolate(a, b, f);
        pose.lng = p.lng;
        pose.lat = p.lat;
        pose.alt = altitudeOffset();
        applyOffset(pose);
        return pose;
    }

    /** 叠加每圈微扰（正弦包络）与低频漂移，保证接缝连续、不来回跳 */
    private void applyOffset(Pose pose) {
        double p = lapLen > 0 ? Math.min(1.0, lapTravelled / lapLen) : 0.0;

        if (lapOffsetMeter > 0) {
            double envelope = Math.sin(Math.PI * p);     // 0 → 1 → 0，接缝为 0
            pose.lng += lapOffLng * envelope;
            pose.lat += lapOffLat * envelope;
        }

        if (driftMeter > 0) {
            double dx = driftMeter * Math.sin(2 * Math.PI * elapsed / 47.0 + driftPhaseX);
            double dy = driftMeter * Math.cos(2 * Math.PI * elapsed / 61.0 + driftPhaseY);
            pose.lng += dx / Math.max(1.0, RoutePath.meterPerDegLng(pose.lat));
            pose.lat += dy / RoutePath.METER_PER_DEG_LAT;
        }
    }

    private double altitudeOffset() {
        if (terrainAmp <= 0) {
            return 0.0;
        }
        double phase = 2.0 * Math.PI * (lapTravelled / terrainPeriod);
        return terrainAmp * Math.sin(phase);
    }

    /** 转弯降速：距拐点 10 m 内按转角比例降速，最多 45% */
    private double cornerLimitedSpeed() {
        double target = targetSpeed;
        if (path.size() < 3 || segLen.length < 2) {
            return target;
        }
        int next = seg + 1;
        if (next >= segLen.length) {
            return target;
        }
        double remain = Math.max(0.0, segLen[seg] - distInSeg);
        if (remain > 10.0) {
            return target;
        }
        double b1 = RoutePath.bearingDeg(path.get(seg), path.get(seg + 1));
        double b2 = RoutePath.bearingDeg(path.get(next), path.get(next + 1));
        double diff = Math.abs(((b2 - b1 + 540.0) % 360.0) - 180.0);   // 0~180
        if (diff < 15.0) {
            return target;
        }
        double ratio = Math.min(1.0, diff / 90.0);
        double fade = 1.0 - remain / 10.0;                               // 0 → 1
        return target * (1.0 - 0.45 * ratio * fade);
    }

    /**
     * 推进 dt 秒，返回新的姿态。
     */
    public Pose tick(double dt) {
        if (dt <= 0) {
            dt = 0.001;
        }
        if (dt > 1.0) {
            dt = 1.0;
        }
        elapsed += dt;
        justWrapped = false;

        if (!isValid() || finished) {
            Pose p = currentPose();
            p.finished = true;
            p.speed = 0;
            return p;
        }

        double wanted = paused ? 0.0 : cornerLimitedSpeed();
        if (curSpeed < wanted) {
            curSpeed = Math.min(wanted, curSpeed + accel * dt);
        } else if (curSpeed > wanted) {
            curSpeed = Math.max(wanted, curSpeed - accel * 2.0 * dt);
        }

        if (dwellLeft > 0) {
            dwellLeft -= dt;
            curSpeed = 0.0;
            Pose p = currentPose();
            p.moving = false;
            p.speed = 0;
            return p;
        }

        double move = curSpeed * dt;
        if (move > 0) {
            advanceBy(move);
        }

        Pose pose = currentPose();
        pose.speed = curSpeed;
        pose.moving = curSpeed > 0.05 && !finished;
        pose.finished = finished;
        pose.bearing = lastBearing;

        if (path.size() >= 2) {
            RoutePath.Point a = path.get(Math.min(seg, path.size() - 2));
            RoutePath.Point b = path.get(Math.min(seg + 1, path.size() - 1));
            double bea = RoutePath.bearingDeg(a, b);
            if (curSpeed > 0.1) {
                double diff = ((bea - lastBearing + 540.0) % 360.0) - 180.0;
                lastBearing = (lastBearing + diff * Math.min(1.0, dt * 6.0) + 360.0) % 360.0;
            }
            pose.bearing = lastBearing;
        }

        return pose;
    }

    /**
     * 按米数推进（步频驱动时由服务端调用），返回推进后的姿态。
     */
    public Pose advanceMeters(double meters) {
        elapsed += 0.1;
        justWrapped = false;
        if (!isValid() || finished || meters <= 0) {
            return currentPose();
        }
        advanceBy(meters);
        Pose pose = currentPose();
        pose.bearing = lastBearing;
        pose.speed = curSpeed > 0 ? curSpeed : targetSpeed;
        pose.moving = true;
        pose.finished = finished;
        return pose;
    }

    private void advanceBy(double meters) {
        double left = meters;
        int guard = 0;

        while (left > 1e-9 && !finished && guard++ < 100000) {
            if (seg >= segLen.length) {
                onReachEnd();
                if (finished) {
                    break;
                }
                continue;
            }

            double remain = segLen[seg] - distInSeg;
            if (remain > 1e-9 && left < remain) {
                distInSeg += left;
                lapTravelled += left;
                travelled += left;
                return;
            }

            double step = Math.max(remain, 0.0);
            left -= step;
            lapTravelled += step;
            travelled += step;

            RoutePath.Point waypoint = path.get(Math.min(seg + 1, path.size() - 1));
            seg++;
            distInSeg = 0.0;

            if (waypoint.stay > 0) {
                dwellLeft = waypoint.stay;
                return;
            }

            if (seg >= path.size() - 1) {
                onReachEnd();
            }
        }

        // 浮点兜底：刚好走完最后一段（且本帧尚未换圈）时立刻结算，避免多等一帧
        if (!finished && !justWrapped && segLen.length > 0
                && seg == segLen.length - 1 && segLen[seg] - distInSeg <= 1e-6) {
            onReachEnd();
        }
    }

    private void onReachEnd() {
        lap++;
        lapTravelled = 0.0;
        dwellLeft = 0.0;

        switch (mode) {
            case MODE_ONCE:
                finished = true;
                curSpeed = 0.0;
                break;
            case MODE_LOOP:
                seg = 0;
                distInSeg = 0.0;
                newLapOffset();
                break;
            case MODE_PINGPONG:
            default:
                Collections.reverse(path);
                seg = 0;
                distInSeg = 0.0;
                rebuild();
                newLapOffset();
                break;
        }
        justWrapped = true;
    }
}
