package com.matech.minikeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.constraintlayout.widget.ConstraintLayout
import com.matech.minikeyboard.keyboard.Keyboard
import com.matech.minikeyboard.keyboard.KeyboardView

open class KeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    companion object {
        const val TIMEOUT_CAPS_LOCK_DOUBLE_CLICK = 500L
    }

    private lateinit var inputMethodManager: InputMethodManager
    private lateinit var wordSeparators: String

    private var inputView: KeyboardView? = null
    private var qwertyKeyboard: Keyboard? = null
    private var symbolsKeyboard: Keyboard? = null
    private var symbolsShiftedKeyboard: Keyboard? = null
    private var currentKeyboard: Keyboard? = null
    private var lastDisplayWidth: Int = 0
    private var lastShiftTime: Long = 0L
    private var metaState = 0L
    private var isCapsLockOn = false
    private var isPredictionOn = false

    override fun onCreate() {
        super.onCreate()
        inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        wordSeparators = resources.getString(R.string.word_separators)
    }

    override fun onInitializeInterface() {
        val displayContext = getDisplayContext()
        if (qwertyKeyboard != null) {
            if (maxWidth == lastDisplayWidth) return
            lastDisplayWidth = maxWidth
        }
        qwertyKeyboard = Keyboard(displayContext, R.xml.qwerty)
        symbolsKeyboard = Keyboard(displayContext, R.xml.symbols)
        symbolsShiftedKeyboard = Keyboard(displayContext, R.xml.symbols_shift)
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val container = layoutInflater.inflate(R.layout.keyboard_container, null) as ConstraintLayout
        initKeyboardView(container)
        return container
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        isPredictionOn = false
        if (!restarting) metaState = 0L
        when (attribute?.inputType?.and(InputType.TYPE_MASK_CLASS)) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME, InputType.TYPE_CLASS_PHONE -> {
                currentKeyboard = symbolsKeyboard
            }
            InputType.TYPE_CLASS_TEXT -> {
                currentKeyboard = qwertyKeyboard
                isPredictionOn = true
                val variation = attribute.inputType.and(InputType.TYPE_MASK_VARIATION)
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ) {
                    isPredictionOn = false
                }
                if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || variation == InputType.TYPE_TEXT_VARIATION_URI
                    || variation == InputType.TYPE_TEXT_VARIATION_FILTER
                ) {
                    isPredictionOn = false
                }
                if (attribute.inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE != 0) {
                    isPredictionOn = false
                }
                updateShiftKeyState(attribute)
            }
            else -> {
                currentKeyboard = qwertyKeyboard
                updateShiftKeyState(attribute)
            }
        }
        attribute?.imeOptions?.let { currentKeyboard?.setImeOptions(resources, it) }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentKeyboard = qwertyKeyboard
        inputView?.closing()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputView?.apply {
            keyboard = currentKeyboard
            closing()
        }
    }

    override fun onPress(primaryCode: Int) {

    }

    override fun onRelease(primaryCode: Int) {

    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        when {
            isWordSeparator(primaryCode) -> {
                sendKey(primaryCode)
                updateShiftKeyState(currentInputEditorInfo)
            }
            primaryCode == Keyboard.KEYCODE_DELETE -> handleBackspace()
            primaryCode == Keyboard.KEYCODE_SHIFT -> handleShift()
            primaryCode == Keyboard.KEYCODE_CANCEL -> handleClose()
            primaryCode == Keyboard.KEYCODE_LANGUAGE_SWITCH -> handleSwitchKeyboard()
            primaryCode == Keyboard.KEYCODE_OPTIONS -> handleOptions()
            primaryCode == Keyboard.KEYCODE_MODE_CHANGE -> {
                inputView?.let {
                    if (it.keyboard == symbolsKeyboard || it.keyboard == symbolsShiftedKeyboard) {
                        it.keyboard = qwertyKeyboard
                    } else {
                        it.keyboard = symbolsKeyboard
                        symbolsKeyboard?.isShifted = false
                    }
                }
            }
            else -> {
                handleCharacter(primaryCode, keyCodes)
            }
        }
    }

    override fun onText(text: CharSequence?) {
        currentInputConnection?.apply {
            beginBatchEdit()
            commitText(text, 0)
            endBatchEdit()
            updateShiftKeyState(currentInputEditorInfo)
        }
    }

    override fun swipeLeft() {
        handleBackspace()
    }

    override fun swipeRight() {

    }

    override fun swipeDown() {
        handleClose()
    }

    override fun swipeUp() {

    }

    open fun initKeyboardView(container: ConstraintLayout) {
        inputView = container.findViewById(R.id.keyboard_view)
        inputView?.setOnKeyboardActionListener(this)
        inputView?.keyboard = qwertyKeyboard
    }

    private fun getDisplayContext(): Context? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.let { createDisplayContext(it) }
        } else {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            createDisplayContext(windowManager.defaultDisplay)
        }
    }

    private fun updateShiftKeyState(attribute: EditorInfo?) {
        attribute?.let { attr ->
            inputView?.let { keyboardView ->
                if (qwertyKeyboard == keyboardView.keyboard) {
                    var caps = 0
                    if (currentInputEditorInfo.inputType != InputType.TYPE_NULL) {
                        caps = currentInputConnection.getCursorCapsMode(attr.inputType)
                    }
                    keyboardView.isShifted = isCapsLockOn || caps != 0
                }
            }
        }
    }

    private fun keyDownUp(keyCode: Int) {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun isWordSeparator(code: Int): Boolean {
        return wordSeparators.contains(code.toChar())
    }

    private fun sendKey(keyCode: Int) {
        keyCode.toChar().let {
            if (it == '\n') {
                keyDownUp(KeyEvent.KEYCODE_ENTER)
            } else {
                if (it in '0'..'9') {
                    keyDownUp(it - '0' + KeyEvent.KEYCODE_0)
                } else {
                    currentInputConnection?.commitText(it.toString(), 1)
                }
            }
        }
    }

    private fun handleBackspace() {
        keyDownUp(KeyEvent.KEYCODE_DEL)
        updateShiftKeyState(currentInputEditorInfo)
    }

    private fun handleShift() {
        inputView?.let {
            when {
                qwertyKeyboard == it.keyboard -> {
                    checkToggleCapsLock()
                    it.isShifted = isCapsLockOn || !it.isShifted
                }
                it.keyboard == symbolsKeyboard -> {
                    symbolsKeyboard?.isShifted = true
                    it.keyboard = symbolsShiftedKeyboard
                    symbolsShiftedKeyboard?.isShifted = true
                }
                it.keyboard == symbolsShiftedKeyboard -> {
                    symbolsShiftedKeyboard?.isShifted = false
                    it.keyboard = symbolsKeyboard
                    symbolsKeyboard?.isShifted = false
                }
            }
        }
    }

    private fun handleCharacter(primaryCode: Int, keyCodes: IntArray?) {
        var code = primaryCode
        if (isInputViewShown) {
            if (inputView?.isShifted == true) code = Character.toUpperCase(primaryCode)
        }
        if (Character.isLetter(code) && isPredictionOn) {
            sendKey(code)
            updateShiftKeyState(currentInputEditorInfo)
        } else {
            currentInputConnection?.commitText(code.toChar().toString(), 1)
        }
    }

    private fun handleClose() {
        requestHideSelf(InputMethodManager.RESULT_UNCHANGED_SHOWN)
        inputView?.closing()
    }

    private fun handleSwitchKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).apply {
            showInputMethodPicker()
        }
    }

    private fun handleOptions() {
        // TODO: 29/10/2020 Handle show menu or something if used
    }

    private fun checkToggleCapsLock() {
        val now = System.currentTimeMillis()
        if (lastShiftTime + TIMEOUT_CAPS_LOCK_DOUBLE_CLICK > now) {
            isCapsLockOn = !isCapsLockOn
            lastShiftTime = 0
        } else {
            lastShiftTime = now
        }
    }
}