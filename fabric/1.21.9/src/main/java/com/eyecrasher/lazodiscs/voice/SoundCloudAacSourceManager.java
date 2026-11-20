package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataReader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudFormatHandler;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudPlaylistLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudHelper;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpStreamTools;
import com.sedmelluq.discord.lavaplayer.tools.io.ChainedInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import org.apache.http.client.methods.HttpGet;

import java.io.DataInput;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Supports SoundCloud's AAC/fMP4 HLS streams while retaining the older audio formats. */
public final class SoundCloudAacSourceManager extends SoundCloudAudioSourceManager {
    private static final String AAC_PREFIX = "A:";
    private final HttpInterfaceManager playbackInterfaces =
            HttpClientTools.createDefaultThreadLocalManager();

    public SoundCloudAacSourceManager() {
        this(
                new DefaultSoundCloudDataLoader(),
                new DefaultSoundCloudDataReader(),
                new AacFormats());
    }

    private SoundCloudAacSourceManager(
            DefaultSoundCloudDataLoader loader,
            DefaultSoundCloudDataReader reader,
            AacFormats formats) {
        super(
                true,
                reader,
                loader,
                formats,
                new DefaultSoundCloudPlaylistLoader(loader, reader, formats),
                false);
    }

    @Override
    protected AudioTrack loadFromTrackData(JsonBrowser data) {
        return adapt(super.loadFromTrackData(data));
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        AudioItem item = super.loadItem(manager, reference);
        if (item instanceof AudioTrack track) return adapt(track);
        if (item instanceof AudioPlaylist playlist) {
            List<AudioTrack> tracks = new ArrayList<>();
            AudioTrack selected = playlist.getSelectedTrack();
            AudioTrack adaptedSelected = selected == null ? null : adapt(selected);
            for (AudioTrack track : playlist.getTracks()) {
                tracks.add(track == selected ? adaptedSelected : adapt(track));
            }
            return new BasicAudioPlaylist(
                    playlist.getName(), tracks, adaptedSelected, playlist.isSearchResult());
        }
        return item;
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo info, DataInput input) {
        return info.identifier.startsWith(AAC_PREFIX)
                ? new AacTrack(info, this)
                : super.decodeTrack(info, input);
    }

    private AudioTrack adapt(AudioTrack track) {
        return track.getIdentifier().startsWith(AAC_PREFIX) && !(track instanceof AacTrack)
                ? new AacTrack(track.getInfo(), this)
                : track;
    }

    @Override
    public void shutdown() {
        ExceptionTools.closeWithWarnings(playbackInterfaces);
        super.shutdown();
    }

    private static final class AacFormats extends DefaultSoundCloudFormatHandler {
        private static boolean isAac(SoundCloudTrackFormat format) {
            return "hls".equals(format.getProtocol())
                    && format.getMimeType().toLowerCase(Locale.ROOT).startsWith("audio/mp4");
        }

        @Override
        public SoundCloudTrackFormat chooseBestFormat(List<SoundCloudTrackFormat> formats) {
            for (SoundCloudTrackFormat format : formats) if (isAac(format)) return format;
            return super.chooseBestFormat(formats);
        }

        @Override
        public String buildFormatIdentifier(SoundCloudTrackFormat format) {
            return isAac(format)
                    ? AAC_PREFIX + format.getLookupUrl()
                    : super.buildFormatIdentifier(format);
        }
    }

    private static final class AacTrack extends DelegatedAudioTrack {
        private final SoundCloudAacSourceManager manager;

        private AacTrack(AudioTrackInfo info, SoundCloudAacSourceManager manager) {
            super(info);
            this.manager = manager;
        }

        @Override
        public long getDuration() {
            // A fragmented MP4 delegate can report only the first HLS segment's duration.
            return trackInfo.length;
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) throws Exception {
            String playback;
            try (HttpInterface api = manager.getHttpInterface()) {
                playback =
                        SoundCloudHelper.loadPlaybackUrl(
                                api, trackInfo.identifier.substring(AAC_PREFIX.length()));
            }
            // Signed CDN URLs must not pass through SoundCloud's client_id request filter.
            try (HttpInterface http = manager.playbackInterfaces.getInterface()) {
                List<URI> segments = readSegments(http, URI.create(playback));
                int[] index = {0};
                try (NonSeekableInputStream stream =
                        new NonSeekableInputStream(
                                new ChainedInputStream(
                                        () ->
                                                index[0] < segments.size()
                                                        ? HttpStreamTools.streamContent(
                                                                http,
                                                                new HttpGet(
                                                                        segments.get(index[0]++)))
                                                        : null))) {
                    processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
                }
            }
        }

        private static List<URI> readSegments(HttpInterface http, URI playlist) throws IOException {
            String[] lines =
                    HttpClientTools.fetchResponseLines(
                            http, new HttpGet(playlist), "SoundCloud AAC playlist");
            List<URI> segments = new ArrayList<>();
            boolean initialized = false;
            for (String text : lines) {
                ExtendedM3uParser.Line line = ExtendedM3uParser.parseLine(text);
                if ("EXT-X-MAP".equals(line.directiveName)) {
                    String init = line.directiveArguments.get("URI");
                    if (init == null
                            || initialized
                            || line.directiveArguments.containsKey("BYTERANGE")) {
                        throw new IOException("Unsupported SoundCloud AAC initialization map");
                    }
                    segments.add(playlist.resolve(init));
                    initialized = true;
                } else if ("EXT-X-KEY".equals(line.directiveName)
                        || "EXT-X-BYTERANGE".equals(line.directiveName)
                        || "EXT-X-STREAM-INF".equals(line.directiveName)) {
                    throw new IOException(
                            "Unsupported SoundCloud AAC playlist directive: " + line.directiveName);
                } else if (line.isData()) {
                    if (!initialized) {
                        throw new IOException("SoundCloud AAC segment precedes initialization map");
                    }
                    segments.add(playlist.resolve(line.lineData));
                }
            }
            if (!initialized || segments.size() < 2) {
                throw new IOException(
                        "SoundCloud AAC playlist has no initialization/audio segments");
            }
            return segments;
        }

        @Override
        protected AudioTrack makeShallowClone() {
            return new AacTrack(trackInfo, manager);
        }

        @Override
        public AudioSourceManager getSourceManager() {
            return manager;
        }
    }
}
