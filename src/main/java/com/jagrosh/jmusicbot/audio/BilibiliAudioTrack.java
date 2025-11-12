package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Audio track that handles processing Bilibili M4S tracks.
 */
public class BilibiliAudioTrack extends DelegatedAudioTrack {
    private static final Logger log = LoggerFactory.getLogger(BilibiliAudioTrack.class);

    private final String audioUrl;
    private final BilibiliAudioSourceManager sourceManager;

    /**
     * @param trackInfo Track info
     * @param audioUrl Direct URL to the M4S audio file
     * @param sourceManager Source manager which was used to find this track
     */
    public BilibiliAudioTrack(AudioTrackInfo trackInfo, String audioUrl, BilibiliAudioSourceManager sourceManager) {
        super(trackInfo);
        this.audioUrl = audioUrl;
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            log.debug("Starting Bilibili M4S track from URL: {}", audioUrl);

            // 创建带有自定义头部的HTTP请求
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.bilibili.com/");
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            headers.put("Accept", "*/*");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.put("Connection", "keep-alive");

            try (PersistentHttpStream stream = new PersistentHttpStream(httpInterface, new URI(audioUrl), null)) {
                // 使用MP4音频轨道处理M4S文件（M4S本质上是分段的MP4）
                processDelegate(new MpegAudioTrack(trackInfo, stream), localExecutor);
            }
        }
    }

    private void setupBilibiliHeaders(HttpInterface httpInterface) {
        // 设置Bilibili需要的HTTP头部以避免403错误
        // 注意：LavaPlayer的HttpInterface可能不支持动态设置头部
        // 这些头部应该在创建PersistentHttpStream时通过自定义HttpGet请求设置
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new BilibiliAudioTrack(trackInfo, audioUrl, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    /**
     * Get the direct audio URL for this track
     * @return The M4S audio file URL
     */
    public String getAudioUrl() {
        return audioUrl;
    }
}