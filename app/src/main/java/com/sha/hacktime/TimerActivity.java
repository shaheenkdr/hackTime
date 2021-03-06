package com.sha.hacktime;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.sha.hacktime.about.ProductTourActivity;
import com.sha.hacktime.settings.SettingsActivity;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static android.graphics.Typeface.createFromAsset;
import static android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP;
import static android.os.PowerManager.FULL_WAKE_LOCK;
import static android.os.PowerManager.ON_AFTER_RELEASE;
import static android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.animation.AnimationUtils.loadAnimation;
import static android.widget.Toast.LENGTH_SHORT;
import static com.sha.hacktime.Preferences.FIRST_RUN;
import static com.sha.hacktime.Preferences.PREFERENCES_NAME;
import static com.sha.hacktime.Preferences.SESSION_DURATION;
import static com.sha.hacktime.Preferences.TOTAL_SESSION_COUNT;
import static com.sha.hacktime.Preferences.ENABLE_SESSIONS_COUNTER;
import static com.sha.hacktime.SessionType.BREAK;
import static com.sha.hacktime.SessionType.LONG_BREAK;
import static com.sha.hacktime.SessionType.WORK;
import static com.sha.hacktime.TimerState.INACTIVE;
import static com.sha.hacktime.TimerState.PAUSED;
import static java.lang.String.format;

public class TimerActivity extends AppCompatActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener{

    public static final int NOTIFICATION_TAG = 2;
    protected final static int MSG_UPDATE_TIME = 0;
    private static final int MAXIMUM_MILLISECONDS_BETWEEN_KEY_PRESSES = 2000;
    private static final int MAXIMUM_MILLISECONDS_NOTIFICATION_TIME = 2000;
    private static final String TAG = "TimerActivity";
    private final Handler mUpdateTimeHandler = new TimeLabelUpdateHandler(this);
    private long mTimeLabelPressedAt;
    private TextView mStartLabel;
    private TextView mStopLabel;
    private TextView mTimeLabel;
    private TextView mSessionCounterButton;
    private Preferences mPref;
    private SharedPreferences mPrivatePref;
    private AlertDialog mAlertDialog;
    private TimerService mTimerService;
    private BroadcastReceiver mBroadcastReceiver;
    private MediaPlayer mPlayer;
    private boolean mIsBoundToTimerService = false;
    private boolean mIsUiVisible;
    private boolean isFirstTime = false;
    private boolean isTimerRunning = false;
    private CircularSeekBar seekbar;
    private ServiceConnection mTimerServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            TimerService.TimerBinder binder = (TimerService.TimerBinder) iBinder;
            mTimerService = binder.getService();
            mIsBoundToTimerService = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mIsBoundToTimerService = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPref = setupPreferences();

        migrateOldPreferences();
        setupUi();
        loadInitialState();
        setupAndroidNougatSettings();
        setupBroadcastReceiver();
    }

    private void setupMediaPlayback()
    {
        mPlayer = MediaPlayer.create(this,R.raw.watch);
        mPlayer.setLooping(false);
        try
        {
            mPlayer.prepare();
        }
        catch (Exception e)
        {
            //ignore exception
        }
    }

    private void migrateOldPreferences() {
        SharedPreferences oldPref = PreferenceManager.getDefaultSharedPreferences(this);
        mPref.migrateFromOldPreferences(oldPref);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, TimerService.class);
        startService(intent);
        bindService(intent, mTimerServiceConnection, Context.BIND_AUTO_CREATE);

        switchOrientation(mPref.getRotateTimeLabel());
    }

    @Override
    protected void onResume() {
        super.onResume();

        mIsUiVisible = true;
        if (mIsBoundToTimerService && mTimerService.getTimerState() != INACTIVE) {
            mTimerService.sendToBackground();
            mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
        }
        removeCompletionNotification();
        setupMediaPlayback();
        if (mPrivatePref.getBoolean(FIRST_RUN, true)) {
            /*Intent introIntent = new Intent(this, ProductTourActivity.class);
            startActivity(introIntent);
            mPrivatePref.edit().putBoolean(FIRST_RUN, false).apply();*/
        }
        setFullscreenMode();
    }

    @Override
    protected void onStop() {
        mIsUiVisible = false;
        if (mIsBoundToTimerService && mTimerService.getTimerState() != INACTIVE) {
            mTimerService.bringToForeground();
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
        }
        try {
            if (mPlayer.isPlaying()) {
                mPlayer.stop();
            }

            mPlayer.release();
        }
        catch (Exception e)
        {
            //ignore exception
        }
        super.onStop();
    }

    @Override
    protected void onDestroy()
    {
        try {
            if (mPlayer.isPlaying()) {
                mPlayer.stop();
            }

            mPlayer.release();
        }
        catch (Exception e)
        {
            //ignore exception
        }
        if (mIsBoundToTimerService) {
            stopService(new Intent(this, TimerService.class));
            unbindService(mTimerServiceConnection);
            mIsBoundToTimerService = false;
        }

        if (mAlertDialog != null) {
            mAlertDialog.dismiss();
        }
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(mBroadcastReceiver);
        super.onDestroy();
    }

    private void setupBroadcastReceiver() {
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                switch (intent.getAction()) {
                    case TimerService.ACTION_FINISHED_UI:
                        onCountdownFinished();
                        break;
                    case Notifications.ACTION_PAUSE_UI:
                        onTimeLabelClick();
                        mTimerService.bringToForeground();
                        break;
                    case Notifications.ACTION_STOP_UI:
                        onStopLabelClick();
                        break;
                    case Notifications.ACTION_SKIP_BREAK_UI:
                        if (mAlertDialog != null) {
                            mAlertDialog.dismiss();
                        }
                        startTimer(WORK);
                        removeCompletionNotification();
                        mTimerService.bringToForeground();
                        break;
                    case Notifications.ACTION_START_BREAK_UI:
                        if (mAlertDialog != null) {
                            mAlertDialog.dismiss();
                        }
                        startBreak();
                        removeCompletionNotification();
                        mTimerService.bringToForeground();
                        break;
                    case Notifications.ACTION_START_WORK_UI:
                        if (mAlertDialog != null) {
                            mAlertDialog.dismiss();
                        }
                        startTimer(WORK);
                        removeCompletionNotification();
                        mTimerService.bringToForeground();
                        break;
                }
            }
        };
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(
                (mBroadcastReceiver), new IntentFilter(TimerService.ACTION_FINISHED_UI)
        );
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(
                (mBroadcastReceiver), new IntentFilter(Notifications.ACTION_PAUSE_UI)
        );
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(
                (mBroadcastReceiver), new IntentFilter(Notifications.ACTION_STOP_UI)
        );
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(
                (mBroadcastReceiver), new IntentFilter(Notifications.ACTION_SKIP_BREAK_UI)
        );
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(
                (mBroadcastReceiver), new IntentFilter(Notifications.ACTION_START_BREAK_UI)
        );
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(
                (mBroadcastReceiver), new IntentFilter(Notifications.ACTION_START_WORK_UI)
        );
    }

    private Preferences setupPreferences() {
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(this);
        mPrivatePref = getSharedPreferences("preferences_private", Context.MODE_PRIVATE);
        mPrivatePref.registerOnSharedPreferenceChangeListener(this);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, true);

        return new Preferences(preferences);
    }

    private void setupAndroidNougatSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NotificationManager notificationManager = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (!notificationManager.isNotificationPolicyAccessGranted()) {
                mPref.disableSoundAndVibration();
            }
        }
    }

    private void setupUi() {
        setContentView(R.layout.activity_main);

        //Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        //setupToolbar(toolbar);

        seekbar = (CircularSeekBar) findViewById(R.id.circularSeekBar1);
        seekbar.setIsTouchEnabled(false);

        mStartLabel = (TextView) findViewById(R.id.startLabel);

        mStopLabel = (TextView) findViewById(R.id.stopLabel);
        setupStopLabel();

        mTimeLabel = (TextView) findViewById(R.id.textView);
        setupTimeLabel();
        setupPauseButton();
        setupLongPress();
    }


    private void setupPauseButton() {
        mTimeLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onTimeLabelClick();
            }
        });
    }

    private void setupLongPress() {
        mTimeLabel.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                onTimeLabelLongClick();
                return true;
            }
        });
    }

    private void setupStopLabel() {
        mStopLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onStopLabelClick();
            }
        });
    }

    private void setupTimeLabel() {
        if (mTimeLabel != null) {
            mTimeLabel.setTypeface(createFromAsset(getAssets(), "fonts/Roboto-Thin.ttf"));
            updateTimeLabel();
        }
    }

    private void removeCompletionNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_TAG);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Log.d(TAG, "A preference has changed");

        switch (key) {
            case TOTAL_SESSION_COUNT:
                if (mSessionCounterButton != null) {
                    mSessionCounterButton.setText(String.valueOf(mPrivatePref.getInt(TOTAL_SESSION_COUNT, 0)));
                }
                break;
            case SESSION_DURATION:
                if (mIsBoundToTimerService && mTimerService.getTimerState() == INACTIVE) {
                    updateTimeLabel();
                }
                break;
/*            case ENABLE_SESSIONS_COUNTER:
                Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
                setupToolbar(toolbar);

                break;*/
        }
    }



    private void loadInitialState() {
        Log.d(TAG, "Loading initial state");

        mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);

        if (mIsBoundToTimerService) {
            updateTimeLabel();

        }

        setVisibility(mStartLabel, VISIBLE);
    }



    private void startTimer(SessionType sessionType) {
        Log.i(TAG, "Timer has been started");

        isTimerRunning = true;
        mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
        setVisibility(mStartLabel, INVISIBLE);

        keepScreenOn();

        mTimerService.startSession(sessionType);
    }

    private void keepScreenOn()
    {
        getWindow().addFlags(FLAG_KEEP_SCREEN_ON);
    }

    private void onTimeLabelClick() {
        switch (mTimerService.getTimerState()) {
            case ACTIVE:
                setVisibility(mStopLabel, VISIBLE);

                if (mTimerService.getSessionType() == WORK) {
                    Log.i(TAG, "Timer has been paused");
                    isTimerRunning = false;
                    mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
                    mTimerService.pauseSession();
                    mTimeLabel.startAnimation(loadAnimation(getApplicationContext(), R.anim.blink));
                } else if (mTimeLabelPressedAt + MAXIMUM_MILLISECONDS_BETWEEN_KEY_PRESSES
                        <= System.currentTimeMillis()) {
                    final Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            setVisibility(mStopLabel, INVISIBLE);
                        }
                    }, MAXIMUM_MILLISECONDS_BETWEEN_KEY_PRESSES);
                }

                mTimeLabelPressedAt = System.currentTimeMillis();
                break;
            case PAUSED:
                Log.i(TAG, "Timer has been resumed");
                isTimerRunning = true;
                if (mIsUiVisible) {
                    mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
                }
                mTimerService.unPauseSession();
                mTimeLabel.clearAnimation();
                setVisibility(mStopLabel, INVISIBLE);
                break;
            case INACTIVE:
                startTimer(WORK);
                setVisibility(mStartLabel, INVISIBLE);
                break;
        }
    }

    private void onTimeLabelLongClick() {
        switch (mTimerService.getTimerState()) {
            case ACTIVE:
            case PAUSED:
                if (mTimerService.getSessionType() == WORK) {
                    showSkipWorkDialog();
                } else {
                    showSkipBreakDialog();
                }
                break;
            case INACTIVE:
                break;
        }
    }

    private void showSkipWorkDialog() {
        mAlertDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_session_break) + "?")
                .setPositiveButton(getString(R.string.dialog_reset_ok), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onStopLabelClick();
                        increaseTotalSessions();
                        startBreak();
                    }
                })
                .setNegativeButton(getString(R.string.dialog_reset_cancel), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .create();
        mAlertDialog.show();
    }

    private void showSkipBreakDialog() {
        mAlertDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_session_skip) + "?")
                .setPositiveButton(getString(R.string.dialog_reset_ok), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onStopLabelClick();
                        startTimer(WORK);
                    }
                })
                .setNegativeButton(getString(R.string.dialog_reset_cancel), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .create();
        mAlertDialog.show();
    }

    private void onStopLabelClick() {
        mTimeLabel.clearAnimation();

        setVisibility(mStopLabel, INVISIBLE);
        mTimerService.stopSession();
        loadInitialState();
    }

    private void setVisibility(TextView textview, int visibility) {
        if (textview != null) {
            if (visibility == VISIBLE && textview.getVisibility() == INVISIBLE) {
                textview.setVisibility(VISIBLE);
                textview.startAnimation(loadAnimation(getApplicationContext(), R.anim.fade));
            } else if (visibility == INVISIBLE && textview.getVisibility() == VISIBLE) {
                textview.setVisibility(INVISIBLE);
                textview.startAnimation(loadAnimation(getApplicationContext(), R.anim.fade_reverse));
            }
        }
    }

    private void onCountdownFinished() {
        Log.i(TAG, "Countdown has finished");

        acquireScreenWakelock();

        mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
        increaseTotalSessions();
        loadInitialState();

        if (mPref.getContinuousMode()) {
            goOnContinuousMode();
        } else {
            showContinueDialog();
        }
    }

    private void increaseTotalSessions() {
        if (mTimerService.getTimerState() == INACTIVE && mTimerService.getSessionType() == WORK) {
            mTimerService.increaseCurrentSessionStreak();
            int totalSessions = mPrivatePref.getInt(TOTAL_SESSION_COUNT, 0);
            mPrivatePref.edit()
                    .putInt(TOTAL_SESSION_COUNT, ++totalSessions)
                    .apply();
        }
    }

    private void acquireScreenWakelock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock screenWakeLock = powerManager.newWakeLock(
                SCREEN_BRIGHT_WAKE_LOCK | ON_AFTER_RELEASE | ACQUIRE_CAUSES_WAKEUP,
                "wake screen lock"
        );

        screenWakeLock.acquire();
        screenWakeLock.release();
    }

    private void showContinueDialog() {
        wakeScreen();

        switch (mTimerService.getSessionType()) {
            case WORK:
                mAlertDialog = buildStartBreakDialog();
                mAlertDialog.setCanceledOnTouchOutside(false);
                mAlertDialog.show();
                break;
            case BREAK:
            case LONG_BREAK:
                if (mTimerService.getCurrentSessionStreak() >= mPref.getSessionsBeforeLongBreak()) {
                    mTimerService.resetCurrentSessionStreak();
                }
                mAlertDialog = buildStartSessionDialog();
                mAlertDialog.setCanceledOnTouchOutside(false);
                mAlertDialog.show();
        }
    }

    private AlertDialog buildStartSessionDialog() {
        return new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_break_message))
                .setPositiveButton(getString(R.string.dialog_break_session), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        removeCompletionNotification();
                        startTimer(WORK);
                    }
                })
                .setNegativeButton(getString(R.string.dialog_session_close), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        removeCompletionNotification();
                        mTimerService.sendToBackground();
                    }
                })
                .create();
    }

    private AlertDialog buildStartBreakDialog() {
        return new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_session_message))
                .setPositiveButton(
                        getString(R.string.dialog_session_break),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    DialogInterface dialog,
                                    int which
                            ) {
                                removeCompletionNotification();
                                startBreak();
                            }
                        }
                )
                .setNegativeButton(
                        getString(R.string.dialog_session_skip),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    DialogInterface dialog,
                                    int which
                            ) {
                                removeCompletionNotification();
                                startTimer(WORK);
                            }
                        }
                )
                .setNeutralButton(getString(R.string.dialog_session_close), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        removeCompletionNotification();
                        mTimerService.sendToBackground();
                    }
                })
                .create();
    }

    private void startBreak() {
        startTimer(mTimerService.getCurrentSessionStreak() >= mPref.getSessionsBeforeLongBreak()
                ? LONG_BREAK
                : BREAK
        );
    }

    private void wakeScreen() {
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(
                SCREEN_BRIGHT_WAKE_LOCK | FULL_WAKE_LOCK,
                "waking screen up"
        );
        wakeLock.acquire();
        wakeLock.release();
    }

    private void goOnContinuousMode() {
        switch (mTimerService.getSessionType()) {
            case WORK:
                startBreak();
                break;
            case BREAK:
            case LONG_BREAK:
                if (mTimerService.getCurrentSessionStreak() >= mPref.getSessionsBeforeLongBreak()) {
                    mTimerService.resetCurrentSessionStreak();
                }
                startTimer(WORK);
        }
        if (!mIsUiVisible) {
            mTimerService.bringToForeground();
        }
        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                removeCompletionNotification();
            }
        }, MAXIMUM_MILLISECONDS_NOTIFICATION_TIME);
    }

    protected void updateTimeLabel() {
        int remainingTime;
        if (mIsBoundToTimerService) {
            if (mTimerService.isTimerRunning()) {
                remainingTime = mTimerService.getRemainingTime();
            } else if (mTimerService.getTimerState().equals(PAUSED)) {
                remainingTime = mTimerService.getRemainingTimePaused();
            } else {
                remainingTime = (int) TimeUnit.MINUTES.toSeconds(mPref.getSessionDuration());
            }
        } else {
            remainingTime = (int) TimeUnit.MINUTES.toSeconds(mPref.getSessionDuration());
        }

        int minutes = remainingTime / 60;
        int seconds = remainingTime % 60;

        Log.i(TAG, "Updating time label: " + minutes + ":" + seconds);
        String currentTick = (minutes > 0 ? minutes+":" : "") + format(Locale.US, "%02d", seconds);

        SpannableString currentFormattedTick = new SpannableString(currentTick);
        currentFormattedTick.setSpan(new RelativeSizeSpan(2f), 0, currentTick.indexOf(":")+3, 0);
        updateCircularProgress(60-seconds);
        /*if(!isFirstTime)
        {
            isFirstTime = true;
        }
        else
        {
            mPlayer.start();
        }*/

        if(isTimerRunning)
        {
            mPlayer.start();
        }

        mTimeLabel.setText(currentFormattedTick);
    }

    private void updateCircularProgress(int index)
    {
        seekbar.setProgress(index);

    }

    private void showSessionCounterDialog() {
        mAlertDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_reset_title))
                .setMessage(getString(R.string.dialog_reset_message))
                .setPositiveButton(getString(R.string.dialog_reset_ok), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mTimerService.resetCurrentSessionStreak();
                        mPrivatePref.edit()
                                .putInt(TOTAL_SESSION_COUNT, 0)
                                .apply();
                    }
                })
                .setNegativeButton(getString(R.string.dialog_reset_cancel), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .create();
        mAlertDialog.show();
    }

    private void setFullscreenMode() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }


    private void switchOrientation(boolean rotate) {
        if (rotate) {
            mTimeLabel.setRotation(90);
        } else {
            mTimeLabel.setRotation(0);
        }
    }

}
