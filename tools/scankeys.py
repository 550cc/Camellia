import zipfile, sys

apk = sys.argv[1] if len(sys.argv) > 1 else '/sdcard/Download/ssc1365.apk'
needles = [
    b'KEY_STEP_COUNT', b'KET_STEP_COUNT', b'STEP_COUNT',
    b'KEY_ALLOW_ENABLE', b'ALLOW_ENABLE',
    b'music_bgm_status', b'checkAISubtitle Result = ',
    b'miui-music://radar',
]
with zipfile.ZipFile(apk) as z:
    names = [n for n in z.namelist() if n.endswith('.dex')]
    print("dex files:", names)
    for n in names:
        d = z.read(n)
        hits = [(nd.decode(), d.count(nd)) for nd in needles]
        hits = [h for h in hits if h[1]]
        print(n, "size=", len(d), hits if hits else "-")
