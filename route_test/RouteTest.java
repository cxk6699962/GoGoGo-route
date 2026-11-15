import com.zcshou.utils.RoutePath;
import com.zcshou.utils.RoutePlayer;

import java.util.ArrayList;
import java.util.List;

/** 纯 Java 逻辑自测：循环 / 往返 / 单次 / 速度收敛 / 停留 / 长度 */
public class RouteTest {

    static List<RoutePath.Point> square() {
        // 约 100m x 100m 的方形，最后一点不闭合（由 LOOP 模式自动补闭合段）
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

    public static void main(String[] args) {
        // 1. 长度
        List<RoutePath.Point> pts = square();
        double len = RoutePath.totalLength(pts);
        System.out.printf("open length = %.2f m (expect ~300)%n", len);

        // 2. 循环模式：闭合后一圈 400m，跑 500s@1m/s 应回到 0 号段且走完 1 圈
        RoutePlayer loop = new RoutePlayer(pts, RoutePlayer.MODE_LOOP, 1.0, 5.0, 0.0, 500.0);
        System.out.printf("loop lap length = %.2f m%n", loop.getLength());
        RoutePlayer.Pose pose = null;
        for (int i = 0; i < 4100; i++) {
            pose = loop.tick(0.1);
        }
        System.out.printf("loop after 410s: lap=%d progress=%.3f lng=%.7f lat=%.7f speed=%.2f%n",
                pose.lap, pose.progress, pose.lng, pose.lat, pose.speed);
        if (pose.lap < 1) {
            System.out.println("FAIL: 循环模式未产生闭环");
            System.exit(1);
        }

        // 3. 位置应回到起点附近（闭合衔接）
        double d = RoutePath.distanceMeter(new RoutePath.Point(pose.lng, pose.lat), pts.get(0));
        System.out.printf("loop position distance to start = %.2f m (转弯降速后仍在第一段附近)%n", d);
        if (d > 15.0) {
            System.out.println("FAIL: 循环衔接位置偏差过大");
            System.exit(2);
        }

        // 4. 往返模式：单程 300m，跑 400s 应处于返回途中
        RoutePlayer ping = new RoutePlayer(pts, RoutePlayer.MODE_PINGPONG, 1.0, 5.0, 0.0, 500.0);
        for (int i = 0; i < 4000; i++) {
            pose = ping.tick(0.1);
        }
        System.out.printf("pingpong after 400s: lap=%d progress=%.3f%n", pose.lap, pose.progress);
        if (pose.lap < 1) {
            System.out.println("FAIL: 往返模式未折返");
            System.exit(3);
        }

        // 5. 单次模式：跑够时间应 finished
        RoutePlayer once = new RoutePlayer(pts, RoutePlayer.MODE_ONCE, 1.0, 5.0, 0.0, 500.0);
        for (int i = 0; i < 4000; i++) {
            pose = once.tick(0.1);
        }
        System.out.printf("once finished=%s%n", once.isFinished());
        if (!once.isFinished()) {
            System.out.println("FAIL: 单次模式未结束");
            System.exit(4);
        }

        // 6. 停留：第 2 个点停留 30s
        List<RoutePath.Point> dwellPts = square();
        dwellPts.get(1).stay = 30.0;
        RoutePlayer dwell = new RoutePlayer(dwellPts, RoutePlayer.MODE_ONCE, 2.0, 5.0, 0.0, 500.0);
        boolean sawDwell = false;
        for (int i = 0; i < 3000; i++) {
            pose = dwell.tick(0.1);
            if (pose.speed < 0.001 && !dwell.isFinished()) {
                sawDwell = true;
            }
        }
        System.out.printf("dwell observed=%s finished=%s%n", sawDwell, dwell.isFinished());
        if (!sawDwell) {
            System.out.println("FAIL: 停留未生效");
            System.exit(5);
        }

        // 7. 速度收敛：3s 内应从 0 加速到接近目标（accel=1.2 → 3.6 m/s）
        RoutePlayer ramp = new RoutePlayer(pts, RoutePlayer.MODE_ONCE, 10.0, 1.2, 0.0, 500.0);
        for (int i = 0; i < 30; i++) {
            pose = ramp.tick(0.1);
        }
        System.out.printf("speed after 3s = %.2f m/s (expect ~3.6)%n", pose.speed);
        if (pose.speed < 3.0 || pose.speed > 4.2) {
            System.out.println("FAIL: 加速度曲线异常");
            System.exit(6);
        }

        // 8. 地形起伏
        RoutePlayer terrain = new RoutePlayer(pts, RoutePlayer.MODE_LOOP, 5.0, 3.0, 2.0, 100.0);
        double minAlt = 0, maxAlt = 0;
        for (int i = 0; i < 2000; i++) {
            pose = terrain.tick(0.1);
            minAlt = Math.min(minAlt, pose.alt);
            maxAlt = Math.max(maxAlt, pose.alt);
        }
        System.out.printf("terrain alt range = [%.2f, %.2f] (expect within +-2)%n", minAlt, maxAlt);

        // 9. 编码/解码往返
        String enc = RoutePath.encode(pts);
        List<RoutePath.Point> dec = RoutePath.decode(enc);
        System.out.printf("encode/decode ok, points=%d%n", dec.size());
        if (dec.size() != pts.size()) {
            System.out.println("FAIL: 序列化往返失败");
            System.exit(7);
        }

        System.out.println("ALL_TESTS_PASSED");
    }
}
