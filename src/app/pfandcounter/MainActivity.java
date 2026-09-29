package app.pfandcounter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.LocaleManager;
import android.app.UiModeManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.VibrationEffect;
import android.text.InputType;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The whole app: preview on top, running total, the lines, and the by-hand buttons. */
public class MainActivity extends Activity implements BarcodeScanner.Listener {

    private static final int REQUEST_CAMERA = 11;
    /** How long an unknown barcode waits for Open Food Facts before the app asks anyway. */
    private static final int CHECK_WAIT_MS = 3000;
    /** The list as it was before the last reset, until the next bottle is counted. */
    private static final String UNDO_FILE = "undo.json";

    private Store store;
    private DepositRules rules;
    private CountSession session;
    private Stats stats;
    private ProductLookup lookup;
    private JevClient jev;
    private BarcodeScanner scanner;
    /** Null unless this build carries one, see ExtraSource. */
    private ExtraSource extra;
    private final Set<String> extraAsked = new HashSet<String>();
    private final ExecutorService extraPool = Executors.newSingleThreadExecutor();

    private TextView totalView;
    private TextView breakdownView;
    private TextView countView;
    private TextView hintView;
    private ListView listView;
    private Button torchButton;
    private Button resetButton;
    private Button shareButton;
    private Button sortButton;
    private LineAdapter adapter;

    private AlertDialog openDialog;
    /** The unknown barcode being looked up right now, before anything is asked. */
    private String checking;
    private ToneGenerator tone;
    private Vibrator vibrator;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        store = new Store(this);
        rules = new DepositRules(store, getResources());
        session = CountSession.load(store);
        stats = Stats.load(store);
        lookup = new ProductLookup();
        jev = new JevClient(this);
        extra = ExtraSource.Loader.load();

        totalView = (TextView) findViewById(R.id.total);
        breakdownView = (TextView) findViewById(R.id.breakdown);
        countView = (TextView) findViewById(R.id.count);
        hintView = (TextView) findViewById(R.id.hint);
        listView = (ListView) findViewById(R.id.list);
        torchButton = (Button) findViewById(R.id.torch);
        resetButton = (Button) findViewById(R.id.reset);
        shareButton = (Button) findViewById(R.id.share);
        sortButton = (Button) findViewById(R.id.sort);
        oldestFirst = "oldest".equals(String.valueOf(store.read(ORDER_FILE)).trim());
        sortButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleOrder();
            }
        });
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                shareCount();
            }
        });
        withIcon((TextView) findViewById(R.id.guide), R.drawable.ic_b_help, getString(R.string.btn_guide));
        withIcon(shareButton, R.drawable.ic_b_share, getString(R.string.share));
        withIcon(torchButton, R.drawable.ic_b_torch, getString(R.string.torch));

        adapter = new LineAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                editLine(lineAt(position));
            }
        });
        // Holding a row opens the same menu: change name, deposit, remove, forget.
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View v, int position, long id) {
                editLine(lineAt(position));
                return true;
            }
        });

        findViewById(R.id.add8).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                addByHand(DepositRules.REUSABLE_SMALL);
            }
        });
        findViewById(R.id.add15).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                addByHand(DepositRules.REUSABLE);
            }
        });
        findViewById(R.id.add25).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                addByHand(DepositRules.SINGLE_USE);
            }
        });
        findViewById(R.id.crate).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showCrates();
            }
        });
        findViewById(R.id.guide).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                guideByHand();
            }
        });
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                resetOrUndo();
            }
        });
        torchButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (scanner != null) scanner.toggleTorch();
            }
        });

        findViewById(R.id.settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSettings();
            }
        });
        totalView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showAbout();
                return true;
            }
        });

        scanner = new BarcodeScanner(this, (TextureView) findViewById(R.id.preview), this);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
        } catch (RuntimeException ignored) {
            tone = null; // some devices refuse a tone generator; the app works without the beep
        }
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        refresh();
        handleTestIntent(getIntent());
        if (state == null && !store.exists(HOWTO_FILE) && !store.exists("current.json")) {
            howToPending = true; // shown once the camera question is out of the way, see onResume
        } else if (state != null && state.getBoolean("reopen_settings")) {
            openLater(new Runnable() {
                @Override public void run() {
                    showSettings();
                }
            });
        }
    }

    /** A change of design or language rebuilds the screen; the old one lets go of what it holds. */
    @Override protected void onDestroy() {
        if (tone != null) tone.release();
        extraPool.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTestIntent(intent);
    }

    /**
     * A way in for checking the app without holding a bottle in front of the lens:
     * adb shell am start -n app.pfandcounter/.MainActivity --es scan 4000000000006
     * Text that is not a product number (a web address) goes the way a QR code from the camera
     * goes: ProductCode decides, exactly as for a real read.
     */
    private void handleTestIntent(Intent intent) {
        if (intent == null) return;
        // adb shell am start -n … --ez layout_check true: every language measured, results in logcat.
        if (intent.getBooleanExtra("layout_check", false)) {
            intent.removeExtra("layout_check");
            LayoutCheck.run(this, LANGUAGES);
        }
        // adb shell am start -n … --ez page_check true: every window opened in every language.
        if (intent.getBooleanExtra("page_check", false)) {
            intent.removeExtra("page_check");
            LocaleList was = getSystemService(LocaleManager.class).getApplicationLocales();
            pageCheckNext(0, 0, was.toLanguageTags());
        }
        // adb shell am start -n … --ez camera_problem true: the notice must go once frames arrive.
        if (intent.getBooleanExtra("camera_problem", false)) {
            intent.removeExtra("camera_problem");
            scanner.simulateProblem();
        }
        final String text = intent.getStringExtra("scan");
        if (text == null || text.isEmpty()) return;
        intent.removeExtra("scan");
        final String code = ProductCode.ofText(text);
        listView.post(new Runnable() {
            @Override public void run() {
                if (code != null) onCode(code); else onOtherCode();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (pageCheck != null) {
            listView.postDelayed(new Runnable() {
                @Override public void run() {
                    pageCheckRun();
                }
            }, 1000);
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanner.start();
            firstStartNotesLater();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override protected void onPause() {
        if (checking != null) {
            checking = null; // the late answer is dropped; the next scan asks afresh
            hintView.setText(R.string.aim_hint);
            scanner.setPaused(false);
        }
        session.save(store);
        scanner.stop();
        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        openDialog = null;
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            scanner.start();
        } else {
            hintView.setText(R.string.camera_needed);
            findViewById(R.id.aim_line).setVisibility(View.GONE);
            torchButton.setVisibility(View.GONE);
            firstStartNotesLater();
        }
    }

    /** Set once the user has ticked that the app can make mistakes; until then the notice comes back. */
    private static final String DISCLAIMER_FILE = "mistakes.understood";

    /**
     * What a first start shows, one after the other and only once the camera question is answered
     * (it would close them): the notice that the app can be wrong, then the short guide.
     */
    private void firstStartNotesLater() {
        openLater(new Runnable() {
            @Override public void run() {
                if (openDialog != null && openDialog.isShowing()) return;
                if (!store.exists(DISCLAIMER_FILE)) {
                    showDisclaimer();
                } else if (howToPending) {
                    howToPending = false;
                    showHowTo();
                }
            }
        });
    }

    /**
     * The app estimates; the machine decides. Shown at every start until the box is ticked and
     * confirmed, and it cannot be tapped away.
     */
    private void showDisclaimer() {
        scanner.setPaused(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        final android.widget.CheckBox understood = new android.widget.CheckBox(this);
        understood.setText(R.string.disclaimer_check);
        understood.setTextColor(getColor(R.color.text));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(understood);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.disclaimer_title)
                .setMessage(R.string.disclaimer_body)
                .setView(wrap)
                .setCancelable(false)
                .setPositiveButton(R.string.disclaimer_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        store.write(DISCLAIMER_FILE, "1");
                        firstStartNotesLater();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        openDialog = dialog;
        dialog.show();
        final Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        ok.setEnabled(false);
        understood.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                ok.setEnabled(checked);
            }
        });
    }

    // --- scanning ----------------------------------------------------------

    @Override public void onCode(String code) {
        DepositRules.Rule rule = rules.lookup(code);
        if (rule == null) {
            checkThenAsk(code);
            return;
        }
        if (rule.cents == DepositRules.NONE) {
            // Taught as "no deposit": say so instead of adding a worthless line — but offer the
            // way back, because such a barcode never gets a row whose menu could undo it.
            showNoDeposit(code, rule.name);
            return;
        }
        // A name the user gave (or learned before) wins over whatever the online lookup says.
        if (rules.isLearned(code) && hasName(rule.name)) {
            add(code, rule.name, rule.cents, 1, rule.crate);
            return;
        }
        String cached = lookup.cached(code);
        add(code, cached != null ? cached : rule.name, rule.cents, 1, rule.crate);
        fetchNameLater(code);
    }

    /** A real name, not empty and not the "Unknown product" stand-in. */
    private boolean hasName(String name) {
        return name != null && !name.trim().isEmpty() && !name.equals(getString(R.string.unknown_product));
    }

    /**
     * A QR code with a web address, a batch code: not a product number, so nothing is looked up
     * and nothing is counted. Only the aiming line says so, and the camera keeps looking.
     */
    @Override public void onOtherCode() {
        if (checking != null || (openDialog != null && openDialog.isShowing())) return;
        // Said once per notice, not for every frame the camera sees the same QR code.
        if (!getString(R.string.other_code).contentEquals(hintView.getText())) speak(getString(R.string.other_code));
        hintView.setText(R.string.other_code);
        hintView.removeCallbacks(resetHint);
        hintView.postDelayed(resetHint, 2500);
    }

    @Override public void onProblem(String message) {
        hintView.setText(getString(R.string.camera_failed));
        torchButton.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Camera thread: an unknown code must agree in three frames, a known one in two. */
    @Override public boolean isKnown(String code) {
        return rules.lookup(code) != null;
    }

    @Override public void onCameraBack() {
        // Only take back what onProblem put there; a scan notice or "checking" stays.
        if (getString(R.string.camera_failed).contentEquals(hintView.getText())) {
            hintView.setText(R.string.aim_hint);
        }
        if (scanner.isTorchAvailable()) torchButton.setVisibility(View.VISIBLE);
    }

    /**
     * An unknown barcode: ask Open Food Facts first. Gummy bears and tuna are clearly not a
     * drink — those get a short notice instead of the deposit question (DrinkCheck). The answer
     * takes a fraction of a second; without internet the question comes after CHECK_WAIT_MS.
     */
    private void checkThenAsk(final String code) {
        if (openDialog != null && openDialog.isShowing()) return;
        if (code.equals(checking)) return;
        if (extra != null && !extraAsked.contains(code) && !rules.mustAsk(code)) {
            askExtraFirst(code);
            return;
        }
        ProductLookup.Info known = lookup.cachedInfo(code);
        if (known != null) {
            decide(code, known);
            return;
        }
        checking = code;
        scanner.setPaused(true);
        hintView.removeCallbacks(resetHint);
        hintView.setText(R.string.checking);
        speak(getString(R.string.checking));
        final Runnable giveUp = new Runnable() {
            @Override public void run() {
                if (!code.equals(checking)) return;
                checking = null;
                hintView.setText(R.string.aim_hint);
                feedback(UNKNOWN);
                askAboutNewCode(code);
            }
        };
        hintView.postDelayed(giveUp, CHECK_WAIT_MS);
        lookup.lookupInfo(code, new ProductLookup.InfoCallback() {
            @Override public void onInfo(String forCode, ProductLookup.Info info) {
                if (!code.equals(checking)) return; // gave up waiting, or the app was left
                checking = null;
                hintView.removeCallbacks(giveUp);
                hintView.setText(R.string.aim_hint);
                decide(code, info);
            }
        });
    }

    /**
     * The extra source (see ExtraSource) answers before Open Food Facts. A clear amount is counted
     * and remembered like a list entry; no answer goes on to the usual lookup. Asked once per
     * barcode and app run, so a source out of reach costs one wait, not one per scan.
     */
    private void askExtraFirst(final String code) {
        android.util.Log.i(Store.TAG, "extra source asked");
        checking = code;
        scanner.setPaused(true);
        hintView.removeCallbacks(resetHint);
        hintView.setText(R.string.checking);
        speak(getString(R.string.checking));
        extraPool.execute(new Runnable() {
            @Override public void run() {
                ExtraSource.Answer a = null;
                try {
                    a = extra.ask(code);
                } catch (Exception e) {
                    android.util.Log.w(Store.TAG, "extra source failed: " + e);
                }
                final ExtraSource.Answer answer = a;
                listView.post(new Runnable() {
                    @Override public void run() {
                        extraAsked.add(code);
                        if (!code.equals(checking)) return; // the app was left meanwhile
                        checking = null;
                        if (answer != null && (answer.cents == DepositRules.REUSABLE_SMALL
                                || answer.cents == DepositRules.REUSABLE || answer.cents == DepositRules.SINGLE_USE)) {
                            android.util.Log.i(Store.TAG, "extra source: " + answer.cents + " cents");
                            hintView.setText(R.string.aim_hint);
                            scanner.setPaused(false);
                            rules.learn(code, answer.cents, answer.name);
                            add(code, answer.name, answer.cents);
                            fetchNameLater(code);
                        } else {
                            android.util.Log.i(Store.TAG, "extra source: no answer");
                            checkThenAsk(code);
                        }
                    }
                });
            }
        });
    }

    private void decide(String code, ProductLookup.Info info) {
        if (info != null && info.clearlyNotADrink()) {
            showNotADrink(code, info);
        } else if (info != null && SyrupCheck.isSyrup(info.name, info.categories)) {
            showSyrup(code, info);
        } else {
            feedback(UNKNOWN);
            askAboutNewCode(code);
        }
    }

    private void showNotADrink(String code, ProductLookup.Info info) {
        String group = DrinkCheck.describe(info.categories);
        String why = group != null ? getString(R.string.not_a_drink_fmt, info.source.label, group)
                : getString(R.string.not_a_drink_plain, info.source.label);
        showNoDeposit(code, info, getString(R.string.not_a_drink_title), why);
    }

    /** A drink syrup is not ready to drink, so there is no deposit on it (SyrupCheck). */
    private void showSyrup(String code, ProductLookup.Info info) {
        showNoDeposit(code, info, getString(R.string.syrup_title), getString(R.string.syrup_why));
    }

    /** Nothing is added and nothing is learned — but the deposit question is one tap away. */
    private void showNoDeposit(final String code, ProductLookup.Info info, String title, String why) {
        feedback(NO_DEPOSIT);
        if (openDialog != null && openDialog.isShowing()) return;
        scanner.setPaused(true);
        String shown = shownForDialog(code, info.name);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(shown + "\n\n" + why)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.set_deposit_anyway, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        openLater(new Runnable() {
                            @Override public void run() {
                                askAboutNewCode(code);
                            }
                        });
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                        scanner.forgetLastCode();
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    private void askAboutNewCode(final String code) {
        if (openDialog != null && openDialog.isShowing()) return;
        scanner.setPaused(true);

        final View content = LayoutInflater.from(this).inflate(R.layout.dialog_new, null);
        final TextView nameView = (TextView) content.findViewById(R.id.dlg_name);
        final EditText ownName = (EditText) content.findViewById(R.id.dlg_name_edit);
        ((TextView) content.findViewById(R.id.dlg_code)).setText(code);
        // Taught before under a name of the user's own: offer it again for correcting.
        final DepositRules.Rule before = rules.isLearned(code) ? rules.lookup(code) : null;
        if (before != null && hasName(before.name)) ownName.setText(before.name);

        final ProductLookup.Info known = lookup.cachedInfo(code);
        if (known != null && known.name != null) {
            nameView.setText(known.name);
        } else {
            nameView.setText(R.string.looking_up);
            nameView.postDelayed(new Runnable() {
                @Override public void run() {
                    if (getString(R.string.looking_up).contentEquals(nameView.getText())) {
                        nameView.setText(R.string.no_name);
                        ownName.setVisibility(View.VISIBLE);
                    }
                }
            }, 5000);
            // Looked up before without a name: nothing more will come, so no waiting.
            if (known != null) nameView.setText(R.string.no_name);
            if (known != null || ownName.length() > 0) ownName.setVisibility(View.VISIBLE);
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.new_code_title)
                .setView(content)
                .setNegativeButton(R.string.skip_once, null)
                // Same as skipping, said for the case where the digits do not match the label.
                .setNeutralButton(R.string.scan_again, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                        scanner.forgetLastCode();
                    }
                })
                .create();

        View.OnClickListener choose = new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cents = v.getId() == R.id.dlg_8 ? DepositRules.REUSABLE_SMALL
                        : v.getId() == R.id.dlg_15 ? DepositRules.REUSABLE
                        : v.getId() == R.id.dlg_25 ? DepositRules.SINGLE_USE
                        : DepositRules.NONE;
                String name = nameView.getText().toString();
                String typed = ownName.getText().toString().trim();
                if (!typed.isEmpty()) {
                    name = typed;
                } else if (name.equals(getString(R.string.looking_up)) || name.equals(getString(R.string.no_name))) {
                    name = getString(R.string.unknown_product);
                }
                rules.learn(code, cents, name);
                if (cents == DepositRules.NONE) {
                    Toast.makeText(MainActivity.this, R.string.no_deposit_toast, Toast.LENGTH_SHORT).show();
                } else {
                    add(code, name, cents);
                }
                if (openDialog != null) openDialog.dismiss();
            }
        };
        content.findViewById(R.id.dlg_8).setOnClickListener(choose);
        content.findViewById(R.id.dlg_15).setOnClickListener(choose);
        content.findViewById(R.id.dlg_25).setOnClickListener(choose);
        content.findViewById(R.id.dlg_0).setOnClickListener(choose);
        // A crate with its own label: learned as a crate, counted apart from the bottles.
        final int crateCents = CrateSheet.crateValue(store);
        Button crateChoice = (Button) content.findViewById(R.id.dlg_crate);
        crateChoice.setText(getString(R.string.d_crate_fmt, money(crateCents)));
        crateChoice.setEnabled(crateCents > 0);
        crateChoice.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = nameView.getText().toString();
                String typed = ownName.getText().toString().trim();
                if (!typed.isEmpty()) {
                    name = typed;
                } else if (name.equals(getString(R.string.looking_up)) || name.equals(getString(R.string.no_name))) {
                    name = getString(R.string.crate_name);
                }
                rules.learn(code, crateCents, name, true);
                add(code, name, crateCents, 1, true);
                if (openDialog != null) openDialog.dismiss();
            }
        });

        // Two digits away from a known barcode: offer that one, the camera may have misread.
        final String near = rules.similar(code);
        if (near != null) {
            DepositRules.Rule nearRule = rules.lookup(near);
            TextView similar = (TextView) content.findViewById(R.id.dlg_similar);
            String nearName = nearRule != null && hasName(nearRule.name) ? nearRule.name
                    : getString(R.string.unknown_product);
            similar.setText(getString(R.string.similar_fmt, nearName, near));
            similar.setVisibility(View.VISIBLE);
            View use = content.findViewById(R.id.dlg_similar_use);
            use.setVisibility(View.VISIBLE);
            use.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (openDialog != null) openDialog.dismiss();
                    openLater(new Runnable() {
                        @Override public void run() {
                            onCode(near);
                        }
                    });
                }
            });
        }

        // "Help me decide" swaps the four buttons for the picture questions — in the same
        // window, because a window opened from a window blocked the input before (see openLater).
        final View choices = content.findViewById(R.id.dlg_choices);
        final LinearLayout guideHost = (LinearLayout) content.findViewById(R.id.dlg_guide);
        final DepositGuide guide = new DepositGuide(this, guideHost, new DepositGuide.Listener() {
            @Override public void onChosen(int cents) {
                int id = cents == DepositRules.REUSABLE_SMALL ? R.id.dlg_8
                        : cents == DepositRules.REUSABLE ? R.id.dlg_15
                        : cents == DepositRules.SINGLE_USE ? R.id.dlg_25 : R.id.dlg_0;
                content.findViewById(id).performClick();
            }
            @Override public void onLeave() {
                guideHost.setVisibility(View.GONE);
                choices.setVisibility(View.VISIBLE);
            }
        });
        content.findViewById(R.id.dlg_help).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                choices.setVisibility(View.GONE);
                guideHost.setVisibility(View.VISIBLE);
                // A recognised dairy jar skips "what is it made of" (showJar sets the tag).
                guide.start(v.getTag() == DepositGuide.JAR ? DepositGuide.JAR : DepositGuide.ROOT);
            }
        });

        openDialog = dialog;
        dialog.show();

        final Button askJev = (Button) content.findViewById(R.id.dlg_ask_jev);
        withIcon(askJev, R.drawable.ic_b_ai, getString(R.string.jev_ask));
        withIcon((TextView) content.findViewById(R.id.dlg_help), R.drawable.ic_b_help, getString(R.string.g_open));
        if (known == null) {
            askJev.setBackgroundResource(R.drawable.btn_bg);
            if (!jev.enabled() || !jev.hasKey()) jevState(content, askJev, null);
            lookup.lookupInfo(code, new ProductLookup.InfoCallback() {
                @Override public void onInfo(String forCode, ProductLookup.Info info) {
                    if (openDialog != dialog) return;
                    if (info != null && info.name != null) {
                        nameView.setText(info.name);
                    } else {
                        nameView.setText(R.string.no_name);
                        ownName.setVisibility(View.VISIBLE);
                    }
                    jevState(content, askJev, info);
                    if (info != null && info.isDepositJar()) showJar(content, askJev);
                    else showMakerHint(content, rules.makerHint(code, info));
                }
            });
        } else {
            jevState(content, askJev, known);
            if (known.isDepositJar()) showJar(content, askJev);
            else showMakerHint(content, rules.makerHint(code, known));
        }
        askJev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askJev(dialog, content, code);
            }
        });
    }

    /**
     * Green "Ask Jev" where the data hint at a drink. Otherwise grey, with the reason in words
     * underneath (and in the button's spoken label): off without a key or without product data,
     * since a tap could only fail; still tappable when the data merely don't sound like a drink.
     */
    private void jevState(View content, Button askJev, ProductLookup.Info info) {
        TextView why = (TextView) content.findViewById(R.id.dlg_jev_why);
        if (!jev.enabled()) { // opt-in: nothing about Jev shows until it is switched on
            askJev.setVisibility(View.GONE);
            why.setVisibility(View.GONE);
            return;
        }
        int reason = 0;
        boolean usable = true;
        if (!jev.hasKey()) {
            reason = R.string.jev_why_no_key;
            usable = false;
        } else if (info == null || info.jevText == null || info.jevText.trim().isEmpty()) {
            reason = R.string.jev_no_data;
            usable = false;
        } else if (!looksLikeDrink(info)) {
            reason = R.string.jev_why_grey;
        }
        askJev.setBackgroundResource(reason == 0 ? R.drawable.btn_accent : R.drawable.btn_bg);
        askJev.setEnabled(usable);
        askJev.setAlpha(usable ? 1f : 0.5f);
        if (reason == 0) {
            why.setVisibility(View.GONE);
            askJev.setContentDescription(null);
        } else {
            why.setText(reason);
            why.setVisibility(View.VISIBLE);
            // The reason is read with the button, so it is not read a second time on its own.
            why.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            askJev.setContentDescription(getString(R.string.jev_ask) + ". " + getString(reason));
        }
    }

    /**
     * A yogurt or cream jar Open Food Facts marks as reusable glass (DairyJarCheck): 0.15 gets the
     * ring, the reason sits where Jev's answer would. Jev would only repeat it — on the 36 control
     * jars with such a word it picked 0.15 every time (27.09.2026) — so its button steps back to
     * grey and saves the call. Still one tap to count.
     */
    private void showJar(View content, Button askJev) {
        askJev.setBackgroundResource(R.drawable.btn_bg);
        if (askJev.isEnabled()) { // the jar's own reason below says why the button stepped back
            content.findViewById(R.id.dlg_jev_why).setVisibility(View.GONE);
            askJev.setContentDescription(null);
        }
        content.findViewById(R.id.dlg_help).setTag(DepositGuide.JAR);
        ((TextView) content.findViewById(R.id.dlg_jev_pick)).setText(R.string.jar_pick);
        TextView why = (TextView) content.findViewById(R.id.dlg_jev_trust);
        why.setText(R.string.jar_why);
        why.setTextColor(getColor(R.color.text_dim));
        content.findViewById(R.id.dlg_jev_meter).setVisibility(View.GONE);
        content.findViewById(R.id.dlg_jev).setVisibility(View.VISIBLE);
        ring((Button) content.findViewById(R.id.dlg_15));
        why.setPadding(0, why.getPaddingTop(), 0, why.getPaddingBottom());
        android.util.Log.i(Store.TAG, "reusable dairy jar: suggesting 0.15");
    }

    /**
     * Other barcodes of the same maker in the list all carry one deposit (MakerHint): that button
     * gets the ring, the reason sits where Jev's answer would. Only a hint — the user still taps,
     * and "Ask Jev" stays as it was.
     */
    private void showMakerHint(View content, int[] hint) {
        if (hint == null) return;
        int cents = hint[0];
        ((TextView) content.findViewById(R.id.dlg_jev_pick)).setText(getString(R.string.maker_pick, money(cents)));
        TextView why = (TextView) content.findViewById(R.id.dlg_jev_trust);
        why.setText(getString(R.string.maker_why, hint[1], money(cents)));
        why.setTextColor(getColor(R.color.text_dim));
        why.setPadding(0, why.getPaddingTop(), 0, why.getPaddingBottom());
        content.findViewById(R.id.dlg_jev_meter).setVisibility(View.GONE);
        content.findViewById(R.id.dlg_jev).setVisibility(View.VISIBLE);
        ring((Button) content.findViewById(CHOICE_IDS[choiceIndex(cents)]));
        android.util.Log.i(Store.TAG, "maker hint: " + cents + " cents from " + hint[1] + " siblings");
    }

    /** The suggestion ring, keeping the button's padding. */
    private void ring(Button b) {
        int left = b.getPaddingLeft(), top = b.getPaddingTop();
        int right = b.getPaddingRight(), bottom = b.getPaddingBottom();
        b.setBackground(bar(0, getColor(R.color.accent_dim), true));
        b.setPadding(left, top, right, bottom);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    /**
     * Green "Ask Jev" only where the data hints at a drink; grey (but just as tappable) for
     * things like "handschuhe m", which Open Food Facts lists with nothing but a name.
     */
    private static boolean looksLikeDrink(ProductLookup.Info info) {
        return info != null && (DrinkCheck.hasDrinkHint(info.categories)
                || NonFoodCheck.nameSoundsLikeDrink(info.name));
    }

    private static final int[] CHOICE_IDS = {R.id.dlg_8, R.id.dlg_15, R.id.dlg_25, R.id.dlg_0};

    /**
     * Only on "Ask Jev": Jev needs the product data, so it waits for the lookup if that is
     * still running. Nothing is counted or learned until a button is tapped.
     */
    private void askJev(final AlertDialog dialog, final View content, String code) {
        final Button ask = (Button) content.findViewById(R.id.dlg_ask_jev);
        if (!jev.hasKey()) {
            Toast.makeText(this, R.string.jev_why_no_key, Toast.LENGTH_LONG).show();
            return;
        }
        ask.setEnabled(false);
        withIcon(ask, R.drawable.ic_b_ai, getString(R.string.jev_asking));
        lookup.lookupInfo(code, new ProductLookup.InfoCallback() {
            @Override public void onInfo(String forCode, ProductLookup.Info info) {
                if (openDialog != dialog) return;
                jev.ask(info != null ? info.jevText : null, new JevClient.Callback() {
                    @Override public void onSuggestion(JevClient.Suggestion s) {
                        if (openDialog != dialog) return;
                        ask.setVisibility(View.GONE);
                        showJev(content, s, info != null && info.categories != null
                                && DairyJarCheck.isDairy(info.categories));
                    }

                    @Override public void onProblem(JevClient.Problem p, String detail) {
                        if (openDialog != dialog) return;
                        if (p == JevClient.Problem.NO_DATA) {
                            ask.setText(R.string.jev_no_data);
                            return; // asking again would not change anything
                        }
                        String why;
                        switch (p) {
                            case NO_KEY: why = getString(R.string.jev_why_no_key); break;
                            case KEY_REJECTED: why = getString(R.string.jev_key_rejected, detail); break;
                            case NO_CREDIT: why = getString(R.string.jev_no_credit); break;
                            case BUSY: why = getString(R.string.jev_busy); break;
                            case TIMEOUT: why = getString(R.string.jev_timeout); break;
                            case OFFLINE: why = getString(R.string.jev_offline); break;
                            default: why = getString(R.string.jev_failed, detail);
                        }
                        Toast.makeText(MainActivity.this, why, Toast.LENGTH_LONG).show();
                        ask.setEnabled(true);
                        withIcon(ask, R.drawable.ic_b_ai, getString(R.string.jev_ask_again));
                    }
                });
            }
        });
    }

    /**
     * Jev's answer as pictures: every deposit button is filled as far as Jev thinks it likely,
     * the pick gets a ring, and one bar shows how often a pick this sure was right in the
     * control runs (JevTrust) — Jev's own confidence is not taken at its word. Dairy products
     * have their own counts there: Jev is much less reliable on them below 0.95.
     */
    private void showJev(View content, JevClient.Suggestion s, boolean dairy) {
        // Not a drink: the deposit odds mean nothing then (they were only measured on drinks and
        // dairy products), so "No deposit" gets the ring and the trust bar speaks for the drink
        // question. A yogurt is no drink either, yet its jar may carry a deposit: for dairy
        // products and a "jar15" pick the deposit question has the say.
        boolean notADrink = s.notADrink() && !dairy && !"jar15".equals(s.choice);
        int pick = notADrink ? 3 : choiceIndex(s.cents);
        double[] odds = notADrink ? new double[]{0, 0, 0, 1} : s.probabilities;
        String what = pick == 3 ? getString(R.string.jev_no_deposit) : money(s.cents);
        ((TextView) content.findViewById(R.id.dlg_jev_pick)).setText(notADrink
                ? getString(R.string.jev_not_a_drink) : getString(R.string.jev_suggests, what));

        double[] band = notADrink ? JevTrust.drinkBandOf(Math.max(0, s.drinkConfidence))
                : dairy ? JevTrust.dairyBandOf(Math.max(0, s.confidence))
                : JevTrust.bandOf(Math.max(0, s.confidence));
        double rate = band[3] > 0 ? band[2] / band[3] : 0;
        int label = rate >= 0.85 ? R.string.jev_trust_high : rate >= 0.75 ? R.string.jev_trust_good
                : rate >= 0.55 ? R.string.jev_trust_mid : R.string.jev_trust_low;
        int color = getColor(rate >= 0.75 ? R.color.accent : rate >= 0.55 ? R.color.warn : R.color.bad);
        TextView trust = (TextView) content.findViewById(R.id.dlg_jev_trust);
        trust.setText(label);
        trust.setTextColor(color);
        View meter = content.findViewById(R.id.dlg_jev_meter);
        meter.setBackground(bar(rate, color, false));
        meter.setVisibility(View.VISIBLE); // a jar or maker hint shown before hid it
        content.findViewById(R.id.dlg_jev).setVisibility(View.VISIBLE);

        for (int i = 0; i < CHOICE_IDS.length; i++) {
            Button b = (Button) content.findViewById(CHOICE_IDS[i]);
            double p = Math.max(0, odds[i]);
            int left = b.getPaddingLeft(), top = b.getPaddingTop();
            int right = b.getPaddingRight(), bottom = b.getPaddingBottom();
            b.setBackground(bar(p, getColor(R.color.accent_dim), i == pick));
            b.setPadding(left, top, right, bottom);
            b.setTypeface(android.graphics.Typeface.DEFAULT); // Jev's pick replaces an earlier hint
            android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(b.getText());
            int from = text.length();
            if (!notADrink) {
                text.append("   ").append(String.valueOf(Math.round(p * 100))).append(" %");
                text.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.text_dim)),
                        from, text.length(), 0);
                text.setSpan(new android.text.style.RelativeSizeSpan(0.85f), from, text.length(), 0);
            }
            if (i == pick) {
                // "No deposit" is dim by default; the pick always reads at full strength.
                b.setTextColor(getColor(R.color.text));
                text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, from, 0);
            }
            b.setText(text);
        }
    }

    /** A rounded track filled from the left to `share`; with a ring when `ring` is set. */
    private android.graphics.drawable.Drawable bar(double share, int fillColor, boolean ring) {
        float radius = 10 * getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(getColor(R.color.surface_high));
        track.setCornerRadius(radius);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor(fillColor);
        fill.setCornerRadius(radius);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fill, android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        clip.setLevel((int) Math.round(Math.min(1, share) * 10000));
        android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
        outline.setColor(android.graphics.Color.TRANSPARENT);
        outline.setCornerRadius(radius);
        if (ring) outline.setStroke((int) (3 * getResources().getDisplayMetrics().density), getColor(R.color.accent));
        return new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track, clip, outline});
    }

    private static int choiceIndex(int cents) {
        return cents == DepositRules.REUSABLE_SMALL ? 0 : cents == DepositRules.REUSABLE ? 1
                : cents == DepositRules.SINGLE_USE ? 2 : 3;
    }

    /**
     * Jev is billed in US dollars; shown in euros like every other amount, so only roughly:
     * ECB reference rate of 28.09.2026, 1 € = 1,1378 $ (ecb.europa.eu, eurofxref-daily.xml).
     */
    private static final double USD_PER_EUR = 1.1378;

    private static String euros(double usd) {
        double eur = usd / USD_PER_EUR;
        return String.format(Locale.GERMANY, eur < 0.01 ? "≈ %.5f€" : "≈ %.2f€", eur);
    }

    /**
     * The user picks how Jev is asked — through OpenRouter or directly at TypeSafe — and types that
     * way's key here; the app never shows a key back. Each way keeps its own key.
     */
    private void showJevSettings() {
        scanner.setPaused(true);
        double[] spent = jev.spent();
        final JevClient.Provider[] chosen = {jev.provider()};
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true);
        final TextView state = new TextView(this);
        final RadioGroup ways = new RadioGroup(this);
        final RadioButton viaOpenRouter = new RadioButton(this);
        viaOpenRouter.setId(View.generateViewId());
        viaOpenRouter.setText(R.string.jev_via_openrouter);
        final RadioButton viaTypeSafe = new RadioButton(this);
        viaTypeSafe.setId(View.generateViewId());
        viaTypeSafe.setText(R.string.jev_via_typesafe);
        ways.addView(viaOpenRouter);
        ways.addView(viaTypeSafe);
        final AlertDialog[] shown = {null};
        final Runnable showState = new Runnable() {
            @Override public void run() {
                boolean typeSafe = chosen[0] == JevClient.Provider.TYPESAFE;
                boolean has = jev.hasKey(chosen[0]);
                field.setHint(typeSafe ? R.string.jev_key_hint_typesafe : R.string.jev_key_hint);
                state.setText(getString(has ? R.string.jev_key_set : R.string.jev_key_none));
                if (shown[0] != null) {
                    Button remove = shown[0].getButton(DialogInterface.BUTTON_NEUTRAL);
                    if (remove != null) remove.setVisibility(has && jev.enabled() ? View.VISIBLE : View.GONE);
                }
            }
        };
        ways.check(chosen[0] == JevClient.Provider.TYPESAFE ? viaTypeSafe.getId() : viaOpenRouter.getId());
        showState.run();
        ways.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup group, int id) {
                chosen[0] = id == viaTypeSafe.getId() ? JevClient.Provider.TYPESAFE : JevClient.Provider.OPENROUTER;
                showState.run();
            }
        });
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, 0, pad, 0);
        final android.widget.Switch use = new android.widget.Switch(this);
        use.setText(R.string.jev_use);
        use.setTextSize(16);
        use.setChecked(jev.enabled());
        use.setPadding(0, pad / 2, 0, pad / 2);
        wrap.addView(use);
        // The way and the key only matter once Jev is on.
        final LinearLayout keyPart = new LinearLayout(this);
        keyPart.setOrientation(LinearLayout.VERTICAL);
        keyPart.addView(ways);
        keyPart.addView(state);
        keyPart.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        keyPart.setVisibility(jev.enabled() ? View.VISIBLE : View.GONE);
        wrap.addView(keyPart);
        use.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                jev.setEnabled(on);
                keyPart.setVisibility(on ? View.VISIBLE : View.GONE);
                showState.run();
            }
        });
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.jev_settings)
                .setMessage(getString(R.string.jev_settings_body, (int) spent[0], euros(spent[1])))
                .setView(wrap)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (!jev.enabled()) return; // switched off: nothing to save
                        String key = field.getText().toString().trim();
                        jev.setProvider(chosen[0]);
                        if (!key.isEmpty()) jev.setKey(chosen[0], key);
                        Toast.makeText(MainActivity.this, !key.isEmpty() ? getString(R.string.jev_saved)
                                : jev.hasKey() ? getString(R.string.jev_switched, getString(chosen[0] == JevClient.Provider.TYPESAFE
                                        ? R.string.jev_way_typesafe : R.string.jev_way_openrouter))
                                : getString(R.string.jev_key_none), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.jev_remove_key, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        jev.removeKey(chosen[0]);
                        Toast.makeText(MainActivity.this, jev.hasKey() ? R.string.jev_removed_other : R.string.jev_removed,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                });
        AlertDialog dialog = builder.create();
        openDialog = dialog;
        dialog.show();
        shown[0] = dialog;
        showState.run();
    }

    /** The languages Settings offers, as language tags; the names stand in the strings lang_*. */
    private static final String[] LANGUAGES = {"de", "en", "tr", "ru", "ar", "pl", "uk", "fr", "es", "fa", "vi"};
    private static final int[] LANGUAGE_NAMES = {R.string.lang_de, R.string.lang_en, R.string.lang_tr,
            R.string.lang_ru, R.string.lang_ar, R.string.lang_pl, R.string.lang_uk,
            R.string.lang_fr, R.string.lang_es, R.string.lang_fa, R.string.lang_vi};
    private static final int[] THEME_MODES = {UiModeManager.MODE_NIGHT_AUTO, UiModeManager.MODE_NIGHT_NO,
            UiModeManager.MODE_NIGHT_YES};
    /** The design picked in Settings, as its index in THEME_MODES; missing means the system's. */
    private static final String THEME_FILE = "theme.txt";
    /** Set when a change in Settings rebuilds the screen, so Settings opens again right after. */
    private boolean reopenSettings;

    /**
     * Design and language. Both are handed to the system (per-app night mode, per-app language),
     * which keeps them across restarts and rebuilds the screen at once — nothing here waits for
     * a restart. The phone's own settings show the same language choice.
     */
    private void showSettings() {
        scanner.setPaused(true);
        final UiModeManager ui = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        final LocaleManager locales = getSystemService(LocaleManager.class);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, pad / 2, pad, 0);

        Button howTo = new Button(this);
        howTo.setAllCaps(false);
        withIcon(howTo, R.drawable.ic_b_book, getString(R.string.howto_open));
        howTo.setTextColor(getColor(R.color.text));
        howTo.setBackgroundResource(R.drawable.btn_accent);
        Button pfand = new Button(this);
        pfand.setAllCaps(false);
        withIcon(pfand, R.drawable.ic_b_info, getString(R.string.pfand_open));
        pfand.setTextColor(getColor(R.color.text));
        pfand.setBackgroundResource(R.drawable.btn_accent);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        Button statsButton = new Button(this);
        statsButton.setAllCaps(false);
        withIcon(statsButton, R.drawable.ic_b_stats, getString(R.string.stats_open));
        statsButton.setTextColor(getColor(R.color.text));
        statsButton.setBackgroundResource(R.drawable.btn_accent);
        Button learnedButton = sheetButton(R.drawable.ic_h_scan, R.string.learned_open);
        // Jev and About sit here, not under the page: three buttons down there stacked up and
        // had to be scrolled once the page grew. Two to a row, so the page fits on one screen.
        Button jevButton = sheetButton(R.drawable.ic_b_ai, R.string.jev_settings);
        Button aboutButton = sheetButton(R.drawable.ic_b_info, R.string.settings_about);
        Button mapButton = sheetButton(R.drawable.ic_h_shop, R.string.map_open);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.addView(pair(this, howTo, pfand), first);
        wrap.addView(pair(this, statsButton, learnedButton), pp);
        wrap.addView(pair(this, jevButton, aboutButton), pp);
        wrap.addView(mapButton, pp);
        TextView mapNote = new TextView(this);
        mapNote.setText(R.string.map_note);
        mapNote.setTextColor(getColor(R.color.text_dim));
        mapNote.setTextSize(13);
        mapNote.setPadding(0, pad / 4, 0, 0);
        wrap.addView(mapNote);

        final android.widget.Switch sounds = new android.widget.Switch(this);
        sounds.setText(R.string.sound_switch);
        sounds.setTextSize(16);
        sounds.setChecked(soundsOn());
        sounds.setPadding(0, pad / 2, 0, 0);
        wrap.addView(sounds, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sounds.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                store.write(SOUND_FILE, on ? "1" : "0");
            }
        });
        TextView soundNote = new TextView(this);
        soundNote.setText(R.string.sound_note);
        soundNote.setTextColor(getColor(R.color.text_dim));
        soundNote.setTextSize(13);
        wrap.addView(soundNote);

        wrap.addView(heading(R.string.settings_theme));
        int themeNow = 0;
        try {
            themeNow = Integer.parseInt(String.valueOf(store.read(THEME_FILE)).trim());
        } catch (NumberFormatException ignored) {
            // no choice yet: the system's
        }
        final RadioGroup themes = themeRow(this, themeNow >= 0 && themeNow < THEME_MODES.length ? themeNow : 0);
        wrap.addView(themes);

        wrap.addView(heading(R.string.settings_language));
        // A drop-down, not a list of buttons: with eleven languages the list pushed everything below it off screen.
        List<String> langNames = new ArrayList<String>();
        langNames.add(getString(R.string.lang_system));
        for (int name : LANGUAGE_NAMES) langNames.add(getString(name));
        final android.widget.Spinner langs = new android.widget.Spinner(this);
        langs.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, langNames));
        LocaleList chosen = locales.getApplicationLocales();
        int langNow = 0;
        if (!chosen.isEmpty()) {
            String tag = chosen.get(0).getLanguage();
            for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i].equals(tag)) langNow = i + 1;
        }
        langs.setSelection(langNow, false);
        langs.setContentDescription(getString(R.string.settings_language));
        wrap.addView(langs);

        // Everything but English and German came from a machine translation.
        String shown = getResources().getConfiguration().getLocales().get(0).getLanguage();
        if (!shown.equals("en") && !shown.equals("de")) {
            TextView note = new TextView(this);
            note.setText(R.string.machine_translated);
            note.setTextColor(getColor(R.color.text_dim));
            note.setPadding(0, pad / 2, 0, 0);
            wrap.addView(note);
        }

        themes.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup group, int id) {
                int i = id - 1000;
                store.write(THEME_FILE, String.valueOf(i));
                reopenSettings = true;
                ui.setApplicationNightMode(THEME_MODES[i]);
            }
        });
        final int langWas = langNow;
        langs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos == langWas) return;
                reopenSettings = true;
                locales.setApplicationLocales(pos == 0 ? LocaleList.getEmptyLocaleList()
                        : LocaleList.forLanguageTags(LANGUAGES[pos - 1]));
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(wrap);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        howTo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                openLater(new Runnable() {
                    @Override public void run() {
                        showHowTo();
                    }
                });
            }
        });
        pfand.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                openLater(new Runnable() {
                    @Override public void run() {
                        showPfand();
                    }
                });
            }
        });
        mapButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openMapSearch();
            }
        });
        jevButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                openLater(new Runnable() {
                    @Override public void run() {
                        showJevSettings();
                    }
                });
            }
        });
        aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                openLater(new Runnable() {
                    @Override public void run() {
                        showAbout();
                    }
                });
            }
        });
        learnedButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                openLater(new Runnable() {
                    @Override public void run() {
                        showLearned();
                    }
                });
            }
        });
        statsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                openLater(new Runnable() {
                    @Override public void run() {
                        showStats();
                    }
                });
            }
        });
        openDialog = dialog;
        dialog.show();
    }

    /** Marks that the guide has been shown once; a new install shows it on its first start. */
    private static final String HOWTO_FILE = "howto.seen";
    private boolean howToPending;

    /**
     * The short guide: one card per thing the app does, each with its picture, then what Jev
     * (the optional AI suggestion) gets and what goes to Open Food Facts.
     */
    private void showHowTo() {
        store.write(HOWTO_FILE, "1");
        showCards(R.string.howto_title, new int[][]{
                {R.drawable.ic_h_scan, R.string.h_scan_t, R.string.h_scan},
                {R.drawable.ic_g_reuse, R.string.h_new_t, R.string.h_new},
                {R.drawable.ic_g_crate, R.string.h_hand_t, R.string.h_hand},
                {R.drawable.ic_h_list, R.string.h_list_t, R.string.h_list},
                {R.drawable.ic_h_ai, R.string.h_jev_t, R.string.h_jev},
                {R.drawable.ic_h_lock, R.string.h_jev_data_t, R.string.h_jev_data},
                {R.drawable.ic_g_glass, R.string.h_names_t, R.string.h_names},
        }, R.string.h_rules);
    }

    /** The German deposit system in one screen: what carries how much, what carries none, where it goes back. */
    private void showPfand() {
        showCards(R.string.pfand_title, new int[][]{
                {R.drawable.ic_g_logo, R.string.p_single_t, R.string.p_single},
                {R.drawable.ic_g_beer, R.string.p_beer_t, R.string.p_beer},
                {R.drawable.ic_g_reuse, R.string.p_reuse_t, R.string.p_reuse},
                {R.drawable.ic_g_crate, R.string.p_crate_t, R.string.p_crate},
                {R.drawable.ic_g_none, R.string.p_none_t, R.string.p_none},
                {R.drawable.ic_h_shop, R.string.p_return_t, R.string.p_return},
                {R.drawable.ic_h_receipt, R.string.p_receipt_t, R.string.p_receipt},
        }, R.string.p_source);
    }

    /** A window of cards, each a picture, a bold title and a line or two, with a note at the end. */
    private void showCards(int titleText, int[][] cards, int footer) {
        showCards(titleText, cards, footer, null);
    }

    /** As above; {@code back} (if any) opens once the cards are closed, e.g. About again. */
    private void showCards(int titleText, int[][] cards, int footer, final Runnable back) {
        scanner.setPaused(true);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, pad / 2, pad, 0);
        for (int[] card : cards) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundResource(R.drawable.btn_bg);
            row.setPadding((int) (12 * density), (int) (10 * density), (int) (12 * density), (int) (10 * density));
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(card[0]);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams((int) (40 * density), (int) (40 * density));
            ip.setMarginEnd((int) (12 * density));
            row.addView(icon, ip);
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this);
            title.setText(card[1]);
            title.setTextColor(getColor(R.color.text));
            title.setTextSize(15);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            words.addView(title);
            TextView body = new TextView(this);
            body.setText(card[2]);
            body.setTextColor(getColor(R.color.text_dim));
            body.setTextSize(13);
            android.text.util.Linkify.addLinks(body, android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.EMAIL_ADDRESSES);
            words.addView(body);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = (int) (8 * density);
            wrap.addView(row, rp);
        }
        TextView rules = new TextView(this);
        rules.setText(footer);
        rules.setTextColor(getColor(R.color.accent));
        rules.setTextSize(13);
        rules.setPadding(0, (int) (4 * density), 0, (int) (8 * density));
        android.text.util.Linkify.addLinks(rules, android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.EMAIL_ADDRESSES);
        wrap.addView(rules);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(wrap);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleText)
                .setView(scroll)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                        if (back != null) openLater(back);
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    /**
     * What has been counted since this page came into the app (Stats): the sum, bottles scanned
     * and by hand, crates, each deposit amount, the months, learned barcodes and Jev's questions.
     * The bars are drawn by the app itself, no chart library.
     */
    private void showStats() {
        scanner.setPaused(true);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, pad / 2, pad, 0);
        buildStats(this, wrap, stats, rules.learnedCount(), jev.spent(), jev.enabled());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(wrap);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.stats_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.stats_reset, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        openLater(new Runnable() {
                            @Override public void run() {
                                confirmStatsReset();
                            }
                        });
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(stats.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * Everything the user taught, newest first: name, barcode, deposit. A tap renames or forgets
     * one entry; "Forget all" clears them after asking. The shipped list is not shown here.
     */
    private void showLearned() {
        scanner.setPaused(true);
        final List<String> codes = new ArrayList<String>();
        final List<DepositRules.Rule> learnedRules = new ArrayList<DepositRules.Rule>();
        for (Map.Entry<String, DepositRules.Rule> e : rules.learnedList().entrySet()) {
            codes.add(0, e.getKey());
            learnedRules.add(0, e.getValue());
        }
        String[] rows = new String[codes.size()];
        for (int i = 0; i < rows.length; i++) {
            DepositRules.Rule r = learnedRules.get(i);
            String name = hasName(r.name) ? r.name : getString(R.string.unknown_product);
            String value = r.cents == DepositRules.NONE ? getString(R.string.no_deposit_title)
                    : money(r.cents) + (r.crate ? " " + getString(R.string.crate_tag) : "");
            rows[i] = name + "\n" + codes.get(i) + "  ·  " + value;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.learned_open)
                .setPositiveButton(R.string.close, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                });
        if (rows.length == 0) {
            b.setMessage(R.string.learned_empty);
        } else {
            b.setItems(rows, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    final String code = codes.get(which);
                    final DepositRules.Rule r = learnedRules.get(which);
                    openLater(new Runnable() {
                        @Override public void run() {
                            learnedEntry(code, r);
                        }
                    });
                }
            });
            b.setNeutralButton(R.string.forget_all, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    openLater(new Runnable() {
                        @Override public void run() {
                            confirmForgetAll();
                        }
                    });
                }
            });
        }
        final AlertDialog dialog = b.create();
        openDialog = dialog;
        dialog.show();
        // Holding an entry opens the same menu as a tap: rename, deposit, forget.
        if (dialog.getListView() != null) {
            dialog.getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(AdapterView<?> parent, View v, int position, long id) {
                    final String code = codes.get(position);
                    final DepositRules.Rule r = learnedRules.get(position);
                    dialog.dismiss();
                    openLater(new Runnable() {
                        @Override public void run() {
                            learnedEntry(code, r);
                        }
                    });
                    return true;
                }
            });
        }
    }

    /** A new deposit for a taught barcode; list lines of it follow, the statistics too. */
    private void learnedDeposit(final String code, final DepositRules.Rule r, final Runnable after) {
        scanner.setPaused(true);
        final int crateCents = CrateSheet.crateValue(store);
        final int[] values = {DepositRules.REUSABLE_SMALL, DepositRules.REUSABLE,
                DepositRules.SINGLE_USE, crateCents, DepositRules.NONE};
        String[] labels = {getString(R.string.d8), getString(R.string.d15), getString(R.string.d25),
                getString(R.string.d_crate_fmt, money(crateCents)), getString(R.string.no_deposit_title)};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.change_deposit)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        int cents = values[which];
                        boolean crate = which == 3;
                        rules.learn(code, cents, r.name, crate);
                        for (CountSession.Line l : new ArrayList<CountSession.Line>(session.lines())) {
                            if (!l.code.equals(code)) continue;
                            countStats(l.code, l.crate, l.cents, -l.qty);
                            if (cents == DepositRules.NONE) {
                                session.remove(l);
                            } else {
                                countStats(l.code, crate, cents, l.qty);
                                session.setDeposit(l, cents, crate);
                            }
                        }
                        session.save(store);
                        refresh();
                        openLater(after);
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        openLater(after);
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    private void learnedEntry(final String code, final DepositRules.Rule r) {
        scanner.setPaused(true);
        final Runnable backToList = new Runnable() {
            @Override public void run() {
                showLearned();
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(hasName(r.name) ? r.name : getString(R.string.unknown_product))
                .setItems(new String[]{getString(R.string.rename_line), getString(R.string.change_deposit),
                                getString(R.string.forget_code)},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    openLater(new Runnable() {
                                        @Override public void run() {
                                            renameCode(code, r.name, backToList);
                                        }
                                    });
                                } else if (which == 1) {
                                    openLater(new Runnable() {
                                        @Override public void run() {
                                            learnedDeposit(code, r, backToList);
                                        }
                                    });
                                } else {
                                    if (rules.forget(code)) {
                                        Toast.makeText(MainActivity.this, R.string.forgotten, Toast.LENGTH_SHORT).show();
                                    }
                                    openLater(backToList);
                                }
                            }
                        })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        openLater(backToList);
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    /** Forgetting everything taught cannot be undone, so it asks first; the count stays. */
    private void confirmForgetAll() {
        scanner.setPaused(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.forget_all)
                .setMessage(getString(R.string.forget_all_q, rules.learnedCount()))
                .setPositiveButton(R.string.forget_all_yes, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        rules.forgetAll();
                        Toast.makeText(MainActivity.this, R.string.forget_all_done, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    /** Clearing the statistics cannot be undone, so it asks first; the list stays as it is. */
    private void confirmStatsReset() {
        scanner.setPaused(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.stats_reset)
                .setMessage(R.string.stats_reset_q)
                .setPositiveButton(R.string.stats_reset_yes, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        stats.clear();
                        stats.save(store);
                        Toast.makeText(MainActivity.this, R.string.stats_reset_done, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    /** The statistics page's content; static so LayoutCheck can measure it in every language. */
    static void buildStats(Context ctx, LinearLayout wrap, Stats stats, int learned, double[] jevSpent,
            boolean jevShown) {
        float density = ctx.getResources().getDisplayMetrics().density;
        TextView total = new TextView(ctx);
        total.setText(String.format(Locale.GERMANY, "%.2f€", stats.totalCents() / 100.0));
        total.setTextSize(36);
        total.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        total.setTextColor(ctx.getColor(R.color.accent));
        wrap.addView(total);
        String since = stats.since == 0 ? ctx.getString(R.string.stats_since_now)
                : ctx.getString(R.string.stats_since, java.text.DateFormat.getDateInstance(
                        java.text.DateFormat.MEDIUM).format(new java.util.Date(stats.since)));
        wrap.addView(statsText(ctx, since, 13, R.color.text_dim));

        int bottles = stats.bottleCount();
        wrap.addView(statsHeading(ctx, R.string.stats_bottles));
        wrap.addView(statsText(ctx, ctx.getResources().getQuantityString(R.plurals.items, bottles, bottles)
                + "  ·  " + ctx.getString(R.string.stats_split, stats.scanned, stats.byHand), 15, R.color.text));
        wrap.addView(statsBar(ctx, bottles == 0 ? 0 : stats.scanned / (double) bottles));

        wrap.addView(statsHeading(ctx, R.string.stats_per_value));
        if (stats.bottles.isEmpty() && stats.crates.isEmpty()) {
            wrap.addView(statsText(ctx, ctx.getString(R.string.stats_none), 13, R.color.text_dim));
        }
        int most = 1;
        for (int q : stats.bottles.values()) most = Math.max(most, q);
        for (int q : stats.crates.values()) most = Math.max(most, q);
        for (java.util.Map.Entry<Integer, Integer> e : stats.bottles.descendingMap().entrySet()) {
            wrap.addView(statsText(ctx, e.getValue() + " × " + money(e.getKey()) + " = "
                    + money(e.getKey() * e.getValue()), 14, R.color.text));
            wrap.addView(statsBar(ctx, e.getValue() / (double) most));
        }
        for (java.util.Map.Entry<Integer, Integer> e : stats.crates.descendingMap().entrySet()) {
            wrap.addView(statsText(ctx, e.getValue() + " × " + money(e.getKey()) + " "
                    + ctx.getString(R.string.crate_tag) + " = " + money(e.getKey() * e.getValue()), 14, R.color.text));
            wrap.addView(statsBar(ctx, e.getValue() / (double) most));
        }

        // The last six months, oldest on the left.
        wrap.addView(statsHeading(ctx, R.string.stats_months));
        String[] labels = new String[6];
        long[] values = new long[6];
        String[] shown = new String[6];
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.DAY_OF_MONTH, 1);
        c.add(java.util.Calendar.MONTH, -5);
        java.text.SimpleDateFormat name = new java.text.SimpleDateFormat("LLL", Locale.getDefault());
        for (int i = 0; i < 6; i++) {
            Integer v = stats.months.get(Stats.month(c.getTimeInMillis()));
            values[i] = v == null ? 0 : v;
            labels[i] = name.format(c.getTime());
            shown[i] = String.format(Locale.GERMANY, "%d€", Math.round(values[i] / 100.0));
            c.add(java.util.Calendar.MONTH, 1);
        }
        wrap.addView(new StatsChart(ctx, labels, values, shown));

        wrap.addView(statsHeading(ctx, R.string.stats_more));
        wrap.addView(statsText(ctx, ctx.getString(R.string.stats_learned, learned), 14, R.color.text));
        // Jev is opt-in: someone who never switched it on and never asked sees nothing of it here.
        if (jevShown || jevSpent[0] > 0) {
            wrap.addView(statsText(ctx, ctx.getString(R.string.stats_jev, (int) jevSpent[0], euros(jevSpent[1])),
                    14, R.color.text));
        }
        TextView note = statsText(ctx, ctx.getString(R.string.stats_note), 12, R.color.text_dim);
        note.setPadding(0, (int) (12 * density), 0, (int) (8 * density));
        wrap.addView(note);
    }

    private static TextView statsHeading(Context ctx, int text) {
        TextView t = statsText(ctx, ctx.getString(text), 14, R.color.accent);
        t.setPadding(0, (int) (14 * ctx.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static TextView statsText(Context ctx, String text, int sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(ctx.getColor(color));
        return t;
    }

    /** A thin track filled to `share` — the statistics page's bars. */
    private static View statsBar(Context ctx, double share) {
        float density = ctx.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(ctx.getColor(R.color.surface_high));
        track.setCornerRadius(5 * density);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor(ctx.getColor(R.color.accent));
        fill.setCornerRadius(5 * density);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fill, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        clip.setLevel((int) Math.round(Math.max(0, Math.min(1, share)) * 10000));
        View v = new View(ctx);
        v.setBackground(new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track, clip}));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (10 * density));
        lp.topMargin = (int) (4 * density);
        lp.bottomMargin = (int) (6 * density);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView heading(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getColor(R.color.accent));
        t.setTextSize(14);
        t.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("reopen_settings", reopenSettings);
    }

    /**
     * Opens a follow-up window only once the current one is really gone. Started straight from a
     * button handler, the closing window keeps the touch input and the new one looks frozen.
     */
    private void openLater(Runnable open) {
        if (pageCheck != null) return; // the page check opens every window itself
        listView.postDelayed(open, 150);
    }

    // --- page check ----------------------------------------------------------

    /**
     * The windows LayoutCheck cannot build on its own, measured as the app really shows them:
     * each one opened, its texts asked whether they fit (LayoutCheck.walk), closed again. One
     * run per language and font size 1.0 and 1.3; between runs the screen is rebuilt, the
     * progress waits in PAGE_CHECK_FILE ("step problems original-languages"). Nothing is counted
     * or saved; at the end the app's language is what it was. Results: adb logcat -s PfandLayout
     */
    private static final String PAGE_CHECK_FILE = "pagecheck.txt";
    private static final float[] PAGE_CHECK_SCALES = {1.0f, 1.3f};
    /** The saved progress while a page check runs, else null. */
    private String[] pageCheck;

    @Override protected void attachBaseContext(Context base) {
        String[] check = readPageCheck(base);
        if (check != null) {
            android.content.res.Configuration c = new android.content.res.Configuration();
            c.fontScale = PAGE_CHECK_SCALES[Integer.parseInt(check[0]) % PAGE_CHECK_SCALES.length];
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
        pageCheck = check;
    }

    private static String[] readPageCheck(Context ctx) {
        java.io.File f = new java.io.File(ctx.getFilesDir(), PAGE_CHECK_FILE);
        if (!f.exists()) return null;
        try {
            String[] parts = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim().split(" ", 3);
            return parts.length == 3 ? parts : new String[]{parts[0], parts[1], ""};
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** Sets language and font size for run {@code step} and rebuilds the screen; past the last, ends. */
    private void pageCheckNext(int step, int problems, String original) {
        LocaleManager locales = getSystemService(LocaleManager.class);
        if (step >= LANGUAGES.length * PAGE_CHECK_SCALES.length) {
            store.delete(PAGE_CHECK_FILE);
            pageCheck = null;
            android.util.Log.i(LayoutCheck.TAG, "page check done: " + problems + " problem(s) in " + LANGUAGES.length
                    + " languages at font scale 1.0 and 1.3");
            // "" is the phone's own language; forLanguageTags("") would pick a language of its own.
            LocaleList back = original.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(original);
            // A new language rebuilds the screen by itself; calling recreate() as well raced it and
            // the old language stayed on screen.
            if (!back.equals(locales.getApplicationLocales())) locales.setApplicationLocales(back);
            else recreate();
            return;
        }
        store.write(PAGE_CHECK_FILE, step + " " + problems + " " + original);
        LocaleList want = LocaleList.forLanguageTags(LANGUAGES[step / PAGE_CHECK_SCALES.length]);
        if (!want.equals(locales.getApplicationLocales())) locales.setApplicationLocales(want);
        else recreate(); // same language, other font size
    }

    private void pageCheckRun() {
        if (pageCheck == null) return;
        final int step = Integer.parseInt(pageCheck[0]);
        final int[] problems = {Integer.parseInt(pageCheck[1])};
        final String original = pageCheck[2];
        final String where = LANGUAGES[step / PAGE_CHECK_SCALES.length] + " x"
                + PAGE_CHECK_SCALES[step % PAGE_CHECK_SCALES.length] + " ";
        final String[] names = {"disclaimer", "howto", "pfand", "settings", "stats", "learned", "jev",
                "about", "licenses", "privacy", "guide", "crates"};
        final Runnable[] pages = {
                new Runnable() { @Override public void run() { showDisclaimer(); } },
                new Runnable() { @Override public void run() { showHowTo(); } },
                new Runnable() { @Override public void run() { showPfand(); } },
                new Runnable() { @Override public void run() { showSettings(); } },
                new Runnable() { @Override public void run() { showStats(); } },
                new Runnable() { @Override public void run() { showLearned(); } },
                new Runnable() { @Override public void run() { showJevSettings(); } },
                new Runnable() { @Override public void run() { showAbout(); } },
                new Runnable() { @Override public void run() { showLicenses(); } },
                new Runnable() { @Override public void run() { showPrivacy(); } },
                new Runnable() { @Override public void run() { guideByHand(); } },
                new Runnable() { @Override public void run() { showCrates(); } },
        };
        final int[] at = {0};
        final Runnable[] next = {null};
        next[0] = new Runnable() {
            @Override public void run() {
                if (pageCheck == null) return;
                if (openDialog != null) {
                    AlertDialog shown = openDialog;
                    problems[0] += LayoutCheck.walk(shown.getWindow().getDecorView(), where + names[at[0] - 1]);
                    shown.dismiss();
                    openDialog = null;
                } else if (at[0] > 0) {
                    android.util.Log.w(LayoutCheck.TAG, where + names[at[0] - 1] + ": window did not open");
                    problems[0]++;
                }
                if (at[0] == pages.length) {
                    pageCheckNext(step + 1, problems[0], original);
                    return;
                }
                pages[at[0]++].run();
                listView.postDelayed(next[0], 700);
            }
        };
        next[0].run();
    }

    private void showNoDeposit(final String code, String name) {
        feedback(NO_DEPOSIT);
        if (openDialog != null && openDialog.isShowing()) return;
        scanner.setPaused(true);
        String shown = shownForDialog(code, name);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.no_deposit_title)
                .setMessage(shown + "\n\n" + getString(R.string.no_deposit_toast))
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.change_this, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        openLater(new Runnable() {
                            @Override public void run() {
                                askAboutNewCode(code);
                            }
                        });
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                        scanner.forgetLastCode();
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    /** Fills in a proper product name once the network answers; never blocks the count. */
    private void fetchNameLater(final String code) {
        lookup.lookup(code, new ProductLookup.Callback() {
            @Override public void onName(String forCode, String name) {
                if (session.renameCode(forCode, name)) {
                    if (rules.isLearned(forCode)) {
                        DepositRules.Rule r = rules.lookup(forCode);
                        if (r != null && (r.name == null || r.name.isEmpty()
                                || r.name.equals(getString(R.string.unknown_product)))) {
                            rules.learn(forCode, r.cents, name);
                        }
                    }
                    refresh();
                }
            }
        });
    }

    // --- counting ----------------------------------------------------------

    /**
     * The picture questions without a barcode — for a bottle whose code will not scan or is
     * torn off. The answer is counted like the +0,xx€ buttons; nothing is learned.
     */
    private void guideByHand() {
        if (openDialog != null && openDialog.isShowing()) return;
        scanner.setPaused(true);
        LinearLayout host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        host.setPadding(pad, pad / 2, pad, pad / 2);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(host);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.guide_title)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        DepositGuide guide = new DepositGuide(this, host, new DepositGuide.Listener() {
            @Override public void onChosen(int cents) {
                if (cents == DepositRules.NONE) {
                    Toast.makeText(MainActivity.this, R.string.no_deposit_toast, Toast.LENGTH_SHORT).show();
                } else {
                    addByHand(cents);
                }
                dialog.dismiss();
            }
            @Override public void onLeave() {
                dialog.dismiss();
            }
        });
        openDialog = dialog;
        dialog.show();
        guide.start();
    }

    private void addByHand(int cents) {
        String name = cents == DepositRules.SINGLE_USE
                ? getString(R.string.manual_25) : getString(R.string.manual_8);
        add("manual-" + cents, name, cents);
    }

    private void add(String code, String name, int cents) {
        add(code, name, cents, 1, false);
    }

    /** `qty` bottles (or crates) of one kind; the list, the statistics and the feedback in one go. */
    private void add(String code, String name, int cents, int qty, boolean crate) {
        String shown = name == null || name.isEmpty() ? getString(R.string.unknown_product) : name;
        store.delete(UNDO_FILE);
        justCounted = session.add(code, shown, cents, qty, crate);
        countStats(code, crate, cents, qty);
        session.save(store);
        refresh();
        listView.setSelection(oldestFirst ? adapter.getCount() - 1 : 0);
        listView.removeCallbacks(fadeJustCounted);
        listView.postDelayed(fadeJustCounted, 2500);
        feedback(COUNTED);
        String what = qty > 1 ? qty + " × " + shown : shown;
        showScanned(what, cents);
        speak(getString(R.string.say_counted, what, money(cents), money(session.totalCents())));
    }

    /** A barcode is a string of digits; the buttons' lines are "manual-…", "crate", "pack:…". */
    private static boolean isBarcode(String code) {
        return code.matches("\\d+");
    }

    private void countStats(String code, boolean crate, int cents, int qty) {
        stats.count(isBarcode(code), crate, cents, qty);
        stats.save(store);
    }

    /**
     * "+Crate": the crate deposit, one empty crate, a full crate with its bottles, own packs and
     * "help me decide", all in one window (CrateSheet). Counting closes it.
     */
    private void showCrates() {
        if (openDialog != null && openDialog.isShowing()) return;
        scanner.setPaused(true);
        LinearLayout host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        host.setPadding(pad, pad / 2, pad, pad / 2);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(host);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.c_title)
                .setView(scroll)
                .setNegativeButton(R.string.close, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        CrateSheet sheet = new CrateSheet(this, store, host, new CrateSheet.Listener() {
            @Override public void onEmptyCrate(int crateCents) {
                add("crate", getString(R.string.crate_name), crateCents, 1, true);
                dialog.dismiss();
            }

            @Override public void onFullCrate(CrateSheet.Pack pack, int bottleCents, int crateCents) {
                if (crateCents > 0) {
                    session.add("crate", getString(R.string.crate_name), crateCents, 1, true);
                    countStats("crate", true, crateCents, 1);
                }
                add("pack:" + pack.key, pack.label(), bottleCents, pack.bottles, false);
                dialog.dismiss();
            }
        });
        openDialog = dialog;
        dialog.show();
        sheet.showMain();
    }

    /** Names what was just counted, right where the eyes are — then back to the aiming hint. */
    private void showScanned(String name, int cents) {
        hintView.setText(getString(R.string.scanned_fmt, name, money(cents)));
        hintView.removeCallbacks(resetHint);
        hintView.postDelayed(resetHint, 2500);
    }

    private final Runnable resetHint = new Runnable() {
        @Override public void run() {
            hintView.setText(R.string.aim_hint);
        }
    };

    /** What a scan came to, told apart by ear and by hand without looking at the screen. */
    private static final int COUNTED = 0, NO_DEPOSIT = 1, UNKNOWN = 2;

    /** Counted: one short beep and buzz. No deposit: two. Unknown, a question follows: one long. */
    /**
     * "1" once sounds are switched on in Settings; missing means off, for everyone alike. Not on
     * by itself with a screen reader: the beeps talked over TalkBack's announcements.
     */
    private static final String SOUND_FILE = "sound.txt";

    private boolean soundsOn() {
        String v = store.read(SOUND_FILE);
        return v != null && v.trim().equals("1");
    }

    /** Vibration always; the three sounds only when soundsOn(). */
    private void feedback(int kind) {
        if (tone != null && soundsOn()) {
            try {
                if (kind == NO_DEPOSIT) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250);
                else if (kind == UNKNOWN) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 400);
                else tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90);
            } catch (RuntimeException ignored) {
            }
        }
        if (kind == NO_DEPOSIT) vibrate(VibrationEffect.createWaveform(new long[]{0, 35, 90, 35}, -1));
        else if (kind == UNKNOWN) vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE));
        else buzz();
    }

    private void buzz() {
        vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private void vibrate(VibrationEffect effect) {
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                vibrator.vibrate(effect);
            } catch (RuntimeException ignored) {
                vibrator = null; // a refused buzz must never cost a scan
            }
        }
    }

    /** True while a screen reader (TalkBack) is on: then the app speaks what others see. */
    private boolean screenReaderOn() {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
    }

    /** Says a line through the screen reader; without one nothing happens. */
    @SuppressWarnings("deprecation")
    private void speak(String text) {
        if (screenReaderOn()) hintView.announceForAccessibility(text);
    }

    /** The 13 digits help sighted users compare with the label; read aloud they are only noise. */
    private String shownForDialog(String code, String name) {
        boolean hasName = name != null && !name.isEmpty();
        if (screenReaderOn()) return hasName ? name : getString(R.string.unknown_product);
        return hasName ? name + "\n" + code : code;
    }

    private void refresh() {
        totalView.setText(money(session.totalCents()));
        String breakdown = session.breakdown(getString(R.string.crate_tag));
        breakdownView.setText(breakdown);
        breakdownView.setVisibility(breakdown.isEmpty() ? View.GONE : View.VISIBLE);
        countView.setText(countText());
        shareButton.setVisibility(session.isEmpty() ? View.GONE : View.VISIBLE);
        sortButton.setVisibility(session.lines().size() < 2 ? View.GONE : View.VISIBLE);
        withIcon(sortButton, R.drawable.ic_b_sort, getString(oldestFirst ? R.string.sort_oldest : R.string.sort_newest));
        // Red while it clears the list; plain grey while it only brings a cleared list back.
        boolean undo = session.isEmpty() && store.exists(UNDO_FILE);
        withIcon(resetButton, undo ? R.drawable.ic_b_undo : R.drawable.ic_b_trash,
                getString(undo ? R.string.btn_undo_reset : R.string.btn_reset));
        resetButton.setBackgroundResource(undo ? R.drawable.btn_bg : R.drawable.btn_bad);
        adapter.notifyDataSetChanged();
    }

    /** "12 items · 2 crates": bottles and crates apart, since a crate is no bottle. */
    private String countText() {
        int items = session.itemCount(), crates = session.crateCount();
        if (items == 0 && crates == 0) return getString(R.string.items_none);
        String b = getResources().getQuantityString(R.plurals.items, items, items);
        String c = getResources().getQuantityString(R.plurals.crates, crates, crates);
        return crates == 0 ? b : items == 0 ? c : b + " · " + c;
    }

    private void editLine(final CountSession.Line line) {
        final List<String> labels = new ArrayList<String>();
        final List<Integer> actions = new ArrayList<Integer>();
        labels.add(getString(R.string.change_deposit));
        actions.add(2);
        labels.add(getString(R.string.rename_line));
        actions.add(5);
        labels.add(getString(R.string.remove_line));
        actions.add(3);
        if (rules.isLearned(line.code)) {
            labels.add(getString(R.string.forget_code));
            actions.add(4);
        }

        scanner.setPaused(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(line.name)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (actions.get(which)) {
                            case 2:
                                openLater(new Runnable() {
                                    @Override public void run() {
                                        changeDeposit(line);
                                    }
                                });
                                return;
                            case 5:
                                openLater(new Runnable() {
                                    @Override public void run() {
                                        renameLine(line);
                                    }
                                });
                                return;
                            case 3:
                                session.remove(line);
                                countStats(line.code, line.crate, line.cents, -line.qty);
                                break;
                            case 4:
                                if (rules.forget(line.code)) {
                                    Toast.makeText(MainActivity.this, R.string.forgotten,
                                            Toast.LENGTH_SHORT).show();
                                }
                                break;
                            default:
                                break;
                        }
                        session.save(store);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                        scanner.forgetLastCode();
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    /**
     * The user's own name for a line, kept for the next scan when the barcode was taught. Stays
     * on the phone like everything the user teaches. An empty field goes back to "Unknown product".
     */
    private void renameLine(final CountSession.Line line) {
        renameCode(line.code, line.name, null);
    }

    /** Renames every list line of `code` and, when it was taught, the learned entry too. */
    private void renameCode(final String code, String current, final Runnable after) {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setHint(R.string.name_field);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        if (hasName(current)) {
            field.setText(current);
            field.setSelection(field.length());
        }
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(field);
        scanner.setPaused(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rename_line)
                .setView(wrap)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String typed = field.getText().toString().trim();
                        String name = typed.isEmpty() ? getString(R.string.unknown_product) : typed;
                        for (CountSession.Line l : session.lines()) {
                            if (l.code.equals(code)) l.name = name;
                        }
                        if (rules.isLearned(code)) {
                            DepositRules.Rule r = rules.lookup(code);
                            rules.learn(code, r.cents, name, r.crate);
                        }
                        session.save(store);
                        refresh();
                        if (after != null) openLater(after);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                        scanner.forgetLastCode();
                    }
                })
                .create();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        openDialog = dialog;
        dialog.show();
        field.requestFocus();
    }

    private void changeDeposit(final CountSession.Line line) {
        final int crateCents = CrateSheet.crateValue(store);
        final int[] values = {DepositRules.REUSABLE_SMALL, DepositRules.REUSABLE,
                DepositRules.SINGLE_USE, crateCents};
        String[] labels = {getString(R.string.d8), getString(R.string.d15),
                getString(R.string.d25), getString(R.string.d_crate_fmt, money(crateCents))};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.change_deposit)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        int cents = values[which];
                        boolean crate = which == 3;
                        countStats(line.code, line.crate, line.cents, -line.qty);
                        countStats(line.code, crate, cents, line.qty);
                        session.setDeposit(line, cents, crate);
                        // A correction is also a lesson: remember it for the next scan.
                        if (isBarcode(line.code)) rules.learn(line.code, cents, line.name, crate);
                        session.save(store);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                        scanner.forgetLastCode();
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    /**
     * There is only ever the current list. Reset clears it at once, without a question — a
     * tap too many is undone with the same button, which reads "Undo reset" until the next
     * bottle is counted. The cleared list waits in undo.json, so the undo survives a restart.
     */
    private void resetOrUndo() {
        if (session.isEmpty()) {
            String kept = store.read(UNDO_FILE);
            if (kept == null) {
                Toast.makeText(this, R.string.already_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                session = CountSession.fromJson(new JSONArray(kept));
            } catch (JSONException e) {
                session = new CountSession();
            }
            store.delete(UNDO_FILE);
            session.save(store);
            refresh();
            Toast.makeText(this, R.string.reset_undone, Toast.LENGTH_SHORT).show();
            return;
        }
        store.write(UNDO_FILE, session.toJson().toString());
        session.clear();
        session.save(store);
        refresh();
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
    }

    /**
     * Where the shipped knowledge comes from — the Open Database License asks for the credit,
     * and the two counts answer the question the list itself raises: how much does it know?
     */
    private void showAbout() {
        scanner.setPaused(true);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, pad / 2, pad, 0);
        TextView body = new TextView(this);
        body.setText(getString(R.string.about_body, rules.shippedCount(), rules.learnedCount())
                + "\n\n" + getString(R.string.about_vibe));
        body.setTextColor(getColor(R.color.text));
        body.setTextSize(15);
        wrap.addView(body);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = (int) (12 * density);
        Button privacy = sheetButton(R.drawable.ic_h_lock, R.string.privacy_open);
        wrap.addView(privacy, bp);
        Button report = sheetButton(R.drawable.ic_b_book, R.string.report_translation);
        wrap.addView(report, bp);
        Button licenses = sheetButton(R.drawable.ic_b_info, R.string.licenses_open);
        wrap.addView(licenses, bp);
        TextView welcome = new TextView(this);
        welcome.setText(R.string.translations_welcome);
        welcome.setTextColor(getColor(R.color.text_dim));
        welcome.setTextSize(13);
        welcome.setPadding(0, (int) (6 * density), 0, (int) (8 * density));
        wrap.addView(welcome);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(wrap);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                    }
                })
                .create();
        privacy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                openLater(new Runnable() {
                    @Override public void run() {
                        showPrivacy();
                    }
                });
            }
        });
        report.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                reportTranslation();
            }
        });
        licenses.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                openLater(new Runnable() {
                    @Override public void run() {
                        showLicenses();
                    }
                });
            }
        });
        openDialog = dialog;
        dialog.show();
    }

    /** Privacy and Licenses are opened from About; closing them leads back there. */
    private final Runnable backToAbout = new Runnable() {
        @Override public void run() {
            showAbout();
        }
    };

    /**
     * Who made what and under which license, then the two license texts in full, as the
     * Apache License asks (section 4a) and as the GPL is meant to travel. The texts are the
     * English originals from apache.org and gnu.org; only the introduction is translated.
     */
    private void showLicenses() {
        scanner.setPaused(true);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, pad / 2, pad, pad / 2);
        TextView intro = new TextView(this);
        intro.setText(getString(R.string.lic_intro) + "\n\n" + getString(R.string.lic_originals));
        intro.setTextColor(getColor(R.color.text));
        intro.setTextSize(15);
        android.text.util.Linkify.addLinks(intro, android.text.util.Linkify.WEB_URLS);
        wrap.addView(intro);
        for (int raw : new int[]{R.raw.license_apache2, R.raw.license_gpl3}) {
            TextView text = new TextView(this);
            text.setText(readRaw(raw));
            text.setTextColor(getColor(R.color.text_dim));
            text.setTextSize(11);
            text.setTypeface(android.graphics.Typeface.MONOSPACE);
            text.setTextDirection(View.TEXT_DIRECTION_LTR);
            text.setPadding(0, (int) (16 * density), 0, 0);
            wrap.addView(text);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(wrap);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.licenses_open)
                .setView(scroll)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        if (openDialog == d) openDialog = null;
                        scanner.setPaused(false);
                        openLater(backToAbout);
                    }
                })
                .create();
        openDialog = dialog;
        dialog.show();
    }

    /** A text file from res/raw, as it is (UTF-8). */
    private String readRaw(int id) {
        try (java.io.InputStream in = getResources().openRawResource(id)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** A full-width button with a picture, as in Settings. */
    /** Marks a half-width button: its label may take two lines (LayoutCheck allows that). */
    static final String TILE = "tile";

    /** The three design choices in one row; the chosen one (0 system, 1 light, 2 dark) is ticked. */
    static RadioGroup themeRow(Context ctx, int now) {
        RadioGroup themes = new RadioGroup(ctx);
        themes.setOrientation(LinearLayout.HORIZONTAL); // one row instead of three
        // Aligned by baseline, a label that wraps pushed its button down and the row cut off its second line.
        themes.setBaselineAligned(false);
        int[] themeNames = {R.string.theme_system, R.string.theme_light, R.string.theme_dark};
        for (int i = 0; i < themeNames.length; i++) {
            RadioButton b = new RadioButton(ctx);
            b.setId(1000 + i);
            b.setText(themeNames[i]);
            // A long word ("Systemstandard") is split at a syllable with a hyphen, not anywhere.
            b.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_FULL);
            themes.addView(b, new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        themes.check(1000 + now);
        return themes;
    }

    /** Two buttons side by side, equally wide and equally tall. */
    static LinearLayout pair(Context ctx, Button a, Button b) {
        // Both as tall as the taller one. MATCH_PARENT did that too, but a label that shrinks to
        // fit (auto-size) came out a few pixels taller than the row and lost its bottom edge.
        LinearLayout row = new LinearLayout(ctx) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                int tallest = 0;
                for (int i = 0; i < getChildCount(); i++) tallest = Math.max(tallest, getChildAt(i).getMeasuredHeight());
                for (int i = 0; i < getChildCount(); i++) {
                    View c = getChildAt(i);
                    c.measure(MeasureSpec.makeMeasureSpec(c.getMeasuredWidth(), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY));
                }
                setMeasuredDimension(getMeasuredWidth(), tallest + getPaddingTop() + getPaddingBottom());
            }
        };
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        int gap = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        la.setMarginEnd(gap / 2);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lb.setMarginStart(gap / 2);
        // A little smaller and with less side padding, so long labels (French, Russian at large
        // font) stay within two lines.
        for (Button t : new Button[]{a, b}) {
            t.setTag(TILE);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            // At a large font size long labels shrink to stay within two lines, and a long word
            // is split at a syllable with a hyphen.
            t.setMaxLines(2);
            t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_FULL);
            t.setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            t.setPadding(gap, t.getPaddingTop(), gap, t.getPaddingBottom());
            // The picture was sized for the old letters (withIcon); shrink it along.
            if (t.getText() instanceof android.text.Spanned) {
                android.text.Spanned text = (android.text.Spanned) t.getText();
                int size = Math.round(t.getTextSize() * 1.25f);
                for (android.text.style.ImageSpan span : text.getSpans(0, text.length(),
                        android.text.style.ImageSpan.class)) {
                    span.getDrawable().setBounds(0, 0, size, size);
                }
                t.setText(text);
            }
        }
        row.addView(a, la);
        row.addView(b, lb);
        return row;
    }

    private Button sheetButton(int icon, int text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        withIcon(b, icon, getString(text));
        b.setTextColor(getColor(R.color.text));
        b.setBackgroundResource(R.drawable.btn_accent);
        return b;
    }

    /** Where the source code lives; the issue pages answer only once the repository is public. */
    static final String REPO_URL = "https://github.com/passport0819/pfand-counter";

    /**
     * Opens a new issue on GitHub with the shown language filled in. Nothing is sent by the app:
     * the browser shows the form, and the user decides whether to send it.
     */
    private void reportTranslation() {
        String lang = getResources().getConfiguration().getLocales().get(0).toLanguageTag();
        String body = "Language: " + lang + "\n\nWhere in the app:\n\nWhat it says:\n\nWhat it should say:\n";
        android.net.Uri uri = android.net.Uri.parse(REPO_URL + "/issues/new").buildUpon()
                .appendQueryParameter("title", "Translation mistake (" + lang + ")")
                .appendQueryParameter("body", body)
                .build();
        openOutside(new Intent(Intent.ACTION_VIEW, uri), R.string.no_browser);
    }

    /** Hands an address to another app (browser, map); says so plainly if there is none. */
    private void openOutside(Intent intent, int missing) {
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, missing, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * "Supermarket nearby": the phone's map app searches for supermarkets. The app itself never
     * learns where the phone is and needs no location permission; the map app does the finding.
     */
    private void openMapSearch() {
        android.net.Uri uri = android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(getString(R.string.map_query)));
        openOutside(new Intent(Intent.ACTION_VIEW, uri), R.string.no_map_app);
    }

    /** The privacy notice, card by card; the same text stands in PRIVACY.md in the repository. */
    private void showPrivacy() {
        showCards(R.string.privacy_title, new int[][]{
                {R.drawable.ic_h_lock, R.string.pv_short_t, R.string.pv_short},
                {R.drawable.ic_h_scan, R.string.pv_camera_t, R.string.pv_camera},
                {R.drawable.ic_g_glass, R.string.pv_off_t, R.string.pv_off},
                {R.drawable.ic_h_ai, R.string.pv_jev_t, R.string.pv_jev},
                {R.drawable.ic_b_share, R.string.pv_out_t, R.string.pv_out},
                {R.drawable.ic_h_list, R.string.pv_stored_t, R.string.pv_stored},
                {R.drawable.ic_g_none, R.string.pv_not_t, R.string.pv_not},
                {R.drawable.ic_b_info, R.string.pv_rights_t, R.string.pv_rights},
        }, R.string.pv_contact, backToAbout);
    }

    /**
     * The count as plain text for any app that takes it — a messenger, notes, e-mail: the total,
     * the breakdown by deposit, then line by line. Nothing leaves the phone unless the user picks
     * where it goes.
     */
    private void shareCount() {
        StringBuilder text = new StringBuilder()
                .append(getString(R.string.share_intro)).append("\n\n")
                .append(getString(R.string.total_label)).append(": ").append(money(session.totalCents()))
                .append(" (").append(countText()).append(")\n")
                .append(session.breakdown(getString(R.string.crate_tag))).append("\n");
        for (CountSession.Line line : session.lines()) {
            String name = line.name == null || line.name.isEmpty() ? getString(R.string.unknown_product) : line.name;
            text.append("\n").append(line.qty).append(" × ").append(name).append(" · ")
                    .append(money(line.cents)).append(" = ").append(money(line.value()));
        }
        text.append("\n\n").append(getString(R.string.share_footer));
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(send, getString(R.string.share_title)));
    }

    /**
     * Screen readers read the arrow in "Not sure? ›" out loud; the spoken label leaves it off.
     * Called again whenever such a button changes its text.
     */
    /**
     * A small picture in front of a button's words, so the button reads without the language too.
     * The picture sits in the text itself, so it stays next to centred words, follows right-to-left
     * order and shrinks with them; screen readers hear only the words.
     */
    void withIcon(TextView v, int icon, CharSequence label) {
        withIcon(this, v, icon, label);
    }

    static void withIcon(Context ctx, TextView v, int icon, CharSequence label) {
        android.graphics.drawable.Drawable d = ctx.getDrawable(icon).mutate();
        int size = Math.round(v.getTextSize() * 1.25f);
        d.setBounds(0, 0, size, size);
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder("\u00A0\u00A0").append(label);
        text.setSpan(new android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(text);
        v.setContentDescription(label);
    }

    static void speakPlain(TextView v) {
        v.setContentDescription(v.getText().toString().replace("›", "").replace("‹", "").trim());
    }

    /** German writing, as asked for: 0,15€ — comma, symbol after the number. */
    static String money(int cents) {
        return String.format(Locale.GERMANY, "%.2f€", cents / 100.0);
    }

    // --- list --------------------------------------------------------------

    private void afterQuantityChange() {
        session.save(store);
        refresh();
        buzz();
    }

    /** Order of the list: false = the line scanned last on top, true = at the bottom. Kept in ORDER_FILE. */
    private boolean oldestFirst;
    private static final String ORDER_FILE = "order.txt";
    /** The line the last scan or tap went to, lit up until fadeJustCounted runs. */
    private CountSession.Line justCounted;
    private final Runnable fadeJustCounted = new Runnable() {
        @Override public void run() {
            justCounted = null;
            adapter.notifyDataSetChanged();
        }
    };

    /** The session keeps the last-scanned line first; the other order only reverses the view. */
    private CountSession.Line lineAt(int position) {
        List<CountSession.Line> lines = session.lines();
        return lines.get(oldestFirst ? lines.size() - 1 - position : position);
    }

    private void toggleOrder() {
        oldestFirst = !oldestFirst;
        store.write(ORDER_FILE, oldestFirst ? "oldest" : "newest");
        refresh();
        listView.setSelection(oldestFirst ? adapter.getCount() - 1 : 0);
    }

    private class LineAdapter extends BaseAdapter {
        @Override public int getCount() {
            return session.lines().size();
        }

        @Override public Object getItem(int position) {
            return lineAt(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this).inflate(R.layout.row, parent, false);
            }
            CountSession.Line line = lineAt(position);
            // The line the last scan went to lights up for a moment: did that bottle register?
            row.setBackgroundColor(line == justCounted ? getColor(R.color.accent_dim) : android.graphics.Color.TRANSPARENT);
            ((TextView) row.findViewById(R.id.row_qty)).setText(line.qty + "x");
            ((TextView) row.findViewById(R.id.row_name)).setText(line.name);
            String origin = isBarcode(line.code) ? line.code : getString(R.string.added_by_hand);
            if (line.crate) origin += " · " + getString(R.string.crate_name);
            ((TextView) row.findViewById(R.id.row_sub))
                    .setText(origin + " · " + money(line.cents) + " " + getString(R.string.each));
            ((TextView) row.findViewById(R.id.row_value)).setText(money(line.value()));
            row.setContentDescription(line.qty + " × " + line.name + ", " + money(line.cents) + " "
                    + getString(R.string.each) + ", " + money(line.value()));
            row.findViewById(R.id.row_minus).setContentDescription(getString(R.string.qty_minus) + ": " + line.name);
            row.findViewById(R.id.row_plus).setContentDescription(getString(R.string.qty_plus) + ": " + line.name);

            final CountSession.Line edited = line;
            row.findViewById(R.id.row_plus).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    edited.qty++;
                    countStats(edited.code, edited.crate, edited.cents, 1);
                    justCounted = edited;
                    listView.removeCallbacks(fadeJustCounted);
                    listView.postDelayed(fadeJustCounted, 2500);
                    afterQuantityChange();
                }
            });
            row.findViewById(R.id.row_minus).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    edited.qty--;
                    countStats(edited.code, edited.crate, edited.cents, -1);
                    if (edited.qty <= 0) session.remove(edited);
                    afterQuantityChange();
                }
            });
            return row;
        }
    }
}
