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
package org.jraf.android.bikey.backend.ride;

import java.util.Date;
import java.util.UUID;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.text.TextUtils;
import android.text.format.DateUtils;

import org.jraf.android.bikey.R;
import org.jraf.android.bikey.app.Application;
import org.jraf.android.bikey.app.collect.LogCollectorService;
import org.jraf.android.bikey.backend.log.LogManager;
import org.jraf.android.bikey.backend.provider.BikeyProvider;
import org.jraf.android.bikey.backend.provider.log.LogContentValues;
import org.jraf.android.bikey.backend.provider.log.LogSelection;
import org.jraf.android.bikey.backend.provider.ride.RideColumns;
import org.jraf.android.bikey.backend.provider.ride.RideContentValues;
import org.jraf.android.bikey.backend.provider.ride.RideCursor;
import org.jraf.android.bikey.backend.provider.ride.RideSelection;
import org.jraf.android.bikey.backend.provider.ride.RideState;
import org.jraf.android.bikey.common.Constants;
import org.jraf.android.util.listeners.Listeners;
import org.jraf.android.util.log.Log;

public class RideManager {
    private static final RideManager INSTANCE = new RideManager();

    public static RideManager get() {
        return INSTANCE;
    }

    private final Context mContext;
    private Listeners<RideListener> mListeners = Listeners.newInstance();

    private RideManager() {
        mContext = Application.getApplication();
    }

    @WorkerThread
    public Uri create(String name) {
        RideContentValues values = new RideContentValues();
        values.putUuid(UUID.randomUUID().toString());
        values.putCreatedDate(new Date());
        if (!TextUtils.isEmpty(name)) {
            values.putName(name);
        }
        values.putState(RideState.CREATED);
        values.putDuration(0L);
        values.putDistance(0f);
        return values.insert(mContext);
    }

    @WorkerThread
    public int delete(long[] ids) {
        // First pause any active rides in the list
        pauseRides(ids);

        // Mark rides as deleted
        RideSelection rideSelection = new RideSelection();
        rideSelection.id(ids);
        RideContentValues rideContentValues = new RideContentValues();
        rideContentValues.putState(RideState.DELETED);
        int res = rideContentValues.update(mContext, rideSelection);

        // Delete logs
        LogSelection logSelection = new LogSelection();
        logSelection.rideId(ids);
        logSelection.delete(mContext);

        // If we just deleted the current ride, select another ride to be the current ride (if any).
        Uri currentRideUri = getCurrentRide();
        if (currentRideUri != null) {
            long currentRideId = Long.valueOf(currentRideUri.getLastPathSegment());
            for (long id : ids) {
                if (currentRideId == id) {
                    Uri nextRideUri = getMostRecentRide();
                    setCurrentRide(nextRideUri);
                    break;
                }
            }
        }
        return res;
    }

    @WorkerThread
    public void merge(long[] ids) {
        // First pause any active rides in the list
        pauseRides(ids);

        // Choose the master ride (the one with the earliest creation date)
        String[] projection = {RideColumns._ID};
        RideSelection rideSelection = new RideSelection();
        rideSelection.id(ids);
        rideSelection.orderByCreatedDate();
        ContentResolver contentResolver = mContext.getContentResolver();
        RideCursor rideCursor = rideSelection.query(contentResolver, projection);
        long masterRideId;
        try {
            rideCursor.moveToNext();
            masterRideId = rideCursor.getId();
        } finally {
            rideCursor.close();
        }

        // Calculate the total duration
        projection = new String[] {"sum(" + RideColumns.DURATION + ")"};
        Cursor c = contentResolver.query(RideColumns.CONTENT_URI, projection, rideSelection.sel(), rideSelection.args(), null);
        long totalDuration = 0;
        try {
            if (c.moveToNext()) {
                totalDuration = c.getLong(0);
            }
        } finally {
            c.close();
        }

        // Merge
        for (long mergedRideId : ids) {
            if (mergedRideId == masterRideId) continue;

            // Update logs
            LogSelection logSelection = new LogSelection();
            logSelection.rideId(mergedRideId);
            LogContentValues values = new LogContentValues();
            values.putRideId(masterRideId);
            values.update(contentResolver, logSelection);

            // Delete merged ride
            rideSelection = new RideSelection();
            rideSelection.id(mergedRideId);
            // Do not notify yet
            Uri contentUri = BikeyProvider.notify(RideColumns.CONTENT_URI, false);
            contentResolver.delete(contentUri, rideSelection.sel(), rideSelection.args());
        }

        // Rename master ride
        Uri masterRideUri = ContentUris.withAppendedId(RideColumns.CONTENT_URI, masterRideId);
        String name = getName(masterRideUri);
        if (name == null) {
            name = mContext.getString(R.string.ride_list_mergedRide);
        } else {
            name = mContext.getString(R.string.ride_list_mergedRide_append, name);
        }
        RideContentValues values = new RideContentValues();
        values.putName(name);
        contentResolver.update(masterRideUri, values.values(), null, null);

        // Update master ride total distance
        float distance = LogManager.get().getTotalDistance(masterRideUri);
        updateTotalDistance(masterRideUri, distance);

        // Update master ride total duration
        updateDuration(masterRideUri, totalDuration);
    }

    private void pauseRides(long[] ids) {
        for (long rideId : ids) {
            Uri rideUri = ContentUris.withAppendedId(RideColumns.CONTENT_URI, rideId);
            RideState state = getState(rideUri);
            if (state == RideState.ACTIVE) {
                mContext.startService(new Intent(LogCollectorService.ACTION_STOP_COLLECTING, rideUri, mContext, LogCollectorService.class));
                break;
            }
        }
    }

    @WorkerThread
    public void activate(@NonNull Uri rideUri) {
        // Get first activated date
        Date firstActivatedDate = getFirstActivatedDate(rideUri);

        // Update state
        RideContentValues values = new RideContentValues();
        values.putState(RideState.ACTIVE);
        // Update activated date
        Date now = new Date();
        values.putActivatedDate(now);
        // Update first activated date, only if first time
        if (firstActivatedDate == null) {
            values.putFirstActivatedDate(now);
        }
        mContext.getContentResolver().update(rideUri, values.values(), null, null);

        // Dispatch to listeners
        mListeners.dispatch(listener -> listener.onActivated(rideUri));
    }

    @WorkerThread
    public void updateTotalDistance(@NonNull Uri rideUri, float distance) {
        RideContentValues values = new RideContentValues();
        values.putDistance(distance);
        mContext.getContentResolver().update(rideUri, values.values(), null, null);
    }

    @WorkerThread
    private void updateDuration(@NonNull Uri rideUri, long duration) {
        RideContentValues values = new RideContentValues();
        values.putDuration(duration);
        mContext.getContentResolver().update(rideUri, values.values(), null, null);
    }

    @WorkerThread
    public void updateName(@NonNull Uri rideUri, String name) {
        RideContentValues values = new RideContentValues();
        if (TextUtils.isEmpty(name)) {
            values.putNameNull();
        } else {
            values.putName(name);
        }
        mContext.getContentResolver().update(rideUri, values.values(), null, null);
    }

    @WorkerThread
    public void pause(@NonNull Uri rideUri) {
        // Get current activated date / duration
        String[] projection = {RideColumns.ACTIVATED_DATE, RideColumns.DURATION};
        RideCursor c = new RideCursor(mContext.getContentResolver().query(rideUri, projection, null, null, null));
        try {
            if (!c.moveToNext()) {
                Log.w("Could not pause ride, uri " + rideUri + " not found");
                return;
            }
            long activatedDate = c.getActivatedDate().getTime();
            long duration = c.getDuration();

            // Update duration, state, and reset activated date
            duration += System.currentTimeMillis() - activatedDate;

            RideContentValues values = new RideContentValues();
            values.putState(RideState.PAUSED);
            values.putDuration(duration);
            values.putActivatedDate(0l);
            mContext.getContentResolver().update(rideUri, values.values(), null, null);

            // Dispatch to listeners
            mListeners.dispatch(listener -> listener.onPaused(rideUri));
        } finally {
            c.close();
        }
    }

    /**
     * Queries all the columns for the given ride.
     * Do not forget to call {@link Cursor#close()} on the returned Cursor.
     */
    public RideCursor query(@NonNull Uri rideUri) {
        if (rideUri == null) throw new IllegalArgumentException("null rideUri");
        Cursor c = mContext.getContentResolver().query(rideUri, null, null, null, null);
        if (!c.moveToNext()) {
            c.close();
            throw new IllegalArgumentException(rideUri + " not found");
        }
        return new RideCursor(c);
    }

    @WorkerThread
    @Nullable
    public Uri getCurrentRide() {
        String currentRideUriStr = PreferenceManager.getDefaultSharedPreferences(mContext).getString(Constants.PREF_CURRENT_RIDE_URI, null);
        if (!TextUtils.isEmpty(currentRideUriStr)) {
            Uri currentRideUri = Uri.parse(currentRideUriStr);
            return currentRideUri;
        }
        return null;
    }

    @WorkerThread
    public void setCurrentRide(@NonNull Uri rideUri) {
        PreferenceManager.getDefaultSharedPreferences(mContext).edit().putString(Constants.PREF_CURRENT_RIDE_URI, rideUri.toString()).apply();
    }

    @WorkerThread
    private Uri getMostRecentRide() {
        String[] projection = {RideColumns._ID};
        // Return a ride, prioritizing ACTIVE ones first, then sorting by creation date.
        Cursor c = mContext.getContentResolver().query(RideColumns.CONTENT_URI, projection, null, null,
                RideColumns.STATE + ", " + RideColumns.CREATED_DATE + " DESC");
        try {
            if (!c.moveToNext()) return null;
            long id = c.getLong(0);
            return ContentUris.withAppendedId(RideColumns.CONTENT_URI, id);
        } finally {
            c.close();
        }
    }

    @WorkerThread
    public Date getActivatedDate(@NonNull Uri rideUri) {
        RideCursor c = query(rideUri);
        try {
            return c.getActivatedDate();
        } finally {
            c.close();
        }
    }

    @WorkerThread
    private Date getFirstActivatedDate(@NonNull Uri rideUri) {
        RideCursor c = query(rideUri);
        try {
            return c.getFirstActivatedDate();
        } finally {
            c.close();
        }
    }

    @WorkerThread
    public long getDuration(@NonNull Uri rideUri) {
        RideCursor c = query(rideUri);
        try {
            return c.getDuration();
        } finally {
            c.close();
        }
    }

    @WorkerThread
    public RideState getState(@NonNull Uri rideUri) {
        RideCursor c = query(rideUri);
        try {
            return c.getState();
        } finally {
            c.close();
        }
    }

    @WorkerThread
    public String getDisplayName(@NonNull Uri rideUri) {
        RideCursor c = query(rideUri);
        try {
            String name = c.getName();
            long createdDateLong = c.getCreatedDate().getTime();
            String createdDateTimeStr = DateUtils.formatDateTime(mContext, createdDateLong, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
            if (name == null) {
                return createdDateTimeStr;
            }
            return name + " (" + createdDateTimeStr + ")";
        } finally {
            c.close();
        }
    }

    @WorkerThread
    public String getName(@NonNull Uri rideUri) {
        RideCursor c = query(rideUri);
        try {
            return c.getName();
        } finally {
            c.close();
        }
    }

    @WorkerThread
    public boolean isExistingRide(@NonNull Uri rideUri) {
        Cursor c = mContext.getContentResolver().query(rideUri, null, null, null, null);
        try {
            return c.moveToNext();
        } finally {
            c.close();
        }
    }


    /*
     * Listeners.
     */

    public void addListener(@NonNull RideListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(@NonNull RideListener listener) {
        mListeners.remove(listener);
    }
}
