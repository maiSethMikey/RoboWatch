package zw.robowatch.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;

/** Shows the RoboWatch page and connects it to the background drive service. */
public class MainActivity extends Activity implements DriveService.Listener {
    private static final int REQ_LOCATION = 7;

    private WebView web;
    private Speaker speaker;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(DriveService.PREFS, MODE_PRIVATE);
        speaker = new Speaker(this);

        web = new WebView(this);
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Links to websites and maps open in the phone's own apps, not inside RoboWatch.
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, hasLocationPermission(), false);
            }
        });

        DriveService.listener = this;
        web.loadUrl("file:///android_asset/index.html");
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void beginDrive() {
        if (!hasLocationPermission()) {
            ArrayList<String> want = new ArrayList<>();
            want.add(Manifest.permission.ACCESS_FINE_LOCATION);
            want.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(want.toArray(new String[0]), REQ_LOCATION);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            tellPageStopped("Location is switched off. Turn it on in the phone's quick settings, then tap Start drive again.");
            return;
        }
        try {
            startForegroundService(new Intent(this, DriveService.class));
        } catch (Exception e) {
            tellPageStopped("The drive could not be started (" + e.getClass().getSimpleName() + ").");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        if (hasLocationPermission()) {
            beginDrive();
        } else {
            tellPageStopped("RoboWatch needs precise location to warn you. Allow it, then tap Start drive again.");
        }
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (web != null) web.evaluateJavascript(code, null);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void tellPageStopped(String message) {
        js("window.__rw&&window.__rw.nativeStopped(" + (message == null ? "null" : JSONObject.quote(message)) + ")");
    }

    // ----- called by the drive service -----

    @Override
    public void onFix(double lat, double lon, float speed) {
        js("window.__rw&&window.__rw.nativeFix(" + lat + "," + lon + "," + speed + ")");
    }

    @Override
    public void onStopped() {
        tellPageStopped(null);
    }

    // ----- called by the page (window.Android) -----

    private final class Bridge {
        @JavascriptInterface
        public void setCameras(String json) {
            prefs.edit().putString("cams", json).apply();
            reloadService();
        }

        @JavascriptInterface
        public void setSettings(int radius, int limit, boolean voice) {
            prefs.edit().putInt("radius", radius).putInt("limit", limit).putBoolean("voice", voice).apply();
            reloadService();
        }

        @JavascriptInterface
        public void startDrive() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    beginDrive();
                }
            });
        }

        @JavascriptInterface
        public void stopDrive() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopService(new Intent(MainActivity.this, DriveService.class));
                }
            });
        }

        @JavascriptInterface
        public boolean isDriving() {
            return DriveService.running;
        }

        @JavascriptInterface
        public void say(String text) {
            speaker.say(text);
        }

        @JavascriptInterface
        public void beep() {
            speaker.beep(2);
        }
    }

    private void reloadService() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                DriveService service = DriveService.instance;
                if (service != null) service.loadConfig();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (DriveService.listener == this) DriveService.listener = null;
        if (speaker != null) speaker.shutdown();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
