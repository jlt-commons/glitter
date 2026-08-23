#!/usr/bin/env bash
# Rebuild docs/demos/montage.mp4 from the six committed demo GIFs.
#
# 3x2 grid, all six demos playing at once, with a branding strip. Sized and
# encoded for social autoplay: 1280x720, H.264 High / yuv420p (even
# dimensions are required by H.264), +faststart, and a silent AAC track
# because some feeds mishandle a video with no audio stream at all.
#
# Regenerate after re-recording any GIF:  bash scripts/make_montage.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OVERLAY=$(mktemp -d)/overlay.png
python3 scripts/make_montage_overlay.py "$OVERLAY"

cell() { echo "[$1:v]fps=20,scale=410:308:force_original_aspect_ratio=decrease,pad=426:320:(ow-iw)/2:(oh-ih)/2:0x0d1117[$2];"; }

ffmpeg -y \
  -stream_loop -1 -i docs/demos/counter.gif -stream_loop -1 -i docs/demos/todo.gif \
  -stream_loop -1 -i docs/demos/crud.gif    -stream_loop -1 -i docs/demos/flights.gif \
  -stream_loop -1 -i docs/demos/temperature.gif -stream_loop -1 -i docs/demos/timer.gif \
  -loop 1 -i "$OVERLAY" \
  -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 \
  -filter_complex "$(cell 0 a)$(cell 1 b)$(cell 2 c)$(cell 3 d)$(cell 4 e)$(cell 5 f)\
[a][b][c][d][e][f]xstack=inputs=6:layout=0_0|426_0|852_0|0_320|426_320|852_320[g];\
[g]pad=1280:720:1:0:0x0d1117[bg];[bg][6:v]overlay=0:0:format=auto,format=yuv420p[v]" \
  -map "[v]" -map 7:a \
  -t 12 -c:v libx264 -preset medium -crf 21 -pix_fmt yuv420p -profile:v high \
  -c:a aac -b:a 96k -shortest -movflags +faststart \
  docs/demos/montage.mp4 -loglevel error

echo "wrote docs/demos/montage.mp4 ($(du -h docs/demos/montage.mp4 | cut -f1))"
