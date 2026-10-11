package com.maxmauroner.zenguard

import android.view.View

/**
 * Runs [action] once, when Android first attaches this view to a window.
 * WindowManager.addView attaches on a later layout pass, so isAttachedToWindow is still false
 * right after addView returns. A view removed before that pass can still attach once, so the
 * caller must check that the view is still its current overlay.
 */
internal fun View.doOnFirstAttach(action: () -> Unit) {
  addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
    override fun onViewAttachedToWindow(view: View) {
      view.removeOnAttachStateChangeListener(this)
      action()
    }

    override fun onViewDetachedFromWindow(view: View) = Unit
  })
}
