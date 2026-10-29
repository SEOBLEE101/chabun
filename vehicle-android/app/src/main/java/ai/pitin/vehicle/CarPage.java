package ai.pitin.vehicle;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One redesigned car screen (1680×1200 artboard). Built once per mood; update() refreshes live text. */
abstract class CarPage {
    final CarShell shell;
    final MainActivity a;
    final CarKit k;
    final CarTheme t;
    View view;
    private boolean shownOnce;

    CarPage(CarShell shell) {
        this.shell = shell;
        this.a = shell.a;
        this.k = shell.kit;
        this.t = shell.theme;
    }

    abstract View build();

    abstract void update();

    List<View> cascadeTargets() { return Collections.emptyList(); }

    View view() {
        if (view == null) view = build();
        return view;
    }

    /** Called each time the page comes on screen; the list cascade runs on the first show only. */
    void onShow(boolean animate) {
        if (!shownOnce) {
            shownOnce = true;
            if (animate) k.cascade(cascadeTargets(), 80);
        }
    }

    /** `.car` frame: padding 52 76 60 (top as {@link CarKit#FRAME_TOP} with the taller header row), header / main / footer column. */
    LinearLayout frame(FrameLayout root) {
        LinearLayout col = k.column(root);
        col.setPadding(k.px(76), k.px(CarKit.FRAME_TOP), k.px(76), k.px(60));
        col.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        col.setClipChildren(false);
        return col;
    }

    /** The full header/footer bar (with its rule) around the inner row returned by CarKit.header/footer. */
    static View bar(View inner) { return inner != null && inner.getParent() instanceof View ? (View) inner.getParent() : inner; }

    static List<View> list(View... views) {
        List<View> out = new ArrayList<>();
        for (View v : views) if (v != null) out.add(v);
        return out;
    }
}
