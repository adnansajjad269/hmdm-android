/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
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

package com.hmdm.launcher.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.util.RemoteLogger;

public class LocationService extends Service {
    private LocationManager locationManager;

    private static final int NOTIFICATION_ID = 112;
    public static String CHANNEL_ID = LocationService.class.getName();

    public static final String ACTION_UPDATE_GPS = "gps";
    public static final String ACTION_UPDATE_NETWORK = "network";
    public static final String ACTION_STOP = "stop";

    boolean updateViaGps = false;
    boolean started = false;

    // Location fix interval (provider minTime). Tunable per-config via the "locationUpdateIntervalSec"
    // app preference, clamped to [MIN, MAX]. Default raised to 900 s (15 min) to cut location wakeups.
    private static final long DEFAULT_LOCATION_UPDATE_INTERVAL_MS = 900000;
    private static final long MIN_LOCATION_UPDATE_INTERVAL_MS = 60000;    // 1 min floor
    private static final long MAX_LOCATION_UPDATE_INTERVAL_MS = 3600000;  // 1 hour ceiling

    // Use different location listeners for GPS and Network
    // Not sure what happens if we share the same listener for both providers
    private LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Do nothing here: we use getLastKnownLocation() to determine the location!
            //Toast.makeText(LocationService.this, "Location updated from GPS", Toast.LENGTH_SHORT).show();
            RemoteLogger.log(LocationService.this, Const.LOG_VERBOSE, "GPS location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            ProUtils.processLocation(LocationService.this, location, LocationManager.GPS_PROVIDER);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private LocationListener networkLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Do nothing here: we use getLastKnownLocation() to determine the location!
            //Toast.makeText(LocationService.this, "Location updated from Network", Toast.LENGTH_SHORT).show();
            RemoteLogger.log(LocationService.this, Const.LOG_VERBOSE, "Network location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            ProUtils.processLocation(LocationService.this, location, LocationManager.NETWORK_PROVIDER);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private Handler handler = new Handler();
    private GnssStatus.Callback gnssStatusCallback = null;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager)this.getSystemService(LOCATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                    super.onSatelliteStatusChanged(status);
                    try {
                        Log.d(Const.LOG_TAG, "Satellite status changed, count: " + status.getSatelliteCount());
                        SettingsHelper.getInstance(LocationService.this.getApplicationContext()).setSatelliteCount(status.getSatelliteCount());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            };
        }
    }

    @SuppressLint("WrongConstant")
    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW keeps the mandatory foreground-service notification silent (no sound,
            // no heads-up) and low in the shade, reducing battery/attention cost.
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Notification Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder( this );
        }
        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setTicker(ProUtils.getAppName(this))
                .setContentText( getString( R.string.location_service_text ) )
                .setSmallIcon( R.drawable.ic_location_service ).build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // Resolve the location fix interval: default DEFAULT_LOCATION_UPDATE_INTERVAL_MS, overridable
    // per-config via the "locationUpdateIntervalSec" app preference, clamped to [MIN, MAX].
    private long resolveLocationUpdateInterval() {
        long intervalMs = DEFAULT_LOCATION_UPDATE_INTERVAL_MS;
        try {
            String pref = SettingsHelper.getInstance(this).getAppPreference(getPackageName(), "locationUpdateIntervalSec");
            if (pref != null && !pref.trim().isEmpty()) {
                long candidate = Long.parseLong(pref.trim()) * 1000L;
                if (candidate < MIN_LOCATION_UPDATE_INTERVAL_MS) {
                    candidate = MIN_LOCATION_UPDATE_INTERVAL_MS;
                } else if (candidate > MAX_LOCATION_UPDATE_INTERVAL_MS) {
                    candidate = MAX_LOCATION_UPDATE_INTERVAL_MS;
                }
                intervalMs = candidate;
            }
        } catch (Exception e) {
            // Bad preference value: keep the default
        }
        return intervalMs;
    }

    private boolean requestLocationUpdates() {
        if (updateViaGps && (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))) {
            updateViaGps = false;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            // No permission, so give up!
            return false;
        }

        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        boolean passiveEnabled = locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);
        RemoteLogger.log(this, Const.LOG_VERBOSE,
                "Request location updates. gps=" + gpsEnabled + ", network=" + networkEnabled + ", passive=" + passiveEnabled);

        long updateInterval = resolveLocationUpdateInterval();
        RemoteLogger.log(this, Const.LOG_INFO, "Location update interval = " + updateInterval + " ms");

        locationManager.removeUpdates(networkLocationListener);
        locationManager.removeUpdates(gpsLocationListener);
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, updateInterval, 0, networkLocationListener);
            if (updateViaGps) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, updateInterval, 0, gpsLocationListener);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
                }
            }
        } catch (Exception e) {
            // Provider may not exist, so process it friendly
            e.printStackTrace();
            return false;
        }

        return true;
    }


    @Override
    public void onDestroy() {
        locationManager.removeUpdates(networkLocationListener);
        locationManager.removeUpdates(gpsLocationListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
        }
        started = false;

        super.onDestroy();
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent inputIntent, int flags, int startId) {
        boolean legacyGpsFlag = updateViaGps;
        if (inputIntent != null && inputIntent.getAction() != null) {
            if (inputIntent.getAction().equals(ACTION_STOP)) {
                // Stop service
                started = false;
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            } else if (inputIntent.getAction().equals(ACTION_UPDATE_GPS)) {
                updateViaGps = true;
            } else {
                updateViaGps = false;
            }
        } else {
            updateViaGps = false;
        }
        if (!started || legacyGpsFlag != updateViaGps) {
            if (!requestLocationUpdates()) {
                // No permissions!
                started = false;
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            }
        }
        if (!started) {
            startAsForeground();
            started = true;
        }
        return START_STICKY;
    }
}
