package ai.pitin.vehicle;

/**
 * Role tokens for the car app (spec 5 · pitin.css). Graphite is the default and takes its values
 * from the protected GraphitePalette so the phone, the car shell and the rest screen stay one colour.
 */
final class CarTheme {
    static final String GRAPHITE_ID = "graphite", PEARL_ID = "pearl", UMBER_ID = "umber";

    final String id, label;
    final boolean dark;
    final int bg, bg2, surface, surface2, ink, ink2, ink3, line, line2, primary, onPrimary;
    final int accent, accentSoft, glassA, glassB, warn, path;
    /** Radial ground (centre, middle, edge) and centre point as fractions, as on the artboards. */
    final int[] ground;
    final float groundX, groundY;
    /** Colour the wave paints while it covers the old mood, and its crest glint. */
    final int waveFill, waveGlint;

    private CarTheme(String id, String label, boolean dark, int bg, int bg2, int surface, int surface2,
                     int ink, int ink2, int ink3, int line, int line2, int primary, int onPrimary,
                     int accent, int accentSoft, int glassA, int glassB, int warn, int path,
                     int[] ground, float groundX, float groundY, int waveFill, int waveGlint) {
        this.id = id; this.label = label; this.dark = dark;
        this.bg = bg; this.bg2 = bg2; this.surface = surface; this.surface2 = surface2;
        this.ink = ink; this.ink2 = ink2; this.ink3 = ink3; this.line = line; this.line2 = line2;
        this.primary = primary; this.onPrimary = onPrimary; this.accent = accent; this.accentSoft = accentSoft;
        this.glassA = glassA; this.glassB = glassB; this.warn = warn; this.path = path;
        this.ground = ground; this.groundX = groundX; this.groundY = groundY;
        this.waveFill = waveFill; this.waveGlint = waveGlint;
    }

    static final CarTheme GRAPHITE = new CarTheme(GRAPHITE_ID, "Graphite", true,
            GraphitePalette.BACKGROUND, 0xff0b0b0c, GraphitePalette.SURFACE, 0xff272a2f,
            GraphitePalette.TEXT, GraphitePalette.MUTED, 0xff96979a, 0xff2c2e31, GraphitePalette.RULE,
            0xfff4efe7, 0xff171615, 0xffa7ba93, 0x29a7ba93, 0xff2e2e33, 0xff19191c, 0xffe2a07a,
            GraphitePalette.PATH, new int[]{0xff18181a, 0xff131314, 0xff0e0e0f}, .52f, .40f,
            0xff131314, 0x38ffffff);

    static final CarTheme PEARL = new CarTheme(PEARL_ID, "Pearl", false,
            0xfff6f4f0, 0xffeeebe6, 0xffffffff, 0xfffaf8f5,
            0xff141413, 0xff57534d, 0xff6e6a63, 0xffe6e2db, 0xffd5d0c8,
            0xff161616, 0xfffaf8f4, 0xff59694e, 0x2459694e, 0xffffffff, 0xffece9e4, 0xff8a4b2a,
            0xff141413, new int[]{0xfffbfaf7, 0xfff6f4f0, 0xffefece6}, .52f, .40f,
            0xfff6f4f0, 0xd9ffffff);

    static final CarTheme UMBER = new CarTheme(UMBER_ID, "Umber", true,
            0xff1c1611, 0xff15100c, 0xff251d17, 0xff2d241d,
            0xfff4efe8, 0xffcdbca8, 0xffa8957f, 0xff3a3129, 0xff4b4036,
            0xfff7f1ea, 0xff1c1712, 0xffe2c39e, 0x29e2c39e, 0xff3a2e25, 0xff221a14, 0xfff0b089,
            0xffe2c39e, new int[]{0xff241b15, 0xff1e1712, 0xff18120e}, .62f, .45f,
            0xff1e1712, 0x8ce2c39e);

    static CarTheme of(String id) {
        if (PEARL_ID.equals(id)) return PEARL;
        if (UMBER_ID.equals(id)) return UMBER;
        return GRAPHITE;
    }

    static final CarTheme[] ALL = {PEARL, GRAPHITE, UMBER};

    /** Same colour with a new alpha (0..255). */
    static int alpha(int color, int alpha) { return (color & 0x00ffffff) | (Math.max(0, Math.min(255, alpha)) << 24); }

    static int lerp(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff, aa = (a >>> 24);
        int br = (b >> 16) & 0xff, bgc = (b >> 8) & 0xff, bb = b & 0xff, ba = (b >>> 24);
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bgc - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
