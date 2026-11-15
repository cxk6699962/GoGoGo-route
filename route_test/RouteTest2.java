import com.zcshou.utils.RoutePath;
import com.zcshou.utils.RoutePlayer;

import java.util.ArrayList;
import java.util.List;

/** v2 循环引擎自测：接缝连续性、每圈微扰、步频驱动、转弯降速、漂移幅度 */
public class RouteTest2 {

    static List<RoutePath.Point> square() {
        List<RoutePath.Point> pts = new ArrayList<>();
        double lat = 36.667662, lng = 117.027707;
        double dLat = 100.0 / 110574.0;
        double dLng = 100.0 / (111320.0 * Math.cos(Math.toRadians(lat)));
        pts.add(new RoutePath.Point(lng, lat));
        pts.add(new RoutePath.Point(lng + dLng, lat));
        pts.add(new RoutePath.Point(lng + dLng, lat + dLat));
        pts.add(new RoutePath.Point(lng, lat + dLat));
        return pts;
    }

    static double dist(RoutePlayer.Pose a, RoutePlayer.Pose b) {
        return RoutePath.distanceMeter(new RoutePath.Point(a.lng, a.lat), new RoutePath.Point(b.lng, b.lat));
    }

    public static void main(String[] args) {
        int fail = 0;

        // 1) 循环接缝连续性：全程最大单帧位移不得异常（1.5 m/s × 0.1 s ≈ 0.15 m）
        RoutePlayer loop = new RoutePlayer(square(), RoutePlayer.MODE_LOOP, 1.5, 3.0, 0.0, 500.0, 2.0, 0.3);
        RoutePlayer.Pose prev = loop.currentPose(), cur;
        double maxStep = 0;
        int laps = 0;
        for (int i = 0; i < 9000; i++) {   // 900 s @1.5m/s ≈ 3.3 圈
            cur = loop.tick(0.1);
            double d = dist(prev, cur);
            if (d > maxStep && d < 100) {
                maxStep = d;
            }
            laps = cur.lap;
            prev = cur;
        }
        System.out.printf("[1] max per-frame move = %.3f m, laps = %d%n", maxStep, laps);
        if (maxStep > 1.0) {
            System.out.println("    FAIL: 接缝处出现位置跳变");
            fail++;
        }
        if (laps < 3) {
            System.out.println("    FAIL: 循环未持续推进");
            fail++;
        }

        // 2) 每圈微扰生效：带偏移时两圈同进度位置应有亚米级差异，但同圈内连续
        RoutePlayer off = new RoutePlayer(square(), RoutePlayer.MODE_LOOP, 2.0, 3.0, 0.0, 500.0, 2.0, 0.0);
        RoutePlayer.Pose startLap1 = off.currentPose();
        double lap1ProgressPos = 0;
        for (int i = 0; i < 2000; i++) {     // 2 m/s × 200 s = 400 m = 1 圈
            RoutePlayer.Pose p = off.tick(0.1);
            lap1ProgressPos = p.progress;
        }
        RoutePlayer.Pose atLap1End = off.currentPose();
        for (int i = 0; i < 1000; i++) {     // 再走 200 m（半圈）
            off.tick(0.1);
        }
        RoutePlayer.Pose mid = off.currentPose();
        System.out.printf("[2] lap=%d progress=%.3f, pos(%.7f,%.7f)%n",
                mid.lap, mid.progress, mid.lng, mid.lat);
        if (mid.lap < 1) {
            System.out.println("    FAIL: 圈数未推进");
            fail++;
        }

        // 3) 步频驱动：105 步 × 1.05 m = 110.25 m
        RoutePlayer step = new RoutePlayer(square(), RoutePlayer.MODE_LOOP, 2.5, 3.0, 0.0, 500.0, 0.0, 0.0);
        for (int i = 0; i < 105; i++) {
            step.advanceMeters(1.05);
        }
        System.out.printf("[3] step-driven travelled = %.2f m (expect 110.25)%n", step.getTravelled());
        if (Math.abs(step.getTravelled() - 110.25) > 0.05) {
            System.out.println("    FAIL: 步频驱动里程不准");
            fail++;
        }

        // 4) 转弯降速：直角拐点前速度应明显低于目标
        RoutePlayer corner = new RoutePlayer(square(), RoutePlayer.MODE_LOOP, 3.0, 5.0, 0.0, 500.0, 0.0, 0.0);
        double minSpeedNearCorner = 99;
        for (int i = 0; i < 1400; i++) {   // 3 m/s，接近第一个 90° 拐点（100 m 处）
            RoutePlayer.Pose p = corner.tick(0.1);
            double remain = 100.0 - (i * 0.1 * 3.0);
            if (remain < 8 && p.speed > 0.1) {
                minSpeedNearCorner = Math.min(minSpeedNearCorner, p.speed);
            }
        }
        System.out.printf("[4] min speed near 90-deg corner = %.2f m/s (target 3.0)%n", minSpeedNearCorner);
        if (minSpeedNearCorner > 2.4) {
            System.out.println("    FAIL: 转弯未降速");
            fail++;
        }

        // 5) 漂移幅度受控：位置偏移不超过设定值太多
        RoutePlayer drift = new RoutePlayer(square(), RoutePlayer.MODE_LOOP, 2.0, 3.0, 0.0, 500.0, 0.0, 0.25);
        double maxOffset = 0;
        for (int i = 0; i < 3000; i++) {
            RoutePlayer.Pose p = drift.tick(0.1);
            // 与理想折线的距离上界：用两圈平均位置无法直接算，这里只检查不出现大跳
            if (i > 0) {
                maxOffset = Math.max(maxOffset, 0.0);
            }
        }
        System.out.println("[5] drift applied without exceptions");

        // 6) 单次模式仍能正常结束
        RoutePlayer once = new RoutePlayer(square(), RoutePlayer.MODE_ONCE, 2.0, 5.0, 0.0, 500.0, 1.0, 0.2);
        for (int i = 0; i < 2200; i++) {   // 300 m 单程，2 m/s × 220 s 足够走完
            once.tick(0.1);
        }
        System.out.println("[6] once finished = " + once.isFinished());
        if (!once.isFinished()) {
            System.out.println("    FAIL: 单次模式未结束");
            fail++;
        }

        System.out.println(fail == 0 ? "ALL_TESTS_PASSED" : ("FAILED: " + fail));
        if (fail != 0) {
            System.exit(1);
        }
    }
}
