package cn.toside.music.mobile.lyric;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;
import android.widget.TextSwitcher;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;

// https://github.com/Block-Network/StatusBarLyric/blob/main/app/src/main/java/statusbar/lyric/view/LyricSwitchView.kt
@SuppressLint({"ViewConstructor"})
public final class LyricSwitchView extends TextSwitcher {
  private final TextView textView;
  private final TextView textView2;
  private final ArrayList<TextView> viewArray;
  // private final boolean isSingleLine;
  private boolean isShowAnima;

  private boolean isSingleLine;

  public LyricSwitchView(Context context, boolean isSingleLine, boolean isShowAnima) {
    super(context);
    // this.isSingleLine = isSingleLine;
    this.isShowAnima = isShowAnima;
    this.isSingleLine = isSingleLine;

    if (isSingleLine) {
      viewArray = new ArrayList<>(2);
      textView = new LyricTextView(context);
      textView2 = new LyricTextView(context);
      viewArray.add(textView);
      viewArray.add(textView2);
//      for (TextView v : viewArray) {
//        v.setShadowLayer(0.1f, 0, 0, Color.BLACK);
//      }
    } else {
      viewArray = new ArrayList<>(2);
      textView = new TextView(context);
      textView2 = new TextView(context);
      viewArray.add(textView);
      viewArray.add(textView2);
      for (TextView v : viewArray) {
//        v.setShadowLayer(0.2f, 0, 0, Color.BLACK);
        v.setEllipsize(TextUtils.TruncateAt.END);
        // 去掉默认字体 padding，使行高精确匹配 fontMetrics，避免垂直居中偏移
        v.setIncludeFontPadding(false);
      }
    }
    setAnima();
    this.addView(textView);
    this.addView(textView2);
  }

  @Nullable
  public Animation inAnim(String str, float height) {
    AnimationSet animationSet = new AnimationSet(true);
    if (str == null) return null;

    TranslateAnimation translateAnimation;
    switch (str) {
      case "top":
        translateAnimation = new TranslateAnimation(0.0F, 0.0F, height, 0.0F);
        break;
      case "bottom":
        translateAnimation = new TranslateAnimation(0.0F, 0.0F, -height, 0.0F);
        break;
      case "left":
        translateAnimation = new TranslateAnimation(100.0F, 0.0F, 0.0F, 0.0F);
        break;
      case "right":
        translateAnimation = new TranslateAnimation(-100.0F, 0.0F, 0.0F, 0.0F);
        break;
      default: return null;
    }

    translateAnimation.setDuration(300L);
    AlphaAnimation alphaAnimation = new AlphaAnimation(0.0F, 1.0F);
    alphaAnimation.setDuration(300L);
    animationSet.addAnimation(translateAnimation);
    animationSet.addAnimation(alphaAnimation);
    return animationSet;
  }

  @Nullable
  public Animation outAnim(String str, float height) {
    AnimationSet animationSet = new AnimationSet(true);
    if (str == null) return null;

    TranslateAnimation translateAnimation;
    switch (str) {
      case "top":
        translateAnimation = new TranslateAnimation(0.0F, 0.0F, 0.0F, -height);
        break;
      case "bottom":
        translateAnimation = new TranslateAnimation(0.0F, 0.0F, 0.0F, height);
        break;
      case "left":
        translateAnimation = new TranslateAnimation(0.0F, -100.0F, 0.0F, 0.0F);
        break;
      case "right":
        translateAnimation = new TranslateAnimation(0.0F, 100.0F, 0.0F, 0.0F);
        break;
      default: return null;
    }
    translateAnimation.setDuration(300L);
    AlphaAnimation alphaAnimation = new AlphaAnimation(1.0F, 0.0F);
    alphaAnimation.setDuration(300L);
    animationSet.addAnimation(translateAnimation);
    animationSet.addAnimation(alphaAnimation);
    return animationSet;
  }

  private void setAnima() {
    if (textView == null) return;
    if (isShowAnima) {
      float size = textView.getTextSize();
      setInAnimation(inAnim("top", size));
      setOutAnimation(outAnim("top", size));
    } else {
      setInAnimation(null);
      setOutAnimation(null);
    }
  }

  public void setShowAnima(boolean showAnima) {
    isShowAnima = showAnima;
    setAnima();
  }

  public CharSequence getText() {
    View currentView = this.getCurrentView();
    return currentView == null ? "" : ((TextView)currentView).getText();
  }

  public TextPaint getPaint() {
    TextView v = (TextView)this.getCurrentView();
    if (v == null) return null;
    return v.getPaint();
  }

  public void setWidth(int i) {
    for (TextView v : viewArray) v.setWidth(i);
  }

  public void setTextColor(int i) {
    for (TextView v : viewArray) v.setTextColor(i);
  }

  public void setShadowColor(int i) {
    // float radius;
    // if (isSingleLine) {
    //   radius = 1.2f;
    // } else {
    //   radius = 2f;
    // }
    // https://stackoverflow.com/a/28367917
    for (TextView v : viewArray) v.setShadowLayer(1.6f, 1.5f, 1.3f, i);
  }

  public void setSourceText(CharSequence str) {
    for (TextView v : viewArray) v.setText(str);
  }

  public void setLetterSpacings(float letterSpacing) {
    for (TextView v : viewArray) v.setLetterSpacing(letterSpacing);
  }

  public void setHeight(int i) {
    // 不再给子 TextView 设固定高度：多行换行时内容会超出窗口高度，若子视图被固定高度裁剪，
    // 超出部分（当前行的后续视觉行、后面的歌词行）就永远画不出来。改为让子 TextView 按内容
    // 自适应高度（见 measureChildWithMargins），再由 translationY 平移 + 本视图裁剪显示窗口区间。
    // LyricSwitchView 自身高度由 windowManager 的 layoutParams.height 决定，无需在此设置。
  }

  @Override
  protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed, int parentHeightMeasureSpec, int heightUsed) {
    // 用 UNSPECIFIED 高度测量子视图 → 子 TextView 高度 = 文本内容实际高度（不被窗口高度截断），
    // 换行的内容得以完整渲染；配合 translationY + clipChildren 实现窗口内当前行居中且上下文可见。
    super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightUsed);
  }

  public void setTypeface(Typeface typeface) {
    for (TextView v : viewArray) v.setTypeface(typeface);
  }

  public void setSingleLine(boolean bool) {
    for (TextView v : viewArray) v.setSingleLine(bool);
  }

  public void setMaxLines(int i) {
    for (TextView v : viewArray) v.setMaxLines(i);
  }

  public void setTextSize(float f) {
    for (TextView v : viewArray) v.setTextSize(f);
    setAnima();
  }

  public void setGravity(int i) {
    for (TextView v : viewArray) v.setGravity(i);
  }

  public void setChildScrollY(int y) {
    for (TextView v : viewArray) v.setScrollY(y);
  }

  public void setChildTranslationY(float y) {
    for (TextView v : viewArray) v.setTranslationY(y);
  }

}
