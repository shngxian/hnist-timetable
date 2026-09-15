package cn.hnist.timetable.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 统一处理“边到边”显示：Android 15（targetSdk 35）起系统强制把内容画到状态栏和导航条下面，
 * 这里把系统栏、刘海和输入法的高度转成根布局的内边距，保证顶部按钮不被状态栏盖住、
 * 底部按钮不被导航条盖住、输入框不被键盘遮挡。所有版本行为一致。
 */
object EdgeToEdge {

    fun setup(activity: Activity, root: View) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val controller = WindowCompat.getInsetsController(window, root)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }
}
