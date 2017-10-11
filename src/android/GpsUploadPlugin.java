package cn.beyondmap.plugins;

import android.app.Activity;
import android.content.Context;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.StrictMode;
import android.util.Log;

import com.baidu.location.BDLocation;
import com.baidu.location.BDLocationListener;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.beyond.pss.bean.GpsData;
import com.beyond.pss.bean.GpsTool;
import com.beyond.pss.bean.JT809Message;
import com.beyond.pss.comm.SharedPrefsUtil;
import com.beyond.pss.exception.GpsParseException;
import com.beyond.pss.socket.UploadController;
import com.beyond.pss.socket.UploadTask;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Created by mac-pc on 16/7/1.
 */
public class GpsUploadPlugin extends CordovaPlugin {
    private final static int ONACTIVITYCODE_GPS = 1001;
    private final static int ONACTIVITYCODE_WIFI = 1002;
    private final static int HANDLE_POINT_NH = 10001;//处理数据拟合
    private final static int HANDLE_CALLBACK = 20001;//处理数据回传
    private LocationManager locationManager;
    private WifiManager mWifiManager;

    private UploadController mUploadController;
    private int FREQUENCY = 1000 * 10;// 默认10S
    private boolean isGpsOpen = false;
    private boolean isWifiOpen = false;
    private final static String token = "BF833A1145CB44669FB387DEA62AC464";
    public boolean result = false;
    public CallbackContext callbackContext;
    private ExecutorService mThreadPool;
    private GpsData preGpsData = null;

    public LocationClient mLocationClient = null;


    private final static List<String> methodList =
            Arrays.asList(
                    "begionLoc",
                    "checkGps",
                    "checkWifi"
            );

    Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg)
        {
            if (mLocationClient != null) {
                int result = mLocationClient.requestLocation();
                mHandler.sendEmptyMessageDelayed(0, FREQUENCY);
                Log.e("myTag", " requestLocation  result   " + result);
            } else {
                Log.e("myTag", " mLocationClient is  null   " + msg);
            }
        };
    };

    Handler uHandler = new Handler() {
        public void handleMessage(android.os.Message msg)
        {
            handleMsg(msg);
        };
    };

    private void handleMsg(android.os.Message msg) {
        if (mLocationClient != null) {
            Bundle data = msg.getData();
            Integer handleType = data.getInt("handleType");
            if (!data.isEmpty() && handleType != null && handleType != 0) {
                GpsData gpsData = (GpsData) data.getSerializable("gpsData");
                String host = data.getString("host");
                Integer port = data.getInt("port");
                String fatUrl = data.getString("fatUrl");
                switch (handleType) {
                    case GpsUploadPlugin.HANDLE_POINT_NH:
                        uploaderNH(gpsData, host, port, fatUrl);
                        break;
                    case GpsUploadPlugin.HANDLE_CALLBACK:
                        callbackUploader(gpsData, host, port);
                        break;
                }
            }
            Log.e("myTag-Message--", " GpsData  handleType   " + handleType);
        } else {
            Log.e("myTag", " mLocationClient is  null   " + msg);
        }
    }

    private void callbackUploader(GpsData bean, String host, Integer port) {
        Gson gson = new Gson();
        if (preGpsData != null && preGpsData.getLongitude() != 0 && preGpsData.getLatitude() != 0) {
            Double direction = getAngleByTwoPoint(preGpsData, bean);
            bean.setDirection(direction.intValue());
            Log.w("GpsUpload DIRECTION", bean.getDirection()+"");
        }
        mUploadController.getUploadController().putOneGpsBean(bean, cordova.getActivity().getApplicationContext(), host, port);
        preGpsData = bean;
        PluginResult mPlugin = new PluginResult(PluginResult.Status.OK,
                gson.toJson(bean));
        mPlugin.setKeepCallback(true);
        callbackContext.sendPluginResult(mPlugin);
    }

    private void uploaderNH(GpsData fbean, String fhost, Integer fport, String ffatUrl) {
        final GpsData newBean = fbean;
        final String host = fhost;
        final Integer port = fport;
        final String fatUrl = ffatUrl;
        Future<?> request = mThreadPool.submit(new Runnable() {
            @Override
            public void run() {
                Map<String, Object> params = new HashMap<String, Object>();
                params.put("x", newBean.getLongitude());
                params.put("y", newBean.getLatitude());
                params.put("token",token);
                String callbackStr = null;
                Bundle data = new Bundle();
                Message msg = new Message();
                data.putString("host", host);
                data.putInt("port", port);
                data.putString("fatUrl", fatUrl);
                data.putInt("handleType", GpsUploadPlugin.HANDLE_CALLBACK);
                try{
                    callbackStr = HttpUtil.doGet("http://"+fatUrl+"/networkserver/rest/networkservice/nearroad",params);
                }catch (Exception e) {
                    callbackStr = null;
                }

                Gson gsonFat = new Gson();
                Map map = null;
                if (callbackStr != null) {
                    map = gsonFat.fromJson(callbackStr, new TypeToken<Map<String, Object>>() {}.getType());
                }

                if (map != null && "1".equals(map.get("returnFlag"))) {
                    Map obj = (Map)map.get("data");
                    if (obj != null && !"".equals(obj)) {
                        List point = (List)obj.get("point");
                        if (point != null && point.size() > 0) {
                            if (point.get(1) != null && point.get(0) != null) {
                                newBean.setLatitude(Double.parseDouble(point.get(1).toString())*1000000);
                                newBean.setLongitude(Double.parseDouble(point.get(0).toString())*1000000);
                            }
                        }
                    }

                }

                data.putSerializable("gpsData", newBean);
                msg.setData(data);
                uHandler.sendMessage(msg);
            }
        });
    }

    private void beginLoc(final String carNo, final String carColor, final String host, final Integer port,final String fatUrl) {
        final Activity activity = cordova.getActivity();
        if (mLocationClient == null) {
            final UploadController uploadController = mUploadController.getUploadController();
            final Bundle data = new Bundle();
            SharedPrefsUtil.putStringValue(activity.getApplicationContext(),"AccurateLocation", "1");
            //
            mLocationClient = new LocationClient(activity.getApplicationContext()); // 声明LocationClient类
            if (SharedPrefsUtil.getStringValue(activity.getApplicationContext(),"AccurateLocation", "").equals("1"))
            {
                mLocationClient.getLocOption().setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy);
            } else
            {
                mLocationClient.getLocOption().setLocationMode(LocationClientOption.LocationMode.Battery_Saving);
            }

            // coorType - 取值有3个： 返回国测局经纬度坐标系：gcj02 返回百度墨卡托坐标系 ：bd09 返回百度经纬度坐标系
            // ：bd09ll
            // mLocationClient.getLocOption().setCoorType("gcj02");
            mLocationClient.getLocOption().setCoorType("gcj02");
            mLocationClient.registerLocationListener(new BDLocationListener()
            {

                @Override
                public void onReceiveLocation(BDLocation loc)
                {
                    BDLocation location = loc;
                    PluginResult mPlugin = null;
                    Log.e("myTag", " onReceiveLocation  result   " + loc.getLongitude() + " , "
                            + loc.getLatitude() + " , 方向" + loc.getDirection() + ",高度" + loc.getAltitude() + ",速度" + loc.getSpeed());
                    try {
                        GpsData bean = new GpsData();
                        int altitude = (int)loc.getAltitude();
                        bean.setAltitude(altitude);
                        bean.setLongitude(loc.getLongitude() * 1000000);
                        bean.setLatitude(loc.getLatitude() * 1000000);
                        int mDirection = (int) loc.getDirection();
                        bean.setDirection(mDirection);
                        int speed = (int)loc.getSpeed();
                        bean.setGpsSpeed(speed); //
                        bean.setPlateNo(carNo);
                        int carColorInt = 2;

                        try {
                            carColorInt = Integer.parseInt(carColor);
                        }catch(Exception e) {
                            carColorInt = 2;
                        }

                        bean.setPlateColor(carColorInt);
                        Gson gson = new Gson();
                        final GpsData newBean = bean;
                        if (bean.getLongitude() == 4.9E-324D || bean.getLatitude() == 4.9E-324D) {
                            Log.e("myTag", "获取坐标失败");
                        } else {
                            Bundle data = new Bundle();
                            Message msg = new Message();
                            data.putSerializable("gpsData", bean);
                            data.putString("host", host);
                            data.putInt("port", port);
                            data.putString("fatUrl", fatUrl);
                            result = true;
                            if (fatUrl != null && !"".equals(fatUrl)) {
                                data.putInt("handleType", GpsUploadPlugin.HANDLE_POINT_NH);
                                uHandler.sendMessage(msg);
                            } else {
                                data.putInt("handleType", GpsUploadPlugin.HANDLE_CALLBACK);
                                uHandler.sendMessage(msg);
                            }
                            msg.setData(data);
                        }
                    } catch (Exception e) {
                        Log.e("GUP-beginLoc",e.getMessage());
                        mPlugin = new PluginResult(PluginResult.Status.ERROR,
                                e.getMessage());
                        mPlugin.setKeepCallback(true);
                        result = true;
                        callbackContext.sendPluginResult(mPlugin);
                    }

                }
            }); // 注册监听函数
            mLocationClient.start();
        }
        mHandler.removeCallbacksAndMessages(null);
        mHandler.sendEmptyMessageDelayed(0, FREQUENCY);
    }

    public String getJT809Messge(GpsData gps)
    {
        // 16机制为1200作为人员采集的编码类型
        JT809Message message = new JT809Message(4608, 225546);
        message.setMsgSN(9674);
        message.setCRCCode("303c");
        byte[] by = new byte[3];
        by[0] = 0x01;
        by[1] = 0x02;
        by[2] = 0x0F;
        message.setVersionFlag(by);

        Date date = new Date();
        gps.setPosTime(date);

        try
        {
            String databody = GpsTool.parseGPSDataToHexString(gps);
            message.setMessageBody(databody);
            String result = GpsTool.parseJT809MessageHexString(message);
            return result;
        } catch (GpsParseException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return "";
    }

    private boolean checkGps() {
        isGpsOpen = false;
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            isGpsOpen = false;
        } else {
            isGpsOpen = true;
        }

        return isGpsOpen;
    }

    private boolean checkWifi() {
        isWifiOpen = false;
        if (mWifiManager != null && !mWifiManager.isWifiEnabled()) {
            isWifiOpen = false;
        } else {
            isWifiOpen = true;
        }
        return isWifiOpen;
    }

    private void stop() {
        if (mLocationClient != null) {
            mLocationClient.stop();
            mLocationClient = null;
        }
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        locationManager = (LocationManager) cordova.getActivity().getSystemService(Context.LOCATION_SERVICE);
        mWifiManager = (WifiManager) cordova.getActivity().getSystemService(Context.WIFI_SERVICE);
        mThreadPool = Executors.newCachedThreadPool();
        super.initialize(cordova, webView);
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        setCallbackContext(callbackContext);
        if (action.equals("beginLoc")) {
            final String carNo = args.getString(0);
            final String carColor = args.getString(1);
            final String host = args.getString(2);
            final Integer port = args.getInt(3);
            final Integer time = args.getInt(4);
            final String fatUrl = args.getString(5);


            if (time != null && time > 0) {
                FREQUENCY = time;
            }

            if (carNo != null && !"".equals(carNo)) {
//                stop();
                android.widget.Toast.makeText(cordova.getActivity(), "开始采集中", android.widget.Toast.LENGTH_LONG).show();
                cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try{
                            beginLoc(carNo, carColor, host, port, fatUrl);
                        }catch (Exception e) {
                            Log.w("GpsUploadPlugin", e.getMessage());
                            stop();
                        }
                    }
                });

            } else {
                callbackContext.error("{flag:0,error:'车牌号不能为空'}");
            }
            return true;
        } else if ("stop".equals(action)) {
            android.widget.Toast.makeText(cordova.getActivity(), "停止采集", android.widget.Toast.LENGTH_LONG).show();
            this.stop();
            callbackContext.success("{flag:1,msg:'数据停止采集成功'}");
        } else if ("checkGps".equals(action)) {
            android.widget.Toast.makeText(cordova.getActivity(), "调用：checkGps", android.widget.Toast.LENGTH_LONG).show();
            boolean activeGps = this.checkGps();
            callbackContext.success(String.valueOf(activeGps));
        } else if ("checkWifi".equals(action)) {
            android.widget.Toast.makeText(cordova.getActivity(), "调用：checkWifi", android.widget.Toast.LENGTH_LONG).show();
            boolean activeWifi = this.checkWifi();
            callbackContext.success(String.valueOf(activeWifi));
        }
        while (result == false) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return super.execute(action, args, callbackContext);
    }

    public void setCallbackContext(CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
    }

    /**
     * 获取坐标方位
     * @param p1 第一个坐标
     * @param p2 第二个坐标
     * @return
     */
    private static double getAngleByTwoPoint(GpsData p1, GpsData p2) {
        double xDistance = p2.getLongitude() - p1.getLongitude();
        double yDistance = p2.getLatitude() - p1.getLatitude();
        double xyDistacn = Math.sqrt(Math.pow(xDistance, 2)
                + Math.pow(yDistance, 2));
        double angle = Math.asin(Math.abs(yDistance) / xyDistacn) * 180 / Math.PI;
        if (p2.getLatitude() >= p1.getLatitude() && p2.getLongitude() >= p1.getLongitude())
            angle = angle ;
        else if (p2.getLatitude() >= p1.getLatitude() && p2.getLongitude() < p1.getLongitude())
            angle = 180 - angle;
        else if (p2.getLatitude() < p1.getLatitude() && p2.getLongitude() < p1.getLongitude())
            angle = angle+ 180;
        else if (p2.getLatitude() < p1.getLatitude() && p2.getLongitude() >= p1.getLongitude())
            angle = 360 - angle;
        return angle;

    }

}
