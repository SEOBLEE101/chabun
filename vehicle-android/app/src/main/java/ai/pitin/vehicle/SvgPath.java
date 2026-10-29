package ai.pitin.vehicle;

import android.graphics.Path;

/** Small SVG path-data parser (M L H V C S Q T A Z, absolute and relative) for the 1.5px line icons. */
final class SvgPath {
    private final String d;
    private int i;

    private SvgPath(String d) { this.d = d; }

    static Path parse(String data) {
        Path path = new Path();
        new SvgPath(data).into(path);
        return path;
    }

    private void into(Path p) {
        float x = 0, y = 0, sx = 0, sy = 0, cx = 0, cy = 0; // current, subpath start, last control
        char cmd = 0, prev = 0;
        while (true) {
            skip();
            if (i >= d.length()) break;
            char c = d.charAt(i);
            if (Character.isLetter(c)) { cmd = c; i++; }
            else if (cmd == 0) break;
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    float nx = num(), ny = num();
                    if (rel) { nx += x; ny += y; }
                    p.moveTo(nx, ny); x = sx = nx; y = sy = ny;
                    cmd = rel ? 'l' : 'L'; // following pairs are implicit line-tos
                    cx = x; cy = y; break;
                }
                case 'L': { float nx = num(), ny = num(); if (rel) { nx += x; ny += y; } p.lineTo(nx, ny); x = nx; y = ny; cx = x; cy = y; break; }
                case 'H': { float nx = num(); if (rel) nx += x; p.lineTo(nx, y); x = nx; cx = x; cy = y; break; }
                case 'V': { float ny = num(); if (rel) ny += y; p.lineTo(x, ny); y = ny; cx = x; cy = y; break; }
                case 'C': {
                    float x1 = num(), y1 = num(), x2 = num(), y2 = num(), nx = num(), ny = num();
                    if (rel) { x1 += x; y1 += y; x2 += x; y2 += y; nx += x; ny += y; }
                    p.cubicTo(x1, y1, x2, y2, nx, ny); cx = x2; cy = y2; x = nx; y = ny; break;
                }
                case 'S': {
                    float x2 = num(), y2 = num(), nx = num(), ny = num();
                    if (rel) { x2 += x; y2 += y; nx += x; ny += y; }
                    char pu = Character.toUpperCase(prev);
                    float x1 = (pu == 'C' || pu == 'S') ? 2 * x - cx : x, y1 = (pu == 'C' || pu == 'S') ? 2 * y - cy : y;
                    p.cubicTo(x1, y1, x2, y2, nx, ny); cx = x2; cy = y2; x = nx; y = ny; break;
                }
                case 'Q': {
                    float x1 = num(), y1 = num(), nx = num(), ny = num();
                    if (rel) { x1 += x; y1 += y; nx += x; ny += y; }
                    p.quadTo(x1, y1, nx, ny); cx = x1; cy = y1; x = nx; y = ny; break;
                }
                case 'T': {
                    float nx = num(), ny = num();
                    if (rel) { nx += x; ny += y; }
                    char pu = Character.toUpperCase(prev);
                    float x1 = (pu == 'Q' || pu == 'T') ? 2 * x - cx : x, y1 = (pu == 'Q' || pu == 'T') ? 2 * y - cy : y;
                    p.quadTo(x1, y1, nx, ny); cx = x1; cy = y1; x = nx; y = ny; break;
                }
                case 'A': {
                    float rx = num(), ry = num(), rot = num();
                    boolean large = flag(), sweep = flag();
                    float nx = num(), ny = num();
                    if (rel) { nx += x; ny += y; }
                    arc(p, x, y, nx, ny, rx, ry, rot, large, sweep);
                    x = nx; y = ny; cx = x; cy = y; break;
                }
                case 'Z': p.close(); x = sx; y = sy; cx = x; cy = y; break;
                default: return;
            }
            prev = cmd;
        }
    }

    private void skip() {
        while (i < d.length()) {
            char c = d.charAt(i);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') i++; else break;
        }
    }

    private boolean flag() {
        skip();
        char c = d.charAt(i++);
        return c == '1';
    }

    private float num() {
        skip();
        int start = i;
        if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean dot = false, exp = false;
        while (i < d.length()) {
            char c = d.charAt(i);
            if (Character.isDigit(c)) { i++; }
            else if (c == '.' && !dot && !exp) { dot = true; i++; }
            else if ((c == 'e' || c == 'E') && !exp) { exp = true; i++; if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++; }
            else break;
        }
        return Float.parseFloat(d.substring(start, i));
    }

    /** Endpoint arc → cubic Béziers (SVG implementation notes F.6). */
    private static void arc(Path p, float x0, float y0, float x1, float y1, float rx, float ry,
                            float angle, boolean large, boolean sweep) {
        if (rx == 0 || ry == 0) { p.lineTo(x1, y1); return; }
        rx = Math.abs(rx); ry = Math.abs(ry);
        double phi = Math.toRadians(angle), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x0 - x1) / 2.0, dy = (y0 - y1) / 2.0;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) { double s = Math.sqrt(lambda); rx *= s; ry *= s; }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = (large != sweep ? 1 : -1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * (rx * y1p / ry), cyp = coef * (-ry * x1p / rx);
        double cx = cos * cxp - sin * cyp + (x0 + x1) / 2.0, cy = sin * cxp + cos * cyp + (y0 + y1) / 2.0;
        double theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double dtheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && dtheta > 0) dtheta -= 2 * Math.PI;
        if (sweep && dtheta < 0) dtheta += 2 * Math.PI;
        int segments = (int) Math.ceil(Math.abs(dtheta) / (Math.PI / 2));
        double delta = dtheta / segments, t = 4.0 / 3.0 * Math.tan(delta / 4);
        double th = theta1;
        for (int s = 0; s < segments; s++) {
            double c1 = Math.cos(th), s1 = Math.sin(th), c2 = Math.cos(th + delta), s2 = Math.sin(th + delta);
            double e1x = c1 - t * s1, e1y = s1 + t * c1, e2x = c2 + t * s2, e2y = s2 - t * c2;
            p.cubicTo((float) (cx + rx * (cos * e1x) - ry * (sin * e1y)), (float) (cy + rx * (sin * e1x) + ry * (cos * e1y)),
                    (float) (cx + rx * (cos * e2x) - ry * (sin * e2y)), (float) (cy + rx * (sin * e2x) + ry * (cos * e2y)),
                    (float) (cx + rx * (cos * c2) - ry * (sin * s2)), (float) (cy + rx * (sin * c2) + ry * (cos * s2)));
            th += delta;
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy, len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        double a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        return (ux * vy - uy * vx) < 0 ? -a : a;
    }
}
