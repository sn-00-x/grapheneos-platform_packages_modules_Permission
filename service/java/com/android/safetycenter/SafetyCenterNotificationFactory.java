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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.safetycenter.SafetySourceIssue;

import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;
import com.android.safetycenter.internaldata.SafetyCenterIssueActionId;
import com.android.safetycenter.internaldata.SafetyCenterIssueKey;

import java.util.List;

/**
 * Factory that builds {@link Notification} objects from {@link SafetySourceIssue} instances with
 * appropriate {@link PendingIntent}s for click and dismiss callbacks.
 */
@RequiresApi(TIRAMISU)
final class SafetyCenterNotificationFactory {

    private static final String TAG = "SafetyCenterNF";

    private static final int OPEN_SAFETY_CENTER_REQUEST_CODE = 1221;

    @NonNull private final Context mContext;
    @NonNull private final SafetyCenterNotificationChannels mNotificationChannels;

    SafetyCenterNotificationFactory(
            @NonNull Context context,
            @NonNull SafetyCenterNotificationChannels notificationChannels) {
        mContext = context;
        mNotificationChannels = notificationChannels;
    }

    /**
     * Creates and returns a new {@link Notification} instance corresponding to the given issue, or
     * {@code null} if none could be created.
     *
     * <p>The provided {@link NotificationManager} is used to create or update the {@link
     * NotificationChannel} for the notification.
     */
    @Nullable
    Notification newNotificationForIssue(
            @NonNull NotificationManager notificationManager,
            @NonNull SafetySourceIssue issue,
            @NonNull SafetyCenterIssueKey issueKey) {
        String channelId = mNotificationChannels.createAndGetChannelId(notificationManager, issue);

        if (channelId == null) {
            return null;
        }

        CharSequence title = issue.getTitle();
        CharSequence text = issue.getSummary();
        List<SafetySourceIssue.Action> issueActions = issue.getActions();

        if (SdkLevel.isAtLeastU()) {
            SafetySourceIssue.Notification customNotification = issue.getCustomNotification();
            if (customNotification != null) {
                title = customNotification.getTitle();
                text = customNotification.getText();
                issueActions = customNotification.getActions();
            }
        }

        Notification.Builder builder =
                new Notification.Builder(mContext, channelId)
                        // TODO(b/259399024): Use correct icon here
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setExtras(getNotificationExtras())
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(newSafetyCenterPendingIntent(issue))
                        .setDeleteIntent(
                                SafetyCenterNotificationReceiver.newNotificationDismissedIntent(
                                        mContext, issueKey));

        for (int i = 0; i < issueActions.size(); i++) {
            Notification.Action notificationAction =
                    toNotificationAction(issueKey, issueActions.get(i));
            builder.addAction(notificationAction);
        }

        return builder.build();
    }

    @NonNull
    private PendingIntent newSafetyCenterPendingIntent(@NonNull SafetySourceIssue targetIssue) {
        // TODO(b/259398419): Add target issue to intent so it's highlighted when SC opens
        Intent intent = new Intent(Intent.ACTION_SAFETY_CENTER);
        return PendingIntentFactory.getActivityPendingIntent(
                mContext, OPEN_SAFETY_CENTER_REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    @NonNull
    private Bundle getNotificationExtras() {
        Bundle extras = new Bundle();
        // TODO(b/259399024): Use suitable string resource here
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, "Safety Center");
        return extras;
    }

    @NonNull
    private Notification.Action toNotificationAction(
            @NonNull SafetyCenterIssueKey issueKey, @NonNull SafetySourceIssue.Action issueAction) {
        // We do not use the action's PendingIntent directly here instead we build a new PI which
        // will be handled by our SafetyCenterNotificationReceiver which will in turn dispatch
        // the source-provided action PI. This ensures that action execution is consistent across
        // between Safety Center UI and notifications, for example executing an action from a
        // notification will send an "action in-flight" update to any current listeners.
        SafetyCenterIssueActionId issueActionId =
                SafetyCenterIssueActionId.newBuilder()
                        .setSafetyCenterIssueKey(issueKey)
                        .setSafetySourceIssueActionId(issueAction.getId())
                        .build();
        PendingIntent receiverPendingIntent =
                SafetyCenterNotificationReceiver.newNotificationActionClickedIntent(
                        mContext, issueActionId);
        return new Notification.Action.Builder(null, issueAction.getLabel(), receiverPendingIntent)
                .build();
    }
}
