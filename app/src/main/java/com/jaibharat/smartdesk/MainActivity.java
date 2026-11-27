package com.jaibharat.smartdesk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.view.View;

public class MainActivity extends Activity {
    private FrameLayout root;
    private WebView webView;
    private WebView printWebView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER_REQUEST = 4102;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        webView = new WebView(this);
        configureWebView(webView);
        webView.addJavascriptInterface(new AndroidBridge(), "jbtAndroid");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST); }
                catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        // Match the responsive HTML app at its intended viewport size.
        // Disable accidental pinch/double-tap zoom inside the APK.
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        view.setBackgroundColor(Color.WHITE);
    }

    private class AndroidBridge {
        @JavascriptInterface public void openWhatsApp(String url) {
            runOnUiThread(() -> {
                try {
                    Uri uri = Uri.parse(url);
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || !"wa.me".equalsIgnoreCase(uri.getHost()))
                        throw new IllegalArgumentException("Invalid WhatsApp URL");
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not open WhatsApp", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface public void savePdfHtml(String html) {
            // Use Android's supported PrintManager UI. The user selects
            // "Save as PDF" and chooses the destination in the system Files picker.
            printHtml(html);
        }

        @JavascriptInterface public void printHtml(String html) {
            if (html == null || html.trim().isEmpty()) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Invoice is empty; please reopen preview", Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> {
                try {
                    if (printWebView == null) {
                        printWebView = new WebView(MainActivity.this);
                        configureWebView(printWebView);
                        // Attach the print WebView to the window so Android can lay it out
                        // before creating the print document adapter. Keep it behind the
                        // main app WebView; do not make it transparent because Android's
                        // print renderer can capture a blank page from a transparent view.
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
                        root.addView(printWebView, lp);
                        printWebView.setAlpha(1f);
                        printWebView.setVisibility(View.VISIBLE);
                        // Keep the invoice WebView in the foreground while Android
                        // captures it. A WebView underneath the app WebView can be
                        // laid out but not actually drawn, producing a blank print.
                        printWebView.bringToFront();
                    }
                    printWebView.setWebViewClient(new WebViewClient() {
                        private boolean printStarted = false;

                        @Override public void onPageFinished(WebView view, String url) {
                            if (printStarted) return;
                            printStarted = true;

                            // Wait until WebView has completed a visual draw, not
                            // merely finished loading the HTML, before Android captures it.
                            view.postVisualStateCallback(
                                SystemClock.uptimeMillis(),
                                new WebView.VisualStateCallback() {
                                    @Override public void onComplete(long requestId) {
                                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                            try {
                                                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                                                if (pm == null) throw new IllegalStateException("Android print service unavailable");
                                                // The web invoice preview contains the selected CSS @page size.
                                                // Match Android's physical print media to that choice so A4 stays A4
                                                // and A5 is a true portrait A5 sheet (not a landscape A4 reduction).
                                                String normalizedHtml = html.toLowerCase().replaceAll("\\s+", "");
                                                boolean useA5 = normalizedHtml.contains("size:148mm210mm");
                                                PrintAttributes.MediaSize selectedMedia =
                                                    useA5 ? PrintAttributes.MediaSize.ISO_A5 : PrintAttributes.MediaSize.ISO_A4;
                                                pm.print("JBT Invoice", view.createPrintDocumentAdapter("JBT-Invoice"),
                                                    new PrintAttributes.Builder()
                                                        .setMediaSize(selectedMedia)
                                                        .setResolution(new PrintAttributes.Resolution("jbt", "JBT", 300, 300))
                                                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build());
                                            } catch (Exception e) {
                                                Toast.makeText(MainActivity.this, "Could not prepare invoice for printing", Toast.LENGTH_LONG).show();
                                                webView.bringToFront();
                                            }
                                        }, 700);
                                    }
                                }
                            );
                        }
                    });
                    printWebView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Print setup failed", Toast.LENGTH_LONG).show();
                }
            });
        }
    }


    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }
    @Override public void onBackPressed() {
        if (printWebView != null && printWebView.getVisibility() == View.VISIBLE) {
            printWebView.setVisibility(View.GONE);
            webView.bringToFront();
            return;
        }
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (webView != null) { webView.removeJavascriptInterface("jbtAndroid"); webView.destroy(); }
        if (printWebView != null) printWebView.destroy();
        super.onDestroy();
    }
}
