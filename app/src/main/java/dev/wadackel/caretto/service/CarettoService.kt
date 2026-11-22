package dev.wadackel.caretto.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import dev.wadackel.caretto.core.ContextResolver
import dev.wadackel.caretto.core.EditorBridge
import dev.wadackel.caretto.core.EditorOperation
import dev.wadackel.caretto.core.GlobalAction
import dev.wadackel.caretto.core.MappedAction
import dev.wadackel.caretto.core.repeats
import dev.wadackel.caretto.core.EditorStateTracker
import dev.wadackel.caretto.core.EventLog
import dev.wadackel.caretto.core.KeyChord
import dev.wadackel.caretto.core.KeyDecision
import dev.wadackel.caretto.core.KeyPolicy
import dev.wadackel.caretto.core.KeyFilter
import dev.wadackel.caretto.core.KeyBindings
import dev.wadackel.caretto.core.Prefs
import dev.wadackel.caretto.core.deletes
import dev.wadackel.caretto.core.requiresKeyEventFallback

class CarettoService : AccessibilityService() {

    private val tracker = EditorStateTracker()
    private val keyFilter = KeyFilter()

    @Volatile
    private var inputMethod: RemapInputMethod? = null

    @Volatile
    private var foregroundPackage: String? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeating: Repetition? = null

    private var imePackages: Set<String> = emptySet()

    private val contextResolver = ContextResolver(
        inputMethodProvider = { inputMethod },
        rawAppsProvider = { Prefs.rawApps(this) },
        foregroundPackageProvider = { foregroundPackage },
    )

    private val editorBridge = EditorBridge(
        inputMethodProvider = { inputMethod },
        tracker = tracker,
        selectToDeleteProvider = { currentEditorNeedsSelectToDelete() },
    )

    private fun currentEditorNeedsSelectToDelete(): Boolean {
        val packages = Prefs.selectDeleteApps(this)
        if (packages.isEmpty()) return false
        val editor = tracker.editorPackage ?: foregroundPackage
        return editor in packages
    }

    override fun onCreateInputMethod(): InputMethod =
        RemapInputMethod(this, tracker) { Prefs.verboseLog(this) }
            .also { inputMethod = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeyBindings.seedIfUnset(this)
        imePackages = loadImePackages()
        keyFilter.reset()
        isConnected = true
        EventLog.add("service connected (ime packages=${imePackages.size})")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopRepeating()
        isConnected = false
        keyFilter.reset()
        inputMethod = null
        EventLog.add("service disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event ?: return
        val tracksForeground = source.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            source.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (!tracksForeground) return

        val packageName = source.packageName?.toString() ?: return
        if (packageName.isEmpty()) return
        // The IME and the system UI own windows of their own; neither is the app being typed into.
        if (packageName in imePackages) return
        if (packageName == SYSTEM_UI_PACKAGE) return
        if (packageName != foregroundPackage) {
            foregroundPackage = packageName
            if (Prefs.verboseLog(this)) EventLog.add("foreground -> $packageName")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val capturing = capture != null
        if (!keyFilter.isPhysicalKeyboardEvent(event)) return false
        if (Prefs.verboseLog(this)) logRawKey(event)
        // Releasing first keeps a press that began before capture opened from leaving its DOWN
        // consumed and its UP loose, which would strand the entry and fire it again later.
        if (event.action == KeyEvent.ACTION_UP) {
            val wasConsumed = keyFilter.releaseIfConsumed(event)
            if (wasConsumed) stopRepeating()
            return wasConsumed
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (capturing) stopRepeating()

        // A press already taken stays taken; the system sends no repeats for it, so any further
        // DOWN here is a fresh press of a key whose release went missing.
        keyFilter.pendingOperation(event)?.let { return true }

        stopRepeating()

        if (!event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) return false
        // A modifier is never the key a chord is named after — the capture dialog will not let one
        // be bound — so resolving it only fills the log with lines about nothing.
        if (KeyEvent.isModifierKey(event.keyCode)) return false

        // Capture reads the chord here rather than in the dialog, because the window manager takes
        // some combinations before any app sees them — Alt+Tab among them — and the service is
        // upstream of that. Everything without a command modifier has already returned above, so
        // Back and Escape still reach the dialog and still close it.
        if (offerToCapture(KeyChord.of(event), event)) return true
        // A capture that declined, or expired, leaves the key alone rather than remapping it.
        if (capturing) return false

        if (!Prefs.remapEnabled(this)) {
            if (Prefs.verboseLog(this)) {
                EventLog.add("${describe(KeyChord.of(event))} pass-through (remap disabled)")
            }
            return false
        }

        val chord = KeyChord.of(event)
        val action = KeyBindings.engine(this).resolve(chord)
        when (val decision = decide(action)) {
            is KeyDecision.PassThrough -> {
                if (action != null || Prefs.verboseLog(this)) {
                    EventLog.add("${describe(chord)} pass-through (${decision.reason}) @${editorName()}")
                }
                return false
            }

            is KeyDecision.Remap -> {
                keyFilter.markConsumed(event, decision.action)
                execute(decision.action, chord, repeated = false)
                if (decision.action.repeats) startRepeating(decision.action, chord)
                return true
            }
        }
    }

    /**
     * Repeats a held chord.
     *
     * Android synthesises key repeat downstream of the accessibility key filter, so consuming a
     * press means no repeat of it is ever generated. Holding a key has to be answered here or not
     * at all.
     */
    private fun startRepeating(action: MappedAction, chord: KeyChord) {
        repeating = Repetition(action, chord)
        repeatHandler.postDelayed(repeatTick, ViewConfiguration.getKeyRepeatTimeout().toLong())
    }

    private fun stopRepeating() {
        repeating = null
        repeatHandler.removeCallbacks(repeatTick)
    }

    private val repeatTick = object : Runnable {
        override fun run() {
            val held = repeating ?: return
            // Asked again every tick: a composition can begin, focus can move, and a repeat must
            // stop meaning something the moment the single press would have.
            // A release can go missing — the keyboard can disconnect mid-press — and a repeat
            // that outlives its key would keep editing on its own. No real hold lasts this long.
            if (held.ticks++ >= MAX_REPEATS || decide(held.action) !is KeyDecision.Remap) {
                stopRepeating()
                return
            }
            execute(held.action, held.chord, repeated = true)
            repeatHandler.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
        }
    }

    private class Repetition(val action: MappedAction, val chord: KeyChord) {
        var ticks: Int = 0
    }

    /**
     * Hands a chord to a waiting capture, and says whether it was taken.
     *
     * The deadline is read here, at the point of harm, rather than trusted from when the request
     * was made.
     */
    private fun offerToCapture(chord: KeyChord, event: KeyEvent): Boolean {
        val waiting = capture ?: return false
        if (SystemClock.uptimeMillis() > waiting.deadline) {
            endCapture()
            return false
        }
        if (!waiting.offer(chord)) return false
        endCapture()
        keyFilter.markCaptured(event)
        return true
    }

    /** Global actions go to the system, not to whatever is focused. */
    private fun perform(action: GlobalAction): EditorBridge.Result {
        val id = when (action) {
            GlobalAction.Home -> GLOBAL_ACTION_HOME
            GlobalAction.Recents -> GLOBAL_ACTION_RECENTS
            GlobalAction.Back -> GLOBAL_ACTION_BACK
            GlobalAction.AllApps -> GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS
        }
        return if (performGlobalAction(id)) {
            EditorBridge.Result.Done("performGlobalAction")
        } else {
            EditorBridge.Result.Failed("performGlobalAction refused")
        }
    }

    private fun editorName(): String = tracker.editorPackage ?: foregroundPackage ?: "-"

    private fun decide(action: MappedAction?): KeyDecision = KeyPolicy.decide(
        action = action,
        context = contextResolver.resolve(),
        composing = tracker.composing,
        fallbackEnabled = Prefs.keyEventFallbackEnabled(this),
    )

    private fun execute(action: MappedAction, chord: KeyChord, repeated: Boolean) {
        val result = when (action) {
            is EditorOperation -> editorBridge.execute(action, Prefs.keyEventFallbackEnabled(this))
            is GlobalAction -> perform(action)
        }
        val prefix = if (repeated) "repeat " else ""
        val status = when (result) {
            is EditorBridge.Result.Done -> "ok"
            is EditorBridge.Result.Failed -> "failed"
        }
        if (result is EditorBridge.Result.Failed || Prefs.verboseLog(this) || !repeated) {
            EventLog.add(
                "$prefix${describe(chord)} -> ${action.label} [$status] " +
                    "${result.detail} @${editorName()}",
            )
        }
    }

    /**
     * Selection state is read from the cached tracker rather than the editor: deciding after the
     * key is already consumed would leave no way back to a pass-through.
     */
    private fun logRawKey(event: KeyEvent) {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        EventLog.add(
            "key $action ${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")} " +
                "code=${event.keyCode} scan=${event.scanCode} " +
                "meta=0x${Integer.toHexString(event.metaState)} " +
                "device=${event.deviceId} repeat=${event.repeatCount}",
        )
    }

    private fun describe(chord: KeyChord): String = buildString {
        if (chord.ctrl) append("Ctrl+")
        if (chord.alt) append("Alt+")
        if (chord.shift) append("Shift+")
        if (chord.meta) append("Meta+")
        append(KeyEvent.keyCodeToString(chord.keyCode).removePrefix("KEYCODE_"))
    }

    private fun loadImePackages(): Set<String> {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return emptySet()
        return manager.enabledInputMethodList.mapNotNull { it.packageName }.toSet()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** Roughly twenty five seconds of holding, far past any deliberate press. */
        private const val MAX_REPEATS = 500

        @Volatile
        var isConnected: Boolean = false
            private set

        /** How long a capture stays open before it expires, on both sides of the callback. */
        const val CAPTURE_WINDOW_MS = 60_000L

        private class Capture(val deadline: Long, val offer: (KeyChord) -> Boolean)

        @Volatile
        private var capture: Capture? = null

        /**
         * Asks for the next chord the user presses.
         *
         * [offer] runs on the main thread from inside `onKeyEvent`, and its answer decides whether
         * the key is consumed — so a dialog that is no longer in front returns false and the key
         * carries on untouched. It is asked at most once: the request clears itself as soon as one
         * chord is taken, which bounds the damage of a request nobody withdrew to a single
         * keystroke rather than to every key the device receives.
         */
        fun beginCapture(offer: (KeyChord) -> Boolean) {
            capture = Capture(SystemClock.uptimeMillis() + CAPTURE_WINDOW_MS, offer)
            EventLog.add("capture mode on")
        }

        fun endCapture() {
            if (capture == null) return
            capture = null
            EventLog.add("capture mode off")
        }
    }
}
