package com.hirust.player

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings as SystemSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 桌面悬浮键控制器:UI 层写请求,服务读取并管理窗口。
 * 与播放链路零耦合(仅复用 Recorder 单例)。
 */
object FloatButtonController {

    val visible = MutableStateFlow(false)

    /** 请求显示(未授予「显示在其他应用上层」时静默忽略,设置页引导授权)。 */
    fun show(context: Context) {
        visible.value = SystemSettings.canDrawOverlays(context)
    }

    fun hide() {
        visible.value = false
    }
}

/**
 * 系统级悬浮录音键:App 退到桌面/其他应用后显示,拖动移动。
 * 点击:录音中→停止保存;空闲→开始录音(无麦克风权限则拉起主界面授权)。
 * 进程存活期内有效(随 MainActivity 启动而启动)。
 */
class FloatButtonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var button: Button? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        scope.launch {
            FloatButtonController.visible.collect { want -> if (want) attach() else detach() }
        }
        scope.launch {
            Recorder.state.collect { st ->
                button?.text = if (st.active && !st.paused) "⏹" else "🎤"
            }
        }
    }

    override fun onDestroy() {
        detach()
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        if (button != null) return
        val windowManager = wm ?: return
        if (!SystemSettings.canDrawOverlays(this)) return

        val density = resources.displayMetrics.density
        val size = (density * 52).toInt()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E614172B"))
        }
        val btn = Button(this).apply {
            text = if (Recorder.state.value.active) "⏹" else "🎤"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = bg
            elevation = 6f
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - (density * 24).toInt()
            y = resources.displayMetrics.heightPixels * 2 / 3
        }

        var downX = 0f
        var downY = 0f
        var downPx = 0
        var downPy = 0
        var moved = false
        btn.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    downPx = params.x
                    downPy = params.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || dx * dx + dy * dy > 25f) {
                        moved = true
                        params.x = downPx + dx.toInt()
                        params.y = downPy + dy.toInt()
                        runCatching { windowManager.updateViewLayout(btn, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggle()
                    true
                }
                else -> false
            }
        }
        runCatching { windowManager.addView(btn, params) }.onFailure {
            detach()
            return
        }
        button = btn
    }

    private fun detach() {
        val b = button ?: return
        runCatching { wm?.removeView(b) }
        button = null
    }

    /** 点击:录音中→停止保存;空闲→开始(无麦克风权限则拉起主界面)。 */
    private fun toggle() {
        if (Recorder.state.value.active) {
            Recorder.stop()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Recorder.start(this)
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
