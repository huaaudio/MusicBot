package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Audio source manager that implements finding Bilibili tracks based on URL.
 */
public class BilibiliAudioSourceManager implements AudioSourceManager, HttpConfigurable {
    private static final Logger log = LoggerFactory.getLogger(BilibiliAudioSourceManager.class);

    private final String cookieSESSDATA;
    private final String cookieBiliJct;
    private final String cookieDedeUserID;

    // Bilibili视频URL正则表达式，支持多种格式
    private static final String TRACK_URL_REGEX = "^(?:http://|https://|)(?:www\\.|m\\.|)bilibili\\.com/video/(?:av(\\d+)|BV([A-Za-z0-9]+))(?:\\?.*|)(?:/\\?.*|)$";
    private static final Pattern trackUrlPattern = Pattern.compile(TRACK_URL_REGEX);

    // 从HTML中提取playinfo的正则表达式
    private static final Pattern PLAYINFO_PATTERN = Pattern.compile("window\\.__playinfo__\\s*=\\s*(\\{.*?\\});");

    // 从playinfo JSON中提取音频URL的正则表达式（备用方案）
    private static final Pattern AUDIO_URL_PATTERN = Pattern.compile("\"audio\":\\[\\{[^]]*?\"baseUrl\":\"(https?://[^\"]+\\.m4s[^\"]*?)\"");

    private final HttpInterfaceManager httpInterfaceManager;
    private final ObjectMapper objectMapper;

    public BilibiliAudioSourceManager() {
        this(null, null, null);
    }

    public BilibiliAudioSourceManager(String cookieSESSDATA, String cookieBiliJct, String cookieDedeUserID) {
        this.cookieSESSDATA = cookieSESSDATA;
        this.cookieBiliJct = cookieBiliJct;
        this.cookieDedeUserID = cookieDedeUserID;
        httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
        objectMapper = new ObjectMapper();

        // 配置HTTP客户端以避免被反爬虫机制拦截
        httpInterfaceManager.configureBuilder(builder -> {
            builder.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        });

        // 配置默认请求头部
        httpInterfaceManager.configureRequests(config -> {
            return RequestConfig.copy(config)
                    .build();
        });
    }

    @Override
    public String getSourceName() {
        return "bilibili";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        Matcher trackMatcher = trackUrlPattern.matcher(reference.identifier);

        if (trackMatcher.matches()) {
            String videoId = extractVideoId(trackMatcher);
            if (videoId != null) {
                return loadTrack(videoId, reference.identifier);
            }
        }

        return null;
    }

    private String extractVideoId(Matcher matcher) {
        // AV号格式
        if (matcher.group(1) != null) {
            return "av" + matcher.group(1);
        }
        // BV号格式
        if (matcher.group(2) != null) {
            return "BV" + matcher.group(2);
        }
        return null;
    }

    private AudioTrack loadTrack(String videoId, String originalUrl) {
        try (HttpInterface httpInterface = getHttpInterface()) {
            // 获取视频页面HTML内容
            String pageContent = getVideoPageContent(httpInterface, originalUrl);

            // 提取视频信息和播放数据
            VideoInfo videoInfo = extractVideoInfo(pageContent);
            String audioUrl = extractAudioUrl(pageContent);

            if (videoInfo != null && audioUrl != null) {
                AudioTrackInfo trackInfo = new AudioTrackInfo(
                        videoInfo.title,
                        videoInfo.uploader,
                        videoInfo.duration,
                        videoId,
                        false,
                        originalUrl
                );

                return new BilibiliAudioTrack(trackInfo, audioUrl, this);
            }

            throw new FriendlyException("Could not extract audio URL from Bilibili video", COMMON, null);

        } catch (IOException e) {
            throw new FriendlyException("Error occurred when loading Bilibili video info", SUSPICIOUS, e);
        }
    }

    private String getVideoPageContent(HttpInterface httpInterface, String url) throws IOException {
        HttpGet request = new HttpGet(url);
        request.setHeader("Referer", "https://www.bilibili.com/");
        request.setHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

        // 构建 Cookie 字符串
        StringBuilder cookie = new StringBuilder();
        boolean hasCookie = false;

        if (cookieSESSDATA != null && !cookieSESSDATA.isEmpty()) {
            cookie.append("SESSDATA=").append(cookieSESSDATA);
            hasCookie = true;
        }
        if (cookieBiliJct != null && !cookieBiliJct.isEmpty()) {
            if (cookie.length() > 0) cookie.append("; ");
            cookie.append("bili_jct=").append(cookieBiliJct);
            hasCookie = true;
        }
        if (cookieDedeUserID != null && !cookieDedeUserID.isEmpty()) {
            if (cookie.length() > 0) cookie.append("; ");
            cookie.append("DedeUserID=").append(cookieDedeUserID);
            hasCookie = true;
        }

        if (hasCookie) {
            request.setHeader("Cookie", cookie.toString());
            log.debug("Using authenticated request with cookies");
        } else {
            log.info("No cookies provided, attempting anonymous access (may have limited quality or fail for restricted videos)");
            // For anonymous access, we need to get a valid buvid3 cookie first to bypass anti-bot measures (HTTP 412).
            try (CloseableHttpResponse response = httpInterface.execute(new HttpGet("https://www.bilibili.com"))) {
                // The buvid3 cookie is set on the first visit. We don't need the response body.
                // The cookie is stored in the HttpClient's cookie store and will be used for subsequent requests.
                int statusCode = response.getStatusLine().getStatusCode();
                if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                    log.warn("Failed to fetch initial Bilibili page to get cookies, status code: {}", statusCode);
                }
            } catch (Exception e) {
                log.warn("Error when trying to get initial Bilibili cookies, continuing without them.", e);
            }
        }

        // Now, perform the request to the video page.
        request = new HttpGet(url);
        try (CloseableHttpResponse response = httpInterface.execute(request)) { // The cookies from the previous request are automatically used.
            int statusCode = response.getStatusLine().getStatusCode();
            if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                throw new IOException("Unexpected status code from Bilibili video page: " + statusCode);
            }

            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }

    private VideoInfo extractVideoInfo(String pageContent) {
        try {
            Document doc = Jsoup.parse(pageContent);

            // 尝试从页面元素中提取标题
            String title = null;
            Element titleElement = doc.selectFirst("h1.video-title");
            if (titleElement == null) {
                titleElement = doc.selectFirst("title");
            }
            if (titleElement != null) {
                title = titleElement.text();
                // 清理标题，移除"_哔哩哔哩_bilibili"等后缀
                if (title.contains("_哔哩哔哩")) {
                    title = title.substring(0, title.indexOf("_哔哩哔哩"));
                }
            }

            // 尝试从JSON数据中提取更详细的信息
            Matcher playinfoMatcher = PLAYINFO_PATTERN.matcher(pageContent);
            if (playinfoMatcher.find()) {
                try {
                    String playinfoJson = playinfoMatcher.group(1);
                    JsonNode playinfo = objectMapper.readTree(playinfoJson);

                    // 从playinfo中提取时长信息
                    long duration = 0;
                    if (playinfo.has("data") && playinfo.get("data").has("dash")) {
                        JsonNode dash = playinfo.get("data").get("dash");
                        if (dash.has("duration")) {
                            duration = dash.get("duration").asLong() * 1000; // 转换为毫秒
                        }
                    }

                    return new VideoInfo(
                            title != null ? title : "Unknown Title",
                            "Unknown Uploader", // Bilibili上传者信息需要额外API调用
                            duration
                    );
                } catch (Exception e) {
                    log.debug("Failed to parse playinfo JSON", e);
                }
            }

            // 如果无法从JSON中提取，使用基本信息
            return new VideoInfo(
                    title != null ? title : "Unknown Title",
                    "Unknown Uploader",
                    0
            );

        } catch (Exception e) {
            log.debug("Failed to extract video info", e);
            return null;
        }
    }

    private String extractAudioUrl(String pageContent) {
        try {
            // 方法1：尝试从window.__playinfo__中提取
            Matcher playinfoMatcher = PLAYINFO_PATTERN.matcher(pageContent);
            if (playinfoMatcher.find()) {
                String playinfoJson = playinfoMatcher.group(1);
                JsonNode playinfo = objectMapper.readTree(playinfoJson);

                if (playinfo.has("data") && playinfo.get("data").has("dash")) {
                    JsonNode dash = playinfo.get("data").get("dash");
                    if (dash.has("audio") && dash.get("audio").isArray() && !dash.get("audio").isEmpty()) {
                        JsonNode firstAudio = dash.get("audio").get(0);
                        if (firstAudio.has("baseUrl")) {
                            String audioUrl = firstAudio.get("baseUrl").asText();
                            return audioUrl.replace("&amp;", "&");
                        }
                    }
                }
            }

            // 方法2：备用正则表达式方法
            Matcher audioMatcher = AUDIO_URL_PATTERN.matcher(pageContent);
            if (audioMatcher.find()) {
                String audioUrl = audioMatcher.group(1);
                return audioUrl.replace("&amp;", "&");
            }

            return null;

        } catch (Exception e) {
            log.debug("Failed to extract audio URL", e);
            return null;
        }
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        // 需要保存音频URL信息
        BilibiliAudioTrack bilibiliTrack = (BilibiliAudioTrack) track;
        output.writeUTF(bilibiliTrack.getAudioUrl());
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        String audioUrl = input.readUTF();
        return new BilibiliAudioTrack(trackInfo, audioUrl, this);
    }

    @Override
    public void shutdown() {
        // 关闭HTTP接口管理器
        if (httpInterfaceManager != null) {
            try {
                httpInterfaceManager.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @return Get an HTTP interface for a playing track.
     */
    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        httpInterfaceManager.configureRequests(configurator);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        httpInterfaceManager.configureBuilder(configurator);
    }

    /**
     * 视频信息内部类
     */
    private static class VideoInfo {
        final String title;
        final String uploader;
        final long duration;

        VideoInfo(String title, String uploader, long duration) {
            this.title = title;
            this.uploader = uploader;
            this.duration = duration;
        }
    }
}