package cn.toside.music.mobile.lyric;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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
  // 悬浮窗根容器：FrameLayout 包裹「歌词+控制栏」与右上角关闭按钮，作为 WindowManager 的目标视图
  FrameLayout rootView = null;
  LinearLayout contentLayout = null;
  LinearLayout controlBar = null;
  ImageButton prevButton = null;
  ImageButton playPauseButton = null;
  ImageButton nextButton = null;
  ImageButton closeButton = null;
  // 关闭二次确认弹窗（独立的 overlay 窗口）
  ViewGroup confirmView = null;

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

  // 边缘/角落长按拖拽调整大小（resize）
  private static final int MODE_NONE = 0;     // 未确定（可能在等长按）
  private static final int MODE_MOVE = 1;     // 拖动移动窗口
  private static final int MODE_RESIZE = 2;   // 长按边缘/角落后拖动调整大小
  // resize 目标：边缘只改宽度，四个角落同时改宽高
  private static final int RESIZE_NONE = 0;
  private static final int RESIZE_EDGE_LEFT = 1;
  private static final int RESIZE_EDGE_RIGHT = 2;
  private static final int RESIZE_CORNER_TL = 3; // 左上
  private static final int RESIZE_CORNER_TR = 4; // 右上
  private static final int RESIZE_CORNER_BL = 5; // 左下
  private static final int RESIZE_CORNER_BR = 6; // 右下
  private static final int LONG_PRESS_TIMEOUT = 300; // 长按判定时长(ms)
  private int touchMode = MODE_NONE;
  private int activeResize = RESIZE_NONE; // 当前长按的调整目标
  private boolean longPressPending;       // 是否在等待长按判定
  private float downViewX;                // 按下时相对窗口的坐标（用于边缘/角落判定）
  private float downViewY;
  private int touchSlop;                  // 触摸 slop（区分点击/拖动）
  private int edgeSlop;                   // 边缘判定宽度(px)
  // resize 起始基准（按下时快照，用相对 delta 计算以规避坐标系偏移）
  private int resizeStartWidth;
  private int resizeStartX;
  private int resizeRightEdge;
  private int resizeStartHeight;          // 歌词区高度基准（不含控制栏）
  private int resizeStartY;
  private int resizeBottomEdge;

  private boolean isLock = false;
  private boolean isSingleLine = false;
  private boolean isShowToggleAnima = false;
  private boolean isPlaying = false;      // 播放状态，用于切换播放/暂停按钮图标
  private String unplayColor = "rgba(255, 255, 255, 1)";
  private String playedColor = "rgba(7, 197, 86, 1)";
  private String shadowColor = "rgba(0, 0, 0, 0.15)";
  // private String lastText = "LX Music ^-^";
  private String textX = "LEFT";
  private String textY = "TOP";
  private float alpha = 1f;
  private float backgroundAlpha = 0.5f; // 歌词背景透明度（0~1）
  private float textSize = 18f;
  private int maxWidth = 0;
  private int maxHeight = 0;

  private int maxLineNum = 5;
  // private float lineHeight = 1;
  private String currentLyric = "LX Music ^-^";
  private ArrayList<String> currentExtendedLyrics = new ArrayList<>();
  private int currentLineNum = -1;
  private List currentAllLines = null;

  // 控制栏固定高度(px)，歌词区高度 = fontHeight * maxLineNum，窗口总高 = 歌词区 + 控制栏
  private int controlBarHeightPx = 0;
  private int lyricAreaHeightPx = 0;

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
      // 居中以「歌词区」高度为准（不含控制栏），否则当前行会被控制栏挤偏
      int viewCenter = lyricAreaHeightPx / 2;
      // 减去子 TextView 的 paddingTop：getLineTop 相对内容区(=TextView top + paddingTop)，
      // 加 padding 后当前行实际位置下移了 paddingTop，需补回去才能垂直居中
      float translationY = viewCenter - center - cv.getPaddingTop();
      textView.setChildTranslationY(translationY);
      return true;
    }
  };

  private int mLastRotation;
  private OrientationEventListener orientationEventListener = null;

  final Handler fixViewPositionHandler;
  final Runnable fixViewPositionRunnable = this::updateViewPosition;
  // 边缘长按判定回调（到达时长后进入 resize 模式）
  final Runnable edgeLongPressRunnable = this::onEdgeLongPress;

  // 毛玻璃模糊半径(px)。Android 12+ 用于 setBlurBehindRadius；设备不支持时由背景 drawable 兜底。
  private int blurBehindRadiusPx = 0;

  LyricView(ReactApplicationContext reactContext, LyricEvent lyricEvent) {
    this.reactContext = reactContext;
    this.lyricEvent = lyricEvent;
    fixViewPositionHandler = new Handler();
    blurBehindRadiusPx = dp(20);
    controlBarHeightPx = dp(44);
    touchSlop = ViewConfiguration.get(reactContext).getScaledTouchSlop();
    edgeSlop = dp(22);
  }

  private int dp(int dps) {
    return (int) TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dps, reactContext.getResources().getDisplayMetrics());
  }

  /**
   * 毛玻璃（暂禁用）。
   * 悬浮窗(WindowManager.LayoutParams)只有 setBlurBehindRadius、没有 setBackgroundBlurRadius
   * (后者是 Window 的方法，悬浮窗没有 Window 对象)。而 setBlurBehindRadius 会把背景 drawable 仅
   * 当作模糊轮廓、不绘制其 solid 填充——支持模糊的设备上呈现的是"模糊+透明"(无卡片底色)，
   * 不支持模糊的设备/悬浮窗上则背景完全透明。两者都做不出"半透明卡片毛玻璃"。
   * 因此先禁用模糊，用 rounded_corner 的半透明圆角背景作为磨砂卡片兜底。
   */
  private void applyBlurBehind() {
    if (layoutParams == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      layoutParams.setBlurBehindRadius(isLock ? 0 : blurBehindRadiusPx);
    }
  }

  /**
   * 代码构造圆角 + 边框 + 半透明背景。
   * XML ShapeDrawable（含 stroke）在悬浮窗上渲染异常（背景完全不显示），改用代码构造 GradientDrawable。
   */
  private GradientDrawable buildLyricBackground() {
    float density = reactContext.getResources().getDisplayMetrics().density;
    // 上深下浅渐变（两端靠近、过渡小），增加卡片质感
    GradientDrawable bg = new GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        new int[]{ 0xD9000000, 0xB3000000 });
    bg.setCornerRadius(10f * density);
    bg.setStroke((int) (1f * density), 0x66FFFFFF);
    bg.setAlpha((int) (backgroundAlpha * 255));
    return bg;
  }

  /**
   * 长按边缘进入 resize 模式时的高亮背景：加粗亮色边框 + 略增不透明度，
   * 让用户明显感知已进入"调整大小"状态。
   */
  private GradientDrawable buildResizeBackground() {
    float density = reactContext.getResources().getDisplayMetrics().density;
    GradientDrawable bg = new GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        new int[]{ 0xD9000000, 0xB3000000 });
    bg.setCornerRadius(10f * density);
    bg.setStroke((int) (2f * density), 0xFFFFFFFF); // 加粗白色高亮边框
    bg.setAlpha((int) (Math.min(1f, backgroundAlpha + 0.15f) * 255));
    return bg;
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
    // 歌词区高度上限：留出控制栏高度
    int maxLyricH = maxHeight - 100 - controlBarHeightPx;
    if (maxLyricH < fontHeight) maxLyricH = fontHeight;
    if (height > maxLyricH) height = maxLyricH;
    lyricAreaHeightPx = height;
    // 窗口总高 = 歌词区 + 控制栏
    layoutParams.height = lyricAreaHeightPx + controlBarHeightPx;
    applyLyricAreaHeight();
  }

  /**
   * 把歌词区高度写进 textView 的 LayoutParams（LyricSwitchView.setHeight 是 no-op，必须走 LayoutParams）。
   */
  private void applyLyricAreaHeight() {
    if (textView == null) return;
    ViewGroup.LayoutParams lp = textView.getLayoutParams();
    if (lp == null) {
      lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, lyricAreaHeightPx);
      textView.setLayoutParams(lp);
    } else {
      lp.height = lyricAreaHeightPx;
      textView.setLayoutParams(lp);
    }
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

    windowManager.updateViewLayout(rootView, layoutParams);
  }

  public void sendPositionEvent(float x, float y) {
    WritableMap params = Arguments.createMap();
    params.putDouble("x", x);
    params.putDouble("y", y);
    lyricEvent.sendEvent(lyricEvent.SET_VIEW_POSITION, params);
  }

  public void sendWidthEvent() {
    // 上报宽度百分比给 JS 持久化（与 desktopLyric.width 设置项一致，范围 10~100）
    int percent = Math.round(widthPercentage * 100f);
    if (percent < 10) percent = 10;
    else if (percent > 100) percent = 100;
    WritableMap params = Arguments.createMap();
    params.putInt("width", percent);
    lyricEvent.sendEvent(lyricEvent.SET_VIEW_WIDTH, params);
  }

  public void sendMaxLineNumEvent() {
    // 上报可见行数给 JS 持久化（最少 1 行，上限由屏幕高度决定）
    int lineNum = Math.max(1, maxLineNum);
    WritableMap params = Arguments.createMap();
    params.putInt("maxLineNum", lineNum);
    lyricEvent.sendEvent(lyricEvent.SET_VIEW_MAX_LINE_NUM, params);
  }

  /** 控制按钮点击上报：action = prev / playPause / next */
  private void sendControlEvent(String action) {
    WritableMap params = Arguments.createMap();
    params.putString("action", action);
    lyricEvent.sendEvent(lyricEvent.CONTROL, params);
  }

  /** 关闭桌面歌词（已二次确认） */
  private void sendCloseEvent() {
    lyricEvent.sendEvent(lyricEvent.CLOSE, null);
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
    backgroundAlpha = (float) options.getDouble("backgroundAlpha", backgroundAlpha);
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

  /** 构造一个控制按钮（透明背景、着色为已播放色、48dp 触控区、24dp 图标） */
  private ImageButton buildControlButton(int res, View.OnClickListener listener) {
    ImageButton btn = new ImageButton(reactContext);
    btn.setImageResource(res);
    btn.setColorFilter(parseColor(playedColor));
    btn.setBackgroundColor(Color.TRANSPARENT);
    int size = dp(48);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
    lp.gravity = Gravity.CENTER;
    btn.setLayoutParams(lp);
    int pad = dp(12);
    btn.setPadding(pad, pad, pad, pad);
    btn.setOnClickListener(listener);
    return btn;
  }

  /** 构造控制栏：上一首 / 播放暂停 / 下一首 */
  private LinearLayout buildControlBar() {
    LinearLayout bar = new LinearLayout(reactContext);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER); // 水平 + 垂直都居中
    bar.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, controlBarHeightPx));
    prevButton = buildControlButton(R.drawable.ic_lyric_prev, v -> sendControlEvent("prev"));
    playPauseButton = buildControlButton(
        isPlaying ? R.drawable.ic_lyric_pause : R.drawable.ic_lyric_play,
        v -> sendControlEvent("playPause"));
    nextButton = buildControlButton(R.drawable.ic_lyric_next, v -> sendControlEvent("next"));
    bar.addView(prevButton);
    bar.addView(playPauseButton);
    bar.addView(nextButton);
    return bar;
  }

  /**
   * 组装根容器：FrameLayout 内放「歌词区 + 控制栏」纵向布局，以及右上角关闭按钮。
   * OnTouchListener 绑在 rootView 上：按钮自身消费点击不会触发拖动/打开 App。
   */
  private void buildRootView() {
    contentLayout = new LinearLayout(reactContext);
    contentLayout.setOrientation(LinearLayout.VERTICAL);
    textView.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    contentLayout.addView(textView, 0);
    controlBar = buildControlBar();
    contentLayout.addView(controlBar);

    closeButton = new ImageButton(reactContext);
    closeButton.setImageResource(R.drawable.ic_lyric_close);
    closeButton.setColorFilter(parseColor(playedColor));
    closeButton.setBackgroundColor(Color.TRANSPARENT);
    int cs = dp(32);
    FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(cs, cs, Gravity.TOP | Gravity.END);
    int m = dp(4);
    closeLp.setMargins(m, m, m, m);
    closeButton.setLayoutParams(closeLp);
    int cp = dp(7);
    closeButton.setPadding(cp, cp, cp, cp);
    closeButton.setOnClickListener(v -> showCloseConfirm());

    rootView = new FrameLayout(reactContext);
    rootView.addView(contentLayout, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    rootView.addView(closeButton);
    // 监听 OnTouch 事件 为了实现"移动歌词 / 边缘缩放 / 点击打开"功能
    rootView.setOnTouchListener(this);
  }

  /** 显示「确认关闭桌面歌词？」二次确认（独立的 overlay 窗口，应用在后台时也可见） */
  private void showCloseConfirm() {
    if (confirmView != null || windowManager == null) return;
    float density = reactContext.getResources().getDisplayMetrics().density;

    LinearLayout card = new LinearLayout(reactContext);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER_HORIZONTAL);
    int pad = (int) (20f * density);
    card.setPadding(pad, pad, pad, pad);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xD9000000);
    bg.setCornerRadius(12f * density);
    bg.setStroke((int) (1f * density), 0x66FFFFFF);
    card.setBackground(bg);
    card.setLayoutParams(new LinearLayout.LayoutParams((int) (260f * density), LinearLayout.LayoutParams.WRAP_CONTENT));

    TextView title = new TextView(reactContext);
    title.setText("确认关闭桌面歌词？");
    title.setTextColor(Color.WHITE);
    title.setTextSize(15f);
    title.setGravity(Gravity.CENTER);
    title.setPadding(0, 0, 0, (int) (16f * density));
    card.addView(title);

    LinearLayout btnRow = new LinearLayout(reactContext);
    btnRow.setOrientation(LinearLayout.HORIZONTAL);
    btnRow.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    Button cancel = new Button(reactContext);
    cancel.setText("取消");
    cancel.setTextColor(Color.WHITE);
    cancel.setBackgroundColor(Color.TRANSPARENT);
    Button ok = new Button(reactContext);
    ok.setText("确认");
    ok.setTextColor(parseColor(playedColor));
    ok.setBackgroundColor(Color.TRANSPARENT);
    cancel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    ok.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    cancel.setOnClickListener(v -> removeCloseConfirm());
    ok.setOnClickListener(v -> { removeCloseConfirm(); sendCloseEvent(); });
    btnRow.addView(cancel);
    btnRow.addView(ok);
    card.addView(btnRow);

    WindowManager.LayoutParams p = new WindowManager.LayoutParams();
    p.type = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ?
      WindowManager.LayoutParams.TYPE_SYSTEM_ALERT :
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    p.format = PixelFormat.TRANSPARENT;
    p.gravity = Gravity.CENTER;
    p.width = WindowManager.LayoutParams.WRAP_CONTENT;
    p.height = WindowManager.LayoutParams.WRAP_CONTENT;

    confirmView = card;
    windowManager.addView(confirmView, p);
  }

  private void removeCloseConfirm() {
    if (confirmView == null) return;
    if (windowManager != null) {
      try { windowManager.removeView(confirmView); } catch (Exception ignored) {}
    }
    confirmView = null;
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
    if (rootView != null) {
      try { windowManager.removeView(rootView); } catch (Exception ignored) {}
    }

    // 使用Application context
    // 创建UI控件，避免Activity销毁导致上下文出现问题,因为现在的悬浮窗是系统级别的，不依赖与Activity存在
    //创建自定义的TextView
    createTextView();
    buildRootView();

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
      rootView.setBackgroundColor(Color.TRANSPARENT);
      if (controlBar != null) controlBar.setVisibility(View.GONE);
      if (closeButton != null) closeButton.setVisibility(View.GONE);

      // 修复 Android 12 的穿透点击问题
      if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
        layoutParams.alpha = 0.8f;
      }
    } else {
      rootView.setBackground(buildLyricBackground());
      if (controlBar != null) controlBar.setVisibility(View.VISIBLE);
      if (closeButton != null) closeButton.setVisibility(View.VISIBLE);

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
    layoutParams.width = (int)(maxWidth * widthPercentage);
    textView.setWidth(layoutParams.width);
    setLayoutParamsHeight();

    //显示位置与指定位置的相对位置差
    layoutParams.x = (int)(maxWidth * prevViewPercentageX);
    layoutParams.y = (int)(maxHeight * prevViewPercentageY);

    fixViewPosition();

    //设置透明
    layoutParams.format = PixelFormat.TRANSPARENT;

    applyBlurBehind();

    //添加到window中
    windowManager.addView(rootView, layoutParams);
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

    // totalLines 以「歌词区」高度为准（不含控制栏）
    int windowHeight = lyricAreaHeightPx;
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

    windowManager.updateViewLayout(rootView, layoutParams);
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

    windowManager.updateViewLayout(rootView, layoutParams);
  }

  /**
   * 边缘长按判定到达：进入 resize 模式，给视觉 + 触感反馈。
   */
  private void onEdgeLongPress() {
    if (!longPressPending) return;
    longPressPending = false;
    touchMode = MODE_RESIZE;
    // 以按下时为基准，resize 用相对 delta 计算（规避 raw 坐标与窗口坐标系间的固定偏移）
    resizeStartWidth = layoutParams.width;
    resizeStartX = layoutParams.x;
    resizeRightEdge = layoutParams.x + layoutParams.width;
    // 高度基准用「歌词区」高度，避免把控制栏高度也算进缩放
    resizeStartHeight = lyricAreaHeightPx;
    resizeStartY = layoutParams.y;
    resizeBottomEdge = layoutParams.y + layoutParams.height;
    // 视觉反馈 + 触感
    if (rootView != null) {
      rootView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
      if (!isLock) rootView.setBackground(buildResizeBackground());
    }
  }

  /**
   * resize 模式下根据手势调整窗口大小：
   * - 左/右边缘只改宽度；四个角落同时改宽高。
   * - 高度对齐到整行(fontHeight)的整数倍，换算成 maxLineNum，保持与 fontHeight×maxLineNum 模型一致。
   *   高度只影响「歌词区」，控制栏高度固定，窗口总高 = 歌词区 + 控制栏。
   */
  private void handleResize(MotionEvent event) {
    float deltaX = event.getRawX() - downX;
    float deltaY = event.getRawY() - downY;

    boolean resizeLeft = activeResize == RESIZE_EDGE_LEFT ||
      activeResize == RESIZE_CORNER_TL || activeResize == RESIZE_CORNER_BL;
    boolean resizeRight = activeResize == RESIZE_EDGE_RIGHT ||
      activeResize == RESIZE_CORNER_TR || activeResize == RESIZE_CORNER_BR;
    boolean resizeTop = activeResize == RESIZE_CORNER_TL || activeResize == RESIZE_CORNER_TR;
    boolean resizeBottom = activeResize == RESIZE_CORNER_BL || activeResize == RESIZE_CORNER_BR;

    // ---- 宽度 ----
    int minW = (int) (maxWidth * 0.10f); // 与设置项最小值(10%)一致
    int newWidth = layoutParams.width;
    int newX = layoutParams.x;
    if (resizeLeft) {
      newWidth = resizeStartWidth - (int) deltaX;
      if (newWidth < minW) newWidth = minW;
      if (newWidth > maxWidth) newWidth = maxWidth;
      newX = resizeRightEdge - newWidth;
      if (newX < 0) { newX = 0; newWidth = resizeRightEdge; }
    } else if (resizeRight) {
      newWidth = resizeStartWidth + (int) deltaX;
      if (newWidth < minW) newWidth = minW;
      int limit = maxWidth - resizeStartX; // 右边缘不超出屏幕
      if (newWidth > limit) newWidth = limit;
      newX = resizeStartX;
    }

    // ---- 高度（仅角落：换算成 maxLineNum，只改歌词区） ----
    int newLyricHeight = lyricAreaHeightPx;
    int newY = layoutParams.y;
    int newMaxLineNum = maxLineNum;
    if (resizeTop || resizeBottom) {
      int fontHeight = textView.getPaint().getFontMetricsInt(null);
      if (fontHeight < 1) fontHeight = lyricAreaHeightPx / Math.max(1, maxLineNum);
      int minH = fontHeight;
      int maxH = maxHeight - 100 - controlBarHeightPx;
      if (resizeTop) {
        newLyricHeight = resizeStartHeight - (int) deltaY;
      } else {
        newLyricHeight = resizeStartHeight + (int) deltaY;
      }
      if (newLyricHeight < minH) newLyricHeight = minH;
      if (newLyricHeight > maxH) newLyricHeight = maxH;
      // 换算成行数并对齐到整行高度（上限由屏幕高度决定，不固定 8 行）
      int maxLines = Math.max(1, (int) Math.floor((float) maxH / (float) fontHeight));
      newMaxLineNum = Math.max(1, Math.min(maxLines, Math.round((float) newLyricHeight / (float) fontHeight)));
      newLyricHeight = fontHeight * newMaxLineNum;
      // 顶部调整保持底边固定（窗口总高 = 歌词区 + 控制栏）；底部调整保持顶边固定
      newY = resizeTop ? (resizeBottomEdge - newLyricHeight - controlBarHeightPx) : resizeStartY;
    }

    if (newWidth == layoutParams.width && newX == layoutParams.x &&
      newLyricHeight == lyricAreaHeightPx && newY == layoutParams.y &&
      newMaxLineNum == maxLineNum) return;

    // 应用宽度
    widthPercentage = (float) newWidth / (float) maxWidth;
    layoutParams.width = newWidth;
    layoutParams.x = newX;
    textView.setWidth(newWidth);

    // 应用高度
    boolean lineNumChanged = newMaxLineNum != maxLineNum;
    maxLineNum = newMaxLineNum;
    lyricAreaHeightPx = newLyricHeight;
    layoutParams.height = lyricAreaHeightPx + controlBarHeightPx;
    applyLyricAreaHeight();
    layoutParams.y = newY;
    int maxY = maxHeight - layoutParams.height;
    if (layoutParams.y < 0) layoutParams.y = 0;
    else if (layoutParams.y > maxY) layoutParams.y = maxY;

    windowManager.updateViewLayout(rootView, layoutParams);
    // 行数变化时刷新歌词，使多行模式渲染对应行数
    if (lineNumChanged) setLyric(currentLyric, currentExtendedLyrics);
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
        downViewX = event.getX();
        downViewY = event.getY();
        touchMode = MODE_NONE;
        longPressPending = false;

        // 锁定状态下窗口不接收触摸(FLAG_NOT_TOUCHABLE)，保险起见仍跳过边缘/角落判定
        if (!isLock) {
          int vw = v.getWidth();
          int vh = v.getHeight();
          // 窗口很扁/很窄时，按"是否过半"判定上下/左右，避免整面都落进同一个边缘区
          // 导致对侧角抓不到（拉到最扁后无法再调高度）。
          int zoneX = Math.min(edgeSlop, vw / 2);
          int zoneY = Math.min(edgeSlop, vh / 2);
          boolean onLeft = downViewX <= zoneX;
          boolean onRight = downViewX >= vw - zoneX;
          boolean onTop = downViewY <= zoneY;
          boolean onBottom = downViewY >= vh - zoneY;
          int target = RESIZE_NONE;
          if (onTop && onLeft) target = RESIZE_CORNER_TL;
          else if (onTop && onRight) target = RESIZE_CORNER_TR;
          else if (onBottom && onLeft) target = RESIZE_CORNER_BL;
          else if (onBottom && onRight) target = RESIZE_CORNER_BR;
          else if (onLeft) target = RESIZE_EDGE_LEFT;
          else if (onRight) target = RESIZE_EDGE_RIGHT;
          if (target != RESIZE_NONE) {
            activeResize = target;
            longPressPending = true;
            fixViewPositionHandler.postDelayed(edgeLongPressRunnable, LONG_PRESS_TIMEOUT);
          }
        }
        break;
      case MotionEvent.ACTION_MOVE: {
        // 获取移动时的X，Y坐标
        nowX = event.getRawX();
        nowY = event.getRawY();
        if (preY == 0) preY = nowY;

        if (touchMode == MODE_RESIZE) {
          // 边缘长按已确认：拖动调整大小
          handleResize(event);
          lastX = nowX;
          lastY = nowY;
          break;
        }

        // 未进入 resize：越过 slop 即确定为"移动"，并取消挂起的长按判定
        if (touchMode == MODE_NONE) {
          float ddx = nowX - downX;
          float ddy = nowY - downY;
          if (ddx * ddx + ddy * ddy > touchSlop * touchSlop) {
            if (longPressPending) {
              longPressPending = false;
              fixViewPositionHandler.removeCallbacks(edgeLongPressRunnable);
            }
            touchMode = MODE_MOVE;
          }
        }
        if (touchMode == MODE_MOVE) {
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
          windowManager.updateViewLayout(rootView, layoutParams);
          //记录当前坐标作为下一次计算的上一次移动的位置坐标
          lastX = nowX;
          lastY = nowY;
        }
        break;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL: {
        // 取消可能挂起的长按判定
        if (longPressPending) {
          longPressPending = false;
          fixViewPositionHandler.removeCallbacks(edgeLongPressRunnable);
        }

        boolean wasResizing = touchMode == MODE_RESIZE;

        if (touchMode == MODE_NONE) {
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
        } else {
          // 移动 / 缩放都可能改变窗口位置，统一上报位置
          float percentageX = (float)layoutParams.x / (float) maxWidth * 100f;
          float percentageY = (float)layoutParams.y / (float) maxHeight * 100f;
          if (percentageX != prevViewPercentageX || percentageY != prevViewPercentageY) {
            prevViewPercentageX = percentageX / 100f;
            prevViewPercentageY = percentageY / 100f;
            sendPositionEvent(percentageX, percentageY);
          }
          // resize 结束：上报宽度 / 行数给 JS 持久化
          if (wasResizing) {
            sendWidthEvent();
            sendMaxLineNumEvent();
          }
        }

        // 退出 resize：恢复普通背景
        if (wasResizing && rootView != null && !isLock) {
          rootView.setBackground(buildLyricBackground());
        }

        touchMode = MODE_NONE;
        activeResize = RESIZE_NONE;
        break;
      }
    }
    return true;
  }

  public void lockView() {
    isLock = true;
    if (windowManager == null || rootView == null) return;
    removeCloseConfirm();
    layoutParams.flags = getLayoutParamsFlags();

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
      layoutParams.alpha = 0.8f;
    }
    rootView.setBackgroundColor(Color.TRANSPARENT);
    // 锁定后窗口 FLAG_NOT_TOUCHABLE，控制栏/关闭按钮无法点击，隐藏以免误导
    if (controlBar != null) controlBar.setVisibility(View.GONE);
    if (closeButton != null) closeButton.setVisibility(View.GONE);
    applyBlurBehind();
    windowManager.updateViewLayout(rootView, layoutParams);
  }

  public void unlockView() {
    isLock = false;
    if (windowManager == null || rootView == null) return;
    layoutParams.flags = getLayoutParamsFlags();

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
      layoutParams.alpha = 1.0f;
    }
    rootView.setBackground(buildLyricBackground());
    if (controlBar != null) controlBar.setVisibility(View.VISIBLE);
    if (closeButton != null) closeButton.setVisibility(View.VISIBLE);
    applyBlurBehind();
    windowManager.updateViewLayout(rootView, layoutParams);
  }

  public void setColor(String unplayColor, String playedColor, String shadowColor) {
    this.unplayColor = unplayColor;
    this.playedColor = playedColor;
    this.shadowColor = shadowColor;
    if (textView == null) return;
    textView.setTextColor(parseColor(playedColor));
    textView.setShadowColor(parseColor(shadowColor));
    // 控制按钮 / 关闭按钮 同步着色为已播放色
    int c = parseColor(playedColor);
    if (prevButton != null) prevButton.setColorFilter(c);
    if (playPauseButton != null) playPauseButton.setColorFilter(c);
    if (nextButton != null) nextButton.setColorFilter(c);
    if (closeButton != null) closeButton.setColorFilter(c);
    // 刷新歌词以应用新颜色
    setLyric(currentLyric, currentExtendedLyrics);
  }

  /** 切换播放/暂停按钮图标（由 JS 根据播放状态推送） */
  public void setPlaying(boolean playing) {
    isPlaying = playing;
    if (playPauseButton != null) {
      playPauseButton.setImageResource(playing ? R.drawable.ic_lyric_pause : R.drawable.ic_lyric_play);
      playPauseButton.setColorFilter(parseColor(playedColor));
    }
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
    windowManager.updateViewLayout(rootView, layoutParams);
  }

  public void setAlpha(float alpha) {
    this.alpha = alpha;
    if (textView == null) return;
    textView.setAlpha(alpha);
  }

  public void setBackgroundOpacity(float alpha) {
    this.backgroundAlpha = alpha;
    if (rootView == null || isLock) return;
    rootView.setBackground(buildLyricBackground());
  }

  public void setVisible(boolean visible) {
    if (rootView == null) return;
    if (!visible) removeCloseConfirm();
    rootView.setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  public void setSingleLine(boolean isSingleLine) {
    this.isSingleLine = isSingleLine;
    if (textView == null || rootView == null) return;
    // 仅重建内部 textView，保留 rootView/控制栏/关闭按钮
    contentLayout.removeView(textView);
    createTextView();
    textView.setWidth(layoutParams.width);
    contentLayout.addView(textView, 0);
    setLayoutParamsHeight();

    if (isLock) lockView();
    else unlockView();

    setLyric(currentLyric, currentExtendedLyrics);
    windowManager.updateViewLayout(rootView, layoutParams);
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
    windowManager.updateViewLayout(rootView, layoutParams);
    // 刷新歌词以更新前后行数
    setLyric(currentLyric, currentExtendedLyrics);
  }

  public void destroyView() {
    removeCloseConfirm();
    if (rootView == null || windowManager == null) return;
    try { windowManager.removeView(rootView); } catch (Exception ignored) {}
    rootView = null;
    textView = null;
    contentLayout = null;
    controlBar = null;
    closeButton = null;
    prevButton = null;
    playPauseButton = null;
    nextButton = null;
    removeOrientationEvent();
  }

  public void destroy() {
    destroyView();
    windowManager = null;
    layoutParams = null;
  }
}
