/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.safetycenter;

import static android.os.Build.VERSION_CODES.TIRAMISU;

import static java.util.Collections.emptyList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.icu.text.ListFormatter;
import android.icu.text.MessageFormat;
import android.icu.util.ULocale;
import android.os.Binder;
import android.os.UserHandle;
import android.safetycenter.SafetyCenterData;
import android.safetycenter.SafetyCenterEntry;
import android.safetycenter.SafetyCenterEntryGroup;
import android.safetycenter.SafetyCenterEntryOrGroup;
import android.safetycenter.SafetyCenterIssue;
import android.safetycenter.SafetyCenterStaticEntry;
import android.safetycenter.SafetyCenterStaticEntryGroup;
import android.safetycenter.SafetyCenterStatus;
import android.safetycenter.SafetyEvent;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceErrorDetails;
import android.safetycenter.SafetySourceIssue;
import android.safetycenter.SafetySourceStatus;
import android.safetycenter.config.SafetyCenterConfig;
import android.safetycenter.config.SafetySource;
import android.safetycenter.config.SafetySourcesGroup;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.permission.util.UserUtils;
import com.android.safetycenter.SafetyCenterConfigReader.ExternalSafetySource;
import com.android.safetycenter.internaldata.SafetyCenterEntryGroupId;
import com.android.safetycenter.internaldata.SafetyCenterEntryId;
import com.android.safetycenter.internaldata.SafetyCenterIds;
import com.android.safetycenter.internaldata.SafetyCenterIssueActionId;
import com.android.safetycenter.internaldata.SafetyCenterIssueId;
import com.android.safetycenter.internaldata.SafetyCenterIssueKey;
import com.android.safetycenter.persistence.PersistedSafetyCenterIssue;
import com.android.safetycenter.resources.SafetyCenterResourcesContext;

import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * A class that keeps track of all the {@link SafetySourceData} set by safety sources, and
 * aggregates them into a {@link SafetyCenterData} object to be used by PermissionController.
 *
 * <p>This class isn't thread safe. Thread safety must be handled by the caller.
 */
@RequiresApi(TIRAMISU)
@NotThreadSafe
final class SafetyCenterDataTracker {

    private static final String TAG = "SafetyCenterDataTracker";

    private static final String ANDROID_LOCK_SCREEN_SOURCES_GROUP_ID = "AndroidLockScreenSources";
    private static final String ANDROID_LOCK_SCREEN_SOURCE_ID = "AndroidLockScreen";
    private static final int ANDROID_LOCK_SCREEN_ICON_ACTION_REQ_CODE = 86;

    private static final SafetyCenterIssuesBySeverityDescending
            SAFETY_CENTER_ISSUES_BY_SEVERITY_DESCENDING =
                    new SafetyCenterIssuesBySeverityDescending();

    private final ArrayMap<SafetySourceKey, SafetySourceData> mSafetySourceDataForKey =
            new ArrayMap<>();

    private final ArraySet<SafetySourceKey> mSafetySourceErrors = new ArraySet<>();

    private final ArrayMap<SafetyCenterIssueKey, IssueData> mSafetyCenterIssueCache =
            new ArrayMap<>();

    private final ArraySet<SafetyCenterIssueActionId> mSafetyCenterIssueActionsInFlight =
            new ArraySet<>();

    @NonNull private final Context mContext;
    @NonNull private final SafetyCenterResourcesContext mSafetyCenterResourcesContext;
    @NonNull private final SafetyCenterConfigReader mSafetyCenterConfigReader;
    @NonNull private final SafetyCenterRefreshTracker mSafetyCenterRefreshTracker;

    private boolean mSafetyCenterIssueCacheDirty = false;

    /**
     * Creates a {@link SafetyCenterDataTracker} using the given {@link Context}, {@link
     * SafetyCenterResourcesContext}, {@link SafetyCenterConfigReader} and {@link
     * SafetyCenterRefreshTracker}.
     */
    SafetyCenterDataTracker(
            @NonNull Context context,
            @NonNull SafetyCenterResourcesContext safetyCenterResourcesContext,
            @NonNull SafetyCenterConfigReader safetyCenterConfigReader,
            @NonNull SafetyCenterRefreshTracker safetyCenterRefreshTracker) {
        mContext = context;
        mSafetyCenterResourcesContext = safetyCenterResourcesContext;
        mSafetyCenterConfigReader = safetyCenterConfigReader;
        mSafetyCenterRefreshTracker = safetyCenterRefreshTracker;
    }

    /**
     * Returns whether the Safety Center issue cache has been modified since the last time a
     * snapshot was taken.
     */
    boolean isSafetyCenterIssueCacheDirty() {
        return mSafetyCenterIssueCacheDirty;
    }

    /**
     * Takes a snapshot of the Safety Center issue cache that should be written to persistent
     * storage.
     *
     * <p>This method will reset the value reported by {@link #isSafetyCenterIssueCacheDirty} to
     * {@code false}.
     */
    @NonNull
    List<PersistedSafetyCenterIssue> snapshotSafetyCenterIssueCache() {
        mSafetyCenterIssueCacheDirty = false;

        List<PersistedSafetyCenterIssue> persistedSafetyCenterIssues = new ArrayList<>();

        for (int i = 0; i < mSafetyCenterIssueCache.size(); i++) {
            String encodedKey = SafetyCenterIds.encodeToString(mSafetyCenterIssueCache.keyAt(i));
            IssueData issueData = mSafetyCenterIssueCache.valueAt(i);
            persistedSafetyCenterIssues.add(
                    new PersistedSafetyCenterIssue.Builder()
                            .setKey(encodedKey)
                            .setFirstSeenAt(issueData.getFirstSeenAt())
                            .setDismissedAt(issueData.getDismissedAt())
                            .setDismissCount(issueData.getDismissCount())
                            .build());
        }

        return persistedSafetyCenterIssues;
    }

    /**
     * Replaces the Safety Center issue cache with the given list of issues.
     *
     * <p>This method may modify the Safety Center issue cache and change the value reported by
     * {@link #isSafetyCenterIssueCacheDirty} to {@code true}.
     */
    void loadSafetyCenterIssueCache(
            @NonNull List<PersistedSafetyCenterIssue> persistedSafetyCenterIssues) {
        mSafetyCenterIssueCache.clear();
        for (int i = 0; i < persistedSafetyCenterIssues.size(); i++) {
            PersistedSafetyCenterIssue persistedSafetyCenterIssue =
                    persistedSafetyCenterIssues.get(i);

            SafetyCenterIssueKey key =
                    SafetyCenterIds.issueKeyFromString(persistedSafetyCenterIssue.getKey());
            // Check the source associated with this issue still exists, it might have been removed
            // from the Safety Center config or the device might have rebooted with data persisted
            // from a temporary Safety Center config.
            if (!mSafetyCenterConfigReader.isExternalSafetySourceActive(key.getSafetySourceId())) {
                mSafetyCenterIssueCacheDirty = true;
                continue;
            }

            IssueData issueData = new IssueData(persistedSafetyCenterIssue.getFirstSeenAt());
            issueData.setDismissedAt(persistedSafetyCenterIssue.getDismissedAt());
            issueData.setDismissCount(persistedSafetyCenterIssue.getDismissCount());
            mSafetyCenterIssueCache.put(key, issueData);
        }
    }

    /**
     * Sets the latest {@link SafetySourceData} for the given {@code safetySourceId}, {@link
     * SafetyEvent}, {@code packageName} and {@code userId}, and returns whether there was a change
     * to the underlying {@link SafetyCenterData}.
     *
     * <p>Throws if the request is invalid based on the {@link SafetyCenterConfig}: the given {@code
     * safetySourceId}, {@code packageName} and/or {@code userId} are unexpected; or the {@link
     * SafetySourceData} does not respect all constraints defined in the config.
     *
     * <p>Setting a {@code null} {@link SafetySourceData} evicts the current {@link
     * SafetySourceData} entry and clears the Safety Center issue cache for the source.
     *
     * <p>This method may modify the Safety Center issue cache and change the value reported by
     * {@link #isSafetyCenterIssueCacheDirty} to {@code true}.
     */
    boolean setSafetySourceData(
            @Nullable SafetySourceData safetySourceData,
            @NonNull String safetySourceId,
            @NonNull SafetyEvent safetyEvent,
            @NonNull String packageName,
            @UserIdInt int userId) {
        if (!validateRequest(safetySourceData, safetySourceId, packageName, userId)) {
            return false;
        }
        boolean safetyEventChangedSafetyCenterData =
                processSafetyEvent(safetySourceId, safetyEvent, userId);

        SafetySourceKey key = SafetySourceKey.of(safetySourceId, userId);
        boolean removingSafetySourceErrorChangedSafetyCenterData = mSafetySourceErrors.remove(key);
        SafetySourceData existingSafetySourceData = mSafetySourceDataForKey.get(key);
        if (Objects.equals(safetySourceData, existingSafetySourceData)) {
            return safetyEventChangedSafetyCenterData
                    || removingSafetySourceErrorChangedSafetyCenterData;
        }

        ArraySet<String> issueIds = new ArraySet<>();
        if (safetySourceData == null) {
            mSafetySourceDataForKey.remove(key);
        } else {
            mSafetySourceDataForKey.put(key, safetySourceData);
            for (int i = 0; i < safetySourceData.getIssues().size(); i++) {
                issueIds.add(safetySourceData.getIssues().get(i).getId());
            }
        }
        updateSafetyCenterIssueCache(issueIds, safetySourceId, userId);

        return true;
    }

    /**
     * Returns the latest {@link SafetySourceData} that was set by {@link #setSafetySourceData} for
     * the given {@code safetySourceId}, {@code packageName} and {@code userId}.
     *
     * <p>Throws if the request is invalid based on the {@link SafetyCenterConfig}: the given {@code
     * safetySourceId}, {@code packageName} and/or {@code userId} are unexpected.
     *
     * <p>Returns {@code null} if it was never set since boot, or if the entry was evicted using
     * {@link #setSafetySourceData} with a {@code null} value.
     */
    @Nullable
    SafetySourceData getSafetySourceData(
            @NonNull String safetySourceId, @NonNull String packageName, @UserIdInt int userId) {
        if (!validateRequest(null, safetySourceId, packageName, userId)) {
            return null;
        }
        return mSafetySourceDataForKey.get(SafetySourceKey.of(safetySourceId, userId));
    }

    /**
     * Reports the given {@link SafetySourceErrorDetails} for the given {@code safetySourceId} and
     * {@code userId}, and returns whether there was a change to the underlying {@link
     * SafetyCenterData}.
     *
     * <p>Throws if the request is invalid based on the {@link SafetyCenterConfig}: the given {@code
     * safetySourceId}, {@code packageName} and/or {@code userId} are unexpected.
     */
    boolean reportSafetySourceError(
            @NonNull SafetySourceErrorDetails safetySourceErrorDetails,
            @NonNull String safetySourceId,
            @NonNull String packageName,
            @UserIdInt int userId) {
        if (!validateRequest(null, safetySourceId, packageName, userId)) {
            return false;
        }
        SafetyEvent safetyEvent = safetySourceErrorDetails.getSafetyEvent();
        Log.w(TAG, "Error reported from source: " + safetySourceId + ", for event: " + safetyEvent);

        boolean safetyEventChangedSafetyCenterData =
                processSafetyEvent(safetySourceId, safetyEvent, userId);
        int safetyEventType = safetyEvent.getType();
        if (safetyEventType == SafetyEvent.SAFETY_EVENT_TYPE_RESOLVING_ACTION_FAILED
                || safetyEventType == SafetyEvent.SAFETY_EVENT_TYPE_RESOLVING_ACTION_SUCCEEDED) {
            return safetyEventChangedSafetyCenterData;
        }

        SafetySourceKey key = SafetySourceKey.of(safetySourceId, userId);
        boolean safetySourceErrorChangedSafetyCenterData = setSafetySourceError(key);
        return safetyEventChangedSafetyCenterData || safetySourceErrorChangedSafetyCenterData;
    }

    /** Marks the given {@link SafetySourceKey} as having errored-out. */
    boolean setSafetySourceError(@NonNull SafetySourceKey safetySourceKey) {
        boolean removingSafetySourceDataChangedSafetyCenterData =
                mSafetySourceDataForKey.remove(safetySourceKey) != null;
        boolean addingSafetySourceErrorChangedSafetyCenterData =
                mSafetySourceErrors.add(safetySourceKey);
        return removingSafetySourceDataChangedSafetyCenterData
                || addingSafetySourceErrorChangedSafetyCenterData;
    }

    /**
     * Clears all safety source errors received so far for the given {@link UserProfileGroup}, this
     * is useful e.g. when starting a new broadcast.
     */
    void clearSafetySourceErrors(@NonNull UserProfileGroup userProfileGroup) {
        // Loop in reverse index order to be able to remove entries while iterating.
        for (int i = mSafetySourceErrors.size() - 1; i >= 0; i--) {
            SafetySourceKey sourceKey = mSafetySourceErrors.valueAt(i);
            if (userProfileGroup.contains(sourceKey.getUserId())) {
                mSafetySourceErrors.removeAt(i);
            }
        }
    }

    /**
     * Returns the current {@link SafetyCenterData} for the given {@code packageName} and {@link
     * UserProfileGroup}, aggregated from all the {@link SafetySourceData} set so far.
     *
     * <p>If a {@link SafetySourceData} was not set, the default value from the {@link
     * SafetyCenterConfig} is used.
     */
    @NonNull
    SafetyCenterData getSafetyCenterData(
            @NonNull String packageName, @NonNull UserProfileGroup userProfileGroup) {
        return getSafetyCenterData(
                mSafetyCenterConfigReader.getSafetySourcesGroups(), packageName, userProfileGroup);
    }

    /** Marks the given {@link SafetyCenterIssueActionId} as in-flight. */
    void markSafetyCenterIssueActionAsInFlight(
            @NonNull SafetyCenterIssueActionId safetyCenterIssueActionId) {
        mSafetyCenterIssueActionsInFlight.add(safetyCenterIssueActionId);
    }

    /**
     * Unmarks the given {@link SafetyCenterIssueActionId} as in-flight and returns whether it
     * caused the underlying {@link SafetyCenterData} to change.
     */
    boolean unmarkSafetyCenterIssueActionAsInFlight(
            @NonNull SafetyCenterIssueActionId safetyCenterIssueActionId) {
        return mSafetyCenterIssueActionsInFlight.remove(safetyCenterIssueActionId)
                && getSafetySourceIssueAction(safetyCenterIssueActionId) != null;
    }

    /**
     * Dismisses the given {@link SafetyCenterIssueKey}.
     *
     * <p>This method may modify the Safety Center issue cache and change the value reported by
     * {@link #isSafetyCenterIssueCacheDirty} to {@code true}.
     */
    void dismissSafetyCenterIssue(@NonNull SafetyCenterIssueKey safetyCenterIssueKey) {
        IssueData issueData = mSafetyCenterIssueCache.get(safetyCenterIssueKey);
        if (issueData == null) {
            Log.w(
                    TAG,
                    "Issue missing when writing to cache: "
                            + safetyCenterIssueKey.getSafetySourceIssueId()
                            + ", of source: "
                            + safetyCenterIssueKey.getSafetySourceId());
            return;
        }
        issueData.setDismissedAt(Instant.now());
        issueData.setDismissCount(issueData.getDismissCount() + 1);
        mSafetyCenterIssueCacheDirty = true;
    }

    /**
     * Returns the {@link SafetySourceIssue} associated with the given {@link SafetyCenterIssueKey}.
     *
     * <p>Returns {@code null} if there is no such {@link SafetySourceIssue}, or if it's been
     * dismissed.
     */
    @Nullable
    SafetySourceIssue getSafetySourceIssue(@NonNull SafetyCenterIssueKey safetyCenterIssueKey) {
        SafetySourceKey key =
                SafetySourceKey.of(
                        safetyCenterIssueKey.getSafetySourceId(), safetyCenterIssueKey.getUserId());
        SafetySourceData safetySourceData = mSafetySourceDataForKey.get(key);
        if (safetySourceData == null) {
            return null;
        }
        List<SafetySourceIssue> safetySourceIssues = safetySourceData.getIssues();

        SafetySourceIssue targetIssue = null;
        for (int i = 0; i < safetySourceIssues.size(); i++) {
            SafetySourceIssue safetySourceIssue = safetySourceIssues.get(i);

            if (safetyCenterIssueKey.getSafetySourceIssueId().equals(safetySourceIssue.getId())) {
                targetIssue = safetySourceIssue;
                break;
            }
        }
        if (targetIssue == null) {
            return null;
        }

        if (isDismissed(safetyCenterIssueKey, targetIssue.getSeverityLevel())) {
            return null;
        }

        return targetIssue;
    }

    /**
     * Returns the {@link SafetySourceIssue.Action} associated with the given {@link
     * SafetyCenterIssueActionId}.
     *
     * <p>Returns {@code null} if there is no associated {@link SafetySourceIssue}, or if it's been
     * dismissed.
     *
     * <p>Returns {@code null} if the {@link SafetySourceIssue.Action} is currently in flight.
     */
    @Nullable
    SafetySourceIssue.Action getSafetySourceIssueAction(
            @NonNull SafetyCenterIssueActionId safetyCenterIssueActionId) {
        SafetySourceIssue safetySourceIssue =
                getSafetySourceIssue(safetyCenterIssueActionId.getSafetyCenterIssueKey());

        if (safetySourceIssue == null) {
            return null;
        }

        if (isInFlight(safetyCenterIssueActionId)) {
            return null;
        }

        List<SafetySourceIssue.Action> safetySourceIssueActions = safetySourceIssue.getActions();
        for (int i = 0; i < safetySourceIssueActions.size(); i++) {
            SafetySourceIssue.Action safetySourceIssueAction = safetySourceIssueActions.get(i);

            if (safetyCenterIssueActionId
                    .getSafetySourceIssueActionId()
                    .equals(safetySourceIssueAction.getId())) {
                return safetySourceIssueAction;
            }
        }

        return null;
    }

    /**
     * Returns a default {@link SafetyCenterData} object to be returned when the API is disabled.
     */
    @NonNull
    SafetyCenterData getDefaultSafetyCenterData() {
        return new SafetyCenterData(
                new SafetyCenterStatus.Builder(
                                getSafetyCenterStatusTitle(
                                        SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN,
                                        new ArrayList<>(),
                                        SafetyCenterStatus.REFRESH_STATUS_NONE,
                                        false),
                                getSafetyCenterStatusSummary(
                                        SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN,
                                        SafetyCenterStatus.REFRESH_STATUS_NONE,
                                        0,
                                        false))
                        .setSeverityLevel(SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN)
                        .build(),
                emptyList(),
                emptyList(),
                emptyList());
    }

    /**
     * Clears all the {@link SafetySourceData} and errors, metadata associated with {@link
     * SafetyCenterIssueKey}s, in flight {@link SafetyCenterIssueActionId} and any refresh in
     * progress so far, for all users.
     *
     * <p>This method will modify the Safety Center issue cache and change the value reported by
     * {@link #isSafetyCenterIssueCacheDirty} to {@code true}.
     */
    void clear() {
        mSafetySourceDataForKey.clear();
        mSafetySourceErrors.clear();
        mSafetyCenterIssueCache.clear();
        mSafetyCenterIssueCacheDirty = true;
        mSafetyCenterIssueActionsInFlight.clear();
    }

    /**
     * Dumps state for debugging purposes.
     *
     * @param fout {@link PrintWriter} to write to
     */
    void dump(@NonNull PrintWriter fout) {
        int dataCount = mSafetySourceDataForKey.size();
        fout.println("SOURCE DATA (" + dataCount + ")");
        for (int i = 0; i < dataCount; i++) {
            SafetySourceKey key = mSafetySourceDataForKey.keyAt(i);
            SafetySourceData data = mSafetySourceDataForKey.valueAt(i);
            fout.println("\t[" + i + "] " + key + " -> " + data);
        }
        fout.println();

        int errorCount = mSafetySourceErrors.size();
        fout.println("SOURCE ERRORS (" + errorCount + ")");
        for (int i = 0; i < errorCount; i++) {
            SafetySourceKey key = mSafetySourceErrors.valueAt(i);
            fout.println("\t[" + i + "] " + key);
        }
        fout.println();

        int issueCacheCount = mSafetyCenterIssueCache.size();
        fout.println(
                "ISSUE CACHE ("
                        + issueCacheCount
                        + ", dirty="
                        + mSafetyCenterIssueCacheDirty
                        + ")");
        for (int i = 0; i < issueCacheCount; i++) {
            SafetyCenterIssueKey key = mSafetyCenterIssueCache.keyAt(i);
            IssueData data = mSafetyCenterIssueCache.valueAt(i);
            fout.println("\t[" + i + "] " + key + " -> " + data);
        }
        fout.println();

        int actionInFlightCount = mSafetyCenterIssueActionsInFlight.size();
        fout.println("ACTIONS IN FLIGHT (" + actionInFlightCount + ")");
        for (int i = 0; i < actionInFlightCount; i++) {
            SafetyCenterIssueActionId id = mSafetyCenterIssueActionsInFlight.valueAt(i);
            fout.println("\t[" + i + "] " + id);
        }
        fout.println();
    }

    /**
     * Clears all the {@link SafetySourceData}, metadata associated with {@link
     * SafetyCenterIssueKey}s, in flight {@link SafetyCenterIssueActionId} and any refresh in
     * progress so far, for the given user.
     *
     * <p>This method may modify the Safety Center issue cache and change the value reported by
     * {@link #isSafetyCenterIssueCacheDirty} to {@code true}.
     */
    void clearForUser(@UserIdInt int userId) {
        // Loop in reverse index order to be able to remove entries while iterating.
        for (int i = mSafetySourceDataForKey.size() - 1; i >= 0; i--) {
            SafetySourceKey sourceKey = mSafetySourceDataForKey.keyAt(i);
            if (sourceKey.getUserId() == userId) {
                mSafetySourceDataForKey.removeAt(i);
            }
        }
        // Loop in reverse index order to be able to remove entries while iterating.
        for (int i = mSafetySourceErrors.size() - 1; i >= 0; i--) {
            SafetySourceKey sourceKey = mSafetySourceErrors.valueAt(i);
            if (sourceKey.getUserId() == userId) {
                mSafetySourceErrors.removeAt(i);
            }
        }
        // Loop in reverse index order to be able to remove entries while iterating.
        for (int i = mSafetyCenterIssueCache.size() - 1; i >= 0; i--) {
            SafetyCenterIssueKey issueKey = mSafetyCenterIssueCache.keyAt(i);
            if (issueKey.getUserId() == userId) {
                mSafetyCenterIssueCache.removeAt(i);
                mSafetyCenterIssueCacheDirty = true;
            }
        }
        // Loop in reverse index order to be able to remove entries while iterating.
        for (int i = mSafetyCenterIssueActionsInFlight.size() - 1; i >= 0; i--) {
            SafetyCenterIssueActionId issueActionId = mSafetyCenterIssueActionsInFlight.valueAt(i);
            if (issueActionId.getSafetyCenterIssueKey().getUserId() == userId) {
                mSafetyCenterIssueActionsInFlight.removeAt(i);
            }
        }
    }

    private boolean isDismissed(
            @NonNull SafetyCenterIssueKey safetyCenterIssueKey,
            @SafetySourceData.SeverityLevel int safetySourceIssueSeverityLevel) {
        IssueData issueData = mSafetyCenterIssueCache.get(safetyCenterIssueKey);
        if (issueData == null) {
            Log.w(
                    TAG,
                    "Issue missing when reading from cache: "
                            + safetyCenterIssueKey.getSafetySourceIssueId()
                            + ", of source: "
                            + safetyCenterIssueKey.getSafetySourceId());
            return false;
        }

        Instant dismissedAt = issueData.getDismissedAt();
        boolean hasNeverBeenDismissed = dismissedAt == null;
        if (hasNeverBeenDismissed) {
            return false;
        }

        long maxCount = SafetyCenterFlags.getResurfaceIssueMaxCount(safetySourceIssueSeverityLevel);
        Duration delay = SafetyCenterFlags.getResurfaceIssueDelay(safetySourceIssueSeverityLevel);

        boolean hasAlreadyResurfacedTheMaxAllowedNumberOfTimes =
                issueData.getDismissCount() > maxCount;
        if (hasAlreadyResurfacedTheMaxAllowedNumberOfTimes) {
            return true;
        }

        Duration timeSinceLastDismissal = Duration.between(dismissedAt, Instant.now());
        boolean isTimeToResurface = timeSinceLastDismissal.compareTo(delay) >= 0;
        if (isTimeToResurface) {
            return false;
        }

        return true;
    }

    private boolean isInFlight(@NonNull SafetyCenterIssueActionId safetyCenterIssueActionId) {
        return mSafetyCenterIssueActionsInFlight.contains(safetyCenterIssueActionId);
    }

    private void updateSafetyCenterIssueCache(
            @NonNull ArraySet<String> safetySourceIssueIds,
            @NonNull String safetySourceId,
            @UserIdInt int userId) {
        // Remove issues no longer reported by the source.
        // Loop in reverse index order to be able to remove entries while iterating.
        for (int i = mSafetyCenterIssueCache.size() - 1; i >= 0; i--) {
            SafetyCenterIssueKey issueKey = mSafetyCenterIssueCache.keyAt(i);
            boolean doesNotBelongToUserOrSource =
                    issueKey.getUserId() != userId
                            || !Objects.equals(issueKey.getSafetySourceId(), safetySourceId);
            if (doesNotBelongToUserOrSource) {
                continue;
            }
            boolean isIssueNoLongerReported =
                    !safetySourceIssueIds.contains(issueKey.getSafetySourceIssueId());
            if (isIssueNoLongerReported) {
                mSafetyCenterIssueCache.removeAt(i);
                mSafetyCenterIssueCacheDirty = true;
            }
        }
        // Add newly reported issues.
        for (int i = 0; i < safetySourceIssueIds.size(); i++) {
            SafetyCenterIssueKey issueKey =
                    SafetyCenterIssueKey.newBuilder()
                            .setUserId(userId)
                            .setSafetySourceId(safetySourceId)
                            .setSafetySourceIssueId(safetySourceIssueIds.valueAt(i))
                            .build();
            boolean isIssueNewlyReported = !mSafetyCenterIssueCache.containsKey(issueKey);
            if (isIssueNewlyReported) {
                mSafetyCenterIssueCache.put(issueKey, new IssueData(Instant.now()));
                mSafetyCenterIssueCacheDirty = true;
            }
        }
    }

    /**
     * Checks if a request to the SafetyCenter is valid, and returns whether the request should be
     * processed.
     */
    private boolean validateRequest(
            @Nullable SafetySourceData safetySourceData,
            @NonNull String safetySourceId,
            @NonNull String packageName,
            @UserIdInt int userId) {
        ExternalSafetySource externalSafetySource =
                mSafetyCenterConfigReader.getExternalSafetySource(safetySourceId);
        if (externalSafetySource == null) {
            throw new IllegalArgumentException("Unexpected safety source: " + safetySourceId);
        }

        SafetySource safetySource = externalSafetySource.getSafetySource();

        // TODO(b/222330089): Security: check certs?
        if (!packageName.equals(safetySource.getPackageName())) {
            throw new IllegalArgumentException(
                    "Unexpected package name: "
                            + packageName
                            + ", for safety source: "
                            + safetySourceId);
        }

        // TODO(b/222327845): Security: check package is installed for user?

        if (UserUtils.isManagedProfile(userId, mContext)
                && !SafetySources.supportsManagedProfiles(safetySource)) {
            throw new IllegalArgumentException(
                    "Unexpected managed profile request for safety source: " + safetySourceId);
        }

        boolean retrievingOrClearingData = safetySourceData == null;
        if (retrievingOrClearingData) {
            return mSafetyCenterConfigReader.isExternalSafetySourceActive(safetySourceId);
        }

        SafetySourceStatus safetySourceStatus = safetySourceData.getStatus();

        if (safetySource.getType() == SafetySource.SAFETY_SOURCE_TYPE_ISSUE_ONLY
                && safetySourceStatus != null) {
            throw new IllegalArgumentException(
                    "Unexpected status for issue only safety source: " + safetySourceId);
        }

        if (safetySource.getType() == SafetySource.SAFETY_SOURCE_TYPE_DYNAMIC
                && safetySourceStatus == null) {
            throw new IllegalArgumentException(
                    "Missing status for dynamic safety source: " + safetySourceId);
        }

        if (safetySourceStatus != null) {
            int sourceSeverityLevel = safetySourceStatus.getSeverityLevel();

            if (externalSafetySource.hasEntryInRigidGroup()
                    && sourceSeverityLevel != SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED) {
                throw new IllegalArgumentException(
                        "Safety source: "
                                + safetySourceId
                                + " is in a rigid group but specified a severity level: "
                                + sourceSeverityLevel);
            }

            int maxSourceSeverityLevel =
                    Math.max(
                            SafetySourceData.SEVERITY_LEVEL_INFORMATION,
                            safetySource.getMaxSeverityLevel());

            if (sourceSeverityLevel > maxSourceSeverityLevel) {
                throw new IllegalArgumentException(
                        "Unexpected severity level: "
                                + sourceSeverityLevel
                                + ", for safety source: "
                                + safetySourceId);
            }
        }

        List<SafetySourceIssue> safetySourceIssues = safetySourceData.getIssues();

        for (int i = 0; i < safetySourceIssues.size(); i++) {
            int issueSeverityLevel = safetySourceIssues.get(i).getSeverityLevel();
            if (issueSeverityLevel > safetySource.getMaxSeverityLevel()) {
                throw new IllegalArgumentException(
                        "Unexpected severity level: "
                                + issueSeverityLevel
                                + ", for issue in safety source: "
                                + safetySourceId);
            }
        }

        return mSafetyCenterConfigReader.isExternalSafetySourceActive(safetySourceId);
    }

    private boolean processSafetyEvent(
            @NonNull String safetySourceId,
            @NonNull SafetyEvent safetyEvent,
            @UserIdInt int userId) {
        int type = safetyEvent.getType();
        switch (type) {
            case SafetyEvent.SAFETY_EVENT_TYPE_REFRESH_REQUESTED:
                String refreshBroadcastId = safetyEvent.getRefreshBroadcastId();
                if (refreshBroadcastId == null) {
                    Log.w(
                            TAG,
                            "Received safety event of type "
                                    + safetyEvent.getType()
                                    + " without a refresh broadcast id");
                    return false;
                }
                return mSafetyCenterRefreshTracker.reportSourceRefreshCompleted(
                        refreshBroadcastId, safetySourceId, userId);
            case SafetyEvent.SAFETY_EVENT_TYPE_RESOLVING_ACTION_SUCCEEDED:
            case SafetyEvent.SAFETY_EVENT_TYPE_RESOLVING_ACTION_FAILED:
                String safetySourceIssueId = safetyEvent.getSafetySourceIssueId();
                if (safetySourceIssueId == null) {
                    Log.w(
                            TAG,
                            "Received safety event of type "
                                    + safetyEvent.getType()
                                    + " without a safety source issue id");
                    return false;
                }
                String safetySourceIssueActionId = safetyEvent.getSafetySourceIssueActionId();
                if (safetySourceIssueActionId == null) {
                    Log.w(
                            TAG,
                            "Received safety event of type "
                                    + safetyEvent.getType()
                                    + " without a safety source issue action id");
                    return false;
                }
                SafetyCenterIssueKey safetyCenterIssueKey =
                        SafetyCenterIssueKey.newBuilder()
                                .setSafetySourceId(safetySourceId)
                                .setSafetySourceIssueId(safetySourceIssueId)
                                .setUserId(userId)
                                .build();
                SafetyCenterIssueActionId safetyCenterIssueActionId =
                        SafetyCenterIssueActionId.newBuilder()
                                .setSafetyCenterIssueKey(safetyCenterIssueKey)
                                .setSafetySourceIssueActionId(safetySourceIssueActionId)
                                .build();
                return unmarkSafetyCenterIssueActionAsInFlight(safetyCenterIssueActionId);
            case SafetyEvent.SAFETY_EVENT_TYPE_SOURCE_STATE_CHANGED:
            case SafetyEvent.SAFETY_EVENT_TYPE_DEVICE_LOCALE_CHANGED:
            case SafetyEvent.SAFETY_EVENT_TYPE_DEVICE_REBOOTED:
                return false;
        }
        Log.w(TAG, "Unexpected SafetyEvent.Type: " + type);
        return false;
    }

    @NonNull
    private SafetyCenterData getSafetyCenterData(
            @NonNull List<SafetySourcesGroup> safetySourcesGroups,
            @NonNull String packageName,
            @NonNull UserProfileGroup userProfileGroup) {
        int safetyCenterOverallSeverityLevel = SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;
        int safetyCenterEntriesSeverityLevel = SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;
        List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories = new ArrayList<>();
        List<SafetyCenterEntryOrGroup> safetyCenterEntryOrGroups = new ArrayList<>();
        List<SafetyCenterStaticEntryGroup> safetyCenterStaticEntryGroups = new ArrayList<>();
        SafetyCenterOverallStatusErrorState safetyCenterOverallStatusErrorState =
                new SafetyCenterOverallStatusErrorState();

        for (int i = 0; i < safetySourcesGroups.size(); i++) {
            SafetySourcesGroup safetySourcesGroup = safetySourcesGroups.get(i);

            safetyCenterOverallSeverityLevel =
                    Math.max(
                            safetyCenterOverallSeverityLevel,
                            addSafetyCenterIssues(
                                    safetyCenterIssuesWithCategories,
                                    safetySourcesGroup,
                                    userProfileGroup));
            int safetySourcesGroupType = safetySourcesGroup.getType();
            switch (safetySourcesGroupType) {
                case SafetySourcesGroup.SAFETY_SOURCES_GROUP_TYPE_COLLAPSIBLE:
                    safetyCenterEntriesSeverityLevel =
                            Math.max(
                                    safetyCenterEntriesSeverityLevel,
                                    addSafetyCenterEntryGroup(
                                            safetyCenterEntryOrGroups,
                                            safetySourcesGroup,
                                            safetyCenterOverallStatusErrorState,
                                            packageName,
                                            userProfileGroup));
                    break;
                case SafetySourcesGroup.SAFETY_SOURCES_GROUP_TYPE_RIGID:
                    addSafetyCenterStaticEntryGroup(
                            safetyCenterStaticEntryGroups,
                            safetySourcesGroup,
                            safetyCenterOverallStatusErrorState,
                            packageName,
                            userProfileGroup);
                    break;
                case SafetySourcesGroup.SAFETY_SOURCES_GROUP_TYPE_HIDDEN:
                    break;
                default:
                    Log.w(TAG, "Unexpected SafetySourceGroupType: " + safetySourcesGroupType);
                    break;
            }
        }

        boolean hasSettingsToReview =
                safetyCenterEntriesSeverityLevel > safetyCenterOverallSeverityLevel
                        || safetyCenterOverallStatusErrorState.mHasAtLeastOneUserVisibleError;
        safetyCenterIssuesWithCategories.sort(SAFETY_CENTER_ISSUES_BY_SEVERITY_DESCENDING);

        List<SafetyCenterIssue> safetyCenterIssues =
                new ArrayList<>(safetyCenterIssuesWithCategories.size());
        for (int i = 0; i < safetyCenterIssuesWithCategories.size(); i++) {
            safetyCenterIssues.add(safetyCenterIssuesWithCategories.get(i).getSafetyCenterIssue());
        }

        int refreshStatus = mSafetyCenterRefreshTracker.getRefreshStatus();
        return new SafetyCenterData(
                new SafetyCenterStatus.Builder(
                                getSafetyCenterStatusTitle(
                                        safetyCenterOverallSeverityLevel,
                                        safetyCenterIssuesWithCategories,
                                        refreshStatus,
                                        hasSettingsToReview),
                                getSafetyCenterStatusSummary(
                                        safetyCenterOverallSeverityLevel,
                                        refreshStatus,
                                        safetyCenterIssues.size(),
                                        hasSettingsToReview))
                        .setSeverityLevel(safetyCenterOverallSeverityLevel)
                        .setRefreshStatus(refreshStatus)
                        .build(),
                safetyCenterIssues,
                safetyCenterEntryOrGroups,
                safetyCenterStaticEntryGroups);
    }

    @SafetyCenterStatus.OverallSeverityLevel
    private int addSafetyCenterIssues(
            @NonNull List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories,
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @NonNull UserProfileGroup userProfileGroup) {
        int safetyCenterIssuesOverallSeverityLevel = SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;
        List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
        for (int i = 0; i < safetySources.size(); i++) {
            SafetySource safetySource = safetySources.get(i);

            if (!SafetySources.isExternal(safetySource)) {
                continue;
            }

            safetyCenterIssuesOverallSeverityLevel =
                    Math.max(
                            safetyCenterIssuesOverallSeverityLevel,
                            addSafetyCenterIssues(
                                    safetyCenterIssuesWithCategories,
                                    safetySource,
                                    userProfileGroup.getProfileParentUserId()));

            if (!SafetySources.supportsManagedProfiles(safetySource)) {
                continue;
            }

            int[] managedRunningProfilesUserIds =
                    userProfileGroup.getManagedRunningProfilesUserIds();
            for (int j = 0; j < managedRunningProfilesUserIds.length; j++) {
                int managedRunningProfileUserId = managedRunningProfilesUserIds[j];

                safetyCenterIssuesOverallSeverityLevel =
                        Math.max(
                                safetyCenterIssuesOverallSeverityLevel,
                                addSafetyCenterIssues(
                                        safetyCenterIssuesWithCategories,
                                        safetySource,
                                        managedRunningProfileUserId));
            }
        }

        return safetyCenterIssuesOverallSeverityLevel;
    }

    @SafetyCenterStatus.OverallSeverityLevel
    private int addSafetyCenterIssues(
            @NonNull List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories,
            @NonNull SafetySource safetySource,
            @UserIdInt int userId) {
        SafetySourceKey key = SafetySourceKey.of(safetySource.getId(), userId);
        SafetySourceData safetySourceData = mSafetySourceDataForKey.get(key);

        if (safetySourceData == null) {
            return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;
        }

        int safetyCenterIssuesOverallSeverityLevel = SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;

        List<SafetySourceIssue> safetySourceIssues = safetySourceData.getIssues();
        for (int i = 0; i < safetySourceIssues.size(); i++) {
            SafetySourceIssue safetySourceIssue = safetySourceIssues.get(i);
            SafetyCenterIssue safetyCenterIssue =
                    toSafetyCenterIssue(safetySourceIssue, safetySource, userId);
            if (safetyCenterIssue == null) {
                continue;
            }
            safetyCenterIssuesOverallSeverityLevel =
                    Math.max(
                            safetyCenterIssuesOverallSeverityLevel,
                            toSafetyCenterStatusOverallSeverityLevel(
                                    safetySourceIssue.getSeverityLevel()));
            safetyCenterIssuesWithCategories.add(
                    SafetyCenterIssueWithCategory.create(
                            safetyCenterIssue, safetySourceIssue.getIssueCategory()));
        }

        return safetyCenterIssuesOverallSeverityLevel;
    }

    @Nullable
    private SafetyCenterIssue toSafetyCenterIssue(
            @NonNull SafetySourceIssue safetySourceIssue,
            @NonNull SafetySource safetySource,
            @UserIdInt int userId) {
        SafetyCenterIssueId safetyCenterIssueId =
                SafetyCenterIssueId.newBuilder()
                        .setSafetyCenterIssueKey(
                                SafetyCenterIssueKey.newBuilder()
                                        .setSafetySourceId(safetySource.getId())
                                        .setSafetySourceIssueId(safetySourceIssue.getId())
                                        .setUserId(userId)
                                        .build())
                        .setIssueTypeId(safetySourceIssue.getIssueTypeId())
                        .build();

        if (isDismissed(
                safetyCenterIssueId.getSafetyCenterIssueKey(),
                safetySourceIssue.getSeverityLevel())) {
            return null;
        }

        List<SafetySourceIssue.Action> safetySourceIssueActions = safetySourceIssue.getActions();
        List<SafetyCenterIssue.Action> safetyCenterIssueActions =
                new ArrayList<>(safetySourceIssueActions.size());
        for (int i = 0; i < safetySourceIssueActions.size(); i++) {
            SafetySourceIssue.Action safetySourceIssueAction = safetySourceIssueActions.get(i);

            safetyCenterIssueActions.add(
                    toSafetyCenterIssueAction(
                            safetySourceIssueAction,
                            safetyCenterIssueId.getSafetyCenterIssueKey()));
        }

        int safetyCenterIssueSeverityLevel =
                toSafetyCenterIssueSeverityLevel(safetySourceIssue.getSeverityLevel());
        return new SafetyCenterIssue.Builder(
                        SafetyCenterIds.encodeToString(safetyCenterIssueId),
                        safetySourceIssue.getTitle(),
                        safetySourceIssue.getSummary())
                .setSeverityLevel(safetyCenterIssueSeverityLevel)
                .setShouldConfirmDismissal(
                        safetyCenterIssueSeverityLevel > SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK)
                .setSubtitle(safetySourceIssue.getSubtitle())
                .setActions(safetyCenterIssueActions)
                .build();
    }

    @NonNull
    private SafetyCenterIssue.Action toSafetyCenterIssueAction(
            @NonNull SafetySourceIssue.Action safetySourceIssueAction,
            @NonNull SafetyCenterIssueKey safetyCenterIssueKey) {
        SafetyCenterIssueActionId safetyCenterIssueActionId =
                SafetyCenterIssueActionId.newBuilder()
                        .setSafetyCenterIssueKey(safetyCenterIssueKey)
                        .setSafetySourceIssueActionId(safetySourceIssueAction.getId())
                        .build();
        return new SafetyCenterIssue.Action.Builder(
                        SafetyCenterIds.encodeToString(safetyCenterIssueActionId),
                        safetySourceIssueAction.getLabel(),
                        safetySourceIssueAction.getPendingIntent())
                .setSuccessMessage(safetySourceIssueAction.getSuccessMessage())
                .setIsInFlight(isInFlight(safetyCenterIssueActionId))
                .setWillResolve(safetySourceIssueAction.willResolve())
                .build();
    }

    @SafetyCenterStatus.OverallSeverityLevel
    private int addSafetyCenterEntryGroup(
            @NonNull List<SafetyCenterEntryOrGroup> safetyCenterEntryOrGroups,
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @NonNull SafetyCenterOverallStatusErrorState safetyCenterOverallStatusErrorState,
            @NonNull String defaultPackageName,
            @NonNull UserProfileGroup userProfileGroup) {
        int groupSafetyCenterEntryLevel = SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN;

        List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
        List<SafetyCenterEntry> entries = new ArrayList<>(safetySources.size());
        for (int i = 0; i < safetySources.size(); i++) {
            SafetySource safetySource = safetySources.get(i);

            groupSafetyCenterEntryLevel =
                    Math.max(
                            groupSafetyCenterEntryLevel,
                            addSafetyCenterEntry(
                                    entries,
                                    safetySource,
                                    safetyCenterOverallStatusErrorState,
                                    defaultPackageName,
                                    userProfileGroup.getProfileParentUserId(),
                                    false,
                                    false));

            if (!SafetySources.supportsManagedProfiles(safetySource)) {
                continue;
            }

            int[] managedProfilesUserIds = userProfileGroup.getManagedProfilesUserIds();
            for (int j = 0; j < managedProfilesUserIds.length; j++) {
                int managedProfileUserId = managedProfilesUserIds[j];
                boolean isManagedUserRunning =
                        userProfileGroup.isManagedUserRunning(managedProfileUserId);

                groupSafetyCenterEntryLevel =
                        Math.max(
                                groupSafetyCenterEntryLevel,
                                addSafetyCenterEntry(
                                        entries,
                                        safetySource,
                                        safetyCenterOverallStatusErrorState,
                                        defaultPackageName,
                                        managedProfileUserId,
                                        true,
                                        isManagedUserRunning));
            }
        }

        if (entries.size() == 1) {
            safetyCenterEntryOrGroups.add(new SafetyCenterEntryOrGroup(entries.get(0)));
        } else if (entries.size() > 1) {
            SafetyCenterEntryGroupId safetyCenterEntryGroupId =
                    SafetyCenterEntryGroupId.newBuilder()
                            .setSafetySourcesGroupId(safetySourcesGroup.getId())
                            .build();
            CharSequence groupSummary =
                    getSafetyCenterEntryGroupSummary(
                            safetySourcesGroup, groupSafetyCenterEntryLevel, entries);
            safetyCenterEntryOrGroups.add(
                    new SafetyCenterEntryOrGroup(
                            new SafetyCenterEntryGroup.Builder(
                                            SafetyCenterIds.encodeToString(
                                                    safetyCenterEntryGroupId),
                                            mSafetyCenterResourcesContext.getString(
                                                    safetySourcesGroup.getTitleResId()))
                                    .setSeverityLevel(groupSafetyCenterEntryLevel)
                                    .setSummary(groupSummary)
                                    .setEntries(entries)
                                    .setSeverityUnspecifiedIconType(
                                            toGroupSeverityUnspecifiedIconType(
                                                    safetySourcesGroup.getStatelessIconType()))
                                    .build()));
        }
        return entryToSafetyCenterStatusOverallSeverityLevel(groupSafetyCenterEntryLevel);
    }

    @Nullable
    private CharSequence getSafetyCenterEntryGroupSummary(
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @SafetyCenterEntry.EntrySeverityLevel int groupSafetyCenterEntryLevel,
            @NonNull List<SafetyCenterEntry> entries) {

        // if there is any critical entry, use the summary of the first critical entry
        // else if there is any recommendation entry, use the summary of the first recommendation
        // entry
        if (groupSafetyCenterEntryLevel >= SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION) {
            for (int i = 0; i < entries.size(); i++) {
                SafetyCenterEntry entry = entries.get(i);

                CharSequence entrySummary = entry.getSummary();
                if (entry.getSeverityLevel() == groupSafetyCenterEntryLevel
                        && entrySummary != null) {
                    return entrySummary;
                }
            }
        }

        // else if there is any information entry with an information issue, use the summary of
        // the first information source with an information issue
        if (groupSafetyCenterEntryLevel >= SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK) {
            for (int i = 0; i < entries.size(); i++) {
                SafetyCenterEntry entry = entries.get(i);
                CharSequence entrySummary = entry.getSummary();
                if (entry.getSeverityLevel() == SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK
                        && entrySummary != null) {
                    SafetySourceKey key = toSafetySourceKey(entry.getId());
                    SafetySourceData safetySourceData = mSafetySourceDataForKey.get(key);
                    boolean hasIssues =
                            safetySourceData != null && !safetySourceData.getIssues().isEmpty();
                    if (hasIssues) {
                        return entrySummary;
                    }
                }
            }
        }

        // else if there is any information entry, use the default summary
        if (groupSafetyCenterEntryLevel >= SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK) {
            return getDefaultGroupSummary(safetySourcesGroup, entries);
        }

        // else if all entry are unspecified, use the default summary
        if (groupSafetyCenterEntryLevel >= SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED) {
            boolean areAllEntriesUnspecified = true;
            for (int i = 0; i < entries.size(); i++) {
                SafetyCenterEntry entry = entries.get(i);
                if (entry.getSeverityLevel()
                        != SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED) {
                    areAllEntriesUnspecified = false;
                    break;
                }
            }
            if (areAllEntriesUnspecified) {
                return getDefaultGroupSummary(safetySourcesGroup, entries);
            }
        }

        // else if there is an unknown entry with an error, show the error state summary
        int errorEntries = 0;
        for (int i = 0; i < entries.size(); i++) {
            SafetyCenterEntry entry = entries.get(i);
            SafetySourceKey key = toSafetySourceKey(entry.getId());
            if (mSafetySourceErrors.contains(key)) {
                errorEntries++;
            }
        }
        if (errorEntries > 0) {
            return getRefreshErrorString(errorEntries);
        }

        // else show the unknown state summary
        return mSafetyCenterResourcesContext.getStringByName("group_unknown_summary");
    }

    @SafetyCenterEntry.EntrySeverityLevel
    private int addSafetyCenterEntry(
            @NonNull List<SafetyCenterEntry> entries,
            @NonNull SafetySource safetySource,
            @NonNull SafetyCenterOverallStatusErrorState safetyCenterOverallStatusErrorState,
            @NonNull String defaultPackageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        SafetyCenterEntry safetyCenterEntry =
                toSafetyCenterEntry(
                        safetySource,
                        defaultPackageName,
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
        if (safetyCenterEntry == null) {
            return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN;
        }

        safetyCenterOverallStatusErrorState.mHasAtLeastOneUserVisibleError |=
                mSafetySourceErrors.contains(SafetySourceKey.of(safetySource.getId(), userId));
        entries.add(safetyCenterEntry);

        return safetyCenterEntry.getSeverityLevel();
    }

    @Nullable
    private SafetyCenterEntry toSafetyCenterEntry(
            @NonNull SafetySource safetySource,
            @NonNull String defaultPackageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        switch (safetySource.getType()) {
            case SafetySource.SAFETY_SOURCE_TYPE_ISSUE_ONLY:
                return null;
            case SafetySource.SAFETY_SOURCE_TYPE_DYNAMIC:
                SafetySourceKey key = SafetySourceKey.of(safetySource.getId(), userId);
                SafetySourceStatus safetySourceStatus =
                        getSafetySourceStatus(mSafetySourceDataForKey.get(key));
                boolean defaultEntryDueToQuietMode = isUserManaged && !isManagedUserRunning;
                if (safetySourceStatus != null && !defaultEntryDueToQuietMode) {
                    PendingIntent pendingIntent = safetySourceStatus.getPendingIntent();
                    boolean enabled = safetySourceStatus.isEnabled();
                    if (pendingIntent == null) {
                        pendingIntent =
                                toPendingIntent(
                                        safetySource.getIntentAction(),
                                        safetySource.getPackageName(),
                                        userId,
                                        false);
                        enabled = enabled && pendingIntent != null;
                    }
                    SafetyCenterEntryId safetyCenterEntryId =
                            SafetyCenterEntryId.newBuilder()
                                    .setSafetySourceId(safetySource.getId())
                                    .setUserId(userId)
                                    .build();
                    int severityUnspecifiedIconType =
                            SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION;
                    SafetyCenterEntry.Builder builder =
                            new SafetyCenterEntry.Builder(
                                            SafetyCenterIds.encodeToString(safetyCenterEntryId),
                                            safetySourceStatus.getTitle())
                                    .setSeverityLevel(
                                            toSafetyCenterEntrySeverityLevel(
                                                    safetySourceStatus.getSeverityLevel()))
                                    .setSummary(safetySourceStatus.getSummary())
                                    .setEnabled(enabled)
                                    .setSeverityUnspecifiedIconType(severityUnspecifiedIconType)
                                    .setPendingIntent(pendingIntent);
                    SafetySourceStatus.IconAction iconAction = safetySourceStatus.getIconAction();
                    if (iconAction == null) {
                        return builder.build();
                    }
                    PendingIntent iconActionPendingIntent =
                            toIconActionPendingIntent(
                                    safetySource.getId(), iconAction.getPendingIntent());
                    builder.setIconAction(
                            new SafetyCenterEntry.IconAction(
                                    toSafetyCenterEntryIconActionType(iconAction.getIconType()),
                                    iconActionPendingIntent));
                    return builder.build();
                }
                return toDefaultSafetyCenterEntry(
                        safetySource,
                        safetySource.getPackageName(),
                        SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN,
                        SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION,
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
            case SafetySource.SAFETY_SOURCE_TYPE_STATIC:
                return toDefaultSafetyCenterEntry(
                        safetySource,
                        defaultPackageName,
                        SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED,
                        SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_ICON,
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
        }
        Log.w(
                TAG,
                "Unknown safety source type found in collapsible group: " + safetySource.getType());
        return null;
    }

    @Nullable
    private SafetyCenterEntry toDefaultSafetyCenterEntry(
            @NonNull SafetySource safetySource,
            @NonNull String packageName,
            @SafetyCenterEntry.EntrySeverityLevel int entrySeverityLevel,
            @SafetyCenterEntry.SeverityUnspecifiedIconType int severityUnspecifiedIconType,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        if (SafetySources.isDefaultEntryHidden(safetySource)) {
            return null;
        }

        SafetyCenterEntryId safetyCenterEntryId =
                SafetyCenterEntryId.newBuilder()
                        .setSafetySourceId(safetySource.getId())
                        .setUserId(userId)
                        .build();
        boolean isQuietModeEnabled = isUserManaged && !isManagedUserRunning;
        PendingIntent pendingIntent =
                toPendingIntent(
                        safetySource.getIntentAction(), packageName, userId, isQuietModeEnabled);
        boolean enabled =
                pendingIntent != null && !SafetySources.isDefaultEntryDisabled(safetySource);
        CharSequence title =
                isUserManaged
                        ? DevicePolicyResources.getSafetySourceWorkString(
                                mSafetyCenterResourcesContext,
                                safetySource.getId(),
                                safetySource.getTitleForWorkResId())
                        : mSafetyCenterResourcesContext.getString(safetySource.getTitleResId());
        CharSequence summary =
                mSafetySourceErrors.contains(SafetySourceKey.of(safetySource.getId(), userId))
                        ? getRefreshErrorString(1)
                        : mSafetyCenterResourcesContext.getOptionalString(
                                safetySource.getSummaryResId());
        if (isQuietModeEnabled) {
            enabled = false;
            summary =
                    DevicePolicyResources.getWorkProfilePausedString(mSafetyCenterResourcesContext);
        }
        return new SafetyCenterEntry.Builder(
                        SafetyCenterIds.encodeToString(safetyCenterEntryId), title)
                .setSeverityLevel(entrySeverityLevel)
                .setSummary(summary)
                .setEnabled(enabled)
                .setPendingIntent(pendingIntent)
                .setSeverityUnspecifiedIconType(severityUnspecifiedIconType)
                .build();
    }

    private void addSafetyCenterStaticEntryGroup(
            @NonNull List<SafetyCenterStaticEntryGroup> safetyCenterStaticEntryGroups,
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @NonNull SafetyCenterOverallStatusErrorState safetyCenterOverallStatusErrorState,
            @NonNull String defaultPackageName,
            @NonNull UserProfileGroup userProfileGroup) {
        List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
        List<SafetyCenterStaticEntry> staticEntries = new ArrayList<>(safetySources.size());
        for (int i = 0; i < safetySources.size(); i++) {
            SafetySource safetySource = safetySources.get(i);

            addSafetyCenterStaticEntry(
                    staticEntries,
                    safetySource,
                    safetyCenterOverallStatusErrorState,
                    defaultPackageName,
                    userProfileGroup.getProfileParentUserId(),
                    false,
                    false);

            if (!SafetySources.supportsManagedProfiles(safetySource)) {
                continue;
            }

            int[] managedProfilesUserIds = userProfileGroup.getManagedProfilesUserIds();
            for (int j = 0; j < managedProfilesUserIds.length; j++) {
                int managedProfileUserId = managedProfilesUserIds[j];
                boolean isManagedUserRunning =
                        userProfileGroup.isManagedUserRunning(managedProfileUserId);

                addSafetyCenterStaticEntry(
                        staticEntries,
                        safetySource,
                        safetyCenterOverallStatusErrorState,
                        defaultPackageName,
                        managedProfileUserId,
                        true,
                        isManagedUserRunning);
            }
        }

        safetyCenterStaticEntryGroups.add(
                new SafetyCenterStaticEntryGroup(
                        mSafetyCenterResourcesContext.getString(safetySourcesGroup.getTitleResId()),
                        staticEntries));
    }

    private void addSafetyCenterStaticEntry(
            @NonNull List<SafetyCenterStaticEntry> staticEntries,
            @NonNull SafetySource safetySource,
            @NonNull SafetyCenterOverallStatusErrorState safetyCenterOverallStatusErrorState,
            @NonNull String defaultPackageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        SafetyCenterStaticEntry staticEntry =
                toSafetyCenterStaticEntry(
                        safetySource,
                        defaultPackageName,
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
        if (staticEntry == null) {
            return;
        }
        safetyCenterOverallStatusErrorState.mHasAtLeastOneUserVisibleError |=
                mSafetySourceErrors.contains(SafetySourceKey.of(safetySource.getId(), userId));
        staticEntries.add(staticEntry);
    }

    @Nullable
    private SafetyCenterStaticEntry toSafetyCenterStaticEntry(
            @NonNull SafetySource safetySource,
            @NonNull String defaultPackageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        switch (safetySource.getType()) {
            case SafetySource.SAFETY_SOURCE_TYPE_ISSUE_ONLY:
                return null;
            case SafetySource.SAFETY_SOURCE_TYPE_DYNAMIC:
                SafetySourceKey key = SafetySourceKey.of(safetySource.getId(), userId);
                SafetySourceStatus safetySourceStatus =
                        getSafetySourceStatus(mSafetySourceDataForKey.get(key));
                boolean defaultEntryDueToQuietMode = isUserManaged && !isManagedUserRunning;
                if (safetySourceStatus != null && !defaultEntryDueToQuietMode) {
                    PendingIntent pendingIntent = safetySourceStatus.getPendingIntent();
                    if (pendingIntent == null) {
                        // TODO(b/222838784): Decide strategy for static entries when the intent is
                        //  null.
                        return null;
                    }
                    return new SafetyCenterStaticEntry.Builder(safetySourceStatus.getTitle())
                            .setSummary(safetySourceStatus.getSummary())
                            .setPendingIntent(pendingIntent)
                            .build();
                }
                return toDefaultSafetyCenterStaticEntry(
                        safetySource,
                        safetySource.getPackageName(),
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
            case SafetySource.SAFETY_SOURCE_TYPE_STATIC:
                return toDefaultSafetyCenterStaticEntry(
                        safetySource,
                        defaultPackageName,
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
        }
        Log.w(TAG, "Unknown safety source type found in rigid group: " + safetySource.getType());
        return null;
    }

    @Nullable
    private SafetyCenterStaticEntry toDefaultSafetyCenterStaticEntry(
            @NonNull SafetySource safetySource,
            @NonNull String packageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        if (SafetySources.isDefaultEntryHidden(safetySource)) {
            return null;
        }
        boolean isQuietModeEnabled = isUserManaged && !isManagedUserRunning;
        PendingIntent pendingIntent =
                toPendingIntent(
                        safetySource.getIntentAction(), packageName, userId, isQuietModeEnabled);

        if (pendingIntent == null) {
            // TODO(b/222838784): Decide strategy for static entries when the intent is null.
            return null;
        }

        CharSequence title =
                isUserManaged
                        ? DevicePolicyResources.getSafetySourceWorkString(
                                mSafetyCenterResourcesContext,
                                safetySource.getId(),
                                safetySource.getTitleForWorkResId())
                        : mSafetyCenterResourcesContext.getString(safetySource.getTitleResId());
        CharSequence summary =
                mSafetySourceErrors.contains(SafetySourceKey.of(safetySource.getId(), userId))
                        ? getRefreshErrorString(1)
                        : mSafetyCenterResourcesContext.getOptionalString(
                                safetySource.getSummaryResId());
        if (isQuietModeEnabled) {
            summary =
                    DevicePolicyResources.getWorkProfilePausedString(mSafetyCenterResourcesContext);
        }
        return new SafetyCenterStaticEntry.Builder(title)
                .setSummary(summary)
                .setPendingIntent(pendingIntent)
                .build();
    }

    @Nullable
    private PendingIntent toPendingIntent(
            @Nullable String intentAction,
            @NonNull String packageName,
            @UserIdInt int userId,
            boolean isQuietModeEnabled) {
        if (intentAction == null) {
            return null;
        }
        Context context = toPackageContextAsUser(packageName, userId);
        if (context == null) {
            return null;
        }
        if (!isIntentActionValid(context, intentAction, userId, isQuietModeEnabled)) {
            return null;
        }
        return toPendingIntent(context, 0, new Intent(intentAction));
    }

    private boolean isIntentActionValid(
            @NonNull Context context,
            @NonNull String intentAction,
            @UserIdInt int userId,
            boolean isQuietModeEnabled) {
        // TODO(b/241743286): queryIntentActivities does not return any activity when work profile
        //  is in quiet mode.
        if (isQuietModeEnabled) {
            return true;
        }
        Intent intent = new Intent(intentAction);
        PackageManager packageManager = context.getPackageManager();
        List<ResolveInfo> activities =
                packageManager.queryIntentActivitiesAsUser(
                        intent, ResolveInfoFlags.of(0), UserHandle.of(userId));
        return !activities.isEmpty();
    }

    @NonNull
    private static PendingIntent toPendingIntent(
            @NonNull Context packageContext, int requestCode, @NonNull Intent intent) {
        // This call is required for getIntentSender() to be allowed to send as another package.
        final long identity = Binder.clearCallingIdentity();
        try {
            return PendingIntent.getActivity(
                    packageContext, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Potentially overrides the Settings IconAction PendingIntent for the AndroidLockScreen source.
     *
     * <p>This is done because of a bug in the Settings app where the PendingIntent created ends up
     * referencing the one from the main entry. The reason for this is that PendingIntent instances
     * are cached and keyed by an object which does not take into account the underlying intent
     * extras; and these two intents only differ by the extras that they set. We fix this issue by
     * recreating the desired Intent and PendingIntent here, using a specific request code for the
     * PendingIntent to ensure a new instance is created (the key does take into account the request
     * code).
     */
    @NonNull
    private PendingIntent toIconActionPendingIntent(
            @NonNull String sourceId, @NonNull PendingIntent pendingIntent) {
        if (!ANDROID_LOCK_SCREEN_SOURCE_ID.equals(sourceId)) {
            return pendingIntent;
        }
        if (!SafetyCenterFlags.getReplaceLockScreenIconAction()) {
            return pendingIntent;
        }
        String settingsPackageName = pendingIntent.getCreatorPackage();
        int userId = pendingIntent.getCreatorUserHandle().getIdentifier();
        Context packageContext = toPackageContextAsUser(settingsPackageName, userId);
        if (packageContext == null) {
            return pendingIntent;
        }
        Resources settingsResources = packageContext.getResources();
        int hasSettingsFixedIssueResourceId =
                settingsResources.getIdentifier(
                        "config_isSafetyCenterLockScreenPendingIntentFixed",
                        "bool",
                        settingsPackageName);
        if (hasSettingsFixedIssueResourceId != Resources.ID_NULL) {
            boolean hasSettingsFixedIssue =
                    settingsResources.getBoolean(hasSettingsFixedIssueResourceId);
            if (hasSettingsFixedIssue) {
                return pendingIntent;
            }
        }
        Intent intent =
                new Intent(Intent.ACTION_MAIN)
                        .setComponent(
                                new ComponentName(
                                        settingsPackageName, settingsPackageName + ".SubSettings"))
                        .putExtra(
                                ":settings:show_fragment",
                                settingsPackageName + ".security.screenlock.ScreenLockSettings")
                        .putExtra(":settings:source_metrics", 1917)
                        .putExtra("page_transition_type", 0);
        PendingIntent offendingPendingIntent = toPendingIntent(packageContext, 0, intent);
        if (!offendingPendingIntent.equals(pendingIntent)) {
            return pendingIntent;
        }
        // If creating that PendingIntent with request code 0 returns the same value as the
        // PendingIntent that was sent to Safety Center, then we’re most likely hitting the caching
        // issue described in this method’s documentation.
        // i.e. the intent action and component of the cached PendingIntent are the same, but the
        // extras are actually different so we should ensure we create a brand new PendingIntent by
        // changing the request code.
        return toPendingIntent(packageContext, ANDROID_LOCK_SCREEN_ICON_ACTION_REQ_CODE, intent);
    }

    @Nullable
    private Context toPackageContextAsUser(@NonNull String packageName, @UserIdInt int userId) {
        // This call requires the INTERACT_ACROSS_USERS permission.
        final long identity = Binder.clearCallingIdentity();
        try {
            return mContext.createPackageContextAsUser(packageName, 0, UserHandle.of(userId));
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Package name " + packageName + " not found", e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Nullable
    private static SafetySourceStatus getSafetySourceStatus(
            @Nullable SafetySourceData safetySourceData) {
        if (safetySourceData == null) {
            return null;
        }

        return safetySourceData.getStatus();
    }

    @SafetyCenterStatus.OverallSeverityLevel
    private static int toSafetyCenterStatusOverallSeverityLevel(
            @SafetySourceData.SeverityLevel int safetySourceSeverityLevel) {
        switch (safetySourceSeverityLevel) {
            case SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED:
            case SafetySourceData.SEVERITY_LEVEL_INFORMATION:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;
            case SafetySourceData.SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetySourceData.SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        Log.w(TAG, "Unexpected SafetySourceData.SeverityLevel: " + safetySourceSeverityLevel);
        return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
    }

    @SafetyCenterStatus.OverallSeverityLevel
    private static int entryToSafetyCenterStatusOverallSeverityLevel(
            @SafetyCenterEntry.EntrySeverityLevel int safetyCenterEntrySeverityLevel) {
        switch (safetyCenterEntrySeverityLevel) {
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED:
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        Log.w(
                TAG,
                "Unexpected SafetyCenterEntry.EntrySeverityLevel: "
                        + safetyCenterEntrySeverityLevel);
        return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
    }

    @SafetyCenterEntry.EntrySeverityLevel
    private static int toSafetyCenterEntrySeverityLevel(
            @SafetySourceData.SeverityLevel int safetySourceSeverityLevel) {
        switch (safetySourceSeverityLevel) {
            case SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED;
            case SafetySourceData.SEVERITY_LEVEL_INFORMATION:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK;
            case SafetySourceData.SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetySourceData.SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        Log.w(
                TAG,
                "Unexpected SafetySourceData.SeverityLevel in SafetySourceStatus: "
                        + safetySourceSeverityLevel);
        return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN;
    }

    @SafetyCenterIssue.IssueSeverityLevel
    private static int toSafetyCenterIssueSeverityLevel(
            @SafetySourceData.SeverityLevel int safetySourceIssueSeverityLevel) {
        switch (safetySourceIssueSeverityLevel) {
            case SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED:
                Log.w(
                        TAG,
                        "Unexpected use of SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED in "
                                + "SafetySourceIssue");
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK;
            case SafetySourceData.SEVERITY_LEVEL_INFORMATION:
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK;
            case SafetySourceData.SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetySourceData.SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        Log.w(
                TAG,
                "Unexpected SafetySourceData.SeverityLevel in SafetySourceIssue: "
                        + safetySourceIssueSeverityLevel);
        return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK;
    }

    @SafetyCenterEntry.SeverityUnspecifiedIconType
    private static int toGroupSeverityUnspecifiedIconType(
            @SafetySourcesGroup.StatelessIconType int statelessIconType) {
        switch (statelessIconType) {
            case SafetySourcesGroup.STATELESS_ICON_TYPE_NONE:
                return SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_ICON;
            case SafetySourcesGroup.STATELESS_ICON_TYPE_PRIVACY:
                return SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_PRIVACY;
        }

        Log.w(TAG, "Unexpected SafetySourcesGroup.StatelessIconType: " + statelessIconType);
        return SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_ICON;
    }

    @SafetyCenterEntry.IconAction.IconActionType
    private static int toSafetyCenterEntryIconActionType(
            @SafetySourceStatus.IconAction.IconType int safetySourceIconActionType) {
        switch (safetySourceIconActionType) {
            case SafetySourceStatus.IconAction.ICON_TYPE_GEAR:
                return SafetyCenterEntry.IconAction.ICON_ACTION_TYPE_GEAR;
            case SafetySourceStatus.IconAction.ICON_TYPE_INFO:
                return SafetyCenterEntry.IconAction.ICON_ACTION_TYPE_INFO;
        }

        Log.w(
                TAG,
                "Unexpected SafetySourceStatus.IconAction.IconActionType: "
                        + safetySourceIconActionType);
        return SafetyCenterEntry.IconAction.ICON_ACTION_TYPE_INFO;
    }

    private String getSafetyCenterStatusTitle(
            @SafetyCenterStatus.OverallSeverityLevel int overallSeverityLevel,
            @NonNull List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories,
            @SafetyCenterStatus.RefreshStatus int refreshStatus,
            boolean hasSettingsToReview) {
        String refreshStatusTitle = getSafetyCenterRefreshStatusTitle(refreshStatus);
        if (refreshStatusTitle != null) {
            return refreshStatusTitle;
        }
        switch (overallSeverityLevel) {
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN:
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK:
                if (hasSettingsToReview) {
                    return mSafetyCenterResourcesContext.getStringByName(
                            "overall_severity_level_ok_review_title");
                }
                return mSafetyCenterResourcesContext.getStringByName(
                        "overall_severity_level_ok_title");
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION:
                return getStatusTitleFromIssueCategories(
                        safetyCenterIssuesWithCategories,
                        "overall_severity_level_device_recommendation_title",
                        "overall_severity_level_account_recommendation_title",
                        "overall_severity_level_safety_recommendation_title");
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING:
                return getStatusTitleFromIssueCategories(
                        safetyCenterIssuesWithCategories,
                        "overall_severity_level_critical_device_warning_title",
                        "overall_severity_level_critical_account_warning_title",
                        "overall_severity_level_critical_safety_warning_title");
        }

        Log.w(TAG, "Unexpected SafetyCenterStatus.OverallSeverityLevel: " + overallSeverityLevel);
        return "";
    }

    @NonNull
    private String getStatusTitleFromIssueCategories(
            @NonNull List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories,
            @NonNull String deviceResourceName,
            @NonNull String accountResourceName,
            @NonNull String generalResourceName) {
        String generalString = mSafetyCenterResourcesContext.getStringByName(generalResourceName);
        if (safetyCenterIssuesWithCategories.isEmpty()) {
            Log.w(TAG, "No safety center issues found in a non-green status");
            return generalString;
        }
        int issueCategory = safetyCenterIssuesWithCategories.get(0).getSafetyCenterIssueCategory();
        switch (issueCategory) {
            case SafetySourceIssue.ISSUE_CATEGORY_DEVICE:
                return mSafetyCenterResourcesContext.getStringByName(deviceResourceName);
            case SafetySourceIssue.ISSUE_CATEGORY_ACCOUNT:
                return mSafetyCenterResourcesContext.getStringByName(accountResourceName);
            case SafetySourceIssue.ISSUE_CATEGORY_GENERAL:
                return generalString;
        }

        Log.w(TAG, "Unexpected issueCategory found: " + issueCategory);
        return generalString;
    }

    private String getSafetyCenterStatusSummary(
            @SafetyCenterStatus.OverallSeverityLevel int overallSeverityLevel,
            @SafetyCenterStatus.RefreshStatus int refreshStatus,
            int numberOfIssues,
            boolean hasSettingsToReview) {
        String refreshStatusSummary = getSafetyCenterRefreshStatusSummary(refreshStatus);
        if (refreshStatusSummary != null) {
            return refreshStatusSummary;
        }
        switch (overallSeverityLevel) {
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN:
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK:
                if (numberOfIssues == 0) {
                    if (hasSettingsToReview) {
                        return mSafetyCenterResourcesContext.getStringByName(
                                "overall_severity_level_ok_review_summary");
                    }
                    return mSafetyCenterResourcesContext.getStringByName(
                            "overall_severity_level_ok_summary");
                }
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION:
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING:
                return getIcuPluralsString("overall_severity_n_alerts_summary", numberOfIssues);
        }

        Log.w(TAG, "Unexpected SafetyCenterStatus.OverallSeverityLevel: " + overallSeverityLevel);
        return "";
    }

    @Nullable
    private CharSequence getDefaultGroupSummary(
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @NonNull List<SafetyCenterEntry> entries) {
        CharSequence groupSummary =
                mSafetyCenterResourcesContext.getOptionalString(
                        safetySourcesGroup.getSummaryResId());

        if (safetySourcesGroup.getId().equals(ANDROID_LOCK_SCREEN_SOURCES_GROUP_ID)
                && TextUtils.isEmpty(groupSummary)) {
            List<CharSequence> titles = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                titles.add(entries.get(i).getTitle());
            }
            groupSummary =
                    ListFormatter.getInstance(
                                    ULocale.getDefault(ULocale.Category.FORMAT),
                                    ListFormatter.Type.UNITS,
                                    ListFormatter.Width.WIDE)
                            .format(titles);
        }

        return groupSummary;
    }

    @NonNull
    private String getRefreshErrorString(int numberOfErrorEntries) {
        return getIcuPluralsString("refresh_error", numberOfErrorEntries);
    }

    @NonNull
    private String getIcuPluralsString(String name, int count, @NonNull Object... formatArgs) {
        MessageFormat messageFormat =
                new MessageFormat(
                        mSafetyCenterResourcesContext.getStringByName(name, formatArgs),
                        Locale.getDefault());
        ArrayMap<String, Object> arguments = new ArrayMap<>();
        arguments.put("count", count);
        return messageFormat.format(arguments);
    }

    @Nullable
    private String getSafetyCenterRefreshStatusTitle(
            @SafetyCenterStatus.RefreshStatus int refreshStatus) {
        switch (refreshStatus) {
            case SafetyCenterStatus.REFRESH_STATUS_NONE:
            case SafetyCenterStatus.REFRESH_STATUS_DATA_FETCH_IN_PROGRESS:
                return null;
            case SafetyCenterStatus.REFRESH_STATUS_FULL_RESCAN_IN_PROGRESS:
                return mSafetyCenterResourcesContext.getStringByName("scanning_title");
        }

        Log.w(TAG, "Unexpected SafetyCenterStatus.RefreshStatus: " + refreshStatus);
        return null;
    }

    @Nullable
    private String getSafetyCenterRefreshStatusSummary(
            @SafetyCenterStatus.RefreshStatus int refreshStatus) {
        switch (refreshStatus) {
            case SafetyCenterStatus.REFRESH_STATUS_NONE:
                return null;
            case SafetyCenterStatus.REFRESH_STATUS_DATA_FETCH_IN_PROGRESS:
            case SafetyCenterStatus.REFRESH_STATUS_FULL_RESCAN_IN_PROGRESS:
                return mSafetyCenterResourcesContext.getStringByName("loading_summary");
        }

        Log.w(TAG, "Unexpected SafetyCenterStatus.RefreshStatus: " + refreshStatus);
        return null;
    }

    @NonNull
    SafetySourceKey toSafetySourceKey(@NonNull String safetyCenterEntryIdString) {
        SafetyCenterEntryId id = SafetyCenterIds.entryIdFromString(safetyCenterEntryIdString);
        return SafetySourceKey.of(id.getSafetySourceId(), id.getUserId());
    }

    /** Wrapper that encapsulates both {@link SafetyCenterIssue} and its category. */
    private static final class SafetyCenterIssueWithCategory {
        @NonNull private final SafetyCenterIssue mSafetyCenterIssue;
        @SafetySourceIssue.IssueCategory private final int mSafetyCenterIssueCategory;

        private SafetyCenterIssueWithCategory(
                @NonNull SafetyCenterIssue safetyCenterIssue,
                @SafetySourceIssue.IssueCategory int safetyCenterIssueCategory) {
            this.mSafetyCenterIssue = safetyCenterIssue;
            this.mSafetyCenterIssueCategory = safetyCenterIssueCategory;
        }

        @NonNull
        public SafetyCenterIssue getSafetyCenterIssue() {
            return mSafetyCenterIssue;
        }

        @SafetySourceIssue.IssueCategory
        public int getSafetyCenterIssueCategory() {
            return mSafetyCenterIssueCategory;
        }

        public static SafetyCenterIssueWithCategory create(
                @NonNull SafetyCenterIssue safetyCenterIssue,
                @SafetySourceIssue.IssueCategory int safetyCenterIssueCategory) {
            return new SafetyCenterIssueWithCategory(safetyCenterIssue, safetyCenterIssueCategory);
        }
    }

    /** A comparator to order {@link SafetyCenterIssueWithCategory} by severity level descending. */
    private static final class SafetyCenterIssuesBySeverityDescending
            implements Comparator<SafetyCenterIssueWithCategory> {

        SafetyCenterIssuesBySeverityDescending() {}

        @Override
        public int compare(
                @NonNull SafetyCenterIssueWithCategory left,
                @NonNull SafetyCenterIssueWithCategory right) {
            return Integer.compare(
                    right.getSafetyCenterIssue().getSeverityLevel(),
                    left.getSafetyCenterIssue().getSeverityLevel());
        }
    }

    /**
     * An internal mutable data structure to track extra metadata associated with a {@link
     * SafetyCenterIssue}.
     */
    private static final class IssueData {
        @NonNull private final Instant mFirstSeenAt;
        @Nullable private Instant mDismissedAt;
        private int mDismissCount;

        private IssueData(@NonNull Instant firstSeenAt) {
            mFirstSeenAt = firstSeenAt;
        }

        @NonNull
        public Instant getFirstSeenAt() {
            return mFirstSeenAt;
        }

        @Nullable
        public Instant getDismissedAt() {
            return mDismissedAt;
        }

        public void setDismissedAt(@Nullable Instant dismissedAt) {
            mDismissedAt = dismissedAt;
        }

        public int getDismissCount() {
            return mDismissCount;
        }

        public void setDismissCount(int dismissCount) {
            mDismissCount = dismissCount;
        }

        @Override
        public String toString() {
            return "IssueData{"
                    + "mFirstSeenAt="
                    + mFirstSeenAt
                    + ", mDismissedAt="
                    + mDismissedAt
                    + ", mDismissCount="
                    + mDismissCount
                    + '}';
        }
    }

    /**
     * An internal mutable class to keep track of the overall {@link SafetyCenterStatus} error
     * state; i.e. whether there is at least one user-visible entry that is showing an error.
     */
    private static final class SafetyCenterOverallStatusErrorState {
        private boolean mHasAtLeastOneUserVisibleError = false;
    }
}
