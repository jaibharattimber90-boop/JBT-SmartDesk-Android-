package com.jaibharat.smartdesk;

import android.app.Activity;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PageRange;
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
    private static final int SAVE_PDF_REQUEST = 4103;
    private String pendingPdfHtml = null;

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
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
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
            if (html == null || html.trim().isEmpty()) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Invoice is empty; please reopen preview", Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> {
                try {
                    pendingPdfHtml = html;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/pdf");
                    intent.putExtra(Intent.EXTRA_TITLE, "JBT-Invoice.pdf");
                    startActivityForResult(intent, SAVE_PDF_REQUEST);
                } catch (Exception e) {
                    pendingPdfHtml = null;
                    Toast.makeText(MainActivity.this, "Could not open Files to save PDF", Toast.LENGTH_LONG).show();
                }
            });
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
                                                pm.print("JBT Invoice", view.createPrintDocumentAdapter("JBT-Invoice"),
                                                    new PrintAttributes.Builder()
                                                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
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

    private void createPdfFile(Uri destination, String html) {
        try {
            if (printWebView == null) {
                printWebView = new WebView(MainActivity.this);
                configureWebView(printWebView);
                root.addView(printWebView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            }
            printWebView.setVisibility(View.VISIBLE);
            printWebView.setAlpha(1f);
            printWebView.bringToFront();
            printWebView.setWebViewClient(new WebViewClient() {
                private boolean started = false;
                @Override public void onPageFinished(WebView view, String url) {
                    if (started) return;
                    started = true;
                    view.postVisualStateCallback(SystemClock.uptimeMillis(), requestId ->
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            ParcelFileDescriptor pfd = null;
                            try {
                                pfd = getContentResolver().openFileDescriptor(destination, "w");
                                if (pfd == null) throw new IllegalStateException("Could not open selected file");
                                final ParcelFileDescriptor output = pfd;
                                PrintDocumentAdapter adapter = view.createPrintDocumentAdapter("JBT-Invoice");
                                PrintAttributes attrs = new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setResolution(new PrintAttributes.Resolution("jbt", "JBT", 300, 300))
                                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build();
                                adapter.onLayout(null, attrs, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
                                    @Override public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                                        adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, output,
                                            new CancellationSignal(), new PrintDocumentAdapter.WriteResultCallback() {
                                                @Override public void onWriteFinished(PageRange[] pages) {
                                                    try { output.close(); } catch (Exception ignored) {}
                                                    Toast.makeText(MainActivity.this, "PDF saved in Files", Toast.LENGTH_LONG).show();
                                                    webView.bringToFront();
                                                    printWebView.setVisibility(View.GONE);
                                                }
                                                @Override public void onWriteFailed(CharSequence error) {
                                                    try { output.close(); } catch (Exception ignored) {}
                                                    Toast.makeText(MainActivity.this, "PDF could not be saved", Toast.LENGTH_LONG).show();
                                                    webView.bringToFront();
                                                    printWebView.setVisibility(View.GONE);
                                                }
                                                @Override public void onWriteCancelled() {
                                                    try { output.close(); } catch (Exception ignored) {}
                                                    webView.bringToFront();
                                                    printWebView.setVisibility(View.GONE);
                                                }
                                            });
                                    }
                                    @Override public void onLayoutFailed(CharSequence error) {
                                        try { output.close(); } catch (Exception ignored) {}
                                        Toast.makeText(MainActivity.this, "Could not prepare PDF", Toast.LENGTH_LONG).show();
                                        webView.bringToFront();
                                        printWebView.setVisibility(View.GONE);
                                    }
                                }, null);
                            } catch (Exception e) {
                                if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
                                Toast.makeText(MainActivity.this, "PDF save failed", Toast.LENGTH_LONG).show();
                                webView.bringToFront();
                                printWebView.setVisibility(View.GONE);
                            }
                        }, 350));
                }
            });
            printWebView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "PDF setup failed", Toast.LENGTH_LONG).show();
            webView.bringToFront();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_PDF_REQUEST) {
            String html = pendingPdfHtml;
            pendingPdfHtml = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && html != null) {
                createPdfFile(data.getData(), html);
            } else {
                Toast.makeText(this, "PDF save cancelled", Toast.LENGTH_SHORT).show();
            }
            return;
        }
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
