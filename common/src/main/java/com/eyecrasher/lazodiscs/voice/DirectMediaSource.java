package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegmentUrlProvider;
import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.stream.MpegTsM3uStreamAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.stream.M3uStreamSegmentUrlProvider;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.container.playlists.M3uPlaylistContainerProbe;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.net.URI;
import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.auth.AuthState;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpCoreContext;

final class DirectMediaSource extends HttpAudioSourceManager {
    private final Consumer<String> validate;
    private final ThreadLocal<RecordingAccess> recording = new ThreadLocal<>();
    private final HttpInterfaceManager requests = HttpClientTools.createDefaultThreadLocalManager();
    private final HttpInterfaceManager hls = HttpClientTools.createDefaultThreadLocalManager();

    DirectMediaSource(Consumer<String> validate) {
        this.validate = validate;
        HttpContextFilter cleanup = new HttpContextFilter() {
            public void onContextOpen(HttpClientContext context) {}
            public void onContextClose(HttpClientContext context) {
                context.removeAttribute(HttpClientContext.COOKIE_STORE);
                context.removeAttribute(HttpClientContext.CREDS_PROVIDER);
                context.removeAttribute(HttpClientContext.AUTH_CACHE);
                context.removeAttribute(HttpClientContext.TARGET_AUTH_STATE);
                context.removeAttribute(HttpClientContext.PROXY_AUTH_STATE);
                context.removeAttribute(HttpClientContext.USER_TOKEN);
            }
            public void onRequest(HttpClientContext context, HttpUriRequest request, boolean repeated) {
                validate.accept(request.getURI().toString());
            }
            public boolean onRequestResponse(HttpClientContext context, HttpUriRequest request, HttpResponse response) { return false; }
            public boolean onRequestException(HttpClientContext context, HttpUriRequest request, Throwable error) { return false; }
        };
        requests.setHttpContextFilter(cleanup);
        hls.setHttpContextFilter(cleanup);
        // Probes return redirects to the source resolver; HLS must follow its
        // permitted playlist/segment redirects while applying the same policy.
        requests.configureBuilder(builder -> builder.setRedirectStrategy(new HttpClientTools.NoRedirectsStrategy()));
        configureBuilder(builder -> builder.addInterceptorFirst((HttpResponseInterceptor) (response, context) -> {
            int status = response.getStatusLine().getStatusCode();
            var location = response.getFirstHeader("Location");
            if (status < 300 || status >= 400 || location == null) return;
            var request = (HttpRequestWrapper) HttpCoreContext.adapt(context).getRequest();
            URI base = ((HttpUriRequest) request.getOriginal()).getURI();
            String target = resolve(base, location.getValue()).toString();
            validate.accept(target);
            response.setHeader("Location", target);
        }));
    }

    @Override public HttpInterface getHttpInterface() {
        RecordingAccess access = recording.get();
        return http(requests, access == null ? new RecordingAccess() : access);
    }

    private static HttpInterface http(HttpInterfaceManager manager, RecordingAccess access) {
        HttpInterface connection = manager.getInterface();
        HttpClientContext context = connection.getContext();
        context.setCookieStore(access.cookies);
        context.setCredentialsProvider(access.credentials);
        context.setAuthCache(new BasicAuthCache());
        context.setAttribute(HttpClientContext.TARGET_AUTH_STATE, new AuthState());
        context.setAttribute(HttpClientContext.PROXY_AUTH_STATE, new AuthState());
        return connection;
    }

    private static URI resolve(URI base, String reference) {
        if (reference.startsWith("?")) return URI.create(base.toString().split("[?#]", 2)[0] + reference);
        if (reference.isEmpty()) return URI.create(base.toString().split("#", 2)[0]);
        return base.resolve(reference);
    }

    private static final class RecordingAccess {
        final CookieStore cookies = new BasicCookieStore();
        final CredentialsProvider credentials = new BasicCredentialsProvider();
    }

    @Override public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        requests.configureRequests(configurator);
        hls.configureRequests(configurator);
    }

    @Override public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        requests.configureBuilder(configurator);
        hls.configureBuilder(configurator);
    }

    @Override public void shutdown() {
        ExceptionTools.closeWithWarnings(requests);
        ExceptionTools.closeWithWarnings(hls);
    }

    @Override public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        AudioReference http = getAsHttpReference(reference);
        if (http == null) return null;
        RecordingAccess previous = recording.get();
        recording.set(new RecordingAccess());
        try {
            // Re-enter source selection without creating a new recording for direct HTTP hops.
            for (int redirects = 0; redirects < 10; redirects++) {
                validate(http);
                AudioItem result = redirects == 0 ? super.loadItem(manager, http) : loadRedirect(manager, http);
                if (!(result instanceof AudioReference) || ((AudioReference) result).identifier == null) return result;
                http = getAsHttpReference((AudioReference) result);
                if (http == null) throw new FriendlyException(
                        LazoDiscsText.unsupportedScheme(), FriendlyException.Severity.COMMON, null);
            }
            throw new FriendlyException("Too many media redirects", FriendlyException.Severity.COMMON, null);
        } finally {
            if (previous == null) recording.remove(); else recording.set(previous);
        }
    }

    private AudioItem loadRedirect(AudioPlayerManager manager, AudioReference reference) {
        for (var source : manager.getSourceManagers()) {
            AudioItem result = source == this ? super.loadItem(manager, reference) : source.loadItem(manager, reference);
            if (result != null) return result;
        }
        return null;
    }

    private void validate(AudioReference reference) {
        try {
            validate.accept(reference.identifier);
        } catch (IllegalArgumentException denied) {
            throw new FriendlyException(denied.getMessage(), FriendlyException.Severity.COMMON, denied);
        }
    }

    @Override protected AudioTrack createTrack(AudioTrackInfo info, MediaContainerDescriptor container) {
        RecordingAccess access = recording.get();
        return track(info, container, access == null ? new RecordingAccess() : access);
    }

    @Override public AudioTrack decodeTrack(AudioTrackInfo info, DataInput input) throws IOException {
        MediaContainerDescriptor container = decodeTrackFactory(input);
        return container == null ? null : track(info, container, new RecordingAccess());
    }

    private AudioTrack track(AudioTrackInfo info, MediaContainerDescriptor container, RecordingAccess access) {
        MediaContainerDescriptor selected = container;
        if (container.probe instanceof M3uPlaylistContainerProbe) {
            selected = new MediaContainerDescriptor(container.probe, container.parameters) {
                @Override public AudioTrack createTrack(AudioTrackInfo track, SeekableInputStream input) {
                    var segments = new RecordingHlsSegments(track.identifier, "hls-inner".equals(parameters));
                    return new MpegTsM3uStreamAudioTrack(track) {
                        @Override protected M3uStreamSegmentUrlProvider getSegmentUrlProvider() { return segments; }
                        @Override protected HttpInterface getHttpInterface() { return http(hls, access); }
                    };
                }
            };
        }
        return new HttpAudioTrack(info, selected, this) {
            @Override public void process(LocalAudioTrackExecutor executor) throws Exception {
                RecordingAccess previous = recording.get();
                recording.set(access);
                try { super.process(executor); }
                finally { if (previous == null) recording.remove(); else recording.set(previous); }
            }
            @Override protected AudioTrack makeShallowClone() { return track(info, container, access); }
        };
    }

    /** Resolve children against the response that actually contains them, after redirects. */
    private static final class RecordingHlsSegments extends HlsStreamSegmentUrlProvider {
        private final String master;
        private String media;

        RecordingHlsSegments(String url, boolean inner) {
            super(inner ? null : url, inner ? url : null);
            master = url;
            media = inner ? url : null;
        }

        @Override protected String fetchSegmentPlaylistUrl(HttpInterface connection) throws IOException {
            if (media != null) return media;
            String[] lines = HttpClientTools.fetchResponseLines(connection, new HttpGet(master), "HLS stream list");
            URI effective = connection.getFinalLocation();
            if (isSegmentPlaylist(lines)) return media = effective.toString();
            boolean variant = false;
            for (String text : lines) {
                ExtendedM3uParser.Line line = ExtendedM3uParser.parseLine(text);
                if (line.isDirective()) variant = "EXT-X-STREAM-INF".equals(line.directiveName);
                else if (variant && line.isData()) return media = resolve(effective, line.lineData).toString();
            }
            throw new IllegalStateException("No streams listed in HLS stream list.");
        }

        @Override protected List<SegmentInfo> loadStreamSegmentsList(HttpInterface connection, String url) throws IOException {
            List<SegmentInfo> segments = super.loadStreamSegmentsList(connection, url);
            URI effective = connection.getFinalLocation();
            List<SegmentInfo> resolved = new ArrayList<>(segments.size());
            for (SegmentInfo segment : segments) {
                resolved.add(new SegmentInfo(resolve(effective, segment.url).toString(), segment.duration, segment.name));
            }
            return resolved;
        }
    }
}
