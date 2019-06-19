package com.yxc.location.map.location;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.amap.api.maps.AMap.OnMapLoadedListener;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.UiSettings;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.maps.model.animation.Animation;
import com.amap.api.maps.model.animation.ScaleAnimation;
import com.yxc.location.R;
import com.yxc.location.map.location.database.LocationDBHelper;
import com.yxc.location.map.location.util.LocationComputeUtil;
import com.yxc.location.map.location.util.LocationConstants;
import com.yxc.location.map.model.Record;
import com.yxc.location.map.model.RecordLocation;
import com.yxc.location.util.DisplayUtil;
import com.yxc.location.util.TimeDateUtil;
import com.yxc.location.view.LocationMarker;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 实现轨迹回放、纠偏后轨迹回放
 */
public class RecordShowActivity extends Activity implements OnMapLoadedListener, AMapLocationListener {

    private final static int AMAP_LOADED = 2;

    private MapView mMapView;
    private AMap mAMap;
    private int mRecordItemId;
    private int recordType;
    private List<LatLng> mOriginLatLngList;
    private ExecutorService mThreadPool;

    private ImageView imgGps;
    private ImageView imgLocation;
    private ImageView imgMarker;

    private boolean enableMarker = true;

    private Marker locationMarker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record_item);
        initView(savedInstanceState);

        Intent recordIntent = getIntent();
        int threadPoolSize = Runtime.getRuntime().availableProcessors() * 2 + 3;
        mThreadPool = Executors.newFixedThreadPool(threadPoolSize);
        if (recordIntent != null) {
            mRecordItemId = recordIntent.getIntExtra(RecordActivity.RECORD_ID, -1);
            recordType = recordIntent.getIntExtra(LocationConstants.KEY_RECORD_TYPE, -1);
        }
        initMap();
        initLocation();
    }

    private void initView(Bundle savedInstanceState) {
        mMapView = findViewById(R.id.map);
        mMapView.onCreate(savedInstanceState);// 此方法必须重写
        imgGps = findViewById(R.id.img_gps);
        imgLocation = findViewById(R.id.img_location);
        imgMarker = findViewById(R.id.img_marker);

        imgLocation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mLocationClient != null) {
                    mLocationClient.startLocation();
                }
            }
        });
    }

    UiSettings uiSettings;
    private void initMap() {
        if (mAMap == null) {
            mAMap = mMapView.getMap();
            mAMap.setOnMapLoadedListener(this);
            uiSettings = mAMap.getUiSettings();
            uiSettings.setZoomControlsEnabled(false);
            uiSettings.setLogoBottomMargin(-50);
        }
    }


    @SuppressLint("HandlerLeak")
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case AMAP_LOADED:
                    setupRecord();
                    break;
                default:
                    break;
            }
        }
    };

    public void onBackClick(View view) {
        this.finish();
        if (mThreadPool != null) {
            mThreadPool.shutdownNow();
        }
    }



    private LatLngBounds getBounds() {
        LatLngBounds.Builder b = LatLngBounds.builder();
        if (mOriginLatLngList == null) {
            return b.build();
        }
        for (int i = 0; i < mOriginLatLngList.size(); i++) {
            b.include(mOriginLatLngList.get(i));
        }
        return b.build();

    }

    /**
     * 轨迹数据初始化
     */
    private void setupRecord() {
        Record mRecord = LocationDBHelper.queryRecordById(recordType, mRecordItemId);
        if (mRecord != null) {
            List<RecordLocation> recordLocationList = mRecord.getPathLine();
            List<AMapLocation> recordList = LocationComputeUtil.getAMapLocationList(recordLocationList);
            AMapLocation startLoc = mRecord.getStartPoint();
            AMapLocation endLoc = mRecord.getEndpoint();
            if (recordList == null || startLoc == null || endLoc == null) {
                return;
            }
            LatLng startLatLng = new LatLng(startLoc.getLatitude(),
                    startLoc.getLongitude());
            LatLng endLatLng = new LatLng(endLoc.getLatitude(),
                    endLoc.getLongitude());
            mOriginLatLngList = LocationComputeUtil.parseLatLngList(recordList);
            addOriginTrace(startLatLng, endLatLng, mOriginLatLngList);
            addMilePost(recordLocationList);
//            addDurationPoint(recordLocationList);
        } else {

        }
    }


    private void addMilePost(List<RecordLocation> recordLocationList) {
        for (int i = 0; i < recordLocationList.size(); i++) {
            RecordLocation recordLocation = recordLocationList.get(i);
            if (recordLocation.milePost > 0) {
                AMapLocation location = LocationComputeUtil.parseLocation(recordLocation.locationStr);
                LatLng milePostPoint = new LatLng(location.getLatitude(), location.getLongitude());
                String milePost = String.valueOf(Math.round(recordLocation.getMilePost() / 1000));
                addMarker(milePostPoint, milePost, 16, 15, R.color.location_wrapper, R.color.location_inner_circle);
            }
        }
    }

    private void addDurationPoint(List<RecordLocation> recordLocationList) {
        for (int i = 0; i < recordLocationList.size(); i++) {
            RecordLocation recordLocation = recordLocationList.get(i);
            if (recordLocation.duration > 1000) {
                AMapLocation location = LocationComputeUtil.parseLocation(recordLocation.locationStr);
                LatLng durationPoint = new LatLng(location.getLatitude(), location.getLongitude());
                int time = Math.round(recordLocation.duration/1000);
                String timeStr = TimeDateUtil.getTimeStrWithSec(time, "h", "m", "s");

                addMarker(durationPoint, timeStr, 16, 12,
                        R.color.black_30_transparent, R.color.black_20_transparent);
            }
        }
    }

    private void addMarker(LatLng position, String displayStr,int radius, int textSize, int wrapperColor, int circleColor){
        addMarker(position, displayStr, radius, textSize, wrapperColor, circleColor, false);
    }

    private void addMarker(LatLng position, String displayStr,int radius, int textSize, int wrapperColor, int circleColor, boolean showBottomShader){
        View view = View.inflate(RecordShowActivity.this, R.layout.custom_location_view, null);
        RelativeLayout locationContainer = view.findViewById(R.id.locationContainer);
        LocationMarker locationMarker = new LocationMarker(mMapView.getContext(),
                DisplayUtil.dip2px(radius), displayStr, textSize);
        locationMarker.setColors(wrapperColor, circleColor);
        locationMarker.setDrawBottomShader(showBottomShader);

        locationContainer.addView(locationMarker);
        BitmapDescriptor markerIcon = BitmapDescriptorFactory.fromView(view);

        MarkerOptions optionPosition = new MarkerOptions()
                .position(position)
                .icon(markerIcon);
        Marker marker = mAMap.addMarker(optionPosition);

        Animation markerAnimation = new ScaleAnimation(0, 1, 0, 1); //初始化生长效果动画
        markerAnimation.setDuration(1000);  //设置动画时间 单位毫秒
        marker.setAnimation(markerAnimation);

        marker.startAnimation();
    }

    /**
     * 地图上添加原始轨迹线路及起终点、轨迹动画小人
     *
     * @param startPoint
     * @param endPoint
     * @param originList
     */
    private void addOriginTrace(LatLng startPoint, LatLng endPoint, List<LatLng> originList) {
        mAMap.addPolyline(new PolylineOptions().color(Color.BLACK).addAll(originList));
        addMarker(startPoint, "起", 17, 16, R.color.location_wrapper, R.color.location_inner_circle, true);
        addMarker(endPoint, "终", 17, 16, R.color.location_end_wrapper, R.color.location_end_circle, true);
        try {
            mAMap.moveCamera(CameraUpdateFactory.newLatLngBounds(getBounds(), 50));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onMapLoaded() {
        Message msg = handler.obtainMessage();
        msg.what = AMAP_LOADED;
        handler.sendMessage(msg);
    }

    //声明AMapLocationClient类对象
    public AMapLocationClient mLocationClient = null;

    //声明mLocationOption对象
    public AMapLocationClientOption mLocationOption = null;
    /**
     * 初始化定位相关东西
     */
    private void initLocation() {
        //初始化定位
        mLocationClient = new AMapLocationClient(getApplicationContext());
        //设置定位回调监听
        mLocationClient.setLocationListener(this);
        //初始化定位参数
        mLocationOption = new AMapLocationClientOption();
        //设置定位模式为高精度模式，Battery_Saving为低功耗模式，Device_Sensors是仅设备模式
        mLocationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        //设置是否返回地址信息（默认返回地址信息）
        mLocationOption.setNeedAddress(true);
        //设置是否只定位一次,默认为false
        mLocationOption.setOnceLocation(false);
        //设置是否强制刷新WIFI，默认为强制刷新
        mLocationOption.setWifiActiveScan(true);
        //设置是否允许模拟位置,默认为false，不允许模拟位置
        mLocationOption.setMockEnable(false);
        //定位一次，且将视角移动到地图中心点。
        mAMap.setMyLocationType(AMap.LOCATION_TYPE_LOCATE);
        //设置定位间隔,单位毫秒,默认为2000ms
//        mLocationOption.setInterval(2000);
        //给定位客户端对象设置定位参数
        mLocationClient.setLocationOption(mLocationOption);
    }

    @Override
    public void onLocationChanged(AMapLocation aMapLocation) {
        if (aMapLocation != null) {
            if (aMapLocation.getErrorCode() == 0) {
                //定位成功回调信息，设置相关消息
                //取出经纬度
                LatLng latLng = new LatLng(aMapLocation.getLatitude(), aMapLocation.getLongitude());
                //添加Marker显示定位位置
                if (locationMarker == null) {
                    //如果是空的添加一个新的,icon方法就是设置定位图标，可以自定义
                    locationMarker = mAMap.addMarker(new MarkerOptions()
                            .position(latLng)
                            .icon(BitmapDescriptorFactory.fromResource(R.drawable.location_marker)));
                } else {
                    //已经添加过了，修改位置即可
                    locationMarker.setPosition(latLng);
                }

                //然后可以移动到定位点,使用animateCamera就有动画效果
//                mAMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 50));
//                mAMap.moveCamera(CameraUpdateFactory.newLatLngBounds(getBounds(), 50));

            } else {
                //显示错误信息ErrCode是错误码，errInfo是错误信息，详见错误码表。
                Log.e("AmapError", "location Error, ErrCode:"
                        + aMapLocation.getErrorCode() + ", errInfo:"
                        + aMapLocation.getErrorInfo());
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMapView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mMapView.onSaveInstanceState(outState);
    }


    public void onDestroy() {
        super.onDestroy();
        if (mThreadPool != null) {
            mThreadPool.shutdownNow();
        }
        mMapView.onDestroy();
        //退出界面的时候停止定位
        if (mLocationClient != null) {
            mLocationClient.stopLocation();
        }
    }

}
