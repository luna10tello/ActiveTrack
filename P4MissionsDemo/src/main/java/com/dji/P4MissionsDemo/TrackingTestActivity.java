package com.dji.P4MissionsDemo;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.mission.activetrack.ActiveTrackMission;
import dji.common.mission.activetrack.ActiveTrackMissionEvent;
import dji.common.mission.activetrack.ActiveTrackMode;
import dji.common.mission.activetrack.ActiveTrackState;
import dji.common.mission.activetrack.ActiveTrackTargetState;
import dji.common.mission.activetrack.ActiveTrackTrackingState;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.mission.activetrack.ActiveTrackMissionOperatorListener;
import dji.sdk.mission.activetrack.ActiveTrackOperator;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.thirdparty.rx.Observable;
import dji.thirdparty.rx.Subscription;
import dji.thirdparty.rx.functions.Action1;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SlidingDrawer;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import org.w3c.dom.Text;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

public class TrackingTestActivity extends DemoBaseActivity implements SurfaceTextureListener, OnClickListener, OnTouchListener, ActiveTrackMissionOperatorListener {

    private static final String TAG = "TrackingTestActivity";

    private ActiveTrackMission mActiveTrackMission;
    private FlightController mFlightController;
    private Camera mCamera;
    private BaseProduct mProduct;
    private Observable<Long> timer = Observable.interval(100, TimeUnit.MILLISECONDS);
    private Subscription timerSubscription;
    private Subscription carHeadingSubscription;
    private float droneHeading;
    private float carHeading;
    private float carHeadingTime;
    private float carHeadingDelta;
    private float maxSpeed;
    private float steeringAngle;
    private DatagramSocket udpSocket;
    private Thread t;

    private ImageButton mPushDrawerIb;
    private SlidingDrawer mPushInfoSd;
    private ImageButton mStopBtn;
    private ImageView mTrackingImage;
    private RelativeLayout mBgLayout;
    private TextView mPushInfoTv;
    private TextView mPushBackTv;
    private TextView mGestureModeTv;
    private Switch mPushBackSw;
    private Switch mGestureModeSw;
    private ImageView mSendRectIV;
    private Button mConfigBtn;
    private Button mConfirmBtn;
    private Button mRejectBtn;
    private Button mConfigureCarHeadingBtn;

    private boolean isDrawingRect = false;

    private ActiveTrackOperator getActiveTrackOperator() {
        return DJISDKManager.getInstance().getMissionControl().getActiveTrackOperator();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setContentView(R.layout.activity_tracking_test);
        super.onCreate(savedInstanceState);
        initUI();
        getActiveTrackOperator().addListener(this);
        t = new Thread (new ReceiveUDP());
        t.start();
    }

    private class ReceiveUDP implements Runnable {

        byte[] receiveData = new byte[8];
        ByteBuffer buffer = ByteBuffer.allocate(8);     //entspricht 2 float values (angleHeading, SteeringAngle)
        @Override
        public void run() {
            try {
                udpSocket = new DatagramSocket(4445);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
            } catch (SocketException e) {
                Log.e(TAG, "SocketException: " + e.getCause());
            }
            while (!t.isInterrupted()) {
                try {
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    udpSocket.receive(receivePacket);
                    if (receiveData != null) {
                        try {
                            buffer.put(receiveData);
                            buffer.flip();
                            carHeading = buffer.getFloat();
                            steeringAngle = buffer.getFloat();
                        } catch (BufferOverflowException e) {
                            Log.e(TAG, "BufferOverflowException: " + e.getCause());
                        }
                        Log.e(TAG, "lat = " + carHeading + " lon = " + steeringAngle);
                    }
                } catch (java.io.IOException e) {
                    Log.e(TAG, "IOException: " + e.getCause());
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        initFlightController();
    }

    private void initFlightController() {
        mProduct = DJIDemoApplication.getProductInstance();
        if (mProduct != null && mProduct.isConnected()) {
            if (mProduct instanceof Aircraft) {
                mFlightController = ((Aircraft) mProduct).getFlightController();
                mCamera = ((Aircraft) mProduct).getCamera();
                mCamera.setExposureMode(SettingsDefinitions.ExposureMode.PROGRAM, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        Log.i(TAG, "set exposure mode PROGRAM: " + (djiError == null ? "success" : djiError.getDescription()));
                    }
                });
            }
        }
    }

    @Override
    protected void onDestroy() {

        if(mCodecManager != null){
            mCodecManager.destroyCodec();
        }
        cancelSubscription(timerSubscription);
        cancelSubscription(carHeadingSubscription);
        t.interrupt();
        udpSocket.close();
        super.onDestroy();
    }

    private void cancelSubscription(Subscription subscription) {
        if (subscription != null && !subscription.isUnsubscribed()) {
            subscription.unsubscribe();
            Log.i(TAG, "Unsubscribed Timer: " + subscription.toString());
        }
    }

    public void onReturn(View view){
        this.finish();
    }

    private void setResultToToast(final String string) {
        TrackingTestActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(TrackingTestActivity.this, string, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setResultToText(final String string) {
        if (mPushInfoTv == null) {
            setResultToToast("Push info tv has not be init...");
        }
        TrackingTestActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mPushInfoTv.setText(string);
            }
        });
    }

    private void initUI() {
        mPushDrawerIb = (ImageButton)findViewById(R.id.tracking_drawer_control_ib);
        mPushInfoSd = (SlidingDrawer)findViewById(R.id.tracking_drawer_sd);
        mStopBtn = (ImageButton)findViewById(R.id.tracking_stop_btn);
        mTrackingImage = (ImageView) findViewById(R.id.tracking_rst_rect_iv);
        mBgLayout = (RelativeLayout)findViewById(R.id.tracking_bg_layout);
        mPushInfoTv = (TextView)findViewById(R.id.tracking_push_tv);
        mSendRectIV = (ImageView)findViewById(R.id.tracking_send_rect_iv);
        mPushBackSw = (Switch)findViewById(R.id.tracking_pull_back_sw);
        mPushBackTv = (TextView)findViewById(R.id.tracking_backward_tv);
        mGestureModeTv = (TextView)findViewById(R.id.gesture_mode_tv);
        mGestureModeSw = (Switch)findViewById(R.id.gesture_mode_enable_sw);
        mConfigBtn = (Button)findViewById(R.id.recommended_configuration_btn);
        mConfirmBtn = (Button)findViewById(R.id.confirm_btn);
        mRejectBtn = (Button)findViewById(R.id.reject_btn);
        mConfigureCarHeadingBtn = findViewById(R.id.configurecarheading);
        mStopBtn.setOnClickListener(this);
        mBgLayout.setOnTouchListener(this);
        mPushDrawerIb.setOnClickListener(this);
        mConfigBtn.setOnClickListener(this);
        mConfirmBtn.setOnClickListener(this);
        mRejectBtn.setOnClickListener(this);
        mConfigureCarHeadingBtn.setOnClickListener(this);

        mGestureModeSw.setChecked(getActiveTrackOperator().isGestureModeEnabled());

        mPushBackSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()
        {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked)
            {
                getActiveTrackOperator().setRetreatEnabled(isChecked, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        setResultToToast("Set RetreatEnabled: " + (error == null
                                ? "Success"
                                : error.getDescription()));
                    }
                });
            }
        });

        mGestureModeSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()
        {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked)
            {
                getActiveTrackOperator().setGestureModeEnabled(isChecked, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        setResultToToast("Set GestureMode Enabled: " + (error == null
                                ? "Success"
                                : error.getDescription()));
                    }
                });
            }
        });

    }

    private float getDroneHeading() {
        if (mFlightController.getCompass().getHeading() < 0) return mFlightController.getCompass().getHeading() + 360;
        else return mFlightController.getCompass().getHeading();
    }

    @Override
    public void onUpdate(ActiveTrackMissionEvent event) {

        StringBuffer sb = new StringBuffer();
        String errorInformation = (event.getError() == null ? "null" : event.getError().getDescription()) + "\n";
        String currentState = event.getCurrentState() == null ? "null" : event.getCurrentState().getName();
        String previousState = event.getPreviousState() == null ? "null" : event.getPreviousState().getName();

        ActiveTrackTargetState targetState = ActiveTrackTargetState.UNKNOWN;
        if (event.getTrackingState() != null) {
            targetState = event.getTrackingState().getState();
        }
        Utils.addLineToSB(sb, "CurrentState: ", currentState);
        Utils.addLineToSB(sb, "PreviousState: ", previousState);
        Utils.addLineToSB(sb, "TargetState: ", targetState);
        Utils.addLineToSB(sb, "Error:", errorInformation);

        ActiveTrackTrackingState trackingState = event.getTrackingState();
        if (trackingState != null) {
            RectF trackingRect = trackingState.getTargetRect();
            if (trackingRect != null) {

                Utils.addLineToSB(sb, "Rect center x: ", trackingRect.centerX());
                Utils.addLineToSB(sb, "Rect center y: ", trackingRect.centerY());
                Utils.addLineToSB(sb, "Rect Width: ", trackingRect.width());
                Utils.addLineToSB(sb, "Rect Height: ", trackingRect.height());
                Utils.addLineToSB(sb, "Reason", trackingState.getReason().name());
                Utils.addLineToSB(sb, "Target Index: ", trackingState.getTargetIndex());
                Utils.addLineToSB(sb, "Target Type", trackingState.getType().name());
                Utils.addLineToSB(sb, "Target State", trackingState.getState().name());

                setResultToText(sb.toString());
            }
        }

        updateActiveTrackRect(mTrackingImage, event);

        ActiveTrackState state = event.getCurrentState();
        if (state == ActiveTrackState.FINDING_TRACKED_TARGET ||
                state == ActiveTrackState.AIRCRAFT_FOLLOWING ||
                state == ActiveTrackState.ONLY_CAMERA_FOLLOWING ||
                state == ActiveTrackState.CANNOT_CONFIRM ||
                state == ActiveTrackState.WAITING_FOR_CONFIRMATION ||
                state == ActiveTrackState.PERFORMING_QUICK_SHOT) {

            TrackingTestActivity.this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    mStopBtn.setVisibility(View.VISIBLE);
                    mStopBtn.setClickable(true);
                    mConfirmBtn.setVisibility(View.VISIBLE);
                    mConfirmBtn.setClickable(true);
                    mRejectBtn.setVisibility(View.VISIBLE);
                    mRejectBtn.setClickable(true);
                    mConfigBtn.setVisibility(View.INVISIBLE);
                }
            });
        } else {
            TrackingTestActivity.this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    mStopBtn.setVisibility(View.INVISIBLE);
                    mStopBtn.setClickable(false);
                    mConfirmBtn.setVisibility(View.INVISIBLE);
                    mConfirmBtn.setClickable(false);
                    mRejectBtn.setVisibility(View.INVISIBLE);
                    mRejectBtn.setClickable(false);
                    mConfigBtn.setVisibility(View.VISIBLE);
                    mTrackingImage.setVisibility(View.INVISIBLE);
                }
            });
        }
    }

    private RectF getActiveTrackRect(View iv) {
        View parent = (View)iv.getParent();
        return new RectF(
                ((float)iv.getLeft() + iv.getX()) / (float)parent.getWidth(),
                ((float)iv.getTop() + iv.getY()) / (float)parent.getHeight(),
                ((float)iv.getRight() + iv.getX()) / (float)parent.getWidth(),
                ((float)iv.getBottom() + iv.getY()) / (float)parent.getHeight()
        );
    }

    private void updateActiveTrackRect(final ImageView iv, final ActiveTrackMissionEvent event) {
        if (iv == null || event == null) return;
        View parent = (View)iv.getParent();

        if (event.getTrackingState() != null){
            RectF trackingRect = event.getTrackingState().getTargetRect();
            final int l = (int)((trackingRect.centerX() - trackingRect.width() / 2) * parent.getWidth());
            final int t = (int)((trackingRect.centerY() - trackingRect.height() / 2) * parent.getHeight());
            final int r = (int)((trackingRect.centerX() + trackingRect.width() / 2) * parent.getWidth());
            final int b = (int)((trackingRect.centerY() + trackingRect.height() / 2) * parent.getHeight());

            TrackingTestActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {

                    ActiveTrackTargetState targetState = event.getTrackingState().getState();

                    if ((targetState == ActiveTrackTargetState.CANNOT_CONFIRM)
                            || (targetState == ActiveTrackTargetState.UNKNOWN))
                    {
                        iv.setImageResource(R.drawable.visual_track_cannotconfirm);
                    } else if (targetState == ActiveTrackTargetState.WAITING_FOR_CONFIRMATION) {
                        iv.setImageResource(R.drawable.visual_track_needconfirm);
                    } else if (targetState == ActiveTrackTargetState.TRACKING_WITH_LOW_CONFIDENCE){
                        iv.setImageResource(R.drawable.visual_track_lowconfidence);
                    } else if (targetState == ActiveTrackTargetState.TRACKING_WITH_HIGH_CONFIDENCE){
                        iv.setImageResource(R.drawable.visual_track_highconfidence);
                    }
                    iv.setVisibility(View.VISIBLE);
                    iv.setX(l);
                    iv.setY(t);
                    iv.getLayoutParams().width = r - l;
                    iv.getLayoutParams().height = b - t;
                    iv.requestLayout();
                }
            });
        }
    }

    float downX;
    float downY;

    private double calcManhattanDistance(double point1X, double point1Y, double point2X, double point2Y) {
        return Math.abs(point1X - point2X) + Math.abs(point1Y - point2Y);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isDrawingRect = false;
                downX = event.getX();
                downY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (calcManhattanDistance(downX, downY, event.getX(), event.getY()) < 20 && !isDrawingRect) {
                    return true;
                }
                isDrawingRect = true;
                mSendRectIV.setVisibility(View.VISIBLE);
                int l = (int)(downX < event.getX() ? downX : event.getX());
                int t = (int)(downY < event.getY() ? downY : event.getY());
                int r = (int)(downX >= event.getX() ? downX : event.getX());
                int b = (int)(downY >= event.getY() ? downY : event.getY());
                mSendRectIV.setX(l);
                mSendRectIV.setY(t);
                mSendRectIV.getLayoutParams().width = r - l;
                mSendRectIV.getLayoutParams().height = b - t;
                mSendRectIV.requestLayout();

                break;

            case MotionEvent.ACTION_UP:

                RectF rectF = getActiveTrackRect(mSendRectIV);
                PointF pointF = new PointF(downX / mBgLayout.getWidth(), downY / mBgLayout.getHeight());
                RectF pointRectF = new RectF(pointF.x, pointF.y, 0, 0);
                mActiveTrackMission = isDrawingRect ? new ActiveTrackMission(rectF, ActiveTrackMode.TRACE) :
                        new ActiveTrackMission(pointRectF, ActiveTrackMode.TRACE);

                getActiveTrackOperator().startTracking(mActiveTrackMission, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        setResultToToast("Start Tracking: " + (error == null
                                    ? "Success"
                                    : error.getDescription()));
                    }
                });
                mSendRectIV.setVisibility(View.INVISIBLE);
                break;
            default:
                break;
        }

        return true;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.tracking_stop_btn:
                cancelSubscription(timerSubscription);
                getActiveTrackOperator().stopTracking(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        setResultToToast(error == null ? "Stop Tracking Success!" : error.getDescription());
                    }
                });

                break;
            case R.id.tracking_drawer_control_ib:
                if (mPushInfoSd.isOpened()) {
                    mPushInfoSd.animateClose();
                } else {
                    mPushInfoSd.animateOpen();
                }
                break;
            case R.id.recommended_configuration_btn:
                getActiveTrackOperator().setRecommendedConfiguration(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        setResultToToast("Set Recommended Config" + (error == null ? "Success" : error.getDescription()));
                    }
                });
                break;
            case R.id.confirm_btn:
                carHeading = getDroneHeading();
                getActiveTrackOperator().acceptConfirmation(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        setResultToToast(error == null ? "Accept Confirm Success!" : error.getDescription());
                        carHeadingSubscription = timer.subscribe(new Action1<Long>() {
                            int i = 0;
                            @Override
                            public void call(Long aLong) {
                                if (i >= carHeadingTime*10) cancelSubscription(carHeadingSubscription);
                                if (carHeading > 359)  carHeading = 0;
                                else if (carHeading < 0) carHeading = 359;
                                else carHeading = carHeading - carHeadingDelta * 0.1f / carHeadingTime;
                                i++;
                                //Log.i(TAG, "current carHeading: " + carHeading);
                            }
                        });
                        if (mFlightController != null) {
                            timerSubscription = timer.subscribe(new Action1<Long>() {
                                float speedFactor;
                                @Override
                                public void call(Long aLong) {
                                    droneHeading = getDroneHeading();
                                    //Log.i(TAG, "current compass heading: " + droneHeading);
                                    float angleDiff = droneHeading - carHeading;
                                    if (Math.abs(angleDiff) < 360 - Math.abs(angleDiff)) {
                                        if (angleDiff >= 90) speedFactor = 1;
                                        else if (angleDiff <= -90) speedFactor = -1;
                                        else speedFactor = angleDiff/90;
                                    } else {
                                        angleDiff = (360 - Math.abs(angleDiff))*-(Math.signum(angleDiff));
                                        if (angleDiff >= 90) speedFactor = 1;
                                        else if (angleDiff <= -90) speedFactor = -1;
                                        else speedFactor = angleDiff/90;
                                    }
                                    Log.i(TAG, "droneHeading = " + droneHeading + " carHeading = " + carHeading + " speedfactor = " + speedFactor);
                                    getActiveTrackOperator().setCircularSpeed(speedFactor*maxSpeed, new CommonCallbacks.CompletionCallback() {
                                        @Override
                                        public void onResult(DJIError djiError) {
                                            if (djiError != null) Log.e(TAG, "setCircularSpeed error: " + djiError.getDescription());
                                        }
                                    });
                                }
                            });
                        }
                    }
                });
                break;
            case R.id.reject_btn:
                cancelSubscription(timerSubscription);
                getActiveTrackOperator().rejectConfirmation(new CommonCallbacks.CompletionCallback() {

                    @Override
                    public void onResult(DJIError error) {
                        setResultToToast(error == null ? "Reject Confirm Success!" : error.getDescription());
                    }
                });
                break;
            case R.id.configurecarheading:
                showSettingDialog();
                break;
            default:
                break;
        }
    }

    private void showSettingDialog() {
        LinearLayout activeTrackSettings = (LinearLayout) getLayoutInflater().inflate(R.layout.dialog_activetrack_setting, null);
        final TextView maxSpeed_TV = activeTrackSettings.findViewById(R.id.maxspeed);
        final TextView carHeadingTime_TV = activeTrackSettings.findViewById(R.id.carheadingtime);
        final TextView carHeadingDelta_TV = activeTrackSettings.findViewById(R.id.carheadingdelta);

        new AlertDialog.Builder(this)
                .setTitle("")
                .setView(activeTrackSettings)
                .setPositiveButton("Finish",new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface dialog, int id) {

                        maxSpeed = Float.parseFloat(nullToFloat(maxSpeed_TV.getText().toString()));
                        carHeadingTime = Float.parseFloat(nullToFloat(carHeadingTime_TV.getText().toString()));
                        carHeadingDelta = Float.parseFloat(nullToFloat(carHeadingDelta_TV.getText().toString()));
                        Log.e(TAG,"maxSpeed: " + maxSpeed);
                        Log.e(TAG, "carHeadingTime: " + carHeadingTime);
                        Log.e(TAG, "carHeadingDelta: " + carHeadingDelta);
                        carHeading = getDroneHeading();
                    }

                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }

                })
                .create()
                .show();
    }

    String nullToFloat (String value) {
        if (!isFloatValue(value)) value = "0";
        return value;
    }

    boolean isFloatValue(String val) {
        try {
            val = val.replace(" ", "");
            Float.parseFloat(val);
        } catch (Exception e) {return false;}
        return true;
    }
}
