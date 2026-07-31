package cn.toside.music.mobile.lyric;

import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

public class LyricEvent {
  final String SET_VIEW_POSITION = "set-position";
  final String SET_VIEW_WIDTH = "set-width";
  final String SET_VIEW_MAX_LINE_NUM = "set-max-line-num";
  final String CONTROL = "control";     // 播放控制按钮点击 { action: "prev" | "playPause" | "next" }
  final String CLOSE = "close";         // 关闭桌面歌词（已二次确认）
  final String LYRIC_Line_PLAY = "lyric-line-play";

  private final ReactApplicationContext reactContext;
  LyricEvent(ReactApplicationContext reactContext) { this.reactContext = reactContext; }

  public void sendEvent(String eventName, @Nullable WritableMap params) {
    // Log.d("Lyric", "senEvent: " + eventName);
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
      .emit(eventName, params);
  }
}
