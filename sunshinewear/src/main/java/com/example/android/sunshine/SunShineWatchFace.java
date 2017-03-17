package com.example.android.sunshine;
/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunShineWatchFace extends CanvasWatchFaceService{
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunShineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunShineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunShineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,GoogleApiClient.OnConnectionFailedListener,GoogleApiClient.ConnectionCallbacks
    {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mCenterLine;
        Paint mImage;
        Paint maxTemp;
        Paint minTemp;
        Bitmap mBitmap;

        int mWeatherId;

        static final String WEATHER_DATA_PATH = "/WEATHER_DATA_PATH";
        static final String WEATHER_ID = "WEATHER_KEY";
        static final String MIN_TEMP = "MIN_TEMP";
        static final String MAX_TEMP = "MAX_TEMP";
        static final String PREFERENCES = "PREFERENCES";
        static final String KEY_MAX_TEMP = "KEY_MAX_TEMP";
        static final String KEY_MIN_TEMP = "KEY_MIN_TEMP";
        static final String KEY_WEATHER_ID = "KEY_WEATHER_ID";

        GoogleApiClient mGoogleApiClient;
        String maxTemprature="";
        String minTemprature="";


        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);


            SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
            maxTemprature = preferences.getString(KEY_MAX_TEMP,"--");
            minTemprature = preferences.getString(KEY_MIN_TEMP,"--");
            mWeatherId = preferences.getInt(KEY_WEATHER_ID,800);
            loadIconFromWeatherId();


            mGoogleApiClient = new GoogleApiClient.Builder(SunShineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            mGoogleApiClient.connect();


            setWatchFaceStyle(new WatchFaceStyle.Builder(SunShineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunShineWatchFace.this.getResources();
            // mYOffset = resources.getDimension(R.dimen.digital_y_offset);



            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(getColor(R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(getColor(R.color.date_text));

            mCenterLine = new Paint();
            mCenterLine = createTextPaint(getColor(R.color.digital_text));
            mCenterLine.setTextSize(10);
            mCalendar = Calendar.getInstance();


            mImage = new Paint();
            mImage.setColor(Color.BLACK);

            maxTemp=new Paint();
            maxTemp.setColor(Color.WHITE);
            maxTemp.setAntiAlias(true);
            maxTemp.setTypeface(NORMAL_TYPEFACE);

            minTemp=new Paint();
            minTemp.setColor(getColor(R.color.date_text));
            minTemp.setAntiAlias(true);
            minTemp.setTypeface(NORMAL_TYPEFACE);

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                 mGoogleApiClient.connect();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunShineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunShineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunShineWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            mYOffset=resources.getDimension(isRound
                    ? R.dimen.digital_y_offset : R.dimen.digital_y_offset_square);
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size_sqaure);

            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size_sqaure);


            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateTextSize);
            maxTemp.setTextSize(tempTextSize);
            minTemp.setTextSize(tempTextSize);

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);

                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }



        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            }
            else
            {
                canvas.drawColor(getColor(R.color.background));
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);


            String text = String.format("%02d:%02d",(mCalendar.get(Calendar.HOUR)+12)%24,mCalendar.get(Calendar.MINUTE));

            //draw time
            canvas.drawText(text,bounds.centerX()-80, mYOffset, mTextPaint);


            // draw date
            canvas.drawText(getFormatedDate(),bounds.centerX()-mDatePaint.getTextSize()*4+10,mYOffset+40,mDatePaint);

            // draw center line of watch
            canvas.drawLine(bounds.centerX()-30,bounds.centerY(),bounds.centerX()+30,bounds.centerY(),mCenterLine);


            // draw image
            canvas.drawBitmap(mBitmap,bounds.centerX()-100,bounds.centerY()+10,mImage);

            // draw max temp
            canvas.drawText(showTemperatureWithDegree(maxTemprature),bounds.centerX()-25,bounds.centerY()+55,maxTemp);

            //draw min temp
            canvas.drawText(showTemperatureWithDegree(minTemprature),bounds.centerX()+45,bounds.centerY()+55,minTemp);
        }


        public String getFormatedDate()
        {
            SimpleDateFormat sdf=new SimpleDateFormat("EEE,d MMM yyyy");
            return sdf.format(mCalendar.getTime());

        }

        public String showTemperatureWithDegree(String temp)
        {
            final String DEGREE  = "\u00b0";
            return temp+""+DEGREE;
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }


        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d("Sunnny: ","Connected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(@NonNull DataItemBuffer dataItems) {
                      if(dataItems!=null)
                      {
                          if(dataItems.getStatus().isSuccess())
                          {
                              for(DataItem data:dataItems)
                              {
                                  DataMap dataMap = DataMapItem.fromDataItem(data).getDataMap();
                                  int high = dataMap.getInt(MAX_TEMP);
                                  int low = dataMap.getInt(MIN_TEMP);
                                  int id = dataMap.getInt(WEATHER_ID);


                                  minTemprature=""+low;
                                  maxTemprature=""+high;
                                  mWeatherId = id;

                                  loadIconFromWeatherId();

                                  SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
                                  SharedPreferences.Editor editor = preferences.edit();
                                  editor.putString(KEY_MAX_TEMP, maxTemprature);
                                  editor.putString(KEY_MIN_TEMP, minTemprature);
                                  editor.putInt(KEY_WEATHER_ID, mWeatherId);
                                  editor.apply();

                              }
                          }
                      }
                      dataItems.release();
                      if (isVisible() && !isInAmbientMode()) {
                        invalidate();
                      }
                }
            });
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d("Sunnny:","Connection Suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
             Log.d("Sunnny:","Connection Failed : "+connectionResult);
        }




        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d("Sunnny: ", "Yeh Data!");
            for (DataEvent event : dataEventBuffer) {
                DataItem item = event.getDataItem();
                if (WEATHER_DATA_PATH.equals(item.getUri().getPath())) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    int high = dataMap.getInt(MAX_TEMP);
                    int low = dataMap.getInt(MIN_TEMP);
                    int id = dataMap.getInt(WEATHER_ID);


                    minTemprature=""+low;
                    maxTemprature=""+high;
                    mWeatherId = id;

                    loadIconFromWeatherId();

                    SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putString(KEY_MAX_TEMP, maxTemprature);
                    editor.putString(KEY_MIN_TEMP, minTemprature);
                    editor.putInt(KEY_WEATHER_ID, mWeatherId);
                    editor.apply();
                    if (isVisible() && !isInAmbientMode()) {
                        invalidate();
                    }

                }
            }
        }


        private void loadIconFromWeatherId() {

            int iconId = 0;
            if (mWeatherId >= 200 && mWeatherId <= 232) {
                iconId = R.drawable.ic_storm;
            } else if (mWeatherId >= 300 && mWeatherId <= 321) {
                iconId = R.drawable.ic_light_rain;
            } else if (mWeatherId >= 500 && mWeatherId <= 504) {
                iconId = R.drawable.ic_rain;
            } else if (mWeatherId == 511) {
                iconId = R.drawable.ic_snow;
            } else if (mWeatherId >= 520 && mWeatherId <= 531) {
                iconId = R.drawable.ic_rain;
            } else if (mWeatherId >= 600 && mWeatherId <= 622) {
                iconId = R.drawable.ic_snow;
            } else if (mWeatherId >= 701 && mWeatherId <= 761) {
                iconId = R.drawable.ic_fog;
            } else if (mWeatherId == 761 || mWeatherId == 781) {
                iconId = R.drawable.ic_storm;
            } else if (mWeatherId == 800) {
                iconId = R.drawable.ic_clear;
            } else if (mWeatherId == 801) {
                iconId = R.drawable.ic_light_clouds;
            } else if (mWeatherId >= 802 && mWeatherId <= 804) {
                iconId = R.drawable.ic_cloudy;
            }
            else
            {
                iconId = R.drawable.ic_clear;
            }

            if (iconId != 0) {
                mBitmap = BitmapFactory.decodeResource(getResources(), iconId);
            }

        }




    }
}
