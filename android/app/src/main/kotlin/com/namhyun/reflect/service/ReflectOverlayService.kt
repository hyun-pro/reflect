package com.namhyun.reflect.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.namhyun.reflect.R
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.SuggestRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 답장창 위에 떠있는 floating overlay.
 *
 *   1. AccessibilityService 가 답장창 열림을 감지 → OverlayBus.setActive(input) → 여기로 흘러옴.
 *   2. 화면 우측에 작은 ✦ 버블 표시 (드래그 가능).
 *   3. 사용자가 버블 탭 → 추천 3개 카드 펼침.
 *   4. 추천 탭 → OverlayBus.requestCommit(text) → AccessibilityService 가 focused EditText 에 채움.
 *   5. AccessibilityService 가 결과 보고 → 토스트 피드백.
 *
 * 답장창 없으면(active==null) 자동 숨김. SYSTEM_ALERT_WINDOW 권한 없으면 즉시 stopSelf().
 */
class ReflectOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelExpanded = false

    private var currentActive: OverlayBus.ActiveInput? = null
    private var lastFetchedFor: Long = -1L // inbox id 로 캐싱 — 같은 inbox 면 재요청 안 함
    private var fetchedSuggestions: List<String> = emptyList()

    private var observeJob: Job? = null
    private var resultJob: Job? = null
    private var fetchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping")
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        observeJob = scope.launch {
            OverlayBus.active.collectLatest { active ->
                handleActiveChange(active)
            }
        }
        resultJob = scope.launch {
            OverlayBus.commitResults.collect { ok ->
                onPanelStatus(
                    if (ok) getString(R.string.overlay_committed)
                    else getString(R.string.overlay_commit_failed)
                )
                if (ok) main.postDelayed({ collapsePanel() }, 800)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // OverlayBus.active 가 이미 흘러오고 있으니 별다른 작업 없음.
        return START_STICKY
    }

    private fun handleActiveChange(active: OverlayBus.ActiveInput?) {
        currentActive = active
        if (active == null) {
            removeBubble()
            removePanel()
            // 답장창 안 보이면 서비스 살아있을 이유가 적음. KeepAliveService 가 다시 startService 해줌.
            return
        }
        showBubble()
        // 같은 inbox 면 캐시 그대로, 다르면 재요청 트리거
        if (active.originSourceId != lastFetchedFor) {
            fetchedSuggestions = active.suggestions
            lastFetchedFor = active.originSourceId
            // active 에 이미 suggestions 가 있으면 그대로 씀
            if (active.suggestions.isNotEmpty()) {
                refreshPanelIfExpanded()
            } else if (active.incoming != null) {
                fetchSuggestionsFor(active)
            }
        }
    }

    private fun fetchSuggestionsFor(active: OverlayBus.ActiveInput) {
        fetchJob?.cancel()
        fetchJob = scope.launch {
            onPanelStatus(getString(R.string.overlay_loading))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BackendApi.suggest(
                        SuggestRequest(
                            app = active.appKey,
                            contact = active.contact,
                            incoming_message = active.incoming ?: return@runCatching null,
                        )
                    ).getOrNull()
                }.getOrNull()
            }
            val suggestions = result?.suggestions.orEmpty()
            fetchedSuggestions = suggestions
            refreshPanelIfExpanded()
            if (suggestions.isEmpty()) onPanelStatus(getString(R.string.overlay_empty))
            else onPanelStatus("")
        }
    }

    // ─── Bubble ────────────────────────────────────────────────────────────────

    private fun showBubble() {
        if (bubbleView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_bubble, null, false)
        val badge = view.findViewById<TextView>(R.id.bubble_badge)
        val count = fetchedSuggestions.size.coerceAtMost(9)
        if (count > 0) {
            badge.text = count.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }

        val params = baseLayoutParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - dp(70)
            y = screenHeight() / 3
        }

        view.setOnTouchListener(BubbleDragListener(view, params))

        runCatching { wm?.addView(view, params) }
            .onFailure { Log.w(TAG, "addView bubble", it); stopSelf(); return }

        bubbleView = view
        bubbleParams = params
    }

    private fun removeBubble() {
        bubbleView?.let { v ->
            runCatching { wm?.removeView(v) }
        }
        bubbleView = null
        bubbleParams = null
    }

    // ─── Panel ─────────────────────────────────────────────────────────────────

    private fun expandPanel() {
        if (panelExpanded) return
        if (currentActive == null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_panel, null, false)
        val params = baseLayoutParams(focusable = false).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            // bubble 옆 살짝 아래에
            y = (bubbleParams?.y ?: (screenHeight() / 3)) + dp(56)
            width = (screenWidth() * 0.88f).toInt().coerceAtMost(dp(360))
        }

        view.findViewById<View>(R.id.panel_close).setOnClickListener { collapsePanel() }
        view.setOnClickListener { /* swallow */ }

        runCatching { wm?.addView(view, params) }
            .onFailure { Log.w(TAG, "addView panel", it); return }

        panelView = view
        panelExpanded = true
        renderPanel()
    }

    private fun collapsePanel() {
        panelView?.let { v -> runCatching { wm?.removeView(v) } }
        panelView = null
        panelExpanded = false
    }

    private fun removePanel() = collapsePanel()

    private fun refreshPanelIfExpanded() {
        if (!panelExpanded) {
            // panel 닫혀 있어도 bubble 의 badge 는 갱신
            bubbleView?.findViewById<TextView>(R.id.bubble_badge)?.let { badge ->
                val count = fetchedSuggestions.size.coerceAtMost(9)
                if (count > 0) {
                    badge.text = count.toString()
                    badge.visibility = View.VISIBLE
                } else {
                    badge.visibility = View.GONE
                }
            }
            return
        }
        renderPanel()
    }

    private fun renderPanel() {
        val view = panelView ?: return
        val active = currentActive
        val title = view.findViewById<TextView>(R.id.panel_title)
        val incoming = view.findViewById<TextView>(R.id.panel_incoming)
        val sug1 = view.findViewById<TextView>(R.id.sug_1)
        val sug2 = view.findViewById<TextView>(R.id.sug_2)
        val sug3 = view.findViewById<TextView>(R.id.sug_3)
        val status = view.findViewById<TextView>(R.id.panel_status)

        title.text = active?.contact?.takeIf { it.isNotBlank() } ?: getString(R.string.overlay_title)
        incoming.text = active?.incoming?.takeIf { it.isNotBlank() } ?: getString(R.string.overlay_no_context)
        incoming.visibility = if (active?.incoming.isNullOrBlank()) View.GONE else View.VISIBLE

        val list = fetchedSuggestions
        val chips = listOf(sug1, sug2, sug3)
        chips.forEachIndexed { i, chip ->
            val s = list.getOrNull(i)?.trim().orEmpty()
            if (s.isBlank()) {
                chip.visibility = View.GONE
                chip.setOnClickListener(null)
            } else {
                chip.visibility = View.VISIBLE
                chip.text = s
                chip.setOnClickListener { onSuggestionTapped(s) }
            }
        }

        status.text = when {
            list.isEmpty() && active?.incoming.isNullOrBlank() ->
                getString(R.string.overlay_no_context)
            list.isEmpty() ->
                getString(R.string.overlay_loading)
            else -> ""
        }
    }

    private fun onPanelStatus(text: String) {
        val v = panelView ?: return
        v.findViewById<TextView>(R.id.panel_status).text = text
    }

    private fun onSuggestionTapped(text: String) {
        scope.launch {
            OverlayBus.requestCommit(text)
            onPanelStatus(getString(R.string.overlay_loading))
        }
    }

    // ─── Layout helpers ─────────────────────────────────────────────────────────

    private fun baseLayoutParams(focusable: Boolean = false): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }

    private inner class BubbleDragListener(
        private val view: View,
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var startedAt = 0L

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    startedAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    params.x = (startX + dx).coerceIn(0, screenWidth() - dp(60))
                    params.y = (startY + dy).coerceIn(0, screenHeight() - dp(120))
                    runCatching { wm?.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val moved = abs(event.rawX - touchX) + abs(event.rawY - touchY)
                    val duration = System.currentTimeMillis() - startedAt
                    if (moved < dp(10).toFloat() && duration < 400) {
                        v.performClick()
                        togglePanel()
                    } else {
                        // 모서리에 자석 붙이기
                        val mid = screenWidth() / 2
                        params.x = if (params.x + dp(26) < mid) 0 else screenWidth() - dp(60)
                        runCatching { wm?.updateViewLayout(view, params) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePanel() {
        if (panelExpanded) collapsePanel() else expandPanel()
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        observeJob?.cancel()
        resultJob?.cancel()
        fetchJob?.cancel()
        removeBubble()
        removePanel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReflectOverlay"

        fun canDrawOverlays(context: Context): Boolean = runCatching {
            Settings.canDrawOverlays(context.applicationContext)
        }.getOrDefault(false)

        fun start(context: Context) {
            if (!canDrawOverlays(context)) return
            runCatching {
                val intent = Intent(context, ReflectOverlayService::class.java)
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ReflectOverlayService::class.java))
            }
        }
    }
}
