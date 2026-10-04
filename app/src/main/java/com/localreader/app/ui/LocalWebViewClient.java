package com.localreader.app.ui;

import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Java platform callbacks keep concrete overrides visible to Android WebKit's lint checker. */
final class LocalWebViewClient extends WebViewClient {
    interface Policy {
        boolean shouldOverrideUrlLoading(@Nullable WebView view, @NonNull WebResourceRequest request);
        @NonNull WebResourceResponse shouldInterceptRequest(@Nullable WebView view, @NonNull WebResourceRequest request);
        void onReceivedError(@Nullable WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceError error);
        void onReceivedHttpError(@Nullable WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceResponse response);
        void onRenderProcessGone(@NonNull WebView view, @NonNull RenderProcessGoneDetail detail);
    }

    private final Policy policy;

    LocalWebViewClient(@NonNull Policy policy) {
        this.policy = policy;
    }

    @Override public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
        return policy.shouldOverrideUrlLoading(view, request);
    }

    @Override public WebResourceResponse shouldInterceptRequest(@NonNull WebView view, @NonNull WebResourceRequest request) {
        return policy.shouldInterceptRequest(view, request);
    }

    @Override public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceError error) {
        policy.onReceivedError(view, request, error);
    }

    @Override public void onReceivedHttpError(@NonNull WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceResponse response) {
        policy.onReceivedHttpError(view, request, response);
    }

    @Override public boolean onRenderProcessGone(@NonNull WebView view, @NonNull RenderProcessGoneDetail detail) {
        policy.onRenderProcessGone(view, detail);
        return true;
    }
}
