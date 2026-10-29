package ai.pitin.vehicle;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Stateless view construction; layout and session ownership stay in MainActivity. */
final class GraphiteViews {
    private final Context context;
    GraphiteViews(Context context) { this.context = context; }
    TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size);
        view.setTextColor(GraphitePalette.TEXT); view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view); return view;
    }
    Button button(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(context); view.setText(label); view.setAllCaps(false); view.setTextSize(17);
        view.setMinHeight(dp(56)); view.setPadding(dp(20), dp(12), dp(20), dp(12));
        view.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{GraphitePalette.BACKGROUND, GraphitePalette.MUTED}));
        GradientDrawable background = new GradientDrawable(); background.setCornerRadius(dp(28));
        background.setColor(GraphitePalette.TEXT);
        view.setBackground(background);
        view.setOnFocusChangeListener((focusedView, focused) ->
                background.setStroke(focused ? dp(3) : 0, GraphitePalette.MUTED));
        view.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{GraphitePalette.TEXT, GraphitePalette.SURFACE}));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, dp(4), 0, dp(4)); view.setLayoutParams(params);
        view.setOnClickListener(ignored -> action.run()); parent.addView(view); return view;
    }
    Button wideButton(LinearLayout parent, String label, Runnable action) {
        Button view = button(parent, label, action);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    void secondaryButton(Button view) {
        view.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{GraphitePalette.TEXT, GraphitePalette.MUTED}));
        view.setBackgroundTintList(null);
        ((GradientDrawable) view.getBackground()).setColor(Color.TRANSPARENT);
    }
    void stylePrimaryActions(boolean wide, Button... actions) {
        for (Button action : actions) {
            action.setTextSize(wide ? 26 : 19);
            action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            action.setMinHeight(dp(wide ? 88 : 56));
            ((GradientDrawable) action.getBackground()).setCornerRadius(dp(wide ? 44 : 28));
            action.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        }
    }
    EditText uriInput(LinearLayout parent, String hint, String initialText) {
        EditText input = new EditText(context);
        input.setSingleLine(true); input.setTextColor(Color.WHITE); input.setHintTextColor(Color.LTGRAY);
        input.setHint(hint); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (!initialText.isEmpty()) input.setText(initialText);
        parent.addView(input); return input;
    }
    CheckBox checkBox(LinearLayout parent, String label, int minHeightDp, boolean checked,
                      CompoundButton.OnCheckedChangeListener listener) {
        CheckBox box = new CheckBox(context);
        box.setText(label); box.setTextColor(Color.WHITE); box.setMinHeight(dp(minHeightDp));
        box.setChecked(checked); box.setButtonTintList(ColorStateList.valueOf(GraphitePalette.TEXT));
        box.setOnCheckedChangeListener(listener); parent.addView(box); return box;
    }
    LinearLayout row(LinearLayout parent) {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL); parent.addView(view); return view;
    }
    LinearLayout column(LinearLayout parent) {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL);
        parent.addView(view); return view;
    }
    void divider(LinearLayout parent) {
        View rule = new View(context); rule.setBackgroundColor(GraphitePalette.RULE);
        parent.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    LinearLayout card(LinearLayout parent) {
        LinearLayout card = column(parent);
        GradientDrawable background = new GradientDrawable();
        background.setColor(GraphitePalette.SURFACE); background.setCornerRadius(dp(24));
        background.setStroke(dp(1), GraphitePalette.RULE); card.setBackground(background);
        card.setPadding(dp(24), dp(20), dp(24), dp(20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(10), 0, dp(10)); card.setLayoutParams(params);
        return card;
    }
    void choice(Button button, boolean selected) {
        if (button.getContentDescription() != null && button.isSelected() == selected) return;
        button.setSelected(selected);
        button.setBackgroundTintList(null);
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? GraphitePalette.TEXT : GraphitePalette.SURFACE);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(selected ? 2 : 1), selected ? GraphitePalette.TEXT : GraphitePalette.RULE);
        button.setBackground(background);
        button.setTextColor(selected ? GraphitePalette.BACKGROUND : GraphitePalette.TEXT);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setContentDescription(button.getText() + (selected ? ", 선택됨" : ""));
    }
    Button secondary(LinearLayout parent, String label, Runnable action) {
        Button button = button(parent, label, action); choice(button, false); return button;
    }
    /** Static preparation/connection controls; existing session buttons keep their original rendering. */
    Button quietAction(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(context);
        view.setText(label); view.setAllCaps(false); view.setTextSize(18);
        view.setMinHeight(dp(56)); view.setMinWidth(0);
        view.setPadding(dp(20), dp(14), dp(20), dp(14));
        view.setStateListAnimator(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(5), 0, dp(5));
        view.setLayoutParams(params);
        quietChoice(view, false);
        // Ordinary action labels may change to retry/hide; let accessibility read their current text.
        view.setContentDescription(null);
        view.setOnClickListener(ignored -> action.run());
        parent.addView(view);
        return view;
    }
    void quietChoice(Button view, boolean selected) {
        if (view.getContentDescription() != null && view.isSelected() == selected) return;
        view.setSelected(selected); view.setBackgroundTintList(null);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shellSurface(GraphitePalette.BACKGROUND, GraphitePalette.RULE, 1));
        states.addState(new int[]{android.R.attr.state_pressed}, shellSurface(GraphitePalette.RULE, GraphitePalette.TEXT, 2));
        states.addState(new int[]{android.R.attr.state_focused}, shellSurface(GraphitePalette.SURFACE, GraphitePalette.TEXT, 2));
        states.addState(new int[]{}, shellSurface(selected ? GraphitePalette.SURFACE : Color.TRANSPARENT,
                selected ? GraphitePalette.TEXT : GraphitePalette.RULE, selected ? 2 : 1));
        view.setBackground(states);
        view.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}},
                new int[]{GraphitePalette.TEXT, GraphitePalette.MUTED}));
        view.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        view.setContentDescription(view.getText() + (selected ? ", 선택됨" : ""));
    }
    private GradientDrawable shellSurface(int fill, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill); drawable.setCornerRadius(dp(16));
        drawable.setStroke(dp(width), stroke);
        return drawable;
    }
    void gap(LinearLayout parent, int height) {
        parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height)));
    }
    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
