package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.joystick.JoyStick;
import com.zcshou.utils.RoutePath;
import com.zcshou.utils.RoutePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ServiceGo extends Service implements SensorEventListener {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    /** 供界面调用：收起/展开悬浮摇杆，避免与路线面板互相遮挡 */
    public static final String ACTION_JOYSTICK_SHOW = SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW;
    public static final String ACTION_JOYSTICK_HIDE = SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE;
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;

    /*============================== 循环路径（路线巡航） ==============================*/
    /** 路线位置进度广播 */
    public static final String ACTION_ROUTE_PROGRESS = "com.zcshou.service.ROUTE_PROGRESS";
    public static final String EXTRA_ROUTE_LNG = "route_lng";
    public static final String EXTRA_ROUTE_LAT = "route_lat";
    public static final String EXTRA_ROUTE_SPEED = "route_speed";
    public static final String EXTRA_ROUTE_BEARING = "route_bearing";
    public static final String EXTRA_ROUTE_ALT = "route_alt";
    public static final String EXTRA_ROUTE_PROGRESS = "route_progress";
    public static final String EXTRA_ROUTE_LAP = "route_lap";
    public static final String EXTRA_ROUTE_FINISHED = "route_finished";
    private RoutePlayer mRoutePlayer = null;
    private RoutePlayer.Pose mRoutePose = null;
    private long mLastTickMs = 0L;

    /*============================== 定位高级仿真 ==============================*/
    private final Random mRandom = new Random();
    private SharedPreferences mPrefs;
    private long mPrefsStamp = 0L;
    private boolean mAdvSim = true;          // 高级仿真总开关
    private double mJitterMeter = 0.6;       // 位置抖动幅度（米）
    private int mSatellites = 12;            // 模拟卫星数
    private double mAltJitter = 0.35;        // 海拔抖动（米）
    private double mBaseAlt = DEFAULT_ALT;   // 基准海拔
    private long mLastGpsTs = 0L;            // 保证时间戳单调递增

    /*============================== 步频驱动 / 上报节流 ==============================*/
    private SensorManager mSensorManager = null;
    private Sensor mStepSensor = null;
    private float mLastStepCount = -1.0f;
    private float mLastStepCountNow = -1.0f;
    private boolean mStepDrive = false;      // 用真实计步推进（步数与距离自洽）
    private double mStride = 1.05;           // 步幅（米/步）
    private int mNetInterval = 6;            // 网络 provider 每隔多少帧上报一次
    private long mTickCounter = 0L;

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        reloadAdvancedSettings();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        initGoLocation();

        initNotification();

        initJoyStick();

        initStepSensor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
            mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
            mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);
            mBaseAlt = mCurAlt;
        }

        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        mRoutePlayer = null;

        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        //准备intent
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                // 手动摇杆接管：自动退出路线巡航，避免两者互相覆盖
                if (mRoutePlayer != null) {
                    mRoutePlayer = null;
                    XLog.i("SERVICEGO: 手动摇杆接管，路线巡航停止");
                }
                mSpeed = speed;
                // 根据当前的经纬度和距离，计算下一个经纬度
                // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                // 具体见：http://wp.mlab.tw/?p=2200
                mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                mCurLat += disLat / 110.574;
                mCurBea = (float) angle;
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
                mBaseAlt = alt;
            }
        });
        mJoyStick.show();
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop) {
                        // 用真实经过时间推进，避免线程调度抖动造成速度失真
                        long now = SystemClock.elapsedRealtime();
                        double dt = mLastTickMs == 0L ? 0.1 : (now - mLastTickMs) / 1000.0;
                        mLastTickMs = now;
                        if (dt <= 0 || dt > 2.0) {
                            dt = 0.1;
                        }

                        mTickCounter++;

                        advanceRoute(dt);

                        setLocationNetwork();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };

        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    /**
     * 推进路线巡航，并把结果写回当前模拟位置。
     * <p>
     * 时间驱动：按真实耗时前进（默认）。
     * 步频驱动：按真实计步增量 × 步幅前进，左右脚连续性与步数校验都能对上。
     */
    private void advanceRoute(double dt) {
        if (mRoutePlayer == null) {
            return;
        }

        RoutePlayer.Pose pose;
        if (mStepDrive && mStepSensor != null) {
            float now = mLastStepCountNow;
            if (now < 0) {
                return;                 // 还没收到步数事件，位置先不动
            }
            if (mLastStepCount < 0) {
                mLastStepCount = now;   // 第一次只记录基线，绝不拿累计值当增量
                return;
            }
            float delta = now - mLastStepCount;
            mLastStepCount = now;
            if (delta < 0 || delta > 50) {
                delta = 0;              // 传感器重启/异常，忽略该帧
            }
            double meters = delta * mStride;
            if (meters < 0.01) {
                return;         // 本帧没走，位置不动
            }
            pose = mRoutePlayer.advanceMeters(meters);
            mSpeed = meters / Math.max(dt, 0.05);
        } else {
            pose = mRoutePlayer.tick(dt);
        }
        if (pose == null) {
            return;
        }
        mRoutePose = pose;

        mCurLng = pose.lng;
        mCurLat = pose.lat;
        mCurBea = (float) pose.bearing;
        mSpeed = pose.speed;
        mCurAlt = mBaseAlt + pose.alt;

        Intent intent = new Intent(ACTION_ROUTE_PROGRESS);
        intent.putExtra(EXTRA_ROUTE_LNG, pose.lng);
        intent.putExtra(EXTRA_ROUTE_LAT, pose.lat);
        intent.putExtra(EXTRA_ROUTE_SPEED, pose.speed);
        intent.putExtra(EXTRA_ROUTE_BEARING, pose.bearing);
        intent.putExtra(EXTRA_ROUTE_ALT, mCurAlt);
        intent.putExtra(EXTRA_ROUTE_PROGRESS, pose.progress);
        intent.putExtra(EXTRA_ROUTE_LAP, pose.lap);
        intent.putExtra(EXTRA_ROUTE_FINISHED, pose.finished);
        sendBroadcast(intent);

        if (pose.finished) {
            XLog.i("SERVICEGO: 路线巡航结束");
            mRoutePlayer = null;
        }
    }

    /*============================== 定位高级仿真 ==============================*/
    private void reloadAdvancedSettings() {
        try {
            mAdvSim = mPrefs.getBoolean("setting_adv_sim", true);

            String jitter = mPrefs.getString("setting_jitter", "0.6");
            mJitterMeter = Math.max(0.0, Math.min(50.0, Double.parseDouble(jitter)));

            String sat = mPrefs.getString("setting_satellites", "12");
            mSatellites = Math.max(4, Math.min(32, Integer.parseInt(sat)));

            String altJ = mPrefs.getString("setting_alt_jitter", "0.35");
            mAltJitter = Math.max(0.0, Math.min(30.0, Double.parseDouble(altJ)));

            mStepDrive = mPrefs.getBoolean("setting_step_drive", false);
            String stride = mPrefs.getString("setting_stride", "1.05");
            mStride = Math.max(0.3, Math.min(2.0, Double.parseDouble(stride)));
            String netIv = mPrefs.getString("setting_net_interval", "6");
            mNetInterval = Math.max(1, Math.min(60, Integer.parseInt(netIv)));
        } catch (Exception e) {
            XLog.e("SERVICEGO: 高级仿真参数解析失败，使用默认值");
            mAdvSim = true;
            mJitterMeter = 0.6;
            mSatellites = 12;
            mAltJitter = 0.35;
        }
        mPrefsStamp = System.currentTimeMillis();
    }

    private void maybeReloadAdvancedSettings() {
        if (System.currentTimeMillis() - mPrefsStamp > 2000L) {
            reloadAdvancedSettings();
        }
    }

    /** 高斯抖动，单位米，返回 [经度偏移, 纬度偏移] */
    private double[] jitterOffset() {
        if (!mAdvSim || mJitterMeter <= 0.0) {
            return new double[] {0.0, 0.0};
        }
        double sigma = mJitterMeter * 0.5;
        double dLngM = mRandom.nextGaussian() * sigma;
        double dLatM = mRandom.nextGaussian() * sigma;
        double dLng = dLngM / (111320.0 * Math.cos(Math.toRadians(Math.abs(mCurLat))) + 1e-9);
        double dLat = dLatM / 110574.0;
        return new double[] {dLng, dLat};
    }

    /** 写入 GNSS 细节 extras，尽量贴近真实 NMEA/GNSS 上报的内容 */
    private void fillGnssExtras(Location loc) {
        Bundle bundle = new Bundle();
        int sat = mSatellites;
        if (mAdvSim) {
            sat = Math.max(4, mSatellites + mRandom.nextInt(3) - 1);
        }
        bundle.putInt("satellites", sat);
        bundle.putInt("satelliteCount", sat);
        if (mAdvSim) {
            bundle.putDouble("averageCn0", 26.0 + mRandom.nextDouble() * 10.0);
            bundle.putDouble("maxCn0", 38.0 + mRandom.nextDouble() * 8.0);
            bundle.putDouble("hdop", 0.6 + mRandom.nextDouble() * 0.9);
            bundle.putDouble("vdop", 0.8 + mRandom.nextDouble() * 1.0);
            bundle.putInt("ttff", 1 + mRandom.nextInt(3));
        } else {
            bundle.putDouble("averageCn0", 30.0);
            bundle.putDouble("maxCn0", 42.0);
            bundle.putDouble("hdop", 0.8);
            bundle.putDouble("vdop", 1.0);
        }
        bundle.putInt("ageMilis", 0);
        loc.setExtras(bundle);
    }

    /** 逐次单调递增的时间戳，防止部分 App 判定定位被“篡改” */
    private long monotonicTime() {
        long now = System.currentTimeMillis();
        if (now <= mLastGpsTs) {
            now = mLastGpsTs + 20L;
        }
        mLastGpsTs = now;
        return now;
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        try {
            maybeReloadAdvancedSettings();

            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);

            double[] jitter = jitterOffset();
            double lng = mCurLng + jitter[0];
            double lat = mCurLat + jitter[1];

            float accuracy = 3.0f;
            if (mAdvSim) {
                accuracy = (float) (2.5 + mRandom.nextDouble() * 4.0);   // 2.5 ~ 6.5 米
            }

            loc.setAccuracy(accuracy);                    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt + (mAdvSim ? (mRandom.nextDouble() - 0.5) * 2.0 * mAltJitter : 0.0));
            loc.setVerticalAccuracyMeters(mAdvSim ? (float) (3.0 + mRandom.nextDouble() * 5.0) : 8.0f);
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setBearingAccuracyDegrees(mAdvSim ? (float) (1.0 + mRandom.nextDouble() * 3.0) : 5.0f);
            loc.setLatitude(lat);                          // 纬度（度）
            loc.setLongitude(lng);                         // 经度（度）
            loc.setTime(monotonicTime());                  // 本地时间（单调递增）
            loc.setSpeed((float) Math.max(0.0, mSpeed));
            loc.setSpeedAccuracyMetersPerSecond(mAdvSim ? (float) (0.05 + mRandom.nextDouble() * 0.2) : 0.5f);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            fillGnssExtras(loc);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        try {
            // 真实网络定位更新频率远低于 GPS，这里按间隔节流，
            // 避免同一帧双 provider 上报同一坐标这种明显的模拟特征
            if (mTickCounter % Math.max(1, mNetInterval) != 0) {
                return;
            }

            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);

            // 网络定位本身精度差，抖动幅度比 GPS 大
            double[] jitter = jitterOffset();
            double netJitter = mAdvSim ? 8.0 : 0.0;
            double dLng = jitter[0] + (mAdvSim ? (mRandom.nextGaussian() * netJitter) / (111320.0 * Math.cos(Math.toRadians(Math.abs(mCurLat))) + 1e-9) : 0.0);
            double dLat = jitter[1] + (mAdvSim ? (mRandom.nextGaussian() * netJitter) / 110574.0 : 0.0);

            float accuracy = 30.0f;
            if (mAdvSim) {
                accuracy = (float) (25.0 + mRandom.nextDouble() * 45.0);   // 25 ~ 70 米
            }

            loc.setAccuracy(accuracy);                  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                   // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setVerticalAccuracyMeters(mAdvSim ? (float) (10.0 + mRandom.nextDouble() * 15.0) : 20.0f);
            loc.setBearing(mCurBea);                    // 方向（度）
            loc.setBearingAccuracyDegrees(mAdvSim ? (float) (10.0 + mRandom.nextDouble() * 20.0) : 30.0f);
            loc.setLatitude(mCurLat + dLat);            // 纬度（度）
            loc.setLongitude(mCurLng + dLng);           // 经度（度）
            loc.setTime(monotonicTime());               // 本地时间（单调递增）
            loc.setSpeed((float) Math.max(0.0, mSpeed));
            loc.setSpeedAccuracyMetersPerSecond(mAdvSim ? (float) (0.5 + mRandom.nextDouble() * 1.5) : 2.0f);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    /*============================== 计步传感器 ==============================*/
    private void initStepSensor() {
        try {
            mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (mSensorManager != null) {
                mStepSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
                if (mStepSensor != null) {
                    mSensorManager.registerListener(this, mStepSensor, SensorManager.SENSOR_DELAY_UI);
                    XLog.i("SERVICEGO: 计步传感器已注册");
                }
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: 计步传感器不可用 - " + e.getMessage());
            mStepSensor = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event != null && event.sensor != null && event.sensor.getType() == Sensor.TYPE_STEP_COUNTER
                && event.values != null && event.values.length > 0) {
            mLastStepCountNow = event.values[0];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 无需处理
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }
            }
        }
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            mRoutePlayer = null;
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mBaseAlt = alt;
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }

        /**
         * 开始路线巡航。
         *
         * @param wgsPoints     WGS84 路径点，每个元素为 {lng, lat, alt, stay}（alt/stay 可省略）
         * @param mode          RoutePlayer.MODE_LOOP / MODE_PINGPONG / MODE_ONCE
         * @param speed         巡航速度 m/s
         * @param accel         加速度 m/s^2
         * @param terrainAmp    海拔起伏幅度（米）
         * @param terrainPeriod 海拔起伏周期（米）
         * @return 是否成功启动
         */
        public boolean startRoute(List<double[]> wgsPoints, int mode, double speed,
                                  double accel, double terrainAmp, double terrainPeriod) {
            return startRoute(wgsPoints, mode, speed, accel, terrainAmp, terrainPeriod, 0.0, 0.0);
        }

        /**
         * @param lapOffsetMeter 每圈轨迹微扰幅度（米），用于避免每圈轨迹完全重复
         * @param driftMeter     低频漂移幅度（米）
         */
        public boolean startRoute(List<double[]> wgsPoints, int mode, double speed,
                                  double accel, double terrainAmp, double terrainPeriod,
                                  double lapOffsetMeter, double driftMeter) {
            List<RoutePath.Point> points = new ArrayList<>();
            if (wgsPoints != null) {
                for (double[] p : wgsPoints) {
                    if (p == null || p.length < 2) {
                        continue;
                    }
                    double alt = p.length > 2 ? p[2] : mBaseAlt;
                    double stay = p.length > 3 ? p[3] : 0.0;
                    points.add(new RoutePath.Point(p[0], p[1], alt - mBaseAlt, stay));
                }
            }

            RoutePlayer player = new RoutePlayer(points, mode, speed, accel, terrainAmp,
                    terrainPeriod, lapOffsetMeter, driftMeter);
            if (!player.isValid()) {
                XLog.e("SERVICEGO: 路线点不足，无法启动巡航");
                return false;
            }

            mRoutePlayer = player;
            mLastTickMs = 0L;           // 重置节拍，避免用上一轮的时间差推进
            mTickCounter = 0L;
            mLastStepCount = -1.0f;     // 步频重新取基线
            mJoyStick.hide();           // 巡航期间收起摇杆，避免两个控制面互相抢位置

            RoutePlayer.Pose pose = player.currentPose();
            if (pose != null) {
                mCurLng = pose.lng;
                mCurLat = pose.lat;
            }
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            XLog.i("SERVICEGO: 路线巡航启动，点数=" + points.size() + " 长度=" + player.getLength());
            return true;
        }

        public void pauseRoute(boolean pause) {
            if (mRoutePlayer != null) {
                mRoutePlayer.setPaused(pause);
            }
        }

        public void setRouteSpeed(double speed) {
            if (mRoutePlayer != null) {
                mRoutePlayer.setTargetSpeed(speed);
            }
        }

        public boolean isRouteRunning() {
            return mRoutePlayer != null;
        }

        public double getRouteProgress() {
            return mRoutePose == null ? 0.0 : mRoutePose.progress;
        }

        public void stopRoute() {
            mRoutePlayer = null;
            mLastStepCount = -1.0f;
            mJoyStick.show();           // 巡航结束把摇杆还回来
            // 顺带把速度归零，避免停下后仍显示运动速度
            mSpeed = 0;
        }
    }
}
