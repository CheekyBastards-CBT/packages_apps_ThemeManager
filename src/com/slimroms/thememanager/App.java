/*
 * Copyright (C) 2017 SlimRoms Project
 * Copyright (C) 2017 Victor Lapin
 * Copyright (C) 2017 Griffin Millender
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
package com.slimroms.thememanager;

import android.app.Application;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.content.LocalBroadcastManager;
import android.system.Os;
import android.util.Log;
import com.slimroms.themecore.Broadcast;
import com.slimroms.themecore.IThemeService;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class App extends Application {
    private static final String TAG = App.class.getSimpleName();
    private static App mInstance;
    private final HashMap<ComponentName, IThemeService> mBackends = new HashMap<>();
    private final List<ServiceConnection> mConnections = new ArrayList<>();
    private final List<ComponentName> mBusyBackends = new ArrayList<>();

    public static App getInstance() {
        return mInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mInstance = this;
        registerReceiver(mBusyReceiver, Broadcast.getBackendBusyFilter());
    }

    public void bindBackends() {
        final Intent filterIntent = new Intent(Broadcast.ACTION_BACKEND_QUERY);
        final List<ResolveInfo> services = getPackageManager().queryIntentServices(filterIntent, 0);
        for (ResolveInfo ri : services) {
            if (ri.serviceInfo.exported) {
                Log.i(TAG, "Found backend: " + ri.serviceInfo.name);
                final ServiceConnection backendConnection = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                        final IThemeService backend = IThemeService.Stub.asInterface(iBinder);
                        try {
                            // if backend is unusable in current ROM setup, drop the connection
                            if (backend.isAvailable()) {
                                synchronized (mBackends) {
                                    mBackends.put(componentName, backend);
                                }
                                synchronized (mConnections) {
                                    mConnections.add(this);
                                }
                                Log.i(TAG, componentName.getClassName() + " service connected");
                                final Intent eventIntent = new Intent(Broadcast.ACTION_BACKEND_CONNECTED);
                                eventIntent.putExtra(Broadcast.EXTRA_BACKEND_NAME, componentName);
                                LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(eventIntent);
                            }
                            else {
                                unbindService(this);
                            }
                        }
                        catch (RemoteException ex) {
                            Log.e(TAG, componentName.getClassName() + " remote exception");
                            ex.printStackTrace();
                            unbindService(this);
                        }
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName componentName) {
                        synchronized (mBackends) {
                            if (mBackends.containsKey(componentName))
                                mBackends.remove(componentName);
                        }
                        synchronized (mConnections) {
                            if (mConnections.contains(this))
                                mConnections.remove(this);
                        }
                        Log.i(TAG, componentName.getClassName() + " service disconnected");
                        final Intent eventIntent = new Intent(Broadcast.ACTION_BACKEND_DISCONNECTED);
                        eventIntent.putExtra(Broadcast.EXTRA_BACKEND_NAME, componentName);
                        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(eventIntent);
                    }
                };

                final Intent backendIntent = new Intent(Broadcast.ACTION_BACKEND_QUERY);
                backendIntent.setPackage(ri.serviceInfo.packageName);
                try {
                    bindService(backendIntent, backendConnection, BIND_AUTO_CREATE);
                }
                catch (SecurityException ex) {
                    Log.i(TAG, ri.serviceInfo.name + " encountered a security exception! Skipping...", ex);
                }
            }
        }
    }

    public void unbindBackends() {
        synchronized (mBackends) {
            mBackends.clear();
        }
        synchronized (mConnections) {
            while (!mConnections.isEmpty()) {
                unbindService(mConnections.get(0));
                mConnections.remove(0);
            }
        }
    }

    public IThemeService getBackend(ComponentName name) {
        return mBackends.get(name);
    }

    public Set<ComponentName> getBackendNames() {
        return mBackends.keySet();
    }

    public boolean isBackendBusy(ComponentName backendName) {
        return backendName != null && mBusyBackends.contains(backendName);
    }

    public boolean isAnyBackendBusy() {
        return !mBusyBackends.isEmpty();
    }

    private final BroadcastReceiver mBusyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final ComponentName backendName = intent.getParcelableExtra(Broadcast.EXTRA_BACKEND_NAME);
            if (backendName != null) {
                if (intent.getAction().equals(Broadcast.ACTION_BACKEND_BUSY) && !mBusyBackends.contains(backendName)) {
                    synchronized (mBusyBackends) {
                        mBusyBackends.add(backendName);
                    }
                }
                else if (intent.getAction().equals(Broadcast.ACTION_BACKEND_NOT_BUSY)) {
                    synchronized (mBusyBackends) {
                        mBusyBackends.remove(backendName);
                    }
                }
            }
        }
    };

    public int checkSignature(String packageName) {
        return getPackageManager().checkSignatures(packageName, "android");
    }

    @Override
    public File getCacheDir() {
        boolean error = false;
        final File appCache = new File("/data/system/theme/cache/", this.getPackageName());

        if (!appCache.exists()) {
            if (appCache.mkdir()) {
                try {
                    Os.chmod(appCache.getAbsolutePath(), 644);
                }
                catch (Exception ex1) {
                    ex1.printStackTrace();
                    error = true;
                }
            } else {
                error = true;
            }
        }

        if (!error) {
            Log.i(App.TAG, "Using cache dir: " + appCache.getAbsolutePath());
            return appCache;
        } else {
            final File fallback = super.getCacheDir();
            Log.i(App.TAG, "Using fallback cache dir: " + fallback.getAbsolutePath());
            return fallback;
        }
    }

    @Override
    public File getFilesDir() {
        final File cache = getCacheDir();
        if (cache.getAbsolutePath().endsWith("/cache")) {
            // fallback
            return super.getFilesDir();
        } else {
            return cache;
        }
    }
}
