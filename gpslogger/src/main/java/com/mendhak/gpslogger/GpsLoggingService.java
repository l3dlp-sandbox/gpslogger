/*
 * Copyright (C) 2016 mendhak
 *
 * This file is part of GPSLogger for Android.
 *
 * GPSLogger for Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * GPSLogger for Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GPSLogger for Android.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mendhak.gpslogger;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.AlarmManagerCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;
import android.text.Html;
import com.mendhak.gpslogger.common.*;
import com.mendhak.gpslogger.common.events.CommandEvents;
import com.mendhak.gpslogger.common.events.ProfileEvents;
import com.mendhak.gpslogger.common.events.ServiceEvents;
import com.mendhak.gpslogger.common.network.ConscryptProviderInstaller;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.common.slf4j.SessionLogcatAppender;
import com.mendhak.gpslogger.loggers.FileLoggerFactory;
import com.mendhak.gpslogger.loggers.Files;
import com.mendhak.gpslogger.loggers.nmea.NmeaFileLogger;
import com.mendhak.gpslogger.senders.AlarmReceiver;
import com.mendhak.gpslogger.senders.FileSenderFactory;
import de.greenrobot.event.EventBus;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

@SuppressLint("MissingPermission")
public class GpsLoggingService extends Service  {
    private static NotificationManager notificationManager;
    private final IBinder binder = new GpsLoggingBinder();
    AlarmManager nextPointAlarmManager;
    private NotificationCompat.Builder nfc;

    private static final Logger LOG = Logs.of(GpsLoggingService.class);

    // ---------------------------------------------------
    // Helpers and managers
    // ---------------------------------------------------
    private PreferenceHelper preferenceHelper = PreferenceHelper.getInstance();
    private Session session = Session.getInstance();
    protected LocationManager gpsLocationManager;
    private LocationManager passiveLocationManager;
    private LocationManager towerLocationManager;
    private GeneralLocationListener gpsLocationListener;
    private GnssStatus.Callback gnssStatusCallback;
    private GeneralLocationListener towerLocationListener;
    private GeneralLocationListener passiveLocationListener;
    private NmeaLocationListener nmeaLocationListener;
    private Intent alarmIntent;
    private Handler handler = new Handler();

    // ---------------------------------------------------


    @Override
    public IBinder onBind(Intent arg0) {
        return binder;
    }

    @Override
    public void onCreate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationChannelNames.GPSLOGGER_DEFAULT_NOTIFICATION_ID, getNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            }
            else {
                startForeground(NotificationChannelNames.GPSLOGGER_DEFAULT_NOTIFICATION_ID, getNotification());
            }
        } catch (Exception ex) {
            LOG.error("Could not start GPSLoggingService in foreground. ", ex);
        }

        nextPointAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        registerEventBus();
        registerConscryptProvider();
    }


    private void registerConscryptProvider(){
        ConscryptProviderInstaller.installIfNeeded(this);
    }

    private void registerEventBus() {
        EventBus.getDefault().registerSticky(this);
    }

    private void unregisterEventBus(){
        try {
            EventBus.getDefault().unregister(this);
        } catch (Throwable t){
            //this may crash if registration did not go through. just be safe
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationChannelNames.GPSLOGGER_DEFAULT_NOTIFICATION_ID, getNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            }
            else {
                startForeground(NotificationChannelNames.GPSLOGGER_DEFAULT_NOTIFICATION_ID, getNotification());
            }
        } catch (Exception ex) {
            LOG.error("Could not start GPSLoggingService in foreground. ", ex);
        }

        if(session.isStarted() && gpsLocationListener == null && towerLocationListener == null && passiveLocationListener == null) {
            if(Systems.hasUserGrantedAllNecessaryPermissions(this)){
                LOG.warn("App might be recovering from an unexpected stop.  Starting logging again.");
                startLogging();
            }

        }

        handleIntent(intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        LOG.warn(SessionLogcatAppender.MARKER_INTERNAL, "GpsLoggingService is being destroyed by Android OS.");
        unregisterEventBus();
        removeNotification();
        super.onDestroy();

        if(session.isStarted()){
            LOG.error("Service unexpectedly destroyed while GPSLogger was running. Will send broadcast to RestarterReceiver.");
            Intent broadcastIntent = new Intent(getApplicationContext(), RestarterReceiver.class);
            broadcastIntent.putExtra("was_running", true);
            sendBroadcast(broadcastIntent);
        }
    }

    @Override
    public void onLowMemory() {
        LOG.error("Android is low on memory!");
        Intent i = new Intent(this, GpsLoggingService.class);
        i.putExtra(IntentConstants.GET_NEXT_POINT, true);
        PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        nextPointAlarmManager.cancel(pi);
        nextPointAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 300000, pi);
        super.onLowMemory();
    }

    private void handleIntent(Intent intent) {

        if (intent != null) {
            Bundle bundle = intent.getExtras();

            if (bundle != null) {

                if(!Systems.locationPermissionsGranted(this)){
                    LOG.error("User has not granted permission to access location services. Will not continue!");
                    Systems.showErrorNotification(this, getString(R.string.gpslogger_permissions_permanently_denied));
                    return;
                }

                boolean needToStartGpsManager = false;

                if (bundle.getBoolean(IntentConstants.IMMEDIATE_START)) {
                    LOG.info("Intent received - Start Logging Now");
                    EventBus.getDefault().post(new CommandEvents.RequestStartStop(true));
                }

                if (bundle.getBoolean(IntentConstants.IMMEDIATE_STOP)) {
                    LOG.info("Intent received - Stop logging now");
                    EventBus.getDefault().post(new CommandEvents.RequestStartStop(false));
                }

                if (bundle.getBoolean(IntentConstants.GET_STATUS)) {
                    LOG.info("Intent received - Sending Status by broadcast");
                    EventBus.getDefault().post(new CommandEvents.GetStatus());
                }


                if (bundle.getBoolean(IntentConstants.AUTOSEND_NOW)) {
                    LOG.info("Intent received - Auto Send Now");
                    EventBus.getDefault().post(new CommandEvents.AutoSend(null));
                }

                if (bundle.getBoolean(IntentConstants.GET_NEXT_POINT)) {
                    LOG.info("Intent received - Get Next Point");
                    needToStartGpsManager = true;
                }

                if (bundle.getString(IntentConstants.SET_DESCRIPTION) != null) {
                    LOG.info("Intent received - Set Next Point Description: " + bundle.getString(IntentConstants.SET_DESCRIPTION));
                    EventBus.getDefault().post(new CommandEvents.Annotate(bundle.getString(IntentConstants.SET_DESCRIPTION)));
                }

                if(bundle.getString(IntentConstants.SWITCH_PROFILE) != null){
                    LOG.info("Intent received - switch profile: " + bundle.getString(IntentConstants.SWITCH_PROFILE));
                    EventBus.getDefault().post(new ProfileEvents.SwitchToProfile(bundle.getString(IntentConstants.SWITCH_PROFILE)));
                    needToStartGpsManager = session.isStarted();
                }

                if (bundle.get(IntentConstants.PREFER_CELLTOWER) != null) {
                    boolean preferCellTower = bundle.getBoolean(IntentConstants.PREFER_CELLTOWER);
                    LOG.debug("Intent received - Set Prefer Cell Tower: " + String.valueOf(preferCellTower));

                    if(preferCellTower){
                        preferenceHelper.setShouldLogNetworkLocations(true);
                        preferenceHelper.setShouldLogSatelliteLocations(false);
                    } else {
                        preferenceHelper.setShouldLogSatelliteLocations(true);
                        preferenceHelper.setShouldLogNetworkLocations(false);
                    }

                    needToStartGpsManager = true;
                }

                if (bundle.get(IntentConstants.TIME_BEFORE_LOGGING) != null) {
                    int timeBeforeLogging = bundle.getInt(IntentConstants.TIME_BEFORE_LOGGING);
                    LOG.debug("Intent received - logging interval: " + String.valueOf(timeBeforeLogging));
                    preferenceHelper.setMinimumLoggingInterval(timeBeforeLogging);
                    needToStartGpsManager = true;
                }

                if (bundle.get(IntentConstants.DISTANCE_BEFORE_LOGGING) != null) {
                    int distanceBeforeLogging = bundle.getInt(IntentConstants.DISTANCE_BEFORE_LOGGING);
                    LOG.debug("Intent received - Set Distance Before Logging: " + String.valueOf(distanceBeforeLogging));
                    preferenceHelper.setMinimumDistanceInMeters(distanceBeforeLogging);
                    needToStartGpsManager = true;
                }

                if (bundle.get(IntentConstants.GPS_ON_BETWEEN_FIX) != null) {
                    boolean keepBetweenFix = bundle.getBoolean(IntentConstants.GPS_ON_BETWEEN_FIX);
                    LOG.debug("Intent received - Set Keep Between Fix: " + String.valueOf(keepBetweenFix));
                    preferenceHelper.setShouldKeepGPSOnBetweenFixes(keepBetweenFix);
                    needToStartGpsManager = true;
                }

                if (bundle.get(IntentConstants.RETRY_TIME) != null) {
                    int retryTime = bundle.getInt(IntentConstants.RETRY_TIME);
                    LOG.debug("Intent received - Set duration to match accuracy: " + String.valueOf(retryTime));
                    preferenceHelper.setLoggingRetryPeriod(retryTime);
                    needToStartGpsManager = true;
                }

                if (bundle.get(IntentConstants.ABSOLUTE_TIMEOUT) != null) {
                    int absoluteTimeout = bundle.getInt(IntentConstants.ABSOLUTE_TIMEOUT);
                    LOG.debug("Intent received - Set absolute timeout: " + String.valueOf(absoluteTimeout));
                    preferenceHelper.setAbsoluteTimeoutForAcquiringPosition(absoluteTimeout);
                    needToStartGpsManager = true;
                }

                if (bundle.get(IntentConstants.PASSIVE_FILTER_INTERVAL) != null) {
                    int passiveFilterInterval = bundle.getInt(IntentConstants.PASSIVE_FILTER_INTERVAL);
                    LOG.debug("Intent received - Set passive filter interval: " + String.valueOf(passiveFilterInterval));
                    preferenceHelper.setPassiveFilterInterval(passiveFilterInterval);
                    needToStartGpsManager = true;
                }
                
                if(bundle.get(IntentConstants.LOG_ONCE) != null){
                    boolean logOnceIntent = bundle.getBoolean(IntentConstants.LOG_ONCE);
                    LOG.debug("Intent received - Log Once: " + String.valueOf(logOnceIntent));
                    needToStartGpsManager = false;
                    logOnce();
                }

                try {
                    if(bundle.containsKey(Intent.EXTRA_ALARM_COUNT) && bundle.get(Intent.EXTRA_ALARM_COUNT) != "0"){
                        needToStartGpsManager = true;
                    }
                }
                catch (Throwable t){
                    LOG.warn(SessionLogcatAppender.MARKER_INTERNAL, "Received a weird EXTRA_ALARM_COUNT value. Cannot continue.");
                    needToStartGpsManager = false;
                }

                if (needToStartGpsManager && session.isStarted()) {
                    startGpsManager();
                }
            }

        } else {
            // A null intent is passed in if the service has been killed and restarted.
            LOG.debug("Service restarted with null intent. Were we logging previously - " + session.isStarted());
            if(session.isStarted()){
                startLogging();
            }

        }
    }

    /**
     * Sets up the auto email timers based on user preferences.
     */
    public void setupAutoSendTimers() {

        if (preferenceHelper.isAutoSendEnabled() && session.getAutoSendDelay() > 0 && session.isStarted()) {
            LOG.debug("Setting up autosend timers. Auto Send Enabled - " + String.valueOf(preferenceHelper.isAutoSendEnabled())
                    + ", Auto Send Delay - " + String.valueOf(session.getAutoSendDelay()));

            long triggerTime = SystemClock.elapsedRealtime() + (long) (session.getAutoSendDelay() * 60 * 1000);

            alarmIntent = new Intent(this, AlarmReceiver.class);
            cancelAlarm();

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent sender = PendingIntent.getBroadcast(this, 0, alarmIntent, flags);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if(AlarmManagerCompat.canScheduleExactAlarms(am)){
                AlarmManagerCompat.setExactAndAllowWhileIdle(am, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, sender);
            } else {
                AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, sender);
            }
            LOG.debug("Autosend alarm has been set");

        } else {
            if (alarmIntent != null) {
                LOG.debug("Canceling auto-send alarm");
                cancelAlarm();
            }
        }
    }


    public void logOnce() {
        session.setSinglePointMode(true);

        if (session.isStarted()) {
            startGpsManager();
        } else {
            startLogging();
        }
    }

    private void cancelAlarm() {
        if (alarmIntent != null) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent sender = PendingIntent.getBroadcast(this, 0, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            am.cancel(sender);
        }
    }

    /**
     * Method to be called if user has chosen to auto email log files when he
     * stops logging
     */
    private void autoSendLogFileOnStop() {
        if (preferenceHelper.isAutoSendEnabled() && preferenceHelper.shouldAutoSendOnStopLogging()) {
            autoSendLogFile(null);
        }
    }

    /**
     * Calls the Auto Senders which process the files and send it.
     */
    private void autoSendLogFile(@Nullable String formattedFileName) {

        LOG.debug("Filename: " + formattedFileName);

        if ( !Strings.isNullOrEmpty(formattedFileName) || !Strings.isNullOrEmpty(Strings.getFormattedFileName()) ) {
            String fileToSend = Strings.isNullOrEmpty(formattedFileName) ? Strings.getFormattedFileName() : formattedFileName;
            FileSenderFactory.autoSendFiles(fileToSend);
            setupAutoSendTimers();
        }
    }

    private void resetAutoSendTimersIfNecessary() {

        if (session.getAutoSendDelay() != preferenceHelper.getAutoSendInterval()) {
            session.setAutoSendDelay(preferenceHelper.getAutoSendInterval());
            setupAutoSendTimers();
        }
    }

    /**
     * Resets the form, resets file name if required, reobtains preferences
     */
    protected void startLogging() {
        LOG.debug(".");
        session.setAddNewTrackSegment(true);



        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationChannelNames.GPSLOGGER_DEFAULT_NOTIFICATION_ID, getNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            }
            else {
                startForeground(NotificationChannelNames.GPSLOGGER_DEFAULT_NOTIFICATION_ID, getNotification());
            }
        } catch (Exception ex) {
            LOG.error("Could not start GPSLoggingService in foreground. ", ex);
        }

        session.setStarted(true);

        resetAutoSendTimersIfNecessary();
        showNotification();
        setupAutoSendTimers();
        setupSignificantMotionSensor();
        resetCurrentFileName(true);
        notifyClientsStarted(true);
        startPassiveManager();
        startGpsManager();

    }


    private TriggerEventListener triggerEventListener;

    private void cancelAndAgainSetupSignificantMotionSensor() {
        // Sometimes, after a long period of the significant motion sensor NOT being triggered,
        // even if some motion occurs, the sensor just fails to trigger.
        // https://issuetracker.google.com/issues/259298110
        // Here, let's cancel the existing sensor then set it up again,
        // without resetting the userStillSinceTimeStamp.

        if(!preferenceHelper.shouldLogOnlyIfSignificantMotion()) {
            return;
        }

        SensorManager sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        Sensor significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
        if(significantMotionSensor != null) {
            sensorManager.cancelTriggerSensor(triggerEventListener, significantMotionSensor);
        }
        triggerEventListener = null;
        setupSignificantMotionSensor();
    }
    private void setupSignificantMotionSensor(){

        if(!preferenceHelper.shouldLogOnlyIfSignificantMotion()) {
            return;
        }

        SensorManager sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        Sensor significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);

        if(significantMotionSensor != null) {
            LOG.debug("Setting up significant motion sensor manager");

            if(triggerEventListener == null){
                // Brand new listener
                triggerEventListener = new TriggerEventListener() {
                    @Override
                    public void onTrigger(TriggerEvent event) {
                        LOG.debug("Motion detected.");
                        session.setUserStillSinceTimeStamp(System.currentTimeMillis());
                    }
                };
            }
            else {
                // Called when a location has been found
                session.setUserStillSinceTimeStamp(System.currentTimeMillis());
            }

            sensorManager.cancelTriggerSensor(triggerEventListener, significantMotionSensor);
            sensorManager.requestTriggerSensor(triggerEventListener, significantMotionSensor);
            session.setSignificantMotionSensorCreationTimeStamp(System.currentTimeMillis());
        }
    }

    private void notifyByBroadcast(boolean loggingStarted) {
            LOG.debug("Sending a started/stopped broadcast");
            String event = (loggingStarted) ? "started" : "stopped";
            Intent sendIntent = new Intent();
            sendIntent.setAction("com.mendhak.gpslogger.EVENT");
            sendIntent.putExtra("gpsloggerevent", event); // started, stopped
            sendIntent.putExtra("filename", session.getCurrentFormattedFileName());
            sendIntent.putExtra("startedtimestamp", session.getStartTimeStamp());
            sendIntent.putExtra("duration", (int) (System.currentTimeMillis() - session.getStartTimeStamp()) / 1000);
            sendIntent.putExtra("distance", session.getTotalTravelled());

            sendBroadcast(sendIntent);
    }

    /**
     * Informs main activity and broadcast listeners whether logging has started/stopped
     */
    private void notifyClientsStarted(boolean started) {
        LOG.info((started)? getString(R.string.started) : getString(R.string.stopped));
        notifyByBroadcast(started);
        EventBus.getDefault().post(new ServiceEvents.LoggingStatus(started));
    }

    /**
     * Notify status of logger
     */
    private void notifyStatus(boolean started) {
        LOG.info((started)? getString(R.string.started) : getString(R.string.stopped));
        notifyByBroadcast(started);
    }

    /**
     * Stops logging, removes notification, stops GPS manager, stops email timer
     */
    public void stopLogging() {
        LOG.debug(".");
        session.setAddNewTrackSegment(true);
        session.setTotalTravelled(0);
        session.setPreviousLocationInfo(null);
        session.setStarted(false);
        session.setUserStillSinceTimeStamp(0);
        session.setLatestTimeStamp(0);
        stopAbsoluteTimer();
        // Email log file before setting location info to null
        autoSendLogFileOnStop();
        cancelAlarm();
        session.setCurrentLocationInfo(null);
        session.setSinglePointMode(false);
        stopForeground(true);
        stopSelf();

        removeNotification();
        stopAlarm();
        stopGpsManager();
        stopPassiveManager();

        notifyClientsStarted(false);
        session.setCurrentFileName("");
        session.setCurrentFormattedFileName("");
    }

    /**
     * Hides the notification icon in the status bar if it's visible.
     */
    private void removeNotification() {
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    /**
     * Shows a notification icon in the status bar for GPS Logger
     */
    private Notification getNotification() {

        Intent stopLoggingIntent = new Intent(this, GpsLoggingService.class);
        stopLoggingIntent.setAction("NotificationButton_STOP");
        stopLoggingIntent.putExtra(IntentConstants.IMMEDIATE_STOP, true);
        PendingIntent piStop = PendingIntent.getService(this, 0, stopLoggingIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent annotateIntent = new Intent(this, NotificationAnnotationActivity.class);
        annotateIntent.setAction("com.mendhak.gpslogger.NOTIFICATION_BUTTON");
        PendingIntent piAnnotate = PendingIntent.getActivity(this,0, annotateIntent, PendingIntent.FLAG_IMMUTABLE);

        // What happens when the notification item is clicked
        Intent contentIntent = new Intent(this, GpsMainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addNextIntent(contentIntent);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = stackBuilder.getPendingIntent(0, flags);

        CharSequence contentTitle = getString(R.string.gpslogger_still_running);
        CharSequence contentText = getString(R.string.app_name);
        long notificationTime = System.currentTimeMillis();

        if (session.hasValidLocation()) {
            contentTitle = Strings.getFormattedLatitude(session.getCurrentLatitude()) + ", "
                    + Strings.getFormattedLongitude(session.getCurrentLongitude());

            contentText = Html.fromHtml("<b>" + getString(R.string.txt_altitude) + "</b> " + Strings.getDistanceDisplay(this,session.getCurrentLocationInfo().getAltitude(), preferenceHelper.shouldDisplayImperialUnits(), false)
                    + "  "
                    + "<b>" + getString(R.string.txt_travel_duration) + "</b> "  + Strings.getDescriptiveDurationString((int) (System.currentTimeMillis() - session.getStartTimeStamp()) / 1000, this)
                    + "  "
                    + "<b>" + getString(R.string.txt_accuracy) + "</b> "  + Strings.getDistanceDisplay(this, session.getCurrentLocationInfo().getAccuracy(), preferenceHelper.shouldDisplayImperialUnits(), true));

            notificationTime = session.getCurrentLocationInfo().getTime();
        }

        if (nfc == null) {

            nfc = new NotificationCompat.Builder(getApplicationContext(), NotificationChannelNames.GPSLOGGER_DEFAULT)
                    .setSmallIcon(R.drawable.notification)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.gpsloggericon3))
                    .setPriority( preferenceHelper.shouldHideNotificationFromStatusBar() ? NotificationCompat.PRIORITY_MIN : NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setVisibility(preferenceHelper.shouldHideNotificationFromLockScreen() ? NotificationCompat.VISIBILITY_SECRET : NotificationCompat.VISIBILITY_PUBLIC) //This hides the notification from lock screen
                    .setContentTitle(contentTitle)
                    .setContentText(contentText)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText).setBigContentTitle(contentTitle))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pending);

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                nfc.setPriority(NotificationCompat.PRIORITY_LOW);
            }

            if(!preferenceHelper.shouldHideNotificationButtons()){
                nfc.addAction(R.drawable.annotate2, getString(R.string.menu_annotate), piAnnotate)
                        .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.shortcut_stop), piStop);
            }
        }

        nfc.setContentTitle(contentTitle);
        nfc.setContentText(contentText);
        nfc.setStyle(new NotificationCompat.BigTextStyle().bigText(contentText).setBigContentTitle(contentTitle));
        nfc.setWhen(notificationTime);

        //notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        //notificationManager.notify(NotificationChannelNames.GPSLOGGER_DEFAULT_ID, nfc.build());
        return nfc.build();
    }

    private void showNotification(){
        Notification notif = getNotification();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NotificationChannelNames.GPSLOGGER_DEFAULT_NOTIFICATION_ID, notif);
    }

    @SuppressWarnings("ResourceType")
    private void startPassiveManager() {
        if(preferenceHelper.shouldLogPassiveLocations()){
            LOG.debug("Starting passive location listener");
            if(passiveLocationListener== null){
                passiveLocationListener = new GeneralLocationListener(this, BundleConstants.PASSIVE);
            }
            passiveLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            passiveLocationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000, 0, passiveLocationListener);
        }
    }


    /**
     * Starts the location manager. There are two location managers - GPS and
     * Cell Tower. This code determines which manager to request updates from
     * based on user preference and whichever is enabled. If GPS is enabled on
     * the phone, that is used. But if the user has also specified that they
     * prefer cell towers, then cell towers are used. If neither is enabled,
     * then nothing is requested.
     */
    @SuppressWarnings("ResourceType")
    private void startGpsManager() {

        //If the user has been still for more than the minimum seconds
        if(userHasBeenStillForTooLong()) {

            if(preferenceHelper.shouldLogOnlyIfSignificantMotion() && session.getUserStillSinceTimeStamp() > 0){
                long howLongSinceSignificantMotionSensorCreated = System.currentTimeMillis() - session.getSignificantMotionSensorCreationTimeStamp();
                if(howLongSinceSignificantMotionSensorCreated > 5 * 60 * 1000){
                    LOG.debug("Significant motion sensor has been created for more than 5 minutes, resetting sensor.");
                    cancelAndAgainSetupSignificantMotionSensor();
                }
            }

            LOG.info("No movement detected in the past interval, will not log");
            setAlarmForNextPoint();
            return;
        }

        if (gpsLocationListener == null) {
            gpsLocationListener = new GeneralLocationListener(this, "GPS");
        }

        if (towerLocationListener == null) {
            towerLocationListener = new GeneralLocationListener(this, "CELL");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onStarted() {
                    super.onStarted();
                }

                @Override
                public void onStopped() {
                    super.onStopped();
                }

                @Override
                public void onFirstFix(int ttffMillis) {
                    super.onFirstFix(ttffMillis);
                    LOG.info("Time to first fix: {}ms", ttffMillis);
                }

                @Override
                public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                    super.onSatelliteStatusChanged(status);
                    int satellitesUsedInFixCount = 0;
                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        if (status.usedInFix(i)) {
                            satellitesUsedInFixCount++;
                        }
                    }
                    // This is just for display
                    setSatelliteInfo(status.getSatelliteCount());
                    LOG.debug(String.valueOf(status.getSatelliteCount()) + " satellites visible");

                    // This will be used later as part of the fix in location object.
                    gpsLocationListener.satellitesUsedInFix = satellitesUsedInFixCount;
                    LOG.debug(String.valueOf(satellitesUsedInFixCount) + " satellites used in fix");
                }
            };
        }


        gpsLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        towerLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        checkTowerAndGpsStatus();

        if (session.isGpsEnabled() && preferenceHelper.shouldLogSatelliteLocations()) {
            LOG.info("Requesting GPS location updates");
            // gps satellite based
            gpsLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsLocationListener);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gpsLocationManager.registerGnssStatusCallback(gnssStatusCallback);
            }
            else {
                gpsLocationManager.addGpsStatusListener(gpsLocationListener);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (nmeaLocationListener == null){
                    //This Nmea listener just wraps the gps listener.
                    nmeaLocationListener = new NmeaLocationListener(gpsLocationListener);
                }
                gpsLocationManager.addNmeaListener(nmeaLocationListener, null);
            }
            else {
                gpsLocationManager.addNmeaListener(gpsLocationListener);
            }


            session.setUsingGps(true);
            startAbsoluteTimer();
        }

        if (session.isTowerEnabled() &&  ( preferenceHelper.shouldLogNetworkLocations() || !session.isGpsEnabled() ) ) {
            LOG.info("Requesting cell and wifi location updates");
            session.setUsingGps(false);
            // Cell tower and wifi based
            towerLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, towerLocationListener);

            startAbsoluteTimer();
        }

        if(!session.isTowerEnabled() && !session.isGpsEnabled()) {
            LOG.error("No provider available!");
            session.setUsingGps(false);
            LOG.error(getString(R.string.gpsprovider_unavailable));
            // Let the app check again, whether location services have returned, after the absolute-timer time has passed.
            startAbsoluteTimer();
            setLocationServiceUnavailable(true);
            return;
        } else {
            setLocationServiceUnavailable(false);
        }

        if(!preferenceHelper.shouldLogNetworkLocations() && !preferenceHelper.shouldLogSatelliteLocations() && !preferenceHelper.shouldLogPassiveLocations()){
            LOG.error("No location provider selected!");
            session.setUsingGps(false);
            stopLogging();
            return;
        }

        EventBus.getDefault().post(new ServiceEvents.WaitingForLocation(true));
        session.setWaitingForLocation(true);
    }

    private boolean userHasBeenStillForTooLong() {
        if(!preferenceHelper.shouldLogOnlyIfSignificantMotion()){
            return false;
        }
        return !session.hasDescription() && !session.isSinglePointMode() &&
                (session.getUserStillSinceTimeStamp() > 0 && (System.currentTimeMillis() - session.getUserStillSinceTimeStamp()) > (preferenceHelper.getMinimumLoggingInterval() * 1000));
    }

    private void startAbsoluteTimer() {
        if (preferenceHelper.getAbsoluteTimeoutForAcquiringPosition() >= 1) {
            handler.postDelayed(stopManagerRunnable, preferenceHelper.getAbsoluteTimeoutForAcquiringPosition() * 1000);
        }
    }

    private Runnable stopManagerRunnable = new Runnable() {
        @Override
        public void run() {
            LOG.warn("Absolute timeout reached, giving up on this point");
            stopManagerAndResetAlarm();
        }
    };

    private void stopAbsoluteTimer() {
        handler.removeCallbacks(stopManagerRunnable);
    }

    /**
     * This method is called periodically to determine whether the cell tower /
     * gps providers have been enabled, and sets class level variables to those
     * values.
     */
    private void checkTowerAndGpsStatus() {
        session.setTowerEnabled(towerLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        session.setGpsEnabled(gpsLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER));
    }

    /**
     * Stops the location managers
     */
    @SuppressWarnings("ResourceType")
    private void stopGpsManager() {

        if (towerLocationListener != null) {
            LOG.debug("Removing towerLocationManager updates");
            towerLocationManager.removeUpdates(towerLocationListener);
        }

        if (gpsLocationListener != null) {
            LOG.debug("Removing gpsLocationManager updates");
            gpsLocationManager.removeUpdates(gpsLocationListener);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
            gpsLocationManager.unregisterGnssStatusCallback(gnssStatusCallback);
        }
        else {
            gpsLocationManager.removeGpsStatusListener(gpsLocationListener);
        }

        session.setWaitingForLocation(false);
        EventBus.getDefault().post(new ServiceEvents.WaitingForLocation(false));

    }

    @SuppressWarnings("ResourceType")
    private void stopPassiveManager(){
        if(passiveLocationManager!=null){
            LOG.debug("Removing passiveLocationManager updates");
            passiveLocationManager.removeUpdates(passiveLocationListener);
        }
    }

    /**
     * Sets the current file name based on user preference.
     */
    private void resetCurrentFileName(boolean newLogEachStart) {

        String oldFileName = session.getCurrentFormattedFileName();

        /* Update the file name, if required. (New day, Re-start service) */
        if (preferenceHelper.shouldCreateCustomFile()) {
            if(Strings.isNullOrEmpty(Strings.getFormattedFileName())){
                session.setCurrentFileName(preferenceHelper.getCustomFileName());
            }

            LOG.debug("Should change file name dynamically: " + preferenceHelper.shouldChangeFileNameDynamically());

            if(!preferenceHelper.shouldChangeFileNameDynamically()){
                session.setCurrentFileName(Strings.getFormattedFileName());
            }

        } else if (preferenceHelper.shouldCreateNewFileOnceAMonth()) {
            // 201001.gpx
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMM");
            session.setCurrentFileName(sdf.format(new Date()));
        } else if (preferenceHelper.shouldCreateNewFileOnceADay()) {
            // 20100114.gpx
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            session.setCurrentFileName(sdf.format(new Date()));
        } else if (newLogEachStart) {
            // 20100114183329.gpx
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
            session.setCurrentFileName(sdf.format(new Date()));
        }

        if(!Strings.isNullOrEmpty(oldFileName)
                && !oldFileName.equalsIgnoreCase(Strings.getFormattedFileName())
                && session.isStarted()){
            LOG.debug("New file name, should auto upload the old one");
            EventBus.getDefault().post(new CommandEvents.AutoSend(oldFileName));
        }

        session.setCurrentFormattedFileName(Strings.getFormattedFileName());

        LOG.info("Filename: " + Strings.getFormattedFileName());
        EventBus.getDefault().post(new ServiceEvents.FileNamed(Strings.getFormattedFileName()));

    }



    void setLocationServiceUnavailable(boolean unavailable){
        session.setLocationServiceUnavailable(unavailable);
        EventBus.getDefault().post(new ServiceEvents.LocationServicesUnavailable());
    }

    /**
     * Stops location manager, then starts it.
     */
    void restartGpsManagers() {
        LOG.debug("Restarting location managers");
        stopGpsManager();
        startGpsManager();
    }

    /**
     * This event is raised when the GeneralLocationListener has a new location.
     * This method in turn updates notification, writes to file, reobtains
     * preferences, notifies main service client and resets location managers.
     *
     * @param loc Location object
     */
    void onLocationChanged(Location loc) {
        if (!session.isStarted()) {
            LOG.debug("onLocationChanged called, but session.isStarted is false");
            stopLogging();
            return;
        }

        boolean isPassiveLocation = loc.getExtras().getBoolean(BundleConstants.PASSIVE);
        long currentTimeStamp = System.currentTimeMillis();

        LOG.debug("Has description? " + session.hasDescription() + ", Single point? " + session.isSinglePointMode() + ", Last timestamp: " + session.getLatestTimeStamp() + ", Current timestamp: " + currentTimeStamp);

        // Sometimes we are given a cached location whose time is in the past and already logged.
        if (session.getPreviousLocationInfo() != null && loc.getTime() <= session.getPreviousLocationInfo().getTime()){
            LOG.debug("Received a stale location, its time was less than or equal to a previous point. Ignoring.");
            return;
        }

        // Don't log a point until the user-defined time has elapsed
        // However, if user has set an annotation, just log the point, disregard time and distance filters
        // However, if it's a passive location, disregard the time filter
        if (!isPassiveLocation && !session.hasDescription()
                && !session.isSinglePointMode()
                && (currentTimeStamp - session.getLatestTimeStamp()) < (preferenceHelper.getMinimumLoggingInterval() * 1000)) {
            LOG.debug("Received location, but minimum logging interval has not passed. Ignoring.");
            return;
        }

        // Even if it's a passive location, the time should be greater than the previous location's time.
        if(isPassiveLocation && session.getPreviousLocationInfo() != null && loc.getTime() <= session.getPreviousLocationInfo().getTime()){
            LOG.debug("Passive location time: " + loc.getTime() + ", previous location's time: " + session.getPreviousLocationInfo().getTime());
            LOG.debug("Passive location received, but its time was less than the previous point's time.");
            return;
        }

        // Even if it's a passive location, the loc.getTime() - session.getLatestPassiveTimeStamp()  should be greater than the previous getPassiveFilterInterval's time.
        if(isPassiveLocation && session.getPreviousLocationInfo() != null ){
            if  ((loc.getTime() - session.getLatestPassiveTimeStamp()) < (preferenceHelper.getPassiveFilterInterval() * 1000)) {
                LOG.debug("Passive location discarded; interval set=>{}ms old=>{}ms now=>{}ms Filter",
                        (preferenceHelper.getPassiveFilterInterval() * 1000),
                        session.getLatestPassiveTimeStamp(),
                        loc.getTime() );
                return;
            }
            //If passed, save LatestPassiveTimeStamp.
            session.setLatestPassiveTimeStamp(loc.getTime());
        }



        // Check that it's a user selected valid listener, even if it's a passive location.
        // In other words, if user wants satellite only, then don't log passive network locations.
        if(!isFromSelectedListener(loc)){
            LOG.debug("Received location, but it's not from a selected listener. Ignoring.");
            return;
        }

        //Check if a ridiculous distance has been travelled since previous point - could be a bad GPS jump
        if(session.getCurrentLocationInfo() != null){
            double distanceTravelled = Maths.calculateDistance(loc.getLatitude(), loc.getLongitude(), session.getCurrentLocationInfo().getLatitude(), session.getCurrentLocationInfo().getLongitude());
            long timeDifference = (int)Math.abs(loc.getTime() - session.getCurrentLocationInfo().getTime())/1000;

            if( timeDifference > 0 && (distanceTravelled/timeDifference) > 357){ //357 m/s ~=  1285 km/h
                LOG.warn(String.format("Very large jump detected - %d meters in %d sec - discarding point", (long)distanceTravelled, timeDifference));
                return;
            }
        }

        // Don't do anything until the user-defined accuracy is reached
        // even for annotations
        if (preferenceHelper.getMinimumAccuracy() > 0) {

            if(!loc.hasAccuracy() || loc.getAccuracy() == 0){
                LOG.debug("Received location, but it has no accuracy value. Ignoring.");
                return;
            }

            if (preferenceHelper.getMinimumAccuracy() < Math.abs(loc.getAccuracy())) {

                if(session.getFirstRetryTimeStamp() == 0){
                    session.setFirstRetryTimeStamp(System.currentTimeMillis());
                }

                if (currentTimeStamp - session.getFirstRetryTimeStamp() <= preferenceHelper.getLoggingRetryPeriod() * 1000) {
                    LOG.warn("Only accuracy of " + String.valueOf(loc.getAccuracy()) + " m. Point discarded." + getString(R.string.inaccurate_point_discarded));
                    //return and keep trying
                    return;
                }

                if (currentTimeStamp - session.getFirstRetryTimeStamp() > preferenceHelper.getLoggingRetryPeriod() * 1000) {
                    LOG.warn("Only accuracy of " + String.valueOf(loc.getAccuracy()) + " m and timeout reached." + getString(R.string.inaccurate_point_discarded));
                    //Give up for now
                    stopManagerAndResetAlarm();

                    //reset timestamp for next time.
                    session.setFirstRetryTimeStamp(0);
                    return;
                }

                //Success, reset timestamp for next time.
                session.setFirstRetryTimeStamp(0);
            }
            //If the user wants the best possible accuracy, store the point, only if it's the best so far.
            // Then retry until the time limit is reached.
            // Exception - if it's a passive location, or it's an annotation, or single point mode.
            // I don't think we need to pick the best point in the case of passive locations (not sure).
            else if(preferenceHelper.shouldGetBestPossibleAccuracy() && !isPassiveLocation && !session.hasDescription() && !session.isSinglePointMode()) {

                if(session.getFirstRetryTimeStamp() == 0){
                    //It's the first loop so reset timestamp and temporary location
                    session.setTemporaryLocationForBestAccuracy(null);
                    session.setFirstRetryTimeStamp(System.currentTimeMillis());
                }

                if(session.getTemporaryLocationForBestAccuracy() == null || loc.getAccuracy() < session.getTemporaryLocationForBestAccuracy().getAccuracy()){
                    LOG.debug("New point with accuracy of " + String.valueOf(loc.getAccuracy()) + " m." );
                    session.setTemporaryLocationForBestAccuracy(loc);
                }

                if (currentTimeStamp - session.getFirstRetryTimeStamp() <= preferenceHelper.getLoggingRetryPeriod() * 1000) {
                    // return and keep trying
                    return;
                }

                if (currentTimeStamp - session.getFirstRetryTimeStamp() > preferenceHelper.getLoggingRetryPeriod() * 1000) {
                    // We've reached the end of the retry period, use the best point we've got so far.
                    LOG.debug("Retry timeout reached, using best point so far with accuracy of " + String.valueOf(session.getTemporaryLocationForBestAccuracy().getAccuracy()) + " m.");
                    loc = session.getTemporaryLocationForBestAccuracy();

                    //reset for next time
                    session.setTemporaryLocationForBestAccuracy(null);
                    session.setFirstRetryTimeStamp(0);

                }

            }
        }

        //Don't do anything until the user-defined distance has been traversed
        // However, if user has set an annotation, just log the point, disregard time and distance filters
        // However, if it's a passive location, ignore distance filter.
        if (!isPassiveLocation && !session.hasDescription() && !session.isSinglePointMode() && preferenceHelper.getMinimumDistanceInterval() > 0 && session.hasValidLocation()) {

            double distanceTraveled = Maths.calculateDistance(loc.getLatitude(), loc.getLongitude(),
                    session.getCurrentLatitude(), session.getCurrentLongitude());

            if (preferenceHelper.getMinimumDistanceInterval() > distanceTraveled) {
                LOG.warn(String.format(getString(R.string.not_enough_distance_traveled), String.valueOf(Math.floor(distanceTraveled))) + ", point discarded");
                stopManagerAndResetAlarm();
                return;
            }
        }


        LOG.debug(String.valueOf(loc.getLatitude()) + "," + String.valueOf(loc.getLongitude()));
        LOG.info(SessionLogcatAppender.MARKER_LOCATION, getLocationDisplayForLogs(loc));
        loc = Locations.getLocationWithAdjustedAltitude(loc, preferenceHelper);
        loc = Locations.getLocationAdjustedForGPSWeekRollover(loc);
        resetCurrentFileName(false);
        session.setLatestTimeStamp(System.currentTimeMillis());
        session.setFirstRetryTimeStamp(0);
        session.setCurrentLocationInfo(loc);
        setDistanceTraveled(loc);
        showNotification();

        if(isPassiveLocation){
            LOG.debug("Logging passive location to file");
        }

        writeToFile(loc);
        resetAutoSendTimersIfNecessary();
        stopManagerAndResetAlarm();
        setupSignificantMotionSensor();

        EventBus.getDefault().post(new ServiceEvents.LocationUpdate(loc));

        if (session.isSinglePointMode()) {
            LOG.debug("Single point mode - stopping now");
            stopLogging();
        }
    }

    private String getLocationDisplayForLogs(Location loc) {

        StringBuilder logLine = new StringBuilder();
        logLine.append(Strings.getFormattedLatitude(loc.getLatitude()));
        logLine.append(" ");
        logLine.append(Strings.getFormattedLongitude(loc.getLongitude()));
        logLine.append(" ");

        if(!Strings.isNullOrEmpty(loc.getProvider())){
            String provider = loc.getProvider();
            logLine.append("(");
            if (provider.equalsIgnoreCase(LocationManager.GPS_PROVIDER)) {
                logLine.append(getString(R.string.listeners_gps));
            }
            if (provider.equalsIgnoreCase(LocationManager.NETWORK_PROVIDER)) {
                logLine.append(getString(R.string.listeners_cell));
            }
            if(loc.getExtras() != null && loc.getExtras().containsKey(BundleConstants.PASSIVE) && loc.getExtras().getBoolean(BundleConstants.PASSIVE)) {
                logLine.append(getString(R.string.listeners_passive));
            }
            logLine.append(")");
        }



        if(loc.hasAltitude()){
            logLine.append("\n");
            logLine.append(getString(R.string.txt_altitude));
            logLine.append(Strings.getDistanceDisplay(this,loc.getAltitude(), preferenceHelper.shouldDisplayImperialUnits(), false));
        }

        if(loc.hasAccuracy()){
            logLine.append("\n");
            logLine.append(getString(R.string.txt_accuracy));
            logLine.append(Strings.getDistanceDisplay(this, loc.getAccuracy(), preferenceHelper.shouldDisplayImperialUnits(), true));
        }

        return logLine.toString();
    }

    private boolean isFromSelectedListener(Location loc) {

        if(!preferenceHelper.shouldLogSatelliteLocations() && !preferenceHelper.shouldLogNetworkLocations()){
            // Special case - if both satellite and network are deselected, but passive is selected, then accept all passive types!
            return preferenceHelper.shouldLogPassiveLocations();
        }

        if(!preferenceHelper.shouldLogNetworkLocations()){
            return !loc.getProvider().equalsIgnoreCase(LocationManager.NETWORK_PROVIDER);
        }

        if(!preferenceHelper.shouldLogSatelliteLocations()){
            return !loc.getProvider().equalsIgnoreCase(LocationManager.GPS_PROVIDER);
        }

        return true;
    }

    private void setDistanceTraveled(Location loc) {
        // Distance
        if (session.getPreviousLocationInfo() == null) {
            session.setPreviousLocationInfo(loc);
        }
        // Calculate this location and the previous location location and add to the current running total distance.
        // NOTE: Should be used in conjunction with 'distance required before logging' for more realistic values.
        double distance = Maths.calculateDistance(
                session.getPreviousLatitude(),
                session.getPreviousLongitude(),
                loc.getLatitude(),
                loc.getLongitude());
        session.setPreviousLocationInfo(loc);
        session.setTotalTravelled(session.getTotalTravelled() + distance);
    }

    protected void stopManagerAndResetAlarm() {
        if (!preferenceHelper.shouldKeepGPSOnBetweenFixes()) {
            stopGpsManager();
        }

        stopAbsoluteTimer();
        setAlarmForNextPoint();
    }


    private void stopAlarm() {
        Intent i = new Intent(this, GpsLoggingService.class);
        i.putExtra(IntentConstants.GET_NEXT_POINT, true);
        PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_MUTABLE);
        nextPointAlarmManager.cancel(pi);
    }

    private void setAlarmForNextPoint() {
        Intent i = new Intent(this, GpsLoggingService.class);
        i.putExtra(IntentConstants.GET_NEXT_POINT, true);
        PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_MUTABLE);
        nextPointAlarmManager.cancel(pi);

        if(AlarmManagerCompat.canScheduleExactAlarms(nextPointAlarmManager)){
            LOG.debug("Setting exact alarm for {}s", preferenceHelper.getMinimumLoggingInterval());
            AlarmManagerCompat.setExactAndAllowWhileIdle(nextPointAlarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + preferenceHelper.getMinimumLoggingInterval() * 1000L, pi);
        }
        else {
            LOG.debug("Setting inexact alarm for {}s (exact alarm disallowed/not available)", preferenceHelper.getMinimumLoggingInterval());
            AlarmManagerCompat.setAndAllowWhileIdle(nextPointAlarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + preferenceHelper.getMinimumLoggingInterval() * 1000L, pi);
        }
    }


    /**
     * Calls file helper to write a given location to a file.
     *
     * @param loc Location object
     */
    private void writeToFile(Location loc) {
        session.setAddNewTrackSegment(false);

        try {
            LOG.debug("Calling file writers");
            FileLoggerFactory.write(getApplicationContext(), loc);

            if (session.hasDescription()) {
                LOG.info("Writing annotation: " + session.getDescription());
                FileLoggerFactory.annotate(getApplicationContext(), session.getDescription(), loc);
            }
        }
        catch(Exception e){
             LOG.error(getString(R.string.could_not_write_to_file), e);
             Systems.showErrorNotification(this, getString(R.string.could_not_write_to_file));
        }

        session.clearDescription();
        EventBus.getDefault().post(new ServiceEvents.AnnotationStatus(true));
    }

    /**
     * Informs the main service client of the number of visible satellites.
     *
     * @param count Number of Satellites
     */
    void setSatelliteInfo(int count) {
        session.setVisibleSatelliteCount(count);
        EventBus.getDefault().post(new ServiceEvents.SatellitesVisible(count));
    }

    public void onNmeaSentence(long timestamp, String nmeaSentence) {

        if (preferenceHelper.shouldLogToNmea()) {
            NmeaFileLogger nmeaLogger = new NmeaFileLogger(Strings.getFormattedFileName());
            nmeaLogger.write(timestamp, nmeaSentence);
        }
    }

    /**
     * Can be used from calling classes as the go-between for methods and
     * properties.
     */
    public class GpsLoggingBinder extends Binder {
        public GpsLoggingService getService() {
            return GpsLoggingService.this;
        }
    }


    @EventBusHook
    public void onEvent(CommandEvents.RequestToggle requestToggle){
        if (session.isStarted()) {
            stopLogging();
        } else {
            startLogging();
        }
    }

    @EventBusHook
    public void onEvent(CommandEvents.RequestStartStop startStop){
        if(startStop.start){
            startLogging();
        }
        else {
            stopLogging();
        }

        EventBus.getDefault().removeStickyEvent(CommandEvents.RequestStartStop.class);
    }

    @EventBusHook
    public void onEvent(CommandEvents.GetStatus getStatus){
        LOG.debug("GetStatus Event.");

        notifyStatus(session.isStarted());
        EventBus.getDefault().removeStickyEvent(CommandEvents.GetStatus.class);

    }

    @EventBusHook
    public void onEvent(CommandEvents.AutoSend autoSend){
        autoSendLogFile(autoSend.formattedFileName);

        EventBus.getDefault().removeStickyEvent(CommandEvents.AutoSend.class);
    }

    @EventBusHook
    public void onEvent(CommandEvents.Annotate annotate){
        final String desc = annotate.annotation;
        if (desc.length() == 0) {
            LOG.debug("Clearing annotation");
            session.clearDescription();
        } else {
            LOG.debug("Pending annotation: " + desc);
            session.setDescription(desc);
            EventBus.getDefault().post(new ServiceEvents.AnnotationStatus(false));

            if(session.isStarted()){
                startGpsManager();
            }
            else {
                logOnce();
            }
        }

        EventBus.getDefault().removeStickyEvent(CommandEvents.Annotate.class);
    }

    @EventBusHook
    public void onEvent(CommandEvents.LogOnce logOnce){
        logOnce();
    }


    @EventBusHook
    public void onEvent(CommandEvents.FileWriteFailure writeFailure){
        Systems.showErrorNotification(this, getString(R.string.could_not_write_to_file));
        if(writeFailure.stopLoggingDueToNMEA){
            LOG.error("Could not write to NMEA file, stopping logging due to high frequency of write failures");
            stopLogging();
        }
    }

    @EventBusHook
    public void onEvent(ProfileEvents.SwitchToProfile switchToProfileEvent){
        try {

            boolean isCurrentProfile = preferenceHelper.getCurrentProfileName().equals(switchToProfileEvent.newProfileName);

            LOG.debug("Switching to profile: " + switchToProfileEvent.newProfileName);

            if(!isCurrentProfile){
                //Save the current settings to a file (overwrite)
                File f = new File(Files.storageFolder(GpsLoggingService.this), preferenceHelper.getCurrentProfileName()+".properties");
                preferenceHelper.savePropertiesFromPreferences(f);
            }


            //Read from a possibly existing file and load those preferences in
            File newProfile = new File(Files.storageFolder(GpsLoggingService.this), switchToProfileEvent.newProfileName+".properties");
            if(newProfile.exists()){
                preferenceHelper.setPreferenceFromPropertiesFile(newProfile);
            }

            //Switch current profile name
            preferenceHelper.setCurrentProfileName(switchToProfileEvent.newProfileName);
            LOG.info("Switched to profile: " + switchToProfileEvent.newProfileName);

        } catch (IOException e) {
            LOG.error("Could not save profile to file", e);
        }
    }

}
