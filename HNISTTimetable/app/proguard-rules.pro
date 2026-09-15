# Keep the JavaScript interface used by the import WebView.
-keepclassmembers class cn.hnist.timetable.LoginActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
