/*
 * This file is part of Neo Launcher
 * Copyright (c) 2026   Neo Launcher Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.neoapps.neolauncher.allapps;

import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.formatElapsedTime;
import static com.android.launcher3.EncryptionType.ENCRYPTED;
import static com.android.launcher3.LauncherPrefs.nonRestorableItem;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS_PREDICTION;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_WIDGETS_PREDICTION;
import static com.android.launcher3.LauncherSettings.Favorites.DESKTOP_ICON_FLAG;
import static com.android.launcher3.icons.cache.CacheLookupFlag.DEFAULT_LOOKUP_FLAG;
import static com.android.launcher3.model.PredictionHelper.getBundleForHotseatPredictions;
import static com.android.launcher3.model.PredictionHelper.getBundleForWidgetPredictions;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.app.StatsManager;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppTargetEvent;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.util.StatsEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.android.launcher3.ConstantItem;
import com.android.launcher3.Flags;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.logger.LauncherAtom;
import com.android.launcher3.logging.InstanceId;
import com.android.launcher3.logging.InstanceIdSequence;
import com.android.launcher3.model.AppEventProducer;
import com.android.launcher3.model.ModelDelegate;
import com.android.launcher3.model.PredictedItemFactory;
import com.android.launcher3.model.PredictionUpdateTask;
import com.android.launcher3.model.PredictorState;
import com.android.launcher3.model.WidgetsPredictionUpdateTask;
import com.android.launcher3.model.data.CollectionInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.PredictedContainerInfo;
import com.android.launcher3.model.data.WorkspaceData;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.quickstep.logging.StatsLogCompatManager;
import com.android.launcher3.util.IntSparseArrayMap;
import com.android.systemui.shared.system.SysUiStatsLog;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Named;

public class NeoLauncherModelDelegate extends ModelDelegate {
    private static final int NUM_OF_RECOMMENDED_WIDGETS_PREDICATION = 20;

    private static final boolean IS_DEBUG = false;
    private static final String TAG = "QuickstepModelDelegate";

    private static final ConstantItem<Long> LAST_SNAPSHOT_TIME_MILLIS =
            nonRestorableItem("LAST_SNAPSHOT_TIME_MILLIS", 0L, ENCRYPTED);

    @VisibleForTesting
    final PredictorState mAllPredictionAppsState = new PredictorState(
            CONTAINER_ALL_APPS_PREDICTION, "all_apps_predictions", DEFAULT_LOOKUP_FLAG);
    @VisibleForTesting
    final PredictorState mHotseatPredictionState = new PredictorState(
            CONTAINER_HOTSEAT_PREDICTION, "hotseat_predictions", DESKTOP_ICON_FLAG);
    @VisibleForTesting
    @Deprecated // unused with the Flag.enableWidgetPickerRefactor enabled
    final PredictorState mWidgetsRecommendationState = new PredictorState(
            CONTAINER_WIDGETS_PREDICTION, "widgets_prediction", DESKTOP_ICON_FLAG);

    private final InvariantDeviceProfile mIDP;
    private final UserCache mUserCache;
    private final PredictedItemFactory.Factory mItemParserFactory;
    private final AppEventProducer mAppEventProducer;

    private final StatsManager mStatsManager;

    protected boolean mActive = false;

    @Inject
    public NeoLauncherModelDelegate(@ApplicationContext Context context,
                                    InvariantDeviceProfile idp,
                                    UserCache userCache,
                                    PredictedItemFactory.Factory itemParserFactory,
                                    @Nullable @Named("ICONS_DB") String dbFileName) {
        super(context);


        mIDP = idp;
        mUserCache = userCache;
        mItemParserFactory = itemParserFactory;

        mAppEventProducer = new AppEventProducer(context, this::onAppTargetEvent);
        StatsLogCompatManager.LOGS_CONSUMER.add(mAppEventProducer);

        // Only register for launcher snapshot logging if this is the primary ModelDelegate
        // instance, as there will be additional instances that may be destroyed at any time.
        mStatsManager = TextUtils.isEmpty(dbFileName)
                ? null : context.getSystemService(StatsManager.class);
    }

    @Override
    public void loadAndAddExtraModelItems(@NonNull IntSparseArrayMap<ItemInfo> outLoadedItems) {
        loadAndBindPredictedItems(
                mIDP.numDatabaseHotseatIcons, mHotseatPredictionState, outLoadedItems);
        loadAndBindPredictedItems(mIDP.numDatabaseAllAppsColumns, mAllPredictionAppsState,
                outLoadedItems);

        // Widgets prediction isn't used frequently. And thus, it is not persisted on disk.
        PredictedContainerInfo widgetPredictionFCI = new PredictedContainerInfo(
                mWidgetsRecommendationState.containerId, new ArrayList<>());
        outLoadedItems.put(mWidgetsRecommendationState.containerId, widgetPredictionFCI);
    }

    @WorkerThread
    private void loadAndBindPredictedItems(int numColumns,
                                           @NonNull PredictorState state, @NonNull IntSparseArrayMap<ItemInfo> outLoadedItems) {
        PredictedItemFactory parser = mItemParserFactory.newParser(numColumns, state);
        PredictedContainerInfo fci = new PredictedContainerInfo(state.containerId,
                state.storage.read(mContext, parser, mUserCache::getUserForSerialNumber));
        outLoadedItems.put(state.containerId, fci);
    }

    public void markActive() {
        super.markActive();
        mActive = true;
    }

    @WorkerThread
    @Override
    public void workspaceLoadComplete() {
        super.workspaceLoadComplete();
        // Initialize ContextualSearchStateManager.
        //ContextualSearchStateManager.INSTANCE.get(mContext);
        recreatePredictors();
    }

    @Override
    @WorkerThread
    public void modelLoadComplete() {
        super.modelLoadComplete();

        // Log snapshot of the model
        LauncherPrefs prefs = LauncherPrefs.get(mContext);
        long lastSnapshotTimeMillis = prefs.get(LAST_SNAPSHOT_TIME_MILLIS);
        // Log snapshot only if previous snapshot was older than a day
        long now = System.currentTimeMillis();
        if (now - lastSnapshotTimeMillis < DAY_IN_MILLIS) {
            if (IS_DEBUG) {
                String elapsedTime = formatElapsedTime((now - lastSnapshotTimeMillis) / 1000);
                Log.d(TAG, String.format(
                        "Skipped snapshot logging since previous snapshot was %s old.",
                        elapsedTime));
            }
        } else {
            WorkspaceData itemsIdMap;
            synchronized (mDataModel) {
                itemsIdMap = mDataModel.itemsIdMap.copy();
            }
            InstanceId instanceId = new InstanceIdSequence().newInstanceId();
            for (ItemInfo info : itemsIdMap) {
                CollectionInfo parent = getContainer(info, itemsIdMap);
                StatsLogCompatManager.writeSnapshot(info.buildProto(parent, mContext), instanceId);
            }
            additionalSnapshotEvents(instanceId);
            prefs.put(LAST_SNAPSHOT_TIME_MILLIS, now);
        }

        registerSnapshotLoggingCallback();
    }

    protected void additionalSnapshotEvents(InstanceId snapshotInstanceId) {
    }

    /**
     * Registers a callback to log launcher workspace layout using Statsd pulled atom.
     */
    private void registerSnapshotLoggingCallback() {
        if (mStatsManager == null) {
            Log.d(TAG, "Skipping snapshot logging");
        }

        try {
            mStatsManager.setPullAtomCallback(
                    SysUiStatsLog.LAUNCHER_LAYOUT_SNAPSHOT,
                    null /* PullAtomMetadata */,
                    MODEL_EXECUTOR,
                    (i, eventList) -> {
                        InstanceId instanceId = new InstanceIdSequence().newInstanceId();
                        WorkspaceData itemsIdMap;
                        synchronized (mDataModel) {
                            itemsIdMap = mDataModel.itemsIdMap.copy();
                        }

                        for (ItemInfo info : itemsIdMap) {
                            CollectionInfo parent = getContainer(info, itemsIdMap);
                            LauncherAtom.ItemInfo itemInfo = info.buildProto(parent, mContext);
                            Log.d(TAG, itemInfo.toString());
                            StatsEvent statsEvent = StatsLogCompatManager.buildStatsEvent(itemInfo,
                                    instanceId);
                            eventList.add(statsEvent);
                        }
                        Log.d(TAG,
                                String.format(
                                        "Successfully logged %d workspace items with instanceId=%d",
                                        eventList.size(), instanceId.getId()));
                        additionalSnapshotEvents(instanceId);
                        //SettingsChangeLogger.INSTANCE.get(mContext).logSnapshot(instanceId);
                        return StatsManager.PULL_SUCCESS;
                    }
            );
            Log.d(TAG, "Successfully registered for launcher snapshot logging!");
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to register launcher snapshot logging callback with StatsManager",
                    e);
        }
    }

    private static CollectionInfo getContainer(
            ItemInfo info, WorkspaceData itemsIdMap) {
        if (info.container > 0) {
            ItemInfo containerInfo = itemsIdMap.get(info.container);

            if (!(containerInfo instanceof CollectionInfo)) {
                Log.e(TAG, String.format(
                        "Item info: %s found with invalid container: %s",
                        info,
                        containerInfo));
            }
            // Allow crash to help debug b/173838775
            return (CollectionInfo) containerInfo;
        }
        return null;
    }

    @Override
    public void validateData() {
        super.validateData();
        mAllPredictionAppsState.requestPredictionUpdate();
        if (!Flags.enableWidgetPickerRefactor()) {
            mWidgetsRecommendationState.requestPredictionUpdate();
        }
    }

    @WorkerThread
    @Override
    public void destroy() {
        super.destroy();
        mActive = false;
        StatsLogCompatManager.LOGS_CONSUMER.remove(mAppEventProducer);
        if (mStatsManager != null) {
            try {
                mStatsManager.clearPullAtomCallback(SysUiStatsLog.LAUNCHER_LAYOUT_SNAPSHOT);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to unregister snapshot logging callback with StatsManager", e);
            }
        }
        destroyPredictors();
    }

    private void destroyPredictors() {
        mAllPredictionAppsState.destroyPredictor();
        mHotseatPredictionState.destroyPredictor();
        if (!Flags.enableWidgetPickerRefactor()) {
            mWidgetsRecommendationState.destroyPredictor();
        }
    }

    @WorkerThread
    private void recreatePredictors() {
        destroyPredictors();
        if (!mActive) {
            return;
        }

        mAllPredictionAppsState.registerPredictor(mContext,
                new AppPredictionContext.Builder(mContext)
                        .setUiSurface("home")
                        .setPredictedTargetCount(mIDP.numDatabaseAllAppsColumns)
                        .build(),
                mModel,
                PredictionUpdateTask::new);


        // TODO: get bundle
        registerHotseatPredictor(mContext);

        if (!Flags.enableWidgetPickerRefactor()) {
            mWidgetsRecommendationState.registerPredictor(mContext,
                    new AppPredictionContext.Builder(mContext)
                            .setUiSurface("widgets")
                            .setExtras(getBundleForWidgetPredictions(mContext, mDataModel))
                            .setPredictedTargetCount(NUM_OF_RECOMMENDED_WIDGETS_PREDICATION)
                            .build(),
                    mModel,
                    WidgetsPredictionUpdateTask::new);
        }
    }

    @WorkerThread
    private void recreateHotseatPredictor() {
        mHotseatPredictionState.destroyPredictor();
        if (mActive) {
            registerHotseatPredictor(mContext);
        }
    }

    private void registerHotseatPredictor(Context context) {
        mHotseatPredictionState.registerPredictor(context,
                new AppPredictionContext.Builder(context)
                        .setUiSurface("hotseat")
                        .setPredictedTargetCount(mIDP.numDatabaseHotseatIcons)
                        .setExtras(getBundleForHotseatPredictions(context, mDataModel))
                        .build(),
                mModel, PredictionUpdateTask::new);
    }

    @VisibleForTesting
    void onAppTargetEvent(AppTargetEvent event, int client) {
        PredictorState state;
        switch (client) {
            case CONTAINER_ALL_APPS_PREDICTION:
                state = mAllPredictionAppsState;
                break;
            case CONTAINER_WIDGETS_PREDICTION:
                state = mWidgetsRecommendationState;
                break;
            case CONTAINER_HOTSEAT_PREDICTION:
            default:
                state = mHotseatPredictionState;
                break;
        }

        state.notifyAppTargetEvent(event);
        Log.d(TAG, "notifyAppTargetEvent action=" + event.getAction()
                + " launchLocation=" + event.getLaunchLocation());
        if (state == mHotseatPredictionState
                && (event.getAction() == AppTargetEvent.ACTION_PIN
                || event.getAction() == AppTargetEvent.ACTION_UNPIN)) {
            // Recreate hot seat predictor when we need to query for hot seat due to pin or
            // unpin app icons.
            recreateHotseatPredictor();
        }
    }
}
