package org.ownscale.app;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.*;
import android.database.sqlite.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
  Store store;
  SQLiteDatabase db;
  ScaleCapture capture;
  HealthBridge health;
  LinearLayout shell, content, nav;
  ScrollView scroll;
  TextView statusText, healthStatusText;
  Button measureButton;
  int page = 0, bg, surface, ink, muted, accent, border, orange;
  int primaryContainer, onPrimary, secondaryContainer, onSecondary;
  boolean ready = false, active = false, testMode = false, foreground = false;
  String statusMessage = "Ready for your morning reading";
  String graphMetric = "Weight", historySource = "All", historySearch = "";
  int graphDays = 90, historyDays = 0;
  boolean compareOriginal = false;
  int exportRequest = 0;
  Handler handler = new Handler(Looper.getMainLooper());

  @Override
  public void onCreate(Bundle state) {
    store = new Store(this);
    boolean dark = store.get("theme", "dark").equals("dark");
    setTheme(
        dark
            ? android.R.style.Theme_Material_NoActionBar
            : android.R.style.Theme_Material_Light_NoActionBar);
    super.onCreate(state);
    db = store.db;
    testMode = getIntent().getBooleanExtra("test_mode", false);
    page = state == null ? 0 : state.getInt("page", 0);
    palette();
    db.execSQL(
        "UPDATE capture_sessions SET ended_at=?,status='Interrupted session; saved packets"
            + " retained' WHERE ended_at IS NULL",
        new Object[] {Instant.now().toString()});
    capture = new ScaleCapture(this);
    health = new HealthBridge(this);
    capture.recoverEstimates();
    store.seedCalibration();
    ready = true;
    showPage(page);
    if ("import".equals(getIntent().getStringExtra("action"))) health.importHistory();
  }

  @Override
  protected void onStart() {
    super.onStart();
    foreground = true;
    if (!testMode) health.sync();
    handler.postDelayed(
        () -> {
          if (!testMode
              && foreground
              && !active
              && store.flag("auto_connect", true)
              && profileReady()
              && hasBluetooth()) connect();
        },
        700);
  }

  @Override
  protected void onStop() {
    foreground = false;
    handler.removeCallbacksAndMessages(null);
    if (active) capture.stop("Connection stopped while OwnScale is in the background");
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    ready = false;
    if (active) capture.stop("Connection closed");
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putInt("page", page);
    super.onSaveInstanceState(out);
  }

  void palette() {
    boolean dark = store.get("theme", "dark").equals("dark");
    bg = Color.parseColor(dark ? "#131019" : "#FFFBFF");
    surface = Color.parseColor(dark ? "#221E2C" : "#F3EDF7");
    ink = Color.parseColor(dark ? "#F5EEFF" : "#201A2B");
    muted = Color.parseColor(dark ? "#C9BFD5" : "#665D72");
    accent = Color.parseColor(dark ? "#D0BCFF" : "#6750A4");
    orange = Color.parseColor(dark ? "#79DBD3" : "#006B65");
    border = Color.parseColor(dark ? "#393140" : "#E6DDEF");
    primaryContainer = Color.parseColor(dark ? "#D0BCFF" : "#EADDFF");
    onPrimary = Color.parseColor("#28134B");
    secondaryContainer = Color.parseColor(dark ? "#174A4B" : "#B8F2EB");
    onSecondary = Color.parseColor(dark ? "#A7F3E9" : "#003734");
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  GradientDrawable rounded(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  RippleDrawable feedback(int color, int radius) {
    return new RippleDrawable(
        ColorStateList.valueOf((ink & 0x00ffffff) | 0x22000000),
        rounded(color, radius),
        rounded(Color.WHITE, radius));
  }

  void pressMotion(View v) {
    android.animation.StateListAnimator states = new android.animation.StateListAnimator();
    android.animation.AnimatorSet down = new android.animation.AnimatorSet();
    down.playTogether(
        android.animation.ObjectAnimator.ofFloat(v, "scaleX", .97f),
        android.animation.ObjectAnimator.ofFloat(v, "scaleY", .97f));
    down.setDuration(100);
    android.animation.AnimatorSet up = new android.animation.AnimatorSet();
    up.playTogether(
        android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1f),
        android.animation.ObjectAnimator.ofFloat(v, "scaleY", 1f));
    up.setDuration(200);
    up.setInterpolator(new android.view.animation.OvershootInterpolator(.6f));
    states.addState(new int[] {android.R.attr.state_pressed}, down);
    states.addState(new int[] {}, up);
    v.setStateListAnimator(states);
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(1);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(0);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  TextView text(LinearLayout parent, String value, float size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setTypeface(Typeface.create("sans-serif", size >= 28 ? Typeface.BOLD : Typeface.NORMAL));
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(3), 1);
    parent.addView(t);
    return t;
  }

  void gap(LinearLayout parent, int size) {
    View v = new View(this);
    parent.addView(v, new LinearLayout.LayoutParams(1, dp(size)));
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout c = column();
    c.setPadding(dp(20), dp(20), dp(20), dp(20));
    GradientDrawable d = rounded(surface, 28);
    c.setBackground(d);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(14);
    parent.addView(c, lp);
    return c;
  }

  TextView eyebrow(LinearLayout p, String s) {
    TextView t = text(p, s, 12, muted);
    t.setLetterSpacing(.02f);
    return t;
  }

  Button button(LinearLayout parent, String label, boolean primary, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(primary ? 17 : 14);
    b.setTypeface(Typeface.create("sans-serif-medium", 0));
    b.setTextColor(primary ? onPrimary : ink);
    b.setBackground(feedback(primary ? primaryContainer : border, primary ? 28 : 20));
    pressMotion(b);
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(primary ? 58 : 48));
    lp.topMargin = dp(10);
    parent.addView(b, lp);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  void section(String title, String caption) {
    text(content, title, 36, ink).setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    gap(content, 8);
    text(content, caption, 14, muted);
    gap(content, 22);
  }

  void showPage(int requested) {
    page = requested;
    statusText = null;
    healthStatusText = null;
    measureButton = null;
    shell = column();
    shell.setBackgroundColor(bg);
    shell.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars());
          v.setPadding(edges.left, edges.top, edges.right, edges.bottom);
          return insets;
        });
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    content = column();
    content.setPadding(dp(20), dp(20), dp(20), dp(18));
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(content);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    nav = row();
    nav.setPadding(dp(12), dp(10), dp(12), dp(6));
    nav.setBackgroundColor(surface);
    String[] tabs = {"Today", "Trends", "History", "Settings"};
    for (int i = 0; i < 4; i++) {
      final int index = i;
      LinearLayout item = column();
      item.setGravity(Gravity.CENTER);
      LinearLayout pill = row();
      pill.setGravity(Gravity.CENTER);
      pill.setBackground(feedback(page == i ? primaryContainer : surface, 24));
      pill.addView(
          new ExpressiveIcon(this, i, page == i ? onPrimary : muted),
          new LinearLayout.LayoutParams(dp(23), dp(23)));
      item.addView(pill, new LinearLayout.LayoutParams(dp(64), dp(32)));
      gap(item, 5);
      TextView label = text(item, tabs[i], 11, page == i ? ink : muted);
      label.setTypeface(Typeface.create("sans-serif-medium", 0));
      label.setGravity(Gravity.CENTER);
      item.setContentDescription(tabs[i]);
      item.setSelected(page == i);
      item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      nav.addView(item, new LinearLayout.LayoutParams(0, dp(62), 1));
      item.setOnClickListener(v -> showPage(index));
      pressMotion(item);
    }
    shell.addView(nav);
    setContentView(shell);
    shell.requestApplyInsets();
    if (page == 0) today();
    else if (page == 1) trends();
    else if (page == 2) history();
    else settings();
    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
      content.setAlpha(0);
      content.setTranslationY(dp(8));
      content
          .animate()
          .alpha(1)
          .translationY(0)
          .setDuration(220)
          .setInterpolator(new android.view.animation.DecelerateInterpolator())
          .start();
    }
  }

  String fmt(double v) {
    return String.format(Locale.US, "%.1f", v);
  }

  String weightUnit() {
    return store.get("units", "lb");
  }

  double weightDisplay(double kg) {
    return weightUnit().equals("lb") ? kg * 2.2046226218488 : kg;
  }

  String weight(Double kg) {
    return kg == null ? "—" : fmt(weightDisplay(kg));
  }

  String date(String utc) {
    try {
      return Instant.parse(utc)
          .atZone(ZoneId.systemDefault())
          .format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a"));
    } catch (Exception e) {
      return utc;
    }
  }

  List<TrendMath.Point> points(String metric, int days, boolean original) {
    List<TrendMath.Point> out = new ArrayList<>();
    long since =
        days == 0
            ? 0
            : LocalDate.now()
                .minusDays(days - 1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    for (Store.Reading r : store.readings()) {
      if (r.excluded) continue;
      Double v = null;
      if (metric.equals("Weight") && r.weight != null) v = weightDisplay(r.weight);
      if (metric.equals("Body fat")
          && (r.experimental || (r.legacyAdjusted && store.flag("include_legacy", true))))
        v = original ? r.originalFat : r.adjustedFat;
      if (metric.equals("Hume fat") && r.source.equals("Hume")) v = r.originalFat;
      if (metric.equals("Waist")) v = r.waist;
      long time = Instant.parse(r.time).toEpochMilli();
      if (v != null && time >= since)
        out.add(new TrendMath.Point(time, v, metric.equals("Body fat") && r.legacyAdjusted));
    }
    out.sort(Comparator.comparingLong(p -> p.time));
    return out;
  }

  List<TrendMath.Point> stabilizedBodyFat() {
    List<TrendMath.Point> result = new ArrayList<>();
    for (Store.Reading r : store.readings())
      if (!r.excluded
          && r.fat != null
          && (r.experimental || (r.legacyAdjusted && store.flag("include_legacy", true))))
        result.add(
            new TrendMath.Point(Instant.parse(r.time).toEpochMilli(), r.fat, r.legacyAdjusted));
    result.sort(Comparator.comparingLong(p -> p.time));
    return result;
  }

  void today() {
    eyebrow(content, "OwnScale");
    gap(content, 6);
    section("Today", LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")));
    List<Store.Reading> readings = store.readings();
    Store.Reading latestWeight = null, latestFat = null;
    for (Store.Reading r : readings) {
      if (r.excluded) continue;
      if (latestWeight == null && r.weight != null) latestWeight = r;
      if (latestFat == null && r.experimental && r.fat != null) latestFat = r;
    }
    LinearLayout hero = card(content);
    GradientDrawable heroShape = rounded(primaryContainer, 32);
    heroShape.setCornerRadii(
        new float[] {dp(32), dp(32), dp(32), dp(32), dp(12), dp(12), dp(32), dp(32)});
    hero.setBackground(heroShape);
    LinearLayout heading = row();
    hero.addView(heading);
    TextView latestLabel = text(heading, "Latest weigh-in", 14, onPrimary);
    latestLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(
        new ExpressiveIcon(this, 4, Color.parseColor("#6750A4")),
        new LinearLayout.LayoutParams(dp(38), dp(38)));
    LinearLayout big = row();
    hero.addView(big);
    TextView number =
        text(big, latestWeight == null ? "—" : weight(latestWeight.weight), 72, onPrimary);
    number.setLetterSpacing(-.055f);
    number.setSingleLine(true);
    number.setAutoSizeTextTypeUniformWithConfiguration(
        38, 72, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    number.setLayoutParams(new LinearLayout.LayoutParams(0, dp(80), 1));
    text(big, weightUnit(), 22, onPrimary).setPadding(dp(8), dp(28), dp(8), 0);
    gap(hero, 8);
    text(
        hero,
        latestWeight == null ? "Your first reading starts here" : date(latestWeight.time),
        12,
        onPrimary);
    gap(hero, 2);
    LinearLayout metrics = row();
    content.addView(metrics, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout left = column(), right = column();
    left.setPadding(dp(16), dp(18), dp(12), dp(18));
    right.setPadding(dp(16), dp(18), dp(12), dp(18));
    left.setBackground(feedback(secondaryContainer, 28));
    right.setBackground(rounded(surface, 28));
    LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -1, 1);
    ml.rightMargin = dp(10);
    metrics.addView(left, ml);
    metrics.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
    text(left, "Body fat  ↗", 13, onSecondary);
    gap(left, 12);
    text(left, latestFat == null ? "—" : fmt(latestFat.fat) + "%", 34, onSecondary);
    gap(left, 8);
    text(
        left,
        store.number("bf_response_hours", 72) > 0 ? "Stabilized estimate" : "Individual estimate",
        11,
        onSecondary);
    left.setOnClickListener(
        v -> {
          graphMetric = "Body fat";
          showPage(1);
        });
    pressMotion(left);
    List<TrendMath.Point> pts = points("Weight", 0, false);
    Double avg = TrendMath.average(pts, LocalDate.now(), 7, ZoneId.systemDefault()),
        previous = TrendMath.average(pts, LocalDate.now().minusDays(7), 7, ZoneId.systemDefault());
    text(right, "7-day average", 13, muted);
    gap(right, 12);
    TextView average = text(right, avg == null ? "—" : fmt(avg), 34, ink);
    gap(right, 8);
    text(
        right,
        avg != null && previous != null
            ? String.format(Locale.US, "%+.1f %s vs prior 7d", avg - previous, weightUnit())
            : weightUnit() + " · last 7 days",
        11,
        muted);
    gap(content, 14);
    if (latestFat != null && latestFat.repeatDifference != null) {
      TextView note =
          text(
              content,
              store.number("bf_response_hours", 72) > 0
                  ? "≈  Short-term variation smoothed · originals saved"
                  : "≈  Repeat variation · stabilization is off",
              11,
              muted);
      note.setPadding(dp(8), 0, dp(8), dp(14));
      note.setOnClickListener(
          v -> {
            graphMetric = "Body fat";
            showPage(1);
          });
    }
    LinearLayout connection = card(content);
    LinearLayout line = row();
    connection.addView(line);
    text(line, active ? "●  Measuring" : "Body Pod", 17, ink)
        .setTypeface(Typeface.create("sans-serif-medium", 0));
    gap(connection, 10);
    statusText = text(connection, statusMessage, 13, muted);
    measureButton =
        button(
            connection,
            active ? "End measurement" : "＋  Measure now",
            true,
            () -> {
              if (active) capture.stop("Measurement ended. Saved readings are retained.");
              else connect();
            });
    measureButton.setBackground(feedback(Color.parseColor("#79DBD3"), 28));
    measureButton.setTextColor(Color.parseColor("#003734"));
    double target = store.number("goal_weight", 0);
    if (target > 0 && latestWeight != null) {
      LinearLayout goal = card(content);
      eyebrow(goal, "Your weight goal");
      gap(goal, 8);
      text(
          goal,
          weight(target)
              + " "
              + weightUnit()
              + "  ·  "
              + fmt(Math.abs(weightDisplay(latestWeight.weight - target)))
              + " away",
          18,
          ink);
    }
    LinearLayout trend = card(content);
    eyebrow(trend, "Weight · last 90 days");
    List<TrendMath.Point> recent = points("Weight", 90, false);
    TrendView chart =
        new TrendView(
            this,
            recent,
            store.flag("smooth", true)
                ? TrendMath.smooth(recent, ZoneId.systemDefault())
                : Collections.emptyList(),
            Collections.emptyList(),
            weightUnit(),
            orange);
    trend.addView(chart, new LinearLayout.LayoutParams(-1, dp(180)));
    text(
        trend,
        store.flag("smooth", true)
            ? "● Readings    ━ 7-day smoothed trend"
            : "● Individual readings",
        11,
        muted);
    button(trend, "Explore trends →", false, () -> showPage(1));
    if (latestFat != null) {
      LinearLayout tune = card(content);
      eyebrow(tune, "Your estimate, explained");
      Store.Calibration cal = store.calibration();
      gap(tune, 8);
      text(
          tune,
          cal == null ? "A personal starting point" : fmt(cal.anchor) + "% assumed baseline",
          18,
          ink);
      gap(tune, 8);
      text(
          tune,
          "Your adjustment applies to historical OwnScale scans. Original readings remain intact.",
          13,
          muted);
      button(tune, "Review tuning", false, this::tuning);
    }
  }

  Spinner select(
      LinearLayout parent, String[] options, int selected, java.util.function.IntConsumer changed) {
    Spinner s = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, options);
    s.setAdapter(adapter);
    s.setSelection(selected);
    parent.addView(s, new LinearLayout.LayoutParams(-1, dp(48)));
    s.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          boolean initial = true;

          public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
            if (initial) {
              initial = false;
              return;
            }
            changed.accept(pos);
          }

          public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
    return s;
  }

  void chips(
      LinearLayout parent, String[] labels, int selected, java.util.function.IntConsumer action) {
    LinearLayout r = row();
    parent.addView(r);
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      TextView t = new TextView(this);
      t.setText(labels[i]);
      t.setTextSize(12);
      t.setGravity(Gravity.CENTER);
      t.setTextColor(i == selected ? onPrimary : muted);
      GradientDrawable shape = rounded(i == selected ? primaryContainer : surface, 8);
      if (i == selected) shape.setCornerRadius(dp(24));
      else {
        float outer = dp(24), inner = dp(8);
        shape.setCornerRadii(
            new float[] {
              i == 0 ? outer : inner,
              i == 0 ? outer : inner,
              i == labels.length - 1 ? outer : inner,
              i == labels.length - 1 ? outer : inner,
              i == labels.length - 1 ? outer : inner,
              i == labels.length - 1 ? outer : inner,
              i == 0 ? outer : inner,
              i == 0 ? outer : inner
            });
      }
      t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x226750A4), shape, null));
      t.setTypeface(Typeface.create("sans-serif-medium", 0));
      t.setSelected(i == selected);
      pressMotion(t);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
      lp.setMargins(0, 0, i == labels.length - 1 ? 0 : dp(3), 0);
      r.addView(t, lp);
      t.setOnClickListener(v -> action.accept(index));
    }
  }

  Switch toggle(
      LinearLayout p, String label, boolean value, java.util.function.Consumer<Boolean> changed) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setTextColor(ink);
    s.setTextSize(14);
    s.setPadding(0, dp(10), 0, dp(10));
    android.graphics.drawable.StateListDrawable tracks =
        new android.graphics.drawable.StateListDrawable();
    GradientDrawable onTrack = rounded(primaryContainer, 16);
    onTrack.setSize(dp(52), dp(32));
    GradientDrawable offTrack = rounded(border, 16);
    offTrack.setSize(dp(52), dp(32));
    offTrack.setStroke(dp(2), muted);
    tracks.addState(new int[] {android.R.attr.state_checked}, onTrack);
    tracks.addState(new int[] {}, offTrack);
    android.graphics.drawable.StateListDrawable thumbs =
        new android.graphics.drawable.StateListDrawable();
    GradientDrawable onThumb = rounded(onPrimary, 12);
    onThumb.setSize(dp(24), dp(24));
    GradientDrawable offThumb = rounded(muted, 12);
    offThumb.setSize(dp(24), dp(24));
    thumbs.addState(
        new int[] {android.R.attr.state_checked},
        new android.graphics.drawable.InsetDrawable(onThumb, dp(4)));
    thumbs.addState(new int[] {}, new android.graphics.drawable.InsetDrawable(offThumb, dp(4)));
    s.setTrackDrawable(tracks);
    s.setThumbDrawable(thumbs);
    s.setSplitTrack(false);
    s.setSwitchMinWidth(dp(52));
    s.setSwitchPadding(dp(16));
    s.setMinHeight(dp(52));
    s.setChecked(value);
    p.addView(s, new LinearLayout.LayoutParams(-1, -2));
    s.setOnCheckedChangeListener((v, on) -> changed.accept(on));
    return s;
  }

  void trends() {
    section("Trends", "Weight, body fat & waist");
    String[] metrics = {"Weight", "Body fat", "Waist"};
    chips(
        content,
        metrics,
        Math.max(0, Arrays.asList(metrics).indexOf(graphMetric)),
        i -> {
          graphMetric = metrics[i];
          showPage(1);
        });
    gap(content, 10);
    int[] periods = {7, 30, 90, 365, 0};
    chips(
        content,
        new String[] {"7D", "30D", "90D", "1Y", "All"},
        index(periods, graphDays),
        i -> {
          graphDays = periods[i];
          showPage(1);
        });
    gap(content, 18);
    LinearLayout c = card(content);
    eyebrow(
        c,
        graphMetric.equals("Body fat")
            ? "Personal estimate · adjusted lookback"
            : graphMetric.equals("Hume fat")
                ? "Hume estimates · historical only"
                : graphMetric + " history");
    List<TrendMath.Point> p = points(graphMetric, graphDays, false);
    boolean showLine = store.flag(graphMetric.equals("Body fat") ? "smooth_bf" : "smooth", true);
    List<TrendMath.Point> smoothed =
        showLine
            ? (graphMetric.equals("Body fat")
                ? stabilizedBodyFat()
                : TrendMath.smooth(points(graphMetric, 0, false), ZoneId.systemDefault()))
            : new ArrayList<>();
    if (!p.isEmpty()) {
      long first = p.get(0).time;
      smoothed.removeIf(v -> v.time < first);
    } else smoothed = new ArrayList<>();
    String unit =
        graphMetric.equals("Weight") ? weightUnit() : graphMetric.equals("Waist") ? "cm" : "%";
    if (!p.isEmpty()) {
      TrendMath.Point latest = p.get(p.size() - 1);
      if (graphMetric.equals("Body fat")) {
        List<TrendMath.Point> stable = stabilizedBodyFat();
        if (!stable.isEmpty()) latest = stable.get(stable.size() - 1);
      }
      gap(c, 12);
      LinearLayout summary = row();
      c.addView(summary);
      TextView headline = text(summary, fmt(latest.value), 44, ink);
      headline.setLetterSpacing(-.035f);
      text(summary, "  " + unit, 18, muted);
      gap(c, 6);
      text(
          c,
          graphMetric.equals("Body fat")
              ? (store.number("bf_response_hours", 72) > 0
                  ? "Latest stabilized estimate"
                  : "Latest individual estimate")
              : "Latest reading",
          12,
          muted);
    }
    c.addView(
        new TrendView(
            this,
            p,
            smoothed,
            compareOriginal && graphMetric.equals("Body fat")
                ? points(graphMetric, graphDays, true)
                : Collections.emptyList(),
            unit,
            orange),
        new LinearLayout.LayoutParams(-1, dp(230)));
    text(c, p.size() + " readings · tap a point to inspect", 12, muted);
    gap(c, 8);
    text(
        c,
        (graphMetric.equals("Body fat") ? "○ Adjusted Hume  ● OwnScale" : "● Readings")
            + (showLine
                ? (graphMetric.equals("Body fat") ? "   ━ Stabilized" : "   ━ Smoothed")
                : "")
            + (compareOriginal && graphMetric.equals("Body fat") ? "   ┄ Original" : ""),
        10,
        muted);
    LinearLayout chartOptions = card(content);
    eyebrow(chartOptions, "Chart display");
    gap(chartOptions, 4);
    toggle(
        chartOptions,
        graphMetric.equals("Body fat") ? "Stabilized estimate line" : "7-day smoothed line",
        showLine,
        on -> {
          store.set(graphMetric.equals("Body fat") ? "smooth_bf" : "smooth", on.toString());
          showPage(1);
        });
    if (graphMetric.equals("Body fat")) {
      toggle(
          chartOptions,
          "Adjusted Hume lookback",
          store.flag("include_legacy", true),
          on -> {
            store.set("include_legacy", on.toString());
            showPage(1);
          });
      toggle(
          chartOptions,
          "Compare original estimates",
          compareOriginal,
          on -> {
            compareOriginal = on;
            showPage(1);
          });
      button(
          chartOptions,
          "How historical adjustment works  ↗",
          false,
          () -> {
            LinearLayout explanation = dialogContent();
            text(explanation, "OwnScale readings", 20, ink);
            gap(explanation, 10);
            text(
                explanation,
                "Past OwnScale estimates are recalculated using your selected baseline, sensitivity"
                    + " and stabilization. Points show individual estimates; the purple line shows"
                    + " the stabilized estimate.",
                14,
                muted);
            gap(explanation, 20);
            text(explanation, "Imported Hume readings", 20, ink);
            gap(explanation, 10);
            Store.Calibration cal = store.calibration();
            text(
                explanation,
                "Hume history is reconstructed from weight, anchored to your reference scan’s"
                    + " weight and assumed body fat. The current split is "
                    + fmt(100 * (cal == null ? .75 : cal.fatFraction))
                    + "% fat / "
                    + fmt(100 * (1 - (cal == null ? .75 : cal.fatFraction)))
                    + "% lean mass for each weight difference. Stabilization smooths historical"
                    + " weight first. Original Hume estimates remain available for comparison.",
                14,
                muted);
            gap(explanation, 16);
            text(
                explanation,
                "Your chosen starting percentage is an assumption. Original readings are preserved"
                    + " and can be compared on the chart or exported.",
                14,
                muted);
            new AlertDialog.Builder(this)
                .setTitle("Your adjusted history")
                .setView(explanation)
                .setPositiveButton("Got it", null)
                .show();
          });
      button(content, "Tune body-fat estimate", false, this::tuning);
    } else if (graphMetric.equals("Hume fat"))
      text(
          content,
          "These are the original Hume estimates you reported as inaccurate. They are retained for"
              + " comparison and never used to calibrate OwnScale.",
          13,
          muted);
  }

  int index(int[] values, int value) {
    for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
    return 0;
  }

  void history() {
    section("History", "Your readings · original data preserved");
    chips(
        content,
        new String[] {"All", "OwnScale", "Hume", "Manual"},
        Arrays.asList("All", "OwnScale", "Hume", "Manual").indexOf(historySource),
        i -> {
          historySource = new String[] {"All", "OwnScale", "Hume", "Manual"}[i];
          showPage(2);
        });
    gap(content, 12);
    int[] periods = {30, 90, 0};
    chips(
        content,
        new String[] {"30 days", "90 days", "All time"},
        index(periods, historyDays),
        i -> {
          historyDays = periods[i];
          showPage(2);
        });
    gap(content, 12);
    EditText search = field(content, "Search date or note", historySearch, false);
    LinearLayout list = column();
    button(content, "Add weight or waist", false, this::manualEntry);
    gap(content, 18);
    content.addView(list);
    renderHistory(list);
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            historySearch = s.toString();
            renderHistory(list);
          }

          public void afterTextChanged(Editable s) {}
        });
  }

  void renderHistory(LinearLayout list) {
    list.removeAllViews();
    int count = 0;
    Instant since =
        historyDays == 0
            ? Instant.EPOCH
            : LocalDate.now()
                .minusDays(historyDays - 1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();
    for (Store.Reading r : store.readings()) {
      if (!historySource.equals("All") && !historySource.equals(r.source)
          || Instant.parse(r.time).isBefore(since)
          || !(date(r.time) + " " + r.note + " " + r.source)
              .toLowerCase(Locale.US)
              .contains(historySearch.toLowerCase(Locale.US))) continue;
      count++;
      LinearLayout c = card(list);
      eyebrow(c, date(r.time));
      gap(c, 10);
      String value =
          r.weight != null
              ? weight(r.weight) + " " + weightUnit()
              : r.waist != null ? fmt(r.waist) + " cm waist" : "Scan";
      text(
          c,
          value + (r.fat != null ? "   ·   " + fmt(r.fat) + "%" : ""),
          22,
          r.excluded ? muted : ink);
      gap(c, 8);
      text(
          c, r.source + " · " + r.quality + (r.excluded ? " · Hidden from trends" : ""), 11, muted);
      if (!r.note.isEmpty()) {
        gap(c, 8);
        text(c, r.note, 12, muted);
      }
      c.setOnClickListener(v -> detail(r));
    }
    if (count == 0) text(list, "No readings match this view.", 15, muted);
  }

  EditText field(LinearLayout parent, String label, String value, boolean numeric) {
    text(parent, label, 12, muted);
    EditText e = new EditText(this);
    e.setText(value);
    e.setTextSize(17);
    e.setTextColor(ink);
    e.setHintTextColor(muted);
    e.setSingleLine(true);
    e.setPadding(dp(14), dp(8), dp(14), dp(8));
    android.graphics.drawable.StateListDrawable inputStates =
        new android.graphics.drawable.StateListDrawable();
    GradientDrawable focused = rounded(border, 12);
    focused.setStroke(dp(2), accent);
    inputStates.addState(new int[] {android.R.attr.state_focused}, focused);
    inputStates.addState(new int[] {}, rounded(border, 12));
    e.setBackground(inputStates);
    e.setContentDescription(label);
    if (numeric)
      e.setInputType(
          android.text.InputType.TYPE_CLASS_NUMBER
              | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
              | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
    LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(-1, dp(52));
    fieldParams.topMargin = dp(8);
    parent.addView(e, fieldParams);
    gap(parent, 12);
    return e;
  }

  LinearLayout dialogContent() {
    LinearLayout l = column();
    l.setPadding(dp(24), dp(16), dp(24), dp(20));
    return l;
  }

  AlertDialog dialog(String title, LinearLayout body) {
    ScrollView v = new ScrollView(this);
    v.addView(body);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(v)
            .setNegativeButton("Close", null)
            .create();
    d.show();
    d.getWindow().setBackgroundDrawable(rounded(surface, 24));
    return d;
  }

  void detail(Store.Reading r) {
    LinearLayout body = dialogContent();
    text(body, date(r.time), 14, muted);
    gap(body, 16);
    if (r.weight != null) text(body, weight(r.weight) + " " + weightUnit(), 32, ink);
    if (r.fat != null)
      text(
          body,
          fmt(r.fat)
              + "% "
              + (r.experimental
                  ? "personal estimate"
                  : r.legacyAdjusted ? "adjusted Hume estimate" : "Hume estimate"),
          20,
          orange);
    if (r.waist != null) text(body, "Waist · " + fmt(r.waist) + " cm", 20, ink);
    gap(body, 12);
    text(body, r.quality + " · " + r.source, 13, muted);
    if (r.repeatDifference != null) {
      gap(body, 10);
      text(
          body,
          "A nearby repeat scan differed by "
              + fmt(r.repeatDifference)
              + " percentage points at nearly the same weight. Interpret this as estimate"
              + " variation.",
          13,
          orange);
    }
    if (r.adjustedFat != null) {
      gap(body, 10);
      text(
          body,
          "Individual adjusted estimate: "
              + fmt(r.adjustedFat)
              + "% · Response: "
              + fmt(store.number("bf_response_hours", 72) / 24)
              + " days",
          13,
          muted);
    }
    if (r.experimental) {
      gap(body, 10);
      text(
          body,
          "Historical adjusted value under your current tuning. Original saved estimate: "
              + fmt(r.originalFat)
              + "%. This is an experimental estimate, not a validated measurement.",
          13,
          muted);
    }
    if (r.legacyAdjusted) {
      gap(body, 10);
      text(
          body,
          "Original Hume value: "
              + fmt(r.originalFat)
              + "%. The lookback reconstructs body fat from weight; it is not a new impedance-based"
              + " measurement.",
          13,
          muted);
    }
    gap(body, 20);
    EditText note = field(body, "Note (optional)", r.note, false);
    Switch hidden = toggle(body, "Exclude from trends", r.excluded, on -> {});
    text(
        body,
        "Excluding keeps the original data. Any previously synced Health Connect value stays"
            + " there.",
        11,
        muted);
    AlertDialog d = dialog("Measurement", body);
    button(
        body,
        "Save changes",
        true,
        () -> {
          store.annotate(r, note.getText().toString(), hidden.isChecked());
          d.dismiss();
          refresh();
        });
    if (r.session != null) button(body, "View raw measurement", false, () -> rawDetail(r));
  }

  void rawDetail(Store.Reading r) {
    LinearLayout b = dialogContent();
    StringBuilder lines = new StringBuilder();
    for (Store.Observation o : r.observations)
      lines.append(o.metric).append("  ").append(o.value).append(" ").append(o.unit).append("\n");
    lines.append("\nBluetooth packets\n");
    try (Cursor c =
        db.rawQuery(
            "SELECT received_at,hex,checksum_valid FROM raw_packets WHERE session_id=? ORDER BY id",
            new String[] {r.session})) {
      while (c.moveToNext())
        lines
            .append(c.getString(1))
            .append(c.getInt(2) == 1 ? "  ✓" : "  checksum mismatch")
            .append("\n");
    }
    TextView t = text(b, lines.toString(), 12, muted);
    t.setTypeface(Typeface.MONOSPACE);
    t.setTextIsSelectable(true);
    dialog("Original data", b);
  }

  void manualEntry() {
    LinearLayout b = dialogContent();
    EditText
        when =
            field(
                b,
                "Local date & time (yyyy-MM-dd HH:mm)",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                false),
        w = field(b, "Weight (" + weightUnit() + ") · optional", "", true),
        waist = field(b, "Waist (cm) · optional", "", true),
        note = field(b, "Note · optional", "", false);
    AlertDialog d = dialog("Add a measurement", b);
    button(
        b,
        "Save measurement",
        true,
        () -> {
          try {
            Double kg =
                w.getText().toString().trim().isEmpty()
                    ? null
                    : Double.parseDouble(w.getText().toString())
                        / (weightUnit().equals("lb") ? 2.2046226218488 : 1);
            Double cm =
                waist.getText().toString().trim().isEmpty()
                    ? null
                    : Double.parseDouble(waist.getText().toString());
            Instant time =
                LocalDateTime.parse(
                        when.getText().toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
            store.manual(time, kg, cm, note.getText().toString());
            d.dismiss();
            refresh();
            health.sync();
          } catch (Exception e) {
            Toast.makeText(this, "Check the date and values: " + e.getMessage(), Toast.LENGTH_LONG)
                .show();
          }
        });
  }

  void settings() {
    section("Settings", "Personalize OwnScale");
    LinearLayout appearance = card(content);
    eyebrow(appearance, "Appearance");
    gap(appearance, 12);
    text(appearance, "Display units", 13, muted);
    chips(
        appearance,
        new String[] {"Pounds", "Kilograms"},
        weightUnit().equals("lb") ? 0 : 1,
        i -> {
          store.set("units", i == 0 ? "lb" : "kg");
          showPage(3);
        });
    gap(appearance, 16);
    text(appearance, "Theme", 13, muted);
    chips(
        appearance,
        new String[] {"Dark", "Light"},
        store.get("theme", "dark").equals("dark") ? 0 : 1,
        i -> {
          store.set("theme", i == 0 ? "dark" : "light");
          recreate();
        });
    LinearLayout connection = card(content);
    eyebrow(connection, "Your scale");
    toggle(
        connection,
        "Connect when OwnScale opens",
        store.flag("auto_connect", true),
        on -> store.set("auto_connect", on.toString()));
    text(
        connection,
        "Connects only while the app is open and your phone is awake. Wake your scale to begin.",
        12,
        muted);
    gap(connection, 10);
    text(
        connection,
        store.get("device", "").isEmpty() ? "No remembered scale" : "Body Pod remembered",
        13,
        accent);
    button(
        connection,
        "Forget remembered scale",
        false,
        () -> {
          if (active) capture.stop("Scale forgotten");
          store.set("device", "");
          showPage(3);
        });
    LinearLayout profile = card(content);
    eyebrow(profile, "Your profile");
    gap(profile, 16);
    EditText height = field(profile, "Height (cm)", store.get("height", ""), true),
        age = field(profile, "Age (years)", store.get("age", ""), true);
    text(profile, "Sex used by the scale's equation", 12, muted);
    Spinner sex =
        select(
            profile, new String[] {"Select", "Female", "Male"}, store.integer("sex", 0), i -> {});
    text(profile, "Activity profile", 12, muted);
    Spinner activity =
        select(
            profile,
            new String[] {"Sedentary", "Moderate", "Active"},
            store.integer("activity", 0),
            i -> {});
    button(
        profile,
        "Save profile",
        true,
        () -> {
          try {
            double cm = Double.parseDouble(height.getText().toString());
            if (!Double.isFinite(cm) || sex.getSelectedItemPosition() == 0)
              throw new IllegalArgumentException("Enter height and select a profile");
            Protocol.profile(
                (int) Math.round(cm),
                Integer.parseInt(age.getText().toString()),
                sex.getSelectedItemPosition() == 2 ? 1 : 0,
                activity.getSelectedItemPosition());
            store.set("height", Double.toString(cm));
            store.set("age", age.getText().toString());
            store.set("sex", Integer.toString(sex.getSelectedItemPosition()));
            store.set("activity", Integer.toString(activity.getSelectedItemPosition()));
            Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show();
          } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
          }
        });
    LinearLayout model = card(content);
    eyebrow(model, "Body-fat estimate");
    gap(model, 12);
    Store.Calibration cal = store.calibration();
    text(
        model,
        cal == null
            ? "Take a full scan to begin"
            : fmt(cal.anchor) + "% baseline · " + fmt(cal.sensitivity) + "× sensitivity",
        18,
        ink);
    gap(model, 8);
    text(
        model,
        "Tuning adjusts the lookback as well as future readings. Your original data and every"
            + " tuning version are preserved.",
        13,
        muted);
    gap(model, 14);
    text(model, "Body-fat responsiveness", 13, muted);
    int[] responses = {0, 24, 72, 168};
    select(
        model,
        new String[] {
          "Off · individual estimates", "Responsive · 1 day", "Balanced · 3 days", "Steady · 7 days"
        },
        index(responses, (int) store.number("bf_response_hours", 72)),
        i -> {
          store.set("bf_response_hours", Integer.toString(responses[i]));
          health.sync();
        });
    text(
        model,
        "Time-aware stabilization gives close repeat readings very little influence. Sustained"
            + " changes move the estimate gradually. Individual calculations remain visible in the"
            + " graph. Response changes also update synced estimates.",
        12,
        muted);
    button(model, "Tune & preview history", false, this::tuning);
    button(model, "Review previous tunings", false, this::previousTunings);
    LinearLayout goals = card(content);
    eyebrow(goals, "Optional weight goal");
    gap(goals, 12);
    EditText target =
        field(
            goals,
            "Target (" + weightUnit() + ") · blank to clear",
            store.number("goal_weight", 0) > 0 ? weight(store.number("goal_weight", 0)) : "",
            true);
    button(
        goals,
        "Save goal",
        false,
        () -> {
          try {
            double v =
                target.getText().toString().trim().isEmpty()
                    ? 0
                    : Double.parseDouble(target.getText().toString())
                        / (weightUnit().equals("lb") ? 2.2046226218488 : 1);
            if (!Double.isFinite(v) || v < 0 || v > 350) throw new IllegalArgumentException();
            store.set("goal_weight", Double.toString(v));
            Toast.makeText(this, "Goal saved", Toast.LENGTH_SHORT).show();
          } catch (Exception e) {
            Toast.makeText(this, "Enter a valid target weight", Toast.LENGTH_SHORT).show();
          }
        });
    LinearLayout sync = card(content);
    eyebrow(sync, "Health Connect");
    gap(sync, 12);
    healthStatusText = text(sync, healthStatus(), 15, accent);
    toggle(
        sync,
        "Sync OwnScale weight",
        store.flag("sync_weight", false),
        on -> {
          store.set("sync_weight", on.toString());
        });
    toggle(
        sync,
        "Sync experimental body fat",
        store.flag("sync_fat", false),
        on -> {
          store.set("sync_fat", on.toString());
        });
    text(
        sync,
        "Only your OwnScale and manual weight records are written. Imported Hume history is never"
            + " re-uploaded. Tuning updates OwnScale's synced estimates; other apps may show these"
            + " as ordinary body-fat values.",
        12,
        muted);
    button(sync, "Allow Health Connect access", true, () -> health.requestWrite());
    button(sync, "Sync now", false, () -> health.sync());
    button(sync, "Import Hume history", false, () -> health.importHistory());
    gap(sync, 8);
    text(
        sync,
        "Turning a switch off stops future writes; it does not delete records already synced.",
        11,
        muted);
    LinearLayout data = card(content);
    eyebrow(data, "Your data");
    gap(data, 10);
    text(data, "No account. No cloud required.", 16, ink);
    button(data, "Email / share CSV", true, this::shareCsv);
    button(data, "Save CSV", false, () -> export("csv"));
    button(data, "Back up everything (JSON)", false, () -> export("json"));
    button(data, "Export SQLite database", false, () -> export("sqlite"));
    button(data, "Restore a backup", false, this::chooseRestore);
    gap(data, 12);
    text(
        data,
        "CSV includes the current adjusted view, sources and notes. JSON includes original"
            + " observations, packets, settings and tuning history.",
        12,
        muted);
    gap(content, 8);
    text(content, "OwnScale · Your data, on your device", 12, muted);
  }

  String healthStatus() {
    if (!store.flag("sync_weight", false) && !store.flag("sync_fat", false)) return "Sync is off";
    if (store.flag("sync_weight", false) && !health.granted(HealthBridge.WEIGHT)
        || store.flag("sync_fat", false) && !health.granted(HealthBridge.FAT))
      return "Permission needed";
    return store.get("sync_status", "Ready to sync");
  }

  void tuning() {
    List<Store.Reading> scans = store.completeScans();
    if (scans.isEmpty()) {
      Toast.makeText(this, "Take a full scan with the handle first.", Toast.LENGTH_LONG).show();
      return;
    }
    Store.Calibration cal = store.calibration();
    LinearLayout b = dialogContent();
    text(
        b,
        "Choose a scan and the body-fat percentage you want to anchor it to. Sensitivity scales"
            + " changes around that anchor; 1× keeps the model's changes, 0× holds the baseline"
            + " constant.",
        13,
        muted);
    gap(b, 16);
    String[] labels = new String[scans.size()];
    int selected = 0;
    for (int i = 0; i < labels.length; i++) {
      labels[i] = date(scans.get(i).time);
      if (cal != null && cal.session.equals(scans.get(i).session)) selected = i;
    }
    text(b, "Reference scan", 12, muted);
    Spinner reference = select(b, labels, selected, i -> {});
    EditText anchor = field(b, "Assumed body fat (%)", cal == null ? "" : fmt(cal.anchor), true),
        sensitivity =
            field(b, "Sensitivity (0–2×)", cal == null ? "1.0" : fmt(cal.sensitivity), true),
        legacy =
            field(
                b,
                "Weight change attributed to fat (0–100%)",
                cal == null ? "75" : fmt(cal.fatFraction * 100),
                true),
        note = field(b, "Reference / reason (optional)", "Personal assumption", false);
    LinearLayout preview = column();
    b.addView(preview);
    Runnable draw =
        () -> {
          try {
            double value = Double.parseDouble(anchor.getText().toString()),
                factor = Double.parseDouble(sensitivity.getText().toString()),
                fatShare = Double.parseDouble(legacy.getText().toString()) / 100;
            if (!Double.isFinite(fatShare)
                || fatShare < 0
                || fatShare > 1
                || !Double.isFinite(value)
                || !Double.isFinite(factor)
                || value <= 0
                || value >= 100
                || factor < 0
                || factor > 2) throw new IllegalArgumentException();
            Store.Reading base = scans.get(reference.getSelectedItemPosition());
            Store.Calibration candidate = new Store.Calibration();
            candidate.anchor = value;
            candidate.sensitivity = factor;
            candidate.model = base.model;
            candidate.session = base.session;
            candidate.height = base.height;
            candidate.sex = base.sex;
            candidate.fatFraction = fatShare;
            List<Store.Reading> candidates = store.readings();
            int invalid = 0;
            for (Store.Reading r : candidates) {
              Double estimate = null;
              if (r.experimental && r.model != null && r.height == base.height && r.sex == base.sex)
                estimate = value + factor * (r.model - base.model);
              else if (r.source.equals("Hume") && r.originalFat != null)
                estimate =
                    r.weight == null || base.weight == null
                        ? null
                        : Lookback.estimate(r.weight, base.weight, value, fatShare);
              if (estimate != null && (estimate <= 0 || estimate >= 100)) {
                estimate = null;
                invalid++;
              }
              r.fat = r.adjustedFat = estimate;
            }
            Store.stabilize(candidates, candidate, store.number("bf_response_hours", 72));
            List<TrendMath.Point> pts = new ArrayList<>(), stable = new ArrayList<>();
            for (Store.Reading r : candidates)
              if (!r.excluded && r.adjustedFat != null) {
                long time = Instant.parse(r.time).toEpochMilli();
                pts.add(new TrendMath.Point(time, r.adjustedFat, r.legacyAdjusted));
                if (r.fat != null) stable.add(new TrendMath.Point(time, r.fat, r.legacyAdjusted));
              }
            pts.sort(Comparator.comparingLong(p -> p.time));
            stable.sort(Comparator.comparingLong(p -> p.time));
            preview.removeAllViews();
            eyebrow(preview, "Adjusted lookback preview");
            preview.addView(
                new TrendView(this, pts, stable, Collections.emptyList(), "%", orange),
                new LinearLayout.LayoutParams(-1, dp(190)));
            text(
                preview,
                pts.size()
                    + " adjusted readings"
                    + (invalid > 0 ? " · " + invalid + " outside valid range" : "")
                    + " · raw readings stay unchanged",
                12,
                muted);
          } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Use anchor 0–100%, sensitivity 0–2× and fat share 0–100%.",
                    Toast.LENGTH_SHORT)
                .show();
          }
        };
    button(b, "Preview adjusted history", false, draw);
    gap(b, 12);
    text(
        b,
        "Hume lookback uses your reference weight and assumed body fat. At 75%, each kilogram of"
            + " weight difference is modeled as 0.75 kg fat and 0.25 kg lean mass. Stabilization"
            + " smooths weight first. This is a reconstruction, not a measurement. OwnScale scans"
            + " retain their impedance model.",
        12,
        muted);
    AlertDialog d = dialog("Personal body-fat tuning", b);
    button(
        b,
        "Apply tuning",
        true,
        () -> {
          try {
            store.tune(
                scans.get(reference.getSelectedItemPosition()),
                Double.parseDouble(anchor.getText().toString()),
                Double.parseDouble(sensitivity.getText().toString()),
                cal == null || cal.legacyOffset == null ? 0 : cal.legacyOffset,
                Double.parseDouble(legacy.getText().toString()) / 100,
                note.getText().toString());
            d.dismiss();
            graphMetric = "Body fat";
            graphDays = 0;
            showPage(1);
            health.sync();
          } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
          }
        });
    draw.run();
  }

  void previousTunings() {
    List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
    try (Cursor c =
        db.rawQuery(
            "SELECT id,anchor,sensitivity,created_at,note,fat_fraction FROM calibrations ORDER BY"
                + " created_at DESC",
            null)) {
      while (c.moveToNext()) {
        ids.add(c.getString(0));
        labels.add(
            (c.getString(0).equals(store.get("calibration", "")) ? "✓ " : "")
                + fmt(c.getDouble(1))
                + "% · "
                + fmt(c.getDouble(2))
                + "× · "
                + String.format(
                    Locale.US,
                    "%.0f%% fat / %.0f%% lean",
                    100 * c.getDouble(5),
                    100 * (1 - c.getDouble(5)))
                + "\n"
                + date(c.getString(3))
                + "\n"
                + c.getString(4));
      }
    }
    new AlertDialog.Builder(this)
        .setTitle("Restore a tuning version")
        .setItems(
            labels.toArray(new String[0]),
            (d, i) -> {
              store.set("calibration", ids.get(i));
              refresh();
              health.sync();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  boolean hasBluetooth() {
    return checkSelfPermission("android.permission.BLUETOOTH_SCAN")
            == PackageManager.PERMISSION_GRANTED
        && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
            == PackageManager.PERMISSION_GRANTED;
  }

  boolean profileReady() {
    try {
      return store.number("height", 0) >= 100
          && store.number("height", 0) <= 250
          && store.integer("age", 0) >= 18
          && store.integer("age", 0) <= 110
          && store.integer("sex", 0) > 0;
    } catch (Exception e) {
      return false;
    }
  }

  void connect() {
    if (active) return;
    if (!profileReady()) {
      say("Save your profile in Settings before connecting.");
      showPage(3);
      return;
    }
    if (!hasBluetooth()) {
      requestPermissions(
          new String[] {
            "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"
          },
          11);
      return;
    }
    try {
      capture.start(
          Protocol.profile(
              (int) Math.round(store.number("height", 0)),
              store.integer("age", 0),
              store.integer("sex", 0) == 2 ? 1 : 0,
              store.integer("activity", 0)));
    } catch (Exception e) {
      say("Could not connect: " + e.getMessage());
      captureActive(false);
    }
  }

  void captureActive(boolean value) {
    active = value;
    if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (measureButton != null) measureButton.setText(value ? "End measurement" : "Measure now");
  }

  void say(String message) {
    runOnUiThread(
        () -> {
          statusMessage = message;
          if (statusText != null) statusText.setText(message);
          android.util.Log.i("OwnScale", message);
          if (ready && page != 0) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
  }

  void refresh() {
    if (!ready) return;
    if (page != 3) showPage(page);
    else if (healthStatusText != null) healthStatusText.setText(healthStatus());
  }

  void captureFinished() {
    store.seedCalibration();
    if (ready) {
      refresh();
      health.sync();
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 11 && hasBluetooth()) connect();
    if (request == 10) {
      boolean all = true;
      for (String p : HealthBridge.READ) all &= health.granted(p);
      if (all) health.importHistory();
      else say("History permission was not granted. You can enable it later in Settings.");
    }
    if (request == 12) {
      showPage(page);
      health.sync();
    }
  }

  Intent csvShareIntent() throws Exception {
    File file = new File(getCacheDir(), "ownscale.csv");
    try (FileOutputStream out = new FileOutputStream(file)) {
      store.writeCsv(out);
    }
    Uri uri = Uri.parse("content://org.ownscale.app.exports/ownscale.csv");
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/csv");
    intent.putExtra(Intent.EXTRA_STREAM, uri);
    intent.putExtra(Intent.EXTRA_SUBJECT, "OwnScale measurement history");
    intent.putExtra(
        Intent.EXTRA_TEXT,
        "My OwnScale measurements. Body-fat values labeled experimental use my personal baseline"
            + " adjustment.");
    intent.setClipData(ClipData.newUri(getContentResolver(), "OwnScale CSV", uri));
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    return intent;
  }

  void shareCsv() {
    try {
      startActivity(Intent.createChooser(csvShareIntent(), "Email or share your CSV"));
    } catch (Exception e) {
      say("Export failed: " + e.getMessage());
    }
  }

  void export(String format) {
    store.set("export_format", format);
    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType(
        format.equals("sqlite")
            ? "application/vnd.sqlite3"
            : format.equals("csv") ? "text/csv" : "application/json");
    i.putExtra(
        Intent.EXTRA_TITLE,
        "ownscale-" + LocalDate.now() + "." + (format.equals("sqlite") ? "db" : format));
    startActivityForResult(i, 20);
  }

  void chooseRestore() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("application/json");
    startActivityForResult(i, 21);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null) return;
    try {
      if (request == 20) {
        String format = store.get("export_format", "json");
        try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
          if (format.equals("csv")) store.writeCsv(out);
          else if (format.equals("json")) out.write(store.backup().toString(2).getBytes("UTF-8"));
          else
            try (InputStream in = new FileInputStream(getDatabasePath("ownscale.db"))) {
              byte[] buffer = new byte[8192];
              int n;
              while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        }
        say("Export saved.");
      } else if (request == 21) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
          byte[] buffer = new byte[8192];
          int n;
          while ((n = in.read(buffer)) > 0) {
            bytes.write(buffer, 0, n);
            if (bytes.size() > 100 * 1024 * 1024) throw new IOException("Backup exceeds 100 MB");
          }
        }
        JSONObject backup = new JSONObject(bytes.toString("UTF-8"));
        new AlertDialog.Builder(this)
            .setTitle("Restore this backup?")
            .setMessage(
                "Existing readings are preserved. Missing readings are added; saved settings, notes"
                    + " and tuning selection are restored. Auto-connect and health sync will be"
                    + " turned off for review.")
            .setPositiveButton(
                "Restore",
                (d, w) -> {
                  try {
                    int count = store.restore(backup);
                    palette();
                    showPage(3);
                    say("Restored " + count + " additional observations.");
                  } catch (Exception e) {
                    say("Restore failed: " + e.getMessage());
                  }
                })
            .setNegativeButton("Cancel", null)
            .show();
      }
    } catch (Exception e) {
      say("Could not access file: " + e.getMessage());
    }
  }

  void writeCsv(OutputStream out) throws Exception {
    store.writeCsv(out);
  }

  JSONArray tableJson(String table) throws Exception {
    return store.tableJson(table);
  }
}
