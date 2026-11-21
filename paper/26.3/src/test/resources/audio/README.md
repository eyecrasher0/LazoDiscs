`hls-control.ts` is a generated 440 Hz sine wave, not a recording. It exercises
actual HLS MPEG-TS/AAC decoding and redirects in `DirectMediaSourceTest`.

Regenerate with FFmpeg:

```sh
ffmpeg -nostdin -hide_banner -loglevel error -f lavfi \
  -i 'sine=frequency=440:sample_rate=48000:duration=0.4' \
  -c:a aac -b:a 64000 -ac 1 -f mpegts -y hls-control.ts
```
