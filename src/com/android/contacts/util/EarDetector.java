/*
 * Copyright (C) 2009 The Android Open Source Project
 * Copyright (C) 2012 Havlena Petr <havlenapetr@gmail.com>
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

package com.android.contacts.util;

import java.util.ArrayList;

import junit.framework.Assert;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * This class is used to listen to the accelerometer and proximity to monitor the
 * orientation and distance of the phone. The client of this class is notified when
 * the phone is near to ear or far from ear.
 */
public class EarDetector {

    private static final String TAG = "EarDetector";
    private static final boolean DEBUG = false;

    // Device orientation
    public static final int ORIENTATION_VERTICAL = 1;
    public static final int ORIENTATION_HORIZONTAL = 2;

    // Device distance
    public static final int DISTANCE_FAR = 1;
    public static final int DISTANCE_NEAR = 2;

    private static final double VERTICAL_ANGLE = 50.0;

    private static final int SENSOR_ACCELEROMETER = 0;
    private static final int SENSOR_PROXIMITY = 1;

    private final SensorManager             mSensorManager;
    private final ArrayList<SensorStruct>   mSensors;
    private final Dispatcher                mDispatcher;

    public EarDetector(Context ctx, EarDetectorListener listener) {
        mDispatcher = new Dispatcher(listener);
        mSensorManager = (SensorManager)ctx.getSystemService(Context.SENSOR_SERVICE);

        Sensor accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor proximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        if (accelerometer != null && proximity != null) {
            mSensors = new ArrayList<SensorStruct>(2);
            mSensors.add(new SensorStruct(accelerometer, new AccelerometerHandler()));
            mSensors.add(new SensorStruct(proximity, new ProximityHandler()));
        } else {
            mSensors = null;
        }
    }

    public static boolean isSupported(Context ctx) {
        SensorManager sensorManager = (SensorManager)ctx.getSystemService(Context.SENSOR_SERVICE);
        return sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
                sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null;
    }

    private SensorStruct getAccelerometer() {
        return mSensors.get(SENSOR_ACCELEROMETER);
    }

    private SensorStruct getProximity() {
        return mSensors.get(SENSOR_PROXIMITY);
    }

    public boolean isInitzialized() {
        return mSensors != null;
    }

    public void enable(boolean enable) {
        if (!isInitzialized()) {
            throw new RuntimeException("Accelerometer or proximity sensor isn't presented on device!");
        }

        SensorStruct accelerometer = getAccelerometer();
        SensorStruct proximity = getProximity();
        if (enable) {
            accelerometer.value.clear();
            proximity.value.clear();
            mSensorManager.registerListener(accelerometer.listener, accelerometer.sensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
            mSensorManager.registerListener(proximity.listener, proximity.sensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            mSensorManager.unregisterListener(accelerometer.listener);
            mSensorManager.unregisterListener(proximity.listener);
        }
    }

    private boolean setValue(SensorValue sensorValue, int value) {
        synchronized (mDispatcher) {
            if (sensorValue.pending == value) {
                // Pending orientation has not changed, so do nothing.
                return false;
            }

            if (sensorValue.real != value) {
                sensorValue.pending = value;
            } else {
                sensorValue.pending = -1;
            }

            return sensorValue.pending != -1;
        }
    }

    private void setProximity(int distance) {
        SensorStruct proximity = getProximity();
        if (setValue(proximity.value, distance)) {
            mDispatcher.dispatchProximity(proximity.value);
        }
    }

    private void setOrientation(int orientation) {
        SensorStruct accelerometer = getAccelerometer();
        if (setValue(accelerometer.value, orientation)) {
            mDispatcher.dispatchOrientation(accelerometer.value);
        }
    }

    private class Dispatcher extends Handler {

        private static final int ORIENTATION_CHANGED = 1234;
        private static final int PROXIMITY_CHANGED = 4321;
        private static final int TIMEOUT = 1000;

        private final EarDetectorListener mListener;
        private SensorValue mProximityVal;
        private SensorValue mOrientationVal;
        private boolean mLastValue;

        public Dispatcher(EarDetectorListener listener) {
            mListener = listener;
            mProximityVal = mOrientationVal = new SensorValue();
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case PROXIMITY_CHANGED:
                    synchronized (this) {
                        SensorValue val = (SensorValue) msg.obj;
                        val.real = val.pending;
                        mProximityVal = new SensorValue(val);
                    }
                    break;
                case ORIENTATION_CHANGED:
                    synchronized (this) {
                        SensorValue val = (SensorValue) msg.obj;
                        val.real = val.pending;
                        mOrientationVal = new SensorValue(val);
                    }
                    break;
            }

            if (!mOrientationVal.isExpired(TIMEOUT) && !mProximityVal.isExpired(TIMEOUT)) {
                boolean enable = mOrientationVal.real == ORIENTATION_VERTICAL &&
                        mProximityVal.real == DISTANCE_NEAR;
                // Report only if change occured
                if (enable != mLastValue) {
                    mListener.onEarDetected(enable);
                    mLastValue = enable;
                }
            }
        }

        public void dispatchOrientation(SensorValue value) {
            // Cancel any pending messages
            removeMessages(ORIENTATION_CHANGED);

            Message m = obtainMessage(ORIENTATION_CHANGED);
            m.obj = value;
            sendMessageDelayed(m, 100);
        }

        public void dispatchProximity(SensorValue value) {
            // Cancel any pending messages
            removeMessages(PROXIMITY_CHANGED);

            Message m = obtainMessage(PROXIMITY_CHANGED);
            m.obj = value;
            sendMessageDelayed(m, 100);
        }
    }

    private class AccelerometerHandler implements SensorEventListener {

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        @Override
        public void onSensorChanged(SensorEvent event) {
            double x = event.values[0];
            double y = event.values[1];
            double z = event.values[2];

            // If some values are exactly zero, then likely the sensor is not powered up yet.
            // ignore these events to avoid false horizontal positives.
            if (x == 0.0 || y == 0.0 || z == 0.0) return;

            // magnitude of the acceleration vector projected onto XY plane
            double xy = Math.sqrt(x*x + y*y);
            // compute the vertical angle
            double angle = Math.atan2(xy, z);
            // convert to degrees
            angle = angle * 180.0 / Math.PI;
            int orientation = (angle >  VERTICAL_ANGLE ? ORIENTATION_VERTICAL : ORIENTATION_HORIZONTAL);
            if (DEBUG) Log.i(TAG, "Orientation: " + orientation);
            setOrientation(orientation);
        }
    }

    private class ProximityHandler implements SensorEventListener {

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        @Override
        public void onSensorChanged(SensorEvent event) {
            double distance = event.values[0];
            if (DEBUG) Log.i(TAG, String.format("Proximity: distance(%f)", distance));
            setProximity(distance > 2 ? DISTANCE_FAR : DISTANCE_NEAR);
        }
    }

    private static class SensorStruct {

        public final Sensor sensor;
        public final SensorValue value;
        public final SensorEventListener listener;

        public SensorStruct(Sensor sensor, SensorEventListener listener) {
            Assert.assertNotNull("Sensor can't be null!", sensor);
            Assert.assertNotNull("Sensor handler can't be null!", listener);
            this.sensor = sensor;
            this.listener = listener;
            this.value = new SensorValue();
        }
    }

    private static class SensorValue {

        public int pending;
        public int real;
        private long time;

        public SensorValue() {
            time = System.currentTimeMillis();
        }

        public SensorValue(SensorValue val) {
            this();
            pending = val.pending;
            real = val.real;
        }

        public void clear() {
            pending = real = -1;
        }

        public boolean isEmpty() {
            return pending == -1;
        }

        public boolean isExpired(int timeout) {
            return isEmpty() || (System.currentTimeMillis() - time > timeout);
        }
    }

    public interface EarDetectorListener {
        public void onEarDetected(boolean earDetected);
    }
}
