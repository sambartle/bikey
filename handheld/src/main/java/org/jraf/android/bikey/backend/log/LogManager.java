/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2013 Benoit 'BoD' Lubek (BoD@JRAF.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jraf.android.bikey.backend.log;

import java.util.ArrayList;
import java.util.List;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;

import com.google.android.gms.maps.model.LatLng;

import org.jraf.android.bikey.app.Application;
import org.jraf.android.bikey.backend.location.LocationManager;
import org.jraf.android.bikey.backend.location.LocationPair;
import org.jraf.android.bikey.backend.provider.log.LogColumns;
import org.jraf.android.bikey.backend.provider.log.LogContentValues;
import org.jraf.android.bikey.backend.provider.log.LogCursor;
import org.jraf.android.bikey.backend.provider.log.LogSelection;
import org.jraf.android.bikey.backend.ride.RideManager;
import org.jraf.android.util.listeners.Listeners;
import org.jraf.android.util.log.Log;

public class LogManager {
    private static final LogManager INSTANCE = new LogManager();

    public static LogManager get() {
        return INSTANCE;
    }

    private final Context mContext;
    private Listeners<LogListener> mListeners = Listeners.newInstance();

    private LogManager() {
        mContext = Application.getApplication();
    }

    @WorkerThread
    public Uri add(@NonNull Uri rideUri, Location location, Location previousLocation, Float cadence, Integer heartRate) {
        // Add a log
        LogContentValues values = new LogContentValues();
        long rideId = ContentUris.parseId(rideUri);
        values.putRideId(rideId);
        values.putRecordedDate(location.getTime());
        values.putLat(location.getLatitude());
        values.putLon(location.getLongitude());
        values.putEle(location.getAltitude());
        if (previousLocation != null) {
            LocationPair locationPair = new LocationPair(previousLocation, location);
            float speed = locationPair.getSpeed();
            if (speed < LocationManager.SPEED_MIN_THRESHOLD_M_S) {
                Log.d("Speed under threshold, not logging it");
            } else {
                values.putLogDuration(locationPair.getDuration());
                values.putLogDistance(locationPair.getDistance());
                values.putSpeed(speed);
            }
        }
        values.putCadence(cadence);
        values.putHeartRate(heartRate);

        Uri res = values.insert(mContext);

        // Update total distance for ride
        float totalDistance = getTotalDistance(rideUri);
        RideManager.get().updateTotalDistance(rideUri, totalDistance);

        // Dispatch to listeners
        mListeners.dispatch(listener -> listener.onLogAdded(rideUri));
        return res;
    }

    @WorkerThread
    public float getTotalDistance(@NonNull Uri rideUri) {
        long rideId = ContentUris.parseId(rideUri);
        String[] projection = {"sum(" + LogColumns.LOG_DISTANCE + ")"};
        LogSelection where = new LogSelection();
        where.rideId(rideId);
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToNext()) return 0;
            return c.getFloat(0);
        } finally {
            c.close();
        }
    }

    /**
     * Note: the top 10% points are discarded to account for imprecise values.
     */
    @WorkerThread
    public float getAverageMovingSpeed(@NonNull Uri rideUri) {
        // First get the max
        float max = getMaxSpeed(rideUri);

        long rideId = ContentUris.parseId(rideUri);
        String[] projection = {"sum(" + LogColumns.LOG_DISTANCE + ")/sum(" + LogColumns.LOG_DURATION + ")*1000"};
        LogSelection where = new LogSelection();
        where.rideId(rideId).and().speedGt(LocationManager.SPEED_MIN_THRESHOLD_M_S).and().speedLtEq(max);
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToNext()) return 0;
            return c.getFloat(0);
        } finally {
            c.close();
        }
    }

    /**
     * Note: the top 10% points are discarded to account for imprecise values.
     */
    @WorkerThread
    public Float getAverageCadence(@NonNull Uri rideUri) {
        // First get the min and max
        float min = getMinCadence(rideUri);
        float max = getMaxCadence(rideUri);

        long rideId = ContentUris.parseId(rideUri);
        String[] projection = {"avg(" + LogColumns.CADENCE + ")"};
        LogSelection where = new LogSelection();
        where.rideId(rideId).and().cadenceGtEq(min).and().cadenceLtEq(max);
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToNext()) return null;
            if (c.isNull(0)) return null;
            return c.getFloat(0);
        } finally {
            c.close();
        }
    }

    /**
     * Note: the top 10% points are discarded to account for imprecise values.
     */
    @WorkerThread
    public Float getAverageHeartRate(@NonNull Uri rideUri) {
        // First get the min and max
        float min = getMinHeartRate(rideUri);
        float max = getMaxHeartRate(rideUri);

        long rideId = ContentUris.parseId(rideUri);
        String[] projection = {"avg(" + LogColumns.HEART_RATE + ")"};
        LogSelection where = new LogSelection();
        where.rideId(rideId).and().heartRateGtEq((int) min).and().heartRateLtEq((int) max);
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToNext()) return null;
            if (c.isNull(0)) return null;
            return c.getFloat(0);
        } finally {
            c.close();
        }
    }

    @WorkerThread
    public Long getMovingDuration(@NonNull Uri rideUri) {
        long rideId = ContentUris.parseId(rideUri);
        String[] projection = {"sum(" + LogColumns.LOG_DURATION + ")"};
        LogSelection where = new LogSelection();
        where.rideId(rideId).and().speedGt(LocationManager.SPEED_MIN_THRESHOLD_M_S);
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToNext()) return null;
            if (c.isNull(0)) return null;
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    /**
     * Note: the top 10% points are discarded to account for imprecise values.
     */
    @WorkerThread
    private float getMax(@NonNull Uri rideUri, @NonNull String column) {
        // Get the point count to discard the top 10% values
        Integer count = getLogCount(rideUri);
        if (count == null) return 0;

        long rideId = ContentUris.parseId(rideUri);
        String[] projection = {column};
        LogSelection where = new LogSelection()
                .rideId(rideId).and().addRaw(column + " IS NOT NULL")
                .orderBy(column, true)
                .limit(count / 10);
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToLast()) return 0;
            return c.getFloat(0);
        } finally {
            c.close();
        }
    }

    /**
     * Note: the bottom 10% points are discarded to account for imprecise values.
     */
    @WorkerThread
    private float getMin(@NonNull Uri rideUri, @NonNull String column) {
        long rideId = ContentUris.parseId(rideUri);
        String[] projection = {column};
        LogSelection where = new LogSelection()
                .rideId(rideId).and().addRaw(column + " IS NOT NULL")
                .orderBy(column);
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToFirst()) return 0;
            int count = c.getCount();
            // Discard the first 10%
            int index = count / 10;
            c.moveToPosition(index);
            return c.getFloat(0);
        } finally {
            c.close();
        }
    }

    @WorkerThread
    public float getMaxSpeed(@NonNull Uri rideUri) {
        return getMax(rideUri, LogColumns.SPEED);
    }

    @WorkerThread
    public float getMaxCadence(@NonNull Uri rideUri) {
        return getMax(rideUri, LogColumns.CADENCE);
    }

    private float getMinCadence(@NonNull Uri rideUri) {
        return getMin(rideUri, LogColumns.CADENCE);
    }

    @WorkerThread
    public float getMaxHeartRate(@NonNull Uri rideUri) {
        return getMax(rideUri, LogColumns.HEART_RATE);
    }

    @WorkerThread
    public float getMinHeartRate(@NonNull Uri rideUri) {
        return getMin(rideUri, LogColumns.HEART_RATE);
    }


    @WorkerThread
    public Long getFirstLogDate(@NonNull Uri rideUri) {
        long rideId = ContentUris.parseId(rideUri);
        String[] projection = {"min(" + LogColumns.RECORDED_DATE + ")"};
        LogSelection where = new LogSelection();
        where.rideId(rideId);
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToNext()) return null;
            if (c.isNull(0)) return null;
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    @WorkerThread
    public Long getLastLogDate(@NonNull Uri rideUri) {
        long rideId = ContentUris.parseId(rideUri);
        String[] projection = {"max(" + LogColumns.RECORDED_DATE + ")"};
        LogSelection where = new LogSelection();
        where.rideId(rideId);
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToNext()) return null;
            if (c.isNull(0)) return null;
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    private Integer getLogCount(@NonNull Uri rideUri) {
        String[] projection = {"count(*)"};
        long rideId = ContentUris.parseId(rideUri);
        LogSelection where = new LogSelection();
        where.rideId(rideId);
        int count;
        Cursor c = where.query(mContext, projection);
        try {
            if (!c.moveToNext()) return null;
            count = c.getInt(0);
        } finally {
            c.close();
        }
        return count;
    }

    @WorkerThread
    public List<LatLng> getLatLngArray(@NonNull Uri rideUri, int max) {
        // Get the point count to determine the ratio to apply to not get more than max values
        Integer count = getLogCount(rideUri);
        if (count == null) return null;
        int ratio = count / max;
        if (ratio == 0) ratio = 1;

        ArrayList<LatLng> res = new ArrayList<>(max);
        // Get the values
        String[] projection = new String[] {LogColumns.LAT, LogColumns.LON};
        LogSelection where = new LogSelection();
        // Get at most max rows by applying a modulo on the id
        long rideId = ContentUris.parseId(rideUri);
        where.rideId(rideId).and().addRaw(LogColumns._ID + "%" + ratio + "=0");
        LogCursor cursor = where.query(mContext, projection);
        try {
            while (cursor.moveToNext()) {
                res.add(new LatLng(cursor.getLat(), cursor.getLon()));
            }
        } finally {
            cursor.close();
        }
        return res;
    }

    @WorkerThread
    public List<Float> getSpeedArray(@NonNull Uri rideUri, int max) {
        // Get the point count to determine the ratio to apply to not get more than max values
        Integer count = getLogCount(rideUri);
        if (count == null) return null;
        int ratio = count / max;
        if (ratio == 0) ratio = 1;

        ArrayList<Float> res = new ArrayList<>(max);
        // Get the values
        String[] projection = new String[] {LogColumns.SPEED};
        LogSelection where = new LogSelection();
        // Get at most max rows by applying a modulo on the id
        long rideId = ContentUris.parseId(rideUri);
        where.rideId(rideId).and().speedNot((Float) null).and().addRaw(LogColumns._ID + "%" + ratio + "=0");
        LogCursor cursor = where.query(mContext, projection);
        try {
            while (cursor.moveToNext()) {
                res.add(cursor.getSpeed());
            }
        } finally {
            cursor.close();
        }
        return res;
    }

    @WorkerThread
    public List<Float> getCadenceArray(@NonNull Uri rideUri, int max) {
        // Get the point count to determine the ratio to apply to not get more than max values
        Integer count = getLogCount(rideUri);
        if (count == null) return null;
        int ratio = count / max;
        if (ratio == 0) ratio = 1;

        ArrayList<Float> res = new ArrayList<>(max);
        // Get the values
        String[] projection = new String[] {LogColumns.CADENCE};
        LogSelection where = new LogSelection();
        // Get at most max rows by applying a modulo on the id
        long rideId = ContentUris.parseId(rideUri);
        where.rideId(rideId).and().cadenceNot((Float) null).and().addRaw(LogColumns._ID + "%" + ratio + "=0");
        LogCursor cursor = where.query(mContext, projection);
        try {
            while (cursor.moveToNext()) {
                res.add(cursor.getCadence());
            }
        } finally {
            cursor.close();
        }
        return res;
    }

    @WorkerThread
    public List<Float> getHeartRateArray(@NonNull Uri rideUri, int max) {
        // Get the point count to determine the ratio to apply to not get more than max values
        Integer count = getLogCount(rideUri);
        if (count == null) return null;
        int ratio = count / max;
        if (ratio == 0) ratio = 1;

        ArrayList<Float> res = new ArrayList<>(max);
        // Get the values
        String[] projection = new String[] {LogColumns.HEART_RATE};
        LogSelection where = new LogSelection();
        // Get at most max rows by applying a modulo on the id
        long rideId = ContentUris.parseId(rideUri);
        where.rideId(rideId).and().heartRateNot((Integer) null).and().addRaw(LogColumns._ID + "%" + ratio + "=0");
        LogCursor cursor = where.query(mContext, projection);
        try {
            while (cursor.moveToNext()) {
                res.add(Float.valueOf(cursor.getHeartRate()));
            }
        } finally {
            cursor.close();
        }
        return res;
    }


    /*
     * Listeners.
     */

    public void addListener(@NonNull LogListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(@NonNull LogListener listener) {
        mListeners.remove(listener);
    }

}
