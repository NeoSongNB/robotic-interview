package interview.guide.modules.voiceinterview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.usercredential.service.UserCredentialService.ProviderSecret;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.springframework.stereotype.Service;

@Service
public class VolcengineSpeechService {
  private static final int MAX_AUDIO = 24_000 * 2 * 120;
  private static final int MAX_FRAME = 1024 * 1024;
  private final ObjectMapper json = new ObjectMapper();
  private final OkHttpClient client = new OkHttpClient.Builder()
      .connectTimeout(Duration.ofSeconds(8)).readTimeout(Duration.ofSeconds(25))
      .callTimeout(Duration.ofSeconds(35)).build();
  private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();

  public void start(String id, ProviderSecret secret, Consumer<String> onFinal, Consumer<String> onPartial,
                    Runnable onReady, Consumer<Throwable> onError) {
    stop(id);
    var state = new AsrSession();
    sessions.put(id, state);
    Request request = request(secret).build();
    state.socket = client.newWebSocket(request, new WebSocketListener() {
      @Override
      public void onOpen(WebSocket socket, Response response) {
        try {
          byte[] payload = json.writeValueAsBytes(Map.of("user", Map.of("uid", UUID.randomUUID().toString()),
              "audio", Map.of("format", "pcm", "codec", "raw", "rate", 16000, "bits", 16, "channel", 1),
              "request", Map.of("model_name", "bigmodel", "show_utterances", true, "result_type", "full")));
          if (!socket.send(frame(0x10, 0x11, payload))) {
            throw new IOException("无法初始化火山引擎语音转写");
          }
        } catch (IOException e) {
          fail(socket, e);
        }
      }

      @Override
      public void onMessage(WebSocket socket, ByteString bytes) {
        if (sessions.get(id) != state) {
          return;
        }
        try {
          var result = json.readTree(decode(bytes)).path("result");
          if (!state.ready) {
            state.ready = true;
            onReady.run();
          }
          boolean finalized = false;
          for (var utterance : result.path("utterances")) {
            if (utterance.path("definite").asBoolean()) {
              String text = utterance.path("text").asText();
              String key = utterance.path("start_time").asText() + ":" + utterance.path("end_time").asText() + ":" + text;
              if (!text.isBlank() && state.finalized.add(key)) {
                onFinal.accept(text);
                finalized = true;
              }
            } else if (!utterance.path("text").asText().isBlank()) {
              onPartial.accept(utterance.path("text").asText());
            }
          }
          if (!finalized && result.path("utterances").isMissingNode() && !result.path("text").asText().isBlank()) {
            onPartial.accept(result.path("text").asText());
          }
        } catch (IOException | RuntimeException e) {
          fail(socket, e);
        }
      }

      @Override
      public void onFailure(WebSocket socket, Throwable error, Response response) {
        fail(socket, new IOException("火山引擎语音转写连接失败" + (response == null ? "" : "（HTTP " + response.code() + "）")));
      }

      @Override
      public void onClosed(WebSocket socket, int code, String reason) {
        sessions.remove(id, state);
      }

      private void fail(WebSocket socket, Throwable error) {
        if (sessions.remove(id, state)) {
          socket.cancel();
          onError.accept(error);
        }
      }
    });
  }

  public boolean isReady(String id) {
    var state = sessions.get(id);
    return state != null && state.ready;
  }

  public void send(String id, byte[] audio) {
    var state = sessions.get(id);
    if (state == null) {
      throw new IllegalStateException("No active session");
    }
    if (!state.ready || state.socket == null) {
      throw new IllegalStateException("ASR session not ready");
    }
    try {
      if (!state.socket.send(frame(0x20, 0x01, audio))) {
        throw new IllegalStateException("ASR append failed");
      }
    } catch (IOException e) {
      throw new IllegalStateException("ASR append failed", e);
    }
  }

  public void stop(String id) {
    var state = sessions.remove(id);
    if (state != null && state.socket != null) {
      state.socket.cancel();
    }
  }

  public byte[] synthesize(String text, ProviderSecret secret) throws IOException {
    var payload = Map.of("user", Map.of("uid", UUID.randomUUID().toString()),
        "req_params", Map.of("text", text, "speaker", "zh_female_vv_uranus_bigtts",
            "audio_params", Map.of("format", "pcm", "sample_rate", 24000)));
    var request = request(secret).header("Accept", "text/event-stream")
        .post(RequestBody.create(json.writeValueAsBytes(payload), MediaType.get("application/json"))).build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        throw new IOException("火山引擎语音播报请求失败（HTTP " + response.code() + "）");
      }
      var source = response.body().source();
      var audio = new ByteArrayOutputStream();
      while (!source.exhausted()) {
        String line = source.readUtf8LineStrict(MAX_FRAME);
        if (!line.startsWith("data:")) {
          continue;
        }
        var event = json.readTree(line.substring(5).trim());
        int code = event.path("code").asInt();
        if (code == 20000000) {
          return audio.toByteArray();
        }
        if (code != 0) {
          throw new IOException("火山引擎语音播报失败（服务代码 " + code + "）");
        }
        if (event.path("data").isTextual()) {
          audio.write(Base64.getDecoder().decode(event.path("data").asText()));
          if (audio.size() > MAX_AUDIO) {
            throw new IOException("语音播报响应过长");
          }
        }
      }
      throw new IOException("语音播报响应提前结束");
    }
  }

  private Request.Builder request(ProviderSecret secret) {
    return new Request.Builder().url(secret.baseUrl()).header("X-Api-Key", secret.apiKey())
        .header("X-Api-Resource-Id", secret.modelName()).header("X-Api-Request-Id", UUID.randomUUID().toString());
  }

  static ByteString frame(int type, int format, byte[] payload) throws IOException {
    var compressed = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(compressed)) {
      gzip.write(payload);
    }
    return ByteString.of(ByteBuffer.allocate(8 + compressed.size())
        .put((byte) 0x11).put((byte) type).put((byte) format).put((byte) 0)
        .putInt(compressed.size()).put(compressed.toByteArray()).array());
  }

  static byte[] decode(ByteString message) throws IOException {
    if (message.size() < 8 || message.size() > MAX_FRAME) {
      throw new IOException("语音转写响应长度无效");
    }
    ByteBuffer in = message.asByteBuffer();
    int header = (in.get() & 15) * 4;
    int kind = in.get() & 255;
    int format = in.get() & 15;
    if (header < 4 || header > in.limit() - 4) {
      throw new IOException("语音转写响应头无效");
    }
    in.position(header);
    if ((kind >>> 4) == 15) {
      throw new IOException("火山引擎语音转写失败（服务代码 " + in.getInt() + "）");
    }
    if ((kind & 1) != 0) {
      in.getInt();
    }
    int size = in.getInt();
    if (size < 0 || size != in.remaining()) {
      throw new IOException("语音转写响应数据无效");
    }
    byte[] payload = new byte[size];
    in.get(payload);
    if (format != 1) {
      return payload;
    }
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
      byte[] decoded = gzip.readNBytes(MAX_FRAME + 1);
      if (decoded.length > MAX_FRAME) {
        throw new IOException("语音转写响应过长");
      }
      return decoded;
    }
  }

  private static final class AsrSession {
    private final Set<String> finalized = new HashSet<>();
    private volatile WebSocket socket;
    private volatile boolean ready;
  }
}
