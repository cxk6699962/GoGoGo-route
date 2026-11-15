package com.zcshou.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 路线（折线）数据模型。
 * <p>
 * 该类型只做坐标与几何计算，不依赖 Android 框架，便于单元测试。
 * 坐标统一使用 WGS84（模拟定位 provider 需要的坐标系）。
 */
public class RoutePath {

    /** 单个路径点 */
    public static class Point {
        public double lng;
        public double lat;
        public double alt;
        /** 到达该点后的停留时间（秒），0 表示不停留 */
        public double stay;

        public Point(double lng, double lat, double alt, double stay) {
            this.lng = lng;
            this.lat = lat;
            this.alt = alt;
            this.stay = stay;
        }

        public Point(double lng, double lat) {
            this(lng, lat, 0.0, 0.0);
        }

        public Point copy() {
            return new Point(lng, lat, alt, stay);
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%.7f,%.7f", lng, lat);
        }
    }

    /** 纬度方向每度对应的米数 */
    public static final double METER_PER_DEG_LAT = 110574.0;

    /** 计算经度方向每度对应的米数（随纬度变化） */
    public static double meterPerDegLng(double lat) {
        double c = Math.cos(Math.toRadians(lat));
        if (Math.abs(c) < 1e-9) {
            c = 1e-9;
        }
        return 111320.0 * c;
    }

    /** 两点的近似平面距离（米），几十公里内误差可忽略 */
    public static double distanceMeter(Point a, Point b) {
        if (a == null || b == null) {
            return 0.0;
        }
        double midLat = (a.lat + b.lat) / 2.0;
        double dy = (b.lat - a.lat) * METER_PER_DEG_LAT;
        double dx = (b.lng - a.lng) * meterPerDegLng(midLat);
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 起点到终点的方位角（0~360，正北为 0，顺时针） */
    public static double bearingDeg(Point a, Point b) {
        if (a == null || b == null) {
            return 0.0;
        }
        double dy = (b.lat - a.lat) * METER_PER_DEG_LAT;
        double dx = (b.lng - a.lng) * meterPerDegLng((a.lat + b.lat) / 2.0);
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) {
            return 0.0;
        }
        double deg = Math.toDegrees(Math.atan2(dx, dy));
        deg %= 360.0;
        if (deg < 0) {
            deg += 360.0;
        }
        return deg;
    }

    /** 按米数在两点之间做线性插值 */
    public static Point interpolate(Point a, Point b, double ratio) {
        if (a == null && b == null) {
            return new Point(0, 0);
        }
        if (a == null) {
            return b.copy();
        }
        if (b == null) {
            return a.copy();
        }
        double r = Math.max(0.0, Math.min(1.0, ratio));
        return new Point(a.lng + (b.lng - a.lng) * r,
                a.lat + (b.lat - a.lat) * r,
                a.alt + (b.alt - a.alt) * r,
                b.stay);
    }

    /**
     * 把路径序列化为可在 SharedPreferences 中保存的字符串。
     * 格式：lng,lat,alt,stay;lng,lat,alt,stay
     */
    public static String encode(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Point p : points) {
            if (p == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(String.format(Locale.US, "%.7f,%.7f,%.2f,%.2f", p.lng, p.lat, p.alt, p.stay));
        }
        return sb.toString();
    }

    /** 反序列化，格式错误时返回空列表，绝不抛异常 */
    public static List<Point> decode(String text) {
        List<Point> points = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return points;
        }
        String[] items = text.split(";");
        for (String item : items) {
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            String[] f = item.split(",");
            if (f.length < 2) {
                continue;
            }
            try {
                double lng = Double.parseDouble(f[0]);
                double lat = Double.parseDouble(f[1]);
                double alt = f.length > 2 ? Double.parseDouble(f[2]) : 0.0;
                double stay = f.length > 3 ? Double.parseDouble(f[3]) : 0.0;
                if (Double.isNaN(lng) || Double.isNaN(lat)) {
                    continue;
                }
                points.add(new Point(lng, lat, alt, stay));
            } catch (NumberFormatException ignored) {
                // 跳过坏点
            }
        }
        return points;
    }

    /** 路径总长度（米） */
    public static double totalLength(List<Point> points) {
        if (points == null || points.size() < 2) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 1; i < points.size(); i++) {
            sum += distanceMeter(points.get(i - 1), points.get(i));
        }
        return sum;
    }
}
