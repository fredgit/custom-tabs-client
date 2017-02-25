// Copyright 2015 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.chromium.customtabsclient;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.support.customtabs.CustomTabsCallback;
import android.support.customtabs.CustomTabsClient;
import android.support.customtabs.CustomTabsIntent;
import android.support.customtabs.CustomTabsServiceConnection;
import android.support.customtabs.CustomTabsSession;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import org.chromium.customtabsclient.shared.CustomTabsHelper;
import org.chromium.customtabsclient.shared.ServiceConnection;
import org.chromium.customtabsclient.shared.ServiceConnectionCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Example client activity for using Chrome Custom Tabs.
 */
public class EditActivity extends Activity implements OnClickListener, ServiceConnectionCallback {
    private static final String TAG = "EditActivity";
    private static final String TOOLBAR_COLOR = "#ef6c00";

    private EditText mEditText;
    private CustomTabsSession mCustomTabsSession;
    private CustomTabsClient mClient;
    private CustomTabsServiceConnection mConnection;
    private String mPackageNameToBind;
    private Button mConnectButton;
    private Button mLaunchButton;
    private MediaPlayer mMediaPlayer;
    private int mNavigationEvent;

    /**
     * Once per second, asks the framework for the process importance, and logs any change.
     */
    private Runnable mLogImportance = new Runnable() {
        private int mPreviousImportance = -1;
        private boolean mPreviousServiceInUse = false;
        private Handler mHandler = new Handler(Looper.getMainLooper());

        @Override
        public void run() {
            ActivityManager.RunningAppProcessInfo state =
                    new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(state);
            int importance = state.importance;
            boolean serviceInUse = state.importanceReasonCode
                    == ActivityManager.RunningAppProcessInfo.REASON_SERVICE_IN_USE;
            if (importance != mPreviousImportance || serviceInUse != mPreviousServiceInUse) {
                mPreviousImportance = importance;
                mPreviousServiceInUse = serviceInUse;
                String message = "New importance = " + importance;
                if (serviceInUse) message += " (Reason: Service in use)";
                Log.w(TAG, message);
            }
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.edit);
        mEditText = (EditText) findViewById(R.id.edit);
        mConnectButton = (Button) findViewById(R.id.connect_button_edit);
        mLaunchButton = (Button) findViewById(R.id.launch_button_edit);
        Spinner spinner = (Spinner) findViewById(R.id.spinner);
        mEditText.requestFocus();
        mConnectButton.setOnClickListener(this);
        mLaunchButton.setOnClickListener(this);
        mMediaPlayer = MediaPlayer.create(this, R.raw.amazing_grace);

        Intent activityIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"));
        PackageManager pm = getPackageManager();
        List<ResolveInfo> resolvedActivityList = pm.queryIntentActivities(
                activityIntent, PackageManager.MATCH_ALL);
        List<Pair<String, String>> packagesSupportingCustomTabs = new ArrayList<>();
        for (ResolveInfo info : resolvedActivityList) {
            Intent serviceIntent = new Intent();
            serviceIntent.setAction("android.support.customtabs.action.CustomTabsService");
            serviceIntent.setPackage(info.activityInfo.packageName);
            if (pm.resolveService(serviceIntent, 0) != null) {
                packagesSupportingCustomTabs.add(
                        Pair.create(info.loadLabel(pm).toString(), info.activityInfo.packageName));
            }
        }

        final ArrayAdapter<Pair<String, String>> adapter = new ArrayAdapter<Pair<String, String>>(
                this, 0, packagesSupportingCustomTabs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = LayoutInflater.from(EditActivity.this).inflate(
                            android.R.layout.simple_list_item_2, parent, false);
                }
                Pair<String, String> data = getItem(position);
                ((TextView) view.findViewById(android.R.id.text1)).setText(data.first);
                ((TextView) view.findViewById(android.R.id.text2)).setText(data.second);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return getView(position, convertView, parent);
            }
        };
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Pair<String, String> item = adapter.getItem(position);
                if (TextUtils.isEmpty(item.second)) {
                    onNothingSelected(parent);
                    return;
                }
                mPackageNameToBind = item.second;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mPackageNameToBind = null;
            }
        });

        mLogImportance.run();
    }

    @Override
    protected void onDestroy() {
        unbindCustomTabsService();
        super.onDestroy();
    }

    private CustomTabsSession getSession() {
        if (mClient == null) {
            mCustomTabsSession = null;
        } else if (mCustomTabsSession == null) {
            mCustomTabsSession = mClient.newSession(null);
            SessionHelper.setCurrentSession(mCustomTabsSession);
        }
        return mCustomTabsSession;
    }

    private CustomTabsSession getSession(NavigationCallback navigationCallback) {
        if (mClient == null) {
            mCustomTabsSession = null;
        } else if (mCustomTabsSession == null) {
            mCustomTabsSession = mClient.newSession(navigationCallback);
            SessionHelper.setCurrentSession(mCustomTabsSession);
        }
        return mCustomTabsSession;
    }

    private void bindCustomTabsService() {
        if (mClient != null) return;
        if (TextUtils.isEmpty(mPackageNameToBind)) {
            mPackageNameToBind = CustomTabsHelper.getPackageNameToUse(this);
            if (mPackageNameToBind == null) return;
        }
        mConnection = new ServiceConnection(this);
        boolean ok = CustomTabsClient.bindCustomTabsService(this, mPackageNameToBind, mConnection);
        if (ok) {
            mConnectButton.setEnabled(false);
        } else {
            mConnection = null;
        }
    }

    private void unbindCustomTabsService() {
        if (mConnection == null) return;
        unbindService(mConnection);
        mClient = null;
        mCustomTabsSession = null;
    }

    @Override
    public void onClick(View v) {
//        String url = mEditText.getText().toString();
        String url  = "https://developer.chrome.com/multidevice/android/customtabs";

        int viewId = v.getId();

        if (viewId == R.id.connect_button_edit) {
            bindCustomTabsService();
        } else if (viewId == R.id.launch_button_edit) {
            CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder(getSession(new NavigationCallback()));
            builder.setToolbarColor(Color.parseColor(TOOLBAR_COLOR)).setShowTitle(true);
            prepareMenuItems(builder);
            prepareActionButton(builder);
            prepareBottombar(builder);
            builder.setStartAnimations(this, R.anim.slide_in_right, R.anim.slide_out_left);
            builder.setExitAnimations(this, R.anim.slide_in_left, R.anim.slide_out_right);
            builder.setCloseButtonIcon(
                    BitmapFactory.decodeResource(getResources(), R.drawable.ic_arrow_back));
            CustomTabsIntent customTabsIntent = builder.build();
            CustomTabsHelper.addKeepAliveExtra(this, customTabsIntent.intent);
            customTabsIntent.launchUrl(this, Uri.parse(url));

        }
    }

    private void prepareMenuItems(CustomTabsIntent.Builder builder) {
        Intent menuIntent = new Intent();
        menuIntent.setClass(getApplicationContext(), this.getClass());
        // Optional animation configuration when the user clicks menu items.
        Bundle menuBundle = ActivityOptions.makeCustomAnimation(this, android.R.anim.slide_in_left,
                android.R.anim.slide_out_right).toBundle();
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0, menuIntent, 0,
                menuBundle);
        builder.addMenuItem("Menu entry 1", pi);
    }

    private void prepareActionButton(CustomTabsIntent.Builder builder) {
        // An example intent that sends an email.
        Intent actionIntent = new Intent(Intent.ACTION_SEND);
        actionIntent.setType("*/*");
        actionIntent.putExtra(Intent.EXTRA_EMAIL, "example@example.com");
        actionIntent.putExtra(Intent.EXTRA_SUBJECT, "example");
        PendingIntent pi = PendingIntent.getActivity(this, 0, actionIntent, 0);
        Bitmap icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_share);
        builder.setActionButton(icon, "send email", pi, true);
    }

    private void prepareBottombar(CustomTabsIntent.Builder builder) {
        BottomBarManager.setMediaPlayer(mMediaPlayer);
        builder.setSecondaryToolbarViews(BottomBarManager.createRemoteViews(this, true),
                BottomBarManager.getClickableIDs(), BottomBarManager.getOnClickPendingIntent(this));
    }

    @Override
    public void onServiceConnected(CustomTabsClient client) {
        mClient = client;
        mConnectButton.setEnabled(false);
        mLaunchButton.setEnabled(true);
    }

    @Override
    public void onServiceDisconnected() {
        mConnectButton.setEnabled(true);
        mLaunchButton.setEnabled(false);
        mClient = null;
    }

    private class NavigationCallback extends CustomTabsCallback {
        @Override
        public void onNavigationEvent(int navigationEvent, Bundle extras) {
            Log.d(TAG, "onNavigationEvent: Code = " + navigationEvent);
            if (CustomTabsCallback.TAB_HIDDEN == navigationEvent) {
                // todo example 1 : add the logic to handle when the custom tab close.
                mNavigationEvent = navigationEvent;
            }
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onNavigationEvent: onResume = ");
        if (CustomTabsCallback.TAB_HIDDEN == mNavigationEvent) {
            // todo example 1:1 : add the logic to handle when the custom tab close.
            String url  = "https://developer.chrome.com/multidevice/android/customtabs";
            CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder(getSession());
            builder.setToolbarColor(Color.parseColor(TOOLBAR_COLOR)).setShowTitle(true);
            prepareMenuItems(builder);
            prepareActionButton(builder);
            prepareBottombar(builder);
            builder.setStartAnimations(this, R.anim.slide_in_right, R.anim.slide_out_left);
            builder.setExitAnimations(this, R.anim.slide_in_left, R.anim.slide_out_right);
            builder.setCloseButtonIcon(
                    BitmapFactory.decodeResource(getResources(), R.drawable.ic_arrow_back));
            CustomTabsIntent customTabsIntent = builder.build();
            customTabsIntent.intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            customTabsIntent.launchUrl(this, Uri.parse(url));

            finish();
        }

    }

    @Override
    public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {
        super.onCreate(savedInstanceState, persistentState);
        Log.d(TAG, "onNavigationEvent: onCreate = ");
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onNavigationEvent: onPause = ");
        mNavigationEvent = 0;  // todo example 1:2 : check if need reset the value.
        super.onPause();

    }

}