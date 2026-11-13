package fr.evan.linea;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static class App {
        String label, key, letter, search;
        ComponentName cn;
        UserHandle user;
        Drawable icon;
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private LauncherApps launcherApps;
    private UserManager userManager;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private final List<App> apps = new ArrayList<>();
    private final Map<String, App> byKey = new HashMap<>();

    private FrameLayout root;
    private LinearLayout home, favList, drawerCol, panel;
    private ScrollView panelScroll;
    private AlphabetBar bar;
    private FrameLayout drawer;
    private EditText search;
    private ListView list;
    private AppAdapter adapter;

    private String currentLetter;
    private View hoverRow;
    private boolean ignoreGesture;
    private int insetTop, insetBottom;
    private GestureDetector flingDetector, homeDetector;

    // ---------------------------------------------------------------- cycle de vie

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        prefs = getSharedPreferences("linea", MODE_PRIVATE);

        buildUi();
        setContentView(root);
        setupGestures();

        launcherApps.registerCallback(appsCallback, handler);
        loadApps();
        askDefaultLauncherOnce();
    }

    @Override
    protected void onDestroy() {
        launcherApps.unregisterCallback(appsCallback);
        bg.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Bouton "accueil" pressé alors qu'on est déjà sur l'accueil
        hidePanel();
        closeDrawer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        hidePanel();
        closeDrawer();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (panelScroll.getVisibility() == View.VISIBLE) hidePanel();
        else if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        // sinon : on est l'écran d'accueil, on ne fait rien
    }

    private final LauncherApps.Callback appsCallback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { loadApps(); }
        @Override public void onPackageAdded(String p, UserHandle u) { loadApps(); }
        @Override public void onPackageChanged(String p, UserHandle u) { loadApps(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { loadApps(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { loadApps(); }
    };

    // ---------------------------------------------------------------- interface

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private Drawable ripple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(14));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void buildUi() {
        root = new FrameLayout(this);

        // léger voile pour la lisibilité sur n'importe quel fond d'écran
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55000000, 0x11000000, 0x66000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ---- accueil : horloge + date + favoris
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(76);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setShadowLayer(dp(4), 0, dp(1), 0x88000000);
        clock.setIncludeFontPadding(false);
        clock.setOnClickListener(v -> safeStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        home.addView(clock, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        date.setTextSize(16);
        date.setTextColor(0xDDFFFFFF);
        date.setShadowLayer(dp(3), 0, dp(1), 0x88000000);
        date.setOnClickListener(v -> safeStart(new Intent(Intent.ACTION_VIEW,
                Uri.parse("content://com.android.calendar/time"))));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        dlp.leftMargin = dp(4);
        home.addView(date, dlp);

        View spacer = new View(this);
        home.addView(spacer, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        favList = new LinearLayout(this);
        favList.setOrientation(LinearLayout.VERTICAL);
        home.addView(favList, new LinearLayout.LayoutParams(MATCH, WRAP));

        root.addView(home, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ---- barre alphabet
        bar = new AlphabetBar(this);
        bar.setListener(this::onBarTouch);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(dp(40), MATCH, Gravity.END);
        barLp.rightMargin = dp(22); // décollée du bord, comme Niagara
        root.addView(bar, barLp);

        // ---- panneau des applis d'une lettre
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        panelScroll = new ScrollView(this);
        panelScroll.setBackground(rounded(0xEE1A1A1D, 22));
        panelScroll.setElevation(dp(10));
        panelScroll.setVerticalScrollBarEnabled(false);
        panelScroll.addView(panel);
        panelScroll.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(250), WRAP, Gravity.TOP | Gravity.END);
        plp.rightMargin = dp(76);
        root.addView(panelScroll, plp);

        // ---- tiroir avec toutes les applis
        buildDrawer();

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            applyInsets(ins);
            return ins;
        });
        home.setOnTouchListener((v, e) -> homeDetector.onTouchEvent(e));
    }

    @SuppressWarnings("deprecation")
    private void applyInsets(WindowInsets ins) {
        insetTop = ins.getSystemWindowInsetTop();
        insetBottom = ins.getSystemWindowInsetBottom();
        int stableBottom = ins.getStableInsetBottom();
        home.setPadding(dp(28), insetTop + dp(36), dp(84), stableBottom + dp(28));

        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        blp.topMargin = insetTop + dp(170);
        blp.bottomMargin = stableBottom + dp(24);
        bar.setLayoutParams(blp);

        drawerCol.setPadding(dp(20), insetTop + dp(16), dp(20), insetBottom + dp(8));
    }

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xF20D0D0F);
        drawer.setClickable(true);
        drawer.setVisibility(View.GONE);

        drawerCol = new LinearLayout(this);
        drawerCol.setOrientation(LinearLayout.VERTICAL);

        search = new EditText(this);
        search.setHint("Rechercher une appli");
        search.setHintTextColor(0x88FFFFFF);
        search.setTextColor(Color.WHITE);
        search.setTextSize(17);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(rounded(0x22FFFFFF, 18));
        search.setPadding(dp(18), dp(12), dp(18), dp(12));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { adapter.filter(s.toString()); }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            if (adapter.getCount() > 0) launch(adapter.getItem(0));
            return true;
        });
        drawerCol.addView(search, new LinearLayout.LayoutParams(MATCH, WRAP));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, dp(8), 0, dp(8));
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        drawerCol.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        drawer.addView(drawerCol, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(drawer, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    /** Une ligne "icône + nom" utilisée partout (favoris, panneau, tiroir). */
    private View makeRow(App a, float sp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        if (showIcons()) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            int s = dp(sp >= 20 ? 30 : 26);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.rightMargin = dp(16);
            row.addView(iv, ilp);
        }
        row.addView(label(a.label, sp), new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.setTag(a);
        row.setBackground(ripple());
        row.setOnClickListener(v -> launch(a));
        row.setOnLongClickListener(v -> {
            appMenu(v, a);
            return true;
        });
        return row;
    }

    // ---------------------------------------------------------------- gestes

    private void setupGestures() {
        flingDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (ignoreGesture) return false;
                if (Math.abs(vy) < Math.abs(vx) * 1.5f || Math.abs(vy) < dp(350)) return false;
                if (drawer.getVisibility() == View.VISIBLE) {
                    if (vy > 0 && !list.canScrollVertically(-1)) closeDrawer();
                    return false;
                }
                if (vy < 0) openDrawer();
                else expandNotifications();
                return false;
            }
        });

        homeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                hidePanel();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (!ignoreGesture) homeMenu();
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            ignoreGesture = isInside(bar, ev) || (panelScroll.getVisibility() == View.VISIBLE && isInside(panelScroll, ev));
        }
        flingDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private boolean isInside(View v, MotionEvent ev) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = ev.getRawX(), y = ev.getRawY();
        return x >= loc[0] && x <= loc[0] + v.getWidth() && y >= loc[1] && y <= loc[1] + v.getHeight();
    }

    private void onBarTouch(int action, String letter, float rawX, float rawY) {
        if (letter != null) {
            setHover(null);
            if (!letter.equals(currentLetter) || panelScroll.getVisibility() != View.VISIBLE) {
                currentLetter = letter;
                showLetter(letter, rawY);
                bar.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        } else if (panelScroll.getVisibility() == View.VISIBLE) {
            // le doigt a glissé vers la gauche, sur le panneau : on surligne l'appli visée
            View r = rowAt(rawY);
            if (r != hoverRow) {
                setHover(r);
                if (r != null) r.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }

        if (action == MotionEvent.ACTION_UP) {
            if (hoverRow != null) {
                App a = (App) hoverRow.getTag();
                setHover(null);
                launch(a);
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            setHover(null);
        }
    }

    private View rowAt(float rawY) {
        int[] loc = new int[2];
        for (int i = 0; i < panel.getChildCount(); i++) {
            View c = panel.getChildAt(i);
            if (!(c.getTag() instanceof App)) continue;
            c.getLocationOnScreen(loc);
            if (rawY >= loc[1] && rawY <= loc[1] + c.getHeight()) return c;
        }
        return null;
    }

    private void setHover(View r) {
        if (hoverRow != null) hoverRow.setBackground(ripple());
        hoverRow = r;
        if (r != null) r.setBackground(rounded(0x33FFFFFF, 14));
    }

    private void showLetter(String letter, float rawY) {
        panel.removeAllViews();
        TextView head = label(letter, 13);
        head.setTextColor(0x99FFFFFF);
        head.setPadding(dp(10), dp(2), 0, dp(4));
        panel.addView(head);
        for (App a : visibleApps()) {
            if (a.letter.equals(letter)) panel.addView(makeRow(a, 18));
        }
        panelScroll.scrollTo(0, 0);

        // calcule la hauteur pour centrer le panneau sur le doigt
        int rootH = root.getHeight();
        int maxH = (int) (rootH * 0.7f);
        panelScroll.measure(
                View.MeasureSpec.makeMeasureSpec(dp(250), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int h = Math.min(panelScroll.getMeasuredHeight(), maxH);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panelScroll.getLayoutParams();
        lp.height = h >= maxH ? maxH : WRAP;
        panelScroll.setLayoutParams(lp);

        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        float y = rawY - rl[1] - h / 2f;
        y = Math.max(insetTop + dp(12), Math.min(y, rootH - insetBottom - h - dp(12)));
        panelScroll.setTranslationY(y);

        if (panelScroll.getVisibility() != View.VISIBLE) {
            panelScroll.setVisibility(View.VISIBLE);
            panelScroll.setAlpha(0f);
            panelScroll.setTranslationX(dp(24));
            panelScroll.animate().alpha(1f).translationX(0).setDuration(140).start();
        }
    }

    private void hidePanel() {
        setHover(null);
        currentLetter = null;
        panelScroll.setVisibility(View.GONE);
    }

    private void openDrawer() {
        hidePanel();
        adapter.filter("");
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.setTranslationY(dp(80));
        drawer.animate().alpha(1f).translationY(0).setDuration(200).start();
        list.setSelection(0);
    }

    private void closeDrawer() {
        if (drawer == null || drawer.getVisibility() != View.VISIBLE) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setText("");
        search.clearFocus();
        drawer.setVisibility(View.GONE);
    }

    @SuppressWarnings({"WrongConstant", "JavaReflectionMemberAccess"})
    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- applis

    private void loadApps() {
        bg.execute(() -> {
            List<App> out = new ArrayList<>();
            String me = getPackageName();
            for (UserHandle u : userManager.getUserProfiles()) {
                List<LauncherActivityInfo> infos;
                try {
                    infos = launcherApps.getActivityList(null, u);
                } catch (Exception e) {
                    continue;
                }
                long serial = userManager.getSerialNumberForUser(u);
                for (LauncherActivityInfo i : infos) {
                    if (i.getComponentName().getPackageName().equals(me)) continue;
                    App a = new App();
                    a.label = i.getLabel().toString().trim();
                    a.cn = i.getComponentName();
                    a.user = u;
                    a.key = a.cn.flattenToString() + "#" + serial;
                    a.search = normalize(a.label);
                    a.letter = letterOf(a.search);
                    try {
                        a.icon = lineIcons() ? lineIcon(i.getIcon(0)) : i.getBadgedIcon(0);
                    } catch (Exception ignored) {
                    }
                    out.add(a);
                }
            }
            Collator col = Collator.getInstance();
            col.setStrength(Collator.PRIMARY);
            Collections.sort(out, (x, y) -> col.compare(x.label, y.label));
            handler.post(() -> {
                apps.clear();
                byKey.clear();
                for (App a : out) {
                    apps.add(a);
                    byKey.put(a.key, a);
                }
                refresh();
            });
        });
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase();
    }

    private static String letterOf(String normalized) {
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isWhitespace(c)) continue;
            c = Character.toUpperCase(c);
            return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
        }
        return "#";
    }

    private List<App> visibleApps() {
        Set<String> hidden = prefs.getStringSet("hidden", Collections.emptySet());
        List<App> l = new ArrayList<>();
        for (App a : apps) if (!hidden.contains(a.key)) l.add(a);
        return l;
    }

    private void refresh() {
        renderFavorites();
        TreeSet<String> letters = new TreeSet<>((x, y) -> {
            if (x.equals(y)) return 0;
            if (x.equals("#")) return 1;
            if (y.equals("#")) return -1;
            return x.compareTo(y);
        });
        for (App a : visibleApps()) letters.add(a.letter);
        bar.setLetters(new ArrayList<>(letters));
        adapter.filter(search.getText().toString());
    }

    private List<String> getFavs() {
        List<String> l = new ArrayList<>();
        for (String s : prefs.getString("favs", "").split("\n")) if (!s.isEmpty()) l.add(s);
        return l;
    }

    private void saveFavs(List<String> l) {
        prefs.edit().putString("favs", TextUtils.join("\n", l)).apply();
        renderFavorites();
    }

    private boolean lineIcons() {
        return prefs.getBoolean("line_icons", true);
    }

    /**
     * Transforme une icône en pictogramme blanc "trait" :
     * 1) couche monochrome Android 13+ (icônes à thème), 2) premier plan de l'icône adaptative
     * teinté en blanc, 3) sinon version en niveaux de gris.
     */
    private Drawable lineIcon(Drawable d) {
        int s = dp(40);
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        boolean ok = false;
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
            Drawable layer = null;
            if (Build.VERSION.SDK_INT >= 33) layer = ad.getMonochrome();
            if (layer == null) layer = ad.getForeground();
            if (layer != null) {
                Drawable l = layer.getConstantState() != null ? layer.getConstantState().newDrawable().mutate() : layer.mutate();
                int pad = s / 4; // les couches font 108dp dont 72dp visibles
                l.setBounds(-pad, -pad, s + pad, s + pad);
                l.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
                l.draw(c);
                ok = coverage(bmp) < 0.55f; // si tout l'espace est rempli, ce n'est pas un logo
                if (!ok) bmp.eraseColor(Color.TRANSPARENT);
            }
        }
        if (!ok) {
            Drawable x = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d.mutate();
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0f);
            x.setColorFilter(new ColorMatrixColorFilter(cm));
            int in = s / 8;
            x.setBounds(in, in, s - in, s - in);
            x.setAlpha(210);
            x.draw(c);
        }
        return new BitmapDrawable(getResources(), bmp);
    }

    private static float coverage(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), n = 0, tot = 0;
        for (int y = 0; y < h; y += 2)
            for (int x = 0; x < w; x += 2) {
                tot++;
                if ((b.getPixel(x, y) >>> 24) > 128) n++;
            }
        return tot == 0 ? 0 : (float) n / tot;
    }

    private boolean showIcons() {
        return prefs.getBoolean("icons", true);
    }

    private void renderFavorites() {
        favList.removeAllViews();
        int n = 0;
        for (String k : getFavs()) {
            App a = byKey.get(k);
            if (a == null) continue;
            favList.addView(makeRow(a, 22));
            n++;
        }
        if (n == 0 && !apps.isEmpty()) {
            TextView hint = label("Glisse le doigt sur les lettres à droite →\nAppui long sur une appli pour l'ajouter ici.", 15);
            hint.setSingleLine(false);
            hint.setMaxLines(4);
            hint.setTextColor(0xBBFFFFFF);
            hint.setPadding(dp(8), 0, 0, dp(12));
            favList.addView(hint);
        }
    }

    private void launch(App a) {
        try {
            launcherApps.startMainActivity(a.cn, a.user, null, null);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir " + a.label, Toast.LENGTH_SHORT).show();
        }
        handler.postDelayed(() -> {
            hidePanel();
            closeDrawer();
        }, 300);
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Aucune appli disponible", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- menus

    private void appMenu(View anchor, App a) {
        List<String> favs = getFavs();
        boolean isFav = favs.contains(a.key);
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, isFav ? "Retirer des favoris" : "Ajouter aux favoris");
        if (isFav) {
            m.add(0, 2, 1, "Monter");
            m.add(0, 3, 2, "Descendre");
        }
        m.add(0, 4, 3, "Masquer l'appli");
        m.add(0, 5, 4, "Infos sur l'appli");
        m.add(0, 6, 5, "Désinstaller");
        pm.setOnMenuItemClickListener(item -> {
            List<String> f = getFavs();
            int idx = f.indexOf(a.key);
            switch (item.getItemId()) {
                case 1:
                    if (idx >= 0) f.remove(idx);
                    else f.add(a.key);
                    saveFavs(f);
                    hidePanel();
                    break;
                case 2:
                    if (idx > 0) { Collections.swap(f, idx, idx - 1); saveFavs(f); }
                    break;
                case 3:
                    if (idx >= 0 && idx < f.size() - 1) { Collections.swap(f, idx, idx + 1); saveFavs(f); }
                    break;
                case 4: {
                    Set<String> h = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
                    h.add(a.key);
                    prefs.edit().putStringSet("hidden", h).apply();
                    if (idx >= 0) { f.remove(idx); saveFavs(f); }
                    hidePanel();
                    refresh();
                    Toast.makeText(this, a.label + " masquée", Toast.LENGTH_SHORT).show();
                    break;
                }
                case 5:
                    try {
                        launcherApps.startAppDetailsActivity(a.cn, a.user, null, null);
                    } catch (Exception ignored) {
                    }
                    break;
                case 6:
                    safeStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.cn.getPackageName())));
                    break;
            }
            return true;
        });
        pm.show();
    }

    private void homeMenu() {
        String[] items = {
                "Fond d'écran",
                showIcons() ? "Masquer les icônes" : "Afficher les icônes",
                lineIcons() ? "Icônes en couleur" : "Icônes en traits blancs",
                "Applis masquées",
                "Choisir le launcher par défaut",
                "Paramètres du téléphone"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            safeStart(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Fond d'écran"));
                            break;
                        case 1:
                            prefs.edit().putBoolean("icons", !showIcons()).apply();
                            refresh();
                            break;
                        case 2:
                            prefs.edit().putBoolean("line_icons", !lineIcons()).apply();
                            loadApps();
                            break;
                        case 3:
                            hiddenAppsDialog();
                            break;
                        case 4:
                            safeStart(new Intent(Settings.ACTION_HOME_SETTINGS));
                            break;
                        case 5:
                            safeStart(new Intent(Settings.ACTION_SETTINGS));
                            break;
                    }
                })
                .show();
    }

    private void hiddenAppsDialog() {
        Set<String> hidden = prefs.getStringSet("hidden", Collections.emptySet());
        List<App> hs = new ArrayList<>();
        for (App a : apps) if (hidden.contains(a.key)) hs.add(a);
        if (hs.isEmpty()) {
            Toast.makeText(this, "Aucune appli masquée", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[hs.size()];
        for (int i = 0; i < hs.size(); i++) names[i] = hs.get(i).label;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Toucher pour réafficher")
                .setItems(names, (d, which) -> {
                    Set<String> h = new HashSet<>(hidden);
                    h.remove(hs.get(which).key);
                    prefs.edit().putStringSet("hidden", h).apply();
                    refresh();
                })
                .show();
    }

    @SuppressWarnings("deprecation")
    private void askDefaultLauncherOnce() {
        if (prefs.getBoolean("asked_default", false)) return;
        prefs.edit().putBoolean("asked_default", true).apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 42);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- tiroir

    private class AppAdapter extends BaseAdapter {
        private final List<App> shown = new ArrayList<>();

        void filter(String q) {
            String n = normalize(q.trim());
            shown.clear();
            List<App> starts = new ArrayList<>();
            for (App a : visibleApps()) {
                if (n.isEmpty() || a.search.contains(n)) {
                    if (!n.isEmpty() && a.search.startsWith(n)) starts.add(a);
                    else shown.add(a);
                }
            }
            shown.addAll(0, starts); // les correspondances en début de nom d'abord
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public App getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            return makeRow(shown.get(p), 19);
        }
    }
}
