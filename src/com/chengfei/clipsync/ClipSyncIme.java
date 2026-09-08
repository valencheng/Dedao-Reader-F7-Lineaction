package com.chengfei.clipsync;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.Locale;

/**
 * 极简输入法。作用不是打字体验，而是让本应用成为"默认输入法"——
 * Android 剪贴板策略规定默认输入法始终可读剪贴板（isDefaultIme 放行），
 * 同包的 ClipSyncService 由此获得稳定的后台读取能力。
 * 键盘只在用户真正唤起输入框时才创建，平时零开销。
 */
public class ClipSyncIme extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private static final int CODE_IME_PICKER = -100;   // 唤出系统输入法切换器

    private KeyboardView mKv;
    private Keyboard mAlpha, mSymbols;
    private boolean mSymbolsOn, mShiftOn;

    @Override
    public View onCreateInputView() {
        mKv = new KeyboardView(this, null);
        mKv.setOnKeyboardActionListener(this);
        mAlpha = new Keyboard(this, R.xml.kbd_alpha);
        mSymbols = new Keyboard(this, R.xml.kbd_symbols);
        mKv.setKeyboard(mAlpha);
        return mKv;
    }

    @Override
    public void onKey(int code, int[] ignored) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (code) {
            case Keyboard.KEYCODE_DELETE:
                ic.deleteSurroundingText(1, 0);
                return;
            case Keyboard.KEYCODE_SHIFT:
                mShiftOn = !mShiftOn;
                mKv.setShifted(mShiftOn);
                return;
            case Keyboard.KEYCODE_MODE_CHANGE:
                mSymbolsOn = !mSymbolsOn;
                mKv.setKeyboard(mSymbolsOn ? mSymbols : mAlpha);
                return;
            case CODE_IME_PICKER:
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
                return;
            default:
                String s = String.valueOf((char) code);
                if (mShiftOn) {
                    s = s.toUpperCase(Locale.US);
                    mShiftOn = false;
                    mKv.setShifted(false);
                }
                ic.commitText(s, 1);
        }
    }

    @Override
    public void onPress(int primaryCode) {
    }

    @Override
    public void onRelease(int primaryCode) {
    }

    @Override
    public void onText(CharSequence text) {
    }

    @Override
    public void swipeLeft() {
    }

    @Override
    public void swipeRight() {
    }

    @Override
    public void swipeDown() {
    }

    @Override
    public void swipeUp() {
    }
}
