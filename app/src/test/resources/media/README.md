`sample.ts` is a synthetic one-second black frame with a generated sine tone, used
to verify offline HLS demuxing and track preparation without a live CDN or device codec.
Generated with:

```sh
ffmpeg -f lavfi -i color=c=black:s=64x64:r=10 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 1 -c:v libx264 -pix_fmt yuv420p -c:a aac -f mpegts sample.ts
```
