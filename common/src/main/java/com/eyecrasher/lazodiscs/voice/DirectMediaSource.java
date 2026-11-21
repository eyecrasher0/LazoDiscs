package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamTrack;
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
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpCoreContext;

final class DirectMediaSource extends HttpAudioSourceManager {
    private final Consumer<String> validate;
    private final HttpInterfaceManager requests = HttpClientTools.createDefaultThreadLocalManager();
    private final HttpInterfaceManager hls = HttpClientTools.createDefaultThreadLocalManager();

    DirectMediaSource(Consumer<String> validate) {
        this.validate = validate;
        // Probes return redirects to the source resolver; HLS must follow its
        // permitted playlist/segment redirects while applying the same policy.
        requests.configureBuilder(builder -> builder.setRedirectStrategy(new HttpClientTools.NoRedirectsStrategy()));
        configureBuilder(builder -> {
            builder.addInterceptorFirst((HttpRequestInterceptor) (request, context) -> {
                URI origin = URI.create(HttpCoreContext.adapt(context).getTargetHost().toURI());
                validate.accept(origin.resolve(request.getRequestLine().getUri()).toString());
            });
            builder.addInterceptorFirst((HttpResponseInterceptor) (response, context) -> {
                int status = response.getStatusLine().getStatusCode();
                if (status < 300 || status >= 400 || response.getFirstHeader("Location") == null) return;
                HttpCoreContext http = HttpCoreContext.adapt(context);
                URI origin = URI.create(http.getTargetHost().toURI()).resolve(http.getRequest().getRequestLine().getUri());
                validate.accept(origin.resolve(response.getFirstHeader("Location").getValue()).toString());
            });
        });
    }

    @Override public HttpInterface getHttpInterface() { return requests.getInterface(); }

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
        validate(http);
        AudioItem result = super.loadItem(manager, http);
        if (result instanceof AudioReference && ((AudioReference) result).identifier != null) {
            AudioReference target = getAsHttpReference((AudioReference) result);
            if (target == null) throw new FriendlyException(
                    LazoDiscsText.unsupportedScheme(), FriendlyException.Severity.COMMON, null);
            validate(target);
        }
        return result;
    }

    private void validate(AudioReference reference) {
        try {
            validate.accept(reference.identifier);
        } catch (IllegalArgumentException denied) {
            throw new FriendlyException(denied.getMessage(), FriendlyException.Severity.COMMON, denied);
        }
    }

    @Override protected AudioTrack createTrack(AudioTrackInfo info, MediaContainerDescriptor container) {
        if (!(container.probe instanceof M3uPlaylistContainerProbe)) return super.createTrack(info, container);
        MediaContainerDescriptor guarded = new MediaContainerDescriptor(container.probe, container.parameters) {
            @Override public AudioTrack createTrack(AudioTrackInfo track, SeekableInputStream input) {
                return new HlsStreamTrack(track, track.identifier, hls, "hls-inner".equals(parameters));
            }
        };
        return super.createTrack(info, guarded);
    }
}
