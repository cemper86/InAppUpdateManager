package com.wellsoft.inappupdate;

import android.app.Activity;
import android.content.IntentSender;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.android.play.core.tasks.OnSuccessListener;
import com.google.android.play.core.tasks.Task;

import java.lang.ref.WeakReference;

import static com.wellsoft.inappupdate.InAppUpdateConstants.*;


public class InAppUpdateManager implements LifecycleObserver {

    private static final String TAG = "InAppUpdateManager";

    private WeakReference<AppCompatActivity> activityWeakReference;

    private static InAppUpdateManager instance;

    // Default mode is FLEXIBLE
    private int mode = FLEXIBLE;

    // Creates instance of the manager.
    private AppUpdateManager appUpdateManager;

    // Returns an intent object that you use to check for an update.
    private Task<AppUpdateInfo> appUpdateInfoTask;

    private FlexibleUpdateDownloadListener flexibleUpdateDownloadListener;

    private boolean isWarningUpdate;
    private Integer stalenessDays;
    private boolean isExistUpdates;

    private boolean isShowStartDownLoading;


    private InAppUpdateManager(AppCompatActivity activity) {
        activityWeakReference = new WeakReference<>(activity);
        this.appUpdateManager = AppUpdateManagerFactory.create(getActivity());
        this.appUpdateInfoTask = appUpdateManager.getAppUpdateInfo();
        activity.getLifecycle().addObserver(this);
    }

    public static InAppUpdateManager Builder(AppCompatActivity activity) {
        if (instance == null) {
            instance = new InAppUpdateManager(activity);
        }
        Log.d(TAG, TAG + " Экземпляр создан");
        return instance;
    }

    public InAppUpdateManager setWarningUpdate(boolean isWarningUpdate){
        this.isWarningUpdate = isWarningUpdate;
        return mode(isWarningUpdate ? IMMEDIATE : FLEXIBLE);
    }

    public InAppUpdateManager setStalenessDays(Integer stalenessDays){
        this.stalenessDays = stalenessDays;
        return instance;
    }

    private InAppUpdateManager mode(int mode) {
        String strMode = getStringMode(mode);
        writeLog("Установлен тип обновления : " + strMode);
        this.mode = mode;
        return this;
    }

    public void setExistUpdates(boolean existUpdates) {
        isExistUpdates = existUpdates;
    }

    private String getStringMode(int mode){
        return mode == FLEXIBLE ? "FLEXIBLE" : "IMMEDIATE";
    }

    public void start() {
        if (mode == FLEXIBLE) {
            setUpListener();
        }
        checkUpdate();
    }

    private void setUpListener() {
        appUpdateManager.registerListener(listener);
    }

    private void checkUpdate() {
        // Checks that the platform will allow the specified type of update.
        writeLog("Проверка наличия обновления");
        appUpdateInfoTask.addOnSuccessListener(new OnSuccessListener<AppUpdateInfo>() {
            @Override
            public void onSuccess(AppUpdateInfo appUpdateInfo) {
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                    // Request the update.
                    writeLog("Обновление доступно");
                    isShowStartDownLoading = true;
                    isExistUpdates = true;
                    if(isWarningUpdate){
                        if(appUpdateInfo.isUpdateTypeAllowed(IMMEDIATE)){
                            writeLog("Обновление разрешено IMMEDIATE");
                            startUpdate(appUpdateInfo, IMMEDIATE);
                        } else {
                            writeLog("Обновление не разрешено IMMEDIATE");
                        }

                    } else {
                        if((stalenessDays != null && stalenessDays > 0)
                                && appUpdateInfo.clientVersionStalenessDays() != null
                                && appUpdateInfo.clientVersionStalenessDays() >= stalenessDays){
                            writeLog("С момента выхода последнего обновления прошло " + stalenessDays + " или более дней");
                            if(appUpdateInfo.isUpdateTypeAllowed(IMMEDIATE)){
                                writeLog("Обновление разрешено IMMEDIATE");
                                startUpdate(appUpdateInfo, IMMEDIATE);
                            } else {
                                writeLog("Обновление не разрешено IMMEDIATE");
                            }
                        } else {
                            writeLog("С момента выхода последнего обновления прошло менее " + stalenessDays + " days");
                            if(appUpdateInfo.isUpdateTypeAllowed(mode)){
                                writeLog("Обновление разрешено " + getStringMode(mode));
                                startUpdate(appUpdateInfo, mode);
                            } else {
                                writeLog("Обновление не разрешено " + getStringMode(mode));
                            }
                        }
                    }
                } else {
                    isExistUpdates = false;
                    writeLog("Нет доступных обновлений");
                }
            }
        });
    }

    private void startUpdate(AppUpdateInfo appUpdateInfo, int requestMode) {
        try {
            writeLog("Запуск обновления");
            appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    requestMode,
                    getActivity(),
                    REQUEST_CODE);
        } catch (IntentSender.SendIntentException e) {
            writeLog("" + e.getMessage());
        }
    }

    private InstallStateUpdatedListener listener = new InstallStateUpdatedListener() {
        @Override
        public void onStateUpdate(InstallState installState) {
            if (installState.installStatus() == InstallStatus.DOWNLOADING) {
                long bytesDownloaded = installState.bytesDownloaded();
                long totalBytesToDownload = installState.totalBytesToDownload();
                if(isShowStartDownLoading){
                    isShowStartDownLoading = false;
                    popupSnackbarForStartLoadingUpdate();
                }
                if (flexibleUpdateDownloadListener != null) {
                    writeLog("Обновление загружается: " + bytesDownloaded + " / " + totalBytesToDownload);
                    flexibleUpdateDownloadListener.onDownloadProgress(bytesDownloaded, totalBytesToDownload);
                } else {
                    writeLog("Обновление загружается без колбека: " + bytesDownloaded + " / " + totalBytesToDownload);
                }
            }
            if (installState.installStatus() == InstallStatus.DOWNLOADED) {
                // After the update is downloaded, show a notification
                // and request user confirmation to restart the app.
                writeLog("Обновление скачано");
                popupSnackbarForCompleteUpdate();
            }
        }
    };

    private void popupSnackbarForCompleteUpdate() {
        Snackbar snackbar =
                Snackbar.make(
                        getActivity().getWindow().getDecorView().findViewById(android.R.id.content),
                        R.string.update_just_been_downloaded,
                        Snackbar.LENGTH_INDEFINITE);
        snackbar.setAction(R.string.restart, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appUpdateManager.completeUpdate();
            }
        });
        snackbar.show();
    }

    private void popupSnackbarForStartLoadingUpdate() {
        Snackbar snackbar =
                Snackbar.make(
                        getActivity().getWindow().getDecorView().findViewById(android.R.id.content),
                        R.string.update_start_downloading,
                        Snackbar.LENGTH_INDEFINITE);
        snackbar.show();
    }

    public void addFlexibleUpdateDownloadListener(FlexibleUpdateDownloadListener flexibleUpdateDownloadListener) {
        this.flexibleUpdateDownloadListener = flexibleUpdateDownloadListener;
    }

    private void unregisterListener() {
        if (appUpdateManager != null && listener != null) {
            appUpdateManager.unregisterListener(listener);
            writeLog("Отмена регистрации слушателя состояния установки");
        }
    }

    private Activity getActivity() {
        return activityWeakReference.get();
    }

    private void continueUpdate() {

        if (instance.mode == FLEXIBLE) {
            continueUpdateForFlexible();
        } else {
            continueUpdateForImmediate();
        }
    }

    private void continueUpdateForFlexible() {
        instance.appUpdateManager
                .getAppUpdateInfo()
                .addOnSuccessListener(new OnSuccessListener<AppUpdateInfo>() {
                    @Override
                    public void onSuccess(AppUpdateInfo appUpdateInfo) {
                        // If the update is downloaded but not installed,
                        // notify the user to complete the update.
                        if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                            writeLog("Обновление скачано");
                            instance.popupSnackbarForCompleteUpdate();
                        }
                    }
                });
    }

    private void continueUpdateForImmediate() {
        instance.appUpdateManager
                .getAppUpdateInfo()
                .addOnSuccessListener(new OnSuccessListener<AppUpdateInfo>() {
                    @Override
                    public void onSuccess(AppUpdateInfo appUpdateInfo) {
                        if (appUpdateInfo.updateAvailability()
                                == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                            // If an in-app update is already running, resume the update.
                            try {
                                instance.appUpdateManager.startUpdateFlowForResult(
                                        appUpdateInfo,
                                        instance.mode,
                                        getActivity(),
                                        REQUEST_CODE);
                            } catch (IntentSender.SendIntentException e) {
                               writeLog("" + e.getMessage());
                            }
                        }
                    }
                });
    }

    public interface FlexibleUpdateDownloadListener {

        void onDownloadProgress(long bytesDownloaded, long totalBytes);

    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private void onResume() {
        continueUpdate();
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    private void onDestroy() {
        unregisterListener();
    }

    private  void writeLog(String message){
        Log.d(TAG, TAG + " " + message);
    }

}
