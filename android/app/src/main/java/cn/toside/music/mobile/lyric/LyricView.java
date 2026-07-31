package cn.toside.music.mobile.lyric;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.toside.music.mobile.R;

public class LyricView extends Activity implements View.OnTouchListener {
  LyricSwitchView textView = null;
  WindowManager windowManager = null;
  WindowManager.LayoutParams layoutParams = null;
  final private ReactApplicationContext reactContext;
  final private LyricEvent lyricEvent;

  // private int winWidth = 0;

  private float lastX; //上一次位置的X.Y坐标
  private float lastY;
  private float nowX;  //当前移动位置的X.Y坐标
  private float nowY;
  private float tranX; //悬浮窗移动位置的相对值
  private float tranY;
  private float prevViewPercentageX = 0;
  private float prevViewPercentageY = 0;
  private float widthPercentage = 1f;

  private float preY = 0;

  // 点击检测：记录按下时的坐标和时间，用于区分点击和拖动
  private float downX;
  private float downY;
  private long downTime;

  private boolean isLock = false;
  private boolean isSingleLine = false;
  private boolean isShowToggleAnima = false;
  private String unplayColor = "rgba(255, 255, 255, 1)";
  private String playedColor = "rgba(7, 197, 86, 1)";
  private String shadowColor = "rgba(0, 0, 0, 0.15)";
  // private String lastText = "LX Music ^-^";
  private String textX = "LEFT";
  private String textY = "TOP";
  private float alpha = 1f;
  private float textSize = 18f;
  private int maxWidth = 0;
  private int maxHeight = 0;

  private int maxLineNum = 5;
  // private float lineHeight = 1;
  private String currentLyric = "LX Music ^-^";
  private ArrayList<String> currentExtendedLyrics = new ArrayList<>();
  private int currentLineNum = -1;
  private List currentAllLines = null;

  // 当前行在 builder 中的字符区间。setText 触发的 layout 是异步的，且用单独 StaticLayout 估算的行位置
  // 与 TextView 实际渲染可能不一致（上方行换行时偏差会累积），因此改为在 layout 完成后（OnPreDraw）用
  // TextView 自己的 Layout 读取真实行位置来计算 scrollY。
  private int pendingLineStart = 0;
  private int pendingLineEnd = 0;
  private final ViewTreeObserver.OnPreDrawListener scrollApplyListener = new ViewTreeObserver.OnPreDrawListener() {
    @Override
    public boolean onPreDraw() {
      if (textView == null) return true;
      View cv = textView.getCurrentView();
      if (!(cv instanceof TextView)) return true;
      Layout layout = ((TextView) cv).getLayout();
      if (layout == null) return true; // 尚未 layout，等下一帧重试（listener 暂不移除）
      textView.getViewTreeObserver().removeOnPreDrawListener(scrollApplyListener);
      int lineStart = layout.getLineForOffset(pendingLineStart);
      int lineEnd = layout.getLineForOffset(Math.max(0, pendingLineEnd - 1));
      int center = (layout.getLineTop(lineStart) + layout.getLineBottom(lineEnd)) / 2;
      int viewCenter = layoutParams.height / 2;
      // 用 translationY 平移内容（而非 setScrollY）：translationY 是渲染期变换，不会被 TextView
      // 的 layout/scroll 机制重置，对"上方换行导致当前行偏离中心"更可靠。
      float translationY = viewCenter - center;
      textView.setChildTranslationY(translationY);
      Log.d("Lyric", "center=" + center + " viewH=" + layoutParams.height
          + " viewCenter=" + viewCenter + " transY=" + translationY
          + " lineStart=" + lineStart + " lineEnd=" + lineEnd
          + " lineCount=" + layout.getLineCount());
      return true;
    }
  };

  private int mLastRotation;
  private OrientationEventListener orientationEventListener = null;

  final Handler fixViewPositionHandler;
  final Runnable fixViewPositionRunnable = this::updateViewPosition;

  LyricView(ReactApplicationContext reactContext, LyricEvent lyricEvent) {
    this.reactContext = reactContext;
    this.lyricEvent = lyricEvent;
    fixViewPositionHandler = new Handler();
  }

  private void listenOrientationEvent() {
    if (orientationEventListener == null) {
      orientationEventListener = new OrientationEventListener(reactContext, SensorManager.SENSOR_DELAY_NORMAL) {
        @Override
        public void onOrientationChanged(int orientation) {
          Display display = windowManager.getDefaultDisplay();
          int rotation = display.getRotation();
          if(rotation != mLastRotation){
            //rotation changed
            // if (rotation == Surface.ROTATION_90){} // check rotations here
            // if (rotation == Surface.ROTATION_270){} //
            // Log.d("Lyric", "rotation: " + rotation);
            fixViewPositionHandler.postDelayed(fixViewPositionRunnable, 300);
          }
          mLastRotation = rotation;
        }
      };
    }
    // Log.d("Lyric", "orientationEventListener: " + orientationEventListener.canDetectOrientation());
    if (orientationEventListener.canDetectOrientation()) {
      orientationEventListener.enable();
    }
  }
  private void removeOrientationEvent() {
    if (orientationEventListener == null) return;
    orientationEventListener.disable();
    // orientationEventListener = null;
  }

  private int getLayoutParamsFlags() {
    int flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    if (isLock) {
      flag = flag | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    return flag;
  }

  /**
   * update screen width and height
   * @return has updated
   */
  private boolean updateWH() {
    Display display = windowManager.getDefaultDisplay();
    Point size = new Point();
    display.getRealSize(size);
    if (maxWidth == size.x && maxHeight == size.y) return false;
    maxWidth = size.x;
    maxHeight = size.y;
    return true;
  }

  private void setLayoutParamsHeight() {
    if (textView == null) return;
    int fontHeight = textView.getPaint().getFontMetricsInt(null);
    int height;
    if (isSingleLine) {
      height = fontHeight;
    } else {
      height = fontHeight * maxLineNum;
    }
    if (height > maxHeight - 100) height = maxHeight - 100;
    layoutParams.height = height;
    textView.setHeight(height);
  }

  /**
   * setText 后请求把当前行滚动到窗口垂直中心。
   * 不用单独的 StaticLayout 估算行位置：它与 TextView 实际渲染（断行策略 / 对齐 / padding）可能不一致，
   * 上方行换行时偏差会累积、使当前行偏离中心。改为在下一个绘制帧（layout 已完成）从 TextView 自己的
   * Layout 读取真实行位置计算 scrollY，与渲染完全一致；而 OnPreDraw 正好在异步 layout 之后、绘制之前，
   * 也能覆盖 setText 引起的 mScrollY 清零。
   */
  private void requestScrollToCurrentLine() {
    if (textView == null) return;
    ViewTreeObserver vto = textView.getViewTreeObserver();
    if (vto.isAlive()) {
      vto.removeOnPreDrawListener(scrollApplyListener);
      vto.addOnPreDrawListener(scrollApplyListener);
    }
  }

  private void fixViewPosition() {
    int maxX = maxWidth - layoutParams.width;
    int x = (int)(maxWidth * prevViewPercentageX);
    if (x < 0) x = 0;
    else if (x > maxX) x = maxX;
    if (layoutParams.x != x) layoutParams.x = x;

    setLayoutParamsHeight();

    int maxY = maxHeight - layoutParams.height;
    int y = (int)(maxHeight * prevViewPercentageY);
    if (y < 0) y = 0;
    else if (y > maxY) y = maxY;
    if (layoutParams.y != y) layoutParams.y = y;
  }

  private void updateViewPosition() {
    if (!updateWH()) return;

    int width = (int)(maxWidth * widthPercentage);
    if (layoutParams.width != width) {
      layoutParams.width = width;
      if (textView != null) textView.setWidth(width);
    }

    fixViewPosition();
    // Log.d("Lyric", "widthPercentage: " + widthPercentage + "  prevViewPercentageX: " + prevViewPercentageX);
    // Log.d("Lyric", "prevViewPercentageY: " + prevViewPercentageY + "  layoutParams.x: " + layoutParams.x);
    // Log.d("Lyric", "layoutParams.y: " + layoutParams.y + "  layoutParams.width: " + layoutParams.width);

    windowManager.updateViewLayout(textView, layoutParams);
  }

  public void sendPositionEvent(float x, float y) {
    WritableMap params = Arguments.createMap();
    params.putDouble("x", x);
    params.putDouble("y", y);
    lyricEvent.sendEvent(lyricEvent.SET_VIEW_POSITION, params);
  }

//  public void permission(){
//    if (Build.VERSION.SDK_INT >= 23) {
//      if(!Settings.canDrawOverlays(this)) {
//        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
//        startActivity(intent);
//        return;
//      } else {
//        //Android6.0以上
//        if (mFloatView!=null && mFloatView.isShow()==false) {
//          mFloatView.show();
//        }
//      }
//    } else {
//      //Android6.0以下，不用动态声明权限
//      if (mFloatView!=null && mFloatView.isShow()==false) {
//        mFloatView.show();
//      }
//    }
//  }
// boolean isLock, String themeColor, float alpha, int lyricViewX, int lyricViewY, String textX, String textY
  public void showLyricView(Bundle options) {
    isLock = options.getBoolean("isLock", isLock);
    isSingleLine = options.getBoolean("isSingleLine", isSingleLine);
    isShowToggleAnima = options.getBoolean("isShowToggleAnima", isShowToggleAnima);
    unplayColor = options.getString("unplayColor", unplayColor);
    playedColor = options.getString("playedColor", playedColor);
    shadowColor = options.getString("shadowColor", shadowColor);
    prevViewPercentageX = (float) options.getDouble("lyricViewX", 0f) / 100f;
    prevViewPercentageY = (float) options.getDouble("lyricViewY", 0f) / 100f;
    textX = options.getString("textX", textX);
    textY = options.getString("textY", textY);
    alpha = (float) options.getDouble("alpha", alpha);
    textSize = (float) options.getDouble("textSize", textSize);
    widthPercentage = (float) options.getDouble("width", 100) / 100f;
    maxLineNum = (int) options.getDouble("maxLineNum", maxLineNum);
    handleShowLyric();
    listenOrientationEvent();
  }
  public void showLyricView() {
    try {
      handleShowLyric();
    } catch (Exception e) {
      Log.e("Lyric", e.getMessage());
      return;
    }
    listenOrientationEvent();
  }
  public static int parseColor(String input) {
    if (input.startsWith("#")) return Color.parseColor(input);
    Pattern c = Pattern.compile("rgba? *\\( *(\\d+), *(\\d+), *(\\d+)(?:, *([\\d.]+))? *\\)");
    Matcher m = c.matcher(input);
    if (m.matches()) {
      int red = Integer.parseInt(m.group(1));
      int green = Integer.parseInt(m.group(2));
      int blue = Integer.parseInt(m.group(3));
      float a = 1;
      if (m.group(4) != null) a = Float.parseFloat(m.group(4));
      return Color.argb((int) (a * 255), red, green, blue);
    }
    return Color.parseColor("#000000");
  }

  private void createTextView() {
    textView = new LyricSwitchView(reactContext, isSingleLine, isShowToggleAnima);
    textView.setText("");
    textView.setText(currentLyric);

    textView.setTextColor(parseColor(playedColor));
    textView.setShadowColor(parseColor(shadowColor));
    textView.setAlpha(alpha);
    textView.setTextSize(textSize);
    // Log.d("Lyric", "alpha: " + alpha + " text size: " + textSize);

    //监听 OnTouch 事件 为了实现"移动歌词"功能
    textView.setOnTouchListener(this);

    int textPositionX;
    int textPositionY;
    switch (textX) {
      case "CENTER":
        textPositionX = Gravity.CENTER_HORIZONTAL;
        break;
      case "RIGHT":
        textPositionX = Gravity.END;
        break;
      case "Left":
      default:
        textPositionX = Gravity.START;
        break;
    }
    if (isSingleLine) {
      switch (textY) {
        case "CENTER":
          textPositionY = Gravity.CENTER_VERTICAL;
          break;
        case "BOTTOM":
          textPositionY = Gravity.BOTTOM;
          break;
        case "TOP":
        default:
          textPositionY = Gravity.TOP;
          break;
      }
    } else {
      // 多行模式：使用 TOP 对齐，由 padding 精确控制当前行垂直居中
      textPositionY = Gravity.TOP;
    }
    textView.setGravity(textPositionX | textPositionY);

    // 多行模式不限制 maxLines，让文本自然换行，由窗口高度 + padding 裁剪
  }
  private void handleShowLyric() {
    if (windowManager == null) {
      windowManager = (WindowManager) reactContext.getSystemService(Context.WINDOW_SERVICE);
      //设置TextView的属性
      layoutParams = new WindowManager.LayoutParams();

      DisplayMetrics outMetrics = new DisplayMetrics();
      windowManager.getDefaultDisplay().getMetrics(outMetrics);
      // winWidth = (int)(outMetrics.widthPixels * 0.92);
    }

    // 注意，悬浮窗只有一个，而当打开应用的时候才会产生悬浮窗，所以要判断悬浮窗是否已经存在，
    if (textView != null) {
      windowManager.removeView(textView);
    }

    // 使用Application context
    // 创建UI控件，避免Activity销毁导致上下文出现问题,因为现在的悬浮窗是系统级别的，不依赖与Activity存在
    //创建自定义的TextView
    createTextView();

    // layoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT | WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
    // layoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
    layoutParams.type = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ?
      WindowManager.LayoutParams.TYPE_SYSTEM_ALERT :
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    // layoutParams.flags = isLock
    //  ? WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    //  : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    layoutParams.flags = getLayoutParamsFlags();
    if (isLock) {
      textView.setBackgroundColor(Color.TRANSPARENT);

      // 修复 Android 12 的穿透点击问题
      if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
        layoutParams.alpha = 0.8f;
      }
    } else {
      textView.setBackgroundResource(R.drawable.rounded_corner);

      if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
        layoutParams.alpha = 1.0f;
      }
    }

    // TYPE_SYSTEM_ALERT  系统提示,它总是出现在应用程序窗口之上
    // TYPE_SYSTEM_OVERLAY   系统顶层窗口。显示在其他一切内容之上。此窗口不能获得输入焦点，否则影响锁屏
    // FLAG_NOT_FOCUSABLE 悬浮窗口较小时，后面的应用图标由不可长按变为可长按,不设置这个flag的话，home页的划屏会有问题
    // FLAG_NOT_TOUCH_MODAL不阻塞事件传递到后面的窗口
    layoutParams.gravity = Gravity.TOP | Gravity.START;  //显示在屏幕上中部

    updateWH();

    //悬浮窗的宽高
    // layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
    // layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
    // layoutParams.width= DisplayUtil.dp2px(mContext,55);
    // layoutParams.height= DisplayUtil.dp2px(mContext,55);
    layoutParams.width = (int)(maxWidth * widthPercentage);
    textView.setWidth(layoutParams.width);
    setLayoutParamsHeight();

    //显示位置与指定位置的相对位置差
    layoutParams.x = (int)(maxWidth * prevViewPercentageX);
    layoutParams.y = (int)(maxHeight * prevViewPercentageY);

    fixViewPosition();

    //设置透明
    layoutParams.format = PixelFormat.TRANSPARENT;

    //添加到window中
    windowManager.addView(textView, layoutParams);
  }

  public void setLyric(String text, ArrayList<String> extendedLyrics) {
    setLyric(text, extendedLyrics, currentAllLines, currentLineNum);
  }

  public void setLyric(String text, ArrayList<String> extendedLyrics, List allLines, int lineNum) {
    if (text.equals("") && text.equals(currentLyric) && extendedLyrics.size() == 0) return;
    currentLyric = text;
    currentExtendedLyrics = extendedLyrics;
    currentAllLines = allLines;
    currentLineNum = lineNum;
    if (textView == null) return;

    // 单行模式、无歌词行数据、或当前行无效时，回退到旧行为
    if (isSingleLine || allLines == null || allLines.size() == 0 || lineNum < 0) {
      if (extendedLyrics.size() > 0 && maxLineNum > 1 && !isSingleLine) {
        int num = maxLineNum - 1;
        StringBuilder textBuilder = new StringBuilder(text);
        for (String lrc : extendedLyrics) {
          textBuilder.append("\n").append(lrc);
          if (--num < 1) break;
        }
        text = textBuilder.toString();
      }
      textView.setText(text);
      textView.setChildScrollY(0);
      textView.setChildTranslationY(0f);
      return;
    }

    // 多行模式：显示前后歌词，当前行居中、用颜色区分
    TextPaint textPaint = textView.getPaint();
    if (textPaint == null) {
      textView.setText(text);
      textView.setChildScrollY(0);
      textView.setChildTranslationY(0f);
      return;
    }
    int fontHeight = textPaint.getFontMetricsInt(null);
    if (fontHeight <= 0) {
      textView.setText(text);
      textView.setChildScrollY(0);
      textView.setChildTranslationY(0f);
      return;
    }

    int windowHeight = layoutParams.height;
    int totalLines = windowHeight / fontHeight;
    if (totalLines < 1) totalLines = 1;
    if (totalLines > maxLineNum) totalLines = maxLineNum;
    // 强制奇数行，确保当前行能精确居中
    if (totalLines > 1 && totalLines % 2 == 0) totalLines--;
    int halfLines = (totalLines - 1) / 2;

    int playedColorInt = parseColor(playedColor);
    int unplayedColorInt = parseColor(unplayColor);

    SpannableStringBuilder builder = new SpannableStringBuilder();
    int currentLineStart = 0;
    int currentLineEnd = 0;
    // 始终渲染 totalLines 行，边界处用空行填充，保证当前行在文本块中心
    for (int i = lineNum - halfLines; i <= lineNum + halfLines; i++) {
      if (builder.length() > 0) builder.append("\n");

      if (i < 0 || i >= allLines.size()) {
        builder.append(" ");
      } else {
        HashMap line = (HashMap) allLines.get(i);
        String lineText = (String) line.get("text");
        if (lineText == null || lineText.isEmpty()) lineText = " ";

        int lineStart = builder.length();
        builder.append(lineText);
        int lineEnd = builder.length();

        if (i == lineNum) {
          currentLineStart = lineStart;
          currentLineEnd = lineEnd;
          builder.setSpan(new ForegroundColorSpan(playedColorInt), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
          builder.setSpan(new ForegroundColorSpan(unplayedColorInt), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
      }
    }

    if (builder.length() == 0) {
      textView.setText(text);
      textView.setChildScrollY(0);
      textView.setChildTranslationY(0f);
      return;
    }

    // 先 setText，再在 layout 完成后用 TextView 实际 Layout 计算并应用 scrollY
    // （不用单独 StaticLayout 估算，避免与实际渲染不一致、上方行换行时偏差累积导致当前行偏移）
    textView.setText(builder);
    pendingLineStart = currentLineStart;
    pendingLineEnd = currentLineEnd;
    requestScrollToCurrentLine();
  }

  public void setMaxLineNum(int maxLineNum) {
    this.maxLineNum = maxLineNum;
    if (textView == null) return;
    // 多行模式不限制 maxLines，由窗口高度裁剪
    setLayoutParamsHeight();

    int maxY = maxHeight - layoutParams.height;
    int y = layoutParams.y;
    if (y < 0) y = 0;
    else if (y > maxY) y = maxY;
    if (layoutParams.y != y) layoutParams.y = y;

    windowManager.updateViewLayout(textView, layoutParams);
    // 刷新歌词以更新前后行数
    setLyric(currentLyric, currentExtendedLyrics);
  }

  public void setWidth(int width) {
    if (textView == null) return;
    widthPercentage = width / 100f;
    layoutParams.width = (int)(maxWidth * widthPercentage);
    textView.setWidth(layoutParams.width);

    int maxX = maxWidth - layoutParams.width;
    int x = layoutParams.x;
    if (x < 0) x = 0;
    else if (x > maxX) x = maxX;
    if (layoutParams.x != x) layoutParams.x = x;

    windowManager.updateViewLayout(textView, layoutParams);
  }

  @Override
  public boolean onTouch(View v, MotionEvent event) {
    int maxX = maxWidth - layoutParams.width;
    int maxY = maxHeight - layoutParams.height;

    switch (event.getAction()){
      case MotionEvent.ACTION_DOWN:
        // 获取按下时的X，Y坐标
        lastX = event.getRawX();
        lastY = event.getRawY();

        preY = lastY;
        // 记录按下时的坐标和时间，用于点击检测
        downX = lastX;
        downY = lastY;
        downTime = System.currentTimeMillis();
        break;
      case MotionEvent.ACTION_MOVE:
        // 获取移动时的X，Y坐标
        nowX = event.getRawX();
        nowY = event.getRawY();
        if (preY == 0){
          preY = nowY;
        }
        // 计算XY坐标偏移量
        tranX = nowX - lastX;
        tranY = nowY - lastY;

        int x = layoutParams.x + (int)tranX;
        if (x < 0) x = 0;
        else if (x > maxX) x = maxX;
        int y = layoutParams.y + (int)tranY;
        if (y < 0) y = 0;
        else if (y > maxY) y = maxY;

        // 移动悬浮窗
        layoutParams.x = x;
        layoutParams.y = y;
        //更新悬浮窗位置
        windowManager.updateViewLayout(textView, layoutParams);
        //记录当前坐标作为下一次计算的上一次移动的位置坐标
        lastX = nowX;
        lastY = nowY;
        break;
      case MotionEvent.ACTION_UP:
        // 检测是否为点击（移动距离小且时间短）
        float dx = event.getRawX() - downX;
        float dy = event.getRawY() - downY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        long duration = System.currentTimeMillis() - downTime;
        if (distance < 20 && duration < 500) {
          // 点击：启动应用主界面
          Intent launchIntent = reactContext.getPackageManager().getLaunchIntentForPackage(reactContext.getPackageName());
          if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            reactContext.startActivity(launchIntent);
          }
        }

        //根据移动的位置来判断
        // dy = 0;
        tranY = 0;
        float percentageX = (float)layoutParams.x / (float) maxWidth * 100f;
        float percentageY = (float)layoutParams.y / (float) maxHeight * 100f;
        if (percentageX != prevViewPercentageX || percentageY != prevViewPercentageY) {
          prevViewPercentageX = percentageX / 100f;
          prevViewPercentageY = percentageY / 100f;
          sendPositionEvent(percentageX, percentageY);
        }
        break;
    }
    return true;
  }

  public void lockView() {
    isLock = true;
    if (windowManager == null || textView == null) return;
    layoutParams.flags = getLayoutParamsFlags();

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
      layoutParams.alpha = 0.8f;
    }
    textView.setBackgroundColor(Color.TRANSPARENT);
    windowManager.updateViewLayout(textView, layoutParams);
  }

  public void unlockView() {
    isLock = false;
    if (windowManager == null || textView == null) return;
    layoutParams.flags = getLayoutParamsFlags();

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
      layoutParams.alpha = 1.0f;
    }
    textView.setBackgroundResource(R.drawable.rounded_corner);
    windowManager.updateViewLayout(textView, layoutParams);
  }

  public void setColor(String unplayColor, String playedColor, String shadowColor) {
    this.unplayColor = unplayColor;
    this.playedColor = playedColor;
    this.shadowColor = shadowColor;
    if (textView == null) return;
    textView.setTextColor(parseColor(playedColor));
    textView.setShadowColor(parseColor(shadowColor));
    // 刷新歌词以应用新颜色
    setLyric(currentLyric, currentExtendedLyrics);
  }

  public void setLyricTextPosition(String textX, String textY) {
    this.textX = textX;
    this.textY = textY;
    if (windowManager == null || textView == null) return;
    int textPositionX;
    int textPositionY;
    // Log.d("Lyric", "textX: " + textX + "  textY: " + textY);
    switch (textX) {
      case "CENTER":
        textPositionX = Gravity.CENTER_HORIZONTAL;
        break;
      case "RIGHT":
        textPositionX = Gravity.END;
        break;
      case "LEFT":
      default:
        textPositionX = Gravity.START;
        break;
    }
    if (isSingleLine) {
      switch (textY) {
        case "CENTER":
          textPositionY = Gravity.CENTER_VERTICAL;
          break;
        case "BOTTOM":
          textPositionY = Gravity.BOTTOM;
          break;
        case "TOP":
        default:
          textPositionY = Gravity.TOP;
          break;
      }
    } else {
      // 多行模式：使用 TOP 对齐，由 padding 精确控制当前行垂直居中
      textPositionY = Gravity.TOP;
    }
    textView.setGravity(textPositionX | textPositionY);
    windowManager.updateViewLayout(textView, layoutParams);
  }

  public void setAlpha(float alpha) {
    this.alpha = alpha;
    if (textView == null) return;
    textView.setAlpha(alpha);
  }

  public void setSingleLine(boolean isSingleLine) {
    this.isSingleLine = isSingleLine;
    if (textView == null) return;
    windowManager.removeView(textView);
    createTextView();
    textView.setWidth(layoutParams.width);
    textView.setHeight(layoutParams.height);
    windowManager.addView(textView, layoutParams);

    if (isLock) lockView();
    else unlockView();

    setLyric(currentLyric, currentExtendedLyrics);
  }

  public void setShowToggleAnima(boolean showToggleAnima) {
    isShowToggleAnima = showToggleAnima;
    if (textView == null) return;
    textView.setShowAnima(showToggleAnima);
  }

  public void setTextSize(float size) {
    this.textSize = size;
    if (windowManager == null || textView == null) return;
    textView.setTextSize(size);
    setLayoutParamsHeight();
    windowManager.updateViewLayout(textView, layoutParams);
    // 刷新歌词以更新前后行数
    setLyric(currentLyric, currentExtendedLyrics);
  }

  public void destroyView() {
    if (textView == null || windowManager == null) return;
    windowManager.removeView(textView);
    textView = null;
    removeOrientationEvent();
  }

  public void destroy() {
    destroyView();
    windowManager = null;
    layoutParams = null;
  }
}
