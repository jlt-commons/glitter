"""Render the montage's cell separators and branding strip as one RGBA overlay.

ffmpeg here has no drawtext (built without libfreetype), so text is
rasterised with PIL and composited by ffmpeg instead. Colours match the
docs site's dark palette.
"""
from PIL import Image, ImageDraw, ImageFont
W, H = 1280, 720
GRID_H = 640
BG=(0x0d,0x11,0x17); RAISED=(0x13,0x1a,0x24); BORDER=(0x25,0x30,0x3d)
TEXT=(0xc9,0xd1,0xd9); DIM=(0x8b,0x96,0xa3); ACCENT=(0xe8,0xa2,0x59)
MONO="/System/Library/Fonts/Menlo.ttc"
f=lambda s,b=False: ImageFont.truetype(MONO,s,index=1 if b else 0)

img=Image.new("RGBA",(W,H),(0,0,0,0)); d=ImageDraw.Draw(img)
# cell separators over the grid area
for x in (426, 852): d.line([(x,0),(x,GRID_H)], fill=BORDER+(255,), width=2)
d.line([(0,GRID_H//2),(W,GRID_H//2)], fill=BORDER+(255,), width=2)
# branding strip
d.rectangle([(0,GRID_H),(W,H)], fill=RAISED+(255,))
d.line([(0,GRID_H),(W,GRID_H)], fill=BORDER+(255,), width=2)
d.text((40, GRID_H+22), "(glitter)", font=f(30,True), fill=ACCENT)
d.text((215, GRID_H+28), "a Replicant-style GTK4 renderer for Jolt", font=f(21), fill=TEXT)
msg="github.com/burinc/glitter"
w=d.textbbox((0,0),msg,font=f(21))[2]
d.text((W-40-w, GRID_H+28), msg, font=f(21), fill=DIM)
import sys
img.save(sys.argv[1] if len(sys.argv) > 1 else "overlay.png")
