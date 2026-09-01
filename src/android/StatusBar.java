/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
*/
package org.apache.cordova.statusbar;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.content.ContextCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONException;
import java.util.Arrays;

public class StatusBar extends CordovaPlugin {
    private static final String TAG = "StatusBar";
    private CordovaWebView cordovaWebView;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener navigationBarLayoutListener;
    private View navigationBarProtectionView;

    /**
     * Sets the context of the Command. This can then be used to do things like
     * get file paths associated with the Activity.
     *
     * @param cordova The context of the main Activity.
     * @param webView The CordovaWebView Cordova is running in.
     */
    @Override
    public void initialize(final CordovaInterface cordova, CordovaWebView webView) {
        LOG.v(TAG, "StatusBar: initialization");
        super.initialize(cordova, webView);
        this.cordovaWebView = webView;

        this.cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Clear flag FLAG_FORCE_NOT_FULLSCREEN which is set initially
                // by the Cordova.
                Window window = cordova.getActivity().getWindow();
                window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);

                // Read 'StatusBarOverlaysWebView' from config.xml, default is true.
                setStatusBarTransparent(preferences.getBoolean("StatusBarOverlaysWebView", true));

                String backgroundColor = preferences.getString("StatusBarBackgroundColor", null);
                setStatusBarBackgroundColor(backgroundColor != null
                        ? backgroundColor : getThemeStatusBarColor());

                String styleSetting = preferences.getString("StatusBarStyle", null);
                if (styleSetting == null || styleSetting.isEmpty()) {
                    styleSetting = isDarkTheme() ? "lightcontent" : "default";
                }
                if (styleSetting.equalsIgnoreCase("blacktranslucent") || styleSetting.equalsIgnoreCase("blackopaque")) {
                    LOG.w(TAG, styleSetting +" is deprecated and will be removed in next major release, use lightcontent");
                }
                setStatusBarStyle(styleSetting);
                scheduleOpaqueNavigationBarUpdate();
                registerNavigationBarLayoutListener();
            }
        });
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        scheduleOpaqueNavigationBarUpdate();
    }

    @Override
    public Object onMessage(String id, Object data) {
        if ("updateSystemBars".equals(id)) {
            scheduleOpaqueNavigationBarUpdate();
        }
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!preferences.contains("StatusBarBackgroundColor")) {
                    setStatusBarBackgroundColor(getThemeStatusBarColor());
                }
                if (!preferences.contains("StatusBarStyle")) {
                    setStatusBarStyle(isDarkTheme() ? "lightcontent" : "default");
                }
                scheduleOpaqueNavigationBarUpdate();
            }
        });
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArry of arguments for the plugin.
     * @param callbackContext   The callback id used when calling back into JavaScript.
     * @return                  True if the action was valid, false otherwise.
     */
    @Override
    public boolean execute(final String action, final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        LOG.v(TAG, "Executing action: " + action);
        final Activity activity = this.cordova.getActivity();
        final Window window = activity.getWindow();

        if ("_ready".equals(action)) {
            boolean statusBarVisible;
            if (isCordovaAndroid15OrLater() && window.getDecorView().getRootWindowInsets() != null) {
                WindowInsetsCompat insets = WindowInsetsCompat.toWindowInsetsCompat(
                        window.getDecorView().getRootWindowInsets());
                statusBarVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars());
            } else {
                statusBarVisible = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) == 0;
            }
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, statusBarVisible));
            return true;
        }

        if ("show".equals(action)) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isCordovaAndroid15OrLater()) {
                        // The legacy plugin may have left the old fullscreen/layout flags set.
                        // Clear them before asking the Android 11+ controller to show the bar.
                        View decor = window.getDecorView();
                        int uiOptions = decor.getSystemUiVisibility();
                        uiOptions &= ~(View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                        uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
                        decor.setSystemUiVisibility(uiOptions);
                        WindowCompat.setDecorFitsSystemWindows(window, false);
                        WindowInsetsControllerCompat controller = getInsetsController(window);
                        if (controller != null) {
                            controller.setSystemBarsBehavior(
                                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                            controller.show(WindowInsetsCompat.Type.statusBars());
                        }
                        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                        setCordovaStatusBarViewVisible(true);
                        requestCordovaInsets();
                        return;
                    }
                    // SYSTEM_UI_FLAG_FULLSCREEN is available since JellyBean, but we
                    // use KitKat here to be aligned with "Fullscreen"  preference
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        int uiOptions = window.getDecorView().getSystemUiVisibility();
                        uiOptions &= ~View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                        uiOptions &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;

                        window.getDecorView().setSystemUiVisibility(uiOptions);
                    }

                    // CB-11197 We still need to update LayoutParams to force status bar
                    // to be hidden when entering e.g. text fields
                    window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
            });
            return true;
        }

        if ("hide".equals(action)) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isCordovaAndroid15OrLater()) {
                        WindowInsetsControllerCompat controller = getInsetsController(window);
                        if (controller != null) {
                            controller.hide(WindowInsetsCompat.Type.statusBars());
                        }
                        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                        setCordovaStatusBarViewVisible(false);
                        requestCordovaInsets();
                        return;
                    }
                    // SYSTEM_UI_FLAG_FULLSCREEN is available since JellyBean, but we
                    // use KitKat here to be aligned with "Fullscreen"  preference
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        int uiOptions = window.getDecorView().getSystemUiVisibility()
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_FULLSCREEN;

                        window.getDecorView().setSystemUiVisibility(uiOptions);
                    }

                    // CB-11197 We still need to update LayoutParams to force status bar
                    // to be hidden when entering e.g. text fields
                    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
            });
            return true;
        }

        if ("backgroundColorByHexString".equals(action)) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        setStatusBarBackgroundColor(args.getString(0));
                    } catch (JSONException ignore) {
                        LOG.e(TAG, "Invalid hexString argument, use f.i. '#777777'");
                    }
                }
            });
            return true;
        }

        if ("overlaysWebView".equals(action)) {
            if (Build.VERSION.SDK_INT >= 21) {
                this.cordova.getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            setStatusBarTransparent(args.getBoolean(0));
                        } catch (JSONException ignore) {
                            LOG.e(TAG, "Invalid boolean argument");
                        }
                    }
                });
                return true;
            }
            else return args.getBoolean(0) == false;
        }

        if ("styleDefault".equals(action)) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatusBarStyle("default");
                }
            });
            return true;
        }

        if ("styleLightContent".equals(action)) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatusBarStyle("lightcontent");
                }
            });
            return true;
        }

        if ("styleBlackTranslucent".equals(action)) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatusBarStyle("blacktranslucent");
                }
            });
            return true;
        }

        if ("styleBlackOpaque".equals(action)) {
            this.cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatusBarStyle("blackopaque");
                }
            });
            return true;
        }

        if ("styleDarkMode".equals(action)) {
            // to-do, if required
            return true;
        }

        if ("styleLightMode".equals(action)) {
            // to-do, if required
            return true;
        }

        return false;
    }

    private void setStatusBarBackgroundColor(final String colorPref) {
        if (Build.VERSION.SDK_INT >= 21) {
            if (colorPref != null && !colorPref.isEmpty()) {
                final Window window = cordova.getActivity().getWindow();
                // Method and constants not available on all SDKs but we want to be able to compile this code with any SDK
                window.clearFlags(0x04000000); // SDK 19: WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                window.addFlags(0x80000000); // SDK 21: WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                try {
                    // Using reflection makes sure any 5.0+ device will work without having to compile with SDK level 21
                    int color = Color.parseColor(colorPref);
                    window.getClass().getMethod("setStatusBarColor", int.class).invoke(window, color);
                    setCordovaStatusBarViewColor(color);
                } catch (IllegalArgumentException ignore) {
                    LOG.e(TAG, "Invalid hexString argument, use f.i. '#999999'");
                } catch (Exception ignore) {
                    // this should not happen, only in case Android removes this method in a version > 21
                    LOG.w(TAG, "Method window.setStatusBarColor not found for SDK level " + Build.VERSION.SDK_INT);
                }
            }
        }
    }

    private void setStatusBarTransparent(final boolean transparent) {
        if (Build.VERSION.SDK_INT >= 21) {
            final Window window = cordova.getActivity().getWindow();
            if (transparent) {
                if (isCordovaAndroid15OrLater()) {
                    WindowCompat.setDecorFitsSystemWindows(window, false);
                }
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                window.setStatusBarColor(Color.TRANSPARENT);
                setCordovaStatusBarViewColor(Color.TRANSPARENT);
            }
            else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }
    }

    private void setStatusBarStyle(final String style) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (style != null && !style.isEmpty()) {
                View decorView = cordova.getActivity().getWindow().getDecorView();
                int uiOptions = decorView.getSystemUiVisibility();

                String[] darkContentStyles = {
                    "default",
                };

                String[] lightContentStyles = {
                    "lightcontent",
                    "blacktranslucent",
                    "blackopaque",
                };

                if (Arrays.asList(darkContentStyles).contains(style.toLowerCase())) {
                    decorView.setSystemUiVisibility(uiOptions | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                    return;
                }

                if (Arrays.asList(lightContentStyles).contains(style.toLowerCase())) {
                    decorView.setSystemUiVisibility(uiOptions & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                    return;
                }

                LOG.e(TAG, "Invalid style, must be either 'default', 'lightcontent' or the deprecated 'blacktranslucent' and 'blackopaque'");
            }
        }
    }

    private boolean isCordovaAndroid15OrLater() {
        try {
            String version = CordovaWebView.CORDOVA_VERSION;
            int dot = version.indexOf('.');
            int major = Integer.parseInt(dot >= 0 ? version.substring(0, dot) : version);
            return major >= 15;
        } catch (Exception ignored) {
            return false;
        }
    }

    private WindowInsetsControllerCompat getInsetsController(Window window) {
        return WindowCompat.getInsetsController(window, window.getDecorView());
    }

    private boolean isDarkTheme() {
        return (cordova.getActivity().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private String getThemeStatusBarColor() {
        int colorId = cordova.getActivity().getResources().getIdentifier(
                "cdv_background_color", "color", cordova.getActivity().getPackageName());
        if (colorId != 0) {
            return String.format("#%08X", ContextCompat.getColor(cordova.getActivity(), colorId));
        }
        return isDarkTheme() ? "#121318" : "#FAF8FF";
    }

    /** Cordova 15 makes the navigation bar transparent in edge-to-edge mode. */
    private Boolean isGestureNavigation(Window window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;

        // Android exposes the selected navigation mode directly. Value 2 is
        // gestural navigation; this is more reliable than inferring the mode
        // from inset sizes, which vary across API 29/30 devices.
        int navigationMode = Settings.Secure.getInt(
                window.getContext().getContentResolver(), "navigation_mode", -1);
        if (navigationMode == 2) return true;
        if (navigationMode == 0 || navigationMode == 1) return false;

        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(window.getDecorView());
        if (insets == null) return null;

        Insets navigationBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars());
        Insets mandatoryGestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures());
        int navigationInset = Math.max(
                Math.max(navigationBars.left, navigationBars.right),
                Math.max(navigationBars.top, navigationBars.bottom));
        int gestureInset = Math.max(
                Math.max(mandatoryGestures.left, mandatoryGestures.right),
                Math.max(mandatoryGestures.top, mandatoryGestures.bottom));
        return navigationInset == 0 && gestureInset > 0;
    }

    private void scheduleOpaqueNavigationBarUpdate() {
        if (!isCordovaAndroid15OrLater()) return;
        cordova.getActivity().getWindow().getDecorView().post(this::updateNavigationBarAppearance);
    }

    private void registerNavigationBarLayoutListener() {
        if (!isCordovaAndroid15OrLater() || navigationBarLayoutListener != null) return;
        View decor = cordova.getActivity().getWindow().getDecorView();
        navigationBarLayoutListener = this::updateNavigationBarAppearance;
        decor.getViewTreeObserver().addOnGlobalLayoutListener(navigationBarLayoutListener);
    }

    private void updateNavigationBarAppearance() {
        Window window = cordova.getActivity().getWindow();
        int color = Color.parseColor(getThemeStatusBarColor());
        if (!preferences.getBoolean("StatusBarOverlaysWebView", true)) {
            window.setStatusBarColor(color);
            setCordovaStatusBarViewColor(color);
        }
        Boolean gestureNavigationResult = isGestureNavigation(window);
        if (gestureNavigationResult == null) return;
        boolean gestureNavigation = gestureNavigationResult;
        window.setNavigationBarColor(gestureNavigation ? Color.TRANSPARENT : color);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(gestureNavigation ? Color.TRANSPARENT : color);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        updateNavigationBarProtectionView(window, color, gestureNavigation);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightNavigationBars(!isDarkTheme());
    }

    /**
     * Android 15+ ignores the requested navigation-bar color for edge-to-edge
     * windows. Draw the color in app content behind the button navigation bar
     * instead, while leaving gesture navigation transparent.
     */
    private void updateNavigationBarProtectionView(Window window, int color, boolean gestureNavigation) {
        ViewGroup content = cordova.getActivity().findViewById(android.R.id.content);
        if (content == null) return;

        if (navigationBarProtectionView == null) {
            navigationBarProtectionView = new View(cordova.getActivity());
            navigationBarProtectionView.setTag("navigationBarProtectionView");
            content.addView(navigationBarProtectionView,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));
        }

        int height = 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(window.getDecorView());
        if (!gestureNavigation && insets != null) {
            height = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom;
        }

        FrameLayout.LayoutParams layoutParams =
                (FrameLayout.LayoutParams) navigationBarProtectionView.getLayoutParams();
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.height = height;
        layoutParams.gravity = Gravity.BOTTOM;
        navigationBarProtectionView.setLayoutParams(layoutParams);
        navigationBarProtectionView.setBackgroundColor(color);
        navigationBarProtectionView.setVisibility(height > 0 ? View.VISIBLE : View.GONE);
    }


    @Override
    public void onDestroy() {
        if (navigationBarLayoutListener != null && cordova != null) {
            View decor = cordova.getActivity().getWindow().getDecorView();
            if (decor.getViewTreeObserver().isAlive()) {
                decor.getViewTreeObserver().removeOnGlobalLayoutListener(navigationBarLayoutListener);
            }
            navigationBarLayoutListener = null;
        }
        if (navigationBarProtectionView != null) {
            ViewParent parent = navigationBarProtectionView.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(navigationBarProtectionView);
            }
            navigationBarProtectionView = null;
        }
        super.onDestroy();
    }

    private void setCordovaStatusBarViewColor(int color) {
        if (cordovaWebView == null) return;
        View webView = cordovaWebView.getView();
        ViewParent parent = webView.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if ("statusBarView".equals(child.getTag())) {
                    child.setBackgroundColor(color);
                    return;
                }
            }
            parent = group.getParent();
        }
    }

    /** Keep Cordova 15's synthetic inset view in sync with the real system bar. */
    private void setCordovaStatusBarViewVisible(boolean visible) {
        if (cordovaWebView == null) return;
        View webView = cordovaWebView.getView();
        ViewParent parent = webView.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if ("statusBarView".equals(child.getTag())) {
                    child.setVisibility(visible ? View.VISIBLE : View.GONE);
                    return;
                }
            }
            parent = group.getParent();
        }
    }

    private void requestCordovaInsets() {
        if (cordovaWebView == null) return;
        View webView = cordovaWebView.getView();
        ViewParent parent = webView.getParent();
        if (parent instanceof View) {
            ViewCompat.requestApplyInsets((View) parent);
        }
    }
}
