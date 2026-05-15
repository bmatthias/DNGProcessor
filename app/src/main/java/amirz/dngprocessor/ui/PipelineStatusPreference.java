package amirz.dngprocessor.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import amirz.dngprocessor.R;
import amirz.dngprocessor.scheduler.DngParseWorker;
import amirz.dngprocessor.util.Utilities;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PipelineStatusPreference extends Preference {
    private TextView statusText;
    private Button pauseResumeButton;
    private Button cancelButton;
    private Handler handler;
    private Runnable updateRunnable;
    private boolean isPaused = false;

    public PipelineStatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        super.onCreateView(parent);
        setSelectable(false);
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.pipeline_status_preference, parent, false);
        return view;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        
        statusText = view.findViewById(R.id.pipeline_status_text);
        pauseResumeButton = view.findViewById(R.id.pipeline_pause_resume_button);
        cancelButton = view.findViewById(R.id.pipeline_cancel_button);
        
        if (pauseResumeButton != null) {
            pauseResumeButton.setOnClickListener(v -> {
                if (isPaused) {
                    DngParseWorker.resumeWork(getContext());
                    isPaused = false;
                } else {
                    DngParseWorker.pauseWork(getContext());
                    isPaused = true;
                }
                updateStatus();
            });
        }
        
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> {
                DngParseWorker.cancelAllWork(getContext());
                isPaused = false;
                updateStatus();
            });
        }
        
        updateStatus();
        startPeriodicUpdate();
    }

    private void startPeriodicUpdate() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateStatus();
                handler.postDelayed(this, 1000); // Update every second
            }
        };
        handler.post(updateRunnable);
    }

    private void updateStatus() {
        if (statusText == null || pauseResumeButton == null || cancelButton == null) {
            return;
        }
        
        Context context = getContext();
        WorkManager workManager = WorkManager.getInstance(context);
        
        // Get all work info for DngParseWorker
        ListenableFuture<List<WorkInfo>> future = workManager.getWorkInfosByTag(DngParseWorker.WORK_TAG);
        
        future.addListener(() -> {
            try {
                List<WorkInfo> workInfos = future.get(5, TimeUnit.SECONDS);
                
                int enqueued = 0;
                int running = 0;
                int blocked = 0;
                
                for (WorkInfo info : workInfos) {
                    WorkInfo.State state = info.getState();
                    if (state == WorkInfo.State.ENQUEUED) {
                        enqueued++;
                    } else if (state == WorkInfo.State.RUNNING) {
                        running++;
                    } else if (state == WorkInfo.State.BLOCKED) {
                        blocked++;
                    }
                }
                
                final int total = enqueued + running + blocked;
                
                // Check pause state from SharedPreferences
                boolean actuallyPaused = Utilities.prefs(context).getBoolean("work_paused", false);
                if (actuallyPaused != isPaused) {
                    isPaused = actuallyPaused;
                }
                final boolean finalIsPaused = isPaused;
                final int finalTotal = total;
                
                handler.post(() -> {
                    if (statusText != null) {
                        if (finalTotal == 0) {
                            statusText.setText(context.getString(R.string.pipeline_status_empty));
                        } else {
                            String statusTextStr;
                            if (finalIsPaused) {
                                statusTextStr = context.getString(R.string.pipeline_status_paused);
                            } else {
                                statusTextStr = context.getString(R.string.pipeline_status_running);
                            }
                            String status = context.getString(R.string.pipeline_status_format, 
                                    finalTotal, statusTextStr);
                            statusText.setText(status);
                        }
                    }
                    
                    if (pauseResumeButton != null) {
                        if (finalIsPaused) {
                            pauseResumeButton.setText(context.getString(R.string.pipeline_resume));
                        } else {
                            pauseResumeButton.setText(context.getString(R.string.pipeline_pause));
                        }
                        pauseResumeButton.setEnabled(finalTotal > 0);
                    }
                    
                    if (cancelButton != null) {
                        cancelButton.setEnabled(finalTotal > 0);
                    }
                });
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                // Ignore errors, will retry on next update
            }
        }, Runnable::run);
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        if (handler != null && updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
    }
}

