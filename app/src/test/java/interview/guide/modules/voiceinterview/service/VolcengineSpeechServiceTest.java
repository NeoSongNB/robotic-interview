package interview.guide.modules.voiceinterview.service;

import com.sun.net.httpserver.HttpServer;
import interview.guide.modules.usercredential.service.UserCredentialService.ProviderSecret;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VolcengineSpeechServiceTest {
  @Test
  @DisplayName("火山语音转写通过 WebSocket 接收 PCM，交付独立的识别结果")
  void receivesFinalTranscriptFromWebSocket() throws Exception {
    var service = new VolcengineSpeechService();
    try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
      var upstream = executor.submit(() -> {
        try (var socket = server.accept()) {
          socket.setSoTimeout(5000);
          var in = new DataInputStream(socket.getInputStream());
          var out = socket.getOutputStream();
          String line;
          String key = null;
          while (!(line = readLine(in)).isEmpty()) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
              key = line.substring(line.indexOf(':') + 1).trim();
            }
          }
          String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
              .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
          out.write(("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n"
              + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          readClientFrame(in);
          sendServerFrame(out, "{}");
          readClientFrame(in);
          sendServerFrame(out, "{\"result\":{\"utterances\":[{\"definite\":true,\"text\":\"你好\"}]}}");
        }
        return null;
      });
      var ready = new CompletableFuture<Void>();
      var transcript = new CompletableFuture<String>();
      var secret = new ProviderSecret("volcengine-asr", "ws://127.0.0.1:" + server.getLocalPort(),
          "volc.seedasr.sauc.duration", "test-key", 1L);
      try {
        service.start("asr-test", secret, transcript::complete, ignored -> {},
            () -> ready.complete(null), transcript::completeExceptionally);
        ready.get(5, TimeUnit.SECONDS);
        service.send("asr-test", new byte[3200]);
        assertThat(transcript.get(5, TimeUnit.SECONDS)).isEqualTo("你好");
        upstream.get(5, TimeUnit.SECONDS);
      } finally {
        service.stop("asr-test");
      }
    }
  }

  @Test
  @DisplayName("火山播报请求使用独立资源标识和凭证，并拼接 SSE 音频")
  void readsSseAudio() throws Exception {
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var headers = new CompletableFuture<String>();
    upstream.createContext("/", exchange -> {
      headers.complete(exchange.getRequestHeaders().getFirst("X-Api-Key") + "/"
          + exchange.getRequestHeaders().getFirst("X-Api-Resource-Id"));
      exchange.getRequestBody().readAllBytes();
      byte[] body = ("data: {\"code\":0,\"data\":\"AQI=\"}\n\n"
          + "data: {\"code\":0,\"data\":\"AwQ=\"}\n\n"
          + "data: {\"code\":20000000}\n\n").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    upstream.start();
    try {
      var secret = new ProviderSecret("volcengine-tts", "http://127.0.0.1:" + upstream.getAddress().getPort(),
          "seed-tts-2.0", "test-tts-key", 1L);
      assertThat(new VolcengineSpeechService().synthesize("你好", secret)).containsExactly(1, 2, 3, 4);
      assertThat(headers.get(5, TimeUnit.SECONDS)).isEqualTo("test-tts-key/seed-tts-2.0");
    } finally {
      upstream.stop(0);
    }
  }

  private static String readLine(DataInputStream in) throws java.io.IOException {
    var line = new StringBuilder();
    for (int b; (b = in.readUnsignedByte()) != '\n'; ) {
      if (b != '\r') {
        line.append((char) b);
      }
    }
    return line.toString();
  }

  private static void readClientFrame(DataInputStream in) throws java.io.IOException {
    in.readUnsignedByte();
    int length = in.readUnsignedByte() & 127;
    if (length == 126) {
      length = in.readUnsignedShort();
    }
    in.skipNBytes(4L + length);
  }

  private static void sendServerFrame(java.io.OutputStream out, String payload) throws java.io.IOException {
    byte[] data = VolcengineSpeechService.frame(0x90, 0x11, payload.getBytes(StandardCharsets.UTF_8)).toByteArray();
    out.write(0x82);
    out.write(data.length);
    out.write(data);
    out.flush();
  }
}
