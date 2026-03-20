package com.vscodeandroid.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.vscodeandroid.bridge.JavascriptBridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class VSCodeWebView extends WebView {

    private JavascriptBridge javascriptBridge;
    private String currentWorkspaceFolder = "/data/data/com.termux/files/home";

    public VSCodeWebView(Context context) {
        super(context);
        init();
    }

    public VSCodeWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void init() {
        WebSettings settings = getSettings();

        // Enable everything VS Code needs
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Enable hardware acceleration
        setLayerType(LAYER_TYPE_HARDWARE, null);

        // Debugging in debug builds
        WebView.setWebContentsDebuggingEnabled(true);

        setWebViewClient(new VSCodeWebViewClient());
        setWebChromeClient(new VSCodeChromeClient());
    }

    public void initialize(Context context, JavascriptBridge bridge) {
        this.javascriptBridge = bridge;
        addJavascriptInterface(bridge, "AndroidBridge");
        loadVSCode();
    }

    private void loadVSCode() {
        // Load VS Code web from assets
        // The assets/vscode/ folder is the built output of:
        // git clone https://github.com/microsoft/vscode && yarn && yarn gulp vscode-web
        loadUrl("file:///android_asset/vscode/index.html");
    }

    public void openFile(File file) {
        String path = file.getAbsolutePath().replace("'", "\\'");
        String js = "window.vscodeAndroidBridge && window.vscodeAndroidBridge.openFile('" + path + "');";
        evaluateJavascript(js, null);
    }

    public void setWorkspaceFolder(String path) {
        this.currentWorkspaceFolder = path;
        String escapedPath = path.replace("'", "\\'");
        String js = "window.vscodeAndroidBridge && window.vscodeAndroidBridge.setWorkspace('" + escapedPath + "');";
        evaluateJavascript(js, null);
    }

    public boolean handleKeyEvent(int keyCode, KeyEvent event) {
        // Pass hardware keyboard shortcuts to VS Code
        return dispatchKeyEvent(event);
    }

    private void injectAndroidBridgePatch() {
        // Read the JS patch from assets and inject it
        try {
            InputStream is = getContext().getAssets().open("vscode-patch/android-bridge.js");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            String js = new String(buffer);
            evaluateJavascript(js, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class VSCodeWebViewClient extends WebViewClient {

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // Inject the Android bridge patch after VS Code loads
            injectAndroidBridgePatch();
            // Set the initial workspace
            setWorkspaceFolder(currentWorkspaceFolder);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            // Intercept filesystem requests made by VS Code and serve via Termux bridge
            String url = request.getUrl().toString();
            if (url.startsWith("vscode-android-fs://")) {
                return javascriptBridge.handleFileSystemRequest(url);
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Keep all navigation inside the WebView
            return false;
        }
    }

    private static class VSCodeChromeClient extends WebChromeClient {
        // Handles JS dialogs, file choosers, etc.
    }
}
